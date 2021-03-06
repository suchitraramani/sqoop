/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sqoop.mapreduce.db.netezza;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.sqoop.config.ConfigurationHelper;
import org.apache.sqoop.io.NamedFifo;
import org.apache.sqoop.lib.DelimiterSet;
import org.apache.sqoop.manager.DirectNetezzaManager;
import org.apache.sqoop.mapreduce.AutoProgressMapper;
import org.apache.sqoop.mapreduce.db.DBConfiguration;
import org.apache.sqoop.util.PerfCounters;
import org.apache.sqoop.util.TaskId;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netezza import mapper using external tables.
 */
public abstract class NetezzaExternalTableImportMapper<K, V> extends
  AutoProgressMapper<Integer, NullWritable, K, V> {
  /**
   * Create a named FIFO, and start Netezza import connected to that FIFO. A
   * File object representing the FIFO is in 'fifoFile'.
   */

  private Configuration conf;
  @VisibleForTesting
  DBConfiguration dbc;
  @VisibleForTesting
  File fifoFile;
  private int numMappers;
  private Connection con;
  private BufferedReader recordReader;
  public static final Log LOG = LogFactory
    .getLog(NetezzaExternalTableImportMapper.class.getName());
  private NetezzaJDBCStatementRunner extTableThread;
  private PerfCounters counter;
  @VisibleForTesting
  File taskAttemptDir = null;

  private AtomicBoolean jdbcFailed = new AtomicBoolean(false);

  private String getSqlStatement(int myId) throws IOException {

    char fd = (char) conf.getInt(DelimiterSet.OUTPUT_FIELD_DELIM_KEY, ',');
    char qc = (char) conf.getInt(DelimiterSet.OUTPUT_ENCLOSED_BY_KEY, 0);
    char ec = (char) conf.getInt(DelimiterSet.OUTPUT_ESCAPED_BY_KEY, 0);

    String nullValue = conf.get(DirectNetezzaManager.NETEZZA_NULL_VALUE);


    int errorThreshold = conf.getInt(
      DirectNetezzaManager.NETEZZA_ERROR_THRESHOLD_OPT, 1);
    String logDir = conf.get(DirectNetezzaManager.NETEZZA_LOG_DIR_OPT);
    String[] cols = dbc.getInputFieldNames();
    String inputConds = dbc.getInputConditions();
    StringBuilder sqlStmt = new StringBuilder(2048);

    sqlStmt.append("CREATE EXTERNAL TABLE '");
    sqlStmt.append(fifoFile.getAbsolutePath());
    sqlStmt.append("' USING (REMOTESOURCE 'JDBC' ");
    sqlStmt.append(" BOOLSTYLE 'T_F' ");
    sqlStmt.append(" CRINSTRING FALSE ");
    sqlStmt.append(" DELIMITER ");
    sqlStmt.append(Integer.toString(fd));
    sqlStmt.append(" ENCODING 'internal' ");
    if (ec > 0) {
      sqlStmt.append(" ESCAPECHAR '\\' ");
    }
    sqlStmt.append(" FORMAT 'Text' ");
    sqlStmt.append(" INCLUDEZEROSECONDS TRUE ");
    sqlStmt.append(" NULLVALUE '");
    if (nullValue != null) {
      sqlStmt.append(nullValue);
    } else {
      sqlStmt.append("null");
    }
    sqlStmt.append("' ");
    if (qc > 0) {
      switch (qc) {
        case '\'':
          sqlStmt.append(" QUOTEDVALUE SINGLE ");
          break;
        case '\"':
          sqlStmt.append(" QUOTEDVALUE DOUBLE ");
          break;
        default:
          LOG.warn("Unsupported enclosed by character: " + qc + " - ignoring.");
      }
    }

    sqlStmt.append(" MAXERRORS ").append(errorThreshold);


    sqlStmt.append(") AS SELECT ");
    if (cols == null || cols.length == 0) {
      sqlStmt.append('*');
    } else {
      sqlStmt.append(cols[0]).append(' ');
      for (int i = 1; i < cols.length; ++i) {
        sqlStmt.append(',').append(cols[i]);
      }
    }
    sqlStmt.append(" FROM ").append(dbc.getInputTableName()).append(' ');
    sqlStmt.append("WHERE (DATASLICEID % ");
    sqlStmt.append(numMappers).append(") = ").append(myId);
    if (inputConds != null && inputConds.length() > 0) {
      sqlStmt.append(" AND ( ").append(inputConds).append(')');
    }

    String stmt = sqlStmt.toString();
    LOG.debug("SQL generated for external table import for data slice " + myId
      + "=" + stmt);
    return stmt;
  }

  private void initNetezzaExternalTableImport(int myId) throws IOException {

    if (taskAttemptDir == null) {
      taskAttemptDir = TaskId.getLocalWorkPath(conf);
    }
    this.fifoFile = new File(taskAttemptDir, ("nzexttable-" + myId + ".txt"));
    String filename = fifoFile.toString();
    NamedFifo nf;
    // Create the FIFO itself.
    try {
      nf = new NamedFifo(this.fifoFile);
      nf.create();
    } catch (IOException ioe) {
      // Command failed.
      LOG.error("Could not create FIFO file " + filename);
      this.fifoFile = null;
      throw new IOException(
        "Could not create FIFO for netezza external table import", ioe);
    }
    String sqlStmt = getSqlStatement(myId);
    boolean cleanup = false;
    try {
      con = dbc.getConnection();
      extTableThread = new NetezzaJDBCStatementRunner(jdbcFailed,
        con, sqlStmt);
    } catch (SQLException sqle) {
      cleanup = true;
      throw new IOException(sqle);
    } catch (ClassNotFoundException cnfe) {
      throw new IOException(cnfe);
    } finally {
      if (con != null && cleanup) {
        try {
          con.close();
        } catch (Exception e) {
          LOG.debug("Exception closing connection " + e.getMessage());
        }
      }
      con = null;
    }
    extTableThread.start();
    // We need to start the reader end first

    final String encoding = conf
        .get(DirectNetezzaManager.NETEZZA_TABLE_ENCODING_OPT);
    recordReader = new BufferedReader(new InputStreamReader(
      new FileInputStream(nf.getFile()), (null == encoding ? "UTF-8" : encoding)));
  }

  abstract protected void writeRecord(Text text, Context context)
    throws IOException, InterruptedException;

  public void map(Integer dataSliceId, NullWritable val, Context context)
    throws IOException, InterruptedException {
    conf = context.getConfiguration();


    if (dbc == null) { // need to be able to mock in tests
      dbc = new DBConfiguration(conf);
    }
    numMappers = ConfigurationHelper.getConfNumMaps(conf);
    char rd = (char) conf.getInt(DelimiterSet.OUTPUT_RECORD_DELIM_KEY, '\n');
    initNetezzaExternalTableImport(dataSliceId);
    counter = new PerfCounters();
    counter.startClock();
    Text outputRecord = new Text();
    try {
      String inputRecord = recordReader.readLine();
      while (inputRecord != null) {
        // Fail fast if there was an error during JDBC operation
        if (jdbcFailed.get()) {
          break;
        }
        outputRecord.set(inputRecord + rd);
        // May be we should set the output to be String for faster performance
        // There is no real benefit in changing it to Text and then
        // converting it back in our case
        writeRecord(outputRecord, context);
        counter.addBytes(1 + inputRecord.length());
        inputRecord = recordReader.readLine();
      }
    } finally {
      recordReader.close();
      extTableThread.join();
      counter.stopClock();
      LOG.info("Transferred " + counter.toString());
      if (extTableThread.hasExceptions()) {
        extTableThread.printException();
        throw new IOException(extTableThread.getException());
      }
    }
  }

}

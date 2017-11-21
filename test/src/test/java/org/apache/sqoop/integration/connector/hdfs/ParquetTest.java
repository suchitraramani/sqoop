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
package org.apache.sqoop.integration.connector.hdfs;

import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.sqoop.connector.common.SqoopAvroUtils;
import org.apache.sqoop.connector.common.SqoopIDFUtils;
import org.apache.sqoop.connector.hdfs.configuration.ToFormat;
import org.apache.sqoop.connector.hdfs.hdfsWriter.HdfsParquetWriter;
import org.apache.sqoop.model.MJob;
import org.apache.sqoop.model.MLink;
import org.apache.sqoop.schema.Schema;
import org.apache.sqoop.schema.type.DateTime;
import org.apache.sqoop.schema.type.FixedPoint;
import org.apache.sqoop.schema.type.Text;
import org.apache.sqoop.test.infrastructure.Infrastructure;
import org.apache.sqoop.test.infrastructure.SqoopTestCase;
import org.apache.sqoop.test.infrastructure.providers.DatabaseInfrastructureProvider;
import org.apache.sqoop.test.infrastructure.providers.HadoopInfrastructureProvider;
import org.apache.sqoop.test.infrastructure.providers.KdcInfrastructureProvider;
import org.apache.sqoop.test.infrastructure.providers.SqoopInfrastructureProvider;
import org.apache.sqoop.test.utils.HdfsUtils;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static org.testng.Assert.fail;

@Infrastructure(dependencies = {KdcInfrastructureProvider.class, HadoopInfrastructureProvider.class, SqoopInfrastructureProvider.class, DatabaseInfrastructureProvider.class})
public class ParquetTest extends SqoopTestCase {

  private Schema sqoopSchema;
  private org.apache.avro.Schema avroSchema;

  @BeforeClass
  public void setUp() {
    sqoopSchema = new Schema("ParquetTest");
    sqoopSchema.addColumn(new FixedPoint("id", Long.valueOf(Integer.SIZE / Byte.SIZE), true));
    sqoopSchema.addColumn(new Text("country"));
    sqoopSchema.addColumn(new DateTime("some_date", true, false));
    sqoopSchema.addColumn(new Text("city"));

    avroSchema = SqoopAvroUtils.createAvroSchema(sqoopSchema);
  }


  @AfterMethod
  public void dropTable() {
    super.dropTable();
  }

  @Test
  public void toParquetTest() throws Exception {
    createAndLoadTableCities();

    // RDBMS link
    MLink rdbmsConnection = getClient().createLink("generic-jdbc-connector");
    fillRdbmsLinkConfig(rdbmsConnection);
    saveLink(rdbmsConnection);

    // HDFS link
    MLink hdfsConnection = getClient().createLink("hdfs-connector");
    fillHdfsLink(hdfsConnection);
    saveLink(hdfsConnection);

    hdfsClient.mkdirs(new Path(HdfsUtils.joinPathFragments
      (getMapreduceDirectory(), "TO")));

    // Job creation
    MJob job = getClient().createJob(rdbmsConnection.getName(), hdfsConnection.getName());




    // Set rdbms "FROM" config
    fillRdbmsFromConfig(job, "id");

    // Fill the hdfs "TO" config
    fillHdfsToConfig(job, ToFormat.PARQUET_FILE);

    job.getToJobConfig().getStringInput("toJobConfig.outputDirectory")
      .setValue(HdfsUtils.joinPathFragments(getMapreduceDirectory(), "TO"));

    saveJob(job);
    executeJob(job);

    List<GenericData.Record> expectedAvroRecords = new ArrayList<>();
    expectedAvroRecords.addAll(Arrays.asList(
            new GenericRecordBuilder(avroSchema)
                    .set(SqoopAvroUtils.createAvroName("id"), 1)
                    .set(SqoopAvroUtils.createAvroName("country"), "USA")
                    .set(SqoopAvroUtils.createAvroName("some_date"), new org.joda.time.DateTime(2004, 10, 23, 0, 0, 0, 0).toDate().getTime())
                    .set(SqoopAvroUtils.createAvroName("city"), "San Francisco").build(),
            new GenericRecordBuilder(avroSchema)
                    .set(SqoopAvroUtils.createAvroName("id"), 2)
                    .set(SqoopAvroUtils.createAvroName("country"), "USA")
                    .set(SqoopAvroUtils.createAvroName("some_date"), new org.joda.time.DateTime(2004, 10, 24, 0, 0, 0, 0).toDate().getTime())
                    .set(SqoopAvroUtils.createAvroName("city"), "Sunnyvale").build(),
            new GenericRecordBuilder(avroSchema)
                    .set(SqoopAvroUtils.createAvroName("id"), 3)
                    .set(SqoopAvroUtils.createAvroName("country"), "Czech Republic")
                    .set(SqoopAvroUtils.createAvroName("some_date"), new org.joda.time.DateTime(2004, 10, 25, 0, 0, 0, 0).toDate().getTime())
                    .set(SqoopAvroUtils.createAvroName("city"), "Brno").build(),
            new GenericRecordBuilder(avroSchema)
                    .set(SqoopAvroUtils.createAvroName("id"), 4)
                    .set(SqoopAvroUtils.createAvroName("country"), "USA")
                    .set(SqoopAvroUtils.createAvroName("some_date"), new org.joda.time.DateTime(2004, 10, 26, 0, 0, 0, 0).toDate().getTime())
                    .set(SqoopAvroUtils.createAvroName("city"), "Palo Alto").build(),
            new GenericRecordBuilder(avroSchema)
                    .set(SqoopAvroUtils.createAvroName("id"), 5)
                    .set(SqoopAvroUtils.createAvroName("country"), "USA")
                    .set(SqoopAvroUtils.createAvroName("some_date"), new org.joda.time.DateTime(2004, 10, 27, 0, 0, 0, 0).toDate().getTime())
                    .set(SqoopAvroUtils.createAvroName("city"), "Martha's Vineyard").build()
    ));
    List<GenericRecord> notFound = new LinkedList<>();

    Path[] files = HdfsUtils.getOutputMapreduceFiles(hdfsClient, HdfsUtils.joinPathFragments(getMapreduceDirectory(), "TO"));
    for (Path file : files) {
      ParquetReader<GenericRecord> avroParquetReader = AvroParquetReader.builder(file).build();
      GenericRecord record;
      while ((record = avroParquetReader.read()) != null) {
        if (!expectedAvroRecords.remove(record)) {
          notFound.add(record);
        }
      }
    }

    if (!expectedAvroRecords.isEmpty() || !notFound.isEmpty()) {
      fail("Output do not match expectations.");
    }
  }

  @Test
  public void fromParquetTest() throws Exception {
    createTableCities();

    HdfsParquetWriter parquetWriter = new HdfsParquetWriter();

    Configuration conf = new Configuration();
    FileSystem.setDefaultUri(conf, hdfsClient.getUri());

    parquetWriter.initialize(
      new Path(HdfsUtils.joinPathFragments(getMapreduceDirectory(), "input-0001.parquet")),
      sqoopSchema, conf, null);

    parquetWriter.write(SqoopIDFUtils.fromCSV("1,'USA','2004-10-23 00:00:00.000','San Francisco'", sqoopSchema), SqoopIDFUtils.DEFAULT_NULL_VALUE);
    parquetWriter.write(SqoopIDFUtils.fromCSV("2,'USA','2004-10-24 00:00:00.000','Sunnyvale'", sqoopSchema), SqoopIDFUtils.DEFAULT_NULL_VALUE);

    parquetWriter.destroy();

    parquetWriter.initialize(
      new Path(HdfsUtils.joinPathFragments(getMapreduceDirectory(), "input-0002.parquet")),
      sqoopSchema, conf, null);

    parquetWriter.write(SqoopIDFUtils.fromCSV("3,'Czech Republic','2004-10-25 00:00:00.000','Brno'", sqoopSchema), SqoopIDFUtils.DEFAULT_NULL_VALUE);
    parquetWriter.write(SqoopIDFUtils.fromCSV("4,'USA','2004-10-26 00:00:00.000','Palo Alto'", sqoopSchema), SqoopIDFUtils.DEFAULT_NULL_VALUE);
    parquetWriter.write(SqoopIDFUtils.fromCSV("5,'USA','2004-10-27 00:00:00.000','Martha\\'s Vineyard'", sqoopSchema), SqoopIDFUtils.DEFAULT_NULL_VALUE);

    parquetWriter.destroy();

    // RDBMS link
    MLink rdbmsLink = getClient().createLink("generic-jdbc-connector");
    fillRdbmsLinkConfig(rdbmsLink);
    saveLink(rdbmsLink);

    // HDFS link
    MLink hdfsLink = getClient().createLink("hdfs-connector");
    fillHdfsLink(hdfsLink);
    saveLink(hdfsLink);

    // Job creation
    MJob job = getClient().createJob(hdfsLink.getName(), rdbmsLink.getName());
    fillHdfsFromConfig(job);
    fillRdbmsToConfig(job);
    saveJob(job);

    executeJob(job);
    Assert.assertEquals(provider.rowCount(getTableName()), 5);
    assertRowInCities(1, "USA", Timestamp.valueOf("2004-10-23 00:00:00.000"), "San Francisco");
    assertRowInCities(2, "USA", Timestamp.valueOf("2004-10-24 00:00:00.000"), "Sunnyvale");
    assertRowInCities(3, "Czech Republic", Timestamp.valueOf("2004-10-25 00:00:00.000"), "Brno");
    assertRowInCities(4, "USA", Timestamp.valueOf("2004-10-26 00:00:00.000"), "Palo Alto");
    assertRowInCities(5, "USA", Timestamp.valueOf("2004-10-27 00:00:00.000"), "Martha's Vineyard");
  }

}
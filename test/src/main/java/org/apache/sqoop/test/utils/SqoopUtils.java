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
package org.apache.sqoop.test.utils;

import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Random;

import org.apache.sqoop.model.MAccountableEntity;
import org.apache.sqoop.model.MJob;
import org.apache.sqoop.model.MLink;

public class SqoopUtils {
  private static final Random rand = new Random();

  public static void fillObjectName(MAccountableEntity object) {
    String objectName = object.getName();
    if (objectName != null && !objectName.isEmpty()) {
      return;
    }

    String prefix = "";
    if (object instanceof MLink) {
      prefix = "link_";
    } else if (object instanceof MJob) {
      prefix = "job_";
    }
    object.setName(prefix + rand.nextLong());
  }

  // Retrieve the FQDN of the current host
  public static String getLocalHostName() {
    String fqdn;
    try {
      fqdn = InetAddress.getLocalHost().getCanonicalHostName().toLowerCase(Locale.getDefault());
    } catch (UnknownHostException e1) {
      fqdn = "localhost";
    }
    return fqdn;
  }

  // Retrieve the IP address of the current host
  public static String getLocalIpAddress() {
    String address;
    try {
      address = InetAddress.getLocalHost().getHostAddress();
    } catch (UnknownHostException e1) {
      address = "127.0.0.1";
    }
    return address;
  }


  @SuppressWarnings("rawtypes")
  public static String getClasspathDir(Class klass) throws Exception {
    String file = klass.getName();
    file = file.replace('.', '/') + ".class";
    URL url = Thread.currentThread().getContextClassLoader().getResource(file);
    String baseDir = url.toURI().getPath();
    baseDir = baseDir.substring(0, baseDir.length() - file.length() - 1);
    return baseDir;
  }
}
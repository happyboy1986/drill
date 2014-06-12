/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.drill.exec.store.hive;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Date;
import java.sql.Timestamp;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.session.SessionState;

public class HiveTestDataGenerator {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HiveTestDataGenerator.class);

  static int RETRIES = 5;
  private Driver hiveDriver = null;
  private static final String DB_DIR = "/tmp/drill_hive_db";
  private static final String WH_DIR = "/tmp/drill_hive_wh";
  
  public static void main(String[] args) throws Exception {
    HiveTestDataGenerator htd = new HiveTestDataGenerator();
    htd.generateTestData();
  }

  private void cleanDir(String dir) throws IOException{
    File f = new File(dir);
    if(f.exists()){
      FileUtils.cleanDirectory(f);
      FileUtils.forceDelete(f);
    }
  }
  
  public void generateTestData() throws Exception {
    
    // remove data from previous runs.
    cleanDir(DB_DIR);
    cleanDir(WH_DIR);
    
    HiveConf conf = new HiveConf();

    conf.set("javax.jdo.option.ConnectionURL", String.format("jdbc:derby:;databaseName=%s;create=true", DB_DIR));
    conf.set(FileSystem.FS_DEFAULT_NAME_KEY, "file:///");
    conf.set("hive.metastore.warehouse.dir", WH_DIR);

    SessionState ss = new SessionState(new HiveConf(SessionState.class));
    SessionState.start(ss);
    hiveDriver = new Driver(conf);

    // generate (key, value) test data
    String testDataFile = generateTestDataFile();

    createTableAndLoadData("default", "kv", testDataFile);
    executeQuery("CREATE DATABASE IF NOT EXISTS db1");
    createTableAndLoadData("db1", "kv_db1", testDataFile);

    // Generate data with date and timestamp data type
    String testDateDataFile = generateTestDataFileWithDate();

    // create table with date and timestamp data type
    executeQuery("USE default");
    executeQuery("CREATE TABLE IF NOT EXISTS default.foodate(a DATE, b TIMESTAMP) "+
        "ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE");
    executeQuery(String.format("LOAD DATA LOCAL INPATH '%s' OVERWRITE INTO TABLE default.foodate", testDateDataFile));

    // create a table with no data
    executeQuery("CREATE TABLE IF NOT EXISTS default.empty_table(a INT, b STRING)");

    // create a Hive table that has columns with data types which are supported for reading in Drill.
    testDataFile = generateAllTypesDataFile();
    executeQuery("CREATE TABLE IF NOT EXISTS allReadSupportedHiveDataTypes (c1 INT, c2 BOOLEAN, c3 DOUBLE, c4 STRING, " +
        "c9 TINYINT, c10 SMALLINT, c11 FLOAT, c12 BIGINT, c19 BINARY) " +
        "ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE");
    executeQuery(String.format("LOAD DATA LOCAL INPATH '%s' OVERWRITE INTO TABLE " +
        "default.allReadSupportedHiveDataTypes", testDataFile));

    // create a table that has all Hive types. This is to test how hive tables metadata is populated in
    // Drill's INFORMATION_SCHEMA.
    executeQuery("CREATE TABLE IF NOT EXISTS allHiveDataTypes(" +
        "booleanType BOOLEAN, " +
        "tinyintType TINYINT, " +
        "smallintType SMALLINT, " +
        "intType INT, " +
        "bigintType BIGINT, " +
        "floatType FLOAT, " +
        "doubleType DOUBLE, " +
        "dataType DATE, " +
        "timestampType TIMESTAMP, " +
        "binaryType BINARY, " +
        "decimalType DECIMAL, " +
        "stringType STRING, " +
        "varCharType VARCHAR(20), " +
        "listType ARRAY<STRING>, " +
        "mapType MAP<STRING,INT>, " +
        "structType STRUCT<sint:INT,sboolean:BOOLEAN,sstring:STRING>, " +
        "uniontypeType UNIONTYPE<int, double, array<string>>)"
    );

    // create a Hive view to test how its metadata is populated in Drill's INFORMATION_SCHEMA
    executeQuery("CREATE VIEW IF NOT EXISTS hiveview AS SELECT * FROM kv");

    ss.close();
  }

  private void createTableAndLoadData(String dbName, String tblName, String dataFile) {
    executeQuery(String.format("USE %s", dbName));
    executeQuery(String.format("CREATE TABLE IF NOT EXISTS %s.%s(key INT, value STRING) "+
        "ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE", dbName, tblName));
    executeQuery(String.format("LOAD DATA LOCAL INPATH '%s' OVERWRITE INTO TABLE %s.%s", dataFile, dbName, tblName));
  }

  private File getTempFile() throws Exception {
    File file = null;
    while (true) {
      file = File.createTempFile("drill-hive-test", ".txt");
      if (file.exists()) {
        boolean success = file.delete();
        if (success) {
          break;
        }
      }
      logger.debug("retry creating tmp file");
    }

    return file;
  }

  private String generateTestDataFile() throws Exception {
    File file = getTempFile();

    PrintWriter printWriter = new PrintWriter(file);
    for (int i=1; i<=5; i++)
      printWriter.println (String.format("%d, key_%d", i, i));
    printWriter.close();

    return file.getPath();
  }

  private String generateTestDataFileWithDate() throws Exception {
    File file = getTempFile();

    PrintWriter printWriter = new PrintWriter(file);
    for (int i=1; i<=5; i++) {
      Date date = new Date(System.currentTimeMillis());
      Timestamp ts = new Timestamp(System.currentTimeMillis());
      printWriter.println (String.format("%s,%s", date.toString(), ts.toString()));
    }
    printWriter.close();

    return file.getPath();
  }

  private String generateAllTypesDataFile() throws Exception {
    File file = getTempFile();

    PrintWriter printWriter = new PrintWriter(file);
    printWriter.println("\\N,\\N,\\N,\\N,\\N,\\N,\\N,\\N,\\N");
    printWriter.println("-1,false,-1.1,,-1,-1,-1.0,-1,\\N");
    printWriter.println("1,true,1.1,1,1,1,1.0,1,YWJjZA==");
    printWriter.close();

    return file.getPath();
  }

  private void executeQuery(String query) {
    CommandProcessorResponse response = null;
    boolean failed = false;
    int retryCount = RETRIES;

    try {
      response = hiveDriver.run(query);
    } catch(CommandNeedRetryException ex) {
      if (--retryCount == 0)
        failed = true;
    }

    if (failed || response.getResponseCode() != 0 )
      throw new RuntimeException(String.format("Failed to execute command '%s', errorMsg = '%s'",
        query, (response != null ? response.getErrorMessage() : "")));
  }
}

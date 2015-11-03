/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 **/

package io.confluent.copycat.hdfs;

import org.apache.kafka.copycat.data.Schema;
import org.apache.kafka.copycat.data.Struct;
import org.apache.kafka.copycat.sink.SinkRecord;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.confluent.copycat.hdfs.utils.Data;
import io.confluent.copycat.hdfs.utils.MemoryRecordWriter;
import io.confluent.copycat.hdfs.utils.MemoryRecordWriterProvider;
import io.confluent.copycat.hdfs.utils.MemoryStorage;

import static org.junit.Assert.assertEquals;


public class FailureRecoveryTest extends HdfsSinkConnectorTestBase {

  @Before
  public void setUp() throws Exception {
    dfsCluster = false;
    super.setUp();
  }

  @Override
  protected Properties createProps() {
    Properties props = super.createProps();
    props.put(HdfsSinkConnectorConfig.STORAGE_CLASS_CONFIG, MemoryStorage.class.getName());
    props.put(HdfsSinkConnectorConfig.RECORD_WRITER_PROVIDER_CLASS_CONFIG,
              MemoryRecordWriterProvider.class.getName());
    return props;
  }

  @Test
  public void testCommitFailure() throws Exception {
    Properties props = createProps();
    HdfsSinkConnectorConfig connectorConfig = new HdfsSinkConnectorConfig(props);

    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    Collection<SinkRecord> sinkRecords = new ArrayList<>();
    for (long offset = 0; offset < 7; offset++) {
      SinkRecord sinkRecord =
          new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, offset);
      sinkRecords.add(sinkRecord);
    }

    HdfsWriter hdfsWriter = new HdfsWriter(connectorConfig, context, avroData);
    MemoryStorage storage = (MemoryStorage) hdfsWriter.getStorage();
    storage.setFailure(MemoryStorage.Failure.appendFailure);

    hdfsWriter.write(sinkRecords);
    assertEquals(context.backoff(), connectorConfig.getLong(HdfsSinkConnectorConfig.RETRY_BACKOFF_CONFIG));

    Map<String, List<Object>> data = Data.getData();

    String logFile = FileUtils.logFileName(url, topicsDir, TOPIC_PARTITION);
    List<Object> content = data.get(logFile);
    assertEquals(null, content);

    hdfsWriter.write(new ArrayList<SinkRecord>());
    content = data.get(logFile);
    assertEquals(null, content);

    Thread.sleep(context.backoff());
    hdfsWriter.write(new ArrayList<SinkRecord>());
    content = data.get(logFile);
    assertEquals(2, content.size());

    hdfsWriter.close();
  }

  @Test
  public void testWriterFailureMultiPartitions() throws Exception {
    Properties props = createProps();

    HdfsSinkConnectorConfig connectorConfig = new HdfsSinkConnectorConfig(props);

    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    ArrayList<SinkRecord> sinkRecords = new ArrayList<>();
    sinkRecords.add(new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, 0L));
    sinkRecords.add(new SinkRecord(TOPIC, PARTITION2, Schema.STRING_SCHEMA, key, schema, record, 0L));

    HdfsWriter hdfsWriter = new HdfsWriter(connectorConfig, context, avroData);
    hdfsWriter.write(sinkRecords);
    sinkRecords.clear();

    for (long offset = 1; offset < 7; offset++) {
      SinkRecord sinkRecord =
          new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, offset);
      sinkRecords.add(sinkRecord);
    }

    for (long offset = 1; offset < 7; offset++) {
      SinkRecord sinkRecord =
          new SinkRecord(TOPIC, PARTITION2, Schema.STRING_SCHEMA, key, schema, record, offset);
      sinkRecords.add(sinkRecord);
    }

    MemoryRecordWriter writer = (MemoryRecordWriter) hdfsWriter.getRecordWriter(TOPIC_PARTITION);
    String tempFileName = hdfsWriter.getTempFileNames(TOPIC_PARTITION);

    writer.setFailure(MemoryRecordWriter.Failure.writeFailure);
    hdfsWriter.write(sinkRecords);
    assertEquals(context.backoff(), connectorConfig.getLong(HdfsSinkConnectorConfig.RETRY_BACKOFF_CONFIG));


    Map<String, List<Object>> data = Data.getData();
    long[] validOffsets = {-1, 2, 5};
    for (int i = 1; i < validOffsets.length; i++) {
      long startOffset = validOffsets[i - 1] + 1;
      long endOffset = validOffsets[i];
      String path = FileUtils.committedFileName(url, topicsDir, TOPIC_PARTITION2, startOffset, endOffset);
      long size = endOffset - startOffset + 1;
      List<Object> records = data.get(path);
      assertEquals(size, records.size());
    }

    writer.setFailure(MemoryRecordWriter.Failure.closeFailure);
    hdfsWriter.write(new ArrayList<SinkRecord>());
    assertEquals(context.backoff(), connectorConfig.getLong(HdfsSinkConnectorConfig.RETRY_BACKOFF_CONFIG));

    List<Object> content = data.get(tempFileName);
    assertEquals(1, content.size());
    for (int i = 0; i < content.size(); ++i) {
      SinkRecord refSinkRecord = new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, i);
      assertEquals(refSinkRecord, content.get(i));
    }

    Thread.sleep(context.backoff());
    hdfsWriter.write(new ArrayList<SinkRecord>());
    assertEquals(3, content.size());
    for (int i = 0; i < content.size(); ++i) {
      SinkRecord refSinkRecord = new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, i);
      assertEquals(refSinkRecord, content.get(i));
    }

    hdfsWriter.write(new ArrayList<SinkRecord>());
    hdfsWriter.close();
  }

  @Test
  public void testWriterFailure() throws Exception {
    Properties props = createProps();

    HdfsSinkConnectorConfig connectorConfig = new HdfsSinkConnectorConfig(props);

    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    ArrayList<SinkRecord> sinkRecords = new ArrayList<>();
    sinkRecords.add(new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, 0L));
    HdfsWriter hdfsWriter = new HdfsWriter(connectorConfig, context, avroData);
    hdfsWriter.write(sinkRecords);

    sinkRecords.clear();
    for (long offset = 1; offset < 7; offset++) {
      SinkRecord sinkRecord =
          new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, offset);
      sinkRecords.add(sinkRecord);
    }

    MemoryRecordWriter writer = (MemoryRecordWriter) hdfsWriter.getRecordWriter(TOPIC_PARTITION);
    writer.setFailure(MemoryRecordWriter.Failure.writeFailure);
    hdfsWriter.write(sinkRecords);
    assertEquals(context.backoff(), connectorConfig.getLong(
        HdfsSinkConnectorConfig.RETRY_BACKOFF_CONFIG));

    writer.setFailure(MemoryRecordWriter.Failure.closeFailure);
    // nothing happens as we the retry back off hasn't yet passed
    hdfsWriter.write(new ArrayList<SinkRecord>());
    Map<String, List<Object>> data = Data.getData();
    String tempFileName = hdfsWriter.getTempFileNames(TOPIC_PARTITION);
    List<Object> content = data.get(tempFileName);
    assertEquals(1, content.size());
    for (int i = 0; i < content.size(); ++i) {
      SinkRecord refSinkRecord = new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, i);
      assertEquals(refSinkRecord, content.get(i));
    }

    Thread.sleep(context.backoff());
    hdfsWriter.write(new ArrayList<SinkRecord>());

    tempFileName = hdfsWriter.getTempFileNames(TOPIC_PARTITION);
    content = data.get(tempFileName);
    assertEquals(3, content.size());
    for (int i = 0; i < content.size(); ++i) {
      SinkRecord refSinkRecord = new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, i);
      assertEquals(refSinkRecord, content.get(i));
    }

    hdfsWriter.write(new ArrayList<SinkRecord>());
    hdfsWriter.close();
  }
}

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

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.copycat.data.Schema;
import org.apache.kafka.copycat.data.Struct;
import org.apache.kafka.copycat.sink.SinkRecord;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HdfsWriterTest extends HdfsSinkConnectorTestBase {

  @Test
  public void testWriteRecord() throws Exception {
    HdfsWriter hdfsWriter = new HdfsWriter(connectorConfig, context, avroData);
    hdfsWriter.recover(TOPIC_PARTITION);

    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    Collection<SinkRecord> sinkRecords = new ArrayList<>();
    for (long offset = 0; offset < 7; offset++) {
      SinkRecord sinkRecord =
          new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, offset);

      sinkRecords.add(sinkRecord);
    }
    hdfsWriter.write(sinkRecords);
    hdfsWriter.close();

    long[] validOffsets = {-1, 2, 5, 6};

    for (int i = 1; i < validOffsets.length; i++) {
      long startOffset = validOffsets[i - 1] + 1;
      long endOffset = validOffsets[i];
      Path path =
          new Path(FileUtils.committedFileName(url, topicsDir, TOPIC_PARTITION, startOffset, endOffset));
      Collection<Object> records = readAvroFile(path);
      long size = endOffset - startOffset + 1;
      assertEquals(size, records.size());
      for (Object avroRecord : records) {
        assertEquals(avroData.fromCopycatData(schema, record), avroRecord);
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testRecovery() throws Exception {
    fs.delete(new Path(FileUtils.directoryName(url, topicsDir, TOPIC_PARTITION)), true);

    Class<? extends Storage> storageClass = (Class<? extends Storage>)
        Class.forName(connectorConfig.getString(HdfsSinkConnectorConfig.STORAGE_CLASS_CONFIG));
    Storage storage = StorageFactory.createStorage(storageClass, conf, url);

    WAL wal = storage.wal(topicsDir, TOPIC_PARTITION);
    Set<String> committedFiles = new HashSet<>();
    for (int i = 0; i < 5; ++i) {
      long startOffset = i * 10;
      long endOffset = (i + 1) * 10 - 1;
      String tempfile = FileUtils.tempFileName(url, topicsDir, TOPIC_PARTITION);
      fs.createNewFile(new Path(tempfile));
      String committedFile = FileUtils.committedFileName(url, topicsDir, TOPIC_PARTITION, startOffset,
                                                         endOffset);
      committedFiles.add(committedFile);
      wal.append(tempfile, committedFile);
    }
    wal.close();

    HdfsWriter hdfsWriter = new HdfsWriter(connectorConfig, context, avroData);
    hdfsWriter.recover(TOPIC_PARTITION);

    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    SinkRecord sinkRecord =
        new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, 50);

    Collection<SinkRecord> sinkRecords = Collections.singletonList(sinkRecord);
    hdfsWriter.write(sinkRecords);
    hdfsWriter.close();

    committedFiles.add(FileUtils.committedFileName(url, topicsDir, TOPIC_PARTITION, 50, 50));
    FileStatus[] statuses =
        fs.listStatus(new Path(FileUtils.directoryName(url, topicsDir, TOPIC_PARTITION)),
                      new CommittedFileFilter());
    for (FileStatus status : statuses) {
      assertTrue(committedFiles.contains(status.getPath().toString()));
    }
  }

  @Test
  public void testWriteRecordMultiplePartitions() throws Exception {
    HdfsWriter hdfsWriter = new HdfsWriter(connectorConfig, context, avroData);

    for (TopicPartition tp: assignment) {
      hdfsWriter.recover(tp);
    }
    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    Collection<SinkRecord> sinkRecords = new ArrayList<>();
    for (TopicPartition tp: assignment) {
      for (long offset = 0; offset < 7; offset++) {
        SinkRecord sinkRecord =
            new SinkRecord(tp.topic(), tp.partition(), Schema.STRING_SCHEMA, key, schema, record, offset);
        sinkRecords.add(sinkRecord);
      }
    }
    hdfsWriter.write(sinkRecords);
    hdfsWriter.close();

    long[] validOffsets = {-1, 2, 5, 6};

    for (TopicPartition tp : assignment) {
      for (int j = 1; j < validOffsets.length; ++j) {
        long startOffset = validOffsets[j - 1] + 1;
        long endOffset = validOffsets[j];
        Path path = new Path(
            FileUtils.committedFileName(url, topicsDir, tp, startOffset, endOffset));
        Collection<Object> records = readAvroFile(path);
        long size = endOffset - startOffset + 1;
        assertEquals(records.size(), size);
        for (Object avroRecord : records) {
          assertEquals(avroRecord, avroData.fromCopycatData(schema, record));
        }
      }
    }
  }

  @Test
  public void testGetPreviousOffsets() throws Exception {
    long[] startOffsets = {0, 3};
    long[] endOffsets = {2, 5};

    for (int i = 0; i < startOffsets.length; ++i) {
      Path path = new Path(FileUtils.committedFileName(url, topicsDir, TOPIC_PARTITION, startOffsets[i],
                                               endOffsets[i]));
      fs.createNewFile(path);
    }
    Path path = new Path(FileUtils.tempFileName(url, topicsDir, TOPIC_PARTITION));
    fs.createNewFile(path);

    path = new Path(FileUtils.fileName(url, topicsDir, TOPIC_PARTITION, "abcd"));
    fs.createNewFile(path);

    HdfsWriter hdfsWriter = new HdfsWriter(connectorConfig, context, avroData);
    hdfsWriter.recover(TOPIC_PARTITION);

    Map<TopicPartition, Long> committedOffsets = hdfsWriter.getCommittedOffsets();

    assertTrue(committedOffsets.containsKey(TOPIC_PARTITION));
    long previousOffset = committedOffsets.get(TOPIC_PARTITION);
    assertEquals(previousOffset, 5L);

    hdfsWriter.close();
  }

  @Test
  public void testWriteRecordNonZeroInitailOffset() throws Exception {
    HdfsWriter hdfsWriter = new HdfsWriter(connectorConfig, context, avroData);
    hdfsWriter.recover(TOPIC_PARTITION);

    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    Collection<SinkRecord> sinkRecords = new ArrayList<>();
    for (long offset = 3; offset < 10; offset++) {
      SinkRecord sinkRecord =
          new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, offset);
      sinkRecords.add(sinkRecord);
    }

    hdfsWriter.write(sinkRecords);
    hdfsWriter.close();

    long[] validOffsets = {2, 5, 8, 9};
    for (int i = 1; i < validOffsets.length; i++) {
      long startOffset = validOffsets[i - 1] + 1;
      long endOffset = validOffsets[i];
      Path path = new Path(FileUtils.committedFileName(url, topicsDir, TOPIC_PARTITION, startOffset, endOffset));
      Collection<Object> records = readAvroFile(path);
      long size = endOffset - startOffset + 1;
      assertEquals(size, records.size());
      for (Object avroRecord : records) {
        assertEquals(avroData.fromCopycatData(schema, record), avroRecord);
      }
    }
  }

  @Test
  public void testRebalance() throws Exception {
    HdfsWriter hdfsWriter = new HdfsWriter(connectorConfig, context, avroData);

    for (TopicPartition tp: assignment) {
      hdfsWriter.recover(tp);
    }
    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    Collection<SinkRecord> sinkRecords = new ArrayList<>();
    for (TopicPartition tp: assignment) {
      for (long offset = 0; offset < 7; offset++) {
        SinkRecord sinkRecord =
            new SinkRecord(tp.topic(), tp.partition(), Schema.STRING_SCHEMA, key, schema, record, offset);
        sinkRecords.add(sinkRecord);
      }
    }
    hdfsWriter.write(sinkRecords);

    Set<TopicPartition> oldAssignment = new HashSet<>();
    for (TopicPartition tp: assignment) {
      oldAssignment.add(tp);
    }

    Set<TopicPartition> newAssignment = new HashSet<>();
    newAssignment.add(TOPIC_PARTITION);
    newAssignment.add(TOPIC_PARTITION3);

    hdfsWriter.onPartitionsRevoked(assignment);

    hdfsWriter.onPartitionsAssigned(newAssignment);
    assertEquals(newAssignment, assignment);

    assertEquals(null, hdfsWriter.getRecordWriter(TOPIC_PARTITION2));
    assertEquals(null, hdfsWriter.getWAL(TOPIC_PARTITION2));

    long[] validOffsetsTopicPartition2 = {5, 6};
    for (int j = 1; j < validOffsetsTopicPartition2.length; ++j) {
      long startOffset = validOffsetsTopicPartition2[j - 1] + 1;
      long endOffset = validOffsetsTopicPartition2[j];
      Path path = new Path(FileUtils.committedFileName(url, topicsDir, TOPIC_PARTITION2, startOffset, endOffset));
      Collection<Object> records = readAvroFile(path);
      long size = endOffset - startOffset + 1;
      assertEquals(records.size(), size);
      for (Object avroRecord : records) {
        assertEquals(avroData.fromCopycatData(schema, record), avroRecord);
      }
    }

    sinkRecords.clear();
    for (TopicPartition tp: assignment) {
      for (long offset = 7; offset < 10; offset++) {
        SinkRecord sinkRecord =
            new SinkRecord(tp.topic(), tp.partition(), Schema.STRING_SCHEMA, key, schema, record, offset);
        sinkRecords.add(sinkRecord);
      }
    }

    hdfsWriter.write(sinkRecords);
    hdfsWriter.close();

    long[] validOffsetsTopicPartition1 = {5, 8, 9};
    for (int j = 1; j < validOffsetsTopicPartition1.length; ++j) {
      long startOffset = validOffsetsTopicPartition1[j - 1] + 1;
      long endOffset = validOffsetsTopicPartition1[j];
      Path path = new Path(FileUtils.committedFileName(url, topicsDir, TOPIC_PARTITION, startOffset, endOffset));
      Collection<Object> records = readAvroFile(path);
      long size = endOffset - startOffset + 1;
      assertEquals(records.size(), size);
      for (Object avroRecord : records) {
        assertEquals(avroData.fromCopycatData(schema, record), avroRecord);
      }
    }

    long[] validOffsetsTopicPartition3 = {6, 9};
    for (int j = 1; j < validOffsetsTopicPartition3.length; ++j) {
      long startOffset = validOffsetsTopicPartition3[j - 1] + 1;
      long endOffset = validOffsetsTopicPartition3[j];
      Path path = new Path(FileUtils.committedFileName(url, topicsDir, TOPIC_PARTITION3, startOffset, endOffset));
      Collection<Object> records = readAvroFile(path);
      long size = endOffset - startOffset + 1;
      assertEquals(records.size(), size);
      for (Object avroRecord : records) {
        assertEquals(avroData.fromCopycatData(schema, record), avroRecord);
      }
    }
    assignment = oldAssignment;
  }
}

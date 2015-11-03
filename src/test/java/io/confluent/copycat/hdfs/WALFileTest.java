/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.copycat.hdfs;

import org.apache.hadoop.fs.Path;
import org.apache.kafka.common.TopicPartition;
import org.junit.Test;

import java.io.IOException;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WALFileTest extends HdfsSinkConnectorTestBase {

  @Test(timeout = 30000)
  public void testeAppend() throws Exception {
    Properties props = createProps();
    HdfsSinkConnectorConfig connectorConfig = new HdfsSinkConnectorConfig(props);

    String topicsDir = connectorConfig.getString(HdfsSinkConnectorConfig.TOPIC_DIR_CONFIG);
    String topic = "topic";
    int partition = 0;
    TopicPartition topicPart = new TopicPartition(topic, partition);

    Path file = new Path(FileUtils.logFileName(url, topicsDir, topicPart));

    WALFile.Writer writer = WALFile.createWriter(conf, WALFile.Writer.file(file));

    WALEntry key1 = new WALEntry("key1");
    WALEntry val1 = new WALEntry("val1");

    WALEntry key2 = new WALEntry("key2");
    WALEntry val2 = new WALEntry("val2");

    writer.append(key1, val1);
    writer.append(key2, val2);
    writer.close();

    verify2Values(file);

    writer = WALFile.createWriter(conf, WALFile.Writer.file(file), WALFile.Writer.appendIfExists(true));

    WALEntry key3 = new WALEntry("key3");
    WALEntry val3 = new WALEntry("val3");

    WALEntry key4 = new WALEntry("key4");
    WALEntry val4 = new WALEntry("val4");

    writer.append(key3, val3);
    writer.append(key4, val4);
    writer.hsync();
    writer.close();

    verifyAll4Values(file);

    fs.deleteOnExit(file);
  }

  private void verify2Values(Path file) throws IOException {
    WALEntry key1 = new WALEntry("key1");
    WALEntry val1 = new WALEntry("val1");

    WALEntry key2 = new WALEntry("key2");
    WALEntry val2 = new WALEntry("val2");

    WALFile.Reader reader = new WALFile.Reader(conf, WALFile.Reader.file(file));

    assertEquals(key1.getFilename(), ((WALEntry) reader.next((Object) null)).getFilename());
    assertEquals(val1.getFilename(), ((WALEntry) reader.getCurrentValue((Object) null)).getFilename());
    assertEquals(key2.getFilename(), ((WALEntry) reader.next((Object) null)).getFilename());
    assertEquals(val2.getFilename(), ((WALEntry) reader.getCurrentValue((Object) null)).getFilename());
    assertNull(reader.next((Object) null));
    reader.close();
  }

  private void verifyAll4Values(Path file) throws IOException {
    WALEntry key1 = new WALEntry("key1");
    WALEntry val1 = new WALEntry("val1");

    WALEntry key2 = new WALEntry("key2");
    WALEntry val2 = new WALEntry("val2");

    WALEntry key3 = new WALEntry("key3");
    WALEntry val3 = new WALEntry("val3");

    WALEntry key4 = new WALEntry("key4");
    WALEntry val4 = new WALEntry("val4");

    WALFile.Reader reader = new WALFile.Reader(conf, WALFile.Reader.file(file));
    assertEquals(key1.getFilename(), ((WALEntry) reader.next((Object) null)).getFilename());
    assertEquals(val1.getFilename(), ((WALEntry) reader.getCurrentValue((Object) null)).getFilename());
    assertEquals(key2.getFilename(), ((WALEntry) reader.next((Object) null)).getFilename());
    assertEquals(val2.getFilename(), ((WALEntry) reader.getCurrentValue((Object) null)).getFilename());

    assertEquals(key3.getFilename(), ((WALEntry) reader.next((Object) null)).getFilename());
    assertEquals(val3.getFilename(), ((WALEntry) reader.getCurrentValue((Object) null)).getFilename());
    assertEquals(key4.getFilename(), ((WALEntry) reader.next((Object) null)).getFilename());
    assertEquals(val4.getFilename(), ((WALEntry) reader.getCurrentValue((Object) null)).getFilename());
    assertNull(reader.next((Object) null));
    reader.close();
  }
}

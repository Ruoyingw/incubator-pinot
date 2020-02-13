/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.grigio.common.storageProvider;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * provide the storage abstraction of storing upsert update event logs to a local disk so we can reload it
 * during server start. This provided the abstraction layer for individual table/segment storage
 */
public class SegmentUpdateLogStorageProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(SegmentUpdateLogStorageProvider.class);

  @VisibleForTesting
  protected final File _file;
  @VisibleForTesting
  protected final FileOutputStream _outputStream;
  private AtomicInteger messageCountInFile = new AtomicInteger(0);

  public SegmentUpdateLogStorageProvider(File file)
      throws IOException {
    Preconditions.checkState(file != null && file.exists(), "storage file for this virtual column provider does not exist");
    LOGGER.info("creating segmentUpdateLogProvider at {}", file.getPath());
    _file = file;
    openAndLoadDataFromFile(file);
    _outputStream = new FileOutputStream(_file, true);
  }

  public synchronized UpdateLogEntrySet readAllMessagesFromFile() throws IOException {
    int fileLength = (int) _file.length();
    if (fileLength > 0) {
      ByteBuffer buffer = ByteBuffer.allocate(fileLength);
      readFullyFromBeginning(_file, buffer);
      int messageCount = fileLength / UpdateLogEntry.SIZE;
      LOGGER.info("read {} messages from file {}", messageCount, _file.getName());
      return new UpdateLogEntrySet(buffer, messageCount);
    } else {
      return UpdateLogEntrySet.getEmptySet();
    }
  }

  public synchronized void addData(List<UpdateLogEntry> messages) throws IOException {
    final ByteBuffer buffer = ByteBuffer.allocate(messages.size() * UpdateLogEntry.SIZE);
    for (UpdateLogEntry message: messages) {
      message.addEntryToBuffer(buffer);
    }
    buffer.flip();
    _outputStream.write(buffer.array());
    _outputStream.flush();
    messageCountInFile.getAndAdd(messages.size());
    LOGGER.debug("file {} message count {}", _file.getName(), messageCountInFile.get());
  }

  public synchronized void destroy() throws IOException {
    _outputStream.close();
    if (_file.exists()) {
      LOGGER.info("deleting file {}", _file.getPath());
      _file.delete();
    }
  }

  public synchronized void close() throws IOException {
    _outputStream.close();
  }

  private synchronized void openAndLoadDataFromFile(File segmentUpdateFile) throws IOException {
    if (segmentUpdateFile == null || !segmentUpdateFile.exists()) {
      throw new IOException("failed to open segment update file");
    }
    FileChannel channel = new RandomAccessFile(segmentUpdateFile, "rw").getChannel();
    // truncate file if necessary, in case the java process crashed while we have not finished writing out content to
    // the file. We abandon any unfinished message as we can always read them back from kafka
    if (segmentUpdateFile.length() > 0 && segmentUpdateFile.length() % UpdateLogEntry.SIZE != 0) {
      long newSize = segmentUpdateFile.length() / UpdateLogEntry.SIZE * UpdateLogEntry.SIZE;
      LOGGER.info("truncating {} file from size {} to size {}", segmentUpdateFile.getAbsolutePath(),
          segmentUpdateFile.length(), newSize);
      channel.truncate(newSize);
      channel.force(false);
      messageCountInFile.set(Math.toIntExact(newSize / UpdateLogEntry.SIZE));
    }
  }

  private synchronized void readFullyFromBeginning(File segmentUpdateFile, ByteBuffer buffer) throws IOException {
    long start = System.currentTimeMillis();
    FileChannel channel = new RandomAccessFile(segmentUpdateFile, "r").getChannel();
    channel.position(0);
    long position = 0;
    int byteRead;
    do {
      byteRead = channel.read(buffer, position);
      position += byteRead;
    } while (byteRead != -1 && buffer.hasRemaining());
    buffer.flip();
    LOGGER.info("read {} bytes from segment update file {} to buffer in {} ms", position, segmentUpdateFile.getName(),
        System.currentTimeMillis() - start);
  }

  /**
   * get the virtual column provider for the consuming segment (won't download from remote)
   * @param table
   * @param segment
   * @param storagePath
   * @return
   */
  public static SegmentUpdateLogStorageProvider getProviderForMutableSegment(String table, String segment,
                                                                             String storagePath) throws IOException {
    File file = new File(storagePath);
    if (!file.exists()) {
      boolean createResult = file.createNewFile();
      if (!createResult) {
        throw new RuntimeException("failed to create file for virtual column storage at path " + storagePath);
      }
    }
    return new SegmentUpdateLogStorageProvider(file);
  }

  /**
   * get the virtual column provider for immutable segment (re-use local one or download from remote)
   * @param table
   * @param segment
   * @param storagePath
   * @return
   */
  public static SegmentUpdateLogStorageProvider getProviderForImmutableSegment(String table, String segment,
                                                                               String storagePath)
      throws IOException {
    File file;
    if (Files.exists(Paths.get(storagePath))) {
      file = new File(storagePath);
    } else {
      file = downloadFileFromRemote(table, segment, storagePath);
      Preconditions.checkState(file.exists(), "download from remote didn't create the file");
    }
    return new SegmentUpdateLogStorageProvider(file);
  }

  // try to download the update log from remote storage
  public static File downloadFileFromRemote(String table, String segment, String storagePath) {
    //TODO implement this logic
    throw new UnsupportedOperationException("download update log from remote is not supported yet");
  }
}
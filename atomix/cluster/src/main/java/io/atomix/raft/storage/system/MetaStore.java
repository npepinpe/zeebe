/*
 * Copyright 2015-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
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
 * limitations under the License
 */
package io.atomix.raft.storage.system;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkNotNull;

import io.atomix.cluster.MemberId;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.storage.StorageException;
import io.atomix.utils.serializer.Serializer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.StandardOpenOption;
import org.agrona.IoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages persistence of server configurations.
 *
 * <p>The server metastore is responsible for persisting server configurations according to the
 * configured {@link RaftStorage#storageLevel() storage level}. Each server persists their current
 * {@link #loadTerm() term} and last {@link #loadVote() vote} as is dictated by the Raft consensus
 * algorithm. Additionally, the metastore is responsible for storing the last know server {@link
 * Configuration}, including cluster membership.
 */
public class MetaStore implements AutoCloseable {

  private final Logger log = LoggerFactory.getLogger(getClass());
  private final Serializer serializer;
  private final FileChannel metaFileChannel;
  private final FileChannel configurationChannel;
  private final File metaFile;
  private final File confFile;

  public MetaStore(final RaftStorage storage, final Serializer serializer) throws IOException {
    this.serializer = checkNotNull(serializer, "serializer cannot be null");

    if (!(storage.directory().isDirectory() || storage.directory().mkdirs())) {
      throw new IllegalArgumentException(
          String.format("Can't create storage directory [%s].", storage.directory()));
    }

    // Note that for raft safety, irrespective of the storage level, <term, vote> metadata is always
    // persisted on disk.
    metaFile = new File(storage.directory(), String.format("%s.meta", storage.prefix()));
    if (!metaFile.exists()) {
      metaFileChannel = IoUtil.createEmptyFile(metaFile, 32, true);
    } else {
      metaFileChannel =
          FileChannel.open(metaFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    confFile = new File(storage.directory(), String.format("%s.conf", storage.prefix()));

    if (!confFile.exists()) {
      configurationChannel = IoUtil.createEmptyFile(confFile, 32, true);
    } else {
      configurationChannel =
          FileChannel.open(confFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
    }
  }

  /**
   * Stores the current server term.
   *
   * @param term The current server term.
   */
  public synchronized void storeTerm(final long term) {
    log.trace("Store term {}", term);
    final byte[] test = new byte[Long.BYTES];
    final ByteBuffer buffer = ByteBuffer.wrap(test);
    buffer.putLong(term);
    buffer.flip();
    try {
      metaFileChannel.write(buffer, 0);
      metaFileChannel.force(true);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  /**
   * Loads the stored server term.
   *
   * @return The stored server term.
   */
  public synchronized long loadTerm() {
    final byte[] test = new byte[Long.BYTES];
    final ByteBuffer buffer = ByteBuffer.wrap(test);
    try {
      metaFileChannel.read(buffer, 0);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
    return buffer.getLong(0);
  }

  /**
   * Stores the last voted server.
   *
   * @param vote The server vote.
   */
  public synchronized void storeVote(final MemberId vote) {
    log.trace("Store vote {}", vote);
    final int offset = Long.BYTES;

    final byte[] test = new byte[1 + Integer.BYTES];
    final ByteBuffer buffer = ByteBuffer.wrap(test);

    try {
      if (vote == null) {
        buffer.put((byte) 0);
        buffer.flip();
        metaFileChannel.write(buffer, offset);
      } else {
        final byte[] bytes = vote.id().getBytes(Charset.defaultCharset());
        buffer.put((byte) 1).putInt(bytes.length);
        buffer.flip();
        metaFileChannel.write(buffer, offset);
        metaFileChannel.write(ByteBuffer.wrap(bytes), offset + 1 + Integer.BYTES);
      }
      metaFileChannel.force(true);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  /**
   * Loads the last vote for the server.
   *
   * @return The last vote for the server.
   */
  public synchronized MemberId loadVote() {
    final int offset = Long.BYTES;

    final ByteBuffer buffer = ByteBuffer.allocate((int) metaFile.length());

    try {
      metaFileChannel.read(buffer, offset);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
    buffer.position(0);
    if (buffer.get() == 1) {
      final int length = buffer.getInt();
      final byte[] idBytes = new byte[length];
      buffer.get(idBytes);
      final String id = new String(idBytes, 0, idBytes.length, Charset.defaultCharset());
      return MemberId.from(id);
    }
    return null;
  }

  /**
   * Stores the current cluster configuration.
   *
   * @param configuration The current cluster configuration.
   */
  public synchronized void storeConfiguration(final Configuration configuration) {
    log.trace("Store configuration {}", configuration);

    final byte[] bytes = serializer.encode(configuration);
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    final ByteBuffer header = ByteBuffer.allocate(1 + Integer.BYTES);
    header.put((byte) 1).putInt(bytes.length);
    header.flip();
    try {
      configurationChannel.write(header, 0);
      configurationChannel.write(buffer, 1 + Integer.BYTES);
      configurationChannel.force(true);
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  /**
   * Loads the current cluster configuration.
   *
   * @return The current cluster configuration.
   */
  public synchronized Configuration loadConfiguration() {
    try {
      configurationChannel.position(0);
      final ByteBuffer buffer = ByteBuffer.allocate((int) confFile.length());
      configurationChannel.read(buffer);
      buffer.position(0);

      if (buffer.get() == 1) {
        final int bytesLength = buffer.getInt();
        if (bytesLength == 0) {
          return null;
        }
        final byte[] serializedBytes = new byte[bytesLength];
        buffer.get(serializedBytes);
        return serializer.decode(serializedBytes);
      }
    } catch (final IOException e) {
      throw new StorageException(e);
    }
    return null;
  }

  @Override
  public synchronized void close() {
    try {
      metaFileChannel.close();
      configurationChannel.close();
    } catch (final IOException e) {
      log.warn("Failed to close metastore", e);
    }
  }

  @Override
  public String toString() {
    return toStringHelper(this).toString();
  }
}

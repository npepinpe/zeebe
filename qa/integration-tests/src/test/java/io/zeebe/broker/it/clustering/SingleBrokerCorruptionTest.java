/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.broker.it.clustering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import io.zeebe.journal.file.FrameUtil;
import io.zeebe.journal.file.JournalSegmentDescriptor;
import io.zeebe.journal.file.record.CorruptedLogException;
import io.zeebe.journal.file.record.JournalRecordReaderUtil;
import io.zeebe.journal.file.record.SBESerializer;
import io.zeebe.model.bpmn.Bpmn;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SingleBrokerCorruptionTest {

  private static final int PARTITION = 1;
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
  @Rule public final ClusteringRule clusteringRule = new ClusteringRule(1, 1, 1);

  @Test
  public void shouldDetectCorruptionOnStart() throws IOException {
    // given
    final int nodeId = clusteringRule.getLeaderForPartition(PARTITION).getNodeId();
    clusteringRule
        .getClient()
        .newDeployCommand()
        .addProcessModel(
            Bpmn.createExecutableProcess("process").startEvent().done(), "process.bpmn")
        .send()
        .join();

    final Path directory = clusteringRule.getSegmentsDirectory(clusteringRule.getBroker(nodeId));
    final Optional<File> optLog =
        Arrays.stream(directory.toFile().listFiles())
            .filter(f -> f.getName().equals("raft-partition-partition-1-1.log"))
            .findFirst();

    // when
    assertThat(optLog).isPresent();
    final File log = optLog.get();

    clusteringRule.stopBroker(nodeId);
    assertThat(corruptFile(temporaryFolder.newFile(), log)).isTrue();

    // then
    assertThatThrownBy(() -> clusteringRule.startBroker(nodeId))
        .hasRootCauseInstanceOf(CorruptedLogException.class);
  }

  static boolean corruptFile(final File temp, final File file) throws IOException {
    final byte[] bytes = new byte[1024];
    int read = 0;

    try (final BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
      while (in.available() > 0 && read < 1024) {
        read += in.read(bytes, read, Math.min(1024, in.available()) - read);
      }
    }

    corruptFirstRecord(bytes);

    try (final BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
      out.write(bytes, 0, read);
    }

    return temp.renameTo(file);
  }

  private static void corruptFirstRecord(final byte[] bytes) {
    final JournalRecordReaderUtil reader = new JournalRecordReaderUtil(new SBESerializer());
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    buffer.position(JournalSegmentDescriptor.BYTES);

    final Optional<Integer> version = FrameUtil.readVersion(buffer);
    assertThat(version).isPresent();
    assertThat(version.get()).isOne();

    reader.read(buffer, 1);
    final int lastPos = buffer.position() - 1;
    buffer.put(lastPos, (byte) ~buffer.get(lastPos));
  }
}

/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.broker.system.partitions.impl;

import io.zeebe.broker.system.partitions.StateController;
import io.zeebe.engine.processing.streamprocessor.StreamProcessor;
import io.zeebe.journal.file.record.CorruptedLogException;
import io.zeebe.logstreams.impl.Loggers;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.snapshots.raft.TransientSnapshot;
import io.zeebe.util.health.FailureListener;
import io.zeebe.util.health.HealthMonitorable;
import io.zeebe.util.health.HealthStatus;
import io.zeebe.util.sched.Actor;
import io.zeebe.util.sched.ActorCondition;
import io.zeebe.util.sched.SchedulingHints;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

public final class AsyncSnapshotDirector extends Actor implements HealthMonitorable {

  public static final Duration MINIMUM_SNAPSHOT_PERIOD = Duration.ofMinutes(1);

  private static final Logger LOG = Loggers.SNAPSHOT_LOGGER;
  private static final String LOG_MSG_WAIT_UNTIL_COMMITTED =
      "Finished taking snapshot, need to wait until last written event position {} is committed, current commit position is {}. After that snapshot can be marked as valid.";
  private static final String ERROR_MSG_ON_RESOLVE_PROCESSED_POS =
      "Unexpected error in resolving last processed position.";
  private static final String ERROR_MSG_ON_RESOLVE_WRITTEN_POS =
      "Unexpected error in resolving last written position.";
  private static final String ERROR_MSG_MOVE_SNAPSHOT =
      "Unexpected exception occurred on moving valid snapshot.";

  private final StateController stateController;
  private final LogStream logStream;
  private final Duration snapshotRate;
  private final String processorName;
  private final StreamProcessor streamProcessor;
  private final String actorName;
  private final List<FailureListener> listeners = new ArrayList<>();

  private ActorCondition commitCondition;
  private Long lastWrittenEventPosition;
  private TransientSnapshot pendingSnapshot;
  private long lowerBoundSnapshotPosition;
  private boolean takingSnapshot;
  private boolean persistingSnapshot;
  private HealthStatus status = HealthStatus.HEALTHY;

  public AsyncSnapshotDirector(
      final int nodeId,
      final StreamProcessor streamProcessor,
      final StateController stateController,
      final LogStream logStream,
      final Duration snapshotRate) {
    this.streamProcessor = streamProcessor;
    this.stateController = stateController;
    this.logStream = logStream;
    processorName = streamProcessor.getName();
    this.snapshotRate = snapshotRate;
    actorName = buildActorName(nodeId, "SnapshotDirector", logStream.getPartitionId());
  }

  @Override
  public String getName() {
    return actorName;
  }

  @Override
  protected void onActorStarting() {
    actor.setSchedulingHints(SchedulingHints.ioBound());
    final var firstSnapshotTime =
        RandomDuration.getRandomDurationMinuteBased(MINIMUM_SNAPSHOT_PERIOD, snapshotRate);
    actor.runDelayed(firstSnapshotTime, this::scheduleSnapshotOnRate);

    lastWrittenEventPosition = null;
    commitCondition =
        actor.onCondition(
            getConditionNameForPosition(), this::persistSnapshotIfLastWrittenPositionCommitted);
    logStream.registerOnCommitPositionUpdatedCondition(commitCondition);
  }

  @Override
  protected void onActorCloseRequested() {
    logStream.removeOnCommitPositionUpdatedCondition(commitCondition);
  }

  @Override
  public ActorFuture<Void> closeAsync() {
    if (actor.isClosed()) {
      return CompletableActorFuture.completed(null);
    }

    return super.closeAsync();
  }

  private void scheduleSnapshotOnRate() {
    actor.runAtFixedRate(snapshotRate, this::prepareTakingSnapshot);
    prepareTakingSnapshot();
  }

  private String getConditionNameForPosition() {
    return getName() + "-wait-for-endPosition-committed";
  }

  public void forceSnapshot() {
    actor.call(this::prepareTakingSnapshot);
  }

  private void prepareTakingSnapshot() {
    if (takingSnapshot) {
      return;
    }

    takingSnapshot = true;
    final var futureLastProcessedPosition = streamProcessor.getLastProcessedPositionAsync();
    actor.runOnCompletion(
        futureLastProcessedPosition,
        (lastProcessedPosition, error) -> {
          if (error == null) {
            if (lastProcessedPosition == StreamProcessor.UNSET_POSITION) {
              LOG.debug(
                  "We will skip taking this snapshot, because we haven't processed something yet.");
              takingSnapshot = false;
              return;
            }

            lowerBoundSnapshotPosition = lastProcessedPosition;
            logStream
                .getCommitPositionAsync()
                .onComplete(
                    (commitPosition, errorOnRetrievingCommitPosition) -> {
                      if (errorOnRetrievingCommitPosition != null) {
                        takingSnapshot = false;
                        LOG.error(
                            "Unexpected error on retrieving commit position",
                            errorOnRetrievingCommitPosition);
                        return;
                      }
                      takeSnapshot(commitPosition);
                    });

          } else {
            LOG.error(ERROR_MSG_ON_RESOLVE_PROCESSED_POS, error);
            takingSnapshot = false;
          }
        });
  }

  private void takeSnapshot(final long initialCommitPosition) {
    final Optional<TransientSnapshot> optionalPendingSnapshot;
    try {
      optionalPendingSnapshot = stateController.takeTransientSnapshot(lowerBoundSnapshotPosition);
    } catch (final CorruptedLogException e) {
      onUnrecoverableFailure(e);
      return;
    }

    if (optionalPendingSnapshot.isEmpty()) {
      takingSnapshot = false;
      return;
    }

    optionalPendingSnapshot
        .get()
        // Snapshot is taken asynchronously.
        .onSnapshotTaken(
            (isValid, snapshotTakenError) ->
                actor.run(
                    () -> {
                      if (snapshotTakenError != null) {
                        LOG.error(
                            "Could not take a snapshot for {}", processorName, snapshotTakenError);
                        return;
                      }
                      LOG.debug("Created pending snapshot for {}", processorName);
                      pendingSnapshot = optionalPendingSnapshot.get();

                      final ActorFuture<Long> lastWrittenPosition =
                          streamProcessor.getLastWrittenPositionAsync();
                      actor.runOnCompletion(
                          lastWrittenPosition,
                          (endPosition, error) -> {
                            if (error == null) {
                              LOG.info(
                                  LOG_MSG_WAIT_UNTIL_COMMITTED, endPosition, initialCommitPosition);
                              lastWrittenEventPosition = endPosition;
                              persistingSnapshot = false;
                              persistSnapshotIfLastWrittenPositionCommitted();
                            } else {
                              lastWrittenEventPosition = null;
                              takingSnapshot = false;
                              pendingSnapshot.abort();
                              pendingSnapshot = null;
                              LOG.error(ERROR_MSG_ON_RESOLVE_WRITTEN_POS, error);
                            }
                          });
                    }));
  }

  private void persistSnapshotIfLastWrittenPositionCommitted() {
    logStream
        .getCommitPositionAsync()
        .onComplete(
            (currentCommitPosition, error) -> {
              if (pendingSnapshot != null
                  && lastWrittenEventPosition != null
                  && currentCommitPosition >= lastWrittenEventPosition
                  && !persistingSnapshot) {
                persistingSnapshot = true;

                final var snapshotPersistFuture = pendingSnapshot.persist();

                snapshotPersistFuture.onComplete(
                    (snapshot, persistError) -> {
                      if (persistError != null) {
                        LOG.error(ERROR_MSG_MOVE_SNAPSHOT, persistError);
                      } else {
                        LOG.info(
                            "Current commit position {} >= {}, snapshot {} is valid and has been persisted.",
                            currentCommitPosition,
                            lastWrittenEventPosition,
                            snapshot.getId());
                      }
                      lastWrittenEventPosition = null;
                      takingSnapshot = false;
                      pendingSnapshot = null;
                      persistingSnapshot = false;
                    });
              }
            });
  }

  private void onUnrecoverableFailure(final Exception error) {
    LOG.error("Detected unrecoverable failure: ", error);
    status = HealthStatus.DEAD;
    listeners.forEach(FailureListener::onUnrecoverableFailure);
  }

  @Override
  public HealthStatus getHealthStatus() {
    return status;
  }

  @Override
  public void addFailureListener(final FailureListener failureListener) {
    listeners.add(failureListener);
  }
}

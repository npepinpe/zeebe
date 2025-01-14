/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.state.immutable;

import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableFlowElement;
import io.camunda.zeebe.engine.state.deployment.DeployedProcess;
import java.util.Collection;
import java.util.Optional;
import org.agrona.DirectBuffer;

public interface ProcessState {

  DeployedProcess getLatestProcessVersionByProcessId(DirectBuffer processId);

  DeployedProcess getProcessByProcessIdAndVersion(DirectBuffer processId, int version);

  DeployedProcess getProcessByKey(long key);

  Collection<DeployedProcess> getProcesses();

  Collection<DeployedProcess> getProcessesByBpmnProcessId(DirectBuffer bpmnProcessId);

  DirectBuffer getLatestVersionDigest(DirectBuffer processId);

  /**
   * Gets the latest process version. This is the latest version for which we have a process in the
   * state. It is not necessarily the latest version we've ever known for this process id, as
   * process could be deleted.
   *
   * @param bpmnProcessId the id of the process
   */
  int getLatestProcessVersion(String bpmnProcessId);

  /**
   * Gets the next version a process of a given id will receive. This is used, for example, when a
   * new deployment is done. Using this method we decide the version the newly deployed process
   * receives.
   *
   * @param bpmnProcessId the id of the process
   */
  int getNextProcessVersion(String bpmnProcessId);

  /**
   * Finds the previous known version a process. This is used, for example, when a process is
   * deleted and the timers of the previous process need to be activated.
   *
   * <p>If not previous version is found, an empty optional is returned.
   *
   * @param bpmnProcessId the id of the process
   * @param version the version for which we want to find the previous version
   */
  Optional<Integer> findProcessVersionBefore(String bpmnProcessId, long version);

  <T extends ExecutableFlowElement> T getFlowElement(
      long processDefinitionKey, DirectBuffer elementId, Class<T> elementType);

  /** TODO: Remove the cache entirely from the immutable state */
  void clearCache();
}

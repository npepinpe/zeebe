/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.bpmn.behavior;

import io.zeebe.engine.processing.bpmn.BpmnElementContext;
import io.zeebe.engine.processing.deployment.model.element.ExecutableServiceTask;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.immutable.ElementInstanceState;
import io.zeebe.engine.state.immutable.JobState;
import io.zeebe.engine.state.immutable.JobState.State;
import io.zeebe.engine.state.immutable.ZeebeState;
import io.zeebe.msgpack.value.DocumentValue;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.protocol.record.intent.JobIntent;

public class BpmnJobBehavior {

  private final JobRecord jobRecord = new JobRecord().setVariables(DocumentValue.EMPTY_DOCUMENT);

  private final TypedCommandWriter commandWriter;
  private final StateWriter stateWriter;

  private final KeyGenerator keyGenerator;
  private final JobState jobState;
  private final ElementInstanceState elementInstanceState;
  private final BpmnIncidentBehavior incidentBehavior;

  public BpmnJobBehavior(
      final ZeebeState zeebeState,
      final KeyGenerator keyGenerator,
      final StateWriter stateWriter,
      final TypedCommandWriter commandWriter,
      final BpmnIncidentBehavior incidentBehavior) {

    this.keyGenerator = keyGenerator;
    jobState = zeebeState.getJobState();
    elementInstanceState = zeebeState.getElementInstanceState();

    this.commandWriter = commandWriter;
    this.stateWriter = stateWriter;

    this.incidentBehavior = incidentBehavior;
  }

  public void createJob(
      final BpmnElementContext context,
      final ExecutableServiceTask serviceTask,
      final String jobType,
      final int retries) {

    jobRecord
        .setType(jobType)
        .setRetries(retries)
        .setCustomHeaders(serviceTask.getEncodedHeaders())
        .setBpmnProcessId(context.getBpmnProcessId())
        .setProcessDefinitionVersion(context.getProcessVersion())
        .setProcessDefinitionKey(context.getProcessDefinitionKey())
        .setProcessInstanceKey(context.getProcessInstanceKey())
        .setElementId(serviceTask.getId())
        .setElementInstanceKey(context.getElementInstanceKey());

    stateWriter.appendFollowUpEvent(keyGenerator.nextKey(), JobIntent.CREATED, jobRecord);
  }

  public void cancelJob(final BpmnElementContext context) {

    final var elementInstance = elementInstanceState.getInstance(context.getElementInstanceKey());
    final long jobKey = elementInstance.getJobKey();
    if (jobKey > 0) {
      final State state = jobState.getState(jobKey);

      if (state == State.ACTIVATABLE || state == State.ACTIVATED || state == State.FAILED) {
        final JobRecord job = jobState.getJob(jobKey);
        commandWriter.appendFollowUpCommand(jobKey, JobIntent.CANCEL, job);
      }
      incidentBehavior.resolveJobIncident(jobKey);
    }
  }
}

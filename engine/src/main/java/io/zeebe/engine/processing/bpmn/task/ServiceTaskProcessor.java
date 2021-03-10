/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.bpmn.task;

import io.zeebe.engine.processing.bpmn.BpmnElementContext;
import io.zeebe.engine.processing.bpmn.BpmnElementProcessor;
import io.zeebe.engine.processing.bpmn.behavior.BpmnBehaviors;
import io.zeebe.engine.processing.bpmn.behavior.BpmnEventSubscriptionBehavior;
import io.zeebe.engine.processing.bpmn.behavior.BpmnIncidentBehavior;
import io.zeebe.engine.processing.bpmn.behavior.BpmnStateTransitionBehavior;
import io.zeebe.engine.processing.bpmn.behavior.BpmnVariableMappingBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.common.Failure;
import io.zeebe.engine.processing.deployment.model.element.ExecutableServiceTask;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.immutable.ElementInstanceState;
import io.zeebe.engine.state.immutable.JobState;
import io.zeebe.engine.state.immutable.JobState.State;
import io.zeebe.engine.state.immutable.ZeebeState;
import io.zeebe.msgpack.value.DocumentValue;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.util.Either;

public final class ServiceTaskProcessor implements BpmnElementProcessor<ExecutableServiceTask> {

  private final JobRecord jobRecord = new JobRecord().setVariables(DocumentValue.EMPTY_DOCUMENT);

  private final ExpressionProcessor expressionBehavior;
  private final BpmnIncidentBehavior incidentBehavior;
  private final BpmnStateTransitionBehavior stateTransitionBehavior;
  private final BpmnVariableMappingBehavior variableMappingBehavior;
  private final BpmnEventSubscriptionBehavior eventSubscriptionBehavior;

  private final TypedCommandWriter commandWriter;
  private final StateWriter stateWriter;

  private final KeyGenerator keyGenerator;
  private final ElementInstanceState elementInstanceState;
  private final JobState jobState;

  public ServiceTaskProcessor(
      final BpmnBehaviors behaviors,
      final Writers writers,
      final ZeebeState zeebeState,
      final KeyGenerator keyGenerator) {
    eventSubscriptionBehavior = behaviors.eventSubscriptionBehavior();
    expressionBehavior = behaviors.expressionBehavior();
    incidentBehavior = behaviors.incidentBehavior();

    stateTransitionBehavior = behaviors.stateTransitionBehavior();
    variableMappingBehavior = behaviors.variableMappingBehavior();

    stateWriter = writers.state();
    commandWriter = writers.command();

    this.keyGenerator = keyGenerator;
    elementInstanceState = zeebeState.getElementInstanceState();
    jobState = zeebeState.getJobState();
  }

  @Override
  public Class<ExecutableServiceTask> getType() {
    return ExecutableServiceTask.class;
  }

  @Override
  public void onActivate(final ExecutableServiceTask element, final BpmnElementContext context) {
    final var activatingContext = stateTransitionBehavior.transitionToActivating(context);
    final var scopeKey = activatingContext.getElementInstanceKey();

    final Either<Failure, String> jobTypeOrFailure =
        expressionBehavior.evaluateStringExpression(element.getType(), scopeKey);
    jobTypeOrFailure
        .flatMap(
            jobType -> expressionBehavior.evaluateLongExpression(element.getRetries(), scopeKey))
        .ifRightOrLeft(
            retries ->
                attemptToCreateJob(element, activatingContext, jobTypeOrFailure.get(), retries),
            failure -> incidentBehavior.createIncident(failure, activatingContext));
  }

  private void attemptToCreateJob(
      final ExecutableServiceTask element,
      final BpmnElementContext context,
      final String jobType,
      final Long retries) {
    variableMappingBehavior
        .applyInputMappings(context, element)
        .flatMap(ok -> eventSubscriptionBehavior.subscribeToEvents(element, context))
        .ifRightOrLeft(
            ok -> {
              final var activatedContext = stateTransitionBehavior.transitionToActivated(context);
              writeJobCreatedEvent(activatedContext, element, jobType, retries.intValue());
            },
            failure -> incidentBehavior.createIncident(failure, context));
  }

  @Override
  public void onComplete(final ExecutableServiceTask element, final BpmnElementContext context) {

    variableMappingBehavior
        .applyOutputMappings(context, element)
        .ifRightOrLeft(
            ok -> {
              eventSubscriptionBehavior.unsubscribeFromEvents(context);
              final var completedContext = stateTransitionBehavior.transitionToCompleted(context);

              stateTransitionBehavior.takeOutgoingSequenceFlows(element, completedContext);
            },
            failure -> incidentBehavior.createIncident(failure, context));
  }

  @Override
  public void onTerminate(final ExecutableServiceTask element, final BpmnElementContext context) {

    final var elementInstance = elementInstanceState.getInstance(context.getElementInstanceKey());
    final long jobKey = elementInstance.getJobKey();
    if (jobKey > 0) {
      cancelJob(jobKey);
      incidentBehavior.resolveJobIncident(jobKey);
    }

    eventSubscriptionBehavior.unsubscribeFromEvents(context);

    final var terminatedContext = stateTransitionBehavior.transitionToTerminated(context);

    eventSubscriptionBehavior.publishTriggeredBoundaryEvent(terminatedContext);

    incidentBehavior.resolveIncidents(terminatedContext);

    stateTransitionBehavior.onElementTerminated(element, terminatedContext);
  }

  @Override
  public void onEventOccurred(
      final ExecutableServiceTask element, final BpmnElementContext context) {

    eventSubscriptionBehavior.triggerBoundaryEvent(element, context);
  }

  private void writeJobCreatedEvent(
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

  private void cancelJob(final long jobKey) {
    final State state = jobState.getState(jobKey);

    if (state == State.ACTIVATABLE || state == State.ACTIVATED || state == State.FAILED) {
      final JobRecord job = jobState.getJob(jobKey);
      commandWriter.appendFollowUpCommand(jobKey, JobIntent.CANCEL, job);
    }
  }
}

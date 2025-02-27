/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.bpmn.gateway;

import io.camunda.zeebe.el.Expression;
import io.camunda.zeebe.engine.processing.bpmn.BpmnElementContext;
import io.camunda.zeebe.engine.processing.bpmn.BpmnElementProcessor;
import io.camunda.zeebe.engine.processing.bpmn.behavior.BpmnBehaviors;
import io.camunda.zeebe.engine.processing.bpmn.behavior.BpmnIncidentBehavior;
import io.camunda.zeebe.engine.processing.bpmn.behavior.BpmnStateTransitionBehavior;
import io.camunda.zeebe.engine.processing.common.ExpressionProcessor;
import io.camunda.zeebe.engine.processing.common.Failure;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableInclusiveGateway;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableSequenceFlow;
import io.camunda.zeebe.protocol.record.value.ErrorType;
import io.camunda.zeebe.util.Either;
import io.camunda.zeebe.util.buffer.BufferUtil;
import java.util.ArrayList;
import java.util.List;

public final class InclusiveGatewayProcessor
    implements BpmnElementProcessor<ExecutableInclusiveGateway> {

  private static final String NO_OUTGOING_FLOW_CHOSEN_ERROR =
      "Expected at least one condition to evaluate to true, or to have a default flow";

  private final BpmnStateTransitionBehavior stateTransitionBehavior;
  private final BpmnIncidentBehavior incidentBehavior;
  private final ExpressionProcessor expressionBehavior;

  public InclusiveGatewayProcessor(
      final BpmnBehaviors behaviors, final BpmnStateTransitionBehavior stateTransitionBehavior) {
    expressionBehavior = behaviors.expressionBehavior();
    incidentBehavior = behaviors.incidentBehavior();
    this.stateTransitionBehavior = stateTransitionBehavior;
  }

  @Override
  public Class<ExecutableInclusiveGateway> getType() {
    return ExecutableInclusiveGateway.class;
  }

  @Override
  public void onActivate(
      final ExecutableInclusiveGateway element, final BpmnElementContext activating) {
    // find outgoing sequence flow with fulfilled condition or the default (or none if implicit end)
    findSequenceFlowsToTake(element, activating)
        .ifRightOrLeft(
            optFlows -> {
              final var activated = stateTransitionBehavior.transitionToActivated(activating);
              final var completing = stateTransitionBehavior.transitionToCompleting(activated);
              stateTransitionBehavior
                  .transitionToCompleted(element, completing)
                  .ifRightOrLeft(
                      completed -> {
                        if (optFlows != null) {
                          optFlows.forEach(
                              flow -> stateTransitionBehavior.takeSequenceFlow(completed, flow));
                        }
                      },
                      failure -> incidentBehavior.createIncident(failure, completing));
            },
            failure -> incidentBehavior.createIncident(failure, activating));
  }

  @Override
  public void onComplete(
      final ExecutableInclusiveGateway element, final BpmnElementContext context) {
    throw new UnsupportedOperationException(
        String.format(
            "Expected to explicitly process complete, but gateway %s has no wait state",
            BufferUtil.bufferAsString(context.getElementId())));
  }

  @Override
  public void onTerminate(
      final ExecutableInclusiveGateway element, final BpmnElementContext context) {
    incidentBehavior.resolveIncidents(context);
    final var terminated = stateTransitionBehavior.transitionToTerminated(context);
    stateTransitionBehavior.onElementTerminated(element, terminated);
  }

  private Either<Failure, List<ExecutableSequenceFlow>> findSequenceFlowsToTake(
      final ExecutableInclusiveGateway element, final BpmnElementContext context) {
    final List<ExecutableSequenceFlow> executableSequenceFlows = new ArrayList<>();
    if (element.getOutgoing().isEmpty()) {
      // there are no flows to take: the gateway is an implicit end for the flow scope
      return Either.right(null);
    }

    final var outgoingSequenceFlows = element.getOutgoing();
    if (outgoingSequenceFlows.size() == 1 && outgoingSequenceFlows.get(0).getCondition() == null) {
      // only one flow without a condition, can just be taken
      executableSequenceFlows.add(outgoingSequenceFlows.get(0));
      return Either.right(executableSequenceFlows);
    }

    for (final ExecutableSequenceFlow sequenceFlow : element.getOutgoingWithCondition()) {
      if (element.getDefaultFlow() == null || element.getDefaultFlow() != sequenceFlow) {
        final Expression condition = sequenceFlow.getCondition();
        final Either<Failure, Boolean> isFulfilledOrFailure =
            expressionBehavior.evaluateBooleanExpression(
                condition, context.getElementInstanceKey());
        if (isFulfilledOrFailure.isLeft()) {
          return Either.left(isFulfilledOrFailure.getLeft());
        } else if (isFulfilledOrFailure.get()) {
          // the condition is fulfilled
          executableSequenceFlows.add(sequenceFlow);
        }
      }
    }
    if (executableSequenceFlows.size() > 0) {
      return Either.right(executableSequenceFlows);
    }
    // no condition is fulfilled - try to take the default flow
    if (element.getDefaultFlow() != null) {
      executableSequenceFlows.add(element.getDefaultFlow());
      return Either.right(executableSequenceFlows);
    }

    return Either.left(
        new Failure(
            NO_OUTGOING_FLOW_CHOSEN_ERROR,
            ErrorType.CONDITION_ERROR,
            context.getElementInstanceKey()));
  }
}

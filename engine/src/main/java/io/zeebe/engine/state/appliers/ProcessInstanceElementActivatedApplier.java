/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEventSupplier;
import io.zeebe.engine.processing.deployment.model.element.ExecutableFlowElementContainer;
import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.engine.state.mutable.MutableElementInstanceState;
import io.zeebe.engine.state.mutable.MutableEventScopeInstanceState;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;

/** Applies state changes for `ProcessInstance:Element_Activated` */
final class ProcessInstanceElementActivatedApplier
    implements TypedEventApplier<ProcessInstanceIntent, ProcessInstanceRecord> {

  private final MutableElementInstanceState elementInstanceState;
  private final ProcessState processState;
  private final MutableEventScopeInstanceState eventScopeInstanceState;

  public ProcessInstanceElementActivatedApplier(
      final MutableElementInstanceState elementInstanceState,
      final ProcessState processState,
      final MutableEventScopeInstanceState eventScopeInstanceState) {
    this.elementInstanceState = elementInstanceState;
    this.processState = processState;
    this.eventScopeInstanceState = eventScopeInstanceState;
  }

  @Override
  public void applyState(final long key, final ProcessInstanceRecord value) {
    elementInstanceState.updateInstance(
        key, instance -> instance.setState(ProcessInstanceIntent.ELEMENT_ACTIVATED));

    //    var flowElement = processState.getFlowElement(
    //        value.getProcessKey(),
    //        value.getElementIdBuffer(),
    //        ExecutableFlowElement.class);

    //    if (flowElement instanceof ExecutableCatchEventSupplier) {
    //      final var executableCatchEventSupplier = (ExecutableCatchEventSupplier)flowElement;
    //      final var events = executableCatchEventSupplier.getEvents();
    //      if (!events.isEmpty()) {
    //        eventScopeInstanceState.createIfNotExists(
    //            key, executableCatchEventSupplier.getInterruptingElementIds());
    //      }
    //    }

    // TODO simplify code (see e.g. example above)
    if (value.getBpmnElementType() == BpmnElementType.SUB_PROCESS) {

      final var executableFlowElementContainer =
          processState.getFlowElement(
              value.getProcessDefinitionKey(),
              value.getElementIdBuffer(),
              ExecutableFlowElementContainer.class);

      final var events = executableFlowElementContainer.getEvents();
      if (!events.isEmpty()) {
        eventScopeInstanceState.createIfNotExists(
            key, executableFlowElementContainer.getInterruptingElementIds());
      }
    } else if (value.getBpmnElementType() == BpmnElementType.SERVICE_TASK) {

      final var executableCatchEventSupplier =
          processState.getFlowElement(
              value.getProcessDefinitionKey(),
              value.getElementIdBuffer(),
              ExecutableCatchEventSupplier.class);

      final var events = executableCatchEventSupplier.getEvents();
      if (!events.isEmpty()) {
        eventScopeInstanceState.createIfNotExists(
            key, executableCatchEventSupplier.getInterruptingElementIds());
      }
    }
  }
}

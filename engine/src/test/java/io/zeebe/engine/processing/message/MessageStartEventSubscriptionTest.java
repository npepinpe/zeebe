/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.zeebe.engine.util.EngineRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.model.bpmn.builder.ProcessBuilder;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.intent.Intent;
import io.zeebe.protocol.record.intent.MessageStartEventSubscriptionIntent;
import io.zeebe.protocol.record.value.MessageStartEventSubscriptionRecordValue;
import io.zeebe.test.util.record.RecordingExporter;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public final class MessageStartEventSubscriptionTest {
  private static final String MESSAGE_NAME1 = "startMessage1";
  private static final String EVENT_ID1 = "startEventId1";

  private static final String MESSAGE_NAME2 = "startMessage2";
  private static final String EVENT_ID2 = "startEventId2";

  @Rule public final EngineRule engine = EngineRule.singlePartition();

  @Test
  public void shouldOpenMessageSubscriptionOnDeployment() {

    // when
    engine.deployment().withXmlResource(createProcessWithOneMessageStartEvent()).deploy();

    final Record<MessageStartEventSubscriptionRecordValue> subscription =
        RecordingExporter.messageStartEventSubscriptionRecords(
                MessageStartEventSubscriptionIntent.CREATED)
            .getFirst();

    // then
    assertThat(subscription.getValue().getStartEventId()).isEqualTo(EVENT_ID1);
    assertThat(subscription.getValue().getMessageName()).isEqualTo(MESSAGE_NAME1);
  }

  @Test
  public void shouldOpenSubscriptionsForAllMessageStartEvents() {

    // when
    engine.deployment().withXmlResource(createProcessWithTwoMessageStartEvent()).deploy();

    final List<Record<MessageStartEventSubscriptionRecordValue>> subscriptions =
        RecordingExporter.messageStartEventSubscriptionRecords(
                MessageStartEventSubscriptionIntent.CREATED)
            .limit(2)
            .asList();

    // then
    assertThat(subscriptions.size()).isEqualTo(2);

    assertThat(subscriptions)
        .hasSize(2)
        .extracting(Record::getValue)
        .extracting(s -> tuple(s.getMessageName(), s.getStartEventId()))
        .containsExactlyInAnyOrder(
            tuple(MESSAGE_NAME1, EVENT_ID1), tuple(MESSAGE_NAME2, EVENT_ID2));
  }

  @Test
  public void shouldDeleteSubscriptionForOldVersions() {
    // given
    engine.deployment().withXmlResource(createProcessWithOneMessageStartEvent()).deploy();

    // when
    engine.deployment().withXmlResource(createProcessWithOneMessageStartEvent()).deploy();
    // then

    final List<Record<MessageStartEventSubscriptionRecordValue>> subscriptions =
        RecordingExporter.messageStartEventSubscriptionRecords().limit(3).asList();

    final List<Intent> intents =
        subscriptions.stream().map(Record::getIntent).collect(Collectors.toList());

    assertThat(intents)
        .containsExactly(
            MessageStartEventSubscriptionIntent.CREATED,
            MessageStartEventSubscriptionIntent.DELETED,
            MessageStartEventSubscriptionIntent.CREATED);

    final long closingProcessDefinitionKey =
        subscriptions.get(1).getValue().getProcessDefinitionKey();
    assertThat(closingProcessDefinitionKey)
        .isEqualTo(subscriptions.get(0).getValue().getProcessDefinitionKey());
  }

  @Test
  public void shouldDeleteSubscriptionsForAllMessageStartEvents() {
    // given
    engine.deployment().withXmlResource(createProcessWithTwoMessageStartEvent()).deploy();

    final var processDefinitionKey = RecordingExporter.processRecords().getFirst().getKey();

    // when
    engine.deployment().withXmlResource(createProcessWithTwoMessageStartEvent()).deploy();

    // then
    assertThat(
            RecordingExporter.messageStartEventSubscriptionRecords(
                    MessageStartEventSubscriptionIntent.DELETED)
                .limit(2))
        .extracting(r -> r.getValue().getProcessDefinitionKey(), r -> r.getValue().getMessageName())
        .contains(
            tuple(processDefinitionKey, MESSAGE_NAME1), tuple(processDefinitionKey, MESSAGE_NAME2));
  }

  @Test
  public void testLifecycle() {
    // given
    engine.deployment().withXmlResource(createProcessWithOneMessageStartEvent()).deploy();

    engine.message().withName(MESSAGE_NAME1).withCorrelationKey("key-1").publish();

    // when
    engine.deployment().withXmlResource(createProcessWithOneMessageStartEvent()).deploy();

    // then
    assertThat(RecordingExporter.messageStartEventSubscriptionRecords().limit(3))
        .extracting(Record::getRecordType, Record::getIntent)
        .containsExactly(
            tuple(RecordType.EVENT, MessageStartEventSubscriptionIntent.CREATED),
            tuple(RecordType.EVENT, MessageStartEventSubscriptionIntent.CORRELATED),
            tuple(RecordType.EVENT, MessageStartEventSubscriptionIntent.DELETED));
  }

  @Test
  public void shouldHaveSameSubscriptionKey() {
    // given
    engine.deployment().withXmlResource(createProcessWithOneMessageStartEvent()).deploy();

    final var subscriptionKey =
        RecordingExporter.messageStartEventSubscriptionRecords(
                MessageStartEventSubscriptionIntent.CREATED)
            .getFirst()
            .getKey();

    engine.message().withName(MESSAGE_NAME1).withCorrelationKey("key-1").publish();

    // when
    engine.deployment().withXmlResource(createProcessWithOneMessageStartEvent()).deploy();

    // then
    assertThat(RecordingExporter.messageStartEventSubscriptionRecords().limit(3))
        .extracting(Record::getIntent, Record::getKey)
        .containsExactly(
            tuple(MessageStartEventSubscriptionIntent.CREATED, subscriptionKey),
            tuple(MessageStartEventSubscriptionIntent.CORRELATED, subscriptionKey),
            tuple(MessageStartEventSubscriptionIntent.DELETED, subscriptionKey));
  }

  private static BpmnModelInstance createProcessWithOneMessageStartEvent() {
    return Bpmn.createExecutableProcess("processId")
        .startEvent(EVENT_ID1)
        .message(m -> m.name(MESSAGE_NAME1).id("startmsgId"))
        .endEvent()
        .done();
  }

  private static BpmnModelInstance createProcessWithTwoMessageStartEvent() {
    final ProcessBuilder process = Bpmn.createExecutableProcess("processId");
    process.startEvent(EVENT_ID1).message(m -> m.name(MESSAGE_NAME1).id("startmsgId1")).endEvent();
    process.startEvent(EVENT_ID2).message(m -> m.name(MESSAGE_NAME2).id("startmsgId2")).endEvent();

    return process.done();
  }
}

/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.gateway.api.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.camunda.zeebe.gateway.api.util.GatewayTest;
import io.camunda.zeebe.gateway.impl.broker.request.BrokerDeployResourceRequest;
import io.camunda.zeebe.gateway.protocol.GatewayOuterClass.DecisionMetadata;
import io.camunda.zeebe.gateway.protocol.GatewayOuterClass.DecisionRequirementsMetadata;
import io.camunda.zeebe.gateway.protocol.GatewayOuterClass.DeployResourceRequest;
import io.camunda.zeebe.gateway.protocol.GatewayOuterClass.Deployment;
import io.camunda.zeebe.gateway.protocol.GatewayOuterClass.ProcessMetadata;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.DeploymentIntent;
import org.junit.Ignore;
import org.junit.Test;

public final class DeployResourceTest extends GatewayTest {

  @Test
  public void shouldMapRequestAndResponseWithDefaultTenantId() {
    // given
    final var stub = new DeployResourceStub();
    stub.registerWith(brokerClient);

    final String bpmnName = "testProcess.bpmn";
    final String dmnName = "testDecision.dmn";
    final String defaultTenantId = "<default>";

    final var builder = DeployResourceRequest.newBuilder();
    builder.addResourcesBuilder().setName(bpmnName).setContent(ByteString.copyFromUtf8("<xml/>"));
    builder.addResourcesBuilder().setName(dmnName).setContent(ByteString.copyFromUtf8("test"));
    // TODO: test explicit passing of tenantId with #14041

    final var request = builder.build();

    // when
    final var response = client.deployResource(request);

    // then
    assertThat(response.getKey()).isEqualTo(stub.getKey());
    assertThat(response.getDeploymentsCount()).isEqualTo(3);
    assertThat(response.getTenantId()).isEqualTo(defaultTenantId);

    final Deployment firstDeployment = response.getDeployments(0);
    assertThat(firstDeployment.hasProcess()).isTrue();
    assertThat(firstDeployment.hasDecision()).isFalse();
    assertThat(firstDeployment.hasDecisionRequirements()).isFalse();
    final ProcessMetadata process = firstDeployment.getProcess();
    assertThat(process.getBpmnProcessId()).isEqualTo(bpmnName);
    assertThat(process.getResourceName()).isEqualTo(bpmnName);
    assertThat(process.getProcessDefinitionKey()).isEqualTo(stub.getProcessDefinitionKey());
    assertThat(process.getVersion()).isEqualTo(stub.getProcessVersion());
    assertThat(process.getTenantId()).isEqualTo(defaultTenantId);

    final Deployment secondDeployment = response.getDeployments(1);
    assertThat(secondDeployment.hasProcess()).isFalse();
    assertThat(secondDeployment.hasDecision()).isTrue();
    assertThat(secondDeployment.hasDecisionRequirements()).isFalse();
    final DecisionMetadata decision = secondDeployment.getDecision();
    assertThat(decision.getDmnDecisionId()).isEqualTo(dmnName);
    assertThat(decision.getDmnDecisionName()).isEqualTo(dmnName);
    assertThat(decision.getVersion()).isEqualTo(456);
    assertThat(decision.getDecisionKey()).isEqualTo(567);
    assertThat(decision.getDmnDecisionRequirementsId()).isEqualTo(dmnName);
    assertThat(decision.getDecisionRequirementsKey()).isEqualTo(678);
    assertThat(decision.getTenantId()).isEqualTo(defaultTenantId);

    final Deployment thirdDeployment = response.getDeployments(2);
    assertThat(thirdDeployment.hasProcess()).isFalse();
    assertThat(thirdDeployment.hasDecision()).isFalse();
    assertThat(thirdDeployment.hasDecisionRequirements()).isTrue();
    final DecisionRequirementsMetadata drg = thirdDeployment.getDecisionRequirements();
    assertThat(drg.getDmnDecisionRequirementsId()).isEqualTo(dmnName);
    assertThat(drg.getDmnDecisionRequirementsName()).isEqualTo(dmnName);
    assertThat(drg.getVersion()).isEqualTo(456);
    assertThat(drg.getDecisionRequirementsKey()).isEqualTo(678);
    assertThat(drg.getResourceName()).isEqualTo(dmnName);
    assertThat(drg.getTenantId()).isEqualTo(defaultTenantId);

    final BrokerDeployResourceRequest brokerRequest = brokerClient.getSingleBrokerRequest();
    assertThat(brokerRequest.getIntent()).isEqualTo(DeploymentIntent.CREATE);
    assertThat(brokerRequest.getValueType()).isEqualTo(ValueType.DEPLOYMENT);
  }

  @Test
  @Ignore("https://github.com/camunda/zeebe/issues/14041")
  public void shouldMapRequestAndResponseWithCustomTenantId() {
    // given
    final var stub = new DeployResourceStub();
    stub.registerWith(brokerClient);

    final String bpmnName = "testProcess.bpmn";
    final String dmnName = "testDecision.dmn";
    final String tenantId = "test-tenant";

    final var builder = DeployResourceRequest.newBuilder();
    builder.addResourcesBuilder().setName(bpmnName).setContent(ByteString.copyFromUtf8("<xml/>"));
    builder.addResourcesBuilder().setName(dmnName).setContent(ByteString.copyFromUtf8("test"));
    builder.setTenantId(tenantId);

    final var request = builder.build();

    // when
    final var response = client.deployResource(request);

    // then
    assertThat(response.getTenantId()).isEqualTo(tenantId);

    assertThat(response.getDeploymentsCount()).isEqualTo(3);
    final ProcessMetadata process = response.getDeployments(0).getProcess();
    assertThat(process.getTenantId()).isEqualTo(tenantId);

    final DecisionMetadata decision = response.getDeployments(1).getDecision();
    assertThat(decision.getTenantId()).isEqualTo(tenantId);

    final DecisionRequirementsMetadata drg = response.getDeployments(2).getDecisionRequirements();
    assertThat(drg.getTenantId()).isEqualTo(tenantId);
  }
}

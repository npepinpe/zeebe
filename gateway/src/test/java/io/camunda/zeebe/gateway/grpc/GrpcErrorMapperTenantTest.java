/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.gateway.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.camunda.zeebe.gateway.RequestMapper;
import io.camunda.zeebe.gateway.cmd.InvalidTenantRequestException;
import io.camunda.zeebe.util.logging.RecordingAppender;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.slf4j.Log4jLogger;
import org.apache.logging.slf4j.Log4jMarkerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class GrpcErrorMapperTenantTest {

  private final RecordingAppender recorder = new RecordingAppender();
  private final GrpcErrorMapper errorMapper = new GrpcErrorMapper();

  private Logger log;
  private Log4jLogger logger;

  @BeforeEach
  void beforeEach() {
    log = (Logger) LogManager.getLogger(UUID.randomUUID().toString());
    logger = new Log4jLogger(new Log4jMarkerFactory(), log, log.getName());

    recorder.start();
    log.addAppender(recorder);
  }

  @AfterEach
  void tearDown() {
    recorder.stop();
    log.removeAppender(recorder);
  }

  @ParameterizedTest
  @MethodSource("invalidTenantIds")
  void shouldLogInvalidTenantRequestException(
      final String invalidTenantId, final boolean multiTenancyEnabled, final String logMessage) {
    // given
    final String requestName = "DeployResource";
    try {
      RequestMapper.ensureTenantIdSet(requestName, invalidTenantId, multiTenancyEnabled);
      fail("Expected to throw exception");
    } catch (final RuntimeException exception) {
      assertThat(exception).isInstanceOf(InvalidTenantRequestException.class);

      // when
      log.setLevel(Level.DEBUG);
      final StatusRuntimeException statusException = errorMapper.mapError(exception, logger);

      // then
      assertThat(statusException.getStatus().getCode()).isEqualTo(Code.INVALID_ARGUMENT);
      assertThat(recorder.getAppendedEvents()).hasSize(1);
      final LogEvent event = recorder.getAppendedEvents().get(0);
      assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
      assertThat(event.getMessage().getFormattedMessage())
          .contains(invalidTenantId)
          .contains(logMessage);
    }
  }

  @ParameterizedTest
  @MethodSource("validTenantIds")
  void shouldNotLogInvalidTenantRequestException(
      final String invalidTenantId, final boolean multiTenancyEnabled) {
    // given
    final String requestName = "DeployResource";

    // when
    RequestMapper.ensureTenantIdSet(requestName, invalidTenantId, multiTenancyEnabled);

    // then
    assertThat(recorder.getAppendedEvents()).hasSize(0);
  }

  public static Stream<Arguments> invalidTenantIds() {
    return Stream.of(
        Arguments.of("tenant!@#", true, "tenant identifier contains illegal characters"),
        Arguments.of("", true, "no tenant identifier was provided"),
        Arguments.of("     ", true, "no tenant identifier was provided"),
        Arguments.of("a".repeat(35), true, "tenant identifier is longer than 31 characters"),
        Arguments.of("abcde.-  ", true, "tenant identifier contains illegal characters"),
        Arguments.of("tenant-1", false, "multi-tenancy is disabled"));
  }

  public static Stream<Arguments> validTenantIds() {
    return Stream.of(
        Arguments.of("tenant1", true),
        Arguments.of("tenant-2", true),
        Arguments.of("tenant.3", true),
        Arguments.of("tenant.test-5", true),
        Arguments.of("<default>", true),
        Arguments.of("<default>", false),
        Arguments.of("", false));
  }
}

/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.util;

import java.net.Inet6Address;
import java.net.InetSocketAddress;

public final class SocketUtil {
  private static final String DEFAULT_FORMAT = "%s:%d";
  private static final String IPV6_FORMAT = "[%s]:%d";

  private SocketUtil() {}

  public static String toHostAndPortString(InetSocketAddress inetSocketAddress) {
    final String format =
        inetSocketAddress.getAddress() instanceof Inet6Address ? IPV6_FORMAT : DEFAULT_FORMAT;
    return String.format(format, inetSocketAddress.getHostString(), inetSocketAddress.getPort());
  }
}

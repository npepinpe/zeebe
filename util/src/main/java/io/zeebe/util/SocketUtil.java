/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public final class SocketUtil {
  private static final String DEFAULT_FORMAT = "%s:%d";
  private static final String IPV6_FORMAT = "[%s]:%d";

  private SocketUtil() {}

  public static String toHostAndPortString(InetSocketAddress inetSocketAddress) {
    final String format =
        isHostOrIpv4Address(inetSocketAddress.getAddress()) ? DEFAULT_FORMAT : IPV6_FORMAT;
    return String.format(format, inetSocketAddress.getHostString(), inetSocketAddress.getPort());
  }

  private static boolean isHostOrIpv4Address(final InetAddress address) {
    if (address == null) {
      return true;
    } else {
      return address instanceof Inet4Address
          || !address.getHostAddress().equals(address.getHostName());
    }
  }
}

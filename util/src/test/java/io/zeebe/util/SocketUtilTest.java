/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.junit.Test;

public final class SocketUtilTest {

  @Test
  public void shouldPrintDnsAndPort() {
    final String host = "foo.barr";
    final int port = 1234;
    final String hostAndPortString =
        SocketUtil.toHostAndPortString(InetSocketAddress.createUnresolved(host, port));
    assertThat(hostAndPortString).isEqualTo("foo.barr:1234");
  }

  @Test
  public void shouldPrintDnsThatResolvesInIpv6() {
    final String host = "foo.bar";
    final int port = 1234;
    final InetSocketAddress testAddress = new InetSocketAddress(host, port);
    final InetAddress testInetAddress = testAddress.getAddress();
    assertThat(testInetAddress).isNotNull();
    assertThat(testInetAddress).isInstanceOf(Inet6Address.class);
    final String hostAndPortString = SocketUtil.toHostAndPortString(testAddress);
    assertThat(hostAndPortString).isEqualTo("foo.bar:1234");
  }

  @Test
  public void shouldPrintDnsThatResolvesInIpv4() {
    final String host = "foo4.bar";
    final int port = 1234;
    final InetSocketAddress testAddress = new InetSocketAddress(host, port);
    final InetAddress testInetAddress = testAddress.getAddress();
    assertThat(testInetAddress).isNotNull();
    assertThat(testInetAddress).isInstanceOf(Inet4Address.class);
    final String hostAndPortString = SocketUtil.toHostAndPortString(testAddress);
    assertThat(hostAndPortString).isEqualTo("foo4.bar:1234");
  }

  @Test
  public void shouldPrintIpv6AddressHost() throws UnknownHostException {
    final InetAddress testInet6Address = Inet6Address.getByName("1112::2");
    final int port = 1234;
    final InetSocketAddress testAddress = new InetSocketAddress(testInet6Address, port);
    final String hostAndPortString = SocketUtil.toHostAndPortString(testAddress);
    assertThat(hostAndPortString).isEqualTo("[1112:0:0:0:0:0:0:2]:1234");
  }

  @Test
  public void shouldPrintIpv4AddressHost() throws UnknownHostException {
    final InetAddress testInet6Address = Inet6Address.getByName("127.0.0.1");
    final int port = 1234;
    final InetSocketAddress testAddress = new InetSocketAddress(testInet6Address, port);
    final String hostAndPortString = SocketUtil.toHostAndPortString(testAddress);
    assertThat(hostAndPortString).isEqualTo("127.0.0.1:1234");
  }
}

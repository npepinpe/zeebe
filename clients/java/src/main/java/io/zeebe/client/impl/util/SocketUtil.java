/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.client.impl.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * This class is the replica of {@link io.zeebe.util.SocketUtil} because zeebe-util uses JDK 11, but
 * the java client module uses JDK 8. TODO: If these classes will grow up consider deduplicate them.
 */
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

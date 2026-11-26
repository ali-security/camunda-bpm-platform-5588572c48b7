/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
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
package org.camunda.spin.impl.json.jackson.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.camunda.spin.Spin.JSON;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

import org.camunda.spin.json.SpinJsonException;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

/**
 * The jackson-databind that Spin builds against must deserialize
 * {@link InetAddress} values from IP address literals only: any other string
 * used to be handed to {@link InetAddress#getByName(String)}, which resolves it
 * as a host name, so a JSON payload could make the engine issue DNS lookups for
 * attacker-chosen hosts (CVE-2026-77310). Strings that look like literals but
 * use non-ASCII digits, which {@link Character#digit(char, int)} accepts but
 * {@link InetAddress#getByName(String)} resolves as host names, must be
 * rejected as well.
 */
public class JacksonInetAddressTest {

  protected static final String NOT_A_LITERAL = "Not a valid IP address string literal";

  protected final JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test");
  protected final ObjectMapper objectMapper = dataFormat.getObjectMapper();

  public static class Host {
    public InetAddress address;
  }

  @Test
  public void shouldNotResolveHostNames() throws Exception {
    // resolvable without any network access, so a lookup would succeed
    assertRejected("localhost");
    assertRejected("google.com");
    // starts off looking like an IP address literal
    assertRejected("1.2.3.4.example.com");
    // punycode
    assertRejected("xn--bcher-kva.example.com");
  }

  @Test
  public void shouldNotResolveLiteralsWithNonAsciiDigits() throws Exception {
    // unicode escapes on purpose: these digits look like the ASCII ones they mimic
    assertRejected("１２７.0.0.1"); // full-width "127".0.0.1
    assertRejected("١٢٧.0.0.1"); // Arabic-Indic "127".0.0.1
    assertRejected("２001:db8::1"); // full-width digit in an IPv6 hextet
    assertRejected("fe80::１"); // full-width digit in an IPv6 hextet
    assertRejected("ａbcd::1"); // full-width hex letter "a" in an IPv6 hextet
  }

  @Test
  public void shouldAcceptIPv4Literal() throws Exception {
    InetAddress address = objectMapper.readValue("\"127.0.0.1\"", InetAddress.class);

    assertThat(address).isInstanceOf(Inet4Address.class);
    assertThat(address.getHostAddress()).isEqualTo("127.0.0.1");
  }

  @Test
  public void shouldAcceptIPv6Literals() throws Exception {
    InetAddress address = objectMapper.readValue("\"2001:db8:85a3:8d3:1319:8a2e:370:7348\"", InetAddress.class);
    assertThat(address).isInstanceOf(Inet6Address.class);
    assertThat(address.getHostAddress()).isEqualTo("2001:db8:85a3:8d3:1319:8a2e:370:7348");

    InetAddress loopback = objectMapper.readValue("\"::1\"", InetAddress.class);
    assertThat(loopback.getHostAddress()).isEqualTo("0:0:0:0:0:0:0:1");

    // IPv4-mapped IPv6 address with an embedded dotted quad
    InetAddress mapped = objectMapper.readValue("\"::ffff:127.0.0.1\"", InetAddress.class);
    assertThat(mapped.getHostAddress()).isEqualTo("127.0.0.1");
  }

  @Test
  public void shouldNotResolveHostNameWhenMappingJsonToJava() {
    try {
      Host host = JSON("{\"address\":\"localhost\"}").mapTo(Host.class);
      fail("InetAddress host name resolved while mapping JSON to Java: " + host.address);
    } catch (SpinJsonException e) {
      assertThat(e.getCause()).isInstanceOf(InvalidFormatException.class);
      assertThat(e.getCause()).hasMessageContaining(NOT_A_LITERAL);
    }
  }

  @Test
  public void shouldNotResolveHostNameWhenMappingInternalToJava() {
    JsonNode node = dataFormat.createJsonNode("localhost");

    try {
      InetAddress address = dataFormat.getMapper().mapInternalToJava(node, InetAddress.class.getName());
      fail("InetAddress host name resolved while mapping internal to Java: " + address);
    } catch (SpinJsonException e) {
      assertThat(e.getCause()).isInstanceOf(InvalidFormatException.class);
      assertThat(e.getCause()).hasMessageContaining(NOT_A_LITERAL);
    }
  }

  @Test
  public void shouldMapIPLiteralWhenMappingJsonToJava() {
    Host host = JSON("{\"address\":\"127.0.0.1\"}").mapTo(Host.class);

    assertThat(host.address.getHostAddress()).isEqualTo("127.0.0.1");
  }

  protected void assertRejected(String value) throws Exception {
    try {
      InetAddress address = objectMapper.readValue("\"" + value + "\"", InetAddress.class);
      fail("InetAddress deserialized from '" + value + "', which is not an IP address literal: " + address);
    } catch (InvalidFormatException e) {
      assertThat(e).hasMessageContaining(NOT_A_LITERAL);
    }
  }

}

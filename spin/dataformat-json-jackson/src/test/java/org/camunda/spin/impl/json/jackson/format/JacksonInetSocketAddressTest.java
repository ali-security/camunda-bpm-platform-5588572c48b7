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
import static org.camunda.spin.Spin.JSON;

import java.net.InetSocketAddress;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The jackson-databind that Spin builds against must deserialize
 * {@link InetSocketAddress} values without resolving their host name, so that
 * a JSON payload cannot make the engine issue DNS lookups for attacker-chosen
 * hosts (CVE-2026-54514), and must keep unresolved IPv6 hosts bracketed when
 * serializing them, so the port stays unambiguous.
 */
public class JacksonInetSocketAddressTest {

  protected final JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test");
  protected final ObjectMapper objectMapper = dataFormat.getObjectMapper();

  public static class Endpoint {
    public InetSocketAddress address;
  }

  @Test
  public void shouldNotResolveHostAndPort() throws Exception {
    InetSocketAddress address = objectMapper.readValue("\"localhost:9999\"", InetSocketAddress.class);

    assertThat(address.isUnresolved())
        .as("InetSocketAddress resolved localhost during deserialization: %s", address)
        .isTrue();
    assertThat(address.getHostString()).isEqualTo("localhost");
    assertThat(address.getPort()).isEqualTo(9999);
  }

  @Test
  public void shouldNotResolveHostWithoutPort() throws Exception {
    InetSocketAddress address = objectMapper.readValue("\"localhost\"", InetSocketAddress.class);

    assertThat(address.isUnresolved())
        .as("InetSocketAddress resolved localhost during deserialization: %s", address)
        .isTrue();
    assertThat(address.getHostString()).isEqualTo("localhost");
    assertThat(address.getPort()).isEqualTo(0);
  }

  @Test
  public void shouldNotResolveIPv4Literal() throws Exception {
    InetSocketAddress address = objectMapper.readValue("\"127.0.0.1:8080\"", InetSocketAddress.class);

    assertThat(address.isUnresolved()).isTrue();
    assertThat(address.getHostString()).isEqualTo("127.0.0.1");
    assertThat(address.getPort()).isEqualTo(8080);
  }

  @Test
  public void shouldNotResolveBracketedIPv6WithPort() throws Exception {
    InetSocketAddress address = objectMapper.readValue("\"[::1]:443\"", InetSocketAddress.class);

    assertThat(address.isUnresolved()).isTrue();
    assertThat(address.getHostString()).isEqualTo("[::1]");
    assertThat(address.getPort()).isEqualTo(443);
  }

  @Test
  public void shouldNotResolveHostWhenMappingJsonToJava() {
    Endpoint endpoint = JSON("{\"address\":\"localhost:9999\"}").mapTo(Endpoint.class);

    assertThat(endpoint.address.isUnresolved())
        .as("InetSocketAddress resolved localhost while mapping JSON to Java: %s", endpoint.address)
        .isTrue();
    assertThat(endpoint.address.getHostString()).isEqualTo("localhost");
    assertThat(endpoint.address.getPort()).isEqualTo(9999);
  }

  @Test
  public void shouldNotResolveHostWhenMappingInternalToJava() {
    JsonNode node = dataFormat.createJsonNode("localhost:9999");

    InetSocketAddress address = dataFormat.getMapper()
        .mapInternalToJava(node, InetSocketAddress.class.getName());

    assertThat(address.isUnresolved()).isTrue();
    assertThat(address.getHostString()).isEqualTo("localhost");
    assertThat(address.getPort()).isEqualTo(9999);
  }

  @Test
  public void shouldBracketUnresolvedIPv6Host() throws Exception {
    assertThat(objectMapper.writeValueAsString(InetSocketAddress.createUnresolved("2001:db8::1", 443)))
        .isEqualTo("\"[2001:db8::1]:443\"");
    // a host name stored with brackets must not be bracketed twice
    assertThat(objectMapper.writeValueAsString(InetSocketAddress.createUnresolved("[2001:db8::1]", 443)))
        .isEqualTo("\"[2001:db8::1]:443\"");

    JsonNode node = (JsonNode) dataFormat.getMapper()
        .mapJavaToInternal(InetSocketAddress.createUnresolved("2001:db8::1", 443));
    assertThat(node.textValue()).isEqualTo("[2001:db8::1]:443");
  }

  @Test
  public void shouldKeepUnresolvedHostNamesAndIPv4Unbracketed() throws Exception {
    assertThat(objectMapper.writeValueAsString(InetSocketAddress.createUnresolved("127.0.0.1", 8080)))
        .isEqualTo("\"127.0.0.1:8080\"");
    assertThat(objectMapper.writeValueAsString(InetSocketAddress.createUnresolved("example.com", 6667)))
        .isEqualTo("\"example.com:6667\"");
  }

  @Test
  public void shouldRoundTripUnresolvedIPv6Address() throws Exception {
    InetSocketAddress address = objectMapper.readValue("\"[2001:db8::1]:443\"", InetSocketAddress.class);
    String json = objectMapper.writeValueAsString(address);
    assertThat(json).isEqualTo("\"[2001:db8::1]:443\"");

    InetSocketAddress result = objectMapper.readValue(json, InetSocketAddress.class);
    assertThat(result.isUnresolved()).isTrue();
    assertThat(result.getHostString()).isEqualTo(address.getHostString());
    assertThat(result.getPort()).isEqualTo(443);
  }

  @Test
  public void shouldRoundTripUnbracketedIPv6AddressWithoutPort() throws Exception {
    InetSocketAddress address = objectMapper.readValue("\"2001:db8::1\"", InetSocketAddress.class);
    assertThat(address.isUnresolved()).isTrue();
    assertThat(address.getHostString()).isEqualTo("2001:db8::1");

    String json = objectMapper.writeValueAsString(address);
    assertThat(json).isEqualTo("\"[2001:db8::1]:0\"");

    InetSocketAddress result = objectMapper.readValue(json, InetSocketAddress.class);
    assertThat(result.isUnresolved()).isTrue();
    assertThat(result.getHostString()).isEqualTo("[2001:db8::1]");
    assertThat(result.getPort()).isEqualTo(0);
  }

}

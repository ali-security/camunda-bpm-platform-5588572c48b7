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

import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The jackson-databind that Spin builds against must keep the per-property
 * {@link JsonIgnoreProperties} (and {@link JsonIncludeProperties}) of a property
 * whose {@link JsonFormat} changes {@code ACCEPT_CASE_INSENSITIVE_PROPERTIES}:
 * the case-insensitive property map must be rebuilt from the filtered
 * properties, so that a JSON payload cannot set an ignored property, neither
 * in its exact case nor in a different case (CVE-2026-54515).
 */
public class JacksonIgnorePropertiesCaseInsensitiveTest {

  protected final ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();

  public static class AdminDto {
    public String adminKey = "DEFAULT";
    public String username;
  }

  /** Ignores {@code adminKey} on the nested value and matches its properties case-insensitively. */
  public static class Container {
    @JsonIgnoreProperties("adminKey")
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    public AdminDto admin;
  }

  /** Only ignores {@code adminKey} on the nested value. */
  public static class BaselineContainer {
    @JsonIgnoreProperties("adminKey")
    public AdminDto admin;
  }

  /** Includes only {@code username} of the nested value and matches its properties case-insensitively. */
  public static class IncludingContainer {
    @JsonIncludeProperties("username")
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    public AdminDto admin;
  }

  /** Matches the properties of the nested value case-insensitively. */
  public static class CaseInsensitiveContainer {
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    public AdminDto admin;
  }

  /** Ignores {@code adminKey} on the nested value and matches its properties case-sensitively. */
  public static class CaseSensitiveContainer {
    @JsonIgnoreProperties("adminKey")
    @JsonFormat(without = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    public AdminDto admin;
  }

  protected static final String PAYLOAD = "{\"admin\":{\"adminKey\":\"HACKED\",\"username\":\"alice\"}}";

  protected static final String MIXED_CASE_PAYLOAD = "{\"admin\":{\"AdminKey\":\"HACKED\",\"username\":\"alice\"}}";

  protected static final String UPPER_CASE_PAYLOAD = "{\"admin\":{\"ADMINKEY\":\"HACKED\",\"USERNAME\":\"alice\"}}";

  @Test
  public void shouldNotSetIgnoredPropertyWithoutCaseInsensitivity() throws Exception {
    BaselineContainer result = objectMapper.readValue(PAYLOAD, BaselineContainer.class);

    assertThat(result.admin.username).isEqualTo("alice");
    assertThat(result.admin.adminKey).isEqualTo("DEFAULT");
  }

  @Test
  public void shouldNotSetIgnoredPropertyOfCaseInsensitiveProperty() throws Exception {
    Container result = objectMapper.readValue(PAYLOAD, Container.class);

    assertThat(result.admin.username).isEqualTo("alice");
    assertThat(result.admin.adminKey)
        .as("adminKey ignored by @JsonIgnoreProperties was set through the case-insensitive property map")
        .isEqualTo("DEFAULT");
  }

  @Test
  public void shouldNotSetIgnoredPropertyOfCaseInsensitivePropertyInDifferentCase() throws Exception {
    ObjectReader reader = objectMapper.readerFor(Container.class)
        .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    Container mixedCase = reader.readValue(MIXED_CASE_PAYLOAD);
    Container upperCase = reader.readValue(UPPER_CASE_PAYLOAD);

    assertThat(mixedCase.admin.username).isEqualTo("alice");
    assertThat(mixedCase.admin.adminKey)
        .as("adminKey ignored by @JsonIgnoreProperties was set as AdminKey")
        .isEqualTo("DEFAULT");
    assertThat(upperCase.admin.username).isEqualTo("alice");
    assertThat(upperCase.admin.adminKey)
        .as("adminKey ignored by @JsonIgnoreProperties was set as ADMINKEY")
        .isEqualTo("DEFAULT");
  }

  /**
   * Ignored in another case, the property is unknown to the case-insensitive
   * property map, so it fails like any other unknown property.
   */
  @Test(expected = UnrecognizedPropertyException.class)
  public void shouldRejectIgnoredPropertyOfCaseInsensitivePropertyInDifferentCase() throws Exception {
    objectMapper.readValue(MIXED_CASE_PAYLOAD, Container.class);
  }

  @Test
  public void shouldNotSetPropertyNotIncludedByCaseInsensitiveProperty() throws Exception {
    IncludingContainer exactCase = objectMapper.readValue(PAYLOAD, IncludingContainer.class);
    IncludingContainer mixedCase = objectMapper.readValue(MIXED_CASE_PAYLOAD, IncludingContainer.class);

    assertThat(exactCase.admin.username).isEqualTo("alice");
    assertThat(exactCase.admin.adminKey)
        .as("adminKey not included by @JsonIncludeProperties was set through the case-insensitive property map")
        .isEqualTo("DEFAULT");
    assertThat(mixedCase.admin.username).isEqualTo("alice");
    assertThat(mixedCase.admin.adminKey)
        .as("adminKey not included by @JsonIncludeProperties was set as AdminKey")
        .isEqualTo("DEFAULT");
  }

  /**
   * Turning case-insensitivity off for one property rebuilds the property map
   * too, here from the one that {@link MapperFeature#ACCEPT_CASE_INSENSITIVE_PROPERTIES}
   * made case-insensitive.
   */
  @Test
  public void shouldNotSetIgnoredPropertyOfCaseSensitiveProperty() throws Exception {
    ObjectMapper caseInsensitiveMapper = JsonMapper.builder()
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        .build();
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test", caseInsensitiveMapper);

    CaseSensitiveContainer result = dataFormat.getObjectMapper().readValue(PAYLOAD, CaseSensitiveContainer.class);

    assertThat(result.admin.username).isEqualTo("alice");
    assertThat(result.admin.adminKey)
        .as("adminKey ignored by @JsonIgnoreProperties was set through the case-sensitive property map")
        .isEqualTo("DEFAULT");
  }

  @Test
  public void shouldNotSetIgnoredPropertyWhenMappingJsonToJava() {
    Container result = JSON(PAYLOAD).mapTo(Container.class);

    assertThat(result.admin.username).isEqualTo("alice");
    assertThat(result.admin.adminKey)
        .as("adminKey ignored by @JsonIgnoreProperties was set while mapping JSON to Java")
        .isEqualTo("DEFAULT");
  }

  @Test
  public void shouldMatchPropertiesCaseInsensitively() throws Exception {
    String payload = UPPER_CASE_PAYLOAD.replace("HACKED", "OK");

    Container restricted = objectMapper.readerFor(Container.class)
        .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .readValue(payload);
    CaseInsensitiveContainer unrestricted = objectMapper.readValue(payload, CaseInsensitiveContainer.class);

    assertThat(restricted.admin.username).isEqualTo("alice");
    assertThat(restricted.admin.adminKey).isEqualTo("DEFAULT");
    // without @JsonIgnoreProperties every property is set, in any case
    assertThat(unrestricted.admin.username).isEqualTo("alice");
    assertThat(unrestricted.admin.adminKey).isEqualTo("OK");
  }

}

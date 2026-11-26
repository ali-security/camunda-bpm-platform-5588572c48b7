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

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.camunda.spin.json.SpinJsonException;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.DefaultBaseTypeLimitingValidator;

/**
 * The jackson-databind that Spin builds against must treat
 * {@link Comparable} as an unsafe polymorphic base type when
 * {@link MapperFeature#BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES} is enabled, like
 * {@link Object} and {@link java.io.Serializable}: {@code Comparable} is
 * implemented by a large number of types, gadget types included, so a
 * polymorphic {@code Comparable} property
 * ({@code @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)}) used to let a JSON
 * payload pick any {@code Comparable} implementation as the sub-type
 * (CVE-2026-83557).
 */
public class JacksonPolymorphicComparableBaseTypeTest {

  protected static final String DENIED_BASE_TYPE = "all subtypes of base type";
  protected static final String COMPARABLE = Comparable.class.getName();

  /** Records every instantiation of the gadget type. */
  protected static final List<String> INSTANTIATIONS = new CopyOnWriteArrayList<>();

  protected static final String GADGET = ComparableGadget.class.getName();

  /** Stand-in for a deserialization gadget that implements {@link Comparable}. */
  public static class ComparableGadget implements Comparable<ComparableGadget> {
    public String cmd;

    public ComparableGadget() {
      INSTANTIATIONS.add(ComparableGadget.class.getName());
    }

    @Override
    public int compareTo(ComparableGadget other) {
      return 0;
    }
  }

  public static class ComparableWrapper {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    public Comparable<?> value;
  }

  public static class NumberWrapper {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    public Number value;
  }

  /**
   * Opens the {@code Comparable} base type up again, but only for the sub-types
   * it knows to be safe: relaxing the base type check alone would leave all
   * gadget types accessible.
   */
  public static class ComparablesAreOkValidator extends DefaultBaseTypeLimitingValidator {
    private static final long serialVersionUID = 1L;

    @Override
    protected boolean isUnsafeBaseType(MapperConfig<?> config, JavaType baseType) {
      if (baseType.hasRawClass(Comparable.class)) {
        return false;
      }
      return super.isUnsafeBaseType(config, baseType);
    }

    @Override
    protected boolean isSafeSubType(MapperConfig<?> config, JavaType baseType, JavaType subType) {
      if (baseType.hasRawClass(Comparable.class)) {
        return subType.hasRawClass(File.class);
      }
      return super.isSafeSubType(config, baseType, subType);
    }
  }

  @Before
  public void clearInstantiations() {
    INSTANTIATIONS.clear();
  }

  @Test
  public void shouldRejectComparableBaseType() throws Exception {
    ObjectMapper objectMapper = blockingDataFormat().getObjectMapper();

    try {
      ComparableWrapper result = objectMapper.readValue("{\"value\":[\"java.io.File\",\"/tmp/stuff\"]}",
          ComparableWrapper.class);
      fail("Sub-type of the unsafe base type Comparable deserialized: " + result.value);
    } catch (InvalidDefinitionException e) {
      assertThat(e).hasMessageContaining("Configured");
      assertThat(e).hasMessageContaining(DENIED_BASE_TYPE);
      assertThat(e).hasMessageContaining(COMPARABLE);
    }
  }

  @Test
  public void shouldNotInstantiateComparableGadget() throws Exception {
    ObjectMapper objectMapper = blockingDataFormat().getObjectMapper();

    try {
      ComparableWrapper result = objectMapper.readValue(gadgetJson(), ComparableWrapper.class);
      fail("Gadget deserialized through the unsafe base type Comparable: " + result.value);
    } catch (InvalidDefinitionException e) {
      assertThat(e).hasMessageContaining(DENIED_BASE_TYPE);
      assertThat(e).hasMessageContaining(COMPARABLE);
    }
    assertThat(INSTANTIATIONS)
        .as("gadget types instantiated")
        .isEmpty();
  }

  @Test
  public void shouldRejectComparableBaseTypeWhenMappingInternalToJava() throws Exception {
    JacksonJsonDataFormat dataFormat = blockingDataFormat();
    JsonNode node = dataFormat.getObjectMapper().readTree(gadgetJson());

    try {
      ComparableWrapper result = dataFormat.getMapper().mapInternalToJava(node, ComparableWrapper.class);
      fail("Gadget mapped through the unsafe base type Comparable: " + result.value);
    } catch (SpinJsonException e) {
      assertThat(e.getCause()).isInstanceOf(InvalidDefinitionException.class);
      assertThat(e.getCause()).hasMessageContaining(DENIED_BASE_TYPE);
      assertThat(e.getCause()).hasMessageContaining(COMPARABLE);
    }
    assertThat(INSTANTIATIONS)
        .as("gadget types instantiated")
        .isEmpty();
  }

  @Test
  public void shouldAllowOnlyApprovedSubTypesWithCustomValidator() throws Exception {
    ObjectMapper objectMapper = JsonMapper.builder()
        .enable(MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES)
        .polymorphicTypeValidator(new ComparablesAreOkValidator())
        .build();
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test", objectMapper);

    ComparableWrapper result = dataFormat.getObjectMapper()
        .readValue("{\"value\":[\"java.io.File\",\"/tmp/stuff\"]}", ComparableWrapper.class);
    assertThat(result.value).isEqualTo(new File("/tmp/stuff"));

    try {
      ComparableWrapper denied = dataFormat.getObjectMapper().readValue(gadgetJson(), ComparableWrapper.class);
      fail("Sub-type that the validator does not approve deserialized: " + denied.value);
    } catch (InvalidTypeIdException e) {
      assertThat(e).hasMessageContaining("Could not resolve type id '" + GADGET + "'");
      assertThat(e).hasMessageContaining("denied resolution");
    }
    assertThat(INSTANTIATIONS)
        .as("gadget types instantiated")
        .isEmpty();
  }

  @Test
  public void shouldStillAcceptSafeBaseType() throws Exception {
    ObjectMapper objectMapper = blockingDataFormat().getObjectMapper();

    NumberWrapper result = objectMapper.readValue("{\"value\":[\"java.lang.Integer\",10]}", NumberWrapper.class);

    assertThat(result.value).isEqualTo(Integer.valueOf(10));
  }

  protected static JacksonJsonDataFormat blockingDataFormat() {
    ObjectMapper objectMapper = JsonMapper.builder()
        .enable(MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES)
        .build();
    return new JacksonJsonDataFormat("test", objectMapper);
  }

  protected static String gadgetJson() {
    return "{\"value\":[\"" + GADGET + "\",{\"cmd\":\"x\"}]}";
  }

}

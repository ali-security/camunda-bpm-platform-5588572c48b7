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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.camunda.spin.json.SpinJsonException;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;

/**
 * The jackson-databind that Spin builds against must validate the type
 * parameters of a generic type id against the {@link PolymorphicTypeValidator},
 * not only its raw container class: when a {@link BasicPolymorphicTypeValidator}
 * allow-lists a safe container by name, e.g. {@code java.util.ArrayList}, a
 * JSON payload must not smuggle a type that the allow-list denies in as a type
 * parameter, e.g. {@code java.util.ArrayList<com.example.Evil>}, otherwise the
 * denied type is instantiated (CVE-2026-54512).
 */
public class JacksonPolymorphicTypeValidatorGenericTest {

  /** Records every instantiation of the types that are not allow-listed. */
  protected static final List<String> INSTANTIATIONS = new CopyOnWriteArrayList<>();

  protected static final String GADGET = FakeGadget.class.getName();
  protected static final String SAFE = SafePayload.class.getName();

  /**
   * Name of this test class: as a name prefix it matches every nested type
   * declared here, e.g. {@code ...JacksonPolymorphicTypeValidatorGenericTest$SafePayload}.
   */
  protected static final String OWN_CLASS_NAME_PREFIX = JacksonPolymorphicTypeValidatorGenericTest.class.getName();

  /**
   * Stand-in for a deserialization gadget; not allow-listed. {@code final}, so
   * the elements of an {@code ArrayList<FakeGadget>} carry no type id of their
   * own and the generic type id is the only one the validator sees.
   */
  public static final class FakeGadget {
    public String cmd;

    public FakeGadget() {
      INSTANTIATIONS.add(FakeGadget.class.getName());
    }
  }

  /** Allow-listed type. */
  public static final class SafePayload {
    public int data;
  }

  /** Not allow-listed enum, element of an {@code EnumSet}. */
  public enum NotAllowListedEnum {
    VALUE_A, VALUE_B
  }

  public static final class ObjectWrapper {
    public Object value;
  }

  public static final class AnnotatedWrapper {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    public Object value;
  }

  @Before
  public void clearInstantiations() {
    INSTANTIATIONS.clear();
  }

  @Test
  public void shouldRejectNotAllowListedTypeParameter() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();
    String json = wrapper("java.util.ArrayList<" + GADGET + ">", "[{\"cmd\":\"x\"}]");

    assertRejected(objectMapper, json, GADGET);
  }

  @Test
  public void shouldRejectNotAllowListedTypeParameterOfNestedGeneric() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();
    // The inner ArrayList is not final, so default typing gives it a type id of its own.
    String json = wrapper("java.util.ArrayList<java.util.ArrayList<" + GADGET + ">>",
        "[[\"java.util.ArrayList\",[{\"cmd\":\"x\"}]]]");

    assertRejected(objectMapper, json, GADGET);
  }

  @Test
  public void shouldRejectNotAllowListedMapValueTypeParameter() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();
    String json = wrapper("java.util.HashMap<java.lang.String," + GADGET + ">", "{\"k\":{\"cmd\":\"x\"}}");

    assertRejected(objectMapper, json, GADGET);
  }

  @Test
  public void shouldRejectNotAllowListedMapKeyTypeParameter() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();
    String json = wrapper("java.util.HashMap<" + GADGET + ",java.lang.String>", "{}");

    assertRejected(objectMapper, json, GADGET);
  }

  @Test
  public void shouldRejectNotAllowListedArrayTypeParameter() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();
    String json = wrapper("java.util.ArrayList<[L" + GADGET + ";>", "[[{\"cmd\":\"x\"}]]");

    assertRejected(objectMapper, json, GADGET);
  }

  @Test
  public void shouldRejectNotAllowListedTypeParameterWithJsonTypeInfo() throws Exception {
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();
    objectMapper.setPolymorphicTypeValidator(polymorphicTypeValidator());
    String json = wrapper("java.util.ArrayList<" + GADGET + ">", "[{\"cmd\":\"x\"}]");

    try {
      AnnotatedWrapper result = objectMapper.readValue(json, AnnotatedWrapper.class);
      fail("Not allow-listed type deserialized from a generic type id: " + result.value);
    } catch (InvalidTypeIdException e) {
      assertThat(e).hasMessageContaining(GADGET);
    }
    assertThat(INSTANTIATIONS).isEmpty();
  }

  @Test
  public void shouldRejectNotAllowListedTypeParameterWhenMappingInternalToJava() throws Exception {
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test");
    dataFormat.getObjectMapper().activateDefaultTyping(polymorphicTypeValidator(), DefaultTyping.NON_FINAL);
    JsonNode node = dataFormat.getObjectMapper()
        .readTree(wrapper("java.util.ArrayList<" + GADGET + ">", "[{\"cmd\":\"x\"}]"));

    try {
      ObjectWrapper result = dataFormat.getMapper().mapInternalToJava(node, ObjectWrapper.class);
      fail("Not allow-listed type mapped from a generic type id: " + result.value);
    } catch (SpinJsonException e) {
      assertThat(e.getCause()).isInstanceOf(InvalidTypeIdException.class);
      assertThat(e.getCause()).hasMessageContaining(GADGET);
    }
    assertThat(INSTANTIATIONS).isEmpty();
  }

  @Test
  public void shouldAcceptAllowListedTypeParameter() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();
    String json = wrapper("java.util.ArrayList<" + SAFE + ">", "[{\"data\":42}]");

    ObjectWrapper result = objectMapper.readValue(json, ObjectWrapper.class);

    assertThat(result.value).isInstanceOf(ArrayList.class);
    List<?> payloads = (List<?>) result.value;
    assertThat(payloads).hasSize(1);
    assertThat(payloads.get(0)).isInstanceOf(SafePayload.class);
    assertThat(((SafePayload) payloads.get(0)).data).isEqualTo(42);
  }

  @Test
  public void shouldAcceptAllowListedMapTypeParameters() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();
    String json = wrapper("java.util.HashMap<java.lang.String," + SAFE + ">", "{\"k\":{\"data\":42}}");

    ObjectWrapper result = objectMapper.readValue(json, ObjectWrapper.class);

    assertThat(result.value).isInstanceOf(HashMap.class);
    Map<?, ?> payloads = (Map<?, ?>) result.value;
    assertThat(payloads).hasSize(1);
    assertThat(payloads.get("k")).isInstanceOf(SafePayload.class);
    assertThat(((SafePayload) payloads.get("k")).data).isEqualTo(42);
  }

  @Test
  public void shouldAcceptObjectTypeParameter() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();
    String json = wrapper("java.util.ArrayList<java.lang.Object>", "[]");

    ObjectWrapper result = objectMapper.readValue(json, ObjectWrapper.class);

    assertThat(result.value).isInstanceOf(ArrayList.class);
    assertThat((List<?>) result.value).isEmpty();
  }

  @Test
  public void shouldAcceptTypeParameterAllowListedByNamePrefix() throws Exception {
    PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
        .allowIfSubType("java.util.ArrayList")
        .allowIfSubType(OWN_CLASS_NAME_PREFIX)
        .build();
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();
    objectMapper.activateDefaultTyping(ptv, DefaultTyping.NON_FINAL);
    String json = wrapper("java.util.ArrayList<" + SAFE + ">", "[{\"data\":42}]");

    ObjectWrapper result = objectMapper.readValue(json, ObjectWrapper.class);

    assertThat(result.value).isInstanceOf(ArrayList.class);
    assertThat(((List<?>) result.value).get(0)).isInstanceOf(SafePayload.class);
  }

  @Test
  public void shouldRejectTypeParameterNotAllowListedByNamePrefix() throws Exception {
    PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
        .allowIfSubType("java.util.ArrayList")
        .allowIfSubType("com.example.")
        .build();
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();
    objectMapper.activateDefaultTyping(ptv, DefaultTyping.NON_FINAL);
    String json = wrapper("java.util.ArrayList<" + GADGET + ">", "[{\"cmd\":\"x\"}]");

    assertRejected(objectMapper, json, GADGET);
  }

  @Test
  public void shouldAcceptArrayElementTypeAllowListedByNamePrefix() throws Exception {
    PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
        .allowIfSubType(OWN_CLASS_NAME_PREFIX)
        .allowIfSubTypeIsArray()
        .build();
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();
    objectMapper.activateDefaultTyping(ptv, DefaultTyping.NON_FINAL);
    String json = wrapper("[L" + SAFE + ";", "[{\"data\":42}]");

    ObjectWrapper result = objectMapper.readValue(json, ObjectWrapper.class);

    assertThat(result.value).isInstanceOf(SafePayload[].class);
    assertThat(((SafePayload[]) result.value)[0].data).isEqualTo(42);
  }

  @Test
  public void shouldAcceptEnumTypeParameter() throws Exception {
    PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
        .allowIfSubType("java.")
        .build();
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();
    objectMapper.setPolymorphicTypeValidator(ptv);
    String json = wrapper("java.util.EnumSet<" + NotAllowListedEnum.class.getName() + ">", "[\"VALUE_A\"]");

    AnnotatedWrapper result = objectMapper.readValue(json, AnnotatedWrapper.class);

    assertThat(result.value).isEqualTo(EnumSet.of(NotAllowListedEnum.VALUE_A));
  }

  protected void assertRejected(ObjectMapper objectMapper, String json, String rejectedType) throws Exception {
    try {
      ObjectWrapper result = objectMapper.readValue(json, ObjectWrapper.class);
      fail("Not allow-listed type '" + rejectedType + "' deserialized as a type parameter: " + result.value);
    } catch (InvalidTypeIdException e) {
      assertThat(e).hasMessageContaining(rejectedType);
    }
    assertThat(INSTANTIATIONS)
        .as("not allow-listed types instantiated")
        .isEmpty();
  }

  protected static ObjectMapper defaultTypingObjectMapper() {
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();
    objectMapper.activateDefaultTyping(polymorphicTypeValidator(), DefaultTyping.NON_FINAL);
    return objectMapper;
  }

  protected static PolymorphicTypeValidator polymorphicTypeValidator() {
    return BasicPolymorphicTypeValidator.builder()
        .allowIfSubType("java.util.ArrayList")
        .allowIfSubType("java.util.HashMap")
        .allowIfSubType(String.class)
        .allowIfSubType(SafePayload.class)
        .build();
  }

  protected static String wrapper(String typeId, String value) {
    return "{\"value\":[\"" + typeId + "\"," + value + "]}";
  }

}

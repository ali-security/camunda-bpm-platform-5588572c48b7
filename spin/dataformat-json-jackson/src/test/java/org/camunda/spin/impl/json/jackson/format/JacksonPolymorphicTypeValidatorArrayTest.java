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

import java.util.List;
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
 * The jackson-databind that Spin builds against must not let a
 * {@link BasicPolymorphicTypeValidator} configured with
 * {@code allowIfSubTypeIsArray()} approve every array: a type that the sub-type
 * allow-list denies must stay denied when a JSON payload wraps it as an array,
 * e.g. {@code [Lcom.example.Evil;} or {@code [[Lcom.example.Evil;}, otherwise
 * the denied type is instantiated (CVE-2026-54513).
 */
public class JacksonPolymorphicTypeValidatorArrayTest {

  /** Records every instantiation of the types that are not allow-listed. */
  protected static final List<String> INSTANTIATIONS = new CopyOnWriteArrayList<>();

  protected static final String GADGET = FakeGadget.class.getName();
  protected static final String GADGET_ARRAY = "[L" + GADGET + ";";
  protected static final String GADGET_NESTED_ARRAY = "[[L" + GADGET + ";";

  /**
   * Stand-in for a deserialization gadget; not allow-listed. {@code final}, so
   * the elements of a {@code FakeGadget[]} carry no type id of their own and
   * the array type id is the only one the validator sees.
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

  /** Not allow-listed, element of an array of an interface type. */
  public interface Marker {
  }

  public static class MarkerGadget implements Marker {
    public String cmd;

    public MarkerGadget() {
      INSTANTIATIONS.add(MarkerGadget.class.getName());
    }
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
  public void shouldRejectNotAllowListedType() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();

    assertRejected(objectMapper, wrapper(GADGET, "{\"cmd\":\"x\"}"), GADGET);
  }

  @Test
  public void shouldRejectNotAllowListedTypeWrappedAsArray() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();

    assertRejected(objectMapper, wrapper(GADGET_ARRAY, "[{\"cmd\":\"x\"}]"), GADGET_ARRAY);
  }

  @Test
  public void shouldRejectNotAllowListedTypeWrappedAsNestedArray() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();

    assertRejected(objectMapper, wrapper(GADGET_NESTED_ARRAY, "[[{\"cmd\":\"x\"}]]"), GADGET_NESTED_ARRAY);
  }

  @Test
  public void shouldRejectNotAllowListedTypeWrappedAsArrayWithJsonTypeInfo() throws Exception {
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test");
    ObjectMapper objectMapper = dataFormat.getObjectMapper();
    objectMapper.setPolymorphicTypeValidator(polymorphicTypeValidator());
    String json = wrapper(GADGET_ARRAY, "[{\"cmd\":\"x\"}]");

    try {
      AnnotatedWrapper result = objectMapper.readValue(json, AnnotatedWrapper.class);
      fail("Not allow-listed type deserialized from an array type id: " + result.value);
    } catch (InvalidTypeIdException e) {
      assertThat(e).hasMessageContaining(GADGET_ARRAY);
    }
    assertThat(INSTANTIATIONS).isEmpty();

    String nestedJson = wrapper(GADGET_NESTED_ARRAY, "[[{\"cmd\":\"x\"}]]");
    try {
      AnnotatedWrapper result = objectMapper.readValue(nestedJson, AnnotatedWrapper.class);
      fail("Not allow-listed type deserialized from a nested array type id: " + result.value);
    } catch (InvalidTypeIdException e) {
      assertThat(e).hasMessageContaining(GADGET_NESTED_ARRAY);
    }
    assertThat(INSTANTIATIONS).isEmpty();
  }

  @Test
  public void shouldRejectNotAllowListedTypeWrappedAsArrayWhenMappingInternalToJava() throws Exception {
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test");
    dataFormat.getObjectMapper().activateDefaultTyping(polymorphicTypeValidator(), DefaultTyping.NON_FINAL);
    JsonNode node = dataFormat.getObjectMapper().readTree(wrapper(GADGET_ARRAY, "[{\"cmd\":\"x\"}]"));

    try {
      ObjectWrapper result = dataFormat.getMapper().mapInternalToJava(node, ObjectWrapper.class);
      fail("Not allow-listed type mapped from an array type id: " + result.value);
    } catch (SpinJsonException e) {
      assertThat(e.getCause()).isInstanceOf(InvalidTypeIdException.class);
      assertThat(e.getCause()).hasMessageContaining(GADGET_ARRAY);
    }
    assertThat(INSTANTIATIONS).isEmpty();
  }

  @Test
  public void shouldRejectNotAllowListedElementOfInterfaceTypedArray() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();
    String json = wrapper("[L" + Marker.class.getName() + ";",
        "[[\"" + MarkerGadget.class.getName() + "\",{\"cmd\":\"x\"}]]");

    assertRejected(objectMapper, json, MarkerGadget.class.getName());
  }

  @Test
  public void shouldAcceptAllowListedTypeWrappedAsArray() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();
    String json = wrapper("[L" + SafePayload.class.getName() + ";", "[{\"data\":42}]");

    ObjectWrapper result = objectMapper.readValue(json, ObjectWrapper.class);

    assertThat(result.value).isInstanceOf(SafePayload[].class);
    SafePayload[] payloads = (SafePayload[]) result.value;
    assertThat(payloads).hasSize(1);
    assertThat(payloads[0].data).isEqualTo(42);
  }

  @Test
  public void shouldAcceptAllowListedTypeWrappedAsNestedArray() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();
    String json = wrapper("[[L" + SafePayload.class.getName() + ";", "[[{\"data\":42}]]");

    ObjectWrapper result = objectMapper.readValue(json, ObjectWrapper.class);

    assertThat(result.value).isInstanceOf(SafePayload[][].class);
    SafePayload[][] payloads = (SafePayload[][]) result.value;
    assertThat(payloads).hasSize(1);
    assertThat(payloads[0]).hasSize(1);
    assertThat(payloads[0][0].data).isEqualTo(42);
  }

  @Test
  public void shouldAcceptPrimitiveArray() throws Exception {
    ObjectMapper objectMapper = defaultTypingObjectMapper();

    ObjectWrapper result = objectMapper.readValue(wrapper("[I", "[1,2,3]"), ObjectWrapper.class);

    assertThat(result.value).isInstanceOf(int[].class);
    assertThat((int[]) result.value).containsExactly(1, 2, 3);
  }

  protected void assertRejected(ObjectMapper objectMapper, String json, String rejectedTypeId) throws Exception {
    try {
      ObjectWrapper result = objectMapper.readValue(json, ObjectWrapper.class);
      fail("Not allow-listed type deserialized from type id '" + rejectedTypeId + "': " + result.value);
    } catch (InvalidTypeIdException e) {
      assertThat(e).hasMessageContaining(rejectedTypeId);
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
        .allowIfSubTypeIsArray()
        .allowIfSubType(SafePayload.class)
        .build();
  }

  protected static String wrapper(String typeId, String value) {
    return "{\"value\":[\"" + typeId + "\"," + value + "]}";
  }

}

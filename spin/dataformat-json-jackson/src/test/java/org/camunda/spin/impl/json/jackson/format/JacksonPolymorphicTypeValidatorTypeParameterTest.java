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

import java.io.Serializable;
import java.util.Collections;
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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;

/**
 * The jackson-databind that Spin builds against must validate the classes that
 * a type id names as type parameters of a generic type, e.g.
 * {@code Envelope<com.example.Evil>} or {@code java.util.HashMap<com.example.Evil,java.lang.String>},
 * and the element type of an array type id, e.g. {@code [Lcom.example.Evil;},
 * like classes that a type id names directly (CVE-2026-110879):
 * <ul>
 *   <li>approving the polymorphic base type ({@code allowIfBaseType()}) must
 *   not approve them, nor must the default validator when they are not
 *   sub-types of the base type;</li>
 *   <li>approving an array type through a rule that matches other arrays too
 *   (every array is {@link Serializable}) must not approve its element
 *   type;</li>
 *   <li>{@code allowIfSubTypeIsArray()} must not accept every abstract or
 *   interface element type, which may still be deserializable without a type
 *   id of its own ({@code @JsonDeserialize(as = ...)});</li>
 *   <li>a deny-listed type must not be accepted as a {@code Map} key type
 *   parameter.</li>
 * </ul>
 * Otherwise a JSON payload makes Spin instantiate a type that the validator
 * does not allow.
 */
public class JacksonPolymorphicTypeValidatorTypeParameterTest {

  /** Records every instantiation of the types that are not allow-listed. */
  protected static final List<String> INSTANTIATIONS = new CopyOnWriteArrayList<>();

  protected static final String ENVELOPE = Envelope.class.getName();
  protected static final String GADGET = FakeGadget.class.getName();
  protected static final String ABSTRACT_GADGET = AbstractGadget.class.getName();
  protected static final String SAFE = SafePayload.class.getName();

  /** Polymorphic base type. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
  public interface Message {
  }

  /** Generic sub-type of the base type: the type id names the class of its payload as type parameter. */
  public static class Envelope<T> implements Message {
    public T payload;
  }

  public static class PlainMessage implements Message {
    public String text;
  }

  /**
   * Stand-in for a deserialization gadget; never allow-listed. {@code final}, so
   * it carries no type id of its own under default typing. Its single-String
   * constructor makes it usable as a {@code Map} key.
   */
  public static final class FakeGadget {
    public String cmd;

    public FakeGadget() {
      INSTANTIATIONS.add(FakeGadget.class.getName());
    }

    public FakeGadget(String key) {
      INSTANTIATIONS.add(FakeGadget.class.getName() + "(" + key + ")");
    }
  }

  /** Abstract, but deserializable without a type id of its own; never allow-listed. */
  @JsonDeserialize(as = AbstractGadgetImpl.class)
  public abstract static class AbstractGadget {
    public String cmd;
  }

  public static class AbstractGadgetImpl extends AbstractGadget {
    public AbstractGadgetImpl() {
      INSTANTIATIONS.add(AbstractGadgetImpl.class.getName());
    }
  }

  /** Allow-listed type, where used. */
  public static final class SafePayload {
    public int data;
  }

  public static final class MessageWrapper {
    public Message message;
  }

  public static final class StringMapWrapper {
    public Map<String, String> map;
  }

  public static final class SerializableWrapper {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    public Serializable value;
  }

  public static final class AnnotatedWrapper {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    public Object value;
  }

  @Before
  public void clearInstantiations() {
    INSTANTIATIONS.clear();
  }

  // approval of the base type (allowIfBaseType) must not approve type parameters

  @Test
  public void shouldRejectNotAllowListedTypeParameterOfApprovedBaseType() throws Exception {
    ObjectMapper objectMapper = objectMapper(BasicPolymorphicTypeValidator.builder()
        .allowIfBaseType(Message.class)
        .build());

    assertRejected(objectMapper, messageJson(ENVELOPE + "<" + GADGET + ">", "\"payload\":{\"cmd\":\"x\"}"),
        MessageWrapper.class, GADGET);
  }

  @Test
  public void shouldRejectNotAllowListedNestedTypeParameterOfApprovedBaseType() throws Exception {
    ObjectMapper objectMapper = objectMapper(BasicPolymorphicTypeValidator.builder()
        .allowIfBaseType(Message.class)
        .build());

    assertRejected(objectMapper,
        messageJson(ENVELOPE + "<java.util.ArrayList<" + GADGET + ">>", "\"payload\":[{\"cmd\":\"x\"}]"),
        MessageWrapper.class, GADGET);
  }

  @Test
  public void shouldRejectNotAllowListedTypeParameterOfApprovedBaseTypeWhenMappingInternalToJava() throws Exception {
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test");
    dataFormat.getObjectMapper().setPolymorphicTypeValidator(BasicPolymorphicTypeValidator.builder()
        .allowIfBaseType(Message.class)
        .build());
    JsonNode node = dataFormat.getObjectMapper()
        .readTree(messageJson(ENVELOPE + "<" + GADGET + ">", "\"payload\":{\"cmd\":\"x\"}"));

    try {
      MessageWrapper result = dataFormat.getMapper().mapInternalToJava(node, MessageWrapper.class);
      fail("Not allow-listed type parameter of an approved base type mapped: " + payloadOf(result));
    } catch (SpinJsonException e) {
      assertThat(e.getCause()).isInstanceOf(InvalidTypeIdException.class);
      assertThat(e.getCause()).hasMessageContaining(GADGET);
    }
    assertThat(INSTANTIATIONS).isEmpty();
  }

  @Test
  public void shouldAcceptSafeAndAllowListedTypeParametersOfApprovedBaseType() throws Exception {
    ObjectMapper objectMapper = objectMapper(BasicPolymorphicTypeValidator.builder()
        .allowIfBaseType(Message.class)
        .allowIfSubType(SafePayload.class)
        .build());

    MessageWrapper result = objectMapper.readValue(
        messageJson(PlainMessage.class.getName(), "\"text\":\"x\""), MessageWrapper.class);
    assertThat(((PlainMessage) result.message).text).isEqualTo("x");

    result = objectMapper.readValue(messageJson(ENVELOPE + "<java.lang.String>", "\"payload\":\"y\""),
        MessageWrapper.class);
    assertThat(payloadOf(result)).isEqualTo("y");

    result = objectMapper.readValue(messageJson(ENVELOPE + "<" + SAFE + ">", "\"payload\":{\"data\":42}"),
        MessageWrapper.class);
    assertThat(((SafePayload) payloadOf(result)).data).isEqualTo(42);

    // a type parameter that is a sub-type of the approved base type is approved like a direct type id
    result = objectMapper.readValue(messageJson(ENVELOPE + "<" + PlainMessage.class.getName() + ">",
        "\"payload\":{\"@class\":\"" + PlainMessage.class.getName() + "\",\"text\":\"z\"}"), MessageWrapper.class);
    assertThat(((PlainMessage) payloadOf(result)).text).isEqualTo("z");
  }

  // default typing with an approved base type: Map key type parameter

  @Test
  public void shouldRejectNotAllowListedMapKeyTypeParameterOfApprovedBaseType() throws Exception {
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();
    objectMapper.activateDefaultTyping(mapBaseTypeValidator(), DefaultTyping.NON_FINAL);
    String json = "{\"map\":[\"java.util.HashMap<" + GADGET + ",java.lang.String>\",{\"attacker-chosen\":\"x\"}]}";

    try {
      StringMapWrapper result = objectMapper.readValue(json, StringMapWrapper.class);
      fail("Not allow-listed Map key type parameter of an approved base type deserialized: " + result.map);
    } catch (InvalidTypeIdException e) {
      assertThat(e).hasMessageContaining(GADGET);
    }
    assertThat(INSTANTIATIONS)
        .as("not allow-listed types instantiated")
        .isEmpty();
  }

  @Test
  public void shouldAcceptSafeMapTypeParametersOfApprovedBaseType() throws Exception {
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();
    objectMapper.activateDefaultTyping(mapBaseTypeValidator(), DefaultTyping.NON_FINAL);
    String json = "{\"map\":[\"java.util.HashMap<java.lang.String,java.lang.String>\",{\"k\":\"v\"}]}";

    StringMapWrapper result = objectMapper.readValue(json, StringMapWrapper.class);

    assertThat(result.map).isEqualTo(Collections.singletonMap("k", "v"));
  }

  // array type ids: approval of the array type must not approve its element type

  @Test
  public void shouldRejectNotAllowListedArrayElementTypeOfApprovedBaseType() throws Exception {
    // every array is Serializable, whatever its element type
    ObjectMapper objectMapper = objectMapper(BasicPolymorphicTypeValidator.builder()
        .allowIfBaseType(Serializable.class)
        .build());

    assertRejected(objectMapper, wrapper("[L" + GADGET + ";", "[{\"cmd\":\"x\"}]"), SerializableWrapper.class,
        GADGET);
    assertRejected(objectMapper, wrapper("[[L" + GADGET + ";", "[[{\"cmd\":\"x\"}]]"), SerializableWrapper.class,
        GADGET);
  }

  @Test
  public void shouldRejectNotAllowListedArrayElementTypeOfArrayApprovedBySubTypeRule() throws Exception {
    // every array is Serializable, whatever its element type
    ObjectMapper objectMapper = objectMapper(BasicPolymorphicTypeValidator.builder()
        .allowIfSubType(Serializable.class)
        .build());

    assertRejected(objectMapper, wrapper("[L" + GADGET + ";", "[{\"cmd\":\"x\"}]"), AnnotatedWrapper.class, GADGET);
  }

  @Test
  public void shouldAcceptSafeArrayElementTypes() throws Exception {
    ObjectMapper objectMapper = objectMapper(BasicPolymorphicTypeValidator.builder()
        .allowIfBaseType(Serializable.class)
        .build());

    SerializableWrapper result = objectMapper.readValue(wrapper("[Ljava.lang.String;", "[\"a\"]"),
        SerializableWrapper.class);
    assertThat((String[]) result.value).containsExactly("a");

    result = objectMapper.readValue(wrapper("[I", "[1,2]"), SerializableWrapper.class);
    assertThat((int[]) result.value).containsExactly(1, 2);
  }

  @Test
  public void shouldAcceptElementTypeOfExplicitlyAllowListedArrayType() throws Exception {
    ObjectMapper objectMapper = objectMapper(BasicPolymorphicTypeValidator.builder()
        .allowIfSubType(SafePayload[].class)
        .build());

    AnnotatedWrapper result = objectMapper.readValue(wrapper("[L" + SAFE + ";", "[{\"data\":42}]"),
        AnnotatedWrapper.class);

    assertThat(result.value).isInstanceOf(SafePayload[].class);
    assertThat(((SafePayload[]) result.value)[0].data).isEqualTo(42);
  }

  // allowIfSubTypeIsArray() must not accept every abstract or interface element type

  @Test
  public void shouldRejectNotAllowListedAbstractArrayElementType() throws Exception {
    ObjectMapper objectMapper = objectMapper(BasicPolymorphicTypeValidator.builder()
        .allowIfSubTypeIsArray()
        .allowIfSubType(SafePayload.class)
        .build());

    assertRejected(objectMapper, wrapper("[L" + ABSTRACT_GADGET + ";", "[{\"cmd\":\"x\"}]"), AnnotatedWrapper.class,
        ABSTRACT_GADGET);
  }

  @Test
  public void shouldAcceptNumberArrayWithArraySupport() throws Exception {
    ObjectMapper objectMapper = objectMapper(BasicPolymorphicTypeValidator.builder()
        .allowIfSubTypeIsArray()
        .build());

    AnnotatedWrapper result = objectMapper.readValue(wrapper("[Ljava.lang.Number;", "[1,2.5]"),
        AnnotatedWrapper.class);

    assertThat(result.value).isInstanceOf(Number[].class);
    assertThat((Number[]) result.value).containsExactly(1, 2.5);
  }

  // default (laissez-faire) validator: type parameters must be sub-types of the base type

  @Test
  public void shouldRejectTypeParameterNotSubTypeOfBaseTypeWithDefaultValidator() throws Exception {
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();

    assertRejected(objectMapper, messageJson(ENVELOPE + "<" + GADGET + ">", "\"payload\":{\"cmd\":\"x\"}"),
        MessageWrapper.class, GADGET);
    assertRejected(objectMapper, messageJson(ENVELOPE + "<[L" + GADGET + ";>", "\"payload\":[{\"cmd\":\"x\"}]"),
        MessageWrapper.class, GADGET);
  }

  @Test
  public void shouldAcceptSafeTypeParametersWithDefaultValidator() throws Exception {
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();

    MessageWrapper result = objectMapper.readValue(messageJson(ENVELOPE + "<java.lang.String>", "\"payload\":\"y\""),
        MessageWrapper.class);
    assertThat(payloadOf(result)).isEqualTo("y");

    result = objectMapper.readValue(messageJson(ENVELOPE + "<" + PlainMessage.class.getName() + ">",
        "\"payload\":{\"@class\":\"" + PlainMessage.class.getName() + "\",\"text\":\"z\"}"), MessageWrapper.class);
    assertThat(((PlainMessage) payloadOf(result)).text).isEqualTo("z");
  }

  // deny list: also for type parameters that get no bean deserializer of their own (Map keys)

  @Test
  public void shouldRejectDenyListedMapKeyTypeParameter() throws Exception {
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();
    String denyListed = "java.util.logging.FileHandler";
    // empty map: no key is deserialized, so the unpatched validator creates no file either
    String json = wrapper("java.util.HashMap<" + denyListed + ",java.lang.String>", "{}");

    try {
      AnnotatedWrapper result = objectMapper.readValue(json, AnnotatedWrapper.class);
      fail("Deny-listed Map key type parameter accepted: " + result.value);
    } catch (InvalidDefinitionException e) {
      assertThat(e).hasMessageContaining("Illegal type");
      assertThat(e).hasMessageContaining(denyListed);
    }
  }

  protected void assertRejected(ObjectMapper objectMapper, String json, Class<?> wrapperType, String rejectedType)
      throws Exception {
    try {
      Object result = objectMapper.readValue(json, wrapperType);
      fail("Not allow-listed type '" + rejectedType + "' deserialized from a type id: " + result);
    } catch (InvalidTypeIdException e) {
      assertThat(e).hasMessageContaining(rejectedType);
    }
    assertThat(INSTANTIATIONS)
        .as("not allow-listed types instantiated")
        .isEmpty();
  }

  protected static ObjectMapper objectMapper(PolymorphicTypeValidator ptv) {
    ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();
    objectMapper.setPolymorphicTypeValidator(ptv);
    return objectMapper;
  }

  protected static PolymorphicTypeValidator mapBaseTypeValidator() {
    return BasicPolymorphicTypeValidator.builder()
        .allowIfBaseType(Map.class)
        .build();
  }

  protected static Object payloadOf(MessageWrapper wrapper) {
    return ((Envelope<?>) wrapper.message).payload;
  }

  protected static String messageJson(String typeId, String properties) {
    return "{\"message\":{\"@class\":\"" + typeId + "\"," + properties + "}}";
  }

  protected static String wrapper(String typeId, String value) {
    return "{\"value\":[\"" + typeId + "\"," + value + "]}";
  }

}

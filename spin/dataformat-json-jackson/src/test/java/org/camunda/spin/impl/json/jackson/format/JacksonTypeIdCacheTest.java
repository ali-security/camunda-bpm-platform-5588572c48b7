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

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;
import com.fasterxml.jackson.databind.annotation.JsonTypeResolver;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
import com.fasterxml.jackson.databind.jsontype.impl.StdTypeResolverBuilder;
import com.fasterxml.jackson.databind.jsontype.impl.TypeDeserializerBase;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;

/**
 * The jackson-databind that Spin builds against must bound the type id to
 * deserializer cache of its type deserializers (CVE-2026-91776). A type
 * deserializer lives as long as the {@link ObjectMapper} of the data format, and
 * used to cache every type id it saw: unknown ids handled through
 * {@code defaultImpl}, through the "nullifying" fallback
 * ({@link DeserializationFeature#FAIL_ON_INVALID_SUBTYPE} disabled) or through a
 * {@link DeserializationProblemHandler}, differently spelled ids of the same
 * type, and overlong ids alike. So JSON payloads carrying distinct type ids
 * could grow the heap of the engine without bound, and the answer of a lenient
 * reader leaked to the readers of the same mapper that are not lenient.
 */
public class JacksonTypeIdCacheTest {

  /** The cache size limit of the sealed jackson-databind (TypeDeserializerBase.MAX_CACHED_TYPE_IDS). */
  protected static final int MAX_CACHED_TYPE_IDS = 1000;

  /** The type id length limit of the sealed jackson-databind (TypeDeserializerBase.MAX_CACHED_TYPE_ID_LENGTH). */
  protected static final int MAX_CACHED_TYPE_ID_LENGTH = 256;

  /** Number of distinct type ids each attack sends. */
  protected static final int FLOOD = 3 * MAX_CACHED_TYPE_IDS;

  /** Every type deserializer built for the polymorphic types below. */
  protected static final List<TypeDeserializer> TYPE_DESERIALIZERS = new CopyOnWriteArrayList<>();

  /** Counts every type id resolution of {@link AnimalIdResolver}. */
  protected static final AtomicInteger RESOLUTIONS = new AtomicInteger();

  protected final JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test");
  protected final ObjectMapper objectMapper = dataFormat.getObjectMapper();

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type",
      defaultImpl = DefaultAnimal.class)
  @JsonTypeIdResolver(AnimalIdResolver.class)
  @JsonTypeResolver(RecordingTypeResolverBuilder.class)
  public abstract static class Animal {
    public String name;
  }

  public static class Dog extends Animal {
  }

  public static class DefaultAnimal extends Animal {
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonTypeIdResolver(AnimalIdResolver.class)
  @JsonTypeResolver(RecordingTypeResolverBuilder.class)
  public abstract static class Pet {
    public String name;
  }

  public static class Cat extends Pet {
  }

  public static class AnimalHolder {
    public Animal animal;
  }

  public static class PetHolder {
    public Pet pet;
  }

  public static class ValueHolder {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    @JsonTypeResolver(RecordingTypeResolverBuilder.class)
    public Object value;
  }

  /** Knows the type ids "Dog" and "Cat" only. */
  public static class AnimalIdResolver extends TypeIdResolverBase {
    private static final long serialVersionUID = 1L;

    @Override
    public JsonTypeInfo.Id getMechanism() {
      return JsonTypeInfo.Id.NAME;
    }

    @Override
    public String idFromValue(Object value) {
      return idFromValueAndType(value, value.getClass());
    }

    @Override
    public String idFromValueAndType(Object value, Class<?> type) {
      return type.getSimpleName();
    }

    @Override
    public JavaType typeFromId(DatabindContext context, String id) {
      RESOLUTIONS.incrementAndGet();
      if ("Dog".equals(id)) {
        return context.constructType(Dog.class);
      }
      if ("Cat".equals(id)) {
        return context.constructType(Cat.class);
      }
      return null;
    }
  }

  /** The standard type resolver builder, recording the type deserializers it builds. */
  public static class RecordingTypeResolverBuilder extends StdTypeResolverBuilder {
    @Override
    public TypeDeserializer buildTypeDeserializer(DeserializationConfig config, JavaType baseType,
        Collection<NamedType> subtypes) {
      TypeDeserializer typeDeserializer = super.buildTypeDeserializer(config, baseType, subtypes);
      TYPE_DESERIALIZERS.add(typeDeserializer);
      return typeDeserializer;
    }
  }

  /** Resolves every unknown type id to {@link Cat}. */
  public static class CatProblemHandler extends DeserializationProblemHandler {
    @Override
    public JavaType handleUnknownTypeId(DeserializationContext context, JavaType baseType, String subTypeId,
        TypeIdResolver idResolver, String failureMsg) {
      return context.constructType(Cat.class);
    }
  }

  @Before
  public void reset() {
    TYPE_DESERIALIZERS.clear();
    RESOLUTIONS.set(0);
  }

  @Test
  public void shouldBoundTypeIdCacheForUnknownIdsWithDefaultImpl() throws Exception {
    for (int i = 0; i < FLOOD; i++) {
      AnimalHolder holder = dataFormat.getMapper()
          .mapInternalToJava(objectMapper.readTree(animalJson("unknown-" + i)), AnimalHolder.class);
      assertThat(holder.animal).isInstanceOf(DefaultAnimal.class);
    }

    assertThat(RESOLUTIONS.get()).isEqualTo(FLOOD);
    assertThat(largestTypeIdCache())
        .as("type ids cached by a single type deserializer")
        .isGreaterThan(0)
        .isLessThanOrEqualTo(MAX_CACHED_TYPE_IDS);

    // type ids are still cached: a known one is resolved only once
    RESOLUTIONS.set(0);
    for (int i = 0; i < 10; i++) {
      AnimalHolder holder = objectMapper.readValue(animalJson("Dog"), AnimalHolder.class);
      assertThat(holder.animal).isInstanceOf(Dog.class);
      assertThat(holder.animal.name).isEqualTo("Rex");
    }
    assertThat(RESOLUTIONS.get()).isEqualTo(1);
  }

  @Test
  public void shouldNotCacheNullifiedUnknownIds() throws Exception {
    ObjectReader lenientReader = objectMapper.readerFor(PetHolder.class)
        .without(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE);

    for (int i = 0; i < FLOOD; i++) {
      PetHolder holder = lenientReader.readValue(petJson("unknown-" + i));
      assertThat(holder.pet).isNull();
    }

    assertThat(largestTypeIdCache())
        .as("type ids cached by a single type deserializer")
        .isZero();
    // the lenient answer must not leak to a reader of the same mapper that fails on invalid sub-types
    assertRejected(objectMapper.readerFor(PetHolder.class), "unknown-0");

    // known type ids are still cached
    lenientReader.readValue(petJson("Cat"));
    assertThat(largestTypeIdCache()).isEqualTo(1);
  }

  @Test
  public void shouldNotCacheUnknownIdsResolvedByProblemHandler() throws Exception {
    ObjectReader handlingReader = objectMapper.readerFor(PetHolder.class).withHandler(new CatProblemHandler());

    for (int i = 0; i < FLOOD; i++) {
      PetHolder holder = handlingReader.readValue(petJson("unknown-" + i));
      assertThat(holder.pet).isInstanceOf(Cat.class);
      assertThat(holder.pet.name).isEqualTo("Rex");
    }

    assertThat(largestTypeIdCache())
        .as("type ids cached by a single type deserializer")
        .isZero();
    // the problem handler's answer must not leak to a reader of the same mapper that has no such handler
    assertRejected(objectMapper.readerFor(PetHolder.class), "unknown-0");

    // known type ids are still cached
    handlingReader.readValue(petJson("Cat"));
    assertThat(largestTypeIdCache()).isEqualTo(1);
  }

  @Test
  public void shouldBoundTypeIdCacheForRespelledClassNames() throws Exception {
    ObjectReader reader = objectMapper.readerFor(ValueHolder.class);

    // the generic type id parser trims the type names, so every spelling below resolves to ArrayList<String>
    int sent = 0;
    for (int before = 0; before < 60 && sent < FLOOD; before++) {
      for (int after = 0; after < 60 && sent < FLOOD; after++) {
        String typeId = "java.util.ArrayList<" + spaces(before) + "java.lang.String" + spaces(after) + ">";
        ValueHolder holder = reader.readValue("{\"value\":[\"" + typeId + "\",[\"a\"]]}");
        assertThat(holder.value).isEqualTo(Collections.singletonList("a"));
        sent++;
      }
    }

    assertThat(sent).isEqualTo(FLOOD);
    assertThat(largestTypeIdCache())
        .as("type ids cached by a single type deserializer")
        .isGreaterThan(0)
        .isLessThanOrEqualTo(MAX_CACHED_TYPE_IDS);
  }

  @Test
  public void shouldNotCacheOverlongTypeIds() throws Exception {
    ObjectReader reader = objectMapper.readerFor(AnimalHolder.class);
    String overlongId = "unknown-" + spaces(64 * 1024).replace(' ', 'x');

    AnimalHolder holder = reader.readValue(animalJson(overlongId));
    assertThat(holder.animal).isInstanceOf(DefaultAnimal.class);
    holder = reader.readValue(animalJson(overlongId));
    assertThat(holder.animal).isInstanceOf(DefaultAnimal.class);
    assertThat(largestTypeIdCache())
        .as("type ids cached by a single type deserializer")
        .isZero();
    assertThat(RESOLUTIONS.get()).isEqualTo(2);

    String justTooLongId = overlongId.substring(0, MAX_CACHED_TYPE_ID_LENGTH + 1);
    reader.readValue(animalJson(justTooLongId));
    assertThat(largestTypeIdCache()).isZero();

    // a type id of the maximum length is still cached
    String longestCachedId = overlongId.substring(0, MAX_CACHED_TYPE_ID_LENGTH);
    reader.readValue(animalJson(longestCachedId));
    assertThat(largestTypeIdCache()).isEqualTo(1);
  }

  protected static void assertRejected(ObjectReader reader, String typeId) throws Exception {
    try {
      PetHolder holder = reader.readValue(petJson(typeId));
      fail("Unknown type id '" + typeId + "' accepted: " + holder.pet);
    } catch (InvalidTypeIdException e) {
      assertThat(e).hasMessageContaining("Could not resolve type id '" + typeId + "'");
    }
  }

  /**
   * Returns the number of entries in the largest type id to deserializer cache
   * of the type deserializers built since the test started.
   */
  protected static int largestTypeIdCache() throws Exception {
    assertThat(TYPE_DESERIALIZERS).as("type deserializers built").isNotEmpty();
    Field cache = TypeDeserializerBase.class.getDeclaredField("_deserializers");
    cache.setAccessible(true);
    int largest = 0;
    for (TypeDeserializer typeDeserializer : TYPE_DESERIALIZERS) {
      largest = Math.max(largest, ((Map<?, ?>) cache.get(typeDeserializer)).size());
    }
    return largest;
  }

  protected static String animalJson(String typeId) {
    return "{\"animal\":{\"type\":\"" + typeId + "\",\"name\":\"Rex\"}}";
  }

  protected static String petJson(String typeId) {
    return "{\"pet\":{\"type\":\"" + typeId + "\",\"name\":\"Rex\"}}";
  }

  protected static String spaces(int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, ' ');
    return new String(chars);
  }

}

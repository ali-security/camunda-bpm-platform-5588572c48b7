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

import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * The jackson-databind that Spin builds against must honor the active
 * {@link JsonView} for properties with an external type id
 * ({@code @JsonTypeInfo(include = EXTERNAL_PROPERTY)}) and for the creator
 * parameters of a type that has one: a property that the active view hides must
 * not be set from the JSON payload, whether the type uses a property-based
 * creator, a default constructor or a builder (GHSA-mhm7-754m-9p8w).
 */
public class JacksonJsonViewExternalTypeIdTest {

  protected final ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();

  public static class PublicView {
  }

  public static class AdminView extends PublicView {
  }

  public abstract static class Asset {
    public String name;
  }

  public static class PublicAsset extends Asset {
  }

  public static class AdminAsset extends Asset {
    public String secret;
  }

  /** View-restricted external type id creator parameter (the advisory's proof of concept). */
  public static class CreatorContainer {
    public final String label;

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PublicAsset.class, name = "pub"),
        @JsonSubTypes.Type(value = AdminAsset.class, name = "admin")
    })
    @JsonView(AdminView.class)
    public final Asset asset;

    @JsonCreator
    public CreatorContainer(@JsonProperty("label") String label,
        @JsonProperty("asset") @JsonView(AdminView.class) Asset asset) {
      this.label = label;
      this.asset = asset;
    }
  }

  /** View-restricted external type id property of a type with a default constructor. */
  public static class DefaultCtorContainer {
    public String label;

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PublicAsset.class, name = "pub"),
        @JsonSubTypes.Type(value = AdminAsset.class, name = "admin")
    })
    @JsonView(AdminView.class)
    public Asset asset;
  }

  /** View-restricted external type id value without a type id ("natural" scalar value). */
  public static class NaturalValueContainer {
    public String label;

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "kind")
    @JsonView(AdminView.class)
    public Object value;
  }

  /** View-restricted external type id property of a builder. */
  @JsonDeserialize(builder = BuiltContainer.Builder.class)
  public static class BuiltContainer {
    public final String label;
    public final Asset asset;

    protected BuiltContainer(String label, Asset asset) {
      this.label = label;
      this.asset = asset;
    }

    @JsonPOJOBuilder(withPrefix = "with")
    public static class Builder {
      protected String label;
      protected Asset asset;

      public Builder withLabel(String label) {
        this.label = label;
        return this;
      }

      @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "kind")
      @JsonSubTypes({
          @JsonSubTypes.Type(value = PublicAsset.class, name = "pub"),
          @JsonSubTypes.Type(value = AdminAsset.class, name = "admin")
      })
      @JsonView(AdminView.class)
      public Builder withAsset(Asset asset) {
        this.asset = asset;
        return this;
      }

      public BuiltContainer build() {
        return new BuiltContainer(label, asset);
      }
    }
  }

  /** The type id is a creator parameter too: hiding the value keeps the visible type id. */
  public static class VisibleTypeIdContainer {
    public final String label;
    public final String kind;

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "kind",
        visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PublicAsset.class, name = "pub"),
        @JsonSubTypes.Type(value = AdminAsset.class, name = "admin")
    })
    @JsonView(AdminView.class)
    public final Asset asset;

    @JsonCreator
    public VisibleTypeIdContainer(@JsonProperty("label") String label, @JsonProperty("kind") String kind,
        @JsonProperty("asset") @JsonView(AdminView.class) Asset asset) {
      this.label = label;
      this.kind = kind;
      this.asset = asset;
    }
  }

  /** The type id creator parameter is view-restricted, the value is not. */
  public static class HiddenTypeIdContainer {
    public final String label;
    public final String kind;

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "kind",
        visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PublicAsset.class, name = "pub"),
        @JsonSubTypes.Type(value = AdminAsset.class, name = "admin")
    })
    public final Asset asset;

    @JsonCreator
    public HiddenTypeIdContainer(@JsonProperty("label") String label,
        @JsonProperty("kind") @JsonView(AdminView.class) String kind, @JsonProperty("asset") Asset asset) {
      this.label = label;
      this.kind = kind;
      this.asset = asset;
    }
  }

  /** Plain view-restricted creator parameter of a type with an external type id property. */
  public static class CreatorParameterContainer {
    public final String label;
    public final String password;

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PublicAsset.class, name = "pub")
    })
    public final Asset asset;

    @JsonCreator
    public CreatorParameterContainer(@JsonProperty("label") String label,
        @JsonProperty("password") @JsonView(AdminView.class) String password, @JsonProperty("asset") Asset asset) {
      this.label = label;
      this.password = password;
      this.asset = asset;
    }
  }

  protected static final String TYPE_ID_FIRST =
      "{\"label\":\"hello\",\"kind\":\"admin\",\"asset\":{\"name\":\"foo\",\"secret\":\"LEAKED\"}}";

  protected static final String VALUE_FIRST =
      "{\"asset\":{\"name\":\"foo\",\"secret\":\"LEAKED\"},\"kind\":\"admin\",\"label\":\"hello\"}";

  protected static final String CREATOR_PASSWORD =
      "{\"password\":\"BYPASS\",\"label\":\"hello\",\"kind\":\"pub\",\"asset\":{\"name\":\"foo\"}}";

  @Test
  public void shouldNotSetHiddenExternalTypeIdCreatorParameter() throws Exception {
    for (String json : new String[] { TYPE_ID_FIRST, VALUE_FIRST }) {
      CreatorContainer container = objectMapper.readerWithView(PublicView.class)
          .forType(CreatorContainer.class).readValue(json);

      assertThat(container.label).isEqualTo("hello");
      assertThat(container.asset).as("asset hidden by the active view was set from %s", json).isNull();
    }
  }

  @Test
  public void shouldNotSetHiddenExternalTypeIdPropertyOfDefaultConstructorType() throws Exception {
    for (String json : new String[] { TYPE_ID_FIRST, VALUE_FIRST }) {
      DefaultCtorContainer container = objectMapper.readerWithView(PublicView.class)
          .forType(DefaultCtorContainer.class).readValue(json);

      assertThat(container.label).isEqualTo("hello");
      assertThat(container.asset).as("asset hidden by the active view was set from %s", json).isNull();
    }
  }

  @Test
  public void shouldNotSetHiddenNaturalExternalTypeIdValue() throws Exception {
    NaturalValueContainer container = objectMapper.readerWithView(PublicView.class)
        .forType(NaturalValueContainer.class).readValue("{\"label\":\"hello\",\"value\":\"LEAKED\"}");

    assertThat(container.label).isEqualTo("hello");
    assertThat(container.value).as("value hidden by the active view was set").isNull();
  }

  @Test
  public void shouldNotSetHiddenExternalTypeIdBuilderProperty() throws Exception {
    for (String json : new String[] { TYPE_ID_FIRST, VALUE_FIRST }) {
      BuiltContainer container = objectMapper.readerWithView(PublicView.class)
          .forType(BuiltContainer.class).readValue(json);

      assertThat(container.label).isEqualTo("hello");
      assertThat(container.asset).as("asset hidden by the active view was set from %s", json).isNull();
    }
  }

  @Test
  public void shouldKeepVisibleTypeIdCreatorParameterOfHiddenValue() throws Exception {
    VisibleTypeIdContainer container = objectMapper.readerWithView(PublicView.class)
        .forType(VisibleTypeIdContainer.class).readValue(TYPE_ID_FIRST);

    assertThat(container.label).isEqualTo("hello");
    assertThat(container.asset).as("asset hidden by the active view was set").isNull();
    assertThat(container.kind).isEqualTo("admin");
  }

  @Test
  public void shouldNotSetHiddenTypeIdCreatorParameter() throws Exception {
    HiddenTypeIdContainer container = objectMapper.readerWithView(PublicView.class)
        .forType(HiddenTypeIdContainer.class)
        .readValue(TYPE_ID_FIRST.replace("LEAKED", "shh"));

    assertThat(container.label).isEqualTo("hello");
    assertThat(container.asset).isInstanceOf(AdminAsset.class);
    assertThat(container.asset.name).isEqualTo("foo");
    assertThat(container.kind).as("kind hidden by the active view was set").isNull();
  }

  @Test
  public void shouldNotSetHiddenCreatorParameterOfExternalTypeIdType() throws Exception {
    String trailingPassword =
        "{\"label\":\"hello\",\"kind\":\"pub\",\"asset\":{\"name\":\"foo\"},\"password\":\"BYPASS\"}";
    for (String json : new String[] { CREATOR_PASSWORD, trailingPassword }) {
      CreatorParameterContainer container = objectMapper.readerWithView(PublicView.class)
          .forType(CreatorParameterContainer.class).readValue(json);

      assertThat(container.label).isEqualTo("hello");
      assertThat(container.asset).isInstanceOf(PublicAsset.class);
      assertThat(container.asset.name).isEqualTo("foo");
      assertThat(container.password).as("password hidden by the active view was set from %s", json).isNull();
    }
  }

  @Test
  public void shouldSetPropertiesVisibleInActiveView() throws Exception {
    String json = TYPE_ID_FIRST.replace("LEAKED", "shh");

    CreatorContainer creator = objectMapper.readerWithView(AdminView.class)
        .forType(CreatorContainer.class).readValue(json);
    assertThat(creator.asset).isInstanceOf(AdminAsset.class);
    assertThat(((AdminAsset) creator.asset).secret).isEqualTo("shh");

    DefaultCtorContainer defaultCtor = objectMapper.readerWithView(AdminView.class)
        .forType(DefaultCtorContainer.class).readValue(json);
    assertThat(defaultCtor.asset).isInstanceOf(AdminAsset.class);
    assertThat(((AdminAsset) defaultCtor.asset).secret).isEqualTo("shh");

    BuiltContainer built = objectMapper.readerWithView(AdminView.class)
        .forType(BuiltContainer.class).readValue(json);
    assertThat(built.asset).isInstanceOf(AdminAsset.class);

    NaturalValueContainer natural = objectMapper.readerWithView(AdminView.class)
        .forType(NaturalValueContainer.class).readValue("{\"label\":\"hello\",\"value\":\"shh\"}");
    assertThat(natural.value).isEqualTo("shh");

    HiddenTypeIdContainer hiddenTypeId = objectMapper.readerWithView(AdminView.class)
        .forType(HiddenTypeIdContainer.class).readValue(json);
    assertThat(hiddenTypeId.kind).isEqualTo("admin");

    CreatorParameterContainer creatorParameter = objectMapper.readerWithView(AdminView.class)
        .forType(CreatorParameterContainer.class).readValue(CREATOR_PASSWORD.replace("BYPASS", "OK"));
    assertThat(creatorParameter.password).isEqualTo("OK");

    // without an active view every property is set
    assertThat(objectMapper.readValue(json, CreatorContainer.class).asset).isInstanceOf(AdminAsset.class);
    assertThat(objectMapper.readValue(CREATOR_PASSWORD, CreatorParameterContainer.class).password)
        .isEqualTo("BYPASS");
  }

}

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
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * The jackson-databind that Spin builds against must honor the active
 * {@link JsonView} while it collects the parameters of a property-based
 * creator: a property that the active view hides must not be set from the
 * JSON payload, neither when it is a creator parameter of a type with a
 * {@link JsonUnwrapped} property nor when it is a setter or builder method
 * whose value is buffered until the creator is complete (CVE-2026-54518).
 */
public class JacksonJsonViewCreatorTest {

  protected final ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();

  public static class PublicView {
  }

  public static class AdminView extends PublicView {
  }

  public static class Extra {
    public String note;
  }

  /** Property-based creator plus a view-restricted setter. */
  public static class Account {
    public final String name;
    public final String city;
    protected String password;

    @JsonCreator
    public Account(@JsonProperty("name") String name, @JsonProperty("city") String city) {
      this.name = name;
      this.city = city;
    }

    @JsonView(AdminView.class)
    public void setPassword(String password) {
      this.password = password;
    }
  }

  /** Builder with a property-based creator plus a view-restricted builder method. */
  @JsonDeserialize(builder = BuiltAccount.Builder.class)
  public static class BuiltAccount {
    public final String name;
    public final String city;
    public final String password;

    protected BuiltAccount(String name, String city, String password) {
      this.name = name;
      this.city = city;
      this.password = password;
    }

    @JsonPOJOBuilder(withPrefix = "with")
    public static class Builder {
      protected final String name;
      protected final String city;
      protected String password;

      @JsonCreator
      public Builder(@JsonProperty("name") String name, @JsonProperty("city") String city) {
        this.name = name;
        this.city = city;
      }

      @JsonView(AdminView.class)
      public Builder withPassword(String password) {
        this.password = password;
        return this;
      }

      public BuiltAccount build() {
        return new BuiltAccount(name, city, password);
      }
    }
  }

  /** View-restricted creator parameter of a type with an unwrapped property. */
  public static class UnwrappedAccount {
    public final String name;
    public final String password;

    @JsonUnwrapped
    public Extra extra;

    @JsonCreator
    public UnwrappedAccount(@JsonProperty("name") String name,
        @JsonProperty("password") @JsonView(AdminView.class) String password) {
      this.name = name;
      this.password = password;
    }
  }

  /** View-restricted creator parameter of a builder with an unwrapped property. */
  @JsonDeserialize(builder = BuiltUnwrappedAccount.Builder.class)
  public static class BuiltUnwrappedAccount {
    public final String name;
    public final String password;
    public final Extra extra;

    protected BuiltUnwrappedAccount(String name, String password, Extra extra) {
      this.name = name;
      this.password = password;
      this.extra = extra;
    }

    @JsonPOJOBuilder(withPrefix = "with")
    public static class Builder {
      protected final String name;
      protected final String password;
      protected Extra extra;

      @JsonCreator
      public Builder(@JsonProperty("name") String name,
          @JsonProperty("password") @JsonView(AdminView.class) String password) {
        this.name = name;
        this.password = password;
      }

      @JsonUnwrapped
      public Builder withExtra(Extra extra) {
        this.extra = extra;
        return this;
      }

      public BuiltUnwrappedAccount build() {
        return new BuiltUnwrappedAccount(name, password, extra);
      }
    }
  }

  /** Type with an unwrapped property, a property-based creator and a view-restricted setter. */
  public static class UnwrappedAccountWithSetter {
    public final String name;
    public final String city;
    protected String password;

    @JsonUnwrapped
    public Extra extra;

    @JsonCreator
    public UnwrappedAccountWithSetter(@JsonProperty("name") String name, @JsonProperty("city") String city) {
      this.name = name;
      this.city = city;
    }

    @JsonView(AdminView.class)
    public void setPassword(String password) {
      this.password = password;
    }
  }

  /** Builder with an unwrapped property, a property-based creator and a view-restricted builder method. */
  @JsonDeserialize(builder = BuiltUnwrappedAccountWithSetter.Builder.class)
  public static class BuiltUnwrappedAccountWithSetter {
    public final String name;
    public final String city;
    public final String password;
    public final Extra extra;

    protected BuiltUnwrappedAccountWithSetter(String name, String city, String password, Extra extra) {
      this.name = name;
      this.city = city;
      this.password = password;
      this.extra = extra;
    }

    @JsonPOJOBuilder(withPrefix = "with")
    public static class Builder {
      protected final String name;
      protected final String city;
      protected String password;
      protected Extra extra;

      @JsonCreator
      public Builder(@JsonProperty("name") String name, @JsonProperty("city") String city) {
        this.name = name;
        this.city = city;
      }

      @JsonView(AdminView.class)
      public Builder withPassword(String password) {
        this.password = password;
        return this;
      }

      @JsonUnwrapped
      public Builder withExtra(Extra extra) {
        this.extra = extra;
        return this;
      }

      public BuiltUnwrappedAccountWithSetter build() {
        return new BuiltUnwrappedAccountWithSetter(name, city, password, extra);
      }
    }
  }

  /** The hidden property sits between the creator parameters, so its value is buffered. */
  protected static final String BUFFERED_PASSWORD = "{\"name\":\"alice\",\"password\":\"BYPASS\",\"city\":\"NY\"}";

  protected static final String UNWRAPPED_CREATOR_PASSWORD =
      "{\"password\":\"BYPASS\",\"note\":\"n\",\"name\":\"alice\"}";

  protected static final String UNWRAPPED_BUFFERED_PASSWORD =
      "{\"name\":\"alice\",\"password\":\"BYPASS\",\"note\":\"n\",\"city\":\"NY\"}";

  @Test
  public void shouldNotSetHiddenPropertyBufferedForCreator() throws Exception {
    Account account = objectMapper.readerWithView(PublicView.class)
        .forType(Account.class).readValue(BUFFERED_PASSWORD);

    assertThat(account.name).isEqualTo("alice");
    assertThat(account.city).isEqualTo("NY");
    assertThat(account.password).as("password hidden by the active view was set").isNull();
  }

  @Test
  public void shouldNotSetHiddenBuilderPropertyBufferedForCreator() throws Exception {
    BuiltAccount account = objectMapper.readerWithView(PublicView.class)
        .forType(BuiltAccount.class).readValue(BUFFERED_PASSWORD);

    assertThat(account.name).isEqualTo("alice");
    assertThat(account.city).isEqualTo("NY");
    assertThat(account.password).as("password hidden by the active view was set").isNull();
  }

  @Test
  public void shouldNotSetHiddenCreatorParameterOfUnwrappingType() throws Exception {
    UnwrappedAccount account = objectMapper.readerWithView(PublicView.class)
        .forType(UnwrappedAccount.class).readValue(UNWRAPPED_CREATOR_PASSWORD);

    assertThat(account.name).isEqualTo("alice");
    assertThat(account.extra.note).isEqualTo("n");
    assertThat(account.password).as("password hidden by the active view was set").isNull();
  }

  @Test
  public void shouldNotSetHiddenCreatorParameterOfUnwrappingBuilder() throws Exception {
    BuiltUnwrappedAccount account = objectMapper.readerWithView(PublicView.class)
        .forType(BuiltUnwrappedAccount.class).readValue(UNWRAPPED_CREATOR_PASSWORD);

    assertThat(account.name).isEqualTo("alice");
    assertThat(account.extra.note).isEqualTo("n");
    assertThat(account.password).as("password hidden by the active view was set").isNull();
  }

  @Test
  public void shouldNotSetHiddenPropertyBufferedForCreatorOfUnwrappingType() throws Exception {
    UnwrappedAccountWithSetter account = objectMapper.readerWithView(PublicView.class)
        .forType(UnwrappedAccountWithSetter.class).readValue(UNWRAPPED_BUFFERED_PASSWORD);

    assertThat(account.name).isEqualTo("alice");
    assertThat(account.city).isEqualTo("NY");
    assertThat(account.extra.note).isEqualTo("n");
    assertThat(account.password).as("password hidden by the active view was set").isNull();
  }

  @Test
  public void shouldNotSetHiddenBuilderPropertyBufferedForCreatorOfUnwrappingBuilder() throws Exception {
    BuiltUnwrappedAccountWithSetter account = objectMapper.readerWithView(PublicView.class)
        .forType(BuiltUnwrappedAccountWithSetter.class).readValue(UNWRAPPED_BUFFERED_PASSWORD);

    assertThat(account.name).isEqualTo("alice");
    assertThat(account.city).isEqualTo("NY");
    assertThat(account.extra.note).isEqualTo("n");
    assertThat(account.password).as("password hidden by the active view was set").isNull();
  }

  @Test
  public void shouldSetPropertiesVisibleInActiveView() throws Exception {
    String buffered = BUFFERED_PASSWORD.replace("BYPASS", "OK");
    String unwrappedCreator = UNWRAPPED_CREATOR_PASSWORD.replace("BYPASS", "OK");
    String unwrappedBuffered = UNWRAPPED_BUFFERED_PASSWORD.replace("BYPASS", "OK");

    assertThat(objectMapper.readerWithView(AdminView.class).forType(Account.class)
        .<Account>readValue(buffered).password).isEqualTo("OK");
    assertThat(objectMapper.readerWithView(AdminView.class).forType(BuiltAccount.class)
        .<BuiltAccount>readValue(buffered).password).isEqualTo("OK");
    assertThat(objectMapper.readerWithView(AdminView.class).forType(UnwrappedAccount.class)
        .<UnwrappedAccount>readValue(unwrappedCreator).password).isEqualTo("OK");
    assertThat(objectMapper.readerWithView(AdminView.class).forType(BuiltUnwrappedAccount.class)
        .<BuiltUnwrappedAccount>readValue(unwrappedCreator).password).isEqualTo("OK");
    assertThat(objectMapper.readerWithView(AdminView.class).forType(UnwrappedAccountWithSetter.class)
        .<UnwrappedAccountWithSetter>readValue(unwrappedBuffered).password).isEqualTo("OK");
    assertThat(objectMapper.readerWithView(AdminView.class).forType(BuiltUnwrappedAccountWithSetter.class)
        .<BuiltUnwrappedAccountWithSetter>readValue(unwrappedBuffered).password).isEqualTo("OK");

    // without an active view every property is set
    assertThat(objectMapper.readValue(buffered, Account.class).password).isEqualTo("OK");
    assertThat(objectMapper.readValue(unwrappedBuffered, BuiltUnwrappedAccountWithSetter.class).password)
        .isEqualTo("OK");
  }

}

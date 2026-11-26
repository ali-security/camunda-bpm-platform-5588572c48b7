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
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

/**
 * The jackson-databind that Spin builds against must honor the active
 * {@link JsonView} for {@link JsonUnwrapped} properties: an unwrapped field,
 * setter or builder method that the active view hides must not be set from
 * the properties of the JSON payload, whether the bean is created through its
 * default constructor, a property-based creator or a builder, or is updated
 * in place (CVE-2026-59889).
 */
public class JacksonJsonViewUnwrappedTest {

  protected final ObjectMapper objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();

  public static class PublicView {
  }

  public static class AdminView extends PublicView {
  }

  /** Admin-only block. */
  public static class AccountFlags {
    public String role;
    public boolean approved;
    public long creditBalance;
  }

  /** Same block, not unwrapped: the active view hides it. */
  public static class Control {
    @JsonView(PublicView.class)
    public String email;

    @JsonView(AdminView.class)
    public AccountFlags flags;
  }

  /** View-restricted unwrapped field. */
  public static class Registration {
    @JsonView(PublicView.class)
    public String email;

    @JsonView(PublicView.class)
    public String password;

    @JsonView(AdminView.class)
    @JsonUnwrapped
    public AccountFlags flags;
  }

  /** View-restricted unwrapped setter. */
  public static class RegistrationWithSetters {
    protected String email;
    protected String password;
    protected AccountFlags flags;

    @JsonView(PublicView.class)
    public void setEmail(String email) {
      this.email = email;
    }

    @JsonView(PublicView.class)
    public void setPassword(String password) {
      this.password = password;
    }

    @JsonView(AdminView.class)
    @JsonUnwrapped
    public void setFlags(AccountFlags flags) {
      this.flags = flags;
    }
  }

  /** View-restricted unwrapped field of a type with a property-based creator. */
  public static class RegistrationWithCreator {
    public final String email;

    @JsonView(AdminView.class)
    @JsonUnwrapped
    public AccountFlags flags;

    @JsonCreator
    public RegistrationWithCreator(@JsonProperty("email") String email) {
      this.email = email;
    }
  }

  /** View-restricted unwrapped builder method. */
  @JsonDeserialize(builder = BuiltRegistration.Builder.class)
  public static class BuiltRegistration {
    public final String email;
    public final AccountFlags flags;

    protected BuiltRegistration(String email, AccountFlags flags) {
      this.email = email;
      this.flags = flags;
    }

    @JsonPOJOBuilder(withPrefix = "with")
    public static class Builder {
      protected String email;
      protected AccountFlags flags;

      @JsonView(PublicView.class)
      public Builder withEmail(String email) {
        this.email = email;
        return this;
      }

      @JsonView(AdminView.class)
      @JsonUnwrapped
      public Builder withFlags(AccountFlags flags) {
        this.flags = flags;
        return this;
      }

      public BuiltRegistration build() {
        return new BuiltRegistration(email, flags);
      }
    }
  }

  /** View-restricted unwrapped builder method of a builder with a property-based creator. */
  @JsonDeserialize(builder = BuiltCreatorRegistration.Builder.class)
  public static class BuiltCreatorRegistration {
    public final String email;
    public final AccountFlags flags;

    protected BuiltCreatorRegistration(String email, AccountFlags flags) {
      this.email = email;
      this.flags = flags;
    }

    @JsonPOJOBuilder(withPrefix = "with")
    public static class Builder {
      protected final String email;
      protected AccountFlags flags;

      @JsonCreator
      public Builder(@JsonProperty("email") String email) {
        this.email = email;
      }

      @JsonView(AdminView.class)
      @JsonUnwrapped
      public Builder withFlags(AccountFlags flags) {
        this.flags = flags;
        return this;
      }

      public BuiltCreatorRegistration build() {
        return new BuiltCreatorRegistration(email, flags);
      }
    }
  }

  /** The admin-only properties are passed inline, next to the public ones. */
  protected static final String ATTACK = "{\"email\":\"mallory@evil.test\",\"password\":\"pw\","
      + "\"role\":\"ADMIN\",\"approved\":true,\"creditBalance\":1000000}";

  /** No creator property, so the creator only runs after the whole object was read. */
  protected static final String ATTACK_WITHOUT_CREATOR_PROPERTY =
      "{\"role\":\"ADMIN\",\"approved\":true,\"creditBalance\":1000000}";

  @Test
  public void shouldNotSetHiddenWrappedProperty() throws Exception {
    Control control = objectMapper.readerWithView(PublicView.class).forType(Control.class)
        .readValue("{\"email\":\"e\",\"flags\":{\"role\":\"ADMIN\",\"approved\":true,\"creditBalance\":1000000}}");

    assertThat(control.email).isEqualTo("e");
    assertThat(control.flags).as("flags hidden by the active view were set").isNull();
  }

  @Test
  public void shouldNotSetHiddenUnwrappedField() throws Exception {
    Registration registration = objectMapper.readerWithView(PublicView.class)
        .forType(Registration.class).readValue(ATTACK);

    assertThat(registration.email).isEqualTo("mallory@evil.test");
    assertThat(registration.password).isEqualTo("pw");
    assertThat(registration.flags).as("unwrapped flags hidden by the active view were set").isNull();
  }

  @Test
  public void shouldNotSetHiddenUnwrappedSetter() throws Exception {
    RegistrationWithSetters registration = objectMapper.readerWithView(PublicView.class)
        .forType(RegistrationWithSetters.class).readValue(ATTACK);

    assertThat(registration.email).isEqualTo("mallory@evil.test");
    assertThat(registration.password).isEqualTo("pw");
    assertThat(registration.flags).as("unwrapped flags hidden by the active view were set").isNull();
  }

  @Test
  public void shouldNotSetHiddenUnwrappedFieldWhenUpdating() throws Exception {
    Registration registration = objectMapper.readerForUpdating(new Registration())
        .withView(PublicView.class).readValue(ATTACK);

    assertThat(registration.email).isEqualTo("mallory@evil.test");
    assertThat(registration.flags).as("unwrapped flags hidden by the active view were set").isNull();
  }

  @Test
  public void shouldNotSetHiddenUnwrappedFieldOfCreatorType() throws Exception {
    ObjectReader reader = objectMapper.readerWithView(PublicView.class).forType(RegistrationWithCreator.class);

    RegistrationWithCreator registration = reader.readValue(ATTACK);
    assertThat(registration.email).isEqualTo("mallory@evil.test");
    assertThat(registration.flags).as("unwrapped flags hidden by the active view were set").isNull();

    registration = reader.readValue(ATTACK_WITHOUT_CREATOR_PROPERTY);
    assertThat(registration.email).isNull();
    assertThat(registration.flags).as("unwrapped flags hidden by the active view were set").isNull();
  }

  @Test
  public void shouldNotSetHiddenUnwrappedBuilderProperty() throws Exception {
    BuiltRegistration registration = objectMapper.readerWithView(PublicView.class)
        .forType(BuiltRegistration.class).readValue(ATTACK);

    assertThat(registration.email).isEqualTo("mallory@evil.test");
    assertThat(registration.flags).as("unwrapped flags hidden by the active view were set").isNull();
  }

  @Test
  public void shouldNotSetHiddenUnwrappedBuilderPropertyOfCreatorBuilder() throws Exception {
    ObjectReader reader = objectMapper.readerWithView(PublicView.class).forType(BuiltCreatorRegistration.class);

    BuiltCreatorRegistration registration = reader.readValue(ATTACK);
    assertThat(registration.email).isEqualTo("mallory@evil.test");
    assertThat(registration.flags).as("unwrapped flags hidden by the active view were set").isNull();

    registration = reader.readValue(ATTACK_WITHOUT_CREATOR_PROPERTY);
    assertThat(registration.email).isNull();
    assertThat(registration.flags).as("unwrapped flags hidden by the active view were set").isNull();
  }

  @Test
  public void shouldSetUnwrappedPropertiesVisibleInActiveView() throws Exception {
    assertAdminFlags(objectMapper.readerWithView(AdminView.class).forType(Registration.class)
        .<Registration>readValue(ATTACK).flags);
    assertAdminFlags(objectMapper.readerWithView(AdminView.class).forType(RegistrationWithSetters.class)
        .<RegistrationWithSetters>readValue(ATTACK).flags);
    assertAdminFlags(objectMapper.readerForUpdating(new Registration()).withView(AdminView.class)
        .<Registration>readValue(ATTACK).flags);
    assertAdminFlags(objectMapper.readerWithView(AdminView.class).forType(RegistrationWithCreator.class)
        .<RegistrationWithCreator>readValue(ATTACK).flags);
    assertAdminFlags(objectMapper.readerWithView(AdminView.class).forType(RegistrationWithCreator.class)
        .<RegistrationWithCreator>readValue(ATTACK_WITHOUT_CREATOR_PROPERTY).flags);
    assertAdminFlags(objectMapper.readerWithView(AdminView.class).forType(BuiltRegistration.class)
        .<BuiltRegistration>readValue(ATTACK).flags);
    assertAdminFlags(objectMapper.readerWithView(AdminView.class).forType(BuiltCreatorRegistration.class)
        .<BuiltCreatorRegistration>readValue(ATTACK).flags);
    assertAdminFlags(objectMapper.readerWithView(AdminView.class).forType(BuiltCreatorRegistration.class)
        .<BuiltCreatorRegistration>readValue(ATTACK_WITHOUT_CREATOR_PROPERTY).flags);

    // without an active view every property is set
    assertAdminFlags(objectMapper.readValue(ATTACK, Registration.class).flags);
    assertAdminFlags(objectMapper.readValue(ATTACK, BuiltCreatorRegistration.class).flags);
  }

  protected static void assertAdminFlags(AccountFlags flags) {
    assertThat(flags).as("unwrapped flags visible in the active view were not set").isNotNull();
    assertThat(flags.role).isEqualTo("ADMIN");
    assertThat(flags.approved).isTrue();
    assertThat(flags.creditBalance).isEqualTo(1000000L);
  }

}

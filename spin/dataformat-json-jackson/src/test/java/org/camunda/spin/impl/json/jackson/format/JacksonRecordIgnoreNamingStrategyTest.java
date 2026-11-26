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
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * The jackson-databind that Spin builds against must keep a {@code @JsonIgnore}'d
 * Record component ignored when a {@code PropertyNamingStrategy} renames it, so that
 * a JSON payload cannot set it through the renamed name, e.g. {@code internal_role}
 * for {@code internalRole} (CVE-2026-59888).
 *
 * Spin is compiled for Java 11, which has no Records, so the Records are compiled
 * when the test runs, and the Record tests only run on Java 16 and later.
 */
public class JacksonRecordIgnoreNamingStrategyTest {

  protected static final String RECORDS_PACKAGE = "org.camunda.spin.impl.json.jackson.format.records";

  /** Named through {@code @JsonNaming}, like the reproducer of the vulnerability. */
  protected static final String SENSITIVE_RECORD = "SensitiveRecord";
  protected static final String SENSITIVE_RECORD_SOURCE =
      "package " + RECORDS_PACKAGE + ";\n"
      + "import com.fasterxml.jackson.annotation.JsonIgnore;\n"
      + "import com.fasterxml.jackson.databind.PropertyNamingStrategies;\n"
      + "import com.fasterxml.jackson.databind.annotation.JsonNaming;\n"
      + "@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)\n"
      + "public record SensitiveRecord(String username, @JsonIgnore String internalRole) {}\n";

  /** Named through the naming strategy configured on the ObjectMapper. */
  protected static final String PLAIN_SENSITIVE_RECORD = "PlainSensitiveRecord";
  protected static final String PLAIN_SENSITIVE_RECORD_SOURCE =
      "package " + RECORDS_PACKAGE + ";\n"
      + "import com.fasterxml.jackson.annotation.JsonIgnore;\n"
      + "public record PlainSensitiveRecord(String username, @JsonIgnore String internalRole) {}\n";

  protected static final String PAYLOAD = "{\"username\":\"alice\",\"internal_role\":\"ADMIN\"}";

  @ClassRule
  public static TemporaryFolder recordsFolder = new TemporaryFolder();

  protected static ClassLoader recordsClassLoader;

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public static class SensitivePojo {
    public String username;
    @JsonIgnore
    public String internalRole;
  }

  @Test
  public void shouldMapRecordWithoutIgnoredComponent() throws Exception {
    Class<?> recordType = loadRecord(SENSITIVE_RECORD);

    Object result = JSON("{\"username\":\"alice\"}").mapTo(recordType);

    assertThat(component(result, "username")).isEqualTo("alice");
    assertThat(component(result, "internalRole")).isNull();
  }

  @Test
  public void shouldNotSetIgnoredRecordComponentThroughRenamedNameWhenMappingJsonToJava() throws Exception {
    Class<?> recordType = loadRecord(SENSITIVE_RECORD);

    Object result = JSON(PAYLOAD).mapTo(recordType);

    assertThat(component(result, "username")).isEqualTo("alice");
    assertThat(component(result, "internalRole"))
        .as("@JsonIgnore'd Record component set through its renamed name: %s", result)
        .isNull();
  }

  @Test
  public void shouldNotSetIgnoredRecordComponentThroughRenamedNameWhenReadingValue() throws Exception {
    Class<?> recordType = loadRecord(SENSITIVE_RECORD);
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test");

    Object result = dataFormat.getObjectMapper().readValue(PAYLOAD, recordType);

    assertThat(component(result, "username")).isEqualTo("alice");
    assertThat(component(result, "internalRole"))
        .as("@JsonIgnore'd Record component set through its renamed name: %s", result)
        .isNull();
  }

  @Test
  public void shouldNotSetIgnoredRecordComponentThroughNameFromConfiguredNamingStrategy() throws Exception {
    Class<?> recordType = loadRecord(PLAIN_SENSITIVE_RECORD);
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test");
    dataFormat.getObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    JsonNode node = dataFormat.getObjectMapper().readTree(PAYLOAD);

    Object result = dataFormat.getMapper().mapInternalToJava(node, recordType);

    assertThat(component(result, "username")).isEqualTo("alice");
    assertThat(component(result, "internalRole"))
        .as("@JsonIgnore'd Record component set through its renamed name: %s", result)
        .isNull();
  }

  /**
   * POJO analog of the Record case: an ignored field must not be set through the
   * renamed name either.
   */
  @Test
  public void shouldNotSetIgnoredPojoFieldThroughRenamedName() throws Exception {
    JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test");
    dataFormat.getObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    SensitivePojo result = dataFormat.getObjectMapper().readValue(PAYLOAD, SensitivePojo.class);

    assertThat(result.username).isEqualTo("alice");
    assertThat(result.internalRole).isNull();
  }

  protected static Object component(Object record, String name) throws Exception {
    return record.getClass().getMethod(name).invoke(record);
  }

  protected static Class<?> loadRecord(String simpleName) throws Exception {
    assumeTrue("Records need Java 16 or later", Runtime.version().feature() >= 16);
    return Class.forName(RECORDS_PACKAGE + "." + simpleName, true, getRecordsClassLoader());
  }

  protected static synchronized ClassLoader getRecordsClassLoader() throws Exception {
    if (recordsClassLoader == null) {
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      assumeNotNull(compiler);

      Path sources = recordsFolder.newFolder("sources").toPath();
      Path classes = recordsFolder.newFolder("classes").toPath();
      Path sensitiveRecord = writeSource(sources, SENSITIVE_RECORD, SENSITIVE_RECORD_SOURCE);
      Path plainSensitiveRecord = writeSource(sources, PLAIN_SENSITIVE_RECORD, PLAIN_SENSITIVE_RECORD_SOURCE);
      String classpath = classpathEntry(JsonIgnore.class) + File.pathSeparator + classpathEntry(JsonFactory.class)
          + File.pathSeparator + classpathEntry(JsonNaming.class);

      ByteArrayOutputStream errors = new ByteArrayOutputStream();
      int result = compiler.run(null, null, errors, "-proc:none", "-classpath", classpath,
          "-d", classes.toString(), sensitiveRecord.toString(), plainSensitiveRecord.toString());
      assertThat(result)
          .as("compiling the Records failed: %s", new String(errors.toByteArray(), StandardCharsets.UTF_8))
          .isZero();

      recordsClassLoader = new URLClassLoader(new URL[] { classes.toUri().toURL() },
          JacksonRecordIgnoreNamingStrategyTest.class.getClassLoader());
    }
    return recordsClassLoader;
  }

  protected static Path writeSource(Path sources, String simpleName, String source) throws Exception {
    Path file = sources.resolve(simpleName + ".java");
    Files.write(file, source.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  protected static String classpathEntry(Class<?> type) throws Exception {
    return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  }

}

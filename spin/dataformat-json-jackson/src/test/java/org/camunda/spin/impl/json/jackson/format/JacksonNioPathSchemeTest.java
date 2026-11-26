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
import static org.camunda.spin.Spin.JSON;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.camunda.spin.json.SpinJsonException;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

/**
 * The jackson-databind that Spin builds against must deserialize
 * {@link Path} values only from {@code file} URIs or from values without a URI
 * scheme: any other scheme used to be handed to {@code Paths.get(URI)} and, when
 * no installed provider matched, to the {@code FileSystemProvider} of that
 * scheme found through the thread context class loader, so a JSON payload
 * could make the engine load and invoke an attacker-chosen file system provider
 * (CVE-2026-19032). Schemes that are not allowed must be rejected before any
 * provider is looked up.
 */
public class JacksonNioPathSchemeTest {

  protected static final String NOT_ALLOWED = "not allowed for Path deserialization";

  protected final JacksonJsonDataFormat dataFormat = new JacksonJsonDataFormat("test");
  protected final ObjectMapper objectMapper = dataFormat.getObjectMapper();

  public static class Workspace {
    public Path workdir;
  }

  @Before
  public void resetProvider() {
    RecordingFileSystemProvider.reset();
  }

  @Test
  public void shouldRejectJarScheme() throws Exception {
    assertRejected("jar:file:/tmp/spin_evil.zip!/x", "jar");
  }

  @Test
  public void shouldRejectOtherSchemes() throws Exception {
    assertRejected("jar:http://example.com/foo.jar!/path", "jar");
    assertRejected("http://example.com/path", "http");
    assertRejected("https://example.com/path", "https");
    assertRejected("s3://bucket/key", "s3");
    assertRejected("jrt:/java.base/java/lang/Object.class", "jrt");
    assertRejected("custom://something", "custom");
  }

  @Test
  public void shouldNotLoadOrInvokeProviderOfSchemeThatIsNotAllowed() throws Exception {
    final String value = RecordingFileSystemProvider.SCHEME + "://attacker-controlled/target?x=1";
    final ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
    // the provider is registered next to this test, not at the root of the test class path,
    // so only this context class loader finds it (and Paths.get(URI) does not)
    final URL providerRoot = JacksonNioPathSchemeTest.class.getResource("nio-path-provider/");
    assertThat(providerRoot).isNotNull();
    Thread.currentThread().setContextClassLoader(new URLClassLoader(new URL[] { providerRoot }, originalLoader));

    try {
      assertRejected(value, RecordingFileSystemProvider.SCHEME);

      try {
        Workspace workspace = JSON("{\"workdir\":\"" + value + "\"}").mapTo(Workspace.class);
        fail("Path deserialized from '" + value + "' while mapping JSON to Java: " + workspace.workdir);
      } catch (SpinJsonException e) {
        assertThat(e.getCause()).isInstanceOf(InvalidFormatException.class);
        assertThat(e.getCause()).hasMessageContaining(NOT_ALLOWED);
      }
    } finally {
      Thread.currentThread().setContextClassLoader(originalLoader);
    }

    assertThat(RecordingFileSystemProvider.INSTANCES.get())
        .as("FileSystemProvider of a scheme that is not allowed was loaded while deserializing a Path")
        .isZero();
    assertThat(RecordingFileSystemProvider.GET_PATH_CALLS.get())
        .as("FileSystemProvider of a scheme that is not allowed was invoked while deserializing a Path")
        .isZero();
  }

  @Test
  public void shouldAcceptFileUriAndPathWithoutScheme() throws Exception {
    Path expected = Paths.get("/tmp", "foo.txt").toAbsolutePath();

    assertThat(objectMapper.readValue("\"file:///tmp/foo.txt\"", Path.class).toAbsolutePath()).isEqualTo(expected);
    // URI schemes are case-insensitive
    assertThat(objectMapper.readValue("\"FILE:///tmp/foo.txt\"", Path.class).toAbsolutePath()).isEqualTo(expected);
    assertThat(objectMapper.readValue("\"/tmp/foo.txt\"", Path.class).toAbsolutePath()).isEqualTo(expected);
  }

  @Test
  public void shouldMapFileUriWhenMappingJsonToJava() {
    Workspace workspace = JSON("{\"workdir\":\"file:///tmp/foo.txt\"}").mapTo(Workspace.class);

    assertThat(workspace.workdir.toAbsolutePath()).isEqualTo(Paths.get("/tmp", "foo.txt").toAbsolutePath());
  }

  @Test
  public void shouldRejectJarSchemeWhenMappingJsonToJava() {
    try {
      Workspace workspace = JSON("{\"workdir\":\"jar:file:/tmp/spin_evil.zip!/x\"}").mapTo(Workspace.class);
      fail("Path deserialized from a jar: URI while mapping JSON to Java: " + workspace.workdir);
    } catch (SpinJsonException e) {
      assertThat(e.getCause()).isInstanceOf(InvalidFormatException.class);
      assertThat(e.getCause()).hasMessageContaining("scheme 'jar' " + NOT_ALLOWED);
    }
  }

  @Test
  public void shouldRejectJarSchemeWhenMappingInternalToJava() {
    JsonNode node = dataFormat.createJsonNode("jar:file:/tmp/spin_evil.zip!/x");

    try {
      Path path = dataFormat.getMapper().mapInternalToJava(node, Path.class.getName());
      fail("Path deserialized from a jar: URI while mapping internal to Java: " + path);
    } catch (SpinJsonException e) {
      assertThat(e.getCause()).isInstanceOf(InvalidFormatException.class);
      assertThat(e.getCause()).hasMessageContaining("scheme 'jar' " + NOT_ALLOWED);
    }
  }

  protected void assertRejected(String value, String scheme) throws Exception {
    try {
      Path path = objectMapper.readValue("\"" + value + "\"", Path.class);
      fail("Path deserialized from '" + value + "', whose URI scheme is not allowed: " + path);
    } catch (InvalidFormatException e) {
      assertThat(e).hasMessageContaining("scheme '" + scheme + "' " + NOT_ALLOWED);
      assertThat(e).hasMessageContaining("allowed: [\"file\"]");
    }
  }

}

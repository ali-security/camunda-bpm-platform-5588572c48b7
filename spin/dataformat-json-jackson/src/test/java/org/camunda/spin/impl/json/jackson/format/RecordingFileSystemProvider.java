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

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link FileSystemProvider} that stands in for a side-effecting
 * third-party provider: it records every instantiation and every
 * {@link #getPath(URI)} call, so {@link JacksonNioPathSchemeTest} can check that
 * deserializing a {@link Path} from a URI of a scheme that is not allowed never
 * loads or invokes the provider of that scheme.
 * <p>
 * It is registered under {@code nio-path-provider/} next to the test instead of
 * at the root of the test class path, so it is not one of the installed
 * providers that {@code Paths.get(URI)} sees, and it is only found through a
 * thread context class loader that the test sets up.
 */
public class RecordingFileSystemProvider extends FileSystemProvider {

  public static final String SCHEME = "sealtestfs";

  public static final AtomicInteger INSTANCES = new AtomicInteger();
  public static final AtomicInteger GET_PATH_CALLS = new AtomicInteger();

  public RecordingFileSystemProvider() {
    INSTANCES.incrementAndGet();
  }

  public static void reset() {
    INSTANCES.set(0);
    GET_PATH_CALLS.set(0);
  }

  @Override
  public String getScheme() {
    return SCHEME;
  }

  @Override
  public Path getPath(URI uri) {
    GET_PATH_CALLS.incrementAndGet();
    return Paths.get("/tmp/sealtestfs" + uri.getPath());
  }

  @Override
  public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
    throw new UnsupportedOperationException();
  }

  @Override
  public FileSystem getFileSystem(URI uri) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
      FileAttribute<?>... attrs) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void delete(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isSameFile(Path path, Path path2) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public FileStore getFileStore(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
      throws IOException {
    throw new UnsupportedOperationException();
  }

}

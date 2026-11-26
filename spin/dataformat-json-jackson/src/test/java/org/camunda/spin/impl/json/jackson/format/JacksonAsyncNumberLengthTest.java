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

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;

/**
 * The non-blocking (async) parser of the jackson-core that Spin builds against
 * must enforce {@link StreamReadConstraints#getMaxNumberLength()}, like the
 * blocking parsers do (CVE-2026-18401).
 */
public class JacksonAsyncNumberLengthTest {

  protected static final int MAX_NUMBER_LENGTH = StreamReadConstraints.defaults().getMaxNumberLength();
  protected static final int NUMBER_LENGTH = MAX_NUMBER_LENGTH * 2;
  protected static final int CHUNK_SIZE = 4 * 1024;
  protected static final int MAX_CHUNKS = 32;

  protected final JsonFactory jsonFactory = new JacksonJsonDataFormat("test").getObjectMapper().getFactory();

  @Test
  public void shouldRejectTooLongInteger() throws Exception {
    assertAsyncParserRejects("{\"v\":" + digits(NUMBER_LENGTH) + "}", JsonToken.VALUE_NUMBER_INT);
  }

  @Test
  public void shouldRejectTooLongNegativeInteger() throws Exception {
    assertAsyncParserRejects("{\"v\":-" + digits(NUMBER_LENGTH) + "}", JsonToken.VALUE_NUMBER_INT);
  }

  @Test
  public void shouldRejectTooLongDecimal() throws Exception {
    assertAsyncParserRejects("{\"v\":0." + digits(NUMBER_LENGTH) + "}", JsonToken.VALUE_NUMBER_FLOAT);
  }

  @Test
  public void shouldRejectTooLongExponent() throws Exception {
    assertAsyncParserRejects("{\"v\":1.1E" + digits(NUMBER_LENGTH), JsonToken.VALUE_NUMBER_FLOAT);
  }

  @Test
  public void shouldRejectIntegerStreamedInChunksWithoutTerminator() throws Exception {
    try (JsonParser parser = jsonFactory.createNonBlockingByteArrayParser()) {
      ByteArrayFeeder feeder = (ByteArrayFeeder) parser;
      byte[] preamble = "{\"v\":".getBytes(StandardCharsets.UTF_8);
      feeder.feedInput(preamble, 0, preamble.length);
      JsonToken token;
      while ((token = parser.nextToken()) != JsonToken.NOT_AVAILABLE) {
        assertThat(token).isNotNull();
      }

      // digit-only chunks, no terminator and no endOfInput(): the number never completes
      byte[] chunk = digits(CHUNK_SIZE).getBytes(StandardCharsets.UTF_8);
      int chunksFed = 0;
      try {
        for (int i = 0; i < MAX_CHUNKS; i++) {
          feeder.feedInput(chunk, 0, chunk.length);
          chunksFed++;
          assertThat(parser.nextToken()).isEqualTo(JsonToken.NOT_AVAILABLE);
        }
        fail("Async parser buffered " + (CHUNK_SIZE * MAX_CHUNKS) + " integer digits with maxNumberLength="
            + MAX_NUMBER_LENGTH);
      } catch (StreamConstraintsException e) {
        assertThat(e).hasMessageContaining("Number length");
        assertThat(chunksFed).isLessThanOrEqualTo(2);
      }
    }
  }

  @Test
  public void shouldAcceptIntegerAtMaxNumberLength() throws Exception {
    String number = digits(MAX_NUMBER_LENGTH);
    byte[] payload = ("{\"v\":" + number + "}").getBytes(StandardCharsets.UTF_8);

    try (JsonParser parser = jsonFactory.createNonBlockingByteArrayParser()) {
      ByteArrayFeeder feeder = (ByteArrayFeeder) parser;
      feeder.feedInput(payload, 0, payload.length);
      feeder.endOfInput();

      assertThat(parser.nextToken()).isEqualTo(JsonToken.START_OBJECT);
      assertThat(parser.nextToken()).isEqualTo(JsonToken.FIELD_NAME);
      assertThat(parser.nextToken()).isEqualTo(JsonToken.VALUE_NUMBER_INT);
      assertThat(parser.getText()).isEqualTo(number);
      assertThat(parser.nextToken()).isEqualTo(JsonToken.END_OBJECT);
    }
  }

  protected void assertAsyncParserRejects(String json, JsonToken numberToken) throws Exception {
    byte[] payload = json.getBytes(StandardCharsets.UTF_8);

    try (JsonParser parser = jsonFactory.createNonBlockingByteArrayParser()) {
      ByteArrayFeeder feeder = (ByteArrayFeeder) parser;
      feeder.feedInput(payload, 0, payload.length);
      feeder.endOfInput();

      try {
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
          if (token == numberToken) {
            fail("Async parser accepted a number of " + parser.getText().length() + " characters with maxNumberLength="
                + MAX_NUMBER_LENGTH);
          }
        }
        fail("Async parser must reject a number of " + NUMBER_LENGTH + " digits");
      } catch (StreamConstraintsException e) {
        assertThat(e).hasMessageContaining("Number length");
      }
    }
  }

  protected static String digits(int count) {
    StringBuilder sb = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      sb.append((char) ('1' + (i % 9)));
    }
    return sb.toString();
  }

}

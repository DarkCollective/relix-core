/*
 * Copyright 2026 Darkcollective, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package com.darkcollective.relix.connectors.std.internal;

import com.darkcollective.relix.connectors.std.internal.StreamInput.Format;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StreamInput — rows from a stream of text")
final class StreamInputTest {

    private static InputStream text(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a TSV field unescapes tab, newline, return and backslash, and keeps anything else")
    void tsvEscapes() {
        assertThat(StreamInput.split(Format.TSV, "a\\tb\tc\\nd\te\\rf\tg\\\\h\ti\\qj\tk\\"))
                .containsExactly("a\tb", "c\nd", "e\rf", "g\\h", "i\\qj", "k\\");
        assertThat(StreamInput.split(Format.TSV, "")).containsExactly("");
    }

    @Test
    @DisplayName("a column's type is the narrowest every sampled value reads as")
    void inference() {
        assertThat(StreamInput.inferText(List.of("1", "2.5", "-3"))).isEqualTo(ScalarType.NUMBER);
        assertThat(StreamInput.inferText(List.of("true", "FALSE"))).isEqualTo(ScalarType.BOOLEAN);
        assertThat(StreamInput.inferText(List.of("2026-01-02"))).isEqualTo(ScalarType.DATE);
        assertThat(StreamInput.inferText(List.of("2026-01-02T10:00:00Z"))).isEqualTo(ScalarType.TIMESTAMP);
        assertThat(StreamInput.inferText(List.of("1", "x"))).isEqualTo(ScalarType.STRING);
        assertThat(StreamInput.inferText(List.of())).isEqualTo(ScalarType.STRING);
    }

    @Test
    @DisplayName("a sampled row shorter or longer than the header still types the columns it has")
    void raggedSample() {
        StreamInput input = StreamInput.open(Format.CSV, text("x,y\n1\n2,3,4\n"), null, 10, "t");
        assertThat(input.schema().columns()).extracting(c -> c.type())
                .containsExactly(ScalarType.NUMBER, ScalarType.NUMBER);
    }

    @Test
    @DisplayName("a JSON string that reads as a date throughout is a DATE")
    void jsonDate() {
        StreamInput input = StreamInput.open(Format.NDJSON,
                text("{\"on\": \"2026-01-02\"}\n{\"on\": \"2026-02-03\"}\n"), null, 10, "t");
        assertThat(input.schema().columns().getFirst().type()).isEqualTo(ScalarType.DATE);
    }

    @Test
    @DisplayName("a byte-order mark before the header is not part of the first name")
    void byteOrderMark() {
        StreamInput input = StreamInput.open(Format.CSV, text("﻿id,name\n1,Ada\n"), null, 10, "t");
        assertThat(input.schema().columns().getFirst().name()).isEqualTo("id");
        try (var rows = input.rows()) {
            assertThat(rows.count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("blank lines are not records, in a delimited stream or in JSON lines")
    void blankLines() {
        try (var rows = StreamInput.open(Format.CSV, text("x\n1\n\n2\n"), null, 1, "t").rows()) {
            assertThat(rows.count()).isEqualTo(2);
        }
        try (var rows = StreamInput.open(Format.NDJSON, text("\n{\"x\": 1}\n\n{\"x\": 2}\n"), null, 1, "t").rows()) {
            assertThat(rows.count()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a JSON array must be an array, of objects")
    void arrayShape() {
        assertThatThrownBy(() -> StreamInput.open(Format.JSON, text("{\"x\": 1}"), null, 1, "t"))
                .isInstanceOf(EvaluationException.class).hasMessageContaining("JSON array");
        assertThatThrownBy(() -> StreamInput.open(Format.JSON, text("[1, 2]"), null, 1, "t"))
                .isInstanceOf(EvaluationException.class).hasMessageContaining("not a JSON object");
    }

    @Test
    @DisplayName("a JSON column whose values are mixed, or never set, is ANY")
    void jsonAny() {
        StreamInput input = StreamInput.open(Format.NDJSON,
                text("{\"a\": 1, \"b\": null}\n{\"a\": \"x\", \"b\": null}\n"), null, 10, "t");
        assertThat(input.schema().columns()).allSatisfy(c -> assertThat(c.type()).isEqualTo(ScalarType.ANY));
    }

    @Test
    @DisplayName("a JSON string of digits stays a string; a boolean column reads booleans")
    void jsonTypes() {
        StreamInput input = StreamInput.open(Format.NDJSON,
                text("{\"zip\": \"02139\", \"ok\": true}\n"), null, 10, "t");
        assertThat(input.schema().columns().get(0).type()).isEqualTo(ScalarType.STRING);
        assertThat(input.schema().columns().get(1).type()).isEqualTo(ScalarType.BOOLEAN);
        try (var rows = input.rows()) {
            assertThat(rows.count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a stream that fails while being read is reported as unreadable")
    void unreadable() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("pipe broken");
            }
        };
        assertThatThrownBy(() -> StreamInput.open(Format.CSV, failing, null, 1, "t"))
                .isInstanceOf(UncheckedIOException.class).hasMessageContaining("input 't'");
        assertThatThrownBy(() -> StreamInput.open(Format.CSV, text("x"), null, 0, "t"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

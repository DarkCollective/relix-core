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

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimestampValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LogConnector — an access log as a typed relation")
final class LogConnectorTest {

    private static final String LINE = "203.0.113.7 - frank [10/Oct/2026:13:02:11 +0200] "
            + "\"GET /index.html HTTP/1.1\" 200 5120 \"-\" \"Mozilla \\\"quoted\\\"\"";

    private static final LogConnector LOG = new LogConnector();

    private static Schema schema(Object... nameType) {
        List<ColumnDefinition> columns = new ArrayList<>();
        for (int i = 0; i < nameType.length; i += 2) {
            columns.add(new ColumnDefinition((String) nameType[i], (Type) nameType[i + 1]));
        }
        return new Schema(columns);
    }

    private static List<Row> read(Path file, Map<String, String> config, String table, Schema schema) {
        Map<String, String> values = new LinkedHashMap<>(config);
        values.put("path", file.toString());
        try (Stream<Row> rows = LOG.open(new ConnectorConfig(values), table, schema)) {
            return rows.toList();
        }
    }

    private static List<com.darkcollective.relix.value.Value> values(Row row) {
        return java.util.stream.IntStream.range(0, row.width()).mapToObj(row::get).toList();
    }

    private static Path write(Path dir, String name, String... lines) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    @Test
    @DisplayName("claims both type tokens")
    void handles() {
        assertThat(LOG.handles()).containsExactlyInAnyOrder("log", "clf");
    }

    @Nested
    @DisplayName("typing")
    class Typing {

        @Test
        @DisplayName("a combined line becomes typed columns, with '-' as NULL")
        void combined(@TempDir Path dir) throws IOException {
            Path file = write(dir, "access.log", LINE);
            Row row = read(file, Map.of("format", "combined"), "access.log", schema(
                    "host", ScalarType.STRING, "user", ScalarType.STRING, "ident", ScalarType.STRING,
                    "at", ScalarType.TIMESTAMP, "method", ScalarType.STRING, "path", ScalarType.STRING,
                    "protocol", ScalarType.STRING, "status", ScalarType.NUMBER, "bytes", ScalarType.NUMBER,
                    "referer", ScalarType.STRING, "agent", ScalarType.STRING, "absent", ScalarType.STRING))
                    .getFirst();
            assertThat(values(row)).containsExactly(
                    new StringValue("203.0.113.7"), new StringValue("frank"), NullValue.INSTANCE,
                    new TimestampValue(Instant.parse("2026-10-10T11:02:11Z")),
                    new StringValue("GET"), new StringValue("/index.html"), new StringValue("HTTP/1.1"),
                    new NumberValue(new BigDecimal("200")), new NumberValue(new BigDecimal("5120")),
                    NullValue.INSTANCE, new StringValue("Mozilla \"quoted\""), NullValue.INSTANCE);
        }

        @Test
        @DisplayName("a declared type other than the field's own converts its text")
        void declaredTypes(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log", LINE);
            Row row = read(file, Map.of("format", "combined"), "a.log", schema(
                    "at", ScalarType.STRING, "status", ScalarType.STRING, "host", ScalarType.ANY))
                    .getFirst();
            assertThat(values(row)).containsExactly(new StringValue("10/Oct/2026:13:02:11 +0200"),
                    new StringValue("200"), new StringValue("203.0.113.7"));
        }

        @Test
        @DisplayName("a text field declared as a number or timestamp is converted, or refused")
        void convertedOrRefused(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log", "7 2026-10-10T13:00:00Z x");
            Row row = read(file, Map.of("format", "%u %l %v"), "a.log",
                    schema("user", ScalarType.NUMBER, "ident", ScalarType.TIMESTAMP)).getFirst();
            assertThat(values(row)).containsExactly(new NumberValue(new BigDecimal("7")),
                    new TimestampValue(Instant.parse("2026-10-10T13:00:00Z")));
            assertThatThrownBy(() -> read(file, Map.of("format", "%u %l %v"), "a.log",
                    schema("server", ScalarType.NUMBER)))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("line 1 of a.log").hasMessageContaining("'x' as number");
            assertThatThrownBy(() -> read(file, Map.of("format", "%u %l %v"), "a.log",
                    schema("server", ScalarType.BOOLEAN)))
                    .isInstanceOf(EvaluationException.class).hasMessageContaining("for column 'server'");
        }

        @Test
        @DisplayName("a field that is not what its directive promises is an error naming the line")
        void badField(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log", "h - - [yesterday] \"GET / HTTP/1.1\" 200 1");
            assertThatThrownBy(() -> read(file, Map.of(), "a.log", schema("at", ScalarType.TIMESTAMP)))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("line 1 of a.log").hasMessageContaining("as timestamp");
        }

        @Test
        @DisplayName("a request line that will not split keeps its text as the path")
        void oddRequest(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log",
                    "h - - [10/Oct/2026:13:02:11 +0000] \"garbage\" 400 0",
                    "h - - [10/Oct/2026:13:02:11 +0000] \"GET /old\" 200 0",
                    "h - - [10/Oct/2026:13:02:11 +0000] \"-\" 408 -");
            List<Row> rows = read(file, Map.of("format", "common"), "a.log",
                    schema("method", ScalarType.STRING, "path", ScalarType.STRING, "protocol", ScalarType.STRING,
                            "bytes", ScalarType.NUMBER));
            assertThat(values(rows.get(0))).containsExactly(NullValue.INSTANCE, new StringValue("garbage"),
                    NullValue.INSTANCE, new NumberValue(BigDecimal.ZERO));
            assertThat(values(rows.get(1))).containsExactly(new StringValue("GET"), new StringValue("/old"),
                    NullValue.INSTANCE, new NumberValue(BigDecimal.ZERO));
            assertThat(values(rows.get(2))).containsExactly(NullValue.INSTANCE, NullValue.INSTANCE,
                    NullValue.INSTANCE, NullValue.INSTANCE);
        }

        @Test
        @DisplayName("an empty field is NULL, and an unquoted trailing backslash is kept")
        void emptyAndBackslash(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log", ":a\\");
            Row row = read(file, Map.of("format", "%u:%l"), "a.log",
                    schema("user", ScalarType.STRING, "ident", ScalarType.STRING)).getFirst();
            assertThat(values(row)).containsExactly(NullValue.INSTANCE, new StringValue("a\\"));
        }

        @Test
        @DisplayName("a nested column cannot hold a log field")
        void nestedColumn(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log", LINE);
            assertThatThrownBy(() -> read(file, Map.of("format", "combined"), "a.log",
                    schema("host", new com.darkcollective.relix.symbol.ArrayType(ScalarType.STRING))))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("column 'host' cannot be");
        }

        @Test
        @DisplayName("an nginx format string, with an ISO time")
        void nginx(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log", "10.0.0.1 [2026-10-10T13:00:00+00:00] \"POST /q HTTP/2\" 201 0.25 \"UA\"");
            Row row = read(file, Map.of("format",
                    "$remote_addr [$time_iso8601] \"$request\" $status $request_time \"$http_user_agent\""),
                    "a.log", schema("host", ScalarType.STRING, "at", ScalarType.TIMESTAMP,
                            "method", ScalarType.STRING, "duration_s", ScalarType.NUMBER,
                            "agent", ScalarType.STRING)).getFirst();
            assertThat(values(row)).containsExactly(new StringValue("10.0.0.1"),
                    new TimestampValue(Instant.parse("2026-10-10T13:00:00Z")), new StringValue("POST"),
                    new NumberValue(new BigDecimal("0.25")), new StringValue("UA"));
        }

        @Test
        @DisplayName("nginx's time_local sits inside literal brackets")
        void nginxLocal(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log", "[10/Oct/2026:13:02:11 +0000] ok");
            Row row = read(file, Map.of("format", "[$time_local] $remote_user"), "a.log",
                    schema("at", ScalarType.TIMESTAMP, "user", ScalarType.STRING)).getFirst();
            assertThat(values(row)).containsExactly(
                    new TimestampValue(Instant.parse("2026-10-10T13:02:11Z")), new StringValue("ok"));
        }
    }

    @Nested
    @DisplayName("lines")
    class Lines {

        @Test
        @DisplayName("an unmatched line is refused, naming its number")
        void refused(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log", LINE, "", "not a log line");
            assertThatThrownBy(() -> read(file, Map.of("format", "combined"), "a.log", schema("host", ScalarType.STRING)))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("line 3 of a.log does not match the log format: not a log line");
        }

        @Test
        @DisplayName("a long unmatched line is shortened in the message")
        void longLine(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log", "x".repeat(200));
            assertThatThrownBy(() -> read(file, Map.of(), "a.log", schema("host", ScalarType.STRING)))
                    .isInstanceOf(EvaluationException.class).hasMessageEndingWith("…");
        }

        @Test
        @DisplayName("onError: skip leaves unmatched lines out")
        void skipped(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log", LINE, "not a log line", LINE);
            assertThat(read(file, Map.of("format", "combined", "onError", "SKIP"), "a.log",
                    schema("host", ScalarType.STRING))).hasSize(2);
        }

        @Test
        @DisplayName("onError takes refuse or skip")
        void badOnError(@TempDir Path dir) throws IOException {
            Path file = write(dir, "a.log", LINE);
            assertThatThrownBy(() -> read(file, Map.of("onError", "ignore"), "a.log", schema("host", ScalarType.STRING)))
                    .isInstanceOf(EvaluationException.class).hasMessageContaining("not \"ignore\"");
        }

        @Test
        @DisplayName("a gzip log reads like a plain one")
        void gzip(@TempDir Path dir) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var gz = new GZIPOutputStream(bytes)) {
                gz.write((LINE + "\n").getBytes(StandardCharsets.UTF_8));
            }
            Files.write(dir.resolve("access.log.2.gz"), bytes.toByteArray());
            assertThat(read(dir, Map.of("format", "combined"), "access.log.2.gz",
                    schema("host", ScalarType.STRING))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("tables")
    class Tables {

        @Test
        @DisplayName("in a directory, a table is a file in it")
        void directory(@TempDir Path dir) throws IOException {
            write(dir, "access.log", LINE);
            assertThat(LogConnector.file(dir, "access.log")).isEqualTo(dir.resolve("access.log"));
            for (String bad : new String[] {"", "../x", "a/b", "a\\b", "..", ".", null}) {
                assertThatThrownBy(() -> LogConnector.file(dir, bad)).as(String.valueOf(bad))
                        .isInstanceOf(EvaluationException.class).hasMessageContaining("names a file in it");
            }
        }

        @Test
        @DisplayName("on a file, the only table is the file")
        void file(@TempDir Path dir) throws IOException {
            Path file = write(dir, "access.log", LINE);
            assertThat(LogConnector.file(file, "access.log")).isEqualTo(file);
            assertThat(LogConnector.file(file, null)).isEqualTo(file);
            assertThat(LogConnector.file(file, " ")).isEqualTo(file);
            assertThatThrownBy(() -> LogConnector.file(file, "other.log"))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("its only table is 'access.log'");
        }

        @Test
        @DisplayName("the heading comes from the format, without the file")
        void tableSchema() {
            Schema schema = LOG.tableSchema(new ConnectorConfig(Map.of("format", "combined")), "x").orElseThrow();
            assertThat(schema.columns()).extracting(ColumnDefinition::name).containsExactly(
                    "host", "ident", "user", "at", "method", "path", "protocol", "status", "bytes",
                    "referer", "agent");
            assertThat(schema.column("at").orElseThrow().type()).isEqualTo(ScalarType.TIMESTAMP);
            assertThat(LOG.tableSchema(new ConnectorConfig(Map.of("format", "%Z")), "x")).isEmpty();
        }
    }

    @Nested
    @DisplayName("format strings")
    class Formats {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"%Z", "%{x}Z", "$nope", "$http_", "%h $remote_addr", "no fields", "%h %h", "%r %U"})
        @DisplayName("an unreadable format is refused")
        void refused(String format) {
            assertThatThrownBy(() -> LogFormat.parse(format)).isInstanceOf(EvaluationException.class);
        }

        @Test
        @DisplayName("every directive has a column")
        void everyDirective() {
            assertThat(LogFormat.parse("%a %m %U %q %H %v %D %T %B %%x %{X-Trace-Id}i").columns())
                    .containsOnlyKeys("address", "method", "path", "query", "protocol", "server",
                            "duration_us", "duration_s", "bytes", "x_trace_id");
            assertThat(LogFormat.parse("$bytes_sent $request_method $request_uri $server_protocol $host"
                    + " $http_x_forwarded_for $remote_user ${status}").columns())
                    .containsOnlyKeys("bytes_sent", "method", "path", "protocol", "server",
                            "x_forwarded_for", "user", "status");
        }

        @Test
        @DisplayName("the presets are named case-insensitively, clf being common")
        void presets() {
            assertThat(LogFormat.parse("CLF").columns()).hasSize(9);
            assertThat(LogFormat.parse("Combined").columns()).hasSize(11);
        }
    }
}

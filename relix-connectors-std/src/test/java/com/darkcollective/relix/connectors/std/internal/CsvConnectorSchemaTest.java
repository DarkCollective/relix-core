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
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.darkcollective.relix.processor.ProcessorAssertions.assertThatRows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CsvConnector — a CSV file's heading, and columns read by the names a source gives")
final class CsvConnectorSchemaTest {

    @TempDir
    Path dir;

    private static final CsvConnector CSV = new CsvConnector();

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private ConnectorConfig config(CsvFileSourceConfig csv) {
        Map<String, String> values = new java.util.LinkedHashMap<>(CsvConnector.config(csv).values());
        values.put("path", dir.resolve(csv.path()).toString());
        return new ConnectorConfig(values);
    }

    private static List<String> names(Schema schema) {
        return schema.columns().stream().map(ColumnDefinition::name).toList();
    }

    @Test
    @DisplayName("names a headed file's columns from its header and types them from its values")
    void fromTheHeader() throws IOException {
        write("r.csv", "id,region,on\n1,north,2026-01-02\n2,south,2026-01-03\n");
        Schema schema = CSV.tableSchema(config(new CsvFileSourceConfig("r.csv", true, List.of())), "R")
                .orElseThrow();
        assertThat(names(schema)).containsExactly("id", "region", "on");
        assertThat(schema.columns()).extracting(ColumnDefinition::type)
                .containsExactly(ScalarType.NUMBER, ScalarType.STRING, ScalarType.DATE);
    }

    @Test
    @DisplayName("names a headerless file's columns as the source gives them, reading its first line as data")
    void namedHeaderless() throws IOException {
        write("v.csv", "﻿2026-03-01,12\n2026-03-02,8\n");
        CsvFileSourceConfig csv = new CsvFileSourceConfig("v.csv", false, List.of(), List.of(),
                List.of("day", "hits"));
        Schema schema = CSV.tableSchema(config(csv), "V").orElseThrow();
        assertThat(names(schema)).containsExactly("day", "hits");
        assertThat(schema.columns()).extracting(ColumnDefinition::type)
                .containsExactly(ScalarType.DATE, ScalarType.NUMBER);
        try (Stream<Row> rows = CSV.open(config(csv), "V", schema)) {
            assertThatRows(rows.toList()).hasRowCount(2);
        }
    }

    @Test
    @DisplayName("names over a header replace it, and the header row is skipped when reading")
    void namedOverAHeader() throws IOException {
        write("h.csv", "Unnamed: 0,Unnamed: 0\n1,x\n2,y\n");
        CsvFileSourceConfig csv = new CsvFileSourceConfig("h.csv", true, List.of(), List.of(),
                List.of("n", "label"));
        Schema schema = CSV.tableSchema(config(csv), "H").orElseThrow();
        assertThat(names(schema)).containsExactly("n", "label");
        try (Stream<Row> rows = CSV.open(config(csv), "H", schema)) {
            List<Row> read = rows.toList();
            assertThatRows(read).hasRowCount(2);
            assertThat(read.getFirst().get("label").asDisplayString()).isEqualTo("x");
        }
    }

    @Test
    @DisplayName("a missing file, and one that cannot be read, are reported naming the path")
    void unreadable() throws IOException {
        assertThatThrownBy(() -> CSV.tableSchema(
                config(new CsvFileSourceConfig("gone.csv", true, List.of())), "G"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("there is no file").hasMessageContaining("gone.csv");
        Files.createDirectory(dir.resolve("adir.csv"));
        assertThatThrownBy(() -> CSV.tableSchema(
                config(new CsvFileSourceConfig("adir.csv", true, List.of())), "D"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("cannot read").hasMessageContaining("adir.csv");
    }

    @Test
    @DisplayName("the file-reading connector reads a named source by position too")
    void legacyConnectorReadsByPosition() throws IOException {
        write("h.csv", "a,b\n1,x\n2,y\n");
        String script = "source H from csv(\"h.csv\") { columns: [n, label] };\n"
                + "query { σ n > 1 (H) };";
        CatalogProvider catalog = new CatalogProvider() {
            @Override
            public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
                return Optional.empty();
            }

            @Override
            public Optional<Schema> sourceSchema(SourceDeclaration source) {
                return CSV.tableSchema(config((CsvFileSourceConfig) source.config()), source.name());
            }
        };
        SemanticModel model = SemanticFixtures.analyze(script, catalog).model().orElseThrow();
        List<Row> rows = new QueryExecutor().execute(model, new CsvDataSourceConnector(model, dir))
                .stream().flatMap(r -> r.rows().stream()).toList();
        assertThatRows(rows).hasRowCount(1);
        assertThat(rows.getFirst().get("label").asDisplayString()).isEqualTo("y");
    }

    @Test
    @DisplayName("a headerless stream reads a declared heading by position, or is refused with nothing to name it")
    void headerlessStreams() {
        Schema declared = new Schema(List.of(new ColumnDefinition("k", ScalarType.STRING)));
        StreamInput input = StreamInput.open(Format.CSV, text("a\nb\n"), declared, 10, "S",
                false, List.of());
        try (Stream<Row> rows = input.rows()) {
            assertThatRows(rows.toList()).hasRowCount(2);
        }
        assertThatThrownBy(() -> StreamInput.open(Format.CSV, text("a\n"), null, 10, "S",
                false, List.of()))
                .isInstanceOf(EvaluationException.class)
                .hasMessage("S has no header row, so nothing names its columns; name them,"
                        + " or declare its schema");
        StreamInput empty = StreamInput.open(Format.CSV, text(""), null, 10, "S", true, List.of("x"));
        assertThat(names(empty.schema())).containsExactly("x");
        StreamInput emptyHeaderless = StreamInput.open(Format.CSV, text(""), null, 10, "S",
                false, List.of("x"));
        try (Stream<Row> rows = emptyHeaderless.rows()) {
            assertThat(rows).isEmpty();
        }
    }

    private static ByteArrayInputStream text(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}

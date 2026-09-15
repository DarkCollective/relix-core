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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.lang.ast.source.*;
import com.darkcollective.relix.lang.ast.table.CsvInlineTable;
import com.darkcollective.relix.lang.ast.table.InlineTable;
import com.darkcollective.relix.lang.ast.table.MarkdownInlineTable;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for source-config AST nodes: {@link SourceDeclaration}, {@link ColumnSpec},
 * {@link HttpSourceConfig}, {@link DatabaseSourceConfig}, {@link CsvFileSourceConfig},
 * inline tables, and {@link InlineTableBody}.
 */
@DisplayName("relix-lang-ast — source and table nodes")
final class LangAstSourceTest {

    // =========================================================================
    // SourceDeclaration
    // =========================================================================

    @Nested
    @DisplayName("SourceDeclaration")
    class SourceDeclarationTests {

        private final ColumnSpec col = ColumnSpec.out("id", ScalarType.NUMBER);
        private final CsvFileSourceConfig csv = new CsvFileSourceConfig("./data.csv", true, List.of(col));

        @Test
        @DisplayName("of() factory creates exported declaration")
        void factoryCreatesExportedDeclaration() {
            SourceDeclaration sd = SourceDeclaration.of("Products", csv);
            assertThat(sd.exported()).isTrue();
            assertThat(sd.name()).isEqualTo("Products");
        }

        @Test
        @DisplayName("Rejects blank name")
        void rejectsBlankName() {
            assertThatThrownBy(() -> SourceDeclaration.of("", csv))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // ColumnSpec
    // =========================================================================

    @Nested
    @DisplayName("ColumnSpec")
    class ColumnSpecTests {

        @Test
        @DisplayName("out() factory creates OUT column without binding")
        void outFactoryCreatesOutColumn() {
            ColumnSpec c = ColumnSpec.out("name", ScalarType.STRING);
            assertThat(c.direction()).isEqualTo(ColumnDirection.OUT);
            assertThat(c.binding()).isEmpty();
            assertThat(c.required()).isFalse();
        }

        @Test
        @DisplayName("out() with path factory creates OUT column with extract binding")
        void outWithPathFactory() {
            ColumnSpec c = ColumnSpec.out("temp", ScalarType.NUMBER, "$.main.temp");
            assertThat(c.binding()).isPresent();
            assertThat(c.binding().get()).isInstanceOf(ExtractPathBinding.class);
        }

        @Test
        @DisplayName("requiredIn() creates required IN column")
        void requiredInFactory() {
            ColumnSpec c = ColumnSpec.requiredIn("city", ScalarType.STRING, "q");
            assertThat(c.direction()).isEqualTo(ColumnDirection.IN);
            assertThat(c.required()).isTrue();
            assertThat(c.defaultValue()).isEmpty();
        }

        @Test
        @DisplayName("optionalIn() creates optional IN column with default")
        void optionalInFactory() {
            ColumnSpec c = ColumnSpec.optionalIn("units", ScalarType.STRING, "units", "metric");
            assertThat(c.required()).isFalse();
            assertThat(c.defaultValue()).isEqualTo(Optional.of("metric"));
        }

        @Test
        @DisplayName("Rejects blank name")
        void rejectsBlankName() {
            assertThatThrownBy(() -> ColumnSpec.out("", ScalarType.STRING))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // HttpSourceConfig
    // =========================================================================

    @Nested
    @DisplayName("HttpSourceConfig")
    class HttpSourceConfigTests {

        private final List<ColumnSpec> cols = List.of(ColumnSpec.out("id", ScalarType.NUMBER));

        @Test
        @DisplayName("Stores all fields correctly")
        void storesAllFields() {
            HttpSourceConfig cfg = new HttpSourceConfig(
                    "https://api.example.com",
                    HttpMethod.GET,
                    Map.of("X-Key", "${API_KEY}"),
                    new JsonExtractSpec("$."),
                    Optional.empty(),
                    cols);
            assertThat(cfg.url()).isEqualTo("https://api.example.com");
            assertThat(cfg.method()).isEqualTo(HttpMethod.GET);
            assertThat(cfg.headers()).containsKey("X-Key");
        }

        @Test
        @DisplayName("Empty columns list selects an open (schema-on-read) source")
        void emptyColumnsIsOpen() {
            HttpSourceConfig cfg = new HttpSourceConfig(
                    "https://api.example.com", HttpMethod.GET, Map.of(),
                    new JsonExtractSpec("$."), Optional.empty(), List.of());
            assertThat(cfg.isOpen()).isTrue();
            assertThat(cfg.columns()).isEmpty();
        }

        @Test
        @DisplayName("Declared columns make the source closed; body/auth default empty")
        void declaredColumnsAreClosed() {
            HttpSourceConfig cfg = new HttpSourceConfig(
                    "https://api.example.com", HttpMethod.GET, Map.of(),
                    new JsonExtractSpec("$."), Optional.empty(), cols);
            assertThat(cfg.isOpen()).isFalse();
            assertThat(cfg.body()).isEmpty();
            assertThat(cfg.auth()).isEmpty();
            assertThat(cfg.extract()).contains(new JsonExtractSpec("$."));
        }

        @Test
        @DisplayName("Headers map is unmodifiable")
        void headersMapIsUnmodifiable() {
            HttpSourceConfig cfg = new HttpSourceConfig(
                    "https://api.example.com", HttpMethod.GET,
                    Map.of("Accept", "application/json"),
                    new JsonExtractSpec("$."), Optional.empty(), cols);
            assertThatThrownBy(() -> cfg.headers().put("X-New", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Rejects blank url")
        void rejectsBlankUrl() {
            assertThatThrownBy(() -> new HttpSourceConfig(
                    "  ", HttpMethod.GET, Map.of(),
                    new JsonExtractSpec("$."), Optional.empty(), cols))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // DatabaseSourceConfig
    // =========================================================================

    @Nested
    @DisplayName("DatabaseSourceConfig")
    class DatabaseSourceConfigTests {

        private final List<ColumnSpec> cols = List.of(ColumnSpec.out("id", ScalarType.NUMBER));

        @Test
        @DisplayName("Stores all fields")
        void storesAllFields() {
            DatabaseSourceConfig cfg = new DatabaseSourceConfig(
                    "jdbc:postgresql://localhost/db", "users", cols);
            assertThat(cfg.url()).isEqualTo("jdbc:postgresql://localhost/db");
            assertThat(cfg.table()).isEqualTo("users");
            assertThat(cfg.columns()).hasSize(1);
        }

        @Test
        @DisplayName("Rejects blank url")
        void rejectsBlankUrl() {
            assertThatThrownBy(() -> new DatabaseSourceConfig("  ", "users", cols))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects blank table")
        void rejectsBlankTable() {
            assertThatThrownBy(() -> new DatabaseSourceConfig(
                    "jdbc:postgresql://localhost/db", "", cols))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects empty columns")
        void rejectsEmptyColumns() {
            assertThatThrownBy(() -> new DatabaseSourceConfig(
                    "jdbc:postgresql://localhost/db", "users", List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Columns list is unmodifiable")
        void columnsListIsUnmodifiable() {
            DatabaseSourceConfig cfg = new DatabaseSourceConfig(
                    "jdbc:postgresql://localhost/db", "users", cols);
            assertThatThrownBy(() -> cfg.columns().add(ColumnSpec.out("name", ScalarType.STRING)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // CsvFileSourceConfig
    // =========================================================================

    @Nested
    @DisplayName("CsvFileSourceConfig")
    class CsvFileSourceConfigTests {

        private final List<ColumnSpec> cols = List.of(ColumnSpec.out("name", ScalarType.STRING));

        @Test
        @DisplayName("Stores all fields")
        void storesAllFields() {
            CsvFileSourceConfig cfg = new CsvFileSourceConfig("./data.csv", true, cols);
            assertThat(cfg.path()).isEqualTo("./data.csv");
            assertThat(cfg.hasHeader()).isTrue();
            assertThat(cfg.columns()).hasSize(1);
        }

        @Test
        @DisplayName("hasHeader false is stored")
        void hasHeaderFalseIsStored() {
            CsvFileSourceConfig cfg = new CsvFileSourceConfig("./data.csv", false, cols);
            assertThat(cfg.hasHeader()).isFalse();
        }

        @Test
        @DisplayName("Rejects blank path")
        void rejectsBlankPath() {
            assertThatThrownBy(() -> new CsvFileSourceConfig("", true, cols))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects empty columns")
        void rejectsEmptyColumns() {
            assertThatThrownBy(() -> new CsvFileSourceConfig("./data.csv", true, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Columns list is unmodifiable")
        void columnsListIsUnmodifiable() {
            CsvFileSourceConfig cfg = new CsvFileSourceConfig("./data.csv", true, cols);
            assertThatThrownBy(() -> cfg.columns().add(ColumnSpec.out("extra", ScalarType.NUMBER)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // Inline table nodes
    // =========================================================================

    @Nested
    @DisplayName("MarkdownInlineTable")
    class MarkdownInlineTableTests {

        @Test
        @DisplayName("Stores headers and rows")
        void storesHeadersAndRows() {
            MarkdownInlineTable t = new MarkdownInlineTable(
                    List.of("name", "country"),
                    List.of(List.of("Chicago", "US")));
            assertThat(t.headers()).containsExactly("name", "country");
            assertThat(t.rows()).hasSize(1);
        }

        @Test
        @DisplayName("Rejects empty headers")
        void rejectsEmptyHeaders() {
            assertThatThrownBy(() -> new MarkdownInlineTable(List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Row lists are unmodifiable")
        void rowsAreUnmodifiable() {
            MarkdownInlineTable t = new MarkdownInlineTable(
                    List.of("id"), List.of(List.of("1")));
            assertThatThrownBy(() -> t.rows().add(List.of("2")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("CsvInlineTable")
    class CsvInlineTableTests {

        @Test
        @DisplayName("Stores headers and rows")
        void storesHeadersAndRows() {
            CsvInlineTable t = new CsvInlineTable(
                    List.of("id", "name"),
                    List.of(List.of("1", "Alice"), List.of("2", "Bob")));
            assertThat(t.headers()).containsExactly("id", "name");
            assertThat(t.rows()).hasSize(2);
        }

        @Test
        @DisplayName("Rejects empty headers")
        void rejectsEmptyHeaders() {
            assertThatThrownBy(() -> new CsvInlineTable(List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("InlineTableBody")
    class InlineTableBodyTests {

        @Test
        @DisplayName("Stores inline table")
        void storesInlineTable() {
            InlineTable table = new CsvInlineTable(List.of("id"), List.of(List.of("1")));
            InlineTableBody body = new InlineTableBody(table);
            assertThat(body.table()).isSameAs(table);
        }

        @Test
        @DisplayName("Rejects null table")
        void rejectsNullTable() {
            assertThatThrownBy(() -> new InlineTableBody(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Works with MarkdownInlineTable")
        void worksWithMarkdownTable() {
            InlineTable table = new MarkdownInlineTable(
                    List.of("city"), List.of(List.of("Chicago")));
            InlineTableBody body = new InlineTableBody(table);
            assertThat(body.table()).isInstanceOf(MarkdownInlineTable.class);
        }
    }
}

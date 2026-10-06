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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@code csv("…")} source that leaves its schema out has it inferred from the file
 * when the script is analysed.
 */
@DisplayName("A CSV source's schema, inferred from its file")
final class CsvSchemaInferenceTest {

    private static final String RESPONSES = """
            id,region,score,passed,answered
            1,north,7.5,true,2026-01-02
            2,south,9,false,2026-01-03
            3,north,,true,2026-02-01
            """;

    @TempDir
    Path dir;

    private Relix session() {
        return Relix.builder().baseDirectory(dir).build();
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    @Nested
    @DisplayName("with a header row")
    final class WithAHeader {

        @Test
        @DisplayName("names each column from the header and types it from its values")
        void inferred() throws IOException {
            write("responses.csv", RESPONSES);
            try (Relix relix = session()) {
                relix.define("source Responses from csv(\"responses.csv\") { };");

                assertThat(relix.relation("Responses")).schema()
                        .hasColumnNames("id", "region", "score", "passed", "answered")
                        .hasColumn("id", ScalarType.NUMBER)
                        .hasColumn("region", ScalarType.STRING)
                        .hasColumn("score", ScalarType.NUMBER)
                        .hasColumn("passed", ScalarType.BOOLEAN)
                        .hasColumn("answered", ScalarType.DATE);
                assertThat(relix.relation("π id (σ score > 8 ∧ passed = false (Responses))"))
                        .rows().hasRowCount(1).hasRow("2");
            }
        }

        @Test
        @DisplayName("type-checks a query against what it inferred")
        void typeChecks() throws IOException {
            write("responses.csv", RESPONSES);
            try (Relix relix = session()) {
                relix.define("source Responses from csv(\"responses.csv\") { };");

                assertThatThrownBy(() -> relix.relation("σ nosuch = 1 (Responses)"))
                        .isInstanceOf(RelixException.class).hasMessageContaining("'nosuch'");
                assertThat(relix.relation("π id (σ answered < DATE '2026-01-03' (Responses))"))
                        .rows().hasRowCount(1).hasRow("1");
            }
        }

        @Test
        @DisplayName("renames the columns by position when it names them, skipping the header")
        void renamed() throws IOException {
            write("responses.csv", RESPONSES);
            try (Relix relix = session()) {
                relix.define("""
                        source Responses from csv("responses.csv") {
                            columns: [rid, area, mark, ok, `answered on`]
                        };""");

                assertThat(relix.relation("Responses")).schema()
                        .hasColumnNames("rid", "area", "mark", "ok", "answered on")
                        .hasColumn("mark", ScalarType.NUMBER);
                assertThat(relix.relation("π rid (σ area = \"north\" (Responses))"))
                        .rows().hasRowCount(2).hasRow("1").hasRow("3");
            }
        }

        @Test
        @DisplayName("refuses a later value that does not fit, naming its line")
        void laterValueDoesNotFit() throws IOException {
            StringBuilder text = new StringBuilder("n\n");
            for (int i = 0; i < 1000; i++) {
                text.append(i).append('\n');
            }
            text.append("many\n");
            write("counts.csv", text.toString());
            try (Relix relix = session()) {
                relix.define("source Counts from csv(\"counts.csv\") { };");

                assertThat(relix.relation("Counts")).schema().hasColumn("n", ScalarType.NUMBER);
                assertThatThrownBy(() -> relix.relation("Counts").toList())
                        .hasMessageContaining("1002");
            }
        }
    }

    @Nested
    @DisplayName("with no header row")
    final class WithoutAHeader {

        @Test
        @DisplayName("takes the names it is given and infers their types")
        void named() throws IOException {
            write("visits.csv", "2026-03-01,/home,12\n2026-03-01,/about,3\n2026-03-02,/home,8\n");
            try (Relix relix = session()) {
                relix.define("""
                        source Visits from csv("visits.csv") {
                            header: false,
                            columns: [day, page, hits]
                        };""");

                assertThat(relix.relation("Visits")).schema()
                        .hasColumnNames("day", "page", "hits")
                        .hasColumn("day", ScalarType.DATE)
                        .hasColumn("hits", ScalarType.NUMBER);
                assertThat(relix.relation("γ page, SUM(hits) → total (Visits)"))
                        .rows().hasRowCount(2).hasRow("/home", "20").hasRow("/about", "3");
            }
        }

        @Test
        @DisplayName("is refused without names, saying what to declare")
        void unnamed() throws IOException {
            write("visits.csv", "2026-03-01,/home,12\n");
            try (Relix relix = session()) {
                assertThatThrownBy(() -> relix.define(
                        "source Visits from csv(\"visits.csv\") { header: false };"))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("Cannot infer the columns of CSV source 'Visits'")
                        .hasMessageContaining("has no header row, so nothing names its columns;"
                                + " name them, or declare its schema");
            }
        }
    }

    @Nested
    @DisplayName("when the file cannot be read")
    final class Unreadable {

        @Test
        @DisplayName("analysis says so, naming the file, rather than reporting a column")
        void missing() {
            try (Relix relix = session()) {
                assertThatThrownBy(() -> relix.define(
                        "source Gone from csv(\"gone.csv\") { };\nV := { σ x = 1 (Gone) };"))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("Cannot infer the columns of CSV source 'Gone' (\"gone.csv\")")
                        .hasMessageContaining("there is no file")
                        .hasMessageContaining("gone.csv")
                        .hasMessageNotContaining("'x'");
            }
        }

        @Test
        @DisplayName("an empty file has no header to name its columns")
        void empty() throws IOException {
            write("empty.csv", "");
            try (Relix relix = session()) {
                assertThatThrownBy(() -> relix.define("source E from csv(\"empty.csv\") { };"))
                        .hasMessageContaining("is empty: it needs a header row naming its columns");
            }
        }
    }

    @Test
    @DisplayName("finds the file through the session's placeholders, as a query would")
    void throughPlaceholders() throws IOException {
        write("responses.csv", RESPONSES);
        try (Relix relix = Relix.builder()
                .placeholders(name -> name.equals("DATA") ? Optional.of(dir.toString()) : Optional.empty())
                .build()) {
            relix.define("source Responses from csv(\"${DATA}/responses.csv\") { };");

            assertThat(relix.relation("Responses")).hasRowCount(3);
        }
    }

    @Test
    @DisplayName("a declared schema reads no file at analysis")
    void declaredReadsNothing() {
        try (Relix relix = session()) {
            relix.define("source Gone from csv(\"gone.csv\") { schema: { x: NUMBER } };");

            assertThat(relix.relation("Gone")).schema().hasColumn("x", ScalarType.NUMBER);
        }
    }

    @Nested
    @DisplayName("Relix.input, the same for a stream")
    final class FromAStream {

        private static ByteArrayInputStream text(String content) {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("names a headerless stream's columns, inferring their types")
        void named() {
            try (Relix relix = Relix.open()) {
                relix.input("visits", Input.of(InputFormat.CSV,
                        text("2026-03-01,/home,12\n2026-03-02,/home,8\n")).names("day", "page", "hits"));

                assertThat(relix.relation("visits")).schema()
                        .hasColumn("day", ScalarType.DATE).hasColumn("hits", ScalarType.NUMBER);
                assertThat(relix.relation("visits")).hasRowCount(2);
            }
        }

        @Test
        @DisplayName("names a headerless TSV stream with a declared schema")
        void namedAndDeclared() {
            try (Relix relix = Relix.open()) {
                relix.input("visits", Input.of(InputFormat.TSV, text("x\t1\ny\t2\n"))
                        .names("k", "v")
                        .schema(new com.darkcollective.relix.symbol.Schema(java.util.List.of(
                                new com.darkcollective.relix.symbol.ColumnDefinition("k", ScalarType.STRING),
                                new com.darkcollective.relix.symbol.ColumnDefinition("v", ScalarType.STRING)))));

                assertThat(relix.relation("visits")).rows().hasRowCount(2).hasRow("x", "1");
            }
        }

        @Test
        @DisplayName("refuses names for JSON, an empty list, and a name given twice")
        void refused() {
            assertThatThrownBy(() -> Input.of(InputFormat.NDJSON, text("{}")).names("a"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("names are for CSV and TSV");
            assertThatThrownBy(() -> Input.of(InputFormat.JSON, text("[]")).names("a"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Input.of(InputFormat.CSV, text("")).names("a", "A"))
                    .hasMessageContaining("names gives a column twice");
            org.assertj.core.api.Assertions.assertThat(Input.of(InputFormat.CSV, text("")).names())
                    .isEmpty();
        }
    }

    /** Placeholders are resolved before the file is read, so one with no value is reported. */
    @Test
    @DisplayName("an unresolvable placeholder in the path is reported for the source")
    void unresolvablePlaceholder() {
        try (Relix relix = Relix.builder().placeholders(name -> Optional.empty()).build()) {
            assertThatThrownBy(() -> relix.define("source R from csv(\"${NOPE}/r.csv\") { };"))
                    .isInstanceOf(RelixException.class)
                    .hasMessageContaining("NOPE");
        }
    }
}

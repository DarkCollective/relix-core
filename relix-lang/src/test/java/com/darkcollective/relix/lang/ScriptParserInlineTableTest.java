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
package com.darkcollective.relix.lang;

import com.darkcollective.relix.lang.ast.*;
import com.darkcollective.relix.lang.ast.table.CsvInlineTable;
import com.darkcollective.relix.lang.ast.table.MarkdownInlineTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ScriptParser} covering inline table assignments:
 * Markdown tables, CSV inline tables, error paths, and CSV line-parsing edge cases.
 */
@DisplayName("ScriptParser — inline tables")
class ScriptParserInlineTableTest {

    private static Script parse(String source) {
        return ScriptParser.parse(source);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Statement> T firstStatement(Script s) {
        return (T) s.statements().get(0);
    }

    // =========================================================================
    // Markdown inline tables
    // =========================================================================

    @Nested
    @DisplayName("assignment — Markdown inline table")
    class MarkdownTableTests {

        @Test
        @DisplayName("Parses header and data rows")
        void parsesHeaderAndDataRows() {
            String src = """
                    Cities := [
                    | name         | country |
                    |--------------|---------|
                    | Chicago      | US      |
                    | London       | UK      |
                    ];
                    """;
            Script s = parse(src);
            AssignmentStatement a = firstStatement(s);
            InlineTableBody body = (InlineTableBody) a.body();
            MarkdownInlineTable t = (MarkdownInlineTable) body.table();
            assertThat(t.headers()).containsExactly("name", "country");
            assertThat(t.rows()).hasSize(2);
            assertThat(t.rows().get(0)).containsExactly("Chicago", "US");
            assertThat(t.rows().get(1)).containsExactly("London", "UK");
        }

        @Test
        @DisplayName("Empty rows produces only headers")
        void emptyRowsProducesOnlyHeaders() {
            String src = "Empty := [\n| id | name |\n|-----|------|\n];";
            Script s = parse(src);
            InlineTableBody body = (InlineTableBody) ((AssignmentStatement) firstStatement(s)).body();
            MarkdownInlineTable t = (MarkdownInlineTable) body.table();
            assertThat(t.headers()).containsExactly("id", "name");
            assertThat(t.rows()).isEmpty();
        }
    }

    // =========================================================================
    // CSV inline tables
    // =========================================================================

    @Nested
    @DisplayName("assignment — CSV inline table")
    class CsvTableTests {

        @Test
        @DisplayName("Parses CSV with quoted fields")
        void parsesCsvWithQuotedFields() {
            String src = """
                    Cities := csv[
                        name, country
                        "Chicago, IL", US
                        London, UK
                    ];
                    """;
            Script s = parse(src);
            AssignmentStatement a = firstStatement(s);
            InlineTableBody body = (InlineTableBody) a.body();
            CsvInlineTable t = (CsvInlineTable) body.table();
            assertThat(t.headers()).containsExactly("name", "country");
            assertThat(t.rows()).hasSize(2);
            assertThat(t.rows().get(0)).containsExactly("Chicago, IL", "US");
            assertThat(t.rows().get(1)).containsExactly("London", "UK");
        }

        @Test
        @DisplayName("Parses single-column CSV")
        void parsesSingleColumnCsv() {
            String src = "Tags := csv[\n    tag\n    alpha\n    beta\n];";
            Script s = parse(src);
            InlineTableBody body = (InlineTableBody) ((AssignmentStatement) firstStatement(s)).body();
            CsvInlineTable t = (CsvInlineTable) body.table();
            assertThat(t.headers()).containsExactly("tag");
            assertThat(t.rows()).hasSize(2);
        }
    }

    // =========================================================================
    // Inline table error paths
    // =========================================================================

    @Nested
    @DisplayName("inline table error paths")
    class InlineTableErrorTests {

        @Test
        @DisplayName("Markdown table with no header row throws")
        void markdownNoHeader() {
            assertThatThrownBy(() -> parse("X := [\n\n];"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("CSV inline table with no header row throws")
        void csvNoHeader() {
            assertThatThrownBy(() -> parse("X := csv[\n\n];"))
                    .isInstanceOf(LangParseException.class);
        }
    }

    // =========================================================================
    // CSV line parsing edge cases
    // =========================================================================

    @Nested
    @DisplayName("CSV inline table parsing edge cases")
    class CsvLineParsingTests {

        @Test
        @DisplayName("Double-quote escape within quoted field")
        void doubleQuoteEscapeInField() {
            String src = "X := csv[\ntext, msg\n\"say \"\"hi\"\"\", world\n];";
            Script s = parse(src);
            InlineTableBody body = (InlineTableBody) ((AssignmentStatement) firstStatement(s)).body();
            CsvInlineTable t = (CsvInlineTable) body.table();
            assertThat(t.rows().get(0).get(0)).isEqualTo("say \"hi\"");
            assertThat(t.rows().get(0).get(1)).isEqualTo("world");
        }

        @Test
        @DisplayName("Quoted field at end of line with no trailing comma")
        void quotedFieldAtEndOfLine() {
            String src = "X := csv[\nid, name\n1, \"Alice\"\n];";
            Script s = parse(src);
            InlineTableBody body = (InlineTableBody) ((AssignmentStatement) firstStatement(s)).body();
            CsvInlineTable t = (CsvInlineTable) body.table();
            assertThat(t.rows().get(0).get(1)).isEqualTo("Alice");
        }

        @Test
        @DisplayName("Markdown table skips non-pipe lines between rows")
        void markdownTableSkipsNonPipeLines() {
            String src = """
                    T := [
                    | a | b |
                    |---|---|

                    | 1 | 2 |
                    ];
                    """;
            Script s = parse(src);
            InlineTableBody body = (InlineTableBody)
                    ((AssignmentStatement) firstStatement(s)).body();
            MarkdownInlineTable t = (MarkdownInlineTable) body.table();
            assertThat(t.rows()).hasSize(1);
        }
    }
}

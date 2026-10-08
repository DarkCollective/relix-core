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

        @Test
        @DisplayName("a markdown table on one line is refused where its header runs into its rows")
        void markdownOnOneLine() {
            assertThatThrownBy(() -> parse("X := [ | item | value | |------|-------| | a | 6 | ];"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("column 3 has no name")
                    .hasMessageContaining("each row of a markdown table goes on a line of its own")
                    .hasMessageContaining("line 1, col 8");
        }

        @Test
        @DisplayName("a header cell with no name is refused, in either form")
        void blankColumnName() {
            assertThatThrownBy(() -> parse("X := [\n| a |  |\n|---|---|\n| 1 | 2 |\n];"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Markdown inline table's column 2 has no name")
                    .hasMessageContaining("line 2, col 1");
            assertThatThrownBy(() -> parse("X := csv[\n  a,,b\n  1,2,3\n];"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("CSV inline table's column 2 has no name")
                    .hasMessageNotContaining("markdown")
                    .hasMessageContaining("line 2, col 3");
        }

        @Test
        @DisplayName("a column named twice is refused, ignoring case as columns are matched")
        void duplicateColumn() {
            assertThatThrownBy(() -> parse("X := [\n| id | ID |\n|---|---|\n| 1 | 2 |\n];"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("names column 'ID' twice");
        }

        @Test
        @DisplayName("a row with a cell too many or too few is refused, rather than cut or padded")
        void rowWidth() {
            assertThatThrownBy(() -> parse("X := [\n| a | b |\n|---|---|\n| 1 | 2 | 3 |\n];"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("row has 3 cells, but its header names 2 columns")
                    .hasMessageContaining("line 4, col 1");
            assertThatThrownBy(() -> parse("X := [\n| a |\n|---|\n| 1 | 2 |\n];"))
                    .hasMessageContaining("row has 2 cells, but its header names 1 column");
            assertThatThrownBy(() -> parse("X := csv[\n  a, b\n  1\n];"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("CSV inline table row has 1 cell, but its header names 2 columns");
        }

        @Test
        @DisplayName("a markdown row may end with a comment after its last pipe")
        void trailingComment() {
            Script s = parse("X := [\n| a | b |\n|---|---|\n| 1 | 2 |  -- the first\n| 3 | 4 |\n];");
            InlineTableBody body = (InlineTableBody) ((AssignmentStatement) firstStatement(s)).body();
            assertThat(((MarkdownInlineTable) body.table()).rows())
                    .containsExactly(java.util.List.of("1", "2"), java.util.List.of("3", "4"));
        }

        @Test
        @DisplayName("an empty cell is how a row writes a missing value")
        void emptyCellIsAccepted() {
            Script s = parse("X := [\n| a | b |\n|---|---|\n| 1 |  |\n];");
            InlineTableBody body = (InlineTableBody) ((AssignmentStatement) firstStatement(s)).body();
            assertThat(((MarkdownInlineTable) body.table()).rows().getFirst()).containsExactly("1", "");
        }
    }

    @Test
    @DisplayName("a csv table prints its commas and quotes quoted, so it reads back unchanged")
    void csvPrintsQuoted() {
        Script s = parse("X := csv[\ncity, note\n\"Austin, TX\", \"say \"\"hi\"\"\"\n];");
        String printed = ScriptPrinter.print(s);

        assertThat(printed).contains("\"Austin, TX\", \"say \"\"hi\"\"\"");
        InlineTableBody body = (InlineTableBody) ((AssignmentStatement) firstStatement(parse(printed))).body();
        assertThat(((CsvInlineTable) body.table()).rows().getFirst()).containsExactly("Austin, TX", "say \"hi\"");
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

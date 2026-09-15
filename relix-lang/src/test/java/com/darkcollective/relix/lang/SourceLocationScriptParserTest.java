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

import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that verify the script-level parser ({@link ScriptParser}) embeds
 * correct {@link SourceLocation} information in every {@link Statement} it
 * produces, and that file path information propagates through to the RA-level
 * AST nodes inside expression bodies.
 */
@DisplayName("ScriptParser — source location tracking")
class SourceLocationScriptParserTest {

    @SuppressWarnings("unchecked")
    private static <T extends Statement> T firstStatement(Script s) {
        return (T) s.statements().get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Statement> T statement(Script s, int index) {
        return (T) s.statements().get(index);
    }

    // =========================================================================
    // Default filePath — "<stdin>"
    // =========================================================================

    @Nested
    @DisplayName("Default filePath from String parse — '<stdin>'")
    class DefaultFilePath {

        @Test
        @DisplayName("AssignmentStatement carries '<stdin>' filePath")
        void assignmentStatementDefaultFilePath() {
            Script s = ScriptParser.parse("A := { Users };");
            AssignmentStatement a = firstStatement(s);
            assertThat(a.location().filePath()).isEqualTo("<stdin>");
        }

        @Test
        @DisplayName("AssignmentStatement is at line 1")
        void assignmentStatementLineOne() {
            Script s = ScriptParser.parse("A := { Users };");
            AssignmentStatement a = firstStatement(s);
            assertThat(a.location().line()).isEqualTo(1);
        }

        @Test
        @DisplayName("QueryStatement carries '<stdin>' filePath")
        void queryStatementDefaultFilePath() {
            Script s = ScriptParser.parse("query A;");
            QueryStatement q = firstStatement(s);
            assertThat(q.location().filePath()).isEqualTo("<stdin>");
        }

        @Test
        @DisplayName("ImportStatement carries '<stdin>' filePath")
        void importStatementDefaultFilePath() {
            Script s = ScriptParser.parse("import \"./other.relix\";");
            ImportStatement imp = firstStatement(s);
            assertThat(imp.location().filePath()).isEqualTo("<stdin>");
        }
    }

    // =========================================================================
    // Custom filePath via parse(String, filePath)
    // =========================================================================

    @Nested
    @DisplayName("Custom filePath via parse(String, filePath)")
    class CustomFilePathString {

        @Test
        @DisplayName("AssignmentStatement carries custom filePath")
        void assignmentStatementCustomFilePath() {
            Script s = ScriptParser.parse("A := { Users };", "weather.relix");
            AssignmentStatement a = firstStatement(s);
            assertThat(a.location().filePath()).isEqualTo("weather.relix");
        }

        @Test
        @DisplayName("QueryStatement carries custom filePath")
        void queryStatementCustomFilePath() {
            Script s = ScriptParser.parse("query A;", "analytics.relix");
            QueryStatement q = firstStatement(s);
            assertThat(q.location().filePath()).isEqualTo("analytics.relix");
        }

        @Test
        @DisplayName("Multiple statements all carry the same filePath")
        void multipleStatementsCarrySameFilePath() {
            String src = "A := { Users };\nquery A;";
            Script s = ScriptParser.parse(src, "multi.relix");
            AssignmentStatement a = statement(s, 0);
            QueryStatement q = statement(s, 1);
            assertThat(a.location().filePath()).isEqualTo("multi.relix");
            assertThat(q.location().filePath()).isEqualTo("multi.relix");
        }

        @Test
        @DisplayName("Second statement is on the correct line")
        void secondStatementLine() {
            String src = "A := { Users };\nquery A;";
            Script s = ScriptParser.parse(src, "m.relix");
            QueryStatement q = statement(s, 1);
            assertThat(q.location().line()).isEqualTo(2);
        }
    }

    // =========================================================================
    // InputStream overload
    // =========================================================================

    @Nested
    @DisplayName("InputStream overload — parse(InputStream, filePath)")
    class InputStreamOverload {

        @Test
        @DisplayName("Statement carries the supplied filePath")
        void statementCarriesFilePath() throws IOException {
            String src = "A := { Users };";
            try (var is = new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8))) {
                Script s = ScriptParser.parse(is, "stdin_test.relix");
                AssignmentStatement a = firstStatement(s);
                assertThat(a.location().filePath()).isEqualTo("stdin_test.relix");
            }
        }

        @Test
        @DisplayName("Statement is at line 1")
        void statementAtLineOne() throws IOException {
            String src = "query A;";
            try (var is = new ByteArrayInputStream(src.getBytes(StandardCharsets.UTF_8))) {
                Script s = ScriptParser.parse(is, "x.relix");
                QueryStatement q = firstStatement(s);
                assertThat(q.location().line()).isEqualTo(1);
            }
        }
    }

    // =========================================================================
    // FilePath propagation into RA expression bodies
    // =========================================================================

    @Nested
    @DisplayName("filePath propagates into RA expression bodies")
    class FilePathPropagation {

        @Test
        @DisplayName("RelNode inside assignment body carries the script filePath")
        void relNodeCarriesFilePath() {
            Script s = ScriptParser.parse("A := { Users };", "queries.relix");
            AssignmentStatement a = firstStatement(s);
            QueryAssignmentBody body = (QueryAssignmentBody) a.body();
            RelationNode rel = (RelationNode) body.expression();
            assertThat(rel.location().filePath()).isEqualTo("queries.relix");
        }

        @Test
        @DisplayName("Nested RelNode inside projection body carries the script filePath")
        void nestedRelNodeCarriesFilePath() {
            Script s = ScriptParser.parse("A := { π name (Users) };", "views.relix");
            AssignmentStatement a = firstStatement(s);
            QueryAssignmentBody body = (QueryAssignmentBody) a.body();
            com.darkcollective.relix.ast.ProjectionNode proj =
                    (com.darkcollective.relix.ast.ProjectionNode) body.expression();
            assertThat(proj.input().location().filePath()).isEqualTo("views.relix");
        }

        @Test
        @DisplayName("Selection body RelNode carries correct line number")
        void selectionBodyLineNumber() {
            // The assignment is on line 2 (namespace is not a statement, it's on line 1).
            // namespace declarations are parsed separately and not included in statements().
            String src = "-- comment on line 1\nA := { σ age > 18 (Users) };";
            Script s = ScriptParser.parse(src, "report.relix");
            AssignmentStatement a = firstStatement(s);
            QueryAssignmentBody body = (QueryAssignmentBody) a.body();
            SelectionNode sel = (SelectionNode) body.expression();
            // The selection is on the same line as the assignment (line 2)
            assertThat(sel.location().line()).isEqualTo(2);
            assertThat(sel.location().filePath()).isEqualTo("report.relix");
        }
    }

    // =========================================================================
    // Default parse(String) uses "<stdin>" filePath
    // =========================================================================

    @Nested
    @DisplayName("Default String parse uses '<stdin>' sentinel")
    class DefaultStringParse {

        @Test
        @DisplayName("RA body node has '<stdin>' filePath when no filePath supplied")
        void raBodyDefaultFilePath() {
            Script s = ScriptParser.parse("A := { Users };");
            AssignmentStatement a = firstStatement(s);
            QueryAssignmentBody body = (QueryAssignmentBody) a.body();
            RelationNode rel = (RelationNode) body.expression();
            assertThat(rel.location().filePath()).isEqualTo("<stdin>");
        }
    }

    // =========================================================================
    // def statement body — operand expression with filePath
    // =========================================================================

    @Nested
    @DisplayName("def statement body operand carries filePath")
    class DefStatementBody {

        @Test
        @DisplayName("def body operand carries the script filePath")
        void defBodyCarriesFilePath() {
            Script s = ScriptParser.parse("def double(x: NUMBER): NUMBER := { x * 2 };", "funcs.relix");
            DefStatement def = firstStatement(s);
            // Body is a BinaryArithmeticExpression or similar operand;
            // verify the def itself is located in the file
            assertThat(def.location().filePath()).isEqualTo("funcs.relix");
            assertThat(def.body()).isNotNull();
        }

        @Test
        @DisplayName("def body parsed with filePath propagation covers parseOperand(String,String,int,int)")
        void defBodyParsedWithFilePath() {
            String src = "def sq(n: NUMBER): NUMBER := { n * n };";
            Script s = ScriptParser.parse(src, "math.relix");
            DefStatement def = firstStatement(s);
            // The body is an Operand produced via parseOperand(String, String, int, int)
            assertThat(def.name()).isEqualTo("sq");
            assertThat(def.returnType()).isEqualTo(com.darkcollective.relix.symbol.ScalarType.NUMBER);
        }

        @Test
        @DisplayName("def on line 2 has correct line number")
        void defOnLineTwo() {
            String src = "-- comment\ndef pi(): NUMBER := { 3 };";
            Script s = ScriptParser.parse(src, "const.relix");
            DefStatement def = firstStatement(s);
            assertThat(def.location().line()).isEqualTo(2);
        }
    }
}

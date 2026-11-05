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

import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptPrinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scripts that used to break the lexer or the parser, and now read as they were written. */
@DisplayName("Front-end robustness")
final class FrontEndRobustnessTest {

    @Nested
    @DisplayName("Braces inside strings and comments in a brace block")
    class BracesInBlocks {

        @ParameterizedTest
        @ValueSource(strings = {
                "q := { σ s = '}' (T) };",
                "q := { σ s = '{' (T) };",
                "q := { σ s = '}{' (T) };",
                "q := { σ s = 'it''s }' (T) };",
                "q := { σ s = 'it\\'s }' (T) };",
                "q := { σ s = \"}\" (T) };",
                "q := { σ s = '{}' (T) };",
                "q := { -- close }\n T };",
                "q := { -- open {\n T };",
                "q := { /* } */ T };",
                "q := { /* { */ T };",
                "q := { /* it's } */ T };",
                "query { σ s = '{' (T) };",
                "def f(x: STRING): STRING := { REPLACE(x, '}', '') };",
        })
        void bracesInStringsAndCommentsDoNotCountE(String source) {
            Script script = ScriptParser.parse(source);
            assertThat(script.statements()).hasSize(1);
        }

        @Test
        @DisplayName("a statement after the block still parses")
        void statementAfterBlock() {
            Script script = ScriptParser.parse("q := { σ s = '}' (T) }; r := { T };");
            assertThat(script.statements()).hasSize(2);
        }

        @Test
        @DisplayName("an unterminated block comment inside a block is still an error")
        void unterminatedComment() {
            assertThatThrownBy(() -> ScriptParser.parse("q := { /* oops T };"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("a block ending inside a line comment is unterminated")
        void lineCommentToEnd() {
            assertThatThrownBy(() -> ScriptParser.parse("q := { T -- }"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Unterminated brace block");
        }
    }

    @Nested
    @DisplayName("Byte-order mark")
    class ByteOrderMark {

        @Test
        @DisplayName("a script that starts with a BOM parses as without it")
        void bomIsSkipped() {
            String body = "q := { T };\nquery q;";
            assertThat(ScriptParser.parse("\uFEFF" + body)).isEqualTo(ScriptParser.parse(body));
        }

        @Test
        @DisplayName("a BOM that is not first is still an error")
        void bomMidScriptIsAnError() {
            assertThatThrownBy(() -> ScriptParser.parse("q := { T };\n\uFEFFquery q;"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("positions are counted from the first character after the BOM")
        void positionsSkipBom() {
            assertThatThrownBy(() -> ScriptParser.parse("\uFEFFq := ;"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("line 1, col 6");
        }
    }

    @Nested
    @DisplayName("Messages name what to type")
    class Spellings {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "foo bar baz;|Expected ':='",
                "source X from csv(\"a\");|Expected '{'",
                "source X from csv(\"f.csv\");|Expected '{'",
                "def f(x NUMBER): NUMBER := { x };|Expected ':'",
                "def f x: NUMBER;|Expected '('",
        })
        void expectedTokensAreSpelledAsWritten(String source, String message) {
            assertThatThrownBy(() -> ScriptParser.parse(source))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining(message)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("assign", "lbrace", "colon", "lparen"));
        }

        @Test
        @DisplayName("an expected name is asked for as a name")
        void expectedNameIsAName() {
            assertThatThrownBy(() -> ScriptParser.parse("def (x: NUMBER): NUMBER := { x };"))
                    .isInstanceOf(LangParseException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("identifier"));
        }
    }

    @Nested
    @DisplayName("A thread with too small a stack")
    class SmallStack {

        @Test
        @DisplayName("is refused with a parse error rather than a StackOverflowError")
        void stackOverflowIsAParseError() throws InterruptedException {
            String source = "q := { π " + "ABS(".repeat(200) + "1" + ")".repeat(200) + " -> z (T) };";
            Throwable[] raised = new Throwable[1];
            Thread thread = new Thread(null, () -> {
                try {
                    ScriptParser.parse(source);
                } catch (Throwable e) {
                    raised[0] = e;
                }
            }, "tiny-stack", 32 * 1024);
            thread.start();
            thread.join();
            assertThat(raised[0]).isInstanceOf(LangParseException.class)
                    .hasMessageContaining("nests too deeply");
        }
    }

    @Nested
    @DisplayName("Types and parameters")
    class TypesAndParameters {

        @Test
        @DisplayName("a type nested past 250 levels is a positioned error")
        void deepType() {
            String source = "source X from csv(\"f.csv\") { schema: { a: " + "[".repeat(300)
                    + "STRING" + "]".repeat(300) + " } };";
            assertThatThrownBy(() -> ScriptParser.parse(source))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("nests more than 250 levels deep");
        }

        @Test
        @DisplayName("a parameter named twice is refused, ignoring case")
        void duplicateParameter() {
            assertThatThrownBy(() -> ScriptParser.parse("def f(x: NUMBER, X: NUMBER): NUMBER := { x };"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("parameter 'X' more than once");
        }
    }

    @Nested
    @DisplayName("Quotes in inline tables")
    class TableQuotes {

        @ParameterizedTest
        @ValueSource(strings = {
                "Z := csv[\na, \"x]y\"\n];",
                "Z := csv[\na,b\n \"x]y\", 2\n];",
                "Z := [\n| a |\n| \"x\\\"]y\" |\n];",
        })
        @DisplayName("a quoted field may hold brackets, after the spaces that precede it")
        void quotedBrackets(String source) {
            assertThat(ScriptParser.parse(source).statements()).hasSize(1);
        }

        @Test
        @DisplayName("a quote that never closes ends the table unterminated")
        void unterminatedQuote() {
            assertThatThrownBy(() -> ScriptParser.parse("Z := [\n| a |\n| 5\""))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Unterminated bracket block");
            assertThatThrownBy(() -> ScriptParser.parse("Z := csv[\na\n\"5"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Unterminated bracket block");
        }

        @Test
        @DisplayName("a string that never closes inside a brace block is unterminated")
        void unterminatedStringInBraces() {
            assertThatThrownBy(() -> ScriptParser.parse("q := { σ s = 'abc (T) };"))
                    .isInstanceOf(LangParseException.class);
        }

        @Test
        @DisplayName("a csv cell built with a carriage return is quoted when printed")
        void builtCarriageReturnIsQuoted() {
            Script script = com.darkcollective.relix.lang.ast.ScriptBuilders.script(
                    com.darkcollective.relix.lang.ast.ScriptBuilders.assign("Z",
                            com.darkcollective.relix.lang.ast.ScriptBuilders.csvTable(
                                    java.util.List.of("a"), java.util.List.of(java.util.List.of("p\rq")))));
            assertThat(ScriptPrinter.print(script)).contains("\"p\rq\"");
        }

        @Test
        @DisplayName("a csv cell holding a line break is quoted when printed")
        void carriageReturnIsQuoted() {
            Script script = ScriptParser.parse("Z := csv[\na\n\"p\nq\"\n];");
            assertThat(ScriptPrinter.print(script)).contains("\"p\nq\"");
            assertThat(ScriptPrinter.print(ScriptParser.parse(ScriptPrinter.print(script))))
                    .isEqualTo(ScriptPrinter.print(script));
        }
    }
}

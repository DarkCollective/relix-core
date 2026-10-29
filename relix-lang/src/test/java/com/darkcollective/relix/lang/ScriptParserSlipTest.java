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

import com.darkcollective.relix.lang.ast.ParseWarning;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptPrinter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two slips the parser accepts with a warning (#239): a {@code ;} before a statement's
 * closing brace, and a view written without its braces. Each parses to the script it
 * would have been, written correctly.
 */
@DisplayName("ScriptParser — slips accepted with a warning")
final class ScriptParserSlipTest {

    private static final String SEMICOLON = "The ';' before '}' was ignored: a statement ends"
            + " with ';' after its closing brace, as in query { R };";

    /** Asserts {@code slip} parses to what {@code written} does, warning once at a place. */
    private static ParseWarning acceptedAs(String slip, String written) {
        Script parsed = ScriptParser.parse(slip);
        assertThat(ScriptPrinter.print(parsed)).isEqualTo(ScriptPrinter.print(ScriptParser.parse(written)));
        assertThat(ScriptParser.parse(written).warnings()).isEmpty();
        assertThat(parsed.warnings()).hasSize(1);
        return parsed.warnings().getFirst();
    }

    @Test
    @DisplayName("a ';' before a query's '}' is dropped, and placed in the warning")
    void semicolonInQueryBraces() {
        ParseWarning warning = acceptedAs("query { PROJECT id (R); };", "query { PROJECT id (R) };");
        assertThat(warning.message()).isEqualTo(SEMICOLON);
        assertThat(warning.location()).extracting("line", "column").containsExactly(1, 23);
    }

    @Test
    @DisplayName("a ';' on its own before a view's '}' on the next line is placed on its line")
    void semicolonInViewBraces() {
        ParseWarning warning = acceptedAs("V := {\n  PROJECT id (R);\n};", "V := { PROJECT id (R) };");
        assertThat(warning.location()).extracting("line", "column").containsExactly(2, 17);
    }

    @Test
    @DisplayName("a ';' before a def's '}' is dropped too")
    void semicolonInDefBraces() {
        assertThat(acceptedAs("def f(x: NUMBER): NUMBER := { x + 1; };",
                "def f(x: NUMBER): NUMBER := { x + 1 };").message()).isEqualTo(SEMICOLON);
    }

    @Test
    @DisplayName("a view without braces is read as if they were there, and told where they go")
    void viewWithoutBraces() {
        ParseWarning warning = acceptedAs("Reach := FIX Reach (Base, Base ⋈ Reach);",
                "Reach := { FIX Reach (Base, Base ⋈ Reach) };");
        assertThat(warning.message()).isEqualTo("A view's expression goes in braces, and was"
                + " read as if it were in them: write Reach := { FIX Reach (Base, Base ⋈ Reach) };");
        assertThat(warning.location()).extracting("line", "column").containsExactly(1, 10);
    }

    @Test
    @DisplayName("a view without braces runs to the ';' that ends it, past one in a string or a comment")
    void viewWithoutBracesEndsAtItsSemicolon() {
        acceptedAs("V := SELECT code = 'a;b''c' (P); -- a ; here\nquery V;",
                "V := { SELECT code = 'a;b''c' (P) }; -- a ; here\nquery V;");
        acceptedAs("V := SELECT code = \"a;\\\"\" (P) -- a ; here\n;",
                "V := { SELECT code = \"a;\\\"\" (P) -- a ; here\n};");
    }

    @Test
    @DisplayName("a view without braces keeps its brackets, braces, strings and comments whole")
    void viewWithoutBracesKeepsItsGroups() {
        acceptedAs("V := SELECT a IN {1, 2} (PROJECT [a, b] -> c, a (R));",
                "V := { SELECT a IN {1, 2} (PROJECT [a, b] -> c, a (R)) };");
        acceptedAs("V := PROJECT a - 1 -> b (R);", "V := { PROJECT a - 1 -> b (R) };");
        acceptedAs("V := SELECT a = 'it''s;' (R);", "V := { SELECT a = 'it''s;' (R) };");
        acceptedAs("V := SELECT a = \"x\\\\;\" (R);", "V := { SELECT a = \"x\\\\;\" (R) };");
        acceptedAs("V := csvdata;", "V := { csvdata };");
        // Running to the end: an unclosed string or group, a comment, or a ')' closing nothing.
        assertThatThrownBy(() -> ScriptParser.parse("V := SELECT a = 'x"))
                .isInstanceOf(LangParseException.class);
        assertThatThrownBy(() -> ScriptParser.parse("V := SELECT a = \"x"))
                .isInstanceOf(LangParseException.class);
        assertThatThrownBy(() -> ScriptParser.parse("V := PROJECT a (R -- x"))
                .isInstanceOf(LangParseException.class);
        assertThatThrownBy(() -> ScriptParser.parse("V := R);"))
                .isInstanceOf(LangParseException.class);
        assertThatThrownBy(() -> ScriptParser.parse("V := csv"))
                .isInstanceOf(LangParseException.class);
        assertThatThrownBy(() -> ScriptParser.parse("V := SELECT a = 'x'"))
                .isInstanceOf(LangParseException.class);
        assertThatThrownBy(() -> ScriptParser.parse("V := PROJECT a -"))
                .isInstanceOf(LangParseException.class);
        // A ';' inside a group is the group's, so the statement runs on to the one after it.
        assertThatThrownBy(() -> ScriptParser.parse("V := PROJECT a (R;);"))
                .isInstanceOf(LangParseException.class);
        assertThat(ScriptParser.parse("T := csv  [a\n1\n];").warnings()).isEmpty();
    }

    @Test
    @DisplayName("an inline table and a CSV table keep their brackets, and nothing after := is still refused")
    void othersUnchanged() {
        assertThat(ScriptParser.parse("T := [| a |\n| 1 |];").warnings()).isEmpty();
        assertThat(ScriptParser.parse("T := csv[a\n1\n];").warnings()).isEmpty();
        assertThatThrownBy(() -> ScriptParser.parse("V := ;"))
                .hasMessageContaining("Expected '{'");
    }
}

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

import com.darkcollective.relix.lang.ast.ScriptParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link LangParseException} — the {@code .relix} grammar's
 * specialisation of the core-level {@link ScriptParseException} (ADR-0025).
 */
@DisplayName("relix-lang — LangParseException")
final class LangParseExceptionTest {

    @Test
    @DisplayName("Is a ScriptParseException, so the core seam can declare the supertype")
    void isAScriptParseException() {
        assertThat(new LangParseException("boom", 1, 2))
                .isInstanceOf(ScriptParseException.class);
    }

    @Test
    @DisplayName("Message carries the position and line()/column() expose it")
    void messageAndPosition() {
        var e = new LangParseException("Expected ';'", 4, 17);
        assertThat(e).hasMessage("Expected ';' (line 4, col 17)");
        assertThat(e.line()).isEqualTo(4);
        assertThat(e.column()).isEqualTo(17);
        assertThat(e.getMessage()).endsWith(LangParseException.suffix(4, 17));
    }

    @Test
    @DisplayName("description() is the message without the position it ends with")
    void descriptionDropsPosition() {
        var e = new LangParseException("Expected ';'",
                new LangToken(LangTokenType.IDENTIFIER, "Orders", 2, 9));
        assertThat(e.description()).isEqualTo("Expected ';'; found 'Orders'");
    }

    @Test
    @DisplayName("A token's position is stated once, not once for the token and again for the error")
    void tokenPositionStatedOnce() {
        var e = new LangParseException("Expected ';'",
                new LangToken(LangTokenType.IDENTIFIER, "Orders", 2, 9));
        assertThat(e).hasMessage("Expected ';'; found 'Orders' (line 2, col 9)");
        assertThat(e.line()).isEqualTo(2);
        assertThat(e.column()).isEqualTo(9);
    }

    @Test
    @DisplayName("A syntax error inside an expression is placed at the offending token, once")
    void expressionErrorPlacedAtToken() {
        assertThatThrownBy(() -> ScriptParser.parse("X := [| a |\n| 1 |];\nquery { π a.+1 (X) };\n"))
                .isInstanceOfSatisfying(LangParseException.class, e -> {
                    assertThat(e.line()).isEqualTo(3);
                    assertThat(e.column())
                            .as("the '+' after 'a.', not the statement's opening brace")
                            .isEqualTo(13);
                    assertThat(e).hasMessage("Syntax error in RA expression: "
                            + "Expected identifier after '.'; found '+' (line 3, col 13)");
                });
    }

    @Test
    @DisplayName("A parser syntax error is catchable as a ScriptParseException")
    void parserFailureIsCatchableAsSupertype() {
        assertThatThrownBy(() -> ScriptParser.parse("!!! not valid relix !!!"))
                .isInstanceOf(ScriptParseException.class);
    }
}

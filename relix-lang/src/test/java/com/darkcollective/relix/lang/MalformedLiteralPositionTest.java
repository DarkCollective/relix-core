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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Malformed literals the grammar accepts in shape but not in value — a fractional row
 * count, a number too large for a {@code long}, a blank name — are refused as a
 * {@link LangParseException} at the offending token.
 *
 * <p>Each of these once escaped as a raw {@code NumberFormatException} or as the
 * {@code IllegalArgumentException} of the AST record the parser was building, with no
 * position: the value was checked by the constructor, which knows nothing of where the
 * text was, rather than at the token, which does.
 */
@DisplayName("relix-lang — malformed literals are reported at their token")
final class MalformedLiteralPositionTest {

    private static final String DATA = "R := [\n| t | a | b |\n|---|---|---|\n| 1 | 1 | 2 |\n];\n";

    /**
     * @param statement the malformed statement, parsed after a data block so that it is
     *                  the script's sixth line
     * @param offending the text of the token the error must be placed at
     * @param message   a fragment the message must carry
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '~', textBlock = """
            query { λ 1.5 (R) };                                         | 1.5                     | whole-number row count
            query { λ 99999999999999999999999 (R) };                     | 99999999999999999999999 | whole-number row count
            query { λ 1, 2.5 (R) };                                      | 2.5                     | whole-number row count
            query { TOP 1.5 a (R) };                                     | 1.5                     | whole-number row count
            query { TOP 1, 2.5 a (R) };                                  | 2.5                     | whole-number row count
            query { DOWNSAMPLE t BY '5m' USING AVG FOR 0 ROWS (R) };     | 0                       | at least 1
            query { DOWNSAMPLE t BY '' USING AVG (R) };                  | ''                      | interval must not be blank
            query { PATH a, b HOPS 2 AS ` ` (R) };                       | ` `                     | Empty delimited identifier
            query { π ` ` (R) };                                         | ` `                     | Empty delimited identifier
            relate "" R.a -> R.b;                                        | ""                      | relationship name must not be blank
            relate "x" / " " R.a -> R.b;                                 | " "                     | inverse relationship name must not be blank
            import "";                                                   | ""                      | import source path must not be blank
            import X from " ";                                           | " "                     | import source path must not be blank
            import { X } from "";                                        | ""                      | import source path must not be blank
            source P from http { url: "https://example.com", paginate: { limit: query("limit") [default: 2.5] }, schema: { id: NUMBER } }; | 2.5 | pagination default value
            """)
    @DisplayName("A malformed literal is a positioned parse error, not a raw exception")
    void refusedAtItsToken(String statement, String offending, String message) {
        int column = statement.indexOf(offending) + 1;
        assertThatThrownBy(() -> ScriptParser.parse(DATA + statement))
                .isInstanceOfSatisfying(LangParseException.class, e -> {
                    assertThat(e.getMessage()).contains(message);
                    assertThat(e.line()).as("line").isEqualTo(6);
                    assertThat(e.column()).as("column of '%s'", offending).isEqualTo(column);
                });
    }
}

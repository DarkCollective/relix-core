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
package com.darkcollective.relix.semantic.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;

/** A LIKE pattern whose '_' is probably meant as an underscore is warned about (#238). */
@DisplayName("LIKE patterns whose '_' looks meant as an underscore")
final class LikePatternChecksTest {

    private static final String TABLE = """
            Promotions := [
            | code |
            |------|
            | A_B  |
            | CD   |
            ];
            """;

    private static SemanticResult query(String expression) {
        return analyze(TABLE + "query { " + expression + " };");
    }

    @Test
    @DisplayName("'%_%' is warned of, with the pattern rewritten to escape the underscore")
    void containsUnderscore() {
        assertThat(query("SELECT code LIKE '%_%' (Promotions)"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("Selection σ: '_' in LIKE matches any one character; to match"
                        + " an underscore, write LIKE '%!_%' ESCAPE '!'");
    }

    @Test
    @DisplayName("a pattern already escaping its underscore with '!' is told the ESCAPE it left out")
    void escapeLeftOut() {
        assertThat(query("SELECT code LIKE '%!_%' (Promotions)"))
                .hasNoErrors()
                .hasErrorContaining("write LIKE '%!_%' ESCAPE '!'");
    }

    @Test
    @DisplayName("the escape chosen is one the pattern does not use")
    void escapeNotInThePattern() {
        assertThat(query("SELECT code LIKE '%!%_' (Promotions)"))
                .hasNoErrors()
                .hasErrorContaining("write LIKE '%!%#_' ESCAPE '#'");
    }

    @Test
    @DisplayName("underscores after different characters, or at the start, are each escaped")
    void mixedPrefixes() {
        assertThat(query("SELECT code LIKE '%!_%_' (Promotions)"))
                .hasErrorContaining("write LIKE '%!#_%#_' ESCAPE '#'");
        assertThat(query("SELECT code LIKE '_%' (Promotions)"))
                .hasErrorContaining("write LIKE '!_%' ESCAPE '!'");
        assertThat(query("SELECT code LIKE '%!_%!_%' (Promotions)"))
                .hasErrorContaining("write LIKE '%!_%!_%' ESCAPE '!'");
    }

    @Test
    @DisplayName("a LIKE in a projected condition is checked too")
    void projectedCondition() {
        assertThat(query("PROJECT code, (code LIKE 'A_%') -> a (Promotions)"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("Projection π: '_' in LIKE matches any one character");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"'A_B'", "'___'", "'AB-_'", "'_AB'", "'%a_b%'", "'%!_%' ESCAPE '!'",
            "'%ab%'"})
    @DisplayName("a '_' between other characters or at an end, or a pattern with an ESCAPE, is left alone")
    void leftAlone(String pattern) {
        assertThat(query("SELECT code LIKE " + pattern + " (Promotions)"))
                .hasNoErrors()
                .hasDiagnosticCount(0);
    }
}

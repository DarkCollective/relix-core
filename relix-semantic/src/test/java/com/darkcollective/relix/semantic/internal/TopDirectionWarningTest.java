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

/** A TOP whose first sort key has no direction, and so keeps the lowest rows, says so (#210). */
@DisplayName("TOP sort key with no direction")
final class TopDirectionWarningTest {

    private static final String TABLES = """
            T := [
            | g | p | t    |
            | 1 | 1 | low  |
            | 1 | 9 | high |
            ];
            """;

    private static SemanticResult query(String expression) {
        return analyze(TABLES + "query { " + expression + " };");
    }

    @Test
    @DisplayName("the issue's TOP warns that it keeps the lowest")
    void issueScript() {
        assertThat(query("TOP 1 p PER g (T)"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("Top-k TOP: p has no direction, so TOP keeps the rows with the lowest p: "
                        + "write p DESC for the highest, or p ASC to keep the lowest without this warning");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "TOP 1 WITH TIES p PER g (T)",
            "TOP 1 p, t DESC (T)",
            "TOP 0, 1 p NULLS FIRST PER g (T)",
    })
    @DisplayName("WITH TIES, a global TOP, an offset and NULLS FIRST warn alike")
    void otherForms(String expression) {
        assertThat(query(expression))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("p has no direction");
    }

    @Test
    @DisplayName("an expression key is named as written")
    void expressionKey() {
        assertThat(query("TOP 1 p * 2 PER g (T)"))
                .hasDiagnosticCount(1)
                .hasErrorContaining("p * 2 has no direction");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "TOP 1 p DESC PER g (T)",
            "TOP 1 p ASC PER g (T)",
            "TOP 1 p DESC, t PER g (T)",
            "λ 1 WITH TIES (τ p (T))",
            "τ p (T)",
    })
    @DisplayName("a written direction, a later tie-breaking key, and a plain sort stay quiet")
    void quiet(String expression) {
        assertThat(query(expression)).hasDiagnosticCount(0);
    }

    @Test
    @DisplayName("a key that does not resolve is reported once, as the missing column")
    void unresolvedKey() {
        assertThat(query("TOP 1 nope PER g (T)"))
                .hasErrorContaining("nope")
                .hasNoErrorContaining("has no direction");
    }
}

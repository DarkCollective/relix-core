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

/** A set operation whose sides have the same column names in another order says so (#209). */
@DisplayName("Set operation sides with permuted column names")
final class SetOperationColumnOrderTest {

    private static final String TABLES = """
            A := [
            | status  | country |
            | shipped | USA     |
            ];
            B := [
            | country | status  |
            | UK      | pending |
            ];
            C := [
            | city   | region |
            | Leeds  | North  |
            ];
            """;

    private static SemanticResult query(String expression) {
        return analyze(TABLES + "query { " + expression + " };");
    }

    @Test
    @DisplayName("the issue's UNION warns, naming where each column lands and pointing to OUNION")
    void issueScript() {
        assertThat(query("A UNION B"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("Set operation ∪ matches columns by position, not name: the right side's "
                        + "'country' lands in 'status', its 'status' lands in 'country'. Use OUNION (⊔) to "
                        + "match them by name, or project the right side's columns in the left side's "
                        + "order: PROJECT status, country (B)");
    }

    @Test
    @DisplayName("the hand-built CUBE the issue came from warns at the subtotal in the wrong order")
    void handBuiltCube() {
        assertThat(analyze("""
                Sales := [
                | status  | country | revenue |
                | shipped | USA     | 10      |
                ];
                StatusSub  := { PROJECT status, NULL -> country, revenue (Sales) };
                CountrySub := { PROJECT country, NULL -> status, revenue (Sales) };
                query { Sales UNION StatusSub UNION CountrySub };
                """))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("PROJECT status, country, revenue (CountrySub)");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"A UALL B", "A INTERSECT B", "A EXCEPT B", "A ∆ B"})
    @DisplayName("the other set operations warn too, suggesting only the projection")
    void otherOperators(String expression) {
        assertThat(query(expression))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("matches columns by position, not name")
                .hasErrorContaining("Project the right side's columns in the left side's order: "
                        + "PROJECT status, country (B)")
                .hasNoErrorContaining("OUNION");
    }

    @Test
    @DisplayName("a right side with no single name is described rather than named")
    void unnamedRightSide() {
        assertThat(query("A ∪ (B ∪ B)"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("PROJECT status, country (…) around the right side");
    }

    @Test
    @DisplayName("names that differ only in case still count as the same names")
    void caseInsensitive() {
        assertThat(analyze(TABLES + """
                D := [
                | COUNTRY | Status |
                | UK      | pending |
                ];
                query { A ∪ D };
                """))
                .hasDiagnosticCount(1)
                .hasErrorContaining("the right side's 'COUNTRY' lands in 'status'");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "A ∪ A",
            "A ∪ PROJECT status, country (B)",
            "A ∪ C",
            "A ⊔ B",
    })
    @DisplayName("sides in the same order, sides with other names, and the outer union stay quiet")
    void quiet(String expression) {
        assertThat(query(expression)).hasDiagnosticCount(0);
    }
}

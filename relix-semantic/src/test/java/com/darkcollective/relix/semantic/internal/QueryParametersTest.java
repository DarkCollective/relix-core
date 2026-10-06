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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QueryParameters — the bound parameters a script uses, and their types")
final class QueryParametersTest {

    private static final String ORDERS = """
            Orders := [
            | id | customer | total |
            |----|----------|-------|
            | 1  | ada      | 10    |
            ];
            """;

    private static RelNode firstQuery(SemanticModel model) {
        return ((ExpressionQueryTarget) model.rootQueries().getFirst().target()).expression();
    }

    @Nested
    @DisplayName("typing")
    final class Typing {

        @Test
        @DisplayName("a parameter takes the type of what it is compared with, on either side")
        void fromAComparison() {
            SemanticModel model = model(ORDERS
                    + "query { σ id = $id ∧ $who = customer ∧ $label ≠ \"x\" (Orders) };");
            assertThat(model.parameters()).containsExactly(
                    Map.entry("id", ScalarType.NUMBER),
                    Map.entry("who", ScalarType.STRING),
                    Map.entry("label", ScalarType.STRING));
        }

        @Test
        @DisplayName("a LIKE pattern is a string, and a parameter compared with nothing typed is ANY")
        void patternAndUntyped() {
            SemanticModel model = model(ORDERS + """
                    query { σ customer LIKE $pattern ∨ ¬(total > $a + 1) ∨ customer IS NULL
                            ∨ total ∈ {1, 2} (Orders) };
                    query { π id, $tag → tag (Orders) };
                    """);
            assertThat(model.parameters()).containsExactly(
                    Map.entry("pattern", ScalarType.STRING),
                    Map.entry("a", ScalarType.ANY),
                    Map.entry("tag", ScalarType.ANY));
        }

        @Test
        @DisplayName("parameters in views and in both kinds of def are found, a query's spelling first")
        void inViewsAndDefs() {
            SemanticModel model = model(ORDERS + """
                    Big := { σ total > $Floor (Orders) };
                    def taxed(x: NUMBER) : NUMBER := { x * $rate };
                    def byCustomer(): RELATION := { σ customer = $who (Orders) };
                    query { σ total > $FLOOR (Big) };
                    """);
            assertThat(model.parameters()).containsOnlyKeys("FLOOR", "rate", "who");
            assertThat(model.parameters()).containsEntry("FLOOR", ScalarType.NUMBER);
        }

        @Test
        @DisplayName("uses that disagree are an error, reported once")
        void conflictingUses() {
            assertThat(analyze(ORDERS
                    + "query { σ id = $key ∨ customer = $key ∨ customer = $KEY (Orders) };"))
                    .hasErrorContaining("Parameter $key is compared with a NUMBER in one place"
                            + " and a STRING in another; one value cannot be both")
                    .hasDiagnosticCount(1);
        }

        @Test
        @DisplayName("uses that agree are not")
        void agreeingUses() {
            assertThat(analyze(ORDERS + "query { σ id = $key ∨ total = $key (Orders) };"))
                    .hasNoErrors();
        }
    }

    @Nested
    @DisplayName("reachedBy — the parameters a query needs values for")
    final class Reached {

        @Test
        @DisplayName("follows views, relation functions and scalar defs, and nothing else")
        void followsWhatTheQueryNames() {
            SemanticModel model = model(ORDERS + """
                    Big := { σ total > $floor (Orders) };
                    Other := { σ id = $unrelated (Orders) };
                    def taxed(x: NUMBER) : NUMBER := { Abs(x) * $rate };
                    def byCustomer(): RELATION := { σ customer = $who (Orders) };
                    query { π id, taxed(total) → t, taxed(id) → u (Big ⋈ byCustomer() ⋈ Big) };
                    """);
            assertThat(QueryParameters.reachedBy(firstQuery(model), model.symbolTable()))
                    .containsExactlyInAnyOrder("floor", "rate", "who");
        }

        @Test
        @DisplayName("a query with no parameter reaches none")
        void none() {
            SemanticModel model = model(ORDERS + "query { Orders };");
            assertThat(QueryParameters.reachedBy(firstQuery(model), model.symbolTable())).isEmpty();
            assertThat(QueryParameters.reachedBy(AstBuilders.rel("Missing"), model.symbolTable()))
                    .isEmpty();
        }
    }
}

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

/** A join condition that never relates its two inputs is warned about (#121). */
@DisplayName("Join conditions that never relate their inputs")
final class JoinConditionChecksTest {

    private static final String TABLES = """
            Customers := [
            | customer_id | name  |
            |-------------|-------|
            | 1           | Asha  |
            | 5           | Emeka |
            ];
            Orders := [
            | order_id | customer_id |
            |----------|-------------|
            | 101      | 1           |
            ];
            """;

    private static SemanticResult query(String expression) {
        return analyze(TABLES + "query { " + expression + " };");
    }

    @Test
    @DisplayName("ANTI comparing the right column with itself names the typo and the fix")
    void antiSelfComparison() {
        assertThat(query("Customers ANTI Orders.customer_id = Orders.customer_id Orders"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("Orders.customer_id = Orders.customer_id compares a column with itself")
                .hasErrorContaining("did you mean Customers.customer_id = Orders.customer_id?");
    }

    @Test
    @DisplayName("a bare name both inputs carry, compared with itself, reads the left twice")
    void bareSharedNameSelfComparison() {
        assertThat(query("Customers ⨝ customer_id = customer_id Orders"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("customer_id = customer_id compares a column with itself")
                .hasErrorContaining("did you mean Customers.customer_id = Orders.customer_id?");
    }

    @Test
    @DisplayName("LJOIN over one side's columns only says which side it never refers to")
    void leftJoinNeverRefersToLeft() {
        assertThat(query("Customers LJOIN Orders.order_id > 100 Orders"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("the condition never refers to Customers, so it compares no row "
                        + "of Customers with any row of Orders");
    }

    @Test
    @DisplayName("a condition over the left alone suggests the matching right column")
    void neverRefersToRight() {
        assertThat(query("Customers ⨝ Customers.customer_id = 1 Orders"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("never refers to Orders")
                .hasErrorContaining("did you mean Customers.customer_id = Orders.customer_id?");
    }

    @Test
    @DisplayName("conjuncts that each read one side only relate nothing")
    void conjunctsOneSideEach() {
        assertThat(query("Customers ⨝ Customers.name = \"Asha\" ∧ Orders.order_id = 101 Orders"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("no part of the condition relates Customers to Orders");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "Customers ANTI Customers.customer_id = Orders.customer_id Orders",
            "Customers ⋉ Customers.customer_id = Orders.customer_id Orders",
            "Customers LJOIN Customers.customer_id = Orders.customer_id ∧ Orders.order_id > 100 Orders",
            "Customers ⨝ Customers.customer_id = Orders.customer_id ∨ Customers.name = \"Asha\" Orders",
            "Customers ⨝ name = \"Asha\" ∧ Len(name) < order_id Orders",
            "Customers ⨝ 1 = 1 Orders",
    })
    @DisplayName("a condition that relates the inputs, or reads no column, is not warned about")
    void relatedConditionsAreClean(String expression) {
        assertThat(query(expression)).isFullyValid();
    }

    @Test
    @DisplayName("a self-join through a rename relates its two copies")
    void renamedSelfJoin() {
        assertThat(query("Customers ⨝ Customers.customer_id = C.customer_id (ρ C (Customers))"))
                .isFullyValid();
    }

    @Test
    @DisplayName("an input with no single name is described by its position")
    void unnamedInput() {
        assertThat(query("(Customers ⨝ Customers.customer_id = Orders.customer_id Orders) "
                + "⨝ Orders.order_id = 1 (ρ P (Orders))"))
                .hasNoErrors()
                .hasErrorContaining("never refers to P, so it compares no row of the left input "
                        + "with any row of P");
    }

    @Test
    @DisplayName("a bare name compared with itself between unnamed inputs has no suggestion")
    void unnamedSelfComparison() {
        assertThat(query("(Customers ⨝ Customers.customer_id = Orders.customer_id Orders) "
                + "⨝ order_id = order_id (Orders × Customers)"))
                .hasNoErrors()
                .hasErrorContaining("order_id = order_id compares a column with itself, so it says "
                        + "nothing about how a row of the left input meets a row of the right input")
                .hasNoErrorContaining("did you mean");
    }

    @Test
    @DisplayName("a bare self-comparison against an unnamed right input has no suggestion")
    void unnamedRightSelfComparison() {
        assertThat(query("Orders ⨝ order_id = order_id (Orders × Customers)"))
                .hasErrorContaining("order_id = order_id compares a column with itself")
                .hasNoErrorContaining("did you mean");
    }

    @Test
    @DisplayName("a side the condition never reads, with no name, gets no suggestion")
    void unnamedMissingSide() {
        assertThat(query("(Customers × Orders) ⨝ P.order_id = 1 (ρ P (Orders))"))
                .hasNoErrors()
                .hasErrorContaining("never refers to the left input")
                .hasNoErrorContaining("did you mean");
    }

    @Test
    @DisplayName("an input without column provenance is not judged")
    void noProvenance() {
        assertThat(query("Customers ⨝ Customers.customer_id = 1 (γ COUNT(*) → n (Orders))"))
                .hasNoErrorContaining("never refers");
    }

    @Test
    @DisplayName("a condition that already has an error is not warned about as well")
    void errorsComeFirst() {
        assertThat(query("Customers ANTI Orders.nosuch = Orders.nosuch Orders"))
                .hasErrors()
                .hasNoErrorContaining("compares a column with itself");
    }
}

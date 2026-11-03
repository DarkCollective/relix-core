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
package com.darkcollective.relix.embed.equivalence;

import com.darkcollective.relix.optimizer.OptimizationCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertEquivalent;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertNotFiredButEqual;

/**
 * SUBQ-001 to SUBQ-006: each subquery written as the join it stands for answers as the
 * per-row lateral the planner otherwise runs, over rows with NULLs — a customer with no
 * orders, an order with no customer, and NULLs on both sides of the correlation (#261).
 *
 * <p>Harness: {@link OptimizerEquivalence}; the unoptimized run is the nested loop.
 */
@DisplayName("Subquery decorrelation — execution equivalence (optimized == unoptimized)")
final class SubqueryDecorrelationEquivalenceTest {

    private static final String DATA = """
            Customers := [| id   | name  |
                          | 1    | Alice |
                          | 2    | Bob   |
                          | 3    | Cara  |
                          | NULL | Dan   |];
            Orders := [| oid | cid  | amount |
                       | 10  | 1    | 120    |
                       | 11  | 1    | 80     |
                       | 12  | 2    | 45     |
                       | 13  | NULL | 60     |
                       | 14  | 4    | NULL   |];
            """;

    private static String q(String query) {
        return DATA + "query { " + query + " };\n";
    }

    @Nested
    @DisplayName("fires — and the answer is unchanged")
    class Fires {

        @Test
        @DisplayName("SUBQ-001: EXISTS, alone and beside another conjunct")
        void exists() {
            assertEquivalent(q("σ EXISTS (σ cid = Customers.id (Orders)) (Customers)"), OptimizationCode.SUBQ_001);
            assertEquivalent(q("σ name ≠ 'Bob' ∧ EXISTS (σ cid = Customers.id ∧ amount > 50 (Orders)) (Customers)"),
                    OptimizationCode.SUBQ_001);
            assertEquivalent(q("σ EXISTS (σ amount > 100 (Orders)) (Customers)"), OptimizationCode.SUBQ_001);
        }

        @Test
        @DisplayName("SUBQ-002: NOT EXISTS keeps the rows with no match, NULL keys among them")
        void notExists() {
            assertEquivalent(q("σ NOT EXISTS (σ cid = Customers.id (Orders)) (Customers)"), OptimizationCode.SUBQ_002);
        }

        @Test
        @DisplayName("SUBQ-003: ANY and IN, uncorrelated and correlated")
        void any() {
            assertEquivalent(q("σ id IN (π cid (Orders)) (Customers)"), OptimizationCode.SUBQ_003);
            assertEquivalent(q("σ id < ANY (π cid (Orders)) (Customers)"), OptimizationCode.SUBQ_003);
            assertEquivalent(q("σ amount > ANY (π amount (σ cid = Orders.cid ∧ oid ≠ Orders.oid (ρ O (Orders))))"
                    + " (Orders)"), OptimizationCode.SUBQ_003);
            assertEquivalent(q("σ NOT (id < ALL (π cid (Orders))) (Customers)"), OptimizationCode.SUBQ_003);
        }

        @Test
        @DisplayName("SUBQ-004: ALL and NOT IN, with the NULL trap held")
        void all() {
            assertEquivalent(q("σ id NOT IN (π cid (Orders)) (Customers)"), OptimizationCode.SUBQ_004);
            assertEquivalent(q("σ id NOT IN (π cid (σ cid IS NOT NULL (Orders))) (Customers)"),
                    OptimizationCode.SUBQ_004);
            assertEquivalent(q("σ id >= ALL (π cid (Orders)) (Customers)"), OptimizationCode.SUBQ_004);
            assertEquivalent(q("σ id > ALL (π cid (σ cid > 100 (Orders))) (Customers)"), OptimizationCode.SUBQ_004);
        }

        @Test
        @DisplayName("SUBQ-005: a correlated COUNT is 0 for a row with no group; MAX is NULL")
        void groupedAggregate() {
            assertEquivalent(q("π name, (γ COUNT(*) → n (σ cid = Customers.id (Orders))) → n (Customers)"),
                    OptimizationCode.SUBQ_005);
            assertEquivalent(q("π name, (γ MAX(amount) → m (σ cid = Customers.id ∧ amount > 50 (Orders))) → m"
                    + " (Customers)"), OptimizationCode.SUBQ_005);
            assertEquivalent(q("π name, (γ SUM(amount) → s (σ Customers.id = cid (Orders))) → s,"
                    + " (γ COUNT(amount) → c (σ cid = Customers.id (Orders))) → c (Customers)"),
                    OptimizationCode.SUBQ_005);
        }

        @Test
        @DisplayName("SUBQ-006: a correlated LATERAL, inner and outer")
        void lateral() {
            assertEquivalent(q("Customers LATERAL (σ cid = Customers.id (Orders))"), OptimizationCode.SUBQ_006);
            assertEquivalent(q("Customers OUTER LATERAL (σ cid = Customers.id ∧ amount > 50 (Orders))"),
                    OptimizationCode.SUBQ_006);
        }
    }

    @Nested
    @DisplayName("does not fire where it would not be exact — and the answer is the lateral's")
    class Declines {

        @Test
        @DisplayName("under OR, in a projection, or reading a NOT the rules do not dualise")
        void position() {
            assertNotFiredButEqual(q("σ EXISTS (σ cid = Customers.id (Orders)) ∨ name = 'Dan' (Customers)"),
                    OptimizationCode.SUBQ_001);
            assertNotFiredButEqual(q("π name, EXISTS (σ cid = Customers.id (Orders)) → has (Customers)"),
                    OptimizationCode.SUBQ_001);
        }

        @Test
        @DisplayName("a value that is not a correlated aggregate, an unnamed one, or an aggregate with no rule")
        void notGroupable() {
            assertNotFiredButEqual(q("π name, (π amount (λ 1 (τ amount DESC (σ cid = Customers.id (Orders)))))"
                    + " → top (Customers)"), OptimizationCode.SUBQ_005);
            assertNotFiredButEqual(q("π name, (γ COUNT(*) → n (σ cid = Customers.id (Orders))) (Customers)"),
                    OptimizationCode.SUBQ_005);
            assertNotFiredButEqual(q("π name, (γ COLLECT(amount) → a (σ cid = Customers.id (Orders))) → a"
                    + " (Customers)"), OptimizationCode.SUBQ_005);
            assertNotFiredButEqual(q("π name, (γ COUNT(*) → n (σ cid > Customers.id (Orders))) → n (Customers)"),
                    OptimizationCode.SUBQ_005);
            assertNotFiredButEqual(q("π name, (γ COUNT(*) → n (Orders)) → n (Customers)"),
                    OptimizationCode.SUBQ_005);
        }

        @Test
        @DisplayName("a relation the outer row also names, or one with two names")
        void ambiguousNames() {
            assertNotFiredButEqual(q("σ EXISTS (σ cid = Customers.id (Orders ⨝ Orders.cid = C.id ρ C (Customers)))"
                    + " (Customers)"), OptimizationCode.SUBQ_001);
            assertNotFiredButEqual(q("σ amount > ANY (π amount (σ cid = Orders.cid (Orders))) (Orders)"),
                    OptimizationCode.SUBQ_003);
        }

        @Test
        @DisplayName("a LATERAL whose correlation is outside a top-level selection")
        void lateralShape() {
            assertNotFiredButEqual(q("Customers LATERAL (λ 1 (τ amount DESC (σ cid = Customers.id (Orders))))"),
                    OptimizationCode.SUBQ_006);
        }
    }
}

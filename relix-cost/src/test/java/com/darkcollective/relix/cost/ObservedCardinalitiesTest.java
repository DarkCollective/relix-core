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
package com.darkcollective.relix.cost;

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.Expr;
import com.darkcollective.relix.ast.RelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ObservedCardinalities — what a run measured, keyed by the expression")
final class ObservedCardinalitiesTest {

    private static RelNode openOrders() {
        return AstBuilders.select(
                Expr.eq(Expr.attr("status"), Expr.str("OPEN")), AstBuilders.rel("Orders"));
    }

    @Nested
    @DisplayName("recording and reading back")
    final class Recording {

        @Test
        @DisplayName("an expression that ran answers with the count it produced")
        void recordsAndReads() {
            ObservedCardinalities observed = new ObservedCardinalities();
            observed.record(openOrders(), 42);

            assertThat(observed.forExpression(openOrders())).hasValue(42);
        }

        @Test
        @DisplayName("an expression that never ran answers empty")
        void unknownIsEmpty() {
            ObservedCardinalities observed = new ObservedCardinalities();
            observed.record(openOrders(), 42);

            assertThat(observed.forExpression(AstBuilders.rel("Orders"))).isEmpty();
            assertThat(new ObservedCardinalities().forExpression(openOrders())).isEmpty();
        }

        /**
         * The location-free digest is the point of using it: the same query written in a
         * different script, or at a different offset in the same one, is the same key.
         */
        @Test
        @DisplayName("the same expression written elsewhere hits the same entry")
        void locationDoesNotMatter() {
            ObservedCardinalities observed = new ObservedCardinalities();
            observed.record(openOrders(), 42);

            assertThat(observed.forExpression(openOrders())).hasValue(42);
        }

        /**
         * The limit worth stating plainly: this memoises what ran, it does not learn a
         * predicate's selectivity from a measured one.
         */
        @Test
        @DisplayName("a different predicate is a different entry — it memoises, it does not learn")
        void doesNotGeneralise() {
            ObservedCardinalities observed = new ObservedCardinalities();
            observed.record(openOrders(), 42);

            RelNode closed = AstBuilders.select(
                    Expr.eq(Expr.attr("status"), Expr.str("CLOSED")), AstBuilders.rel("Orders"));
            assertThat(observed.forExpression(closed)).isEmpty();
        }

        @Test
        @DisplayName("a later measurement replaces an earlier one")
        void latestWins() {
            ObservedCardinalities observed = new ObservedCardinalities();
            observed.record(openOrders(), 42);
            observed.record(openOrders(), 7);

            assertThat(observed.forExpression(openOrders())).hasValue(7);
        }

        @Test
        @DisplayName("a negative count is refused — nothing produces one")
        void refusesNegative() {
            assertThatThrownBy(() -> new ObservedCardinalities().record(openOrders(), -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the bound, which is the lever a parameterised workload needs")
    final class Bounded {

        private static RelNode byId(int id) {
            return AstBuilders.select(
                    Expr.eq(Expr.attr("id"), Expr.num(id)), AstBuilders.rel("Orders"));
        }

        /**
         * `σ id = 12345 (Orders)` mints an entry per literal — the classic plan-cache
         * failure. Normalising the literal away would discard exactly the precision that
         * makes a measured count worth more than an estimate, so the bound is the lever.
         */
        @Test
        @DisplayName("it evicts rather than growing without bound")
        void evicts() {
            ObservedCardinalities observed = new ObservedCardinalities(3);
            for (int id = 0; id < 10; id++) {
                observed.record(byId(id), id);
            }

            assertThat(observed.size()).isEqualTo(3);
            assertThat(observed.forExpression(byId(9))).hasValue(9);
            assertThat(observed.forExpression(byId(0))).isEmpty();
        }

        @Test
        @DisplayName("says how full it is, which is the one question a log of it answers")
        void describesItsOccupancy() {
            ObservedCardinalities observed = new ObservedCardinalities(3);
            assertThat(observed).hasToString("ObservedCardinalities[0/3]");
            observed.record(byId(1), 1);
            assertThat(observed).hasToString("ObservedCardinalities[1/3]");
        }

        @Test
        @DisplayName("eviction takes the least recently used, not the least recently written")
        void evictsLeastRecentlyUsed() {
            ObservedCardinalities observed = new ObservedCardinalities(2);
            observed.record(byId(1), 1);
            observed.record(byId(2), 2);

            assertThat(observed.forExpression(byId(1))).hasValue(1);   // 1 is now the recent one
            observed.record(byId(3), 3);

            assertThat(observed.forExpression(byId(1))).hasValue(1);
            assertThat(observed.forExpression(byId(2))).isEmpty();
        }

        @Test
        @DisplayName("NONE remembers nothing and can be handed anywhere")
        void noneRemembersNothing() {
            ObservedCardinalities.NONE.record(openOrders(), 42);

            assertThat(ObservedCardinalities.NONE.forExpression(openOrders())).isEmpty();
            assertThat(ObservedCardinalities.NONE.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a negative bound is refused")
        void refusesNegativeBound() {
            assertThatThrownBy(() -> new ObservedCardinalities(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the cost model prefers it to its own estimate")
    final class Consumption {

        @Test
        @DisplayName("a measured count is returned whole, not blended with a selectivity guess")
        void measuredBeatsEstimated() {
            ObservedCardinalities observed = new ObservedCardinalities();
            observed.record(openOrders(), 42);

            CostEstimator estimator = new CostEstimator(null).withObserved(observed);

            assertThat(estimator.estimateRows(openOrders())).hasValue(42);
        }

        @Test
        @DisplayName("an expression it has not seen falls back to estimating")
        void unmeasuredFallsBack() {
            CostEstimator plain = new CostEstimator(null);
            CostEstimator withStore =
                    new CostEstimator(null).withObserved(new ObservedCardinalities());

            assertThat(withStore.estimateRows(openOrders()))
                    .isEqualTo(plain.estimateRows(openOrders()));
        }
    }
}

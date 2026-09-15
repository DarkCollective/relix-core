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
package com.darkcollective.relix.provenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PathCostSemiring (cheapest cost + route witness)")
final class PathCostSemiringTest {

    private static final PathCostSemiring S = PathCostSemiring.INSTANCE;

    /** A single edge of {@code cost} witnessed by the one-token route {@code token}. */
    private static PathCost edge(double cost, String token) {
        return PathCost.of(cost, Route.of(token));
    }

    @Nested
    @DisplayName("plus (⊕ = cheaper wins, union on a tie)")
    class Plus {

        @Test
        @DisplayName("the cheaper alternative wins, keeping its route")
        void cheaperWins() {
            assertThat(S.plus(edge(2.0, "a"), edge(5.0, "b"))).hasToString("2.0 via a");
            assertThat(S.plus(edge(5.0, "b"), edge(2.0, "a"))).hasToString("2.0 via a");
        }

        @Test
        @DisplayName("a cost tie unions every co-cheapest route (in deterministic order)")
        void tieUnions() {
            assertThat(S.plus(edge(3.0, "b"), edge(3.0, "a"))).hasToString("3.0 via a | b");
        }

        @Test
        @DisplayName("zero is the additive identity")
        void identity() {
            assertThat(S.plus(edge(2.0, "a"), S.zero())).isEqualTo(edge(2.0, "a"));
        }
    }

    @Nested
    @DisplayName("times (⊗ = costs add, routes concatenate in series)")
    class Times {

        @Test
        @DisplayName("composing two hops adds cost and concatenates the route")
        void seriesComposition() {
            assertThat(S.times(edge(1.0, "a"), edge(1.0, "b"))).hasToString("2.0 via a·b");
        }

        @Test
        @DisplayName("a route-set product distributes over both witness sets")
        void routeSetProduct() {
            PathCost twoRoutes = S.plus(edge(1.0, "a"), edge(1.0, "c")); // (1.0, {a, c})
            // (1.0, {a, c}) ⊗ (1.0, {b}) = (2.0, {a∪b, c∪b}) = (2.0, {a·b, b·c})
            assertThat(S.times(twoRoutes, edge(1.0, "b"))).hasToString("2.0 via a·b | b·c");
        }

        @Test
        @DisplayName("one is the multiplicative identity (concatenating ε is a no-op)")
        void identity() {
            assertThat(S.times(edge(2.0, "a"), S.one())).isEqualTo(edge(2.0, "a"));
        }

        @Test
        @DisplayName("zero annihilates (+∞ absorbs cost, empty route-set empties the product)")
        void annihilates() {
            assertThat(S.times(edge(2.0, "a"), S.zero())).isEqualTo(S.zero());
        }
    }

    @Nested
    @DisplayName("zero / one rendering")
    class Identities {

        @Test
        @DisplayName("zero renders as ∞ and is the empty witness set")
        void zero() {
            assertThat(S.zero().isZero()).isTrue();
            assertThat(S.zero()).hasToString("∞");
        }

        @Test
        @DisplayName("one renders as the free derivation 0.0 via ε")
        void one() {
            assertThat(S.one().isZero()).isFalse();
            assertThat(S.one()).hasToString("0.0 via ε");
        }
    }

    @Nested
    @DisplayName("bounded representation (truncate + flag)")
    class Bounded {

        /** A value carrying {@code n} distinct routes of the same cost. */
        private static PathCost coCheapest(int n) {
            PathCost acc = S.zero();
            for (int i = 0; i < n; i++) {
                // zero-pad so the deterministic Route order is the numeric order
                acc = S.plus(acc, edge(1.0, String.format("v%04d", i)));
            }
            return acc;
        }

        @Test
        @DisplayName("a route-set at the cap is not truncated")
        void atCapNotTruncated() {
            PathCost p = coCheapest(PathCostSemiring.MAX_ROUTES);
            assertThat(p.routes()).hasSize(PathCostSemiring.MAX_ROUTES);
            assertThat(p.truncated()).isFalse();
            assertThat(p.toString()).doesNotContain("⋯");
        }

        @Test
        @DisplayName("exceeding the cap keeps MAX_ROUTES routes and flags truncation")
        void overCapTruncates() {
            PathCost p = coCheapest(PathCostSemiring.MAX_ROUTES + 50);
            assertThat(p.routes()).hasSize(PathCostSemiring.MAX_ROUTES);
            assertThat(p.truncated()).isTrue();
            assertThat(p.toString()).endsWith("⋯");
        }

        @Test
        @DisplayName("the cost stays exact even when the route-set is truncated")
        void costStaysExact() {
            PathCost p = coCheapest(PathCostSemiring.MAX_ROUTES + 1);
            assertThat(p.cost()).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("truncation is contagious through further operations")
        void contagious() {
            PathCost truncated = coCheapest(PathCostSemiring.MAX_ROUTES + 1);
            assertThat(truncated.truncated()).isTrue();
            assertThat(S.plus(truncated, S.zero()).truncated()).isTrue();
            assertThat(S.plus(S.zero(), truncated).truncated()).isTrue();
            assertThat(S.times(truncated, S.one()).truncated()).isTrue();
            assertThat(S.times(S.one(), truncated).truncated()).isTrue();
            // a cost tie carries truncation through the witness-set union (the ⊕ tie path),
            // from either operand
            PathCost sameCostFresh = edge(1.0, "v9999");
            assertThat(S.plus(truncated, sameCostFresh).truncated()).isTrue();
            assertThat(S.plus(sameCostFresh, truncated).truncated()).isTrue();
        }

        @Test
        @DisplayName("annihilating a truncated value renders the truncated zero")
        void truncatedZeroRenders() {
            PathCost truncated = coCheapest(PathCostSemiring.MAX_ROUTES + 1);
            PathCost annihilated = S.times(truncated, S.zero());
            assertThat(annihilated.isZero()).isTrue();
            assertThat(annihilated).hasToString("∞ + ⋯");
        }
    }
}

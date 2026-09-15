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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SortSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import com.darkcollective.relix.ast.AstBuilders;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Redundant-SORT elimination — SORT-001")
final class SortEliminationPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    private RelNode apply(RelNode node) {
        return SortEliminationPass.apply(node, "Q", ctx);
    }

    private boolean fired() {
        return !ctx.recordsFor(OptimizationCode.SORT_001).isEmpty();
    }

    private static SortNode sortBy(RelNode in, String... cols) {
        return sort(Arrays.stream(cols).map(AstBuilders::asc).toList(), in);
    }
    private static Predicate pred() {
        return cmp(attr("x"),
                ComparisonOperator.GREATER, num("0"));
    }

    // =========================================================================
    // SORT-001 — fires
    // =========================================================================

    @Nested
    @DisplayName("SORT-001 — redundant τ removed")
    class Fires {

        @Test
        @DisplayName("τ x (τ x (R)) → τ x (R)")
        void sortOverSameSort() {
            var inner = sortBy(rel("R"), "x");
            assertThat(apply(sortBy(inner, "x"))).isSameAs(inner);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("τ x (τ x, y (R)) → τ x, y (R) (required order is a prefix of the delivered)")
        void sortOverFinerSort() {
            var inner = sortBy(rel("R"), "x", "y");
            assertThat(apply(sortBy(inner, "x"))).isSameAs(inner);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("τ x (σ p (τ x (R))) → σ p (τ x (R)) (order carried through σ)")
        void sortOverSelectionOverSort() {
            var inner = select(pred(), sortBy(rel("R"), "x"));
            assertThat(apply(sortBy(inner, "x"))).isSameAs(inner);
            assertThat(fired()).isTrue();
        }
    }

    // =========================================================================
    // SORT-001 — no-op
    // =========================================================================

    @Nested
    @DisplayName("SORT-001 — does not fire")
    class NoOp {

        @Test
        @DisplayName("τ over a bare base relation is kept")
        void sortOverBase() {
            var node = sortBy(rel("R"), "x");
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("τ x, y (τ x (R)) is kept (delivered order too coarse)")
        void finerSortOverCoarser() {
            var node = sortBy(sortBy(rel("R"), "x"), "x", "y");
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("τ over a λ that bounds but the input has no order is kept")
        void sortOverUnorderedLimit() {
            var node = sortBy(limit(10L, rel("R")), "x");
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a tree with no τ is unchanged")
        void noSort() {
            var node = select(pred(), rel("R"));
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired()).isFalse();
        }
    }
}

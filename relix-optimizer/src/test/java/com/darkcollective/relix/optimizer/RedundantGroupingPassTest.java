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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Redundant-grouping elimination — AGG-001")
final class RedundantGroupingPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private RelNode apply(RelNode node) {
        return RedundantGroupingPass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    /** Aggregation with the given grouping keys and aggregate functions. */
    private static AggregationNode agg(List<String> keys, List<AggregateFunction> fns, RelNode in) {
        return AstBuilders.groupBy(keys, fns, in);
    }

    private static AggregateFunction sum(String col)               { return AggregateFunction.simple(AggregateOperator.SUM, col); }
    private static AggregateFunction sumAs(String col, String as)  { return AggregateFunction.aliased(AggregateOperator.SUM, col, as); }
    private static AggregateFunction count(String col)             { return AggregateFunction.simple(AggregateOperator.COUNT, col); }

    private boolean fired() {
        return !ctx.recordsFor(OptimizationCode.AGG_001).isEmpty();
    }

    // =========================================================================
    // AGG-001 — fires
    // =========================================================================

    @Nested
    @DisplayName("AGG-001 — redundant outer γ removed")
    class Fires {

        @Test
        @DisplayName("γ[a,b][](γ[a,b][](R)) → γ[a,b][](R) (both agg-less, same keys)")
        void doubleDistinctSameKeys() {
            var inner = agg(List.of("a", "b"), List.of(), rel("R"));
            var outer = agg(List.of("a", "b"), List.of(), inner);

            assertThat(apply(outer)).isSameAs(inner);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("γ[a,sum_x][](γ[a]SUM(x)(R)) → inner (outer keys = inner output cols)")
        void regroupOverAllOutputColumns() {
            var inner = agg(List.of("a"), List.of(sum("x")), rel("R"));
            var outer = agg(List.of("a", "sum_x"), List.of(), inner);

            assertThat(apply(outer)).isSameAs(inner);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("aliased inner aggregate: γ[a,total][](γ[a]SUM(x)→total(R)) → inner")
        void regroupOverAliasedColumns() {
            var inner = agg(List.of("a"), List.of(sumAs("x", "total")), rel("R"));
            var outer = agg(List.of("a", "total"), List.of(), inner);

            assertThat(apply(outer)).isSameAs(inner);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("comparison is set-based: outer keys in a different order still collapse")
        void keyOrderInsensitive() {
            var inner = agg(List.of("a"), List.of(sum("x")), rel("R"));
            var outer = agg(List.of("sum_x", "a"), List.of(), inner);

            assertThat(apply(outer)).isSameAs(inner);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("records exactly one AGG-001 transformation")
        void recordsTransformation() {
            var inner = agg(List.of("a"), List.of(), rel("R"));
            var outer = agg(List.of("a"), List.of(), inner);

            apply(outer);

            var records = ctx.recordsFor(OptimizationCode.AGG_001);
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().relationName()).isEqualTo("Q");
        }
    }

    // =========================================================================
    // AGG-001 — does not fire
    // =========================================================================

    @Nested
    @DisplayName("AGG-001 — no-op cases")
    class NoOp {

        @Test
        @DisplayName("outer aggregation has its own aggregate → not redundant")
        void outerHasAggregates() {
            var inner = agg(List.of("a"), List.of(), rel("R"));
            var outer = agg(List.of("a"), List.of(count("b")), inner);

            assertThat(apply(outer)).isSameAs(outer);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("outer keys are a strict subset of inner output cols → changes result")
        void outerKeysStrictSubset() {
            var inner = agg(List.of("a", "b"), List.of(), rel("R"));
            var outer = agg(List.of("a"), List.of(), inner);

            assertThat(apply(outer)).isSameAs(outer);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("outer keys drop the inner's aggregate column → changes schema")
        void outerKeysOmitAggregateColumn() {
            var inner = agg(List.of("a"), List.of(sum("x")), rel("R"));
            var outer = agg(List.of("a"), List.of(), inner); // missing sum_x

            assertThat(apply(outer)).isSameAs(outer);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("outer keys exceed inner output cols → not equal")
        void outerKeysSuperset() {
            var inner = agg(List.of("a", "b"), List.of(), rel("R"));
            var outer = agg(List.of("a", "b", "c"), List.of(), inner);

            assertThat(apply(outer)).isSameAs(outer);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("input is not an aggregation → no-op")
        void inputNotAggregation() {
            var single = agg(List.of("a"), List.of(), rel("R"));

            assertThat(apply(single)).isSameAs(single);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("plain relation → no-op")
        void plainRelation() {
            var r = rel("R");
            assertThat(apply(r)).isSameAs(r);
            assertThat(fired()).isFalse();
        }
    }

    // =========================================================================
    // Traversal
    // =========================================================================

    @Nested
    @DisplayName("traversal")
    class Traversal {

        @Test
        @DisplayName("rule fires deep in the tree (below a projection)")
        void firesBelowProjection() {
            var inner = agg(List.of("a"), List.of(), rel("R"));
            var outer = agg(List.of("a"), List.of(), inner);
            var proj  = project(
                    List.of(ProjectedAttribute.simple(attr("a"))), outer);

            RelNode result = apply(proj);

            assertThat(result).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) result).input()).isSameAs(inner);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a stack of three γ collapses to one in a single pass")
        void tripleStackCollapses() {
            var innermost = agg(List.of("a"), List.of(), rel("R"));
            var middle    = agg(List.of("a"), List.of(), innermost);
            var outer     = agg(List.of("a"), List.of(), middle);

            assertThat(apply(outer)).isSameAs(innermost);
            assertThat(ctx.recordsFor(OptimizationCode.AGG_001)).hasSize(2);
        }
    }

    // =========================================================================
    // QueryOptimizer wiring
    // =========================================================================

    @Nested
    @DisplayName("QueryOptimizer wiring")
    class Wiring {

        @Test
        @DisplayName("AGG-001 runs as part of the full pass pipeline")
        void wiredIntoOptimizer() {
            var inner = agg(List.of("a"), List.of(), rel("R"));
            var outer = agg(List.of("a"), List.of(), inner);

            RelNode result = new QueryOptimizer().optimize(outer, "Q", SchemaAnnotations.empty(), ctx);

            assertThat(result).isSameAs(inner);
            assertThat(fired()).isTrue();
        }
    }
}

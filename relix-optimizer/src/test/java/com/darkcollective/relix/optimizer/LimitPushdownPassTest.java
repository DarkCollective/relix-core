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

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Limit pushdown — LIM-001..004")
final class LimitPushdownPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private RelNode applyLimit(RelNode node) {
        return LimitPushdownPass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }


    private static ProjectedAttribute simple(AttributeOperand ao) {
        return ProjectedAttribute.simple(ao);
    }

    // =========================================================================
    // LIM-001 — basic pushdown
    // =========================================================================

    @Nested
    @DisplayName("LIM-001 — limit pushed below projection")
    class Lim001 {

        @Test
        @DisplayName("λ(0, 10)(π(a)(R)) → π(a)(λ(0, 10)(R))")
        void basicPushdown() {
            var base  = rel("R");
            var proj  = project(List.of(simple(attr("a"))), base);
            var limit = limit(10, proj);

            RelNode result = applyLimit(limit);

            assertThat(result).isNode(ProjectionNode.class);
            var outerProj = (ProjectionNode) result;
            assertThat(outerProj.attributes()).isEqualTo(proj.attributes());
            assertThat(outerProj.input()).isNode(LimitNode.class);
            var innerLimit = (LimitNode) outerProj.input();
            assertThat(innerLimit.offset()).isEmpty();
            assertThat(innerLimit.count()).isEqualTo(10L);
            assertThat(innerLimit.input()).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.LIM_001, 1);
        }

        @Test
        @DisplayName("non-zero offset is preserved: λ(5, 10)(π(a)(R)) → π(a)(λ(5, 10)(R))")
        void offsetPreserved() {
            var base  = rel("R");
            var proj  = project(List.of(simple(attr("a")), simple(attr("b"))), base);
            var limit = limit(Optional.of(5L), 10L, proj);

            RelNode result = applyLimit(limit);

            assertThat(result).isNode(ProjectionNode.class);
            var innerLimit = (LimitNode) ((ProjectionNode) result).input();
            assertThat(innerLimit.offset()).contains(5L);
            assertThat(innerLimit.count()).isEqualTo(10L);
            assertThat(innerLimit.input()).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.LIM_001, 1);
        }

        @Test
        @DisplayName("multi-column projection retained after pushdown")
        void multiColumnProjectionRetained() {
            var base  = rel("R");
            var attrs = List.of(simple(attr("a")), simple(attr("b")), simple(attr("c")));
            var proj  = project(attrs, base);
            var limit = limit(5, proj);

            RelNode result = applyLimit(limit);

            assertThat(result).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) result).attributes()).isEqualTo(attrs);
            assertThat(ctx).fired(OptimizationCode.LIM_001, 1);
        }

        @Test
        @DisplayName("pushdown is applied recursively (bottom-up): inner limit is pushed first")
        void bottomUpRecursion() {
            // Build: λ(0,5)(π(a)(λ(0,10)(π(a,b)(R))))
            // Bottom-up: the inner λ is pushed first → π(a,b)(λ(0,10)(R)); the outer
            // λ(0,5) then descends both projections in one visit, so LIM-001 fires
            // three times in total → π(a)(π(a,b)(λ(0,5)(λ(0,10)(R)))).
            var base        = rel("R");
            var innerProj   = project(
                    List.of(simple(attr("a")), simple(attr("b"))), base);
            var innerLimit  = limit(10, innerProj);
            var outerProj   = project(List.of(simple(attr("a"))), innerLimit);
            var outerLimit  = limit(5, outerProj);

            RelNode result = applyLimit(outerLimit);

            assertThat(ctx).fired(OptimizationCode.LIM_001, 3);
            // Outermost should be a ProjectionNode
            assertThat(result).isNode(ProjectionNode.class);
        }
    }

    // =========================================================================
    // LIM-002 — below rename
    // =========================================================================

    @Nested
    @DisplayName("LIM-002 — limit pushed below rename")
    class Lim002 {

        @Test
        @DisplayName("λ(0, 10)(ρ N (R)) → ρ N (λ(0, 10)(R))")
        void belowRelationRename() {
            var base   = rel("R");
            var rename = rename("N", List.of(), base);

            RelNode result = applyLimit(limit(10, rename));

            assertThat(result).isNode(RenameNode.class);
            var inner = (LimitNode) ((RenameNode) result).input();
            assertThat(inner.count()).isEqualTo(10L);
            assertThat(inner.input()).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.LIM_002, 1);
        }

        @Test
        @DisplayName("column-renaming ρ is pushed through too, offset preserved")
        void belowColumnRename() {
            var base   = rel("R");
            var rename = rename("N", List.of("x", "y"), base);

            RelNode result = applyLimit(limit(Optional.of(5L), 10L, rename));

            assertThat(result).isNode(RenameNode.class);
            var renamed = (RenameNode) result;
            assertThat(renamed.attributes()).containsExactly("x", "y");
            var inner = (LimitNode) renamed.input();
            assertThat(inner.offset()).contains(5L);
            assertThat(ctx).fired(OptimizationCode.LIM_002, 1);
        }
    }

    // =========================================================================
    // LIM-003 — top-N (λ over τ → TOP)
    // =========================================================================

    @Nested
    @DisplayName("LIM-003 — limit over sort fused into TOP")
    class Lim003 {

        private final SortSpecification byA = desc("a");

        @Test
        @DisplayName("λ(3)(τ a DESC (R)) → TOP 3 a DESC (R) with no grouping keys")
        void fusesIntoTopK() {
            var base = rel("R");
            var sort = sort(List.of(byA), base);

            RelNode result = applyLimit(limit(3, sort));

            assertThat(result).isNode(TopKNode.class);
            var top = (TopKNode) result;
            assertThat(top.groupingAttributes()).isEmpty();
            assertThat(top.sortSpecs()).containsExactly(byA);
            assertThat(top.count()).isEqualTo(3L);
            assertThat(top.offset()).isEmpty();
            assertThat(top.input()).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.LIM_003, 1);
        }

        @Test
        @DisplayName("the λ's offset carries across — TOP skips before it takes")
        void offsetCarriesAcross() {
            var base = rel("R");
            var sort = sort(List.of(byA), base);

            var top = (TopKNode) applyLimit(limit(Optional.of(5L), 3L, sort));

            assertThat(top.offset()).contains(5L);
            assertThat(top.count()).isEqualTo(3L);
        }

        @Test
        @DisplayName("λ over π over τ: the limit descends the projection and then fuses")
        void fusesAfterDescendingAProjection() {
            var base = rel("R");
            var sort = sort(List.of(byA), base);
            var proj = project(List.of(simple(attr("a"))), sort);

            RelNode result = applyLimit(limit(3, proj));

            assertThat(result).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) result).input()).isNode(TopKNode.class);
            assertThat(ctx).fired(OptimizationCode.LIM_001, 1);
            assertThat(ctx).fired(OptimizationCode.LIM_003, 1);
        }

        @Test
        @DisplayName("the rewrite is idempotent — a TOP is not re-fused")
        void idempotent() {
            var base = rel("R");
            var sort = sort(List.of(byA), base);

            RelNode once  = applyLimit(limit(3, sort));
            RelNode twice = LimitPushdownPass.apply(once, "Q", SchemaAnnotations.empty(), ctx);

            assertThat(twice).isSameAs(once);
            assertThat(ctx).fired(OptimizationCode.LIM_003, 1);
        }
    }

    // =========================================================================
    // LIM-004 — into union-all branches
    // =========================================================================

    @Nested
    @DisplayName("LIM-004 — limit replicated into union-all branches")
    class Lim004 {

        @Test
        @DisplayName("λ(10)(A ⊎ B) → λ(10)((λ(10) A) ⊎ (λ(10) B)) — the outer λ stays")
        void replicatesIntoBranches() {
            var a = rel("A");
            var b = rel("B");

            RelNode result = applyLimit(limit(10, unionAll(a, b)));

            assertThat(result).isNode(LimitNode.class);
            var outer = (LimitNode) result;
            assertThat(outer.count()).isEqualTo(10L);
            var union = (UnionAllNode) outer.input();
            assertThat(((LimitNode) union.left()).count()).isEqualTo(10L);
            assertThat(((LimitNode) union.right()).count()).isEqualTo(10L);
            assertThat(ctx).fired(OptimizationCode.LIM_004, 1);
        }

        @Test
        @DisplayName("with an offset each branch is bounded at offset + count")
        void offsetWidensTheBranchBound() {
            var a = rel("A");
            var b = rel("B");

            var outer = (LimitNode) applyLimit(limit(Optional.of(5L), 10L, unionAll(a, b)));

            assertThat(outer.offset()).contains(5L);
            var union = (UnionAllNode) outer.input();
            // A branch could supply every one of the 5 skipped rows, so it must
            // still produce 15 for the union to have 10 left after the skip.
            assertThat(((LimitNode) union.left()).count()).isEqualTo(15L);
            assertThat(((LimitNode) union.left()).offset()).isEmpty();
            assertThat(((LimitNode) union.right()).count()).isEqualTo(15L);
        }

        @Test
        @DisplayName("the rewrite is idempotent — already-bounded branches are left alone")
        void idempotent() {
            var union = unionAll(rel("A"), rel("B"));

            RelNode once  = applyLimit(limit(10, union));
            RelNode twice = LimitPushdownPass.apply(once, "Q", SchemaAnnotations.empty(), ctx);

            assertThat(twice).isSameAs(once);
            assertThat(ctx).fired(OptimizationCode.LIM_004, 1);
        }

        @Test
        @DisplayName("one already-bounded branch is not enough to skip the rewrite")
        void oneBranchAlreadyBounded() {
            // Idempotence is about a union whose branches are *both* already capped —
            // the shape this rule itself produces. A union the author wrote with a limit
            // on one branch only is not that shape, and the other branch still needs its
            // cap, so the rewrite must fire rather than read the first branch and stop.
            var union = unionAll(limit(3, rel("A")), rel("B"));

            var outer = (LimitNode) applyLimit(limit(10, union));
            var u = (UnionAllNode) outer.input();

            assertThat(((LimitNode) u.left()).count()).isEqualTo(10L);
            assertThat(((LimitNode) u.left()).input())
                    .as("the branch's own tighter bound survives underneath, so it still "
                            + "yields three rows; collapsing the pair is LIM-001's job")
                    .isNode(LimitNode.class);
            assertThat(((LimitNode) ((LimitNode) u.left()).input()).count()).isEqualTo(3L);
            assertThat(((LimitNode) u.right()).count()).isEqualTo(10L);
            assertThat(ctx).fired(OptimizationCode.LIM_004, 1);
        }

        @Test
        @DisplayName("a branch limit descends its own projection")
        void branchLimitDescends() {
            var a    = rel("A");
            var proj = project(List.of(simple(attr("a"))), a);
            var union = unionAll(proj, rel("B"));

            var outer = (LimitNode) applyLimit(limit(4, union));

            var u = (UnionAllNode) outer.input();
            assertThat(u.left()).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) u.left()).input()).isNode(LimitNode.class);
            assertThat(ctx).fired(OptimizationCode.LIM_004, 1);
            assertThat(ctx).fired(OptimizationCode.LIM_001, 1);
        }
    }

    // =========================================================================
    // No-op cases — limit not pushed through non-projection operators
    // =========================================================================

    @Nested
    @DisplayName("No-op — limit not pushed through row-changing operators")
    class NoOp {

        @Test
        @DisplayName("λ(n)(σ(p)(R)) is NOT pushed (selection filters rows)")
        void notPushedThroughSelection() {
            var base  = rel("R");
            var pred  = cmp(attr("a"), ComparisonOperator.EQUAL, num("1"));
            var sel   = select(pred, base);
            var limit = limit(10, sel);

            RelNode result = applyLimit(limit);

            assertThat(result).isSameAs(limit);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("λ(n)(R) with RelationNode input is unchanged")
        void limitOverRelationUnchanged() {
            var base  = rel("R");
            var limit = limit(10, base);

            RelNode result = applyLimit(limit);

            assertThat(result).isSameAs(limit);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("λ(n)(δ(R)) is NOT pushed (distinct may change cardinality)")
        void notPushedThroughDistinct() {
            var base  = rel("R");
            var dist  = distinct(base);
            var limit = limit(5, dist);

            RelNode result = applyLimit(limit);

            assertThat(result).isSameAs(limit);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("λ(n)(γ(...)( R)) is NOT pushed (aggregation changes cardinality)")
        void notPushedThroughAggregation() {
            var base  = rel("R");
            var agg   = groupBy(
                    List.of("dept"),
                    List.of(AggregateFunction.simple(AggregateOperator.COUNT, "id")),
                    base);
            var limit = limit(5, agg);

            RelNode result = applyLimit(limit);

            assertThat(result).isSameAs(limit);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("λ(n)(A ⋈ B) is NOT pushed (join operates on both sides)")
        void notPushedThroughNaturalJoin() {
            var a     = rel("A");
            var b     = rel("B");
            var join  = naturalJoin(a, b);
            var limit = limit(10, join);

            RelNode result = applyLimit(limit);

            assertThat(result).isSameAs(limit);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("nodes without any LimitNode pass through unchanged")
        void nonLimitNodeUnchanged() {
            var base = rel("R");
            var proj = project(List.of(simple(attr("a"))), base);

            RelNode result = applyLimit(proj);

            assertThat(result).isSameAs(proj);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("RelationNode leaf passes through unchanged")
        void relationNodeLeafUnchanged() {
            var r = rel("R");
            assertThat(applyLimit(r)).isSameAs(r);
        }
    }

    // =========================================================================
    // LIM-001 fires inside a larger tree (bottom-up recursion)
    // =========================================================================

    @Nested
    @DisplayName("LIM-001 fires inside a binary node")
    class InsideBinaryNode {

        @Test
        @DisplayName("limit inside natural join left child is pushed")
        void pushInsideJoinLeftChild() {
            var base  = rel("R");
            var proj  = project(List.of(simple(attr("a"))), base);
            var limit = limit(5, proj);       // left child

            var right = rel("S");
            var join  = naturalJoin(limit, right);

            RelNode result = applyLimit(join);

            assertThat(result).isNode(NaturalJoinNode.class);
            var rjoin = (NaturalJoinNode) result;
            // Left child should now be ProjectionNode wrapping LimitNode
            assertThat(rjoin.left()).isNode(ProjectionNode.class);
            assertThat(rjoin.right()).isSameAs(right);
            assertThat(ctx).fired(OptimizationCode.LIM_001, 1);
        }
    }

    // =========================================================================
    // QueryOptimizer wiring
    // =========================================================================

    @Nested
    @DisplayName("QueryOptimizer wiring")
    class OptimizerWiring {

        @Test
        @DisplayName("LIM-001 fires through QueryOptimizer")
        void lim001ThroughOptimizer() {
            var base  = rel("R");
            var proj  = project(List.of(simple(attr("a"))), base);
            var limit = limit(10, proj);

            var ctx2 = new OptimizationContext();
            RelNode result = new QueryOptimizer()
                    .optimize(limit, "Q", SchemaAnnotations.empty(), ctx2);

            assertThat(result).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) result).input()).isNode(LimitNode.class);
            assertThat(ctx2).fired(OptimizationCode.LIM_001, 1);
        }

        @Test
        @DisplayName("limit over selection is unchanged through QueryOptimizer")
        void limitOverSelectionUnchangedThroughOptimizer() {
            var base  = rel("R");
            var pred  = cmp(attr("a"), ComparisonOperator.EQUAL, num("1"));
            var sel   = select(pred, base);
            var limit = limit(5, sel);

            var ctx2 = new OptimizationContext();
            RelNode result = new QueryOptimizer()
                    .optimize(limit, "Q", SchemaAnnotations.empty(), ctx2);

            // The selection might be merged/reordered by selection rules,
            // but the limit should NOT be pushed below the selection
            assertThat(ctx2).didNotFire(OptimizationCode.LIM_001);
        }
    }
}

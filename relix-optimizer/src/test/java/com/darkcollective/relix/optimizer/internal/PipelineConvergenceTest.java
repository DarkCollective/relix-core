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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.TransformationRecord;
import com.darkcollective.relix.optimizer.internal.DistinctEliminationPass;
import com.darkcollective.relix.optimizer.internal.OptimizationContext;
import com.darkcollective.relix.optimizer.internal.OptimizationPipeline;
import com.darkcollective.relix.optimizer.internal.PassRule;
import com.darkcollective.relix.optimizer.internal.QueryOptimizer;
import com.darkcollective.relix.optimizer.internal.RedundantGroupingPass;
import com.darkcollective.relix.optimizer.internal.SelectionMergePass;
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

/**
 * Pins the behaviour of the whole pipeline rather than of any one rule: the
 * sequence of rules a representative query fires (the "golden" pipeline), that a
 * second run of the optimizer changes nothing, and that the rule pairs which are
 * mutual inverses do not oscillate now that phases are iterated.
 *
 * <p>The oscillation guards are the reason {@link OptimizationPipeline} groups rules
 * into phases at all — {@code SEL-001}/{@code SEL-002} and {@code SEL-003}/{@code
 * PROJ-003} each undo one another, and iterating either pair together would not
 * terminate.
 */
@DisplayName("Optimizer pipeline — golden order, convergence, no oscillation")
final class PipelineConvergenceTest {

    private final QueryOptimizer optimizer = new QueryOptimizer();

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static ComparisonPredicate gt(String column, long value) {
        return cmp(attr(column), ComparisonOperator.GREATER,
                num(String.valueOf(value)));
    }

    private static RelNode sales() {
        return groupBy(
                List.of("region"),
                List.of(AggregateFunction.aliased(AggregateOperator.SUM, "amount", "total")),
                rel("Sales"));
    }

    /** The codes recorded by optimizing {@code tree}, in firing order. */
    private List<OptimizationCode> codesFor(RelNode tree) {
        var ctx = new OptimizationContext();
        optimizer.optimize(tree, "Q", SchemaAnnotations.empty(), ctx);
        return ctx.records().stream().map(TransformationRecord::code).toList();
    }

    // =========================================================================
    // Golden pipeline
    // =========================================================================

    @Nested
    @DisplayName("golden pipeline")
    class Golden {

        @Test
        @DisplayName("a filtered, deduplicated rollup fires a fixed, ordered rule sequence")
        void goldenRuleSequence() {
            // σ region > 1 ∧ total > 100 ( δ ( γ region, SUM(amount)→total (Sales) ) )
            Predicate both = and(gt("region", 1), gt("total", 100));
            RelNode tree = select(both, distinct(sales()));

            assertThat(codesFor(tree)).containsExactly(
                    // pushdown: the conjunction is split, then each conjunct travels as far
                    // as it legally can — the grouping-key one below the δ and then below
                    // the γ (HAVING → WHERE), the aggregate one only as far as the δ.
                    OptimizationCode.SEL_001,
                    OptimizationCode.SEL_008,
                    OptimizationCode.SEL_007,
                    OptimizationCode.SEL_008,
                    // cleanup: nothing is left adjacent to merge, and the δ over a γ is
                    // redundant because a γ already emits distinct grouping keys.
                    OptimizationCode.DIST_001);
        }

        @Test
        @DisplayName("the golden sequence is stable across repeated optimizer instances")
        void goldenIsStable() {
            Predicate both = and(gt("region", 1), gt("total", 100));
            RelNode tree = select(both, distinct(sales()));

            assertThat(codesFor(tree)).isEqualTo(codesFor(tree));
        }
    }

    // =========================================================================
    // Convergence
    // =========================================================================

    @Nested
    @DisplayName("convergence — a second run is a no-op")
    class Convergence {

        /** Optimizing an already-optimized tree must record nothing and rewrite nothing. */
        private void assertConverged(String label, RelNode tree) {
            var first = new OptimizationContext();
            RelNode once = optimizer.optimize(tree, "Q", SchemaAnnotations.empty(), first);

            var second = new OptimizationContext();
            RelNode twice = optimizer.optimize(once, "Q", SchemaAnnotations.empty(), second);

            assertThat(second.records()).as("%s — second run fired rules", label).isEmpty();
            assertThat(twice).as("%s — second run rewrote the tree", label).isSameAs(once);
        }

        @Test
        @DisplayName("a filtered rollup converges")
        void rollupConverges() {
            assertConverged("σ ∧ σ (δ (γ))",
                    select(and(gt("region", 1), gt("total", 100)),
                            distinct(sales())));
        }

        @Test
        @DisplayName("a projection over a selection converges")
        void projectionOverSelectionConverges() {
            assertConverged("π (σ (R))",
                    project(
                            List.of(ProjectedAttribute.simple(attr("a")),
                                    ProjectedAttribute.simple(attr("b"))),
                            select(gt("a", 1), rel("R"))));
        }

        @Test
        @DisplayName("a sort under a selection converges")
        void sortConverges() {
            assertConverged("σ (τ (R))",
                    select(gt("a", 1),
                            sort(List.of(sortKey(attr("a"),
                                    SortDirection.ASC)), rel("R"))));
        }

        @Test
        @DisplayName("a bare leaf converges")
        void leafConverges() {
            assertConverged("R", rel("R"));
        }
    }

    // =========================================================================
    // Oscillation guards
    // =========================================================================

    @Nested
    @DisplayName("mutual inverses do not oscillate")
    class NoOscillation {

        @Test
        @DisplayName("SEL-001 ∘ SEL-002 is the identity on a conjunction")
        void splitThenMergeIsIdentity() {
            Predicate both = and(gt("a", 1), gt("b", 2));
            RelNode tree = select(both, rel("R"));

            var ctx = new OptimizationContext();
            RelNode result = optimizer.optimize(tree, "Q", SchemaAnnotations.empty(), ctx);

            // The split (phase 2) and the merge (phase 3) each fire once and cancel:
            // one σ, carrying both conjuncts, straight above the leaf.
            assertThat(ctx).fired(OptimizationCode.SEL_001, 1);
            assertThat(ctx).fired(OptimizationCode.SEL_002, 1);
            assertThat(result).isNode(SelectionNode.class);
            var selection = (SelectionNode) result;
            assertThat(selection.predicate()).isInstanceOf(AndPredicate.class);
            assertThat(selection.input()).isNode(RelationNode.class);
        }

        @Test
        @DisplayName("SEL-003 and PROJ-003 settle instead of trading the σ/π order forever")
        void selectionAndProjectionDoNotOscillate() {
            // π a, b (σ a > 1 (R)) — PROJ-003 wants π below σ, SEL-003 wants σ below π.
            RelNode tree = project(
                    List.of(ProjectedAttribute.simple(attr("a")),
                            ProjectedAttribute.simple(attr("b"))),
                    select(gt("a", 1), rel("R")));

            var ctx = new OptimizationContext();
            RelNode result = optimizer.optimize(tree, "Q", SchemaAnnotations.empty(), ctx);

            // Each fires at most once: SEL-003 lives in the pushdown phase and PROJ-003
            // in the cleanup phase, so neither can hand the other back its own input.
            assertThat(ctx.countOf(OptimizationCode.SEL_003)).isLessThanOrEqualTo(1);
            assertThat(ctx.countOf(OptimizationCode.PROJ_003)).isLessThanOrEqualTo(1);
            assertThat(ctx.countOf(OptimizationCode.SEL_003)
                    + ctx.countOf(OptimizationCode.PROJ_003)).isLessThanOrEqualTo(1);

            // …and re-optimizing the settled tree moves nothing back.
            var again = new OptimizationContext();
            assertThat(optimizer.optimize(result, "Q", SchemaAnnotations.empty(), again)
                    ).isSameAs(result);
            assertThat(again.isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // What iteration buys
    // =========================================================================

    @Nested
    @DisplayName("iteration within a phase")
    class Iteration {

        /** The real cleanup-phase rules, in their real order, at a given sweep cap. */
        private OptimizationPipeline cleanupPhase(int maxIterations) {
            return OptimizationPipeline.of(List.of(new OptimizationPipeline.Phase("cleanup",
                    List.of(PassRule.of("selection-merge", SelectionMergePass::apply,
                                    OptimizationCode.SEL_002),
                            PassRule.of("distinct-elimination", DistinctEliminationPass::apply,
                                    OptimizationCode.DIST_001)),
                    maxIterations)));
        }

        @Test
        @DisplayName("a rule late in a phase can expose work for one earlier in it")
        void lateRuleFeedsEarlierRule() {
            // σ a > 1 ( δ ( σ total > 100 ( γ region, SUM(amount)→total (Sales) ) ) )
            // DIST-001 removes the δ (a γ is already distinct), which leaves the two σ
            // adjacent — but the merge already ran this sweep, so SEL-002 needs another.
            RelNode tree = select(gt("a", 1),
                    distinct(select(gt("total", 100), sales())));

            var single = new OptimizationContext();
            cleanupPhase(OptimizationPipeline.SINGLE_PASS)
                    .run(tree, "Q", SchemaAnnotations.empty(), single);

            var iterated = new OptimizationContext();
            cleanupPhase(OptimizationPipeline.DEFAULT_MAX_ITERATIONS)
                    .run(tree, "Q", SchemaAnnotations.empty(), iterated);

            assertThat(single.records()).extracting(TransformationRecord::code)
                    .containsExactly(OptimizationCode.DIST_001);
            assertThat(iterated.records()).extracting(TransformationRecord::code)
                    .containsExactly(OptimizationCode.DIST_001, OptimizationCode.SEL_002);
        }

        @Test
        @DisplayName("AGG-001 collapses a three-deep γ stack in a single sweep (it is bottom-up)")
        void aggregationStackCollapsesInOneSweep() {
            // The pass rewrites each input before testing the rule at the node above it,
            // so the whole stack collapses inside-out within one application — the loop
            // is not what makes this reachable.
            RelNode inner = sales();
            RelNode middle = groupBy(List.of("region", "total"), List.of(), inner);
            RelNode outer = groupBy(List.of("region", "total"), List.of(), middle);

            var ctx = new OptimizationContext();
            RelNode result = RedundantGroupingPass.apply(outer, "Q",
                    SchemaAnnotations.empty(), ctx);

            assertThat(ctx).fired(OptimizationCode.AGG_001, 2);
            assertThat(result).isSameAs(inner);
        }
    }
}

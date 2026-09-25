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
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Nest/unnest round-trip laws — NEST-001..003")
final class NestUnnestPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private RelNode apply(RelNode node) {
        return NestUnnestPass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private boolean fired(OptimizationCode code) {
        return !ctx.recordsFor(code).isEmpty();
    }

    /** {@code γ keys, COLLECT(arg)→alias (R)}; alias empty → synthetic output name. */
    private static AggregationNode nest(List<String> keys, String arg, Optional<String> alias) {
        AggregateFunction collect = alias
                .map(a -> AggregateFunction.aliased(AggregateOperator.COLLECT, attr(arg), a))
                .orElseGet(() -> AggregateFunction.of(AggregateOperator.COLLECT, attr(arg)));
        return groupBy(keys, List.of(collect), rel("R"));
    }

    private static Predicate gt(String col, String value) {
        return cmp(attr(col),
                ComparisonOperator.GREATER, num(value));
    }

    // =========================================================================
    // NEST-001 — round-trip collapse (μ ∘ COLLECT = id)
    // =========================================================================

    @Nested
    @DisplayName("NEST-001 — μ over COLLECT collapses to a projection")
    class RoundTripFires {

        @Test
        @DisplayName("μ g (γ k, COLLECT(x)→g (R)) → π k, (x→g) (R)")
        void collapsesGroupedCollect() {
            var agg = nest(List.of("k"), "x", Optional.of("g"));
            RelNode result = apply(unnest("g", agg));

            assertThat(result).isNode(ProjectionNode.class);
            var proj = (ProjectionNode) result;
            assertThat(proj.attributes()).containsExactly(
                    projected(attr("k")),
                    projected(attr("x"),"g"));
            assertThat(proj.input()).isSameAs(agg.input());
            assertThat(fired(OptimizationCode.NEST_001)).isTrue();
        }

        @Test
        @DisplayName("matches the COLLECT's synthetic output name when there is no alias")
        void collapsesViaSyntheticName() {
            var agg = nest(List.of("k"), "x", Optional.empty());   // outputName = collect_x
            RelNode result = apply(unnest("collect_x", agg));

            assertThat(result).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) result).attributes()).containsExactly(
                    projected(attr("k")),
                    projected(attr("x"),"collect_x"));
            assertThat(fired(OptimizationCode.NEST_001)).isTrue();
        }

        @Test
        @DisplayName("no-key (scalar) COLLECT under an inner μ collapses to a single-column projection")
        void collapsesScalarCollect() {
            var agg = nest(List.of(), "x", Optional.of("g"));
            RelNode result = apply(unnest("g", agg));

            assertThat(result).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) result).attributes()).containsExactly(
                    projected(attr("x"),"g"));
            assertThat(fired(OptimizationCode.NEST_001)).isTrue();
        }

        @Test
        @DisplayName("an outer μ with a grouping key still collapses (groups are never empty)")
        void collapsesOuterWithKey() {
            var agg = nest(List.of("k"), "x", Optional.of("g"));
            RelNode result = apply(unnest("g", true, agg));

            assertThat(result).isNode(ProjectionNode.class);
            assertThat(fired(OptimizationCode.NEST_001)).isTrue();
        }

        @Test
        @DisplayName("the collapse fires for a μ buried under another operator")
        void collapsesUnderSelection() {
            var agg = nest(List.of("k"), "x", Optional.of("g"));
            // σ (k>0) (μ g (γ …))  — the σ references k, not g, so the σ pushes too,
            // but the μ→π collapse still fires underneath.
            RelNode result = apply(select(gt("k", "0"), unnest("g", agg)));
            assertThat(fired(OptimizationCode.NEST_001)).isTrue();
        }

        @Test
        @DisplayName("the collapse fires inside a binary operator's branch (recursion)")
        void collapsesInsideUnionBranch() {
            var agg = nest(List.of("k"), "x", Optional.of("g"));
            var union = new com.darkcollective.relix.ast.UnionNode(rel("A"), unnest("g", agg));
            RelNode result = apply(union);

            assertThat(result).isInstanceOf(com.darkcollective.relix.ast.UnionNode.class);
            var rewritten = (com.darkcollective.relix.ast.UnionNode) result;
            assertThat(rewritten.left()).isSameAs(union.left());
            assertThat(rewritten.right()).isNode(ProjectionNode.class);
            assertThat(fired(OptimizationCode.NEST_001)).isTrue();
        }
    }

    @Nested
    @DisplayName("NEST-001 — does not fire")
    class RoundTripNoOp {

        @Test
        @DisplayName("μ WITH ORDINALITY is not collapsed (per-group position is not reproducible)")
        void ordinalityBlocks() {
            var agg = nest(List.of("k"), "x", Optional.of("g"));
            var node = unnest("g", false, Optional.of("pos"), agg);
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_001)).isFalse();
        }

        @Test
        @DisplayName("an outer μ over a no-key COLLECT is kept (empty input would emit a NULL row)")
        void outerScalarBlocks() {
            var agg = nest(List.of(), "x", Optional.of("g"));
            var node = unnest("g", true, agg);
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_001)).isFalse();
        }

        @Test
        @DisplayName("a second aggregate alongside COLLECT blocks the collapse")
        void multipleAggregatesBlock() {
            var agg = groupBy(List.of("k"),
                    List.of(AggregateFunction.aliased(AggregateOperator.COLLECT, attr("x"), "g"),
                            AggregateFunction.simple(AggregateOperator.SUM, "y")),
                    rel("R"));
            var node = unnest("g", agg);
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_001)).isFalse();
        }

        @Test
        @DisplayName("a non-COLLECT aggregate is not a nest and is not collapsed")
        void nonCollectBlocks() {
            var agg = groupBy(List.of("k"),
                    List.of(AggregateFunction.aliased(AggregateOperator.SUM, "x", "g")), rel("R"));
            var node = unnest("g", agg);
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_001)).isFalse();
        }

        @Test
        @DisplayName("μ on a column other than the COLLECT output does not fire")
        void columnMismatchBlocks() {
            var agg = nest(List.of("k"), "x", Optional.of("g"));
            var node = unnest("other", agg);   // unnests a different column
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_001)).isFalse();
        }

        @Test
        @DisplayName("μ over a non-aggregation input does not fire")
        void nonAggregationInputBlocks() {
            var node = unnest("items", rel("R"));
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_001)).isFalse();
        }
    }

    // =========================================================================
    // NEST-002 — selection pushed below unnest
    // =========================================================================

    @Nested
    @DisplayName("NEST-002 — σ pushed below μ")
    class SelectionThroughUnnest {

        @Test
        @DisplayName("σ p (μ c (R)) → μ c (σ p (R)) when p avoids the unnested column")
        void pushesDisjointPredicate() {
            var mu = unnest("items", rel("R"));
            RelNode result = apply(select(gt("region", "0"), mu));

            assertThat(result).isNode(UnnestNode.class);
            var pushed = (UnnestNode) result;
            assertThat(pushed.column()).isEqualTo("items");
            assertThat(pushed.input()).isNode(SelectionNode.class);
            assertThat(((SelectionNode) pushed.input()).input()).isSameAs(mu.input());
            assertThat(fired(OptimizationCode.NEST_002)).isTrue();
        }

        @Test
        @DisplayName("an outer μ preserves its outer flag when the σ is pushed through")
        void preservesOuterFlag() {
            var mu = unnest("items", true, rel("R"));
            RelNode result = apply(select(gt("region", "0"), mu));

            assertThat(result).isNode(UnnestNode.class);
            assertThat(((UnnestNode) result).outer()).isTrue();
            assertThat(fired(OptimizationCode.NEST_002)).isTrue();
        }

        @Test
        @DisplayName("a stack of independent selections all push through one μ")
        void stackedSelectionsPush() {
            var mu = unnest("items", rel("R"));
            RelNode result = apply(select(gt("a", "0"),
                    select(gt("b", "0"), mu)));

            assertThat(result).isNode(UnnestNode.class);
            var inner = ((UnnestNode) result).input();
            assertThat(inner).isNode(SelectionNode.class);                 // σ a
            assertThat(((SelectionNode) inner).input()).isNode(SelectionNode.class); // σ b
            assertThat(ctx.recordsFor(OptimizationCode.NEST_002)).hasSize(2);
        }

        @Test
        @DisplayName("a predicate that references the unnested column is kept above μ")
        void keepsPredicateReferencingUnnestColumn() {
            var node = select(gt("items", "0"), unnest("items", rel("R")));
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_002)).isFalse();
        }

        @Test
        @DisplayName("a predicate that references the ordinality column is kept above μ")
        void keepsPredicateReferencingOrdinality() {
            var mu = unnest("items", false, Optional.of("pos"), rel("R"));
            var node = select(gt("pos", "1"), mu);
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_002)).isFalse();
        }

        @Test
        @DisplayName("a predicate reading a field of the unnested column is kept above μ")
        void keepsPredicateReadingIntoUnnestColumn() {
            // Above the μ, `items.qty` is a field of one element. Below it, `items` is
            // still the array and the path resolves to nothing — so pushing this turns
            // the predicate UNKNOWN for every row and the query returns none. Reading
            // only the qualifier part of the name yields `qty`, which does not look like
            // a reference to `items` at all, which is how this used to be permitted.
            var node = select(gt("items.qty", "0"), unnest("items", rel("R")));
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_002)).isFalse();
        }

        @Test
        @DisplayName("a predicate reading deeper into the unnested column is kept above μ")
        void keepsPredicateReadingDeepIntoUnnestColumn() {
            var node = select(gt("items.price.net", "0"), unnest("items", rel("R")));
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_002)).isFalse();
        }

        @Test
        @DisplayName("a predicate reading a field of the ordinality column is kept above μ")
        void keepsPredicateReadingIntoOrdinality() {
            var mu = unnest("items", false, Optional.of("pos"), rel("R"));
            var node = select(gt("pos.depth", "1"), mu);
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_002)).isFalse();
        }

        @Test
        @DisplayName("a path into another column still pushes")
        void pushesPathIntoAnUnrelatedColumn() {
            // The guard is about the unnested column, not about dotted names: a path
            // into a column μ does not touch commutes with the explode as any other
            // reference does.
            var mu = unnest("items", rel("R"));
            RelNode result = apply(select(gt("address.zone", "0"), mu));

            assertThat(result).isNode(UnnestNode.class);
            assertThat(fired(OptimizationCode.NEST_002)).isTrue();
        }
    }

    // =========================================================================
    // NEST-003 — projection pushed below unnest
    // =========================================================================

    @Nested
    @DisplayName("NEST-003 — column-pruning π pushed below μ")
    class ProjectionThroughUnnest {

        @Test
        @DisplayName("π a, c (μ c (R)) → μ c (π a, c (R)) when c is among bare columns")
        void pushesColumnPruningProjection() {
            var mu = unnest("items", rel("R"));
            RelNode result = apply(project(attrs("region", "items"), mu));

            assertThat(result).isNode(UnnestNode.class);
            var pushed = (UnnestNode) result;
            assertThat(pushed.column()).isEqualTo("items");
            assertThat(pushed.input()).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) pushed.input()).input()).isSameAs(mu.input());
            assertThat(fired(OptimizationCode.NEST_003)).isTrue();
        }

        @Test
        @DisplayName("a projection that drops the unnested column is kept above μ")
        void keepsProjectionDroppingUnnestColumn() {
            var node = project(attrs("region"), unnest("items", rel("R")));
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_003)).isFalse();
        }

        @Test
        @DisplayName("a projection carrying a computed expression is not pushed")
        void keepsExpressionProjection() {
            var attrs = List.of(
                    projected(attr("items")),
                    projected(unary(attr("region"))));
            var node = project(attrs, unnest("items", rel("R")));
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_003)).isFalse();
        }

        @Test
        @DisplayName("a projection with an aliased column is not pushed (alias guard)")
        void keepsAliasedProjection() {
            var attrs = List.of(
                    projected(attr("items")),
                    projected(attr("region"),"r"));
            var node = project(attrs, unnest("items", rel("R")));
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_003)).isFalse();
        }

        @Test
        @DisplayName("a projection below a μ WITH ORDINALITY is not pushed")
        void keepsProjectionWithOrdinality() {
            var mu = unnest("items", false, Optional.of("pos"), rel("R"));
            var node = project(attrs("items", "region"), mu);
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_003)).isFalse();
        }

        @Test
        @DisplayName("a projection reading a field of the unnested column is kept above μ")
        void keepsProjectionReadingIntoUnnestColumn() {
            // `items.qty` reads a field of the element, which exists only above the μ.
            // The unnested column is projected here too, so the rule's own precondition
            // is met — this guard is what stops the push, and it has to be its own test
            // rather than the σ one, since there a reference to the unnested column is
            // what permits the rewrite rather than what forbids it.
            var node = project(attrs("items.qty", "items"), unnest("items", rel("R")));
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired(OptimizationCode.NEST_003)).isFalse();
        }

        @Test
        @DisplayName("a projection carrying a path into another column still pushes")
        void pushesProjectionWithPathIntoAnUnrelatedColumn() {
            var mu = unnest("items", rel("R"));
            RelNode result = apply(project(attrs("address.zone", "items"), mu));

            assertThat(result).isNode(UnnestNode.class);
            assertThat(fired(OptimizationCode.NEST_003)).isTrue();
        }
    }

    // =========================================================================
    // No nest/unnest pattern
    // =========================================================================

    @Nested
    @DisplayName("trees without a nest/unnest pattern are unchanged")
    class NoOp {

        @Test
        @DisplayName("a plain selection over a relation is returned unchanged")
        void plainTreeUnchanged() {
            var node = select(gt("x", "0"), rel("R"));
            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx.isEmpty()).isTrue();
        }
    }
}

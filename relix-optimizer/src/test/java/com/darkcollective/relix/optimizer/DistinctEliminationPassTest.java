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
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.CoverNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.cost.DistinctnessSource;
import com.darkcollective.relix.cost.StatisticsDistinctnessSource;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.RelationStatistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("DISTINCT elimination — DIST-001 / DIST-002")
final class DistinctEliminationPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = OptimizerFixtures.context(); }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private RelNode apply(RelNode node) {
        return DistinctEliminationPass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private boolean fired() {
        return !ctx.recordsFor(OptimizationCode.DIST_001).isEmpty();
    }

    private static AggregationNode grouped() {
        return groupBy(List.of("region"),
                List.of(AggregateFunction.simple(AggregateOperator.SUM, "amount")), rel("R"));
    }

    private static Predicate pred() {
        return cmp(attr("x"),
                ComparisonOperator.GREATER, num("0"));
    }

    // =========================================================================
    // DIST-001 — fires
    // =========================================================================

    @Nested
    @DisplayName("DIST-001 — redundant δ removed")
    class Fires {

        @Test
        @DisplayName("δ(γ …) → γ … (aggregation output is already distinct)")
        void distinctOverAggregation() {
            var agg = grouped();
            assertThat(apply(distinct(agg))).isSameAs(agg);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("δ(A ∪ B) → A ∪ B (set union is already distinct)")
        void distinctOverUnion() {
            var union = union(rel("A"), rel("B"));
            assertThat(apply(distinct(union))).isSameAs(union);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("δ(σ(γ …)) → σ(γ …) (distinctness carried through selection)")
        void distinctOverSelectionOverAggregation() {
            var inner = select(pred(), grouped());
            assertThat(apply(distinct(inner))).isSameAs(inner);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("δ(δ(δ(R))) collapses to a single δ(R) over the base relation")
        void nestedDistinctsCollapse() {
            var base = rel("R");
            var result = apply(distinct(distinct(distinct(base))));
            assertThat(result).isNode(DistinctNode.class);
            assertThat(((DistinctNode) result).input()).isSameAs(base);
            // two of the three δ were redundant
            assertThat(ctx.recordsFor(OptimizationCode.DIST_001)).hasSize(2);
        }

        @Test
        @DisplayName("a redundant δ buried under another operator is still removed")
        void redundantDistinctUnderSelection() {
            // σ(δ(γ …))  →  σ(γ …)
            var agg = grouped();
            var result = apply(select(pred(), distinct(agg)));
            assertThat(result).isNode(SelectionNode.class);
            assertThat(((SelectionNode) result).input()).isSameAs(agg);
            assertThat(fired()).isTrue();
        }
    }

    // =========================================================================
    // DIST-001 — no-op
    // =========================================================================

    @Nested
    @DisplayName("DIST-001 — does not fire")
    class NoOp {

        @Test
        @DisplayName("δ over a bare base relation is kept (rows may duplicate)")
        void distinctOverBaseRelation() {
            var node = distinct(rel("R"));
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("δ over a projection is kept (projection may introduce duplicates)")
        void distinctOverProjection() {
            var proj = project(
                    List.of(projected(attr("region"))),
                    rel("R"));
            var node = distinct(proj);
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a tree with no δ is returned unchanged")
        void noDistinct() {
            var node = grouped();
            assertThat(apply(node)).isSameAs(node);
            assertThat(fired()).isFalse();
        }
    }

    // =========================================================================
    // DIST-001 — leaf distinctness via DistinctnessSource (ADR-0008)
    // =========================================================================

    @Nested
    @DisplayName("DIST-001 — leaf distinctness from the source")
    class LeafDistinctness {

        @Test
        @DisplayName("δ over a leaf the source marks duplicate-free is removed")
        void distinctOverDuplicateFreeLeafRemoved() {
            DistinctnessSource src = name -> name.equals("R");   // e.g. a duplicate-free generator
            var ctx2 = OptimizerFixtures.context(src);

            RelNode result = DistinctEliminationPass.apply(
                    distinct(rel("R")), "Q", SchemaAnnotations.empty(), ctx2);

            assertThat(result).isEqualTo(rel("R"));
            assertThat(ctx2.recordsFor(OptimizationCode.DIST_001)).isNotEmpty();
        }

        @Test
        @DisplayName("δ over a bare leaf is kept when no source marks it distinct")
        void distinctOverBareLeafKept() {
            RelNode d = distinct(rel("R"));
            assertThat(apply(d)).isSameAs(d);     // default ctx → DistinctnessSource.NONE
            assertThat(fired()).isFalse();
        }
    }

    // =========================================================================
    // DIST-001 — leaf distinctness from a declared key (#525)
    // =========================================================================

    @Nested
    @DisplayName("DIST-001 — base-relation keys make a leaf duplicate-free")
    class KeyedLeafDistinctness {

        /** Statistics for {@code name} carrying {@code keys} and a row count. */
        private static DistinctnessSource keyed(String name, List<List<String>> keys) {
            var stats = new RelationStatistics(OptionalLong.of(500L), Map.of(), keys);
            return new StatisticsDistinctnessSource(
                    r -> r.equals(name) ? Optional.of(stats) : Optional.empty());
        }

        private static OptimizationContext contextWith(DistinctnessSource src) {
            return OptimizerFixtures.context(src);
        }

        @Test
        @DisplayName("δ over a primary-keyed relation is removed")
        void distinctOverPrimaryKeyedRelationRemoved() {
            var ctx2 = contextWith(keyed("Orders", List.of(List.of("order_id"))));

            RelNode result = DistinctEliminationPass.apply(
                    distinct(rel("Orders")), "Q", SchemaAnnotations.empty(), ctx2);

            assertThat(result).isEqualTo(rel("Orders"));
            assertThat(ctx2.recordsFor(OptimizationCode.DIST_001)).isNotEmpty();
        }

        @Test
        @DisplayName("δ over a relation with statistics but NO key is kept")
        void distinctOverUnkeyedRelationKept() {
            var ctx2 = contextWith(keyed("Events", List.of()));
            RelNode d = distinct(rel("Events"));

            RelNode result = DistinctEliminationPass.apply(
                    d, "Q", SchemaAnnotations.empty(), ctx2);

            assertThat(result).isSameAs(d);
            assertThat(ctx2.recordsFor(OptimizationCode.DIST_001)).isEmpty();
        }

        @Test
        @DisplayName("a keyed relation and a distinct generator are both honoured")
        void generatorAndKeyedRelationCompose() {
            DistinctnessSource generator = name -> name.equals("Range");
            var ctx2 = contextWith(DistinctnessSource.anyOf(
                    generator, keyed("Orders", List.of(List.of("order_id")))));

            assertThat(DistinctEliminationPass.apply(distinct(rel("Range")),
                    "Q", SchemaAnnotations.empty(), ctx2)).isEqualTo(rel("Range"));
            assertThat(DistinctEliminationPass.apply(distinct(rel("Orders")),
                    "Q", SchemaAnnotations.empty(), ctx2)).isEqualTo(rel("Orders"));
            assertThat(ctx2.recordsFor(OptimizationCode.DIST_001)).hasSize(2);
        }
    }

    // ─── FIX — recurses into base and step but never pushes across ──────────

    @Nested
    @DisplayName("COVER — DIST-001 eliminates δ over COVER (output is whole-row distinct)")
    class CoverDistinctness {

        @Test
        @DisplayName("δ(COVER t(R)) → COVER t(R): greedy output is already whole-row distinct")
        void distinctOverCoverEliminated() {
            RelNode inner = rel("Params");
            RelNode cover = cover(2, inner);
            RelNode d = distinct(cover);

            RelNode result = apply(d);

            // δ(COVER) → COVER (already distinct — every selected row covers a new tuple)
            assertThat(result).isNode(CoverNode.class);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("DIST-001 recurses into COVER's input — δ inside input is optimised")
        void dist001RecursesIntoCoverInput() {
            // δ(∪) is distinct, so the δ wrapping the union inside the COVER input fires.
            RelNode union = union(rel("A"), rel("B"));
            RelNode d = distinct(union);
            RelNode cover = cover(2, d);

            RelNode result = apply(cover);

            // The COVER is unchanged but its input's δ(∪) is eliminated.
            assertThat(result).isNode(CoverNode.class);
            assertThat(((CoverNode) result).input()).isNode(UnionNode.class);
        }
    }

    @Nested
    @DisplayName("FIX — DIST-001 recurses into base and step")
    class FixpointRecursion {

        @Test
        @DisplayName("δ inside a FIX step is eliminated when step is already distinct")
        void distinctInsideFixStepEliminated() {
            // δ over a ∪ is duplicate-free (DIST-001 fires inside the step)
            RelNode union = union(rel("Edges"), recRef("R"));
            RelNode step = distinct(union);
            RelNode fix = fixpoint("R", rel("Edges"), step);

            RelNode result = apply(fix);

            FixpointNode rewritten = assertThat(result).asNode(FixpointNode.class);
            // δ(∪) eliminated → step is the plain ∪
            assertThat(rewritten.step()).isNode(UnionNode.class);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("δ wrapping a whole FIX is eliminated — FIX produces a SET")
        void distinctOverFixEliminated() {
            RelNode fix = fixpoint("R", rel("Edges"),
                    union(rel("Edges"), recRef("R")));
            RelNode d = distinct(fix);

            RelNode result = apply(d);

            // δ(FIX …) → FIX … (already a set)
            assertThat(result).isNode(FixpointNode.class);
            assertThat(fired()).isTrue();
        }
    }

    // =========================================================================
    // DIST-002 — a δ the aggregation above it makes irrelevant (#541)
    // =========================================================================

    @Nested
    @DisplayName("DIST-002 — δ below a duplicate-insensitive γ")
    class DistinctBelowAggregation {

        private boolean fired002() {
            return !ctx.recordsFor(OptimizationCode.DIST_002).isEmpty();
        }

        /** {@code γ region, <aggs> (δ (R))}. */
        private static AggregationNode over(List<AggregateFunction> aggregates, RelNode input) {
            return groupBy(List.of("region"), aggregates, input);
        }

        private static AggregateFunction agg(AggregateOperator op, String column) {
            return AggregateFunction.simple(op, column);
        }

        @Test
        @DisplayName("γ region, MIN(amount) (δ R) → γ region, MIN(amount) (R)")
        void minIsDuplicateInsensitive() {
            var base = rel("R");
            var node = over(List.of(agg(AggregateOperator.MIN, "amount")), distinct(base));

            var result = (AggregationNode) apply(node);

            assertThat(result.input()).isSameAs(base);
            assertThat(fired002()).isTrue();
        }

        @Test
        @DisplayName("MAX is duplicate-insensitive too")
        void maxIsDuplicateInsensitive() {
            var base = rel("R");
            var node = over(List.of(agg(AggregateOperator.MAX, "amount")), distinct(base));

            assertThat(((AggregationNode) apply(node)).input()).isSameAs(base);
            assertThat(fired002()).isTrue();
        }

        @Test
        @DisplayName("a γ with no aggregates at all is pure grouping — the δ goes")
        void pureGroupingIsDuplicateInsensitive() {
            var base = rel("R");
            var node = over(List.of(), distinct(base));

            assertThat(((AggregationNode) apply(node)).input()).isSameAs(base);
            assertThat(fired002()).isTrue();
        }

        @Test
        @DisplayName("an ungrouped γ (null grouping keys) still drops the δ")
        void ungroupedAggregation() {
            var base = rel("R");
            var node = groupBy((List<String>) null,
                    List.of(agg(AggregateOperator.MIN, "amount")), distinct(base));

            assertThat(((AggregationNode) apply(node)).input()).isSameAs(base);
            assertThat(fired002()).isTrue();
        }

        @Test
        @DisplayName("MIN over an expression is fine — the argument is deterministic")
        void deterministicExpressionArgument() {
            var base = rel("R");
            var node = over(List.of(AggregateFunction.of(AggregateOperator.MIN,
                            func("Abs",attr("delta")))),
                    distinct(base));

            assertThat(((AggregationNode) apply(node)).input()).isSameAs(base);
            assertThat(fired002()).isTrue();
        }

        @Test
        @DisplayName("σ / π / ρ / τ between the γ and the δ are looked through")
        void transparentChainLookedThrough() {
            for (java.util.function.UnaryOperator<RelNode> wrap : List.<java.util.function.UnaryOperator<RelNode>>of(
                    in -> select(pred(), in),
                    in -> project(List.of(projected(
                            attr("region"))), in),
                    in -> rename("V", List.of(), in),
                    in -> sort(List.of(asc("region")), in))) {
                ctx = OptimizerFixtures.context();
                var base = rel("R");
                var node = over(List.of(agg(AggregateOperator.MIN, "amount")),
                        wrap.apply(distinct(base)));

                var result = (AggregationNode) apply(node);

                assertThat(result.input().children().getFirst())
                        .as("the δ below %s should have been removed",
                                node.input().getClass().getSimpleName())
                        .isSameAs(base);
                assertThat(fired002()).isTrue();
            }
        }

        @Test
        @DisplayName("a λ between the γ and the δ blocks the rule — it counts rows")
        void limitIsNotTransparent() {
            var node = over(List.of(agg(AggregateOperator.MIN, "amount")),
                    limit(10L, distinct(rel("R"))));

            assertThat(apply(node)).isSameAs(node);
            assertThat(fired002()).isFalse();
        }

        @Test
        @DisplayName("COUNT counts duplicates — the δ stays")
        void countBlocksTheRule() {
            var node = over(List.of(agg(AggregateOperator.COUNT, "amount")),
                    distinct(rel("R")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(fired002()).isFalse();
        }

        @Test
        @DisplayName("SUM / AVG / COLLECT / ARGMAX / ARGMIN all block the rule")
        void multiplicityReadingAggregatesBlockTheRule() {
            List<AggregateFunction> blocking = List.of(
                    agg(AggregateOperator.SUM, "amount"),
                    agg(AggregateOperator.AVG, "amount"),
                    agg(AggregateOperator.COLLECT, "amount"),
                    AggregateFunction.arg(AggregateOperator.ARGMAX, "amount", "cust"),
                    AggregateFunction.arg(AggregateOperator.ARGMIN, "amount", "cust"));
            for (AggregateFunction blocker : blocking) {
                ctx = OptimizerFixtures.context();
                var node = over(List.of(agg(AggregateOperator.MIN, "amount"), blocker),
                        distinct(rel("R")));

                assertThat(apply(node))
                        .as("%s reads multiplicity, so the δ must stay", blocker.operator())
                        .isSameAs(node);
                assertThat(fired002()).isFalse();
            }
        }

        @Test
        @DisplayName("MIN over a non-deterministic argument blocks the rule (Rand())")
        void nonDeterministicArgumentBlocksTheRule() {
            var node = over(List.of(AggregateFunction.of(AggregateOperator.MIN,
                    func("Rand"))), distinct(rel("R")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(fired002()).isFalse();
        }

        @Test
        @DisplayName("a non-deterministic grouping key blocks the rule too")
        void nonDeterministicGroupingKeyBlocksTheRule() {
            var node = groupByKeys(
                    List.of(GroupingKey.of(func("NOW"))),
                    List.of(agg(AggregateOperator.MIN, "amount")),
                    distinct(rel("R")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(fired002()).isFalse();
        }

        @Test
        @DisplayName("a γ with no δ beneath it is left alone")
        void noDistinctBelow() {
            var node = over(List.of(agg(AggregateOperator.MIN, "amount")), rel("R"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(fired002()).isFalse();
        }
    }
}

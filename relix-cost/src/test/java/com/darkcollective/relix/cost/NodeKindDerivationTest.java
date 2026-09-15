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

import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.MaterializationMode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelNodeCorpus;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two {@code relix-cost} derivations, exercised for every {@link RelNode} kind.
 *
 * <p>{@link PropertyDeriver#deriveAt} and {@link BoundednessChecker}'s label switch are
 * exhaustive, so the compiler makes you write an arm for a new kind — but it does not make
 * you write the <em>right</em> arm, and for roughly twenty kinds each no test executed the
 * one that was written.  That gap is not cosmetic in either case:
 *
 * <ul>
 *   <li><strong>Distinctness deletes work.</strong>  {@code DIST-001} reads
 *       {@link RelationProperties#isDuplicateFree()} to remove a {@code δ}.  A kind
 *       classified into the wrong arm is a wrong-rows bug — the optimizer drops a
 *       deduplication the query needed.  The arms here are wide multi-label groups
 *       ({@code case SelectionNode _, SortNode _, LimitNode _, …}), which is exactly the
 *       shape where a new kind gets appended to whichever group looks closest.</li>
 *   <li><strong>Boundedness now refuses queries.</strong>  {@code QueryExecutor.requireBounded}
 *       reads {@link PropertyDeriver#boundedness} on the root and throws.  An arm that
 *       wrongly reports BOUNDED means the guard silently does not fire; one that wrongly
 *       reports UNBOUNDED refuses a legal query.</li>
 * </ul>
 *
 * <h2>Behaviour is classified, not tabulated</h2>
 *
 * <p>Rather than pinning a literal answer per kind — which pins today's behaviour without
 * saying what it should be — each kind is derived <em>twice</em>, under sources that give
 * opposite answers for the leaf, and classified by how it responds.  "Preserves its input's
 * distinctness" is then a property the test can state, and a kind that silently moves from
 * preserving to establishing fails even though neither individual answer looks wrong.
 */
@DisplayName("PropertyDeriver / BoundednessChecker — every RelNode kind")
final class NodeKindDerivationTest {

    /** Says every leaf is duplicate-free. */
    private static final DistinctnessSource ALL_DISTINCT = name -> true;

    /** Says no leaf is duplicate-free — the conservative default. */
    private static final DistinctnessSource NONE_DISTINCT = name -> false;

    /** Says every leaf is finite. */
    private static final BoundednessSource ALL_BOUNDED = name -> Boundedness.BOUNDED;

    /** Says every leaf is endless — a generator like {@code Naturals}. */
    private static final BoundednessSource ALL_UNBOUNDED = name -> Boundedness.UNBOUNDED;

    // =========================================================================
    // Distinctness
    // =========================================================================

    /** How a kind's distinctness responds to its input's. */
    private enum Distinctness {
        /** Duplicate-free whatever the input is — δ, the set operations, FIX, COVER, … */
        ESTABLISHES,
        /** Carries candidate keys whatever the input is — γ and ∀ key on their grouping columns. */
        ESTABLISHES_KEY,
        /** Duplicate-free exactly when the input is — σ, τ, λ, the left-filter joins, … */
        PRESERVES,
        /** Never duplicate-free — π, μ, the row-multiplying joins, the column-appending operators. */
        BREAKS
    }

    private static Distinctness classifyDistinctness(RelNode node) {
        RelationProperties withDistinct = PropertyDeriver.derive(node, ALL_DISTINCT);
        RelationProperties withoutDistinct = PropertyDeriver.derive(node, NONE_DISTINCT);
        if (withDistinct.wholeRowDistinct() && withoutDistinct.wholeRowDistinct()) {
            return Distinctness.ESTABLISHES;
        }
        if (!withDistinct.keys().isEmpty() && !withoutDistinct.keys().isEmpty()) {
            return Distinctness.ESTABLISHES_KEY;
        }
        if (withDistinct.isDuplicateFree() && !withoutDistinct.isDuplicateFree()) {
            return Distinctness.PRESERVES;
        }
        return Distinctness.BREAKS;
    }

    /**
     * What each kind does to distinctness.
     *
     * <p>This is the table a new operator has to add a row to, which is the point: the
     * alternative is inheriting whatever the nearest multi-label arm happens to say, in a
     * derivation whose consumers delete a {@code δ} on the strength of it.
     */
    private static final Map<String, Distinctness> DISTINCTNESS = Map.ofEntries(
            // Leaves: a relation is duplicate-free iff the source says so; the rest are
            // opaque or trivially distinct.
            Map.entry("RelationNode", Distinctness.PRESERVES),
            Map.entry("RelationFunctionCall", Distinctness.BREAKS),
            Map.entry("TruthRelationNode", Distinctness.ESTABLISHES),
            Map.entry("EmptyRelationNode", Distinctness.ESTABLISHES),
            Map.entry("RecursiveRefNode", Distinctness.BREAKS),

            // Row-subset operators keep whatever the input had.
            Map.entry("SelectionNode", Distinctness.PRESERVES),
            Map.entry("SortNode", Distinctness.PRESERVES),
            Map.entry("LimitNode", Distinctness.PRESERVES),
            Map.entry("SampleNode", Distinctness.PRESERVES),
            Map.entry("ReservoirSampleNode", Distinctness.PRESERVES),
            Map.entry("TopKNode", Distinctness.PRESERVES),
            Map.entry("OptimizeNode", Distinctness.PRESERVES),
            Map.entry("RenameNode", Distinctness.PRESERVES),

            // Left-filter joins emit a row-subset of their left input.
            Map.entry("SemiJoinNode", Distinctness.PRESERVES),
            Map.entry("AntiJoinNode", Distinctness.PRESERVES),
            Map.entry("PairwiseUniversalNode", Distinctness.PRESERVES),
            // AS-OF emits exactly one (nearest) right row per left probe.
            Map.entry("AsOfJoinNode", Distinctness.PRESERVES),

            // Set-producing operators are duplicate-free by construction.
            Map.entry("DistinctNode", Distinctness.ESTABLISHES),
            Map.entry("UnionNode", Distinctness.ESTABLISHES),
            Map.entry("OuterUnionNode", Distinctness.ESTABLISHES),
            Map.entry("IntersectionNode", Distinctness.ESTABLISHES),
            Map.entry("DifferenceNode", Distinctness.ESTABLISHES),
            Map.entry("SymmetricDifferenceNode", Distinctness.ESTABLISHES),
            Map.entry("DivisionNode", Distinctness.ESTABLISHES),
            Map.entry("ClosureNode", Distinctness.ESTABLISHES),
            Map.entry("ClusterNode", Distinctness.ESTABLISHES),
            Map.entry("PathNode", Distinctness.ESTABLISHES),
            Map.entry("TraceNode", Distinctness.ESTABLISHES),
            Map.entry("FixpointNode", Distinctness.ESTABLISHES),
            Map.entry("CoverNode", Distinctness.ESTABLISHES),

            // Grouping establishes a candidate key from the grouping columns.
            Map.entry("AggregationNode", Distinctness.ESTABLISHES_KEY),
            Map.entry("UniversalNode", Distinctness.ESTABLISHES_KEY),

            // Everything else multiplies rows, appends a column, or depends on an
            // untracked body — conservatively no distinctness at all.
            Map.entry("ProjectionNode", Distinctness.BREAKS),
            Map.entry("UnnestNode", Distinctness.BREAKS),
            Map.entry("SolveNode", Distinctness.BREAKS),
            Map.entry("DownsampleNode", Distinctness.BREAKS),
            Map.entry("LateralJoinNode", Distinctness.BREAKS),
            Map.entry("WindowNode", Distinctness.BREAKS),
            Map.entry("SessionizeNode", Distinctness.BREAKS),
            Map.entry("UnpivotNode", Distinctness.BREAKS),
            Map.entry("PivotNode", Distinctness.BREAKS),
            Map.entry("TreeNode", Distinctness.BREAKS),
            Map.entry("WhyNode", Distinctness.BREAKS),
            Map.entry("NaturalJoinNode", Distinctness.BREAKS),
            Map.entry("ThetaJoinNode", Distinctness.BREAKS),
            Map.entry("LeftOuterJoinNode", Distinctness.BREAKS),
            Map.entry("RightOuterJoinNode", Distinctness.BREAKS),
            Map.entry("FullOuterJoinNode", Distinctness.BREAKS),
            Map.entry("IntervalJoinNode", Distinctness.BREAKS),
            Map.entry("ProductNode", Distinctness.BREAKS),
            Map.entry("CompositionNode", Distinctness.BREAKS),
            Map.entry("UnionAllNode", Distinctness.BREAKS));

    @Nested
    @DisplayName("distinctness")
    final class DistinctnessDerivation {

        @Test
        @DisplayName("the table names every kind, and only kinds that exist")
        void tableIsComplete() {
            List<String> kinds = RelNodeCorpus.everyKind().stream()
                    .map(n -> n.getClass().getSimpleName()).sorted().toList();
            assertThat(DISTINCTNESS.keySet())
                    .as("every kind must state what it does to distinctness")
                    .containsExactlyInAnyOrderElementsOf(kinds);
        }

        @TestFactory
        @DisplayName("each kind behaves as the table says")
        Stream<DynamicTest> eachKindMatchesItsRow() {
            return RelNodeCorpus.everyKind().stream().map(node -> {
                String kind = node.getClass().getSimpleName();
                return DynamicTest.dynamicTest(kind, () ->
                        assertThat(classifyDistinctness(node))
                                .as("%s: derived twice, under sources that disagree about "
                                    + "the leaf", kind)
                                .isEqualTo(DISTINCTNESS.get(kind)));
            });
        }

        @Test
        @DisplayName("a grouping key set is the aggregation's candidate key")
        void groupingKeysBecomeTheCandidateKey() {
            // The classification above proves ESTABLISHES_KEY; this pins which key, since
            // DIST-002 and the planner both read the columns rather than the fact.
            RelNode grouped = AstBuilders.groupBy(AstBuilders.cols("dept", "region"),
                    List.of(AstBuilders.agg(AggregateOperator.SUM, "amount")),
                    RelNodeCorpus.LEFT);
            assertThat(PropertyDeriver.derive(grouped, NONE_DISTINCT).keys())
                    .containsExactly(java.util.Set.of("dept", "region"));
        }
    }

    // =========================================================================
    // Boundedness
    // =========================================================================

    @Nested
    @DisplayName("boundedness")
    final class BoundednessDerivation {

        /**
         * Kinds that bound an endless input rather than passing its boundedness up.
         *
         * <p>λ is the rescue by definition, and a bounded-frame window buffers at most
         * {@code n} rows per partition (ADR-0015 §D5) so it streams the same way.  Every
         * other kind is contagious.  A leaf carrying a pushed produce bound (GEN-001) is
         * finite too, and is checked separately below since it is a property of the node's
         * own component rather than of its kind.
         */
        private final List<String> alwaysBounded = List.of("LimitNode");

        @TestFactory
        @DisplayName("boundedness is contagious, except where a kind bounds its input")
        Stream<DynamicTest> contagiousExceptWhereBounded() {
            return RelNodeCorpus.everyKind().stream().map(node -> {
                String kind = node.getClass().getSimpleName();
                return DynamicTest.dynamicTest(kind, () -> {
                    Boundedness overUnbounded = PropertyDeriver.boundedness(node, ALL_UNBOUNDED);
                    if (alwaysBounded.contains(kind)) {
                        assertThat(overUnbounded)
                                .as("%s bounds its input, so it is finite over an endless one",
                                        kind)
                                .isEqualTo(Boundedness.BOUNDED);
                        return;
                    }
                    if (node instanceof RelationNode leaf && leaf.produceBound().isPresent()) {
                        // GEN-001's pushed bound is the rescue, like λ — see
                        // produceBoundMakesALeafFinite for the paired case.
                        assertThat(overUnbounded).isEqualTo(Boundedness.BOUNDED);
                        return;
                    }
                    if (node.children().isEmpty() && !(node instanceof RelationNode)) {
                        // A childless non-relation leaf reads no source, so it is finite.
                        assertThat(overUnbounded).isEqualTo(Boundedness.BOUNDED);
                        return;
                    }
                    assertThat(overUnbounded)
                            .as("%s must pass an endless input's boundedness up — an "
                                + "operator that quietly reports BOUNDED disarms the "
                                + "collect guard", kind)
                            .isEqualTo(Boundedness.UNBOUNDED);
                });
            });
        }

        @TestFactory
        @DisplayName("no kind invents unboundedness over finite leaves")
        Stream<DynamicTest> noKindInventsUnboundedness() {
            // PropertyDeriver states this as its contagious-only invariant, and
            // BoundednessChecker's javadoc relies on it ("a tree whose leaves are all
            // BOUNDED never trips this check"). Stated once, checked for all 52.
            return RelNodeCorpus.everyKind().stream().map(node -> {
                String kind = node.getClass().getSimpleName();
                return DynamicTest.dynamicTest(kind, () ->
                        assertThat(PropertyDeriver.boundedness(node, ALL_BOUNDED))
                                .as("%s reported UNBOUNDED over finite leaves", kind)
                                .isEqualTo(Boundedness.BOUNDED));
            });
        }

        @Test
        @DisplayName("a bounded-frame window streams an endless input")
        void boundedFrameWindowIsFinite() {
            // The corpus carries the cumulative (blocking) window, so the frame that makes
            // the difference is stated here rather than left to a shape nothing exercises.
            RelNode bounded = AstBuilders.window(
                    new WindowFunction.AggregateWindow(AggregateOperator.SUM,
                            AstBuilders.attr("amount")),
                    AstBuilders.cols("dept"), List.of(AstBuilders.asc("day")),
                    new WindowFrame.BoundedFrame(10), "rolling", RelNodeCorpus.LEFT);

            assertThat(PropertyDeriver.boundedness(bounded, ALL_UNBOUNDED))
                    .isEqualTo(Boundedness.BOUNDED);
        }

        @Test
        @DisplayName("a pushed produce bound makes an unbounded generator finite")
        void produceBoundMakesALeafFinite() {
            RelNode bounded = RelNodeCorpus.everyKind().stream()
                    .filter(RelationNode.class::isInstance)
                    .findFirst().orElseThrow();

            assertThat(((RelationNode) bounded).produceBound()).isPresent();
            assertThat(PropertyDeriver.boundedness(bounded, ALL_UNBOUNDED))
                    .as("GEN-001's pushed bound is the rescue, like λ")
                    .isEqualTo(Boundedness.BOUNDED);
            assertThat(PropertyDeriver.boundedness(rel("Naturals"), ALL_UNBOUNDED))
                    .as("without one, the same leaf is endless")
                    .isEqualTo(Boundedness.UNBOUNDED);
        }
    }

    // =========================================================================
    // The diagnostic
    // =========================================================================

    @Nested
    @DisplayName("BoundednessChecker")
    final class Diagnostics {

        @TestFactory
        @DisplayName("every blocking kind names itself in the error, not its class")
        Stream<DynamicTest> everyBlockingKindHasALabel() {
            // label() ends in a default arm returning getClass().getSimpleName(), so a
            // blocking operator with no arm produces "cannot materialise unbounded relation
            // for blocking operator AsOfJoinNode" — a Java type name in a message a user is
            // meant to act on. Which kinds block is not a judgement call: it is
            // materializationMode() != STREAM, so the two can be checked against each other.
            return RelNodeCorpus.everyKind().stream()
                    .filter(node -> node.materializationMode() != MaterializationMode.STREAM)
                    .map(node -> DynamicTest.dynamicTest(node.getClass().getSimpleName(), () -> {
                        List<String> errors = BoundednessChecker.check(node, ALL_UNBOUNDED);
                        assertThat(errors)
                                .as("%s blocks over an endless input, so it must be flagged",
                                        node.getClass().getSimpleName())
                                .isNotEmpty();
                        assertThat(errors.get(0))
                                .as("%s must carry a user-facing label, not its class name",
                                        node.getClass().getSimpleName())
                                .doesNotContain(node.getClass().getSimpleName());
                    }));
        }

        @TestFactory
        @DisplayName("no streaming kind is flagged")
        Stream<DynamicTest> streamingKindsAreNeverFlagged() {
            return RelNodeCorpus.everyKind().stream()
                    .filter(node -> node.materializationMode() == MaterializationMode.STREAM)
                    .map(node -> DynamicTest.dynamicTest(node.getClass().getSimpleName(), () ->
                            assertThat(BoundednessChecker.check(node, ALL_UNBOUNDED))
                                    .as("%s streams, so it may consume an endless input",
                                            node.getClass().getSimpleName())
                                    .isEmpty()));
        }

        @Test
        @DisplayName("a finite tree is never flagged, whatever it is built from")
        void finiteTreesAreNeverFlagged() {
            List<String> flagged = new ArrayList<>();
            for (RelNode node : RelNodeCorpus.everyKind()) {
                if (!BoundednessChecker.check(node, ALL_BOUNDED).isEmpty()) {
                    flagged.add(node.getClass().getSimpleName());
                }
            }
            assertThat(flagged)
                    .as("the check must fire only on what is provably endless")
                    .isEmpty();
        }

        @Test
        @DisplayName("UNKNOWN is not flagged — only what is provably unbounded")
        void unknownIsNotFlagged() {
            BoundednessSource unknown = name -> Boundedness.UNKNOWN;
            RelNode sorted = AstBuilders.sort(
                    List.of(AstBuilders.sortKey(AstBuilders.attr("a"), SortDirection.ASC)),
                    RelNodeCorpus.LEFT);

            assertThat(BoundednessChecker.check(sorted, unknown)).isEmpty();
            assertThat(BoundednessChecker.check(sorted, ALL_UNBOUNDED)).isNotEmpty();
        }
    }
}

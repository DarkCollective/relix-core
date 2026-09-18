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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.plan.SharedSubexpressions.Sharing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SharedSubexpressions} — which sub-expressions a tree would
 * evaluate more than once.
 *
 * <p>Driven with hand-built trees rather than parsed scripts, because the question is
 * purely structural and the interesting cases (a shared sub-expression nested inside
 * another, an unshareable node with a shareable one beneath it) are fiddly to write
 * as a script and obvious to write as a tree.
 */
@DisplayName("SharedSubexpressions — counting evaluation sites, not occurrences")
final class SharedSubexpressionsTest {

    /** Everything is shareable unless a test says otherwise. */
    private static final java.util.function.Predicate<RelNode> ANYTHING = unused -> true;

    /**
     * Stands in for the planner's gate where a test needs one: a sub-expression that
     * reads a recursive relation cannot be shared. The planner's own rule is
     * bound-aware — a self-contained {@code FIX} is shareable, because the references
     * its step makes are its own — and that distinction belongs to {@code Planner},
     * which is where it is tested.
     */
    private static final java.util.function.Predicate<RelNode> NOT_RECURSIVE =
            node -> !readsRecursion(node);

    private static boolean readsRecursion(RelNode node) {
        return node instanceof RecursiveRefNode
                || node.children().stream().anyMatch(SharedSubexpressionsTest::readsRecursion);
    }

    private static RelNode r() {
        return AstBuilders.rel("R");
    }

    /** σ id > 0 (R) — a distinct sub-expression from a bare R. */
    private static RelNode sigma() {
        return AstBuilders.select(
                AstBuilders.cmp(AstBuilders.attr("id"),
                        com.darkcollective.relix.ast.ComparisonOperator.GREATER,
                        AstBuilders.num("0")),
                r());
    }

    private static RelNode union(RelNode left, RelNode right) {
        return AstBuilders.union(left, right);
    }

    private static String digest(RelNode node) {
        return com.darkcollective.relix.ast.AstEquivalence.digest(node);
    }

    /** The sharing a sub-expression read from {@code n} ordinary places is worth. */
    private static Sharing sites(int n) {
        return Sharing.sites(n);
    }

    /** The sharing a sub-expression inside a fixpoint step is worth, written {@code n} times. */
    private static Sharing perRound(int n) {
        return new Sharing(n, true);
    }

    @Nested
    @DisplayName("counting")
    class Counting {

        @Test
        @DisplayName("a sub-expression read twice is reported with two sites")
        void twoOccurrencesIsTwoSites() {
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    union(sigma(), sigma()), ANYTHING);

            assertThat(sites).containsEntry(digest(sigma()), sites(2));
        }

        @Test
        @DisplayName("a sub-expression read once is not reported at all")
        void oneOccurrenceIsNotReported() {
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    union(sigma(), AstBuilders.rel("S")), ANYTHING);

            assertThat(sites).isEmpty();
        }

        @Test
        @DisplayName("a leaf inside one branch and standing alone in another is two sites")
        void aLeafReachedTwoWaysIsTwoSites() {
            // σ(R) ∪ R reads R twice — once through the selection, once directly.
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    union(sigma(), r()), ANYTHING);

            assertThat(sites).containsExactly(Map.entry(digest(r()), sites(2)));
        }

        @Test
        @DisplayName("occurrences at different source positions still match")
        void locationsDoNotSeparateOccurrences() {
            // The builders default every node's location to UNKNOWN, so build one side
            // through a path that produces a structurally identical but distinct tree.
            RelNode left = sigma();
            RelNode right = sigma();
            assertThat(left).isNotSameAs(right);

            assertThat(SharedSubexpressions.detect(union(left, right), ANYTHING))
                    .containsEntry(digest(left), sites(2));
        }
    }

    @Nested
    @DisplayName("maximality — the inner copy is reached through the outer sharing")
    class Maximality {

        @Test
        @DisplayName("only the outermost repeated sub-expression is reported")
        void innerRepeatIsNotDoubleCounted() {
            // δ(σ(R)) ∪ δ(σ(R)): the σ appears twice, but sharing the δ above it already
            // reduces the σ to one evaluation. Buffering it too would hold the same rows
            // a second time for a single reader.
            RelNode outer = AstBuilders.distinct(sigma());
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    union(outer, AstBuilders.distinct(sigma())), ANYTHING);

            assertThat(sites).containsExactly(Map.entry(digest(outer), sites(2)));
        }

        @Test
        @DisplayName("an inner sub-expression read outside the outer one is reported too")
        void innerRepeatOutsideIsCounted() {
            // (δ(σ) ∪ δ(σ)) ∪ σ — now the σ has a reader of its own, so both are shared
            // and the δ's spool reads the σ's. That is what a plan DAG is.
            RelNode outer = AstBuilders.distinct(sigma());
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    union(union(outer, AstBuilders.distinct(sigma())), sigma()), ANYTHING);

            assertThat(sites)
                    .containsEntry(digest(outer), sites(2))
                    .containsEntry(digest(sigma()), sites(2));
        }
    }

    @Nested
    @DisplayName("unshareable nodes are walked through, not counted")
    class Unshareable {

        @Test
        @DisplayName("a shareable sub-expression under an unshareable one is still found")
        void sharesBeneathAnUnshareableNode() {
            // The δ stands in for an operator whose two evaluations may differ: it is
            // never counted, but the walk still descends both copies, so the σ that
            // feeds both of them is found.
            RelNode outer = AstBuilders.distinct(sigma());
            String outerDigest = digest(outer);
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    union(outer, AstBuilders.distinct(sigma())),
                    node -> !digest(node).equals(outerDigest));

            assertThat(sites)
                    .doesNotContainKey(outerDigest)
                    .containsEntry(digest(sigma()), sites(2));
        }

        @Test
        @DisplayName("the shareable test is asked once per distinct sub-expression")
        void shareableIsMemoizedPerDigest() {
            java.util.List<String> asked = new java.util.ArrayList<>();
            SharedSubexpressions.detect(union(sigma(), sigma()), node -> {
                asked.add(digest(node));
                return true;
            });

            assertThat(asked).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("a fixpoint step is a site per round")
    class PerRound {

        /** {@code FIX T (base, step)}. */
        private static RelNode fix(RelNode base, RelNode step) {
            return AstBuilders.fixpoint("T", base, step);
        }

        /** The recursive body's usual shape: the recursion, unioned with something invariant. */
        private static RelNode stepOver(RelNode invariant) {
            return union(AstBuilders.recRef("T"), invariant);
        }

        @Test
        @DisplayName("an invariant sub-expression written once inside a step is shared")
        void invariantInAStepIsSharedThoughWrittenOnce() {
            // Semi-naïve evaluation re-runs the step every round, so one occurrence is
            // one evaluation per iteration — the thing CSE could not see, because one
            // site is one site.
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    fix(r(), stepOver(sigma())), NOT_RECURSIVE);

            assertThat(sites).containsEntry(digest(sigma()), perRound(1));
        }

        @Test
        @DisplayName("the base is not per-round — it seeds the iteration once")
        void theBaseIsEvaluatedOnce() {
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    fix(sigma(), stepOver(AstBuilders.rel("S"))), NOT_RECURSIVE);

            assertThat(sites)
                    .doesNotContainKey(digest(sigma()))
                    .containsEntry(digest(AstBuilders.rel("S")), perRound(1));
        }

        @Test
        @DisplayName("a sub-expression reading the recursion is walked through, not shared")
        void theRecursiveSideIsNotShared() {
            RelNode step = stepOver(sigma());
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    fix(r(), step), NOT_RECURSIVE);

            assertThat(sites)
                    .as("the union reads T, whose rows change with every iteration")
                    .doesNotContainKey(digest(step));
        }

        @Test
        @DisplayName("only the largest invariant sub-expression in a step is reported")
        void theInnerInvariantIsReachedThroughTheOuterOne() {
            RelNode outer = AstBuilders.distinct(sigma());
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    fix(r(), stepOver(outer)), NOT_RECURSIVE);

            assertThat(sites)
                    .containsEntry(digest(outer), perRound(1))
                    .doesNotContainKey(digest(sigma()));
        }

        @Test
        @DisplayName("a sub-expression read outside the FIX and inside its step is per-round")
        void perRoundOutranksAPlainCount() {
            // Two sites, and one of them recurs — the stronger claim is the one the
            // planner's event should make.
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    union(sigma(), fix(r(), stepOver(sigma()))), NOT_RECURSIVE);

            assertThat(sites).containsEntry(digest(sigma()), perRound(2));
        }

        @Test
        @DisplayName("an inner fixpoint's base is per-round when an outer step runs it")
        void anInnerBaseIsPerRoundToo() {
            // The inner FIX seeds itself once per outer round, so its base is invariant
            // work repeated as often as the outer step is.
            RelNode inner = AstBuilders.fixpoint("U", sigma(), union(AstBuilders.recRef("U"), r()));
            Map<String, Sharing> sites = SharedSubexpressions.detect(
                    fix(r(), union(AstBuilders.recRef("T"), inner)), NOT_RECURSIVE);

            assertThat(sites).containsEntry(digest(sigma()), perRound(1));
        }
    }
}

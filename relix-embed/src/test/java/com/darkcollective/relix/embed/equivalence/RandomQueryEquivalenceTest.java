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
package com.darkcollective.relix.embed.equivalence;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.semantic.QueryGenerator;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertEquivalent;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQLancer-style differential testing of the optimizer (#722): draw random query trees
 * over fixed inline relations, run each optimized and unoptimized, and diff the rows.
 *
 * <p>The hand-written equivalence suites exercise one rule at a time, which is what they
 * are for — {@link OptimizerRuleCoverageTest} holds every rule code to having one. What
 * no hand-written case reaches is <em>combinations</em>: a σ pushed through a join whose
 * inputs a projection has already narrowed. #555 is that shape, a rule correct alone that
 * permuted the ordered output heading and broke the consumers above it.
 *
 * <p>Generation is {@link QueryGenerator}; the comparison and its classification are
 * {@link RandomQueryEquivalence}.
 *
 * <h2>The run is seeded, bounded, and asserts what it did</h2>
 *
 * A search that explores something different on every build is not a test — a failure
 * nobody can reproduce is a rumour. The seed is a named constant, so this run draws the
 * same trees on every machine forever; changing the seed is how you explore, and a seed
 * that finds something gets its shrunk witness pinned as its own case in
 * {@link Witnesses} rather than left for the build to rediscover.
 *
 * <p>Three properties are asserted about the run itself, because a generated-input test
 * that generates the wrong inputs passes while testing nothing:
 *
 * <ul>
 *   <li><b>Most draws are real queries.</b> A high skip rate means the budget drained into
 *       trees that never ran.</li>
 *   <li><b>The optimizer actually fired.</b> Trees no rule matches prove nothing, however
 *       many of them there are.</li>
 *   <li><b>The firing spans the phases.</b> One rule firing on every tree would satisfy
 *       the previous check and still explore a single rewrite.</li>
 * </ul>
 */
@DisplayName("Random query generation — optimized and unoptimized answers agree (#722)")
final class RandomQueryEquivalenceTest {

    // ─── the search's parameters, all named ─────────────────────────────────────

    /** Fixed so the run is reproducible; change it deliberately to explore. */
    private static final long SEED = 20_260_910L;

    /** Trees per run — a few seconds, which is the budget a gate can afford. */
    private static final int TREES = 400;

    /** Operators above the leaves. Deep enough for a rule to interact with another. */
    private static final int MAX_DEPTH = 5;

    /**
     * The floor on how many distinct rules the run must fire. This seed fires 32, across
     * every phase of the pipeline; the floor sits well below that so an unrelated change
     * to a rule's matching conditions does not turn this into a failure, and well above
     * zero so a generator that stopped producing interesting shapes would be caught.
     */
    private static final int DISTINCT_RULES_EXPECTED = 20;

    // ─── inline fixtures ────────────────────────────────────────────────────────

    /**
     * Relations chosen for what they let the generator build, not for realism.
     *
     * <p>{@code Left} and {@code Same} share a heading, so a set operation has two
     * genuinely different sides to combine rather than one relation against itself.
     * {@code Right} shares {@code id} and {@code cat} with them, so a natural join has
     * something to match on. {@code Left} repeats a row, so δ and ⊎ differ. Values
     * overlap only partly, so a join drops rows.
     *
     * <p><b>Empty cells are NULLs</b>, which is how a CSV says so and how an inline table
     * does too. This block said the opposite — that an inline table cannot spell one, so
     * three-valued logic was reached only on a draw that happened to build an outer join —
     * while {@code NullSemanticsAgreementTest} in this same repository spells one with an
     * empty cell and says so in its own javadoc. A NULL in a base relation is a different
     * thing from one an outer join padded in: it can be a join key, a grouping key and a
     * sort key, and those are three rules the search never reached.
     *
     * <p>{@code Left} also carries {@code 20.0} against {@code Same}'s {@code 20}. One
     * number written two ways is a single value to a comparison and was, for a while, two
     * to δ — so a search over data where every number has one spelling cannot see the
     * difference between those two answers being right.
     */
    private static final String FIXTURES =
            "Left := [\n"
          + "| id | cat | qty  |\n"
          + "|----|-----|------|\n"
          + "| 1  | a   | 10   |\n"
          + "| 2  | a   | 20   |\n"
          + "| 2  | a   | 20   |\n"
          + "| 3  | b   | 20.0 |\n"
          + "| 4  | c   | 5    |\n"
          + "| 5  |     | 15   |\n"
          + "|    | d   |      |\n"
          + "| 6  | \u00df   | 15   |\n"
          + "| 7  | \u0041\u0301  | 15   |\n"
          + "];\n"
          + "Right := [\n"
          + "| id | cat | score |\n"
          + "|----|-----|-------|\n"
          + "| 1  | a   | 7     |\n"
          + "| 3  | b   | 2     |\n"
          + "| 6  | d   | 9     |\n"
          + "|    | a   |       |\n"
          + "];\n"
          + "Same := [\n"
          + "| id | cat | qty |\n"
          + "|----|-----|-----|\n"
          + "| 3  | b   | 20  |\n"
          + "| 7  | e   | 1   |\n"
          + "|    |     |     |\n"
          + "];\n"
          // An inline cell infers as NUMBER or STRING and nothing else, so a column of
          // any other type has to be computed. This is the whole of the type system the
          // search could otherwise never reach: a BOOLEAN to compare and group by, a
          // TIMESTAMP and a DATE to order and truncate, and an ANY that holds two runtime
          // kinds at once — which is the case a declared type does not settle.
          + "Stamps := [\n"
          + "| sid | when                 | flag |\n"
          + "|-----|----------------------|------|\n"
          + "| 1   | 2024-01-15T08:30:00Z | y    |\n"
          + "| 2   | 2024-02-29T00:00:00Z | n    |\n"
          + "| 3   | 2024-01-15T08:30:00Z | y    |\n"
          + "| 4   |                      |      |\n"
          + "];\n"
          + "Typed := { \u03c0 sid, to_timestamp(when) \u2192 at, "
          + "to_date('2024-03-01') \u2192 day, IIf(flag = 'y', true, false) \u2192 ok, "
          + "IIf(sid = 1, 'one', 1) \u2192 either (Stamps) };\n"
          // A struct and an array column. The generator writes no predicate over either —
          // it asks the schema for a scalar type and a nested column has none — but they
          // travel through π, δ, ρ and the set operations, which is where a nested value
          // is compared, hashed and re-labelled. Those are the mechanisms that decide
          // whether two rows are one row, and until now no generated row carried a value
          // they could not read as a number or a string.
          + "Shapes := { \u03c0 sid, {kind: flag, at: when} \u2192 box, "
          + "[sid, sid] \u2192 pair (Stamps) };\n"
          // The two cardinalities with rules of their own. A one-row relation is where
          // PERCENT_RANK is fixed and where a δ cannot tell a working deduplication from
          // a broken one; an empty one is what the EMPTY-00x propagation rules are about,
          // and the place γ and ∀ are documented to differ — a scalar COUNT of nothing is
          // a row saying 0, while a grouped one is no rows at all.
          + "Solo := [\n"
          + "| id | cat | qty |\n"
          + "|----|-----|-----|\n"
          + "| 8  | f   | 3   |\n"
          + "];\n"
          + "None := { \u03c3 id > 1000 (Solo) };\n";

    private static final List<String> RELATIONS =
            List.of("Left", "Right", "Same", "Typed", "Shapes", "Solo", "None");

    // =========================================================================
    // The search
    // =========================================================================

    @Test
    @DisplayName("400 generated trees: every rewrite preserves the answer")
    void optimizationPreservesTheAnswer() {
        SemanticModel m = model(FIXTURES);
        QueryGenerator generator = new QueryGenerator(new Random(SEED), m, RELATIONS);

        Set<OptimizationCode> fired = new LinkedHashSet<>();
        int skipped = 0;
        for (int i = 0; i < TREES; i++) {
            RelNode tree = generator.generate(MAX_DEPTH).node();
            switch (RandomQueryEquivalence.check(tree, m)) {
                case RandomQueryEquivalence.Outcome.Agreed a -> fired.addAll(a.fired());
                case RandomQueryEquivalence.Outcome.Skipped ignored -> skipped++;
                case RandomQueryEquivalence.Outcome.Disagreed d -> failWith(d, m);
            }
        }

        assertThat(skipped)
                .as("draws that never ran — a high count means the budget drained into "
                    + "trees the generator could not build, not into testing the optimizer")
                .isLessThan(TREES / 4);
        assertThat(fired)
                .as("rules fired across the run — trees no rule matches prove nothing")
                .isNotEmpty();
        assertThat(fired.size())
                .as("distinct rules fired: %s", fired)
                .isGreaterThanOrEqualTo(DISTINCT_RULES_EXPECTED);
    }

    /**
     * Shrinks the counterexample before reporting it, and reports the reduced tree as
     * Relix text. {@code PrettyPrinter} round-trips through the parser, so a witness
     * printed here can be pasted straight into a case in {@link Witnesses}.
     */
    private static void failWith(RandomQueryEquivalence.Outcome.Disagreed found, SemanticModel m) {
        RelNode smallest = RandomQueryEquivalence.shrink(found.tree(),
                candidate -> RandomQueryEquivalence.check(candidate, m)
                             instanceof RandomQueryEquivalence.Outcome.Disagreed);
        String detail = RandomQueryEquivalence.check(smallest, m)
                        instanceof RandomQueryEquivalence.Outcome.Disagreed d
                ? d.detail() : found.detail();
        throw new AssertionError(
                "optimization changed the answer\n\n"
                + "shrunk witness:\n  " + smallest.prettyPrint() + "\n\n"
                + detail + "\n\n"
                + "as drawn (seed " + SEED + "):\n  " + found.tree().prettyPrint() + '\n');
    }

    // =========================================================================
    // Pinned witnesses
    // =========================================================================

    /**
     * Counterexamples this search has found, each pinned as its own case.
     *
     * <p>A witness belongs here rather than in the search because the search is a
     * <em>budget</em>: it draws what this seed draws, and a later change to the weights,
     * the depth or the fixtures would silently stop drawing the tree that found the
     * defect. Pinning it turns a discovery into a regression test that no longer depends
     * on the search — which is what {@code TESTING.md} asks of a generated-input test.
     */
    @Nested
    @DisplayName("Witnesses — counterexamples found by the search, pinned")
    class Witnesses {

        /**
         * The first run of this search, at {@link #SEED}, drew a twelve-node tree whose
         * optimized form returned five rows where the original returned four, and
         * {@link RandomQueryEquivalence#shrink} reduced it to {@code δ (Left ∩ Left)}.
         *
         * <p>{@code DIST-001} was only the messenger. Every other component held ∩ to be
         * a set operation — the reference page, {@code MaterializationMode.SET} on the
         * node, and {@code PropertyDeriver}, which is what let the rule drop the δ — while
         * {@code SetOpExecutor} made a set of the <em>right</em> input only and streamed
         * the left through untouched. So a duplicate on the left survived, and removing a
         * δ that was doing the deduplication changed the answer.
         *
         * <p>What makes it a good witness is that it needs the optimizer to <em>show</em>
         * but not to cause: {@code SetOperationsTest} now asserts the executor's own
         * behaviour directly, with no rewrite involved.
         */
        @Test
        @DisplayName("δ (A ∩ A) — ∩ deduplicates its left input, so removing the δ is sound")
        void intersectionIsASetOperationOnBothSides() {
            assertEquivalent(FIXTURES + "query { δ (Left ∩ Left) };\n",
                    OptimizationCode.DIST_001);
        }

        /** The same defect through {@code −}, which shared the executor arm's shape. */
        @Test
        @DisplayName("δ (A − B) — − deduplicates its left input too")
        void differenceIsASetOperationOnBothSides() {
            assertEquivalent(FIXTURES + "query { δ (Left − Same) };\n",
                    OptimizationCode.DIST_001);
        }

        /**
         * A later sweep over other seeds, once the generator learned to build closures,
         * drew a σ on a {@code CLOSURE} endpoint whose optimized form returned nothing.
         *
         * <p>{@code CLOSURE-001} folds {@code σ from = c} into a single-source
         * reachability from {@code c}, which is sound only if the graph's idea of node
         * identity agrees with the σ's idea of equality. At the time it did not: a node is
         * a {@code Value}, and two numbers were then the same node only when their scales
         * matched, while the σ compared numerically. Seeding from the literal therefore
         * found whichever spelling happened to match it and dropped the rest — against a
         * column holding {@code 20.0}, all of them. {@code NumberValue} has since been made
         * to compare numerically, so that half of the disagreement is gone; this case still
         * holds the other half, below.
         *
         * <p>The executor now seeds from the graph's own nodes that the bound selects,
         * and emits their values rather than the literal's. Both halves matter: the
         * unbounded closure builds its pairs out of row values, so a bounded run emitting
         * the literal would answer {@code (20|5)} where the σ above answers
         * {@code (20.0|5)}.
         */
        @Test
        @DisplayName("σ on a CLOSURE endpoint — the bound selects nodes, not a spelling")
        void closureEndpointBoundSelectsByValueNotBySpelling() {
            assertEquivalent(
                    "Edges := [\n| a | b |\n|---|---|\n| 20.0 | 5 |\n];\n"
                            + "query { σ a = 20 (CLOSURE a, b (Edges)) };\n",
                    OptimizationCode.CLOSURE_001);
        }

        /** The mirror through the target endpoint, which reverses the adjacency. */
        @Test
        @DisplayName("σ on the target endpoint resolves the same way")
        void closureTargetBoundSelectsByValueNotBySpelling() {
            assertEquivalent(
                    "Edges := [\n| a | b |\n|---|---|\n| 5 | 20.0 |\n];\n"
                            + "query { σ b = 20 (CLOSURE a, b (Edges)) };\n",
                    OptimizationCode.CLOSURE_001);
        }

        @Test
        @DisplayName("the search's own reproduction path adopts no reduction that stops failing")
        void shrinkingReportsOnlyTreesThatStillFail() {
            SemanticModel m = model(FIXTURES);
            QueryGenerator generator = new QueryGenerator(new Random(SEED), m, RELATIONS);
            RelNode tree = generator.generate(MAX_DEPTH).node();

            RelNode shrunk = RandomQueryEquivalence.shrink(tree, unused -> false);
            assertThat(shrunk).isSameAs(tree);
        }
    }
}

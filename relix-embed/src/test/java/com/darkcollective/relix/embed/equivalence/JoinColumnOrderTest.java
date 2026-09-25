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

import com.darkcollective.relix.optimizer.internal.OptimizationResult;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for #555 — <strong>no logical rewrite may permute a join's output
 * columns.</strong>
 *
 * <p>{@code JOIN-003} used to swap a commutative join's inputs to put the cheaper source
 * on the left.  {@code ⨝}, {@code ⋈} and {@code ×} are commutative, but only <em>up to a
 * permutation of the output columns</em> — and a Relix schema is <em>ordered</em>, with
 * consumers that match by position.  The swap therefore did not merely reorder the
 * result's columns; downstream it changed which rows came back:
 *
 * <ul>
 *   <li>the <b>positional form of ρ</b> assigns names by index, so the rename bound
 *       names to the wrong columns — {@code big_name} came back holding the right-hand
 *       relation's value;</li>
 *   <li><b>{@code −} and {@code ∩}</b> compare <em>whole rows</em> positionally, so a
 *       permuted branch matched nothing: a difference returned the row it should have
 *       subtracted, and an intersection lost the row it should have kept.</li>
 * </ul>
 *
 * <p>The rule was removed rather than repaired, because the planner already made the
 * same decision without the permutation: {@code Planner.buildSide} picks the hash build
 * side for {@code INNER}/{@code NATURAL}/{@code FULL_OUTER}/{@code PRODUCT} from the
 * identical two-level cost model (row counts, then {@code CostTier}).
 *
 * <p>These tests are execution-level and deliberately compare rows <strong>in
 * order</strong>, columns included: the assertion has to be sensitive to exactly the
 * thing that was wrong.  A future cost-based rewrite that reorders join inputs — the
 * enumerator T3.3/#546 would want one — has to preserve the output column order to pass
 * here.
 */
@DisplayName("Join column order is stable under optimization (#555)")
final class JoinColumnOrderTest {

    private static final String RELATIONS =
            "Big := [\n"
          + "| bid | bname |\n"
          + "|-----|-------|\n"
          + "| 1   | one   |\n"
          + "| 2   | two   |\n"
          + "| 3   | three |\n"
          + "];\n"
          + "Small := [\n"
          + "| sid | sname |\n"
          + "|-----|-------|\n"
          + "| 1   | uno   |\n"
          + "];\n";

    /** A row shaped exactly like the join's output, for the set-operation cases. */
    private static final String MANUAL =
            "Manual := [\n"
          + "| bid | bname | sid | sname |\n"
          + "|-----|-------|-----|-------|\n"
          + "| 1   | one   | 1   | uno   |\n"
          + "];\n";

    /**
     * Asserts the optimizer leaves the answer <em>and its column order</em> alone.
     *
     * <p>{@code Small} is the smaller relation, so any rule that reorders join inputs on
     * cost fires here.  The comparison is positional in both dimensions — same rows, in
     * the same order, with the same columns in the same order.
     */
    private static void assertStable(String src) {
        SemanticModel m = model(src);
        OptimizationResult r = OptimizerEquivalence.optimizeFirst(m);
        List<String> optimized   = OptimizerEquivalence.run(r.optimized(), m);
        List<String> unoptimized = OptimizerEquivalence.run(r.original(), m);
        assertThat(optimized)
                .as("optimizing must not permute a join's output columns (#555)")
                .containsExactlyElementsOf(unoptimized);
    }

    @Nested
    @DisplayName("the cases that returned wrong rows")
    class WrongRows {

        @Test
        @DisplayName("a positional ρ over a join keeps each name on its own column")
        void positionalRenameOverAJoin() {
            // Was: big_name came back as "uno" — the *right* relation's value — because
            // the rename is arity- and order-bound and the optimizer moved the data out
            // from under the names.
            assertStable(RELATIONS
                  + "query { ρ R (big_id, big_name, small_id, small_name)\n"
                  + "        (Big ⨝ Big.bid = Small.sid Small) };\n");
        }

        @Test
        @DisplayName("a difference over a join still subtracts the row it matches")
        void differenceOverAJoin() {
            // Was: 0 rows unoptimized, 1 row optimized — `−` compares whole rows
            // positionally, and a permuted branch matches nothing.
            assertStable(RELATIONS + MANUAL
                  + "query { (Big ⨝ Big.bid = Small.sid Small) − Manual };\n");
        }

        @Test
        @DisplayName("an intersection over a join still keeps the row it matches")
        void intersectionOverAJoin() {
            // Was: 1 row unoptimized, 0 rows optimized.
            assertStable(RELATIONS + MANUAL
                  + "query { (Big ⨝ Big.bid = Small.sid Small) ∩ Manual };\n");
        }
    }

    @Nested
    @DisplayName("the visible-but-harmless case, and the other commutative operators")
    class ColumnOrder {

        @Test
        @DisplayName("a bare theta join's columns come back in the order the query wrote them")
        void thetaJoinColumnOrder() {
            assertStable(RELATIONS
                  + "query { Big ⨝ Big.bid = Small.sid Small };\n");
        }

        @Test
        @DisplayName("a natural join's columns are stable too")
        void naturalJoinColumnOrder() {
            assertStable(
                    "Big := [\n"
                  + "| k | bname |\n"
                  + "|---|-------|\n"
                  + "| 1 | one   |\n"
                  + "| 2 | two   |\n"
                  + "| 3 | three |\n"
                  + "];\n"
                  + "Small := [\n"
                  + "| k | sname |\n"
                  + "|---|-------|\n"
                  + "| 1 | uno   |\n"
                  + "];\n"
                  + "query { Big ⋈ Small };\n");
        }

        @Test
        @DisplayName("a cross product's columns are stable too")
        void productColumnOrder() {
            assertStable(RELATIONS + "query { Big × Small };\n");
        }
    }
}

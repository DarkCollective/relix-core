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

import com.darkcollective.relix.optimizer.OptimizationCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertEquivalent;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertNotFiredButEqual;

/**
 * Execution-level <em>equivalence</em> tests for the selection-pushdown / SIP rewrites
 * (ADR-0020 / ADR-0021): a constrained query must produce <strong>identical results</strong>
 * whether or not the optimizer folds the selection into the expensive operator. The
 * optimizer-unit tests prove the <em>shape</em> of each rewrite; these prove the
 * rewrite is answer-preserving when actually executed.
 *
 * <p>The harness lives in {@link OptimizerEquivalence}; the textbook σ/λ arms use the
 * same one from {@link RewriteEquivalenceTest}.
 */
@DisplayName("SIP / magic-sets pushdown — execution equivalence (optimized == unoptimized)")
final class SipEquivalenceTest {

    // ─── inline fixtures ────────────────────────────────────────────────────────

    /** A bill-of-materials with two independent assemblies so the pushdown prunes work. */
    private static final String CONTAINS =
            "Contains := [\n"
          + "| assembly | part   |\n"
          + "|----------|--------|\n"
          + "| Bike     | Frame  |\n"
          + "| Bike     | Wheel  |\n"
          + "| Wheel    | Rim    |\n"
          + "| Wheel    | Tyre   |\n"
          + "| Tyre     | Tube   |\n"
          + "| Car      | Engine |\n"
          + "| Engine   | Piston |\n"
          + "];\n";

    /** The BOM explosion as general recursion; `assembly` is the frozen (carried) key. */
    private static final String EXPLOSION =
            "Explosion := { FIX BOM (\n"
          + "  Contains,\n"
          + "  π assembly, sub → part (BOM ⋈ ρ Edge(part, sub) (Contains))\n"
          + ") };\n";

    // =========================================================================
    // FIX-001 — magic sets into general recursion (ADR-0021, #334)
    // =========================================================================

    @Nested
    @DisplayName("FIX-001 — selection over a frozen column pushed into the recursion")
    class Fix {

        @Test
        @DisplayName("σ on the frozen key: single-assembly explosion equals the filtered full explosion")
        void frozenKeyEquivalent() {
            assertEquivalent(
                    CONTAINS + EXPLOSION
                  + "query { σ assembly = \"Bike\" (Explosion) };\n",
                    OptimizationCode.FIX_001);
        }

        @Test
        @DisplayName("frozen + non-frozen conjunction: pushed part + residual part agree with baseline")
        void mixedConjunctionEquivalent() {
            assertEquivalent(
                    CONTAINS + EXPLOSION
                  + "query { σ assembly = \"Bike\" ∧ part = \"Tube\" (Explosion) };\n",
                    OptimizationCode.FIX_001);
        }

        @Test
        @DisplayName("inlined FIX (no view): the rewrite fires and preserves the answer")
        void inlinedFixEquivalent() {
            assertEquivalent(
                    CONTAINS
                  + "query { σ assembly = \"Bike\" (FIX BOM (\n"
                  + "    Contains,\n"
                  + "    π assembly, sub → part (BOM ⋈ ρ Edge(part, sub) (Contains))\n"
                  + "  )) };\n",
                    OptimizationCode.FIX_001);
        }

        @Test
        @DisplayName("σ on a produced (non-frozen) column does not fire, answer still correct")
        void producedColumnDoesNotFire() {
            assertNotFiredButEqual(
                    CONTAINS + EXPLOSION
                  + "query { σ part = \"Tube\" (Explosion) };\n",
                    OptimizationCode.FIX_001);
        }
    }

    // =========================================================================
    // CLOSURE-001 / TRACE-001 — the fixed-shape SIP instances (ADR-0020)
    // =========================================================================

    private static final String EDGES =
            "Edges := [\n"
          + "| src | dst |\n"
          + "|-----|-----|\n"
          + "| A   | B   |\n"
          + "| B   | C   |\n"
          + "| C   | D   |\n"
          + "| X   | Y   |\n"
          + "| Y   | Z   |\n"
          + "];\n";

    @Nested
    @DisplayName("CLOSURE-001 / TRACE-001 — endpoint pushdown preserves the answer")
    class ClosureAndTrace {

        @Test
        @DisplayName("σ src = c (CLOSURE): single-source reachability equals filtered all-pairs")
        void closureSourceEquivalent() {
            assertEquivalent(
                    EDGES
                  + "query { σ src = \"A\" (CLOSURE src, dst (Edges)) };\n",
                    OptimizationCode.CLOSURE_001);
        }

        @Test
        @DisplayName("σ src = c ∧ dst = c (CLOSURE): single-pair check equals filtered all-pairs")
        void closurePairEquivalent() {
            assertEquivalent(
                    EDGES
                  + "query { σ src = \"A\" ∧ dst = \"D\" (CLOSURE src, dst (Edges)) };\n",
                    OptimizationCode.CLOSURE_001);
        }

        /**
         * A bound whose type the graph's nodes cannot be <em>ordered</em> against.
         *
         * <p>Without the pushdown this is an ordinary σ: a number compared with a string
         * is not equal, no row survives, and the answer is no rows. With it, the endpoint
         * is matched against each node — and endpoint equality was asking
         * {@code compare(a, b) == 0}, whose comparison raises on a pair it cannot order
         * rather than reporting that they are different. So the rewrite turned an empty
         * answer into a failed query.
         *
         * <p>{@code ValueComparator.equal} is the method whose javadoc says why: it never
         * throws, because a match test should skip a mismatched pair rather than abort,
         * and comparing against zero conflates <em>different</em> with
         * <em>unorderable</em>. The σ this bound came from had always used it.
         */
        @Test
        @DisplayName("σ src = c where c cannot be ordered against the nodes")
        void closureBoundOfAnotherTypeIsNotAMatchAndNotAnError() {
            assertEquivalent(
                    "Nodes := [\n"
                  + "| src | dst |\n"
                  + "|-----|-----|\n"
                  + "| 1   | 2   |\n"
                  + "| 2   | 3   |\n"
                  + "];\n"
                  + "query { σ src = \"zzz\" (CLOSURE src, dst (Nodes)) };\n",
                    OptimizationCode.CLOSURE_001);
        }

        @Test
        @DisplayName("σ from = c (TRACE): single-source optimal paths equal filtered all-pairs")
        void traceSourceEquivalent() {
            String weighted =
                    "WEdges := [\n"
                  + "| src | dst | w |\n"
                  + "|-----|-----|---|\n"
                  + "| A   | B   | 1 |\n"
                  + "| B   | C   | 2 |\n"
                  + "| A   | C   | 4 |\n"
                  + "| X   | Y   | 1 |\n"
                  + "];\n";
            assertEquivalent(
                    weighted
                  + "query { σ src = \"A\" (TRACE src, dst VIA w MINIMIZE AS route (WEdges)) };\n",
                    OptimizationCode.TRACE_001);
        }
    }
}

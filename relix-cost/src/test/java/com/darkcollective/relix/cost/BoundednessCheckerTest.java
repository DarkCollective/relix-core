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

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.WindowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.ast.Expr.plus;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BoundednessChecker — blocking operator over unbounded input")
final class BoundednessCheckerTest {

    /** The leaf named "Inf" is unbounded; every other leaf is bounded. */
    private static final BoundednessSource SRC =
            name -> "Inf".equals(name) ? Boundedness.UNBOUNDED : Boundedness.BOUNDED;

    private static RelationNode inf() { return rel("Inf"); }

    private static Predicate pred() {
        return cmp(attr("x"),
                ComparisonOperator.GREATER, num("0"));
    }

    private static AggregationNode groupBy(String key, RelNode in) {
        return AstBuilders.groupBy(List.of(key),
                List.of(AggregateFunction.simple(AggregateOperator.SUM, "amount")), in);
    }

    private static List<String> check(RelNode root) {
        return BoundednessChecker.check(root, SRC);
    }

    private static WindowNode window(WindowFrame frame, RelNode in) {
        return AstBuilders.window(
                new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("amount")),
                List.of("region"), List.of(asc("id")),
                frame, "w", in);
    }

    // =========================================================================
    // Positive — a blocking operator over an unbounded input is flagged
    // =========================================================================

    @Nested
    @DisplayName("flags a blocking operator over an unbounded input")
    class Flags {

        @Test
        @DisplayName("γ over an unbounded input is rejected with an actionable message")
        void aggregationOverUnbounded() {
            List<String> errors = check(groupBy("region", inf()));
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst())
                    .contains("unbounded")
                    .contains("γ")
                    .contains("add a bound");
        }

        @Test
        @DisplayName("τ (sort) over an unbounded input is rejected")
        void sortOverUnbounded() {
            assertThat(check(sort(List.of(asc("id")), inf())))
                    .singleElement().asString().contains("τ");
        }

        @Test
        @DisplayName("ω (WHY) over an unbounded input is rejected — it is blocking")
        void whyOverUnbounded() {
            assertThat(check(new com.darkcollective.relix.ast.WhyNode(inf())))
                    .singleElement().asString().contains("ω (WHY)");
        }

        @Test
        @DisplayName("a blocking operator over a chain that stays unbounded is rejected")
        void blockingOverUnboundedChain() {
            // γ over σ over an unbounded leaf — σ streams the unboundedness up to γ.
            assertThat(check(groupBy("region", select(pred(), inf())))).hasSize(1);
        }

        @Test
        @DisplayName("a binary set op flags each unbounded input")
        void setOpOverTwoUnbounded() {
            assertThat(check(union(inf(), inf()))).hasSize(2);
        }

        @Test
        @DisplayName("outer-union (⊔) flags each unbounded input")
        void outerUnionOverTwoUnbounded() {
            List<String> errors = check(outerUnion(inf(), inf()));
            assertThat(errors).hasSize(2);
            assertThat(errors).allSatisfy(e -> assertThat(e).contains("OUNION"));
        }

        @Test
        @DisplayName("CLUSTER over an unbounded input is rejected")
        void clusterOverUnbounded() {
            assertThat(check(cluster("src", "dst", "cid",inf())))
                    .singleElement().asString().contains("CLUSTER");
        }

        @Test
        @DisplayName("PATH over an unbounded input is rejected")
        void pathOverUnbounded() {
            assertThat(check(path("src", "dst", 1, 3, "depth",inf())))
                    .singleElement().asString().contains("PATH");
        }

        @Test
        @DisplayName("a cumulative window (OVER ALL ROWS) over an unbounded input is rejected")
        void cumulativeWindowOverUnbounded() {
            assertThat(check(window(new WindowFrame.CumulativeFrame(), inf())))
                    .singleElement().asString().contains("WINDOW");
        }
    }

    // =========================================================================
    // Negative — materialisation-safe trees produce no diagnostics
    // =========================================================================

    @Nested
    @DisplayName("does not flag materialisation-safe trees")
    class Safe {

        @Test
        @DisplayName("a blocking operator over a bounded input is fine")
        void blockingOverBounded() {
            assertThat(check(groupBy("region", rel("R")))).isEmpty();
            assertThat(check(union(rel("A"), rel("B")))).isEmpty();
            assertThat(check(sort(
                    List.of(asc("id")), rel("R")))).isEmpty();
        }

        @Test
        @DisplayName("λ below a blocking operator rescues it (the input becomes bounded)")
        void limitRescuesBlocking() {
            assertThat(check(groupBy("region", limit(100L, inf())))).isEmpty();
        }

        @Test
        @DisplayName("a streaming operator may consume an unbounded input — not flagged")
        void streamingOverUnbounded() {
            // σ, δ and an inner join are STREAM: they pipeline an unbounded input.
            assertThat(check(select(pred(), inf()))).isEmpty();
            assertThat(check(distinct(inf()))).isEmpty();
            assertThat(check(join(rel("R"), inf(), pred()))).isEmpty();
        }

        @Test
        @DisplayName("a bounded-frame window (OVER n ROWS) streams an unbounded input safely")
        void boundedWindowOverUnbounded() {
            assertThat(check(window(new WindowFrame.BoundedFrame(3), inf()))).isEmpty();
        }

        @Test
        @DisplayName("the all-bounded source never flags anything (production no-op)")
        void allBoundedNeverFlags() {
            // Even a γ over the "Inf" leaf is safe when every leaf is treated as bounded.
            assertThat(BoundednessChecker.check(groupBy("region", inf()),
                    BoundednessSource.ALL_BOUNDED)).isEmpty();
        }
    }

    // =========================================================================
    // General fixpoint (FIX) — blocking because it materialises a SET
    // =========================================================================

    @Nested
    @DisplayName("FIX (general recursion) — blocking over unbounded base")
    class FixpointBoundedness {

        private static RelNode fix(RelNode base) {
            return fixpoint("R", base,
                    union(base, recRef("R")));
        }

        @Test
        @DisplayName("FIX over an unbounded base is flagged — FIX is a blocking (SET) operator")
        void fixpointOverUnboundedFlagged() {
            List<String> errors = check(fix(inf()));
            assertThat(errors).hasSizeGreaterThanOrEqualTo(1);
            assertThat(errors.getFirst()).contains("FIX");
        }

        @Test
        @DisplayName("FIX over bounded input produces no boundedness errors")
        void fixpointOverBoundedIsSafe() {
            assertThat(check(fix(rel("R")))).isEmpty();
        }
    }

    // =========================================================================
    // A FIX that computes new values — the hole in the lattice (#874)
    // =========================================================================

    /**
     * {@code deriveBoundedness} is contagious-only by design: unboundedness travels up
     * from a leaf that declares it, and no operator invents any. A {@code FIX} whose step
     * <em>computes</em> a value breaks that, because it derives a row per round forever
     * from a base and a step that are both perfectly finite.
     *
     * <p>These tests pin the asymmetry rather than call it a defect, because it is not one
     * the lattice can fix: whether such a fixpoint is finite is undecidable, and typing it
     * {@code UNBOUNDED} would have {@code BoundednessChecker} refuse the {@code FIX} itself
     * (it is blocking), which rejects every value-inventing recursion outright. What covers
     * it is the runtime row cap, not this analysis. The point of recording it is that the
     * two relations below have the <em>same extent</em> and get opposite treatment.
     */
    @Nested
    @DisplayName("a FIX that computes new values escapes the lattice (#874)")
    class ValueInventingFixpoint {

        /**
         * ℕ written as a fixpoint — base {@code {0}}, step {@code n + 1} — which denotes
         * exactly what the unbounded leaf {@code Inf} denotes. Both inputs are bounded:
         * {@code Zero} is an ordinary finite leaf and the recursive reference is a leaf too.
         */
        private static RelNode counter() {
            return fixpoint("N", rel("Zero"),
                    project(List.of(projected(plus(attr("n"), num("1")), "m")), recRef("N")));
        }

        @Test
        @DisplayName("the generator is UNBOUNDED; the fixpoint denoting the same relation is BOUNDED")
        void sameExtentOppositeBoundedness() {
            assertThat(PropertyDeriver.boundedness(inf(), SRC)).isEqualTo(Boundedness.UNBOUNDED);
            assertThat(PropertyDeriver.boundedness(counter(), SRC)).isEqualTo(Boundedness.BOUNDED);
        }

        @Test
        @DisplayName("so a blocking γ is refused over the generator and allowed over the fixpoint")
        void blockingOperatorRefusedOverOneAllowedOverTheOther() {
            assertThat(check(groupBy("n", inf())))
                    .as("γ over a declared-unbounded leaf is refused")
                    .isNotEmpty();
            assertThat(check(groupBy("n", counter())))
                    .as("γ over a fixpoint of the same extent is allowed — it runs forever instead")
                    .isEmpty();
        }

        @Test
        @DisplayName("a λ below the fixpoint is not the rescue it is for a generator")
        void limitBelowDoesNotBoundTheDerivation() {
            // λ bounds the *base*, and the base was never the unbounded part: the rows come
            // from the step, which λ below the FIX does not reach. The lattice reports
            // BOUNDED either way, so this is not a difference it could ever express.
            RelNode boundedBase = fixpoint("N", limit(5, rel("Zero")),
                    project(List.of(projected(plus(attr("n"), num("1")), "m")), recRef("N")));
            assertThat(PropertyDeriver.boundedness(boundedBase, SRC))
                    .isEqualTo(Boundedness.BOUNDED);
        }
    }

    // =========================================================================
    // Streaming γ over ordered input (Phase C3, #58) — no longer blocks
    // =========================================================================

    @Nested
    @DisplayName("a γ over an input ordered by its grouping key streams (Phase C3)")
    class StreamingAggregate {

        private static SortNode sortedBy(String key, RelNode in) {
            return sort(List.of(asc(key)), in);
        }

        @Test
        @DisplayName("γ ordered by its grouping key is not itself flagged — only the τ below it")
        void aggregateOverMatchingOrderNotFlagged() {
            // γ region (τ region (Inf)): the τ delivers region-order, so γ aggregates in
            // a single streaming pass and is not flagged; only the τ (blocking over the
            // unbounded leaf) is.
            List<String> errors = check(groupBy("region", sortedBy("region", inf())));
            assertThat(errors).singleElement().asString().contains("τ");
        }

        @Test
        @DisplayName("γ whose input order does not match its grouping key still blocks")
        void aggregateOverWrongOrderStillBlocks() {
            // Ordered by id but grouping by region — groups are not contiguous, so γ
            // still buffers: both the γ and the τ are flagged.
            assertThat(check(groupBy("region", sortedBy("id", inf())))).hasSize(2);
        }
    }
}

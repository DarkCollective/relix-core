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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Selection-into-TRACE pushdown — TRACE-001 (ADR-0020 / #328)")
final class SelectionIntoTracePassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private RelNode apply(RelNode node) {
        return SelectionIntoTracePass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private boolean fired() {
        return !ctx.recordsFor(OptimizationCode.TRACE_001).isEmpty();
    }

    private static TraceNode trace() {
        return AstBuilders.trace("src", "dst", "cost",
                ObjectiveSense.MINIMIZE, "route",rel("Edges"));
    }

    private static Predicate eq(String col, Operand lit) {
        return cmp(attr(col), ComparisonOperator.EQUAL, lit);
    }

    // =========================================================================
    // TRACE-001 — fires
    // =========================================================================

    @Nested
    @DisplayName("TRACE-001 — endpoint folded into the operator")
    class Fires {

        @Test
        @DisplayName("σ src = c → single-source bound, selection removed")
        void sourceBound() {
            RelNode result = apply(select(eq("src", num("1")), trace()));

            TraceNode t = assertThat(result).asNode(TraceNode.class);
            assertThat(t.boundSource()).contains(num("1"));
            assertThat(t.boundTarget()).isEmpty();
            assertThat(t.weightColumn()).isEqualTo("cost");   // other fields preserved
            assertThat(fired()).isTrue();
            assertThat(ctx.recordsFor(OptimizationCode.TRACE_001).getFirst().detail())
                    .contains("single-source");
        }

        @Test
        @DisplayName("σ dst = c → single-target bound on the reversed graph")
        void targetBound() {
            RelNode result = apply(select(eq("dst", num("4")), trace()));

            TraceNode t = (TraceNode) result;
            assertThat(t.boundSource()).isEmpty();
            assertThat(t.boundTarget()).contains(num("4"));
            assertThat(ctx.recordsFor(OptimizationCode.TRACE_001).getFirst().detail())
                    .contains("single-target");
        }

        @Test
        @DisplayName("σ src = a ∧ dst = b → single-pair bound")
        void bothBounds() {
            Predicate both = and(eq("src", num("1")), eq("dst", num("4")));
            TraceNode t = (TraceNode) apply(select(both, trace()));
            assertThat(t.boundSource()).contains(num("1"));
            assertThat(t.boundTarget()).contains(num("4"));
            assertThat(ctx.recordsFor(OptimizationCode.TRACE_001).getFirst().detail())
                    .contains("single-pair");
        }

        @Test
        @DisplayName("a string-literal endpoint is pushed; residual non-endpoint predicate stays")
        void residualKept() {
            // σ src = "JFK" ∧ cost < 300  →  src pushed, cost < 300 retained above
            Predicate both = and(eq("src", str("JFK")),
                    cmp(attr("cost"),
                            ComparisonOperator.LESS, num("300")));
            RelNode result = apply(select(both, trace()));

            SelectionNode s = assertThat(result).asNode(SelectionNode.class);
            assertThat(s.predicate()).isInstanceOf(ComparisonPredicate.class);   // only cost < 300
            assertThat(s.input()).isNode(TraceNode.class);
            assertThat(((TraceNode) s.input()).boundSource()).contains(str("JFK"));
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a trace buried under another operator is still bounded")
        void buriedUnderProjection() {
            RelNode tree = project(
                    List.of(projected(attr("src"))),
                    select(eq("src", num("1")), trace()));
            RelNode result = apply(tree);

            assertThat(result).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) result).input()).isNode(TraceNode.class);
            assertThat(fired()).isTrue();
        }
    }

    // =========================================================================
    // TRACE-001 — no-op
    // =========================================================================

    @Nested
    @DisplayName("TRACE-001 — does not fire")
    class NoOp {

        @Test
        @DisplayName("a plain TRACE with no selection is unchanged")
        void plainTrace() {
            TraceNode t = trace();
            assertThat(apply(t)).isSameAs(t);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an inequality on an endpoint is not pushed")
        void inequalityNotPushed() {
            SelectionNode s = select(
                    cmp(attr("src"),
                            ComparisonOperator.GREATER, num("1")), trace());
            assertThat(apply(s)).isSameAs(s);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an equality on a non-endpoint column is not pushed")
        void nonEndpointColumnNotPushed() {
            SelectionNode s = select(eq("cost", num("5")), trace());
            assertThat(apply(s)).isSameAs(s);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a TRACE already bound on its TARGET is skipped, like one bound on its source")
        void aTargetBoundTraceIsSkipped() {
            // The eligibility test is `boundSource().isEmpty() && boundTarget().isEmpty()`,
            // so a source-bound fixture short-circuits on the first half and never shows
            // that the second is consulted.
            TraceNode bound = trace().withBounds(Optional.empty(), Optional.of(num("4")));
            RelNode tree = select(eq("src", num("1")), bound);

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a chain that binds nothing is rebuilt when a nested TRACE below it did fire")
        void chainIsRebuiltOverARewrittenInput() {
            // The one path that reaches the chain-rebuild: this σ-chain binds neither
            // endpoint, so nothing is folded here — but the TRACE's own input contains a
            // second σ-over-TRACE that *does* fold, so the input changes underneath and
            // the outer chain has to be put back over the new one. Returning the original
            // chain here would silently discard the inner rewrite.
            RelNode inner = select(eq("src", num("1")), trace());
            TraceNode outer = AstBuilders.trace("src", "dst", "cost",
                    ObjectiveSense.MINIMIZE, "route",inner);
            RelNode tree = select(eq("cost", num("7")),
                    select(eq("route", num("9")), outer));

            RelNode result = apply(tree);

            assertThat(result).isNotSameAs(tree);
            assertThat(fired()).as("the inner TRACE bound its source").isTrue();
            // Both σ of the outer chain survive, outermost first, over the rebuilt TRACE.
            SelectionNode first = (SelectionNode) result;
            assertThat(first.predicate()).isEqualTo(eq("cost", num("7")));
            SelectionNode second = (SelectionNode) first.input();
            assertThat(second.predicate()).isEqualTo(eq("route", num("9")));

            TraceNode rebuilt = (TraceNode) second.input();
            assertThat(rebuilt.boundSource()).as("the outer TRACE stays unbounded").isEmpty();
            assertThat(rebuilt.boundTarget()).isEmpty();
            assertThat(rebuilt.fromColumn()).isEqualTo("src");
            assertThat(rebuilt.pathColumn()).isEqualTo("route");
            assertThat(((TraceNode) rebuilt.input()).boundSource())
                    .as("the nested TRACE kept its new bound").contains(num("1"));
        }

        @Test
        @DisplayName("a chain that binds nothing over an unchanged input is returned as it was")
        void chainOverAnUnchangedInputIsNotRebuilt() {
            RelNode tree = select(eq("cost", num("7")),
                    select(eq("route", num("9")), trace()));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an already-bounded trace is left untouched (no re-fire)")
        void alreadyBounded() {
            TraceNode bounded = trace().withBounds(Optional.of(num("1")), Optional.empty());
            apply(select(eq("dst", num("4")), bounded));
            assertThat(fired()).isFalse();
        }
    }
}

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
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Selection-into-CLOSURE pushdown — CLOSURE-001 (ADR-0020 / #330)")
final class SelectionIntoClosurePassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private RelNode apply(RelNode node) {
        return SelectionIntoClosurePass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private boolean fired() {
        return !ctx.recordsFor(OptimizationCode.CLOSURE_001).isEmpty();
    }

    private static ClosureNode closure() {
        return AstBuilders.closure("src", "dst",rel("Edges"));
    }

    private static ClosureNode rClosure() {
        return AstBuilders.closure("src", "dst", true,rel("Edges"));
    }

    private static Predicate eq(String col, Operand lit) {
        return cmp(attr(col), ComparisonOperator.EQUAL, lit);
    }

    // =========================================================================
    // CLOSURE-001 — fires
    // =========================================================================

    @Nested
    @DisplayName("CLOSURE-001 — endpoint folded into the operator")
    class Fires {

        @Test
        @DisplayName("σ src = c → single-source bound, selection removed")
        void sourceBound() {
            RelNode result = apply(select(eq("src", num("1")), closure()));

            ClosureNode c = assertThat(result).asNode(ClosureNode.class);
            assertThat(c.boundSource()).contains(num("1"));
            assertThat(c.boundTarget()).isEmpty();
            assertThat(fired()).isTrue();
            assertThat(ctx.recordsFor(OptimizationCode.CLOSURE_001).getFirst().detail())
                    .contains("single-source");
        }

        @Test
        @DisplayName("σ dst = c → single-target bound on reversed adjacency")
        void targetBound() {
            RelNode result = apply(select(eq("dst", num("4")), closure()));

            ClosureNode c = assertThat(result).asNode(ClosureNode.class);
            assertThat(c.boundSource()).isEmpty();
            assertThat(c.boundTarget()).contains(num("4"));
            assertThat(ctx.recordsFor(OptimizationCode.CLOSURE_001).getFirst().detail())
                    .contains("single-target");
        }

        @Test
        @DisplayName("σ src = a ∧ dst = b → single-pair bound (both endpoints)")
        void bothBoundsFromConjunction() {
            Predicate both = and(eq("src", num("1")), eq("dst", num("4")));
            RelNode result = apply(select(both, closure()));

            ClosureNode c = assertThat(result).asNode(ClosureNode.class);
            assertThat(c.boundSource()).contains(num("1"));
            assertThat(c.boundTarget()).contains(num("4"));
            assertThat(ctx.recordsFor(OptimizationCode.CLOSURE_001).getFirst().detail())
                    .contains("single-pair");
        }

        @Test
        @DisplayName("stacked σ src = a (σ dst = b (…)) → both endpoints folded")
        void bothBoundsFromStackedSelections() {
            RelNode tree = select(eq("src", num("1")),
                    select(eq("dst", num("4")), closure()));
            RelNode result = apply(tree);

            ClosureNode c = assertThat(result).asNode(ClosureNode.class);
            assertThat(c.boundSource()).contains(num("1"));
            assertThat(c.boundTarget()).contains(num("4"));
        }

        @Test
        @DisplayName("literal on the left (c = src) is still pushed")
        void literalOnLeft() {
            Predicate p = cmp(num("1"), ComparisonOperator.EQUAL,
                    attr("src"));
            RelNode result = apply(select(p, closure()));

            assertThat(result).isNode(ClosureNode.class);
            assertThat(((ClosureNode) result).boundSource()).contains(num("1"));
        }

        @Test
        @DisplayName("a string-literal endpoint is pushed")
        void stringLiteral() {
            Operand x = str("X");
            RelNode result = apply(select(eq("src", x), closure()));
            assertThat(((ClosureNode) result).boundSource()).contains(x);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("RCLOSURE keeps its reflexive flag when bounded")
        void reflexivePreserved() {
            RelNode result = apply(select(eq("src", num("1")), rClosure()));
            ClosureNode c = (ClosureNode) result;
            assertThat(c.reflexive()).isTrue();
            assertThat(c.boundSource()).contains(num("1"));
        }

        @Test
        @DisplayName("non-pushable conjunct stays as a residual σ above the bounded closure")
        void residualKept() {
            Predicate both = and(eq("src", num("1")),
                    cmp(attr("w"),
                            ComparisonOperator.GREATER, num("0")));
            RelNode result = apply(select(both, closure()));

            SelectionNode s = assertThat(result).asNode(SelectionNode.class);
            assertThat(s.predicate()).isInstanceOf(ComparisonPredicate.class);   // only w > 0 remains
            assertThat(s.input()).isNode(ClosureNode.class);
            assertThat(((ClosureNode) s.input()).boundSource()).contains(num("1"));
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a closure buried under another operator is still bounded")
        void buriedUnderProjection() {
            RelNode tree = project(
                    List.of(projected(attr("src"))),
                    select(eq("src", num("1")), closure()));
            RelNode result = apply(tree);

            assertThat(result).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) result).input()).isNode(ClosureNode.class);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a second equality on an already-bound endpoint stays residual (no double bind)")
        void secondEqualityOnSameEndpointResidual() {
            Predicate both = and(eq("src", num("1")), eq("src", num("2")));
            RelNode result = apply(select(both, closure()));

            SelectionNode s = assertThat(result).asNode(SelectionNode.class);
            assertThat(((ClosureNode) s.input()).boundSource()).contains(num("1"));
            assertThat(ctx.recordsFor(OptimizationCode.CLOSURE_001)).hasSize(1);
        }
    }

    // =========================================================================
    // CLOSURE-001 — no-op
    // =========================================================================

    @Nested
    @DisplayName("CLOSURE-001 — does not fire")
    class NoOp {

        @Test
        @DisplayName("a plain CLOSURE with no selection is unchanged")
        void plainClosure() {
            ClosureNode c = closure();
            assertThat(apply(c)).isSameAs(c);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an inequality on an endpoint is not pushed")
        void inequalityNotPushed() {
            SelectionNode s = select(
                    cmp(attr("src"),
                            ComparisonOperator.GREATER, num("1")), closure());
            assertThat(apply(s)).isSameAs(s);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an equality on a non-endpoint column is not pushed")
        void nonEndpointColumnNotPushed() {
            SelectionNode s = select(eq("weight", num("1")), closure());
            assertThat(apply(s)).isSameAs(s);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a cross-column equality (src = dst) is not pushed")
        void crossColumnNotPushed() {
            Predicate p = cmp(attr("src"),
                    ComparisonOperator.EQUAL, attr("dst"));
            SelectionNode s = select(p, closure());
            assertThat(apply(s)).isSameAs(s);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a selection separated from the closure by a projection is not pushed")
        void notDirectlyAboveClosure() {
            RelNode tree = select(eq("src", num("1")),
                    project(
                            List.of(projected(attr("src"))),
                            closure()));
            apply(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a CLOSURE already bound on its TARGET is skipped, like one bound on its source")
        void aTargetBoundClosureIsSkipped() {
            // The eligibility test is `boundSource().isEmpty() && boundTarget().isEmpty()`;
            // a source-bound fixture short-circuits on the first half.
            ClosureNode bound = closure().withBounds(Optional.empty(), Optional.of(num("4")));
            RelNode tree = select(eq("src", num("1")), bound);

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a chain that binds nothing is rebuilt when a nested CLOSURE below it did fire")
        void chainIsRebuiltOverARewrittenInput() {
            // The one path that reaches the chain-rebuild: this σ-chain binds neither
            // endpoint, so nothing folds here — but the CLOSURE's own input holds a second
            // σ-over-CLOSURE that does fold, so the input changes underneath and the outer
            // chain must be put back over the new one. Returning the original chain would
            // silently discard the inner rewrite.
            RelNode inner = select(eq("src", num("1")), closure());
            ClosureNode outer = AstBuilders.closure("src", "dst",inner);
            RelNode tree = select(eq("weight", num("7")),
                    select(eq("label", num("9")), outer));

            RelNode result = apply(tree);

            assertThat(result).isNotSameAs(tree);
            assertThat(fired()).as("the inner CLOSURE bound its source").isTrue();
            SelectionNode first = (SelectionNode) result;
            assertThat(first.predicate()).isEqualTo(eq("weight", num("7")));
            SelectionNode second = (SelectionNode) first.input();
            assertThat(second.predicate()).isEqualTo(eq("label", num("9")));

            ClosureNode rebuilt = (ClosureNode) second.input();
            assertThat(rebuilt.boundSource()).as("the outer CLOSURE stays unbounded").isEmpty();
            assertThat(rebuilt.boundTarget()).isEmpty();
            assertThat(rebuilt.fromColumn()).isEqualTo("src");
            assertThat(((ClosureNode) rebuilt.input()).boundSource())
                    .as("the nested CLOSURE kept its new bound").contains(num("1"));
        }

        @Test
        @DisplayName("a chain that binds nothing over an unchanged input is returned as it was")
        void chainOverAnUnchangedInputIsNotRebuilt() {
            RelNode tree = select(eq("weight", num("7")),
                    select(eq("label", num("9")), closure()));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an already-bounded closure is left untouched (no re-fire)")
        void alreadyBounded() {
            ClosureNode bounded = closure().withBounds(Optional.of(num("1")), Optional.empty());
            SelectionNode s = select(eq("dst", num("4")), bounded);
            apply(s);
            assertThat(fired()).isFalse();
        }
    }
}

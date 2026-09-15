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

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.cost.DistinctnessSource;
import com.darkcollective.relix.cost.MonotoneGeneratorSource;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Generator bound pushdown — GEN-001 (ADR-0020 / #331)")
final class SelectionIntoGeneratorPassTest {

    /** "Naturals" is an ascending generator on column n; everything else is not. */
    private static final MonotoneGeneratorSource NATURALS =
            name -> name.equalsIgnoreCase("Naturals") ? Optional.of("n") : Optional.empty();

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new OptimizationContext(QueryEventListener.NONE, DistinctnessSource.NONE, NATURALS);
    }

    private RelNode apply(RelNode node) {
        return SelectionIntoGeneratorPass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private boolean fired() {
        return !ctx.recordsFor(OptimizationCode.GEN_001).isEmpty();
    }

    private static Predicate cmp(String col, ComparisonOperator op, Operand lit) {
        return AstBuilders.cmp(attr(col), op, lit);
    }

    private static RelationNode naturals() { return rel("Naturals"); }

    // =========================================================================
    // GEN-001 — fires
    // =========================================================================

    @Nested
    @DisplayName("GEN-001 — bound folded into the generator")
    class Fires {

        @Test
        @DisplayName("σ n < k → produce-while-less bound; σ kept as residual")
        void lessBound() {
            RelNode result = apply(select(cmp("n", ComparisonOperator.LESS, num("100")), naturals()));

            assertThat(result).isNode(SelectionNode.class);            // σ stays above
            SelectionNode s = (SelectionNode) result;
            assertThat(s.input()).isNode(RelationNode.class);
            var bound = ((RelationNode) s.input()).produceBound();
            assertThat(bound).isPresent();
            assertThat(bound.get().column()).isEqualTo("n");
            assertThat(bound.get().operator()).isEqualTo(ComparisonOperator.LESS);
            assertThat(bound.get().limit()).isEqualTo(num("100"));
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("σ n <= k → inclusive bound")
        void lessEqualBound() {
            RelNode r = apply(select(cmp("n", ComparisonOperator.LESS_EQUAL, num("9")), naturals()));
            var bound = ((RelationNode) ((SelectionNode) r).input()).produceBound().orElseThrow();
            assertThat(bound.operator()).isEqualTo(ComparisonOperator.LESS_EQUAL);
            assertThat(bound.inclusive()).isTrue();
        }

        @Test
        @DisplayName("σ n = k → equality bound (inclusive, residual σ does the exact match)")
        void equalBound() {
            RelNode r = apply(select(cmp("n", ComparisonOperator.EQUAL, num("42")), naturals()));
            var bound = ((RelationNode) ((SelectionNode) r).input()).produceBound().orElseThrow();
            assertThat(bound.operator()).isEqualTo(ComparisonOperator.EQUAL);
        }

        @Test
        @DisplayName("σ k > n (literal on the left) is mirrored to an upper bound")
        void mirroredGreater() {
            Predicate p = AstBuilders.cmp(num("100"), ComparisonOperator.GREATER,
                    attr("n"));
            RelNode r = apply(select(p, naturals()));
            var bound = ((RelationNode) ((SelectionNode) r).input()).produceBound().orElseThrow();
            assertThat(bound.operator()).isEqualTo(ComparisonOperator.LESS);
            assertThat(bound.limit()).isEqualTo(num("100"));
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a conjunction keeps every conjunct as residual σ but folds the first upper bound")
        void conjunctionKeepsResidual() {
            Predicate both = and(
                    cmp("n", ComparisonOperator.LESS, num("100")),
                    cmp("n", ComparisonOperator.GREATER, num("2")));
            RelNode r = apply(select(both, naturals()));

            assertThat(r).isNode(SelectionNode.class);
            assertThat(((SelectionNode) r).predicate()).isInstanceOf(AndPredicate.class);  // full σ kept
            var bound = ((RelationNode) ((SelectionNode) r).input()).produceBound().orElseThrow();
            assertThat(bound.operator()).isEqualTo(ComparisonOperator.LESS);
            assertThat(fired()).isTrue();
        }
    }

    // =========================================================================
    // GEN-001 — no-op
    // =========================================================================

    @Nested
    @DisplayName("GEN-001 — does not fire")
    class NoOp {

        @Test
        @DisplayName("a lower bound (n > k) is not a termination stop")
        void lowerBoundNotPushed() {
            SelectionNode s = select(cmp("n", ComparisonOperator.GREATER, num("5")), naturals());
            assertThat(apply(s)).isSameAs(s);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a bound on a non-ascending column is not pushed")
        void otherColumnNotPushed() {
            SelectionNode s = select(cmp("x", ComparisonOperator.LESS, num("5")), naturals());
            assertThat(apply(s)).isSameAs(s);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a non-generator leaf is never bounded")
        void nonGeneratorNotPushed() {
            SelectionNode s = select(
                    cmp("n", ComparisonOperator.LESS, num("5")), rel("Users"));
            assertThat(apply(s)).isSameAs(s);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a bound against another column is not a bound the generator can stop on")
        void columnAgainstColumnNotPushed() {
            // A produce bound is a termination test the generator applies while it emits,
            // before any other row exists to compare against — so the other side has to
            // be a constant. Reading `n < m` as one would stop the generator at a value
            // it cannot know, and both orderings are written out because the guard tries
            // the column on each side in turn.
            SelectionNode columnLeft =
                    select(cmp("n", ComparisonOperator.LESS, attr("m")), naturals());
            assertThat(apply(columnLeft)).isSameAs(columnLeft);
            assertThat(fired()).isFalse();

            SelectionNode columnRight =
                    select(AstBuilders.cmp(attr("m"), ComparisonOperator.LESS, attr("n")), naturals());
            assertThat(apply(columnRight)).isSameAs(columnRight);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a plain generator with no selection is unchanged")
        void plainGenerator() {
            RelationNode g = naturals();
            assertThat(apply(g)).isSameAs(g);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an already-bounded generator is left untouched")
        void alreadyBounded() {
            RelationNode bounded = naturals().withProduceBound(
                    new com.darkcollective.relix.ast.ProduceBound("n", ComparisonOperator.LESS, num("10")));
            SelectionNode s = select(cmp("n", ComparisonOperator.LESS, num("5")), bounded);
            apply(s);
            assertThat(fired()).isFalse();
        }
    }
}

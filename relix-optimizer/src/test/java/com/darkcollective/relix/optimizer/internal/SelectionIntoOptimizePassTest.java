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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Group pruning into OPTIMIZE — OPTIMIZE-001 (ADR-0020 / #333)")
final class SelectionIntoOptimizePassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    private RelNode apply(RelNode node) {
        return SelectionIntoOptimizePass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private boolean fired() {
        return !ctx.recordsFor(OptimizationCode.OPTIMIZE_001).isEmpty();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    /** OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 100 PER {keys} (input). */
    private static OptimizeNode optimize(RelNode input, String... keys) {
        return AstBuilders.optimize(
                ObjectiveSense.MAXIMIZE,
                attr("value"),
                List.of(constraint(attr("weight"),
                        ComparisonOperator.LESS_EQUAL, 100.0)),
                List.of(keys),
                input);
    }

    /** LP allocation variant: OPTIMIZE ALLOCATE (0,1) … -> alloc PER {keys}. */
    private static OptimizeNode optimizeAllocate(RelNode input, String... keys) {
        return AstBuilders.optimize(
                ObjectiveSense.MAXIMIZE,
                attr("ret"),
                List.of(constraint(num("1"),
                        ComparisonOperator.EQUAL, 1.0)),
                List.of(keys),
                Optional.of(allocation(0.0, 1.0, "alloc")),
                input);
    }

    private static Predicate eq(String col, String value) {
        return cmp(attr(col),
                ComparisonOperator.EQUAL, str(value));
    }

    private static SelectionNode sel(Predicate p, RelNode input) {
        return AstBuilders.select(p, input);
    }

    // =========================================================================
    // OPTIMIZE-001 — fires
    // =========================================================================

    @Nested
    @DisplayName("OPTIMIZE-001 — group pruned")
    class Fires {

        @Test
        @DisplayName("σ region='WEST' (OPTIMIZE … PER region) → OPTIMIZE … (σ region='WEST' (R))")
        void pushesGroupingEqualityBelowOptimize() {
            var opt = optimize(rel("Candidates"), "region");
            var result = apply(sel(eq("region", "WEST"), opt));

            assertThat(fired()).isTrue();
            // No residual σ remains above the operator.
            assertThat(result).isNode(OptimizeNode.class);
            var o = (OptimizeNode) result;
            assertThat(o.groupingKeys()).containsExactly("region");
            // The pushed selection now sits directly above the operator's input.
            assertThat(o.input()).isNode(SelectionNode.class);
            var pushed = (SelectionNode) o.input();
            assertThat(pushed.predicate()).isEqualTo(eq("region", "WEST"));
            assertThat(pushed.input()).isEqualTo(rel("Candidates"));
            // Objective / constraints / sense are preserved.
            assertThat(o.sense()).isEqualTo(ObjectiveSense.MAXIMIZE);
            assertThat(o.objective()).isEqualTo(attr("value"));
            assertThat(o.constraints()).hasSize(1);
        }

        @Test
        @DisplayName("constant on the left (\"WEST\" = region) is also pushable")
        void constantOnLeftIsPushable() {
            var opt = optimize(rel("Candidates"), "region");
            var pred = cmp(str("WEST"),
                    ComparisonOperator.EQUAL, attr("region"));
            var result = apply(sel(pred, opt));

            assertThat(fired()).isTrue();
            assertThat(result).isNode(OptimizeNode.class);
        }

        @Test
        @DisplayName("grouping-key match is case-insensitive (σ REGION='WEST')")
        void caseInsensitiveKey() {
            var opt = optimize(rel("Candidates"), "region");
            var result = apply(sel(eq("REGION", "WEST"), opt));

            assertThat(fired()).isTrue();
            assertThat(result).isNode(OptimizeNode.class);
        }

        @Test
        @DisplayName("qualified key reference (σ Candidates.region='WEST') matches the bare key")
        void qualifiedKeyReference() {
            var opt = optimize(rel("Candidates"), "region");
            var result = apply(sel(eq("Candidates.region", "WEST"), opt));

            assertThat(fired()).isTrue();
            assertThat(result).isNode(OptimizeNode.class);
        }

        @Test
        @DisplayName("both keys of a two-key PER fixed → both pushed, no residual")
        void multipleKeysAllPushed() {
            var opt = optimize(rel("Candidates"), "region", "team");
            var pred = and(eq("region", "WEST"), eq("team", "core"));
            var result = apply(sel(pred, opt));

            assertThat(fired()).isTrue();
            assertThat(result).isNode(OptimizeNode.class);
            var pushed = (SelectionNode) ((OptimizeNode) result).input();
            assertThat(pushed.predicate()).isInstanceOf(AndPredicate.class);
        }

        @Test
        @DisplayName("mixed predicate → key equality pushed, non-key conjunct left as residual σ")
        void residualKeptAbove() {
            var opt = optimize(rel("Candidates"), "region");
            var valueGt = cmp(attr("value"),
                    ComparisonOperator.GREATER, num("50"));
            var pred = and(eq("region", "WEST"), valueGt);
            var result = apply(sel(pred, opt));

            assertThat(fired()).isTrue();
            // Residual σ value > 50 sits above the operator.
            assertThat(result).isNode(SelectionNode.class);
            var residual = (SelectionNode) result;
            assertThat(residual.predicate()).isEqualTo(valueGt);
            assertThat(residual.input()).isNode(OptimizeNode.class);
            // Equality pushed below the operator.
            var pushed = (SelectionNode) ((OptimizeNode) residual.input()).input();
            assertThat(pushed.predicate()).isEqualTo(eq("region", "WEST"));
        }

        @Test
        @DisplayName("LP ALLOCATE mode prunes on the grouping key just like MIP mode")
        void allocateModePruned() {
            var opt = optimizeAllocate(rel("Assets"), "sector");
            var result = apply(sel(eq("sector", "TECH"), opt));

            assertThat(fired()).isTrue();
            assertThat(result).isNode(OptimizeNode.class);
            var o = (OptimizeNode) result;
            // Allocation spec survives the rewrite.
            assertThat(o.allocation()).isPresent();
            assertThat(o.allocation().get().columnName()).isEqualTo("alloc");
            assertThat(o.input()).isNode(SelectionNode.class);
        }

        @Test
        @DisplayName("stacked selections — σ value>50 (σ region='WEST' (OPTIMIZE …)) still prunes the inner")
        void stackedSelections() {
            var opt = optimize(rel("Candidates"), "region");
            var valueGt = cmp(attr("value"),
                    ComparisonOperator.GREATER, num("50"));
            var inner = sel(eq("region", "WEST"), opt);
            var result = apply(sel(valueGt, inner));

            assertThat(fired()).isTrue();
            assertThat(result).isNode(SelectionNode.class);
            var outer = (SelectionNode) result;
            assertThat(outer.predicate()).isEqualTo(valueGt);
            assertThat(outer.input()).isNode(OptimizeNode.class);
        }

        @Test
        @DisplayName("event description names the pushed equality and the operator")
        void eventDescription() {
            var listener = new CapturingListener();
            var c = new OptimizationContext(listener);
            var opt = optimize(rel("Candidates"), "region");
            SelectionIntoOptimizePass.apply(sel(eq("region", "WEST"), opt),
                    "Q", SchemaAnnotations.empty(), c);

            assertThat(listener.events).hasSize(1);
            var e = listener.events.get(0);
            assertThat(e.stage()).isEqualTo(QueryEvent.Stage.OPTIMIZE);
            assertThat(e.code()).isEqualTo("OPTIMIZE-001");
            assertThat(e.description())
                    .contains("group pruned")
                    .contains("region = \"WEST\"")
                    .contains("OPTIMIZE")
                    .contains("other groups not solved");
            assertThat(e.target()).contains("Q");
        }
    }

    // =========================================================================
    // Does NOT fire (safety / no-op cases)
    // =========================================================================

    @Nested
    @DisplayName("does not fire")
    class DoesNotFire {

        @Test
        @DisplayName("equality on a non-grouping column (value = 5) is not pushed")
        void nonGroupingEqualityNotPushed() {
            var opt = optimize(rel("Candidates"), "region");
            var pred = cmp(attr("value"),
                    ComparisonOperator.EQUAL, num("5"));
            var input = sel(pred, opt);
            var result = apply(input);

            assertThat(fired()).isFalse();
            assertThat(result).isSameAs(input);
        }

        @Test
        @DisplayName("inequality on a grouping key (region < 'M') is not pushed")
        void groupingKeyInequalityNotPushed() {
            var opt = optimize(rel("Candidates"), "region");
            var pred = cmp(attr("region"),
                    ComparisonOperator.LESS, str("M"));
            var result = apply(sel(pred, opt));

            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("cross-column equality (region = other_col) is not pushed")
        void crossColumnEqualityNotPushed() {
            var opt = optimize(rel("Candidates"), "region");
            var pred = cmp(attr("region"),
                    ComparisonOperator.EQUAL, attr("other_col"));
            var result = apply(sel(pred, opt));

            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("PER-less OPTIMIZE (no grouping keys = one whole-relation group) is never pruned")
        void wholeRelationNotPruned() {
            var opt = optimize(rel("Candidates")); // empty grouping list
            var pred = cmp(attr("value"),
                    ComparisonOperator.EQUAL, num("5"));
            var result = apply(sel(pred, opt));

            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("σ over a plain relation (no OPTIMIZE) is unchanged")
        void noOptimizeInTree() {
            var input = sel(eq("region", "WEST"), rel("Candidates"));
            var result = apply(input);

            assertThat(fired()).isFalse();
            assertThat(result).isSameAs(input);
        }

        @Test
        @DisplayName("running the pass twice does not fire again (idempotent)")
        void idempotent() {
            var opt = optimize(rel("Candidates"), "region");
            var once = apply(sel(eq("region", "WEST"), opt));
            assertThat(fired()).isTrue();

            var c2 = new OptimizationContext();
            SelectionIntoOptimizePass.apply(once, "Q", SchemaAnnotations.empty(), c2);
            assertThat(c2.recordsFor(OptimizationCode.OPTIMIZE_001)).isEmpty();
        }
    }

    // ── helper listener ──────────────────────────────────────────────────────

    private static final class CapturingListener implements QueryEventListener {
        final List<QueryEvent> events = new ArrayList<>();
        @Override public void onEvent(QueryEvent event) { events.add(event); }
    }
}

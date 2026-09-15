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
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RankingFunction;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.WindowNode;
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

@DisplayName("Partition pruning into WINDOW / TopK — WINDOW-001 / TOPK-001 (ADR-0020)")
final class SelectionIntoWindowPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    private RelNode apply(RelNode node) {
        return SelectionIntoWindowPass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private boolean windowFired() {
        return !ctx.recordsFor(OptimizationCode.WINDOW_001).isEmpty();
    }

    private boolean topkFired() {
        return !ctx.recordsFor(OptimizationCode.TOPK_001).isEmpty();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    /** ROW_NUMBER() OVER (PARTITION BY {keys} ORDER BY amount DESC) AS rk. */
    private static WindowNode window(RelNode input, String outputCol, String... keys) {
        return AstBuilders.window(
                new WindowFunction.RankingWindow(RankingFunction.ROW_NUMBER, Optional.empty()),
                List.of(keys), List.of(desc("amount")),
                new WindowFrame.PartitionFrame(), outputCol, input);
    }

    private static TopKNode topk(RelNode input, long count, String... keys) {
        return topK(List.of(keys), List.of(desc("amount")), count, input);
    }

    private static Predicate eq(String col, String value) {
        return cmp(attr(col),
                ComparisonOperator.EQUAL, str(value));
    }

    private static SelectionNode sel(Predicate p, RelNode input) {
        return AstBuilders.select(p, input);
    }

    // =========================================================================
    // WINDOW-001 — fires
    // =========================================================================

    @Nested
    @DisplayName("WINDOW-001 — partition pruned")
    class WindowFires {

        @Test
        @DisplayName("σ region='WEST' (WINDOW … PARTITION BY region …) → WINDOW … (σ region='WEST' (R))")
        void pushesPartitionEqualityBelowWindow() {
            var win = window(rel("Sales"), "rk", "region");
            var result = apply(sel(eq("region", "WEST"), win));

            assertThat(windowFired()).isTrue();
            // No residual σ remains above the operator.
            assertThat(result).isNode(WindowNode.class);
            var w = (WindowNode) result;
            assertThat(w.partitionKeys()).containsExactly("region");
            // The pushed selection now sits directly above the window's input.
            assertThat(w.input()).isNode(SelectionNode.class);
            var pushed = (SelectionNode) w.input();
            assertThat(pushed.predicate()).isEqualTo(eq("region", "WEST"));
            assertThat(pushed.input()).isEqualTo(rel("Sales"));
        }

        @Test
        @DisplayName("constant on the left (\"WEST\" = region) is also pushable")
        void constantOnLeftIsPushable() {
            var win = window(rel("Sales"), "rk", "region");
            var pred = cmp(str("WEST"),
                    ComparisonOperator.EQUAL, attr("region"));
            var result = apply(sel(pred, win));

            assertThat(windowFired()).isTrue();
            assertThat(result).isNode(WindowNode.class);
        }

        @Test
        @DisplayName("partition-key match is case-insensitive (σ REGION='WEST')")
        void caseInsensitivePartitionKey() {
            var win = window(rel("Sales"), "rk", "region");
            var result = apply(sel(eq("REGION", "WEST"), win));

            assertThat(windowFired()).isTrue();
            assertThat(result).isNode(WindowNode.class);
        }

        @Test
        @DisplayName("qualified key reference (σ Sales.region='WEST') matches the bare key")
        void qualifiedKeyReference() {
            var win = window(rel("Sales"), "rk", "region");
            var result = apply(sel(eq("Sales.region", "WEST"), win));

            assertThat(windowFired()).isTrue();
            assertThat(result).isNode(WindowNode.class);
        }

        @Test
        @DisplayName("both keys of a two-key partition fixed → both pushed, no residual")
        void multipleKeysAllPushed() {
            var win = window(rel("Sales"), "rk", "region", "channel");
            var pred = and(eq("region", "WEST"), eq("channel", "WEB"));
            var result = apply(sel(pred, win));

            assertThat(windowFired()).isTrue();
            assertThat(result).isNode(WindowNode.class);
            var pushed = (SelectionNode) ((WindowNode) result).input();
            // Both equalities are conjoined below the window.
            assertThat(pushed.predicate()).isInstanceOf(AndPredicate.class);
        }

        @Test
        @DisplayName("mixed predicate → key equality pushed, non-key conjunct left as residual σ")
        void residualKeptAbove() {
            var win = window(rel("Sales"), "rk", "region");
            var amountGt = cmp(attr("amount"),
                    ComparisonOperator.GREATER, num("100"));
            var pred = and(eq("region", "WEST"), amountGt);
            var result = apply(sel(pred, win));

            assertThat(windowFired()).isTrue();
            // Residual σ amount > 100 sits above the window.
            assertThat(result).isNode(SelectionNode.class);
            var residual = (SelectionNode) result;
            assertThat(residual.predicate()).isEqualTo(amountGt);
            assertThat(residual.input()).isNode(WindowNode.class);
            // Equality pushed below the window.
            var pushed = (SelectionNode) ((WindowNode) residual.input()).input();
            assertThat(pushed.predicate()).isEqualTo(eq("region", "WEST"));
        }

        @Test
        @DisplayName("stacked selections — σ amount>100 (σ region='WEST' (WINDOW …)) still prunes the inner")
        void stackedSelections() {
            var win = window(rel("Sales"), "rk", "region");
            var amountGt = cmp(attr("amount"),
                    ComparisonOperator.GREATER, num("100"));
            var inner = sel(eq("region", "WEST"), win);
            var result = apply(sel(amountGt, inner));

            assertThat(windowFired()).isTrue();
            assertThat(result).isNode(SelectionNode.class);
            var outer = (SelectionNode) result;
            assertThat(outer.predicate()).isEqualTo(amountGt);
            assertThat(outer.input()).isNode(WindowNode.class);
        }

        @Test
        @DisplayName("stacked the other way up — the pushable conjunct outermost — prunes too")
        void stackedSelectionsPushableOutermost() {
            // The order SEL-001 produces depends on which side of the ∧ the user wrote
            // the partition equality: σ region='WEST' (σ amount>100 (WINDOW …)) leaves
            // the *non*-pushable conjunct adjacent to the operator. Reading only the
            // nearest σ made the rule fire or not by that accident.
            var win = window(rel("Sales"), "rk", "region");
            var amountGt = cmp(attr("amount"),
                    ComparisonOperator.GREATER, num("100"));
            var result = apply(sel(eq("region", "WEST"), sel(amountGt, win)));

            assertThat(windowFired()).isTrue();
            var outer = (SelectionNode) result;
            assertThat(outer.predicate()).isEqualTo(amountGt);
            assertThat(outer.input()).isNode(WindowNode.class);
            var pushed = (SelectionNode) ((WindowNode) outer.input()).input();
            assertThat(pushed.predicate()).isEqualTo(eq("region", "WEST"));
        }

        @Test
        @DisplayName("event description names the pushed equality and the operator")
        void eventDescription() {
            var listener = new CapturingListener();
            var c = new OptimizationContext(listener);
            var win = window(rel("Sales"), "rk", "region");
            SelectionIntoWindowPass.apply(sel(eq("region", "WEST"), win),
                    "Q", SchemaAnnotations.empty(), c);

            assertThat(listener.events).hasSize(1);
            var e = listener.events.get(0);
            assertThat(e.stage()).isEqualTo(QueryEvent.Stage.OPTIMIZE);
            assertThat(e.code()).isEqualTo("WINDOW-001");
            assertThat(e.description())
                    .contains("partition pruned")
                    .contains("region = \"WEST\"")
                    .contains("WINDOW")
                    .contains("other partitions not computed");
            assertThat(e.target()).contains("Q");
        }
    }

    // =========================================================================
    // TOPK-001 — fires
    // =========================================================================

    @Nested
    @DisplayName("TOPK-001 — partition pruned")
    class TopKFires {

        @Test
        @DisplayName("σ customer_id='c1' (TOP 3 amount DESC PER customer_id) → TOP … (σ … (R))")
        void pushesPartitionEqualityBelowTopK() {
            var t = topk(rel("Orders"), 3, "customer_id");
            var result = apply(sel(eq("customer_id", "c1"), t));

            assertThat(topkFired()).isTrue();
            assertThat(result).isNode(TopKNode.class);
            var node = (TopKNode) result;
            assertThat(node.count()).isEqualTo(3);
            assertThat(node.input()).isNode(SelectionNode.class);
            assertThat(((SelectionNode) node.input()).predicate()).isEqualTo(eq("customer_id", "c1"));
        }

        @Test
        @DisplayName("TopK offset is preserved through the rewrite")
        void preservesOffset() {
            var t = topK(List.of("customer_id"), List.of(desc("amount")),
                    Optional.of(2L), 3, rel("Orders"));
            var result = apply(sel(eq("customer_id", "c1"), t));

            assertThat(topkFired()).isTrue();
            assertThat(((TopKNode) result).offset()).contains(2L);
        }

        @Test
        @DisplayName("event uses the TOPK-001 code")
        void eventCode() {
            var listener = new CapturingListener();
            var c = new OptimizationContext(listener);
            var t = topk(rel("Orders"), 3, "customer_id");
            SelectionIntoWindowPass.apply(sel(eq("customer_id", "c1"), t),
                    "Q", SchemaAnnotations.empty(), c);

            assertThat(listener.events).singleElement()
                    .satisfies(e -> {
                        assertThat(e.code()).isEqualTo("TOPK-001");
                        assertThat(e.description()).contains("TOP");
                    });
        }
    }

    // =========================================================================
    // Does NOT fire (safety / no-op cases)
    // =========================================================================

    @Nested
    @DisplayName("does not fire")
    class DoesNotFire {

        @Test
        @DisplayName("predicate on a computed window column (rk ≤ 3) is not pushed")
        void computedColumnNotPushed() {
            var win = window(rel("Sales"), "rk", "region");
            var pred = cmp(attr("rk"),
                    ComparisonOperator.LESS_EQUAL, num("3"));
            var input = sel(pred, win);
            var result = apply(input);

            assertThat(windowFired()).isFalse();
            assertThat(result).isSameAs(input);
        }

        @Test
        @DisplayName("equality on a non-partition column (amount = 5) is not pushed")
        void nonPartitionEqualityNotPushed() {
            var win = window(rel("Sales"), "rk", "region");
            var pred = cmp(attr("amount"),
                    ComparisonOperator.EQUAL, num("5"));
            var input = sel(pred, win);
            var result = apply(input);

            assertThat(windowFired()).isFalse();
            assertThat(result).isSameAs(input);
        }

        @Test
        @DisplayName("inequality on a partition key (region < 'M') is not pushed")
        void partitionKeyInequalityNotPushed() {
            var win = window(rel("Sales"), "rk", "region");
            var pred = cmp(attr("region"),
                    ComparisonOperator.LESS, str("M"));
            var result = apply(sel(pred, win));

            assertThat(windowFired()).isFalse();
        }

        @Test
        @DisplayName("cross-column equality (region = other_col) is not pushed")
        void crossColumnEqualityNotPushed() {
            var win = window(rel("Sales"), "rk", "region");
            var pred = cmp(attr("region"),
                    ComparisonOperator.EQUAL, attr("other_col"));
            var result = apply(sel(pred, win));

            assertThat(windowFired()).isFalse();
        }

        @Test
        @DisplayName("a predicate that is not a comparison at all is not pushed")
        void nonComparisonPredicateNotPushed() {
            // IS NULL fixes the key as surely as an equality does, and is still not one:
            // the rule pushes an equality against a constant, and widening it to every
            // predicate that happens to constrain a key is a different rule with its own
            // argument to make.
            var win = window(rel("Sales"), "rk", "region");
            var result = apply(sel(nullPred(attr("region"), true), win));

            assertThat(windowFired()).isFalse();
        }

        @Test
        @DisplayName("an arithmetic operand is constant only if both of its sides are")
        void halfConstantArithmeticNotPushed() {
            var win = window(rel("Sales"), "rk", "region");
            var constantFirst = cmp(attr("region"), ComparisonOperator.EQUAL,
                    arith(num("1"), ArithmeticOperator.PLUS, attr("amount")));
            apply(sel(constantFirst, win));
            assertThat(windowFired())
                    .as("the right-hand side reads a column, so it is not a value the "
                            + "partition can be pruned to before the rows are read")
                    .isFalse();

            var columnFirst = cmp(attr("region"), ComparisonOperator.EQUAL,
                    arith(attr("amount"), ArithmeticOperator.PLUS, num("1")));
            apply(sel(columnFirst, win));
            assertThat(windowFired())
                    .as("and with the column written first, which decides on its own")
                    .isFalse();
        }

        @Test
        @DisplayName("a nested constant is still a constant — every composite operand form")
        void compositeConstantsArePushable() {
            // "Constant" is defined recursively over every composite operand form, so a
            // struct or an array of literals fixes a partition key just as a bare literal
            // does. Each form is its own arm and none of them is reachable from a test
            // that only ever writes a scalar on the right-hand side.
            List<Operand> constants = List.of(
                    structOf(
                            new StructConstruction.Field("a", num("1"))),
                    arrayOf(num("1")),
                    set(num("1")),
                    unary(num("1")),
                    arith(num("1"),
                            ArithmeticOperator.PLUS, num("2")),
                    func("UCase",str("west")));

            for (Operand constant : constants) {
                ctx = new OptimizationContext();
                var win = window(rel("Sales"), "rk", "region");
                var pred = cmp(attr("region"),
                        ComparisonOperator.EQUAL, constant);

                assertThat(apply(sel(pred, win)))
                        .as("%s should be pushable", constant.getClass().getSimpleName())
                        .isNotInstanceOf(SelectionNode.class);
                assertThat(windowFired())
                        .as("%s should be pushable", constant.getClass().getSimpleName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a composite operand hiding a column reference is not a constant")
        void compositeWithAColumnIsNotPushable() {
            // The other half: each recursive arm has to actually look inside, or a
            // correlated expression would be pushed below the window it depends on.
            List<Operand> notConstants = List.of(
                    structOf(
                            new StructConstruction.Field("a", attr("other"))),
                    arrayOf(attr("other")),
                    set(attr("other")),
                    unary(attr("other")),
                    arith(num("1"),
                            ArithmeticOperator.PLUS, attr("other")),
                    func("UCase",attr("other")));

            for (Operand operand : notConstants) {
                ctx = new OptimizationContext();
                var win = window(rel("Sales"), "rk", "region");
                var pred = cmp(attr("region"),
                        ComparisonOperator.EQUAL, operand);
                var input = sel(pred, win);

                assertThat(apply(input))
                        .as("%s hides a column", operand.getClass().getSimpleName())
                        .isSameAs(input);
                assertThat(windowFired())
                        .as("%s hides a column", operand.getClass().getSimpleName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("global PER-less window (no partition keys) is never pruned")
        void globalWindowNotPruned() {
            var win = window(rel("Sales"), "rk"); // empty partition list
            var pred = cmp(attr("amount"),
                    ComparisonOperator.EQUAL, num("5"));
            var result = apply(sel(pred, win));

            assertThat(windowFired()).isFalse();
        }

        @Test
        @DisplayName("σ over a plain relation (no window/topk) is unchanged")
        void noWindowInTree() {
            var input = sel(eq("region", "WEST"), rel("Sales"));
            var result = apply(input);

            assertThat(windowFired()).isFalse();
            assertThat(topkFired()).isFalse();
            assertThat(result).isSameAs(input);
        }

        @Test
        @DisplayName("running the pass twice does not fire again (idempotent)")
        void idempotent() {
            var win = window(rel("Sales"), "rk", "region");
            var once = apply(sel(eq("region", "WEST"), win));
            assertThat(windowFired()).isTrue();

            var c2 = new OptimizationContext();
            SelectionIntoWindowPass.apply(once, "Q", SchemaAnnotations.empty(), c2);
            assertThat(c2.recordsFor(OptimizationCode.WINDOW_001)).isEmpty();
        }
    }

    // ── helper listener ──────────────────────────────────────────────────────

    private static final class CapturingListener implements QueryEventListener {
        final List<QueryEvent> events = new ArrayList<>();
        @Override public void onEvent(QueryEvent event) { events.add(event); }
    }
}

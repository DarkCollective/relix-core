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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.plan.PlanAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for table-valued-function binding.
 *
 * <ul>
 *   <li>{@link Substitution} — direct unit tests of {@link RelationFunctionInliner#bind}:
 *       a parameter reference is replaced by its bound argument across every
 *       operand-bearing construct, while real columns and qualified references are
 *       left untouched.</li>
 *   <li>{@link PlannerInlining} — the planner-level behaviours that ride on top of
 *       {@code bind}: transitive (nested-TVF) inlining, schema re-annotation of the
 *       freshly substituted body, and rejection of recursive functions.</li>
 * </ul>
 */
@DisplayName("RelationFunctionInliner — parameter substitution + planner inlining")
final class RelationFunctionInlinerTest {

    // ── direct bind() substitution ──────────────────────────────────────────────

    @Nested
    @DisplayName("bind() substitutes each parameter reference by its argument")
    class Substitution {

        /** A single-parameter TVF with the given body. */
        private static RelationFunctionSymbol fn(String param, RelNode body) {
            return RelationFunctionSymbol.builder("f")
                    .parameter(param, ScalarType.NUMBER)
                    .body(body)
                    .build();
        }

        @Test
        @DisplayName("a bare parameter in a selection predicate is replaced by the argument")
        void substitutesInPredicate() {
            RelationFunctionSymbol f = fn("cid", select(
                    cmp(attr("customer_id"),
                            ComparisonOperator.EQUAL, attr("cid")),
                    rel("Orders")));

            RelNode out = RelationFunctionInliner.bind(f, List.of(num("2")));

            ComparisonPredicate cmp = (ComparisonPredicate) ((SelectionNode) out).predicate();
            assertThat(cmp.left()).isEqualTo(attr("customer_id")); // a real column, untouched
            assertThat(cmp.right()).isEqualTo(num("2"));             // the parameter, bound
        }

        @Test
        @DisplayName("a parameter inside an arithmetic projection expression is replaced")
        void substitutesInArithmetic() {
            RelationFunctionSymbol f = fn("rate", project(
                    List.of(ProjectedAttribute.simple(arith(
                            attr("amount"), ArithmeticOperator.MULTIPLY,
                            attr("rate")))),
                    rel("Orders")));

            RelNode out = RelationFunctionInliner.bind(f, List.of(num("3")));

            Operand expr = ((ProjectionNode) out).attributes().get(0).expression();
            BinaryArithmeticExpression arith = (BinaryArithmeticExpression) expr;
            assertThat(arith.left()).isEqualTo(attr("amount"));
            assertThat(arith.right()).isEqualTo(num("3"));
        }

        @Test
        @DisplayName("a parameter inside a function-call argument is replaced")
        void substitutesInFunctionCall() {
            RelationFunctionSymbol f = fn("p", project(
                    List.of(ProjectedAttribute.simple(
                            func("Abs",attr("p")))),
                    rel("Orders")));

            RelNode out = RelationFunctionInliner.bind(f, List.of(num("7")));

            FunctionCall call = (FunctionCall) ((ProjectionNode) out).attributes().get(0).expression();
            assertThat(call.arguments()).containsExactly(num("7"));
        }

        @Test
        @DisplayName("the argument of a nested table-valued-function call is substituted")
        void substitutesInNestedCallArgument() {
            // body is itself a call `inner(cid)` (a leaf RelNode) — bind rewrites the
            // call's argument; the Planner is what later expands the call.
            RelationFunctionSymbol f = fn("cid",
                    tvf("inner",attr("cid")));

            RelNode out = RelationFunctionInliner.bind(f, List.of(num("9")));

            RelationFunctionCall call = (RelationFunctionCall) out;
            assertThat(call.functionName()).isEqualTo("inner");
            assertThat(call.arguments()).containsExactly(num("9"));
        }

        @Test
        @DisplayName("a qualified reference (R.cid) is a column, never a parameter — left untouched")
        void leavesQualifiedReference() {
            RelationFunctionSymbol f = fn("cid", select(
                    cmp(attr("Orders.cid"),
                            ComparisonOperator.EQUAL, num("1")),
                    rel("Orders")));

            RelNode out = RelationFunctionInliner.bind(f, List.of(num("42")));

            ComparisonPredicate cmp = (ComparisonPredicate) ((SelectionNode) out).predicate();
            assertThat(cmp.left()).isEqualTo(attr("Orders.cid")); // qualified — not bound
        }

        @Test
        @DisplayName("parameters bind positionally")
        void bindsPositionally() {
            RelationFunctionSymbol f = RelationFunctionSymbol.builder("f")
                    .parameter("lo", ScalarType.NUMBER)
                    .parameter("hi", ScalarType.NUMBER)
                    .body(select(
                            cmp(attr("lo"),
                                    ComparisonOperator.LESS, attr("hi")),
                            rel("Orders")))
                    .build();

            RelNode out = RelationFunctionInliner.bind(f,
                    List.of(num("10"), num("20")));

            ComparisonPredicate cmp = (ComparisonPredicate) ((SelectionNode) out).predicate();
            assertThat(cmp.left()).isEqualTo(num("10"));   // lo → first arg
            assertThat(cmp.right()).isEqualTo(num("20"));  // hi → second arg
        }
    }

    // ── planner-level inlining ──────────────────────────────────────────────────

    @Nested
    @DisplayName("planner inlining of table-valued-function calls")
    class PlannerInlining {

        private static final String ORDERS =
                "Orders := [| order_id | customer_id | amount |\n" +
                "           | 1        | 2           | 100    |];\n";

        private static final String CUSTOMERS =
                "Customers := [| customer_id | name  |\n" +
                "              | 2           | Alice |];\n";

        private static PhysicalNode planFirstQuery(String src) {
            SemanticModel model = model(src);
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
            return new Planner(model.symbolTable(), model.nodeSchemas()).plan(logical);
        }

        @Test
        @DisplayName("a call whose body is another call inlines transitively — no call survives")
        void transitiveInlining() {
            PhysicalNode plan = planFirstQuery(
                    "def inner(x: NUMBER): RELATION := { σ customer_id = x (Orders) };\n" +
                    "def outer(y: NUMBER): RELATION := { inner(y) };\n" + ORDERS +
                    "query { outer(2) };");
            // outer(2) → inner(2) → σ customer_id = 2 (Orders): a Select over a Scan.
            assertThat(plan).isNode(PhysicalNode.Select.class);
            assertThat(((PhysicalNode.Select) plan).input()).isNode(PhysicalNode.Scan.class);
            assertThat(PhysicalPlanPrinter.explain(plan))
                    .doesNotContain("inner").doesNotContain("outer");
        }

        @Test
        @DisplayName("the inlined body is re-annotated so a join over it resolves its keys")
        void schemaReannotationLetsJoinResolveKeys() {
            // The natural join needs the schema of BOTH inputs to extract its key
            // columns; the right input is the freshly substituted TVF body, which only
            // resolves because the planner re-annotates it after inlining.
            PhysicalNode plan = planFirstQuery(
                    "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n"
                    + ORDERS + CUSTOMERS +
                    "query { Customers ⋈ ordersFor(2) };");

            PhysicalNode.Join join = assertThat(plan).asNode(PhysicalNode.Join.class);
            // common column customer_id drives the natural-join keys on both sides
            assertThat(join.keys().left()).isNotEmpty();
            assertThat(join.keys().right()).isNotEmpty();
            assertThat(join.right()).isNode(PhysicalNode.Select.class);
        }

        @Test
        @DisplayName("a directly recursive function is rejected at plan time")
        void recursiveFunctionRejected() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> planFirstQuery(
                            "def loop(): RELATION := { loop() };\n"
                            + "query { loop() };"))
                    .withMessageContaining("Recursive");
        }

        @Test
        @DisplayName("a mutually recursive pair is rejected at plan time")
        void mutuallyRecursiveRejected() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> planFirstQuery(
                            "def ping(): RELATION := { pong() };\n" +
                            "def pong(): RELATION := { ping() };\n" +
                            "query { ping() };"))
                    .withMessageContaining("Recursive");
        }
    }

    // ── substitution reaches every operand-bearing construct ────────────────────

    @Nested
    @DisplayName("bind() reaches every operand-bearing construct")
    class SubstitutionCoverage {

        private static final com.darkcollective.relix.ast.RelationNode A =
                new com.darkcollective.relix.ast.RelationNode("A");
        private static final com.darkcollective.relix.ast.RelationNode B =
                new com.darkcollective.relix.ast.RelationNode("B");
        private static final NumberOperand ARG = num("7");

        private static RelationFunctionSymbol fn(RelNode body) {
            return RelationFunctionSymbol.builder("f")
                    .parameter("p", ScalarType.NUMBER)
                    .body(body)
                    .build();
        }

        private static RelNode bind(RelNode body) {
            return RelationFunctionInliner.bind(fn(body), List.of(ARG));
        }

        private static com.darkcollective.relix.ast.Predicate onParam() {
            return cmp(attr("col"),
                    ComparisonOperator.EQUAL, attr("p"));
        }

        /** The bound tree must contain no bare reference to the parameter. */
        private static void assertFullyBound(RelNode bound) {
            assertThat(bound.toString()).doesNotContain("AttributeOperand[name=p,");
        }

        @Test
        @DisplayName("conditions of all seven conditional-join node types")
        void allConditionalJoins() {
            List<RelNode> joins = List.of(
                    new com.darkcollective.relix.ast.ThetaJoinNode(A, B, onParam()),
                    new com.darkcollective.relix.ast.LeftOuterJoinNode(A, B, onParam()),
                    new com.darkcollective.relix.ast.RightOuterJoinNode(A, B, onParam()),
                    new com.darkcollective.relix.ast.FullOuterJoinNode(A, B, onParam()),
                    new com.darkcollective.relix.ast.SemiJoinNode(A, B, onParam()),
                    new com.darkcollective.relix.ast.AntiJoinNode(A, B, onParam()),
                    new com.darkcollective.relix.ast.PairwiseUniversalNode(A, B, onParam()));
            for (RelNode join : joins) {
                assertFullyBound(bind(join));
            }
        }

        @Test
        @DisplayName("OR / NOT / NULL / ∈ / LIKE predicate shapes inside a σ")
        void allPredicateShapes() {
            var p = attr("p");
            List<com.darkcollective.relix.ast.Predicate> preds = List.of(
                    new com.darkcollective.relix.ast.OrPredicate(onParam(), onParam()),
                    new com.darkcollective.relix.ast.NotPredicate(onParam()),
                    new com.darkcollective.relix.ast.NullPredicate(p, true),
                    new com.darkcollective.relix.ast.ElementOfPredicate(
                            attr("col"),
                            new com.darkcollective.relix.ast.SetLiteralOperand(List.of(p)),
                            false),
                    new com.darkcollective.relix.ast.PatternPredicate(
                            attr("col"), p, false));
            for (var pred : preds) {
                assertFullyBound(bind(select(pred, A)));
            }
        }

        @Test
        @DisplayName("grouping keys and aggregate arguments of a γ")
        void aggregation() {
            var agg = new com.darkcollective.relix.ast.AggregationNode(
                    List.of(com.darkcollective.relix.ast.GroupingKey.of(
                            arith(attr("p"),
                                    ArithmeticOperator.PLUS, attr("col")))),
                    List.of(com.darkcollective.relix.ast.AggregateFunction.of(
                            com.darkcollective.relix.ast.AggregateOperator.SUM,
                            attr("p"))),
                    A, com.darkcollective.relix.ast.SourceLocation.UNKNOWN);
            assertFullyBound(bind(agg));
        }

        @Test
        @DisplayName("all three window-function families")
        void windowFunctions() {
            var sort = List.of(new com.darkcollective.relix.ast.SortSpecification(
                    attr("ts"),
                    com.darkcollective.relix.ast.SortDirection.ASC));
            var frame = new com.darkcollective.relix.ast.WindowFrame.PartitionFrame();
            var p = attr("p");

            var aggregate = new com.darkcollective.relix.ast.WindowNode(
                    new com.darkcollective.relix.ast.WindowFunction.AggregateWindow(
                            com.darkcollective.relix.ast.AggregateOperator.AVG, p),
                    List.of("k"), sort, frame, "out", A);
            var ranking = new com.darkcollective.relix.ast.WindowNode(
                    new com.darkcollective.relix.ast.WindowFunction.RankingWindow(
                            com.darkcollective.relix.ast.RankingFunction.NTILE,
                            java.util.Optional.of(p)),
                    List.of("k"), sort, frame, "out", A);
            var offset = new com.darkcollective.relix.ast.WindowNode(
                    new com.darkcollective.relix.ast.WindowFunction.OffsetWindow(
                            com.darkcollective.relix.ast.OffsetFunction.LAG, p,
                            java.util.Optional.of(p), java.util.Optional.of(p)),
                    List.of("k"), sort, frame, "out", A);

            for (RelNode wn : List.of(aggregate, ranking, offset)) {
                assertFullyBound(bind(wn));
            }
        }

        @Test
        @DisplayName("SESSIONIZE threshold")
        void sessionizeThreshold() {
            var sn = new com.darkcollective.relix.ast.SessionizeNode(
                    A, "ts", attr("p"), List.of(), "session");
            assertFullyBound(bind(sn));
        }

        @Test
        @DisplayName("SOLVE operands and OPTIMIZE objective/constraints")
        void solveAndOptimize() {
            var p = attr("p");
            var solve = new com.darkcollective.relix.ast.SolveNode(p, p, A);
            assertFullyBound(bind(solve));

            var optimize = new com.darkcollective.relix.ast.OptimizeNode(
                    com.darkcollective.relix.ast.ObjectiveSense.MAXIMIZE,
                    attr("p"),
                    List.of(new com.darkcollective.relix.ast.OptimizeConstraint(
                            attr("p"), ComparisonOperator.LESS_EQUAL, 100.0)),
                    List.of(), A);
            assertFullyBound(bind(optimize));
        }

        @Test
        @DisplayName("∀ predicate and a nested TVF call's arguments")
        void universalAndNestedCall() {
            var universal = new com.darkcollective.relix.ast.UniversalNode(
                    List.of("k"), onParam(), A);
            assertFullyBound(bind(universal));

            var nested = tvf("g",attr("p"));
            assertFullyBound(bind(nested));
        }

        @Test
        @DisplayName("unary / struct / array / condition / call operand forms; literals untouched")
        void operandForms() {
            var p = attr("p");
            var date = new com.darkcollective.relix.ast.DateOperand(java.time.LocalDate.of(2026, 1, 1));
            var proj = project(List.of(
                    ProjectedAttribute.aliased(new com.darkcollective.relix.ast.UnaryOperand(p), "u"),
                    ProjectedAttribute.aliased(new com.darkcollective.relix.ast.StructConstruction(
                            List.of(new com.darkcollective.relix.ast.StructConstruction.Field("f", p))), "s"),
                    ProjectedAttribute.aliased(new com.darkcollective.relix.ast.ArrayConstruction(
                            List.of(p)), "a"),
                    ProjectedAttribute.aliased(new com.darkcollective.relix.ast.ConditionOperand(
                            onParam()), "c"),
                    ProjectedAttribute.aliased(func("Abs",p), "abs"),
                    ProjectedAttribute.aliased(date, "d")), A);

            RelNode bound = bind(proj);

            assertFullyBound(bound);
            var attrs = ((ProjectionNode) bound).attributes();
            assertThat(attrs.get(5).expression()).isSameAs(date);
        }
    }
}

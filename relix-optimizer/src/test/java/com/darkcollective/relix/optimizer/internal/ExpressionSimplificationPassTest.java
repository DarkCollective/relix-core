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
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.OffsetFunction;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProduceBound;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SessionizeNode;
import com.darkcollective.relix.ast.SolveNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.TieBreak;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.ast.TreeNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("ExpressionSimplificationPass")
final class ExpressionSimplificationPassTest {

    private OptimizationContext ctx;
    private static final SchemaAnnotations SCHEMAS = SchemaAnnotations.empty();

    @BeforeEach
    void setUp() {
        ctx = new OptimizationContext();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private RelNode apply(RelNode node) {
        return ExpressionSimplificationPass.apply(node, "Q", SCHEMAS, ctx);
    }

    // =========================================================================
    // Leaf node
    // =========================================================================

    @Nested
    @DisplayName("RelationNode (leaf) — no change")
    class Leaf {

        @Test @DisplayName("returned unchanged")
        void leafUnchanged() {
            var rel = rel("Users");
            assertThat(apply(rel)).isSameAs(rel);
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // ProjectionNode
    // =========================================================================

    @Nested
    @DisplayName("ProjectionNode")
    class Projections {

        @Test @DisplayName("foldable expression in projection is simplified")
        void foldableExpressionSimplified() {
            // π price * 1 → price (EXPR-004)
            var attr = attr("price");
            var attrs = List.of(ProjectedAttribute.simple(
                    arith(attr, ArithmeticOperator.MULTIPLY, num("1"))));
            var node = project(attrs, rel("P"));

            RelNode result = apply(node);

            assertThat(result).isNode(ProjectionNode.class);
            var proj = (ProjectionNode) result;
            assertThat(proj.attributes().get(0).expression()).isSameAs(attr);
            assertThat(ctx).fired(OptimizationCode.EXPR_004, 1);
        }

        @Test @DisplayName("unchanged projection returns same instance")
        void unchangedProjectionReturnsSameInstance() {
            var attrs = List.of(ProjectedAttribute.simple(attr("id")));
            var node  = project(attrs, rel("P"));
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("constant expression in aliased projection is folded")
        void aliasedProjectionFolded() {
            var attrs = List.of(ProjectedAttribute.aliased(
                    arith(num("2"), ArithmeticOperator.MULTIPLY, num("3")), "six"));
            var node = project(attrs, rel("P"));

            RelNode result = apply(node);

            var proj = (ProjectionNode) result;
            assertThat(proj.attributes().get(0).expression()).isEqualTo(num("6"));
        }

        @Test @DisplayName("an IIf condition in a projection is simplified too (#542)")
        void conditionInsideProjectedIifSimplified() {
            // π IIf(qty * 1 > 0, "some", "none") → the condition folds to `qty > 0`
            var cond = cmp(arith(attr("qty"), ArithmeticOperator.MULTIPLY, num("1")),
                    ComparisonOperator.GREATER, num("0"));
            var call = new com.darkcollective.relix.ast.FunctionCall("IIf", List.of(
                    new com.darkcollective.relix.ast.ConditionOperand(cond),
                    new com.darkcollective.relix.ast.StringOperand("some"),
                    new com.darkcollective.relix.ast.StringOperand("none")));
            var node = project(
                    List.of(ProjectedAttribute.aliased(call, "label")), rel("P"));

            var proj = (ProjectionNode) apply(node);

            var rewritten = (com.darkcollective.relix.ast.FunctionCall)
                    proj.attributes().get(0).expression();
            var condition = ((com.darkcollective.relix.ast.ConditionOperand)
                    rewritten.arguments().get(0)).predicate();
            assertThat(((ComparisonPredicate) condition).left()).isEqualTo(attr("qty"));
            assertThat(ctx).fired(OptimizationCode.EXPR_004, 1);
        }
    }

    // =========================================================================
    // SelectionNode
    // =========================================================================

    @Nested
    @DisplayName("SelectionNode")
    class Selections {

        @Test @DisplayName("comparison predicate operands are simplified")
        void comparisonOperandsSimplified() {
            // σ price + 0 > 10 (P)  →  σ price > 10 (P)
            var priceAttr = attr("price");
            var predicate = cmp(
                    arith(priceAttr, ArithmeticOperator.PLUS, num("0")),
                    ComparisonOperator.GREATER,
                    num("10"));
            var node = select(predicate, rel("P"));

            RelNode result = apply(node);

            assertThat(result).isNode(SelectionNode.class);
            var sel = (SelectionNode) result;
            var newPred = (ComparisonPredicate) sel.predicate();
            assertThat(newPred.left()).isSameAs(priceAttr);
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1);
        }

        @Test @DisplayName("unchanged selection returns same instance")
        void unchangedSelectionReturnsSameInstance() {
            var predicate = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var node = select(predicate, rel("P"));
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("nested AND predicate — both sides simplified")
        void nestedAndPredicate() {
            // σ (2*3 > x) AND (y + 0 = 5)
            var left  = cmp(arith(num("2"), ArithmeticOperator.MULTIPLY, num("3")),
                            ComparisonOperator.GREATER, attr("x"));
            var right = cmp(arith(attr("y"), ArithmeticOperator.PLUS, num("0")),
                            ComparisonOperator.EQUAL, num("5"));
            var pred = and(left, right);
            var node = select(pred, rel("P"));

            apply(node);

            assertThat(ctx).fired(OptimizationCode.EXPR_001, 1); // 2*3→6
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1); // y+0→y
        }
    }

    // =========================================================================
    // ThetaJoinNode
    // =========================================================================

    @Nested
    @DisplayName("ThetaJoinNode")
    class ThetaJoin {

        @Test @DisplayName("join condition operands are simplified")
        void joinConditionSimplified() {
            // A ⨝ A.id = B.id + 0 B
            var bId   = attr("B.id");
            var left  = rel("A");
            var right = rel("B");
            var cond  = cmp(attr("A.id"), ComparisonOperator.EQUAL,
                            arith(bId, ArithmeticOperator.PLUS, num("0")));
            var join  = join(left, right, cond);

            RelNode result = apply(join);

            assertThat(result).isNode(ThetaJoinNode.class);
            var newJoin = (ThetaJoinNode) result;
            var newCond = (ComparisonPredicate) newJoin.condition();
            assertThat(newCond.right()).isSameAs(bId);
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1);
        }
    }

    // =========================================================================
    // Every other operand carrier (#564)
    // =========================================================================

    /**
     * Before #564 this pass reached only σ, π and the conditional joins; every other
     * node's own expressions were skipped, so a redundant {@code * 1} survived all the
     * way into the executor and was re-evaluated per row.  Driving the rewrite through
     * {@link com.darkcollective.relix.ast.internal.RelNodeOperands#map} reaches all of them —
     * one test here per carrier the walker enumerates.
     */
    @Nested
    @DisplayName("Every other operand carrier — reached through the exhaustive walker")
    class OtherCarriers {

        private final RelNode base = rel("T");

        /** {@code amount * 1} — EXPR-004 reduces it to the bare attribute. */
        private Operand foldable() {
            return arith(attr("amount"), ArithmeticOperator.MULTIPLY, num("1"));
        }

        /** Asserts EXPR-004 fired exactly {@code n} times and returns the rewritten node. */
        private RelNode folded(RelNode node, int n) {
            RelNode result = apply(node);
            assertThat(ctx).fired(OptimizationCode.EXPR_004, n);
            return result;
        }

        private SortSpecification foldableSpec() {
            return AstBuilders.sortKey(foldable(), SortDirection.ASC);
        }

        @Test @DisplayName("γ — an aggregate argument")
        void aggregateArgument() {
            var node = groupByKeys(
                    List.of(GroupingKey.column("cust")),
                    List.of(AggregateFunction.of(AggregateOperator.SUM, foldable())),
                    base);

            var result = (AggregationNode) folded(node, 1);
            assertThat(result.aggregates().getFirst().argument())
                    .isInstanceOf(AttributeOperand.class);
        }

        @Test @DisplayName("γ — an ARGMAX yield expression")
        void aggregateYieldExpression() {
            var node = groupByKeys(
                    List.of(GroupingKey.column("cust")),
                    List.of(argAgg(AggregateOperator.ARGMAX, attr("rank"),foldable(),"top")),
                    base);

            var result = (AggregationNode) folded(node, 1);
            assertThat(result.aggregates().getFirst().yieldExpr())
                    .get().isInstanceOf(AttributeOperand.class);
        }

        @Test @DisplayName("γ — a grouping key")
        void groupingKey() {
            var node = groupByKeys(
                    List.of(GroupingKey.aliased(foldable(), "k")),
                    List.of(AggregateFunction.simple(AggregateOperator.COUNT, "id")),
                    base);

            var result = (AggregationNode) folded(node, 1);
            assertThat(result.groupingKeys().getFirst().expression())
                    .isInstanceOf(AttributeOperand.class);
            assertThat(result.groupingKeys().getFirst().alias())
                    .as("the alias rides across the rebuild").contains("k");
        }

        @Test @DisplayName("τ — a sort key")
        void sortKey() {
            var result = (SortNode) folded(sort(List.of(foldableSpec()), base), 1);
            assertThat(result.sortSpecs().getFirst().expression())
                    .isInstanceOf(AttributeOperand.class);
            assertThat(result.sortSpecs().getFirst().direction()).isEqualTo(SortDirection.ASC);
        }

        @Test @DisplayName("TOP — a sort key")
        void topKSortKey() {
            var node = topK(List.of("cust"), List.of(foldableSpec()),
                    Optional.empty(), 3L, base);

            var result = (TopKNode) folded(node, 1);
            assertThat(result.sortSpecs().getFirst().expression())
                    .isInstanceOf(AttributeOperand.class);
            assertThat(result.count()).isEqualTo(3L);
        }

        @Test @DisplayName("TREE — a sibling-ordering key")
        void treeOrderKey() {
            var node = tree("id", "parent", List.of(foldableSpec()), "children",base);

            var result = (TreeNode) folded(node, 1);
            assertThat(result.orderSpecs().getFirst().expression())
                    .isInstanceOf(AttributeOperand.class);
        }

        @Test @DisplayName("∀ — operands inside its predicate")
        void universalPredicate() {
            var node = universal(List.of("cust"),
                    cmp(foldable(), ComparisonOperator.GREATER, num("5")), base);

            var result = (UniversalNode) folded(node, 1);
            assertThat(((ComparisonPredicate) result.predicate()).left())
                    .isInstanceOf(AttributeOperand.class);
        }

        @Test @DisplayName("AS-OF — operands in its condition, and its tolerance")
        void asOfConditionAndTolerance() {
            var node = asOfJoin(base, rel("U"),
                    cmp(foldable(), ComparisonOperator.LESS_EQUAL, attr("u")),
                    Optional.of(arith(attr("gap"), ArithmeticOperator.MULTIPLY, num("1"))),
                    true, TieBreak.FIRST);

            var result = (AsOfJoinNode) folded(node, 2);
            assertThat(((ComparisonPredicate) result.condition()).left())
                    .isInstanceOf(AttributeOperand.class);
            assertThat(result.tolerance()).get().isInstanceOf(AttributeOperand.class);
        }

        @Test @DisplayName("CLOSURE — both endpoint bounds")
        void closureBounds() {
            var node = closure("src", "dst", false, false,
                    Optional.of(foldable()), Optional.of(foldable()),base);

            var result = (ClosureNode) folded(node, 2);
            assertThat(result.boundSource()).get().isInstanceOf(AttributeOperand.class);
            assertThat(result.boundTarget()).get().isInstanceOf(AttributeOperand.class);
        }

        @Test @DisplayName("TRACE — both endpoint bounds")
        void traceBounds() {
            var node = trace("src", "dst", false, "w", ObjectiveSense.MINIMIZE, "path",
                    Optional.of(foldable()), Optional.of(foldable()),base);

            var result = (TraceNode) folded(node, 2);
            assertThat(result.boundSource()).get().isInstanceOf(AttributeOperand.class);
            assertThat(result.boundTarget()).get().isInstanceOf(AttributeOperand.class);
        }

        @Test @DisplayName("SOLVE — both sides of the equation")
        void solveOperands() {
            var result = (SolveNode) folded(solve(foldable(), foldable(), base), 2);
            assertThat(result.left()).isInstanceOf(AttributeOperand.class);
            assertThat(result.right()).isInstanceOf(AttributeOperand.class);
        }

        @Test @DisplayName("OPTIMIZE — its objective and every constraint expression")
        void optimizeOperands() {
            var node = optimize(ObjectiveSense.MAXIMIZE, foldable(),
                    List.of(constraint(foldable(),
                            ComparisonOperator.LESS_EQUAL, 5)),
                    List.of("cust"), Optional.empty(), base);

            var result = (OptimizeNode) folded(node, 2);
            assertThat(result.objective()).isInstanceOf(AttributeOperand.class);
            assertThat(result.constraints().getFirst().expr())
                    .isInstanceOf(AttributeOperand.class);
            assertThat(result.groupingKeys()).containsExactly("cust");
        }

        @Test @DisplayName("SESSIONIZE — its gap threshold")
        void sessionizeThreshold() {
            var node = sessionize("at", foldable(), "session",base);

            var result = (SessionizeNode) folded(node, 1);
            assertThat(result.threshold()).isInstanceOf(AttributeOperand.class);
        }

        @Test @DisplayName("WINDOW — the function's operands and the within-partition ordering")
        void windowOperands() {
            var node = new WindowNode(
                    new WindowFunction.OffsetWindow(OffsetFunction.LAG, foldable(),
                            Optional.of(foldable()), Optional.of(foldable())),
                    List.of("cust"), List.of(foldableSpec()),
                    new WindowFrame.PartitionFrame(), "prev", base, SourceLocation.UNKNOWN);

            var result = (WindowNode) folded(node, 4);
            var fn = (WindowFunction.OffsetWindow) result.function();
            assertThat(fn.expression()).isInstanceOf(AttributeOperand.class);
            assertThat(fn.offset()).get().isInstanceOf(AttributeOperand.class);
            assertThat(fn.defaultValue()).get().isInstanceOf(AttributeOperand.class);
            assertThat(result.sortSpecs().getFirst().expression())
                    .isInstanceOf(AttributeOperand.class);
            assertThat(result.outputColumn()).isEqualTo("prev");
        }

        @Test @DisplayName("a generator's produce bound")
        void produceBound() {
            var node = rel("Naturals",AstBuilders.produceBound("n", ComparisonOperator.LESS,
                            arith(num("50"), ArithmeticOperator.MULTIPLY, num("2"))));

            RelNode result = apply(node);

            assertThat(ctx).fired(OptimizationCode.EXPR_001, 1);   // 50*2 → 100
            assertThat(((RelationNode) result).produceBound()).get()
                    .extracting(ProduceBound::limit).isEqualTo(num("100"));
        }

        @Test @DisplayName("a TVF call's arguments — no structural traversal reaches them")
        void relationFunctionCallArguments() {
            var node = tvf("ordersFor",foldable(), arith(num("1"), ArithmeticOperator.PLUS, num("1")));

            var result = (RelationFunctionCall) apply(node);

            assertThat(ctx).fired(OptimizationCode.EXPR_004, 1);
            assertThat(ctx).fired(OptimizationCode.EXPR_001, 1);
            assertThat(result.arguments().getFirst()).isInstanceOf(AttributeOperand.class);
            assertThat(result.arguments().get(1)).isEqualTo(num("2"));
        }

        @Test @DisplayName("a LATERAL join's arguments — children() reports only the left input")
        void lateralArguments() {
            var node = lateral(base, "ordersFor",foldable());

            var result = (LateralJoinNode) folded(node, 1);
            assertThat(result.arguments().getFirst()).isInstanceOf(AttributeOperand.class);
            assertThat(result.left()).isSameAs(base);
        }

        @Test @DisplayName("a carrier with nothing to fold comes back by reference")
        void cleanCarriersUnchanged() {
            List<RelNode> clean = List.of(
                    sort(List.of(AstBuilders.sortKey(attr("amount"),
                            SortDirection.ASC)), base),
                    groupByKeys(List.of(GroupingKey.column("cust")),
                            List.of(AggregateFunction.simple(AggregateOperator.SUM, "amount")),
                            base),
                    solve(attr("x"), num("10"), base),
                    tvf("ordersFor",attr("cust")),
                    lateral(base, "ordersFor",attr("cust")));

            assertThat(clean).allSatisfy(node -> assertThat(apply(node)).isSameAs(node));
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // Recursive simplification in nested trees
    // =========================================================================

    @Nested
    @DisplayName("Recursive tree simplification")
    class Recursive {

        @Test @DisplayName("simplification descends into child nodes")
        void descendsIntoChildren() {
            // σ (age > 18) (π price * 1 (Products))
            var priceAttr = attr("price");
            var innerAttrs = List.of(ProjectedAttribute.simple(
                    arith(priceAttr, ArithmeticOperator.MULTIPLY, num("1"))));
            var proj = project(innerAttrs, rel("Products"));
            var sel  = select(
                    cmp(attr("age"), ComparisonOperator.GREATER, num("18")), proj);

            RelNode result = apply(sel);

            assertThat(result).isNode(SelectionNode.class);
            var newSel = (SelectionNode) result;
            assertThat(newSel.input()).isNode(ProjectionNode.class);
            var newProj = (ProjectionNode) newSel.input();
            assertThat(newProj.attributes().get(0).expression()).isSameAs(priceAttr);
            assertThat(ctx).fired(OptimizationCode.EXPR_004, 1);
        }

        @Test @DisplayName("no rules fire on a plain tree — entire tree returned same instance")
        void noRulesFireReturnsSameInstance() {
            var proj = project(
                    List.of(ProjectedAttribute.simple(attr("id"))),
                    rel("Users"));
            assertThat(apply(proj)).isSameAs(proj);
        }
    }

    // =========================================================================
    // Remaining conditional-join arms — every join type gets the same operand
    // simplification inside its condition
    // =========================================================================

    @Nested
    @DisplayName("Outer/semi/anti/∀ join conditions — operands simplified")
    class OtherJoinArms {

        private final RelNode l = rel("A");
        private final RelNode r = rel("B");

        /** {@code A.id = B.id + 0} — EXPR-003 strips the {@code + 0}. */
        private Predicate foldable() {
            return cmp(attr("A.id"), ComparisonOperator.EQUAL,
                       arith(attr("B.id"), ArithmeticOperator.PLUS, num("0")));
        }

        private void assertFolded(RelNode result) {
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1);
            assertThat(result).isNotNull();
        }

        @Test @DisplayName("left outer join")
        void leftOuter() {
            assertFolded(apply(new com.darkcollective.relix.ast.LeftOuterJoinNode(l, r, foldable())));
        }

        @Test @DisplayName("right outer join")
        void rightOuter() {
            assertFolded(apply(new com.darkcollective.relix.ast.RightOuterJoinNode(l, r, foldable())));
        }

        @Test @DisplayName("full outer join")
        void fullOuter() {
            assertFolded(apply(new com.darkcollective.relix.ast.FullOuterJoinNode(l, r, foldable())));
        }

        @Test @DisplayName("semi-join")
        void semiJoin() {
            assertFolded(apply(new com.darkcollective.relix.ast.SemiJoinNode(l, r, foldable())));
        }

        @Test @DisplayName("anti-join")
        void antiJoin() {
            assertFolded(apply(new com.darkcollective.relix.ast.AntiJoinNode(l, r, foldable())));
        }

        @Test @DisplayName("pairwise-∀ join")
        void pairwiseUniversal() {
            assertFolded(apply(new com.darkcollective.relix.ast.PairwiseUniversalNode(l, r, foldable())));
        }

        @Test @DisplayName("clean join condition — node returned by reference")
        void cleanJoinSameInstance() {
            var join = new com.darkcollective.relix.ast.SemiJoinNode(
                    l, r, cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id")));
            assertThat(apply(join)).isSameAs(join);
        }
    }

    // =========================================================================
    // Predicate-type arms beyond comparison/AND — Null, ∈, LIKE, OR, NOT
    // =========================================================================

    @Nested
    @DisplayName("Predicate arms — operands simplified inside every predicate type")
    class PredicateArms {

        private SelectionNode sel(Predicate p) {
            return select(p, rel("T"));
        }

        /** {@code x + 0} — EXPR-003 reduces it to the bare attribute. */
        private Operand foldableOperand() {
            return arith(attr("x"), ArithmeticOperator.PLUS, num("0"));
        }

        @Test @DisplayName("NULL predicate operand is simplified")
        void nullPredicate() {
            var node = sel(new com.darkcollective.relix.ast.NullPredicate(foldableOperand(), true));
            var result = (SelectionNode) apply(node);
            var pred = (com.darkcollective.relix.ast.NullPredicate) result.predicate();
            assertThat(pred.operand()).isInstanceOf(AttributeOperand.class);
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1);
        }

        @Test @DisplayName("∈ predicate element and set expressions are simplified")
        void elementOfPredicate() {
            var node = sel(new com.darkcollective.relix.ast.ElementOfPredicate(
                    foldableOperand(), attr("allowed"), false));
            var result = (SelectionNode) apply(node);
            var pred = (com.darkcollective.relix.ast.ElementOfPredicate) result.predicate();
            assertThat(pred.element()).isInstanceOf(AttributeOperand.class);
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1);
        }

        @Test @DisplayName("LIKE predicate operand is simplified")
        void patternPredicate() {
            var node = sel(new com.darkcollective.relix.ast.PatternPredicate(
                    foldableOperand(), new com.darkcollective.relix.ast.StringOperand("%a%"), false));
            var result = (SelectionNode) apply(node);
            var pred = (com.darkcollective.relix.ast.PatternPredicate) result.predicate();
            assertThat(pred.operand()).isInstanceOf(AttributeOperand.class);
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1);
        }

        @Test @DisplayName("OR predicate — both sides simplified")
        void orPredicate() {
            var left  = cmp(foldableOperand(), ComparisonOperator.GREATER, num("1"));
            var right = cmp(foldableOperand(), ComparisonOperator.LESS, num("9"));
            var node = sel(new com.darkcollective.relix.ast.OrPredicate(left, right));
            apply(node);
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 2);
        }

        @Test @DisplayName("NOT predicate — inner simplified")
        void notPredicate() {
            var node = sel(new com.darkcollective.relix.ast.NotPredicate(
                    cmp(foldableOperand(), ComparisonOperator.EQUAL, num("5"))));
            apply(node);
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1);
        }

        @Test @DisplayName("clean Null / ∈ / LIKE / OR / NOT predicates — same instance back")
        void cleanPredicatesUnchanged() {
            var candidates = List.<Predicate>of(
                    new com.darkcollective.relix.ast.NullPredicate(attr("x"), false),
                    new com.darkcollective.relix.ast.ElementOfPredicate(attr("x"), attr("s"), true),
                    new com.darkcollective.relix.ast.PatternPredicate(attr("x"), new com.darkcollective.relix.ast.StringOperand("%a"), true),
                    new com.darkcollective.relix.ast.OrPredicate(
                            cmp(attr("x"), ComparisonOperator.GREATER, num("1")),
                            cmp(attr("x"), ComparisonOperator.LESS, num("9"))),
                    new com.darkcollective.relix.ast.NotPredicate(
                            cmp(attr("x"), ComparisonOperator.EQUAL, num("5"))));
            for (Predicate p : candidates) {
                var node = sel(p);
                assertThat(apply(node)).isSameAs(node);
            }
        }
    }
}

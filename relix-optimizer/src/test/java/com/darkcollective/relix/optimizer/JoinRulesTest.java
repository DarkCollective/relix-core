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

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Join rules — JOIN-001 and JOIN-002")
final class JoinRulesTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private RelNode applyJoin(RelNode node, SchemaAnnotations schemas) {
        return JoinRulesPass.apply(node, "Q", schemas, ctx);
    }

    private RelNode applyJoin(RelNode node) {
        return applyJoin(node, SchemaAnnotations.empty());
    }

    private static Schema schema(String... cols) {
        return new Schema(List.of(cols).stream()
                .map(c -> new ColumnDefinition(c, ScalarType.NUMBER))
                .toList());
    }

    private static SchemaAnnotations annotate(RelNode n1, Schema s1,
                                               RelNode n2, Schema s2) {
        var map = new IdentityHashMap<RelNode, Schema>();
        map.put(n1, s1);
        map.put(n2, s2);
        return new SchemaAnnotations(map);
    }

    private static SchemaAnnotations annotate(RelNode n1, Schema s1) {
        var map = new IdentityHashMap<RelNode, Schema>();
        map.put(n1, s1);
        return new SchemaAnnotations(map);
    }

    // =========================================================================
    // JOIN-001 — cross-product + selection → theta join
    // =========================================================================

    @Nested
    @DisplayName("JOIN-001 — cartesian product + selection → theta join")
    class Join001 {

        @Test
        @DisplayName("σ(A.id = B.fk)(A × B) → A ⨝_{A.id=B.fk} B")
        void basicConversion() {
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);
            var pred = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var sel  = select(pred, prod);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("fk", "amount"));

            RelNode result = applyJoin(sel, schemas);

            assertThat(result).isNode(ThetaJoinNode.class);
            var tj = (ThetaJoinNode) result;
            assertThat(tj.left()).isSameAs(a);
            assertThat(tj.right()).isSameAs(b);
            assertThat(tj.condition()).isSameAs(pred);
            assertThat(ctx).fired(OptimizationCode.JOIN_001, 1);
            assertThat(ctx).didNotFire(OptimizationCode.JOIN_002);
        }

        @Test
        @DisplayName("qualified attribute refs still convert: σ(A.id = B.id)(A × B)")
        void qualifiedAttributeConversion() {
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);
            // Both schemas have "id" — the pred has "A.id" and "B.id"
            // PredicateAttributeCollector strips qualifiers → {"id"}
            // anyLeft = anyRight = true → JOIN-001 fires
            var pred = cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id"));
            var sel  = select(pred, prod);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("id", "fk"));

            RelNode result = applyJoin(sel, schemas);

            assertThat(result).isNode(ThetaJoinNode.class);
            assertThat(ctx).fired(OptimizationCode.JOIN_001, 1);
        }

        @Test
        @DisplayName("no conversion when schemas are not annotated")
        void noConversionWithoutSchemas() {
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);
            var pred = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var sel  = select(pred, prod);

            // No annotations
            RelNode result = applyJoin(sel, SchemaAnnotations.empty());

            assertThat(result).isSameAs(sel);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("no conversion when only one side annotated")
        void noConversionWithOneAnnotation() {
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);
            var pred = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var sel  = select(pred, prod);

            // Only annotate one side
            RelNode result = applyJoin(sel, annotate(a, schema("id")));

            assertThat(result).isSameAs(sel);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("no conversion when predicate attributes not found in either schema")
        void noConversionUnknownColumns() {
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);
            // pred uses columns "x" and "y" which don't exist in either schema
            var pred = cmp(attr("x"), ComparisonOperator.EQUAL, attr("y"));
            var sel  = select(pred, prod);

            SchemaAnnotations schemas = annotate(a, schema("id"), b, schema("fk"));

            RelNode result = applyJoin(sel, schemas);

            assertThat(result).isSameAs(sel);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("leaf nodes (RelationNode) are not affected")
        void leafNodeUnchanged() {
            var r = rel("R");
            assertThat(applyJoin(r)).isSameAs(r);
        }

        @Test
        @DisplayName("ProductNode without wrapping selection passes through unchanged")
        void productWithoutSelectionUnchanged() {
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);

            RelNode result = applyJoin(prod);

            assertThat(result).isSameAs(prod);
            assertThat(ctx.records()).isEmpty();
        }
    }

    // =========================================================================
    // JOIN-002 — one-sided selection over product → push into product input
    // =========================================================================

    @Nested
    @DisplayName("JOIN-002 — one-sided selection over product or theta join")
    class Join002 {

        @Test
        @DisplayName("σ(left-pred)(A × B) pushes selection into left input")
        void pushToLeftProductInput() {
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);
            var pred = cmp(attr("name"), ComparisonOperator.EQUAL, str("Alice"));
            var sel  = select(pred, prod);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("fk", "amount"));

            RelNode result = applyJoin(sel, schemas);

            assertThat(result).isNode(ProductNode.class);
            var newProd = (ProductNode) result;
            assertThat(newProd.right()).isSameAs(b);
            assertThat(newProd.left()).isNode(SelectionNode.class);
            var newSel = (SelectionNode) newProd.left();
            assertThat(newSel.predicate()).isSameAs(pred);
            assertThat(newSel.input()).isSameAs(a);
            assertThat(ctx).fired(OptimizationCode.JOIN_002, 1);
            assertThat(ctx).didNotFire(OptimizationCode.JOIN_001);
        }

        @Test
        @DisplayName("σ(right-pred)(A × B) pushes selection into right input")
        void pushToRightProductInput() {
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);
            var pred = cmp(attr("amount"), ComparisonOperator.GREATER, num("100"));
            var sel  = select(pred, prod);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("fk", "amount"));

            RelNode result = applyJoin(sel, schemas);

            assertThat(result).isNode(ProductNode.class);
            var newProd = (ProductNode) result;
            assertThat(newProd.left()).isSameAs(a);
            assertThat(newProd.right()).isNode(SelectionNode.class);
            var newSel = (SelectionNode) newProd.right();
            assertThat(newSel.predicate()).isSameAs(pred);
            assertThat(newSel.input()).isSameAs(b);
            assertThat(ctx).fired(OptimizationCode.JOIN_002, 1);
        }

        @Test
        @DisplayName("σ(left-pred)(A ⨝_q B) pushes selection into left theta-join input")
        void pushToLeftThetaJoinInput() {
            var a    = rel("A");
            var b    = rel("B");
            var cond = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var join = join(a, b, cond);
            var pred = cmp(attr("name"), ComparisonOperator.EQUAL, str("Bob"));
            var sel  = select(pred, join);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("fk", "amount"));

            RelNode result = applyJoin(sel, schemas);

            assertThat(result).isNode(ThetaJoinNode.class);
            var newJoin = (ThetaJoinNode) result;
            assertThat(newJoin.condition()).isSameAs(cond);
            assertThat(newJoin.right()).isSameAs(b);
            assertThat(newJoin.left()).isNode(SelectionNode.class);
            var newSel = (SelectionNode) newJoin.left();
            assertThat(newSel.predicate()).isSameAs(pred);
            assertThat(ctx).fired(OptimizationCode.JOIN_002, 1);
        }

        @Test
        @DisplayName("σ(right-pred)(A ⨝_q B) pushes selection into right theta-join input")
        void pushToRightThetaJoinInput() {
            var a    = rel("A");
            var b    = rel("B");
            var cond = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var join = join(a, b, cond);
            var pred = cmp(attr("amount"), ComparisonOperator.GREATER, num("50"));
            var sel  = select(pred, join);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("fk", "amount"));

            RelNode result = applyJoin(sel, schemas);

            assertThat(result).isNode(ThetaJoinNode.class);
            var newJoin = (ThetaJoinNode) result;
            assertThat(newJoin.condition()).isSameAs(cond);
            assertThat(newJoin.left()).isSameAs(a);
            assertThat(newJoin.right()).isNode(SelectionNode.class);
            var newSel = (SelectionNode) newJoin.right();
            assertThat(newSel.predicate()).isSameAs(pred);
            assertThat(ctx).fired(OptimizationCode.JOIN_002, 1);
        }

        @Test
        @DisplayName("JOIN-002 skipped when schemas not annotated for join inputs")
        void noJoin002WithoutSchemas() {
            var a    = rel("A");
            var b    = rel("B");
            var cond = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var join = join(a, b, cond);
            var pred = cmp(attr("name"), ComparisonOperator.EQUAL, str("X"));
            var sel  = select(pred, join);

            RelNode result = applyJoin(sel, SchemaAnnotations.empty());

            assertThat(result).isSameAs(sel);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("JOIN-002 skipped when predicate spans both join sides")
        void noJoin002WhenPredSpansBothSides() {
            var a    = rel("A");
            var b    = rel("B");
            var cond = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var join = join(a, b, cond);
            // pred references columns from both sides
            var pred = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var sel  = select(pred, join);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("fk", "amount"));

            RelNode result = applyJoin(sel, schemas);

            assertThat(result).isSameAs(sel);
            assertThat(ctx.records()).isEmpty();
        }
    }

    // =========================================================================
    // Two-step optimisation: stacked selections over a product
    // =========================================================================

    @Nested
    @DisplayName("Combined — JOIN-002 then JOIN-001 (stacked selections over product)")
    class Combined {

        @Test
        @DisplayName("σ(join_cond)(σ(filter)(A × B)) → σ(filter)(A) ⨝_{join_cond} B")
        void stackedSelectionsOverProduct() {
            // Setup: A has {id, name}, B has {fk, amount}
            // Inner sel:  σ(name='Alice')(A × B)  — name is only in A → JOIN-002
            // Outer sel:  σ(id = fk)(result)      — crosses both sides → JOIN-001
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);

            var filter    = cmp(attr("name"), ComparisonOperator.EQUAL, str("Alice"));
            var joinCond  = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var innerSel  = select(filter, prod);
            var outerSel  = select(joinCond, innerSel);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("fk", "amount"));

            RelNode result = applyJoin(outerSel, schemas);

            // Expected: ThetaJoinNode(σ(filter)(A), B, joinCond)
            assertThat(result).isNode(ThetaJoinNode.class);
            var tj = (ThetaJoinNode) result;
            assertThat(tj.condition()).isSameAs(joinCond);
            assertThat(tj.right()).isSameAs(b);
            assertThat(tj.left()).isNode(SelectionNode.class);
            var leftSel = (SelectionNode) tj.left();
            assertThat(leftSel.predicate()).isSameAs(filter);
            assertThat(leftSel.input()).isSameAs(a);
            assertThat(ctx).fired(OptimizationCode.JOIN_001, 1);
            assertThat(ctx).fired(OptimizationCode.JOIN_002, 1);
        }

        @Test
        @DisplayName("σ(join_cond)(σ(filter)(A × B)) right-side filter then join-001")
        void stackedSelectionsRightFilter() {
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);

            var filter   = cmp(attr("amount"), ComparisonOperator.GREATER, num("100"));
            var joinCond = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var innerSel = select(filter, prod);
            var outerSel = select(joinCond, innerSel);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("fk", "amount"));

            RelNode result = applyJoin(outerSel, schemas);

            assertThat(result).isNode(ThetaJoinNode.class);
            var tj = (ThetaJoinNode) result;
            assertThat(tj.condition()).isSameAs(joinCond);
            assertThat(tj.left()).isSameAs(a);
            assertThat(tj.right()).isNode(SelectionNode.class);
            var rightSel = (SelectionNode) tj.right();
            assertThat(rightSel.predicate()).isSameAs(filter);
            assertThat(rightSel.input()).isSameAs(b);
            assertThat(ctx).fired(OptimizationCode.JOIN_001, 1);
            assertThat(ctx).fired(OptimizationCode.JOIN_002, 1);
        }

        @Test
        @DisplayName("nested product inside a NaturalJoin — inner product converted, outer join untouched")
        void nestedProductInsideNaturalJoin() {
            var a    = rel("A");
            var b    = rel("B");
            var c    = rel("C");
            var prod = product(a, b);
            var pred = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var sel  = select(pred, prod);

            // Natural join of the selection result and C
            var nj   = naturalJoin(sel, c);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("fk", "amount"));

            RelNode result = applyJoin(nj, schemas);

            // NaturalJoin structure preserved; inner product→theta conversion applied
            assertThat(result).isNode(NaturalJoinNode.class);
            var resultNJ = (NaturalJoinNode) result;
            assertThat(resultNJ.right()).isSameAs(c);
            assertThat(resultNJ.left()).isNode(ThetaJoinNode.class);
            assertThat(ctx).fired(OptimizationCode.JOIN_001, 1);
        }
    }

    // =========================================================================
    // No-op cases
    // =========================================================================

    @Nested
    @DisplayName("No-op — nodes not involved in join rules pass through unchanged")
    class NoOp {

        @Test
        @DisplayName("NaturalJoinNode without wrapping selection passes through")
        void naturalJoinUnchanged() {
            var a  = rel("A");
            var b  = rel("B");
            var nj = naturalJoin(a, b);

            assertThat(applyJoin(nj)).isSameAs(nj);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("ThetaJoinNode without wrapping selection passes through")
        void thetaJoinUnchanged() {
            var a    = rel("A");
            var b    = rel("B");
            var cond = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var tj   = join(a, b, cond);

            assertThat(applyJoin(tj)).isSameAs(tj);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("σ over a RelationNode passes through unchanged")
        void selectionOverRelationUnchanged() {
            var r    = rel("R");
            var pred = cmp(attr("a"), ComparisonOperator.EQUAL, num("1"));
            var sel  = select(pred, r);

            assertThat(applyJoin(sel)).isSameAs(sel);
            assertThat(ctx.records()).isEmpty();
        }
    }

    // =========================================================================
    // QueryOptimizer wiring
    // =========================================================================

    @Nested
    @DisplayName("QueryOptimizer wiring")
    class OptimizerWiring {

        @Test
        @DisplayName("JOIN-001 fires through QueryOptimizer")
        void join001ThroughOptimizer() {
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);
            var pred = cmp(attr("id"), ComparisonOperator.EQUAL, attr("fk"));
            var sel  = select(pred, prod);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("fk", "amount"));
            var ctx2 = new OptimizationContext();
            RelNode result = new QueryOptimizer().optimize(sel, "Q", schemas, ctx2);

            assertThat(result).isNode(ThetaJoinNode.class);
            assertThat(ctx2).fired(OptimizationCode.JOIN_001, 1);
        }

        @Test
        @DisplayName("JOIN-002 fires through QueryOptimizer for one-sided product predicate")
        void join002ThroughOptimizer() {
            var a    = rel("A");
            var b    = rel("B");
            var prod = product(a, b);
            var pred = cmp(attr("name"), ComparisonOperator.EQUAL, str("Alice"));
            var sel  = select(pred, prod);

            SchemaAnnotations schemas = annotate(a, schema("id", "name"),
                                                 b, schema("fk", "amount"));
            var ctx2 = new OptimizationContext();
            RelNode result = new QueryOptimizer().optimize(sel, "Q", schemas, ctx2);

            assertThat(result).isNode(ProductNode.class);
            var np = (ProductNode) result;
            assertThat(np.left()).isNode(SelectionNode.class);
            assertThat(np.right()).isSameAs(b);
            assertThat(ctx2).fired(OptimizationCode.JOIN_002);
        }
    }
}

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
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.UnionAllNode;
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
import java.util.Map;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Selection rules — SEL-001..006")
final class SelectionRulesTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private RelNode applySplit(RelNode node) {
        return SelectionSplitPass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private RelNode applyMerge(RelNode node) {
        return SelectionMergePass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private RelNode applyPushdown(RelNode node, SchemaAnnotations schemas) {
        return SelectionPushdownPass.apply(node, "Q", schemas, ctx);
    }

    private RelNode applyPushdown(RelNode node) {
        return applyPushdown(node, SchemaAnnotations.empty());
    }

    private static Schema schema(String... cols) {
        return new Schema(List.of(cols).stream()
                .map(c -> new ColumnDefinition(c, ScalarType.NUMBER))
                .toList());
    }

    private static SchemaAnnotations annotate(RelNode node, Schema s) {
        var map = new IdentityHashMap<RelNode, Schema>();
        map.put(node, s);
        return new SchemaAnnotations(map);
    }

    private static SchemaAnnotations annotate(RelNode n1, Schema s1, RelNode n2, Schema s2) {
        var map = new IdentityHashMap<RelNode, Schema>();
        map.put(n1, s1);
        map.put(n2, s2);
        return new SchemaAnnotations(map);
    }

    // =========================================================================
    // SEL-001 — conjunctive predicate split
    // =========================================================================

    @Nested
    @DisplayName("SEL-001 — conjunctive predicate split")
    class Sel001 {

        @Test @DisplayName("σ(A ∧ B)(R) → σ(A)(σ(B)(R))")
        void splitConjunction() {
            var base = rel("T");
            var pA = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var pB = cmp(attr("active"), ComparisonOperator.EQUAL, num("1"));
            var node = select(and(pA, pB), base);

            RelNode result = applySplit(node);

            var outer = (SelectionNode) result;
            assertThat(outer.predicate()).isSameAs(pA);
            var inner = (SelectionNode) outer.input();
            assertThat(inner.predicate()).isSameAs(pB);
            assertThat(inner.input()).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.SEL_001, 1);
        }

        @Test @DisplayName("σ(A ∧ B ∧ C)(R) → σ(A)(σ(B)(σ(C)(R)))  (three-way split)")
        void tripleConjunction() {
            var base = rel("T");
            var pA = cmp(attr("a"), ComparisonOperator.EQUAL, num("1"));
            var pB = cmp(attr("b"), ComparisonOperator.EQUAL, num("2"));
            var pC = cmp(attr("c"), ComparisonOperator.EQUAL, num("3"));
            // Build right-nested AND: A ∧ (B ∧ C)
            var node = select(and(pA, and(pB, pC)), base);

            RelNode result = applySplit(node);

            var sel1 = (SelectionNode) result;
            assertThat(sel1.predicate()).isSameAs(pA);
            var sel2 = (SelectionNode) sel1.input();
            assertThat(sel2.predicate()).isSameAs(pB);
            var sel3 = (SelectionNode) sel2.input();
            assertThat(sel3.predicate()).isSameAs(pC);
            assertThat(sel3.input()).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.SEL_001, 2);
        }

        @Test @DisplayName("non-AND predicate is unchanged")
        void nonConjunctionUnchanged() {
            var base = rel("T");
            var p = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var node = select(p, base);
            assertThat(applySplit(node)).isSameAs(node);
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test @DisplayName("OR predicate is NOT split")
        void orPredicateUnchanged() {
            var base = rel("T");
            var p = or(
                    cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                    cmp(attr("active"), ComparisonOperator.EQUAL, num("1")));
            var node = select(p, base);
            assertThat(applySplit(node)).isSameAs(node);
        }

        @Test @DisplayName("split applies recursively to nested selections' inputs")
        void deepSplit() {
            var base = rel("T");
            var pA = cmp(attr("x"), ComparisonOperator.EQUAL, num("1"));
            var pB = cmp(attr("y"), ComparisonOperator.EQUAL, num("2"));
            var inner = select(and(pA, pB), base);
            var pC = cmp(attr("z"), ComparisonOperator.EQUAL, num("3"));
            var outer = select(pC, inner);

            RelNode result = applySplit(outer);

            // outer selection is unchanged (predicate pC is not AND)
            var outerSel = (SelectionNode) result;
            assertThat(outerSel.predicate()).isSameAs(pC);
            // inner was split
            var sel1 = (SelectionNode) outerSel.input();
            assertThat(sel1.predicate()).isSameAs(pA);
            var sel2 = (SelectionNode) sel1.input();
            assertThat(sel2.predicate()).isSameAs(pB);
        }
    }

    // =========================================================================
    // SEL-002 — adjacent selection merge
    // =========================================================================

    @Nested
    @DisplayName("SEL-002 — adjacent selection merge")
    class Sel002 {

        @Test @DisplayName("σ(A)(σ(B)(R)) → σ(A ∧ B)(R)")
        void mergeTwo() {
            var base = rel("T");
            var pA = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var pB = cmp(attr("active"), ComparisonOperator.EQUAL, num("1"));
            var inner = select(pB, base);
            var outer = select(pA, inner);

            RelNode result = applyMerge(outer);

            var merged = (SelectionNode) result;
            assertThat(merged.input()).isSameAs(base);
            var and = (AndPredicate) merged.predicate();
            assertThat(and.left()).isSameAs(pA);
            assertThat(and.right()).isSameAs(pB);
            assertThat(ctx).fired(OptimizationCode.SEL_002, 1);
        }

        @Test @DisplayName("σ(A)(σ(B)(σ(C)(R))) → σ(A ∧ B ∧ C)(R)  (three merged)")
        void mergeThree() {
            var base = rel("T");
            var pA = cmp(attr("a"), ComparisonOperator.EQUAL, num("1"));
            var pB = cmp(attr("b"), ComparisonOperator.EQUAL, num("2"));
            var pC = cmp(attr("c"), ComparisonOperator.EQUAL, num("3"));
            var sel = select(pA,
                    select(pB,
                            select(pC, base)));

            RelNode result = applyMerge(sel);

            var merged = (SelectionNode) result;
            assertThat(merged.input()).isSameAs(base);
            // Outer AND first
            var and1 = (AndPredicate) merged.predicate();
            assertThat(and1.left()).isSameAs(pA);
            var and2 = (AndPredicate) and1.right();
            assertThat(and2.left()).isSameAs(pB);
            assertThat(and2.right()).isSameAs(pC);
            assertThat(ctx).fired(OptimizationCode.SEL_002, 2);
        }

        @Test @DisplayName("single selection unchanged")
        void singleUnchanged() {
            var p = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var node = select(p, rel("T"));
            assertThat(applyMerge(node)).isSameAs(node);
        }

        @Test @DisplayName("split+merge roundtrip: σ(A ∧ B)(R) unchanged")
        void splitMergeRoundtrip() {
            var base = rel("T");
            var pA = cmp(attr("x"), ComparisonOperator.EQUAL, num("1"));
            var pB = cmp(attr("y"), ComparisonOperator.EQUAL, num("2"));
            var original = select(and(pA, pB), base);

            var splitCtx  = new OptimizationContext();
            var mergeCtx  = new OptimizationContext();
            RelNode split  = SelectionSplitPass.apply(original, "Q", SchemaAnnotations.empty(), splitCtx);
            RelNode merged = SelectionMergePass.apply(split,    "Q", SchemaAnnotations.empty(), mergeCtx);

            // Merged result: single SelectionNode with AND predicate
            var result = (SelectionNode) merged;
            assertThat(result.input()).isSameAs(base);
            var and = (AndPredicate) result.predicate();
            assertThat(and.left()).isSameAs(pA);
            assertThat(and.right()).isSameAs(pB);
        }
    }

    // =========================================================================
    // SEL-003 — push below projection
    // =========================================================================

    @Nested
    @DisplayName("SEL-003 — selection pushed below projection")
    class Sel003 {

        @Test @DisplayName("σ(age>18)(π id,age (T)) → π id,age (σ(age>18)(T))")
        void pushBelowProjection() {
            var base = rel("T");
            var inputSchema = schema("id", "age", "name");
            var proj = project(List.of(
                    ProjectedAttribute.simple(attr("id")),
                    ProjectedAttribute.simple(attr("age"))),
                    base);
            var pred = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var node = select(pred, proj);
            var schemas = annotate(base, inputSchema);

            RelNode result = applyPushdown(node, schemas);

            var newProj = (ProjectionNode) result;
            var newSel  = (SelectionNode) newProj.input();
            assertThat(newSel.predicate()).isSameAs(pred);
            assertThat(newSel.input()).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.SEL_003, 1);
        }

        @Test @DisplayName("push not applied when input schema unavailable")
        void noSchemaNosPush() {
            var base = rel("T");
            var proj = project(List.of(
                    ProjectedAttribute.simple(attr("id"))), base);
            var pred = cmp(attr("id"), ComparisonOperator.EQUAL, num("1"));
            var node = select(pred, proj);

            // empty schemas → no push
            RelNode result = applyPushdown(node);
            assertThat(result).isSameAs(node);
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test @DisplayName("push not applied when predicate column absent from input schema")
        void predicateColumnNotInInput() {
            var base = rel("T");
            // Input schema only has id; pred references 'score' which is absent
            var inputSchema = schema("id");
            var proj = project(List.of(
                    ProjectedAttribute.simple(attr("id"))), base);
            var pred = cmp(attr("score"), ComparisonOperator.GREATER, num("50"));
            var node = select(pred, proj);
            var schemas = annotate(base, inputSchema);

            RelNode result = applyPushdown(node, schemas);
            assertThat(result).isSameAs(node);
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test @DisplayName("push not applied when pred references a computed alias in projection")
        void computedAliasBlocksPush() {
            var base = rel("T");
            var inputSchema = schema("price");
            // π price*1.1 → adjusted (T)  — "adjusted" is a computed alias
            var expr = new com.darkcollective.relix.ast.BinaryArithmeticExpression(
                    attr("price"),
                    com.darkcollective.relix.ast.ArithmeticOperator.MULTIPLY,
                    num("1.1"));
            var proj = project(List.of(
                    ProjectedAttribute.aliased(expr, "adjusted")), base);
            // pred references "adjusted" — which is a computed alias, not the original column
            var pred = cmp(attr("adjusted"), ComparisonOperator.GREATER, num("100"));
            var node = select(pred, proj);
            var schemas = annotate(base, inputSchema);

            RelNode result = applyPushdown(node, schemas);
            // "adjusted" is not in the input schema → no push
            assertThat(result).isSameAs(node);
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test @DisplayName("literal-only predicate (1=1) is pushed regardless of projection")
        void constantPredicatePushed() {
            var base = rel("T");
            var inputSchema = schema("id");
            var proj = project(List.of(
                    ProjectedAttribute.simple(attr("id"))), base);
            // Constant predicate — no attr refs, so trivially all-in-input
            var pred = cmp(num("1"), ComparisonOperator.EQUAL, num("1"));
            var node = select(pred, proj);
            var schemas = annotate(base, inputSchema);

            RelNode result = applyPushdown(node, schemas);
            assertThat(result).isNode(ProjectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_003, 1);
        }
    }

    // =========================================================================
    // SEL-004 — push below rename
    // =========================================================================

    @Nested
    @DisplayName("SEL-004 — selection pushed below rename")
    class Sel004 {

        @Test @DisplayName("no column rename: σ(id>5)(ρ E (T)) → ρ E (σ(id>5)(T))")
        void pushBelowRelationRename() {
            var base = rel("T");
            var inputSchema = schema("id", "name");
            var rename = rename("E", List.of(), base);
            var pred = cmp(attr("id"), ComparisonOperator.GREATER, num("5"));
            var node = select(pred, rename);
            var schemas = annotate(base, inputSchema);

            RelNode result = applyPushdown(node, schemas);

            var newRename = (RenameNode) result;
            var newSel = (SelectionNode) newRename.input();
            assertThat(newSel.predicate()).isSameAs(pred); // pred unchanged
            assertThat(newSel.input()).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.SEL_004, 1);
        }

        @Test @DisplayName("column rename: σ(user_id>5)(ρ E(user_id,user_name)(T)) → ρ E(user_id,user_name)(σ(id>5)(T))")
        void pushBelowColumnRename() {
            var base = rel("T");
            var inputSchema = new Schema(List.of(
                    new ColumnDefinition("id",   ScalarType.NUMBER),
                    new ColumnDefinition("name", ScalarType.STRING)));
            var rename = rename("E", List.of("user_id", "user_name"), base);
            // pred uses NEW column name "user_id"
            var pred = cmp(attr("user_id"), ComparisonOperator.GREATER, num("5"));
            var node = select(pred, rename);
            var schemas = annotate(base, inputSchema);

            RelNode result = applyPushdown(node, schemas);

            var newRename = (RenameNode) result;
            var newSel = (SelectionNode) newRename.input();
            // pred should be rewritten to use OLD name "id"
            var newPred = (ComparisonPredicate) newSel.predicate();
            assertThat(((AttributeOperand) newPred.left()).name()).isEqualTo("id");
            assertThat(newSel.input()).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.SEL_004, 1);
        }

        @Test @DisplayName("push not applied when input schema unavailable")
        void noSchemaNoPush() {
            var base = rel("T");
            var rename = rename("E", List.of(), base);
            var pred = cmp(attr("id"), ComparisonOperator.EQUAL, num("1"));
            var node = select(pred, rename);

            assertThat(applyPushdown(node)).isSameAs(node);
        }
    }

    // =========================================================================
    // SEL-005 — push into join input
    // =========================================================================

    @Nested
    @DisplayName("SEL-005 — selection pushed into join input")
    class Sel005 {

        @Test @DisplayName("NaturalJoin: left-only predicate pushed into left input")
        void naturalJoinPushLeft() {
            var A = rel("A");
            var B = rel("B");
            var join = naturalJoin(A, B);
            var pred = cmp(attr("a_id"), ComparisonOperator.GREATER, num("0"));
            var node = select(pred, join);
            var schemas = annotate(A, schema("a_id", "shared"), B, schema("b_id", "shared"));

            RelNode result = applyPushdown(node, schemas);

            var newJoin = (NaturalJoinNode) result;
            var newSel  = (SelectionNode) newJoin.left();
            assertThat(newSel.predicate()).isSameAs(pred);
            assertThat(newSel.input()).isSameAs(A);
            assertThat(newJoin.right()).isSameAs(B);
            assertThat(ctx).fired(OptimizationCode.SEL_005, 1);
        }

        @Test @DisplayName("NaturalJoin: right-only predicate pushed into right input")
        void naturalJoinPushRight() {
            var A = rel("A");
            var B = rel("B");
            var join = naturalJoin(A, B);
            var pred = cmp(attr("b_id"), ComparisonOperator.EQUAL, num("42"));
            var node = select(pred, join);
            var schemas = annotate(A, schema("a_id"), B, schema("b_id", "extra"));

            RelNode result = applyPushdown(node, schemas);

            var newJoin = (NaturalJoinNode) result;
            assertThat(newJoin.left()).isSameAs(A);
            var newSel = (SelectionNode) newJoin.right();
            assertThat(newSel.predicate()).isSameAs(pred);
            assertThat(ctx).fired(OptimizationCode.SEL_005, 1);
        }

        @Test @DisplayName("NaturalJoin: cross-side predicate NOT pushed")
        void naturalJoinCrossSideNoPush() {
            var A = rel("A");
            var B = rel("B");
            var join = naturalJoin(A, B);
            // pred refs columns from both sides
            var pred = and(
                    cmp(attr("a_id"), ComparisonOperator.EQUAL, num("1")),
                    cmp(attr("b_id"), ComparisonOperator.EQUAL, num("1")));
            var node = select(pred, join);
            var schemas = annotate(A, schema("a_id"), B, schema("b_id"));

            RelNode result = applyPushdown(node, schemas);
            assertThat(result).isSameAs(node);
        }

        @Test @DisplayName("ThetaJoin: left-only predicate pushed into left input")
        void thetaJoinPushLeft() {
            var A = rel("A");
            var B = rel("B");
            var joinCond = cmp(attr("a_id"), ComparisonOperator.EQUAL, attr("b_a_id"));
            var join = join(A, B, joinCond);
            var selPred = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var node = select(selPred, join);
            var schemas = annotate(A, schema("a_id", "age"), B, schema("b_a_id", "score"));

            RelNode result = applyPushdown(node, schemas);

            var newJoin = (ThetaJoinNode) result;
            assertThat(newJoin.condition()).isSameAs(joinCond);
            var newSel = (SelectionNode) newJoin.left();
            assertThat(newSel.predicate()).isSameAs(selPred);
            assertThat(ctx).fired(OptimizationCode.SEL_005, 1);
        }

        @Test @DisplayName("no push when join input schemas unavailable")
        void noSchemaNoPush() {
            var A = rel("A");
            var B = rel("B");
            var join = naturalJoin(A, B);
            var pred = cmp(attr("a_id"), ComparisonOperator.EQUAL, num("1"));
            var node = select(pred, join);

            assertThat(applyPushdown(node)).isSameAs(node);
        }

        @Test @DisplayName("USEMI: left-only predicate pushed into left input")
        void usemiPushLeft() {
            var A = rel("A");
            var B = rel("B");
            var joinCond = cmp(attr("a_id"), ComparisonOperator.EQUAL, attr("b_a_id"));
            var join = pairwiseUniversal(A, B, joinCond);
            var selPred = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var node = select(selPred, join);
            var schemas = annotate(A, schema("a_id", "age"), B, schema("b_a_id", "score"));

            RelNode result = applyPushdown(node, schemas);

            var newJoin = (PairwiseUniversalNode) result;
            assertThat(newJoin.condition()).isSameAs(joinCond);
            var newSel = (SelectionNode) newJoin.left();
            assertThat(newSel.predicate()).isSameAs(selPred);
            assertThat(ctx).fired(OptimizationCode.SEL_005, 1);
        }

        @Test @DisplayName("USEMI: cross-side predicate NOT pushed")
        void usemiCrossSideNoPush() {
            var A = rel("A");
            var B = rel("B");
            var joinCond = cmp(attr("a_id"), ComparisonOperator.EQUAL, attr("b_a_id"));
            var join = pairwiseUniversal(A, B, joinCond);
            var pred = and(
                    cmp(attr("a_id"), ComparisonOperator.EQUAL, num("1")),
                    cmp(attr("b_a_id"), ComparisonOperator.EQUAL, num("1")));
            var node = select(pred, join);
            var schemas = annotate(A, schema("a_id"), B, schema("b_a_id"));

            RelNode result = applyPushdown(node, schemas);
            assertThat(result).isSameAs(node);
        }
    }

    // =========================================================================
    // SEL-006 — push into union-all branches
    // =========================================================================

    @Nested
    @DisplayName("SEL-006 — selection pushed into union-all branches")
    class Sel006 {

        @Test @DisplayName("σ(p)(A ⊎ B) → σ(p)(A) ⊎ σ(p)(B)")
        void pushIntoUnionAll() {
            var A = rel("A");
            var B = rel("B");
            var union = unionAll(A, B);
            var pred = cmp(attr("status"), ComparisonOperator.EQUAL, str("active"));
            var node = select(pred, union);

            RelNode result = applyPushdown(node);

            var newUnion = (UnionAllNode) result;
            var leftSel  = (SelectionNode) newUnion.left();
            var rightSel = (SelectionNode) newUnion.right();
            assertThat(leftSel.predicate()).isSameAs(pred);
            assertThat(leftSel.input()).isSameAs(A);
            assertThat(rightSel.predicate()).isSameAs(pred);
            assertThat(rightSel.input()).isSameAs(B);
            assertThat(ctx).fired(OptimizationCode.SEL_006, 1);
        }

        @Test @DisplayName("push recurses: stacked union-all pushes into all branches")
        void nestedUnionAll() {
            var A = rel("A");
            var B = rel("B");
            var C = rel("C");
            var innerUnion = unionAll(B, C);
            var outerUnion = unionAll(A, innerUnion);
            var pred = cmp(attr("val"), ComparisonOperator.GREATER, num("0"));
            var node = select(pred, outerUnion);

            RelNode result = applyPushdown(node);

            // outer: σ(p)(A ⊎ (B ⊎ C)) → σ(p)(A) ⊎ σ(p)(B ⊎ C)
            // inner push: σ(p)(B ⊎ C) → σ(p)(B) ⊎ σ(p)(C)
            var newOuter = (UnionAllNode) result;
            assertThat(newOuter.left()).isNode(SelectionNode.class);
            var newInner = (UnionAllNode) newOuter.right();
            assertThat(newInner.left()).isNode(SelectionNode.class);
            assertThat(newInner.right()).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_006, 2);
        }
    }

    // =========================================================================
    // Multi-level pushdown
    // =========================================================================

    @Nested
    @DisplayName("Multi-level pushdown")
    class MultiLevel {

        @Test @DisplayName("σ(p)(π(attrs)(ρ E (T))) → π(attrs)(ρ E (σ(p)(T)))  (two levels)")
        void pushThroughProjectionAndRename() {
            var base = rel("T");
            var inputSchema = schema("id", "age");
            var rename = rename("E", List.of(), base);
            var renameInput = base; // rename's input = base

            // For the test, annotate base (which is rename's input)
            // and also annotate rename's output (for projection's input check)
            var proj = project(List.of(
                    ProjectedAttribute.simple(attr("id")),
                    ProjectedAttribute.simple(attr("age"))),
                    rename);
            var pred = cmp(attr("age"), ComparisonOperator.GREATER, num("0"));
            var node = select(pred, proj);

            // Annotate both renameInput (base) and rename (for proj's input)
            var renameSchema = schema("id", "age"); // unchanged after relation-only rename
            var map = new IdentityHashMap<RelNode, Schema>();
            map.put(base, inputSchema);
            map.put(rename, renameSchema); // projection's input schema
            var schemas = new SchemaAnnotations(map);

            RelNode result = applyPushdown(node, schemas);

            // Should push below projection first, then below rename
            assertThat(result).isNode(ProjectionNode.class);
            var newProj = (ProjectionNode) result;
            assertThat(newProj.input()).isNode(RenameNode.class);
            var newRename = (RenameNode) newProj.input();
            assertThat(newRename.input()).isNode(SelectionNode.class);
            var newSel = (SelectionNode) newRename.input();
            assertThat(newSel.predicate()).isSameAs(pred);
            assertThat(newSel.input()).isSameAs(base);
        }
    }

    // =========================================================================
    // No-op
    // =========================================================================

    @Nested
    @DisplayName("No-op — tree returned unchanged")
    class NoOp {

        @Test @DisplayName("plain RelationNode returned as-is")
        void leafUnchanged() {
            var node = rel("T");
            assertThat(applySplit(node)).isSameAs(node);
            assertThat(applyMerge(node)).isSameAs(node);
            assertThat(applyPushdown(node)).isSameAs(node);
        }

        @Test @DisplayName("selection on a non-grouping column: not pushed below aggregation")
        void notPushedBelowAggregation() {
            // `salary` is neither a grouping key nor an aggregate output here, so
            // SEL-007 has nothing to match and the σ stays above the γ.
            var base  = rel("T");
            var agg   = new com.darkcollective.relix.ast.AggregationNode(
                    List.of("dept"), List.of(), base);
            var pred  = cmp(attr("salary"), ComparisonOperator.EQUAL, str("HR"));
            var node  = select(pred, agg);
            assertThat(applyPushdown(node)).isSameAs(node);
        }

        /**
         * Regression guard (ADR-0012): σ must NOT be pushed below COVER.
         * Filtering candidates changes the coverage universe, so
         * σ(p)(COVER t(R)) ≠ COVER t(σ(p)(R)) in general.
         * The SelectionPushdownPass only pushes below its named operator set;
         * it must leave σ(p)(COVER t(R)) structurally unchanged.
         */
        @Test @DisplayName("σ not pushed below COVER — filtering candidates changes the universe")
        void notPushedBelowCover() {
            var base  = rel("R");
            var cover = cover(2, base);
            var pred  = cmp(attr("x"), ComparisonOperator.EQUAL, str("a"));
            var node  = select(pred, cover);

            // The pushdown pass must return the tree unchanged.
            assertThat(applyPushdown(node)).isSameAs(node);
        }
    }

    // =========================================================================
    // PredicateAttributeCollector unit tests
    // =========================================================================

    @Nested
    @DisplayName("PredicateAttributeCollector")
    class CollectorTests {

        @Test @DisplayName("collects attrs from simple comparison")
        void simpleComparison() {
            var pred = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            assertThat(PredicateAttributeCollector.collectNames(pred))
                    .containsExactly("age");
        }

        @Test @DisplayName("collects attrs from AND predicate")
        void andPredicate() {
            var pred = and(
                    cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                    cmp(attr("status"), ComparisonOperator.EQUAL, str("active")));
            assertThat(PredicateAttributeCollector.collectNames(pred))
                    .containsExactlyInAnyOrder("age", "status");
        }

        @Test @DisplayName("collects attrs from nested NOT predicate")
        void notPredicate() {
            var pred = not(cmp(attr("deleted"), ComparisonOperator.EQUAL, num("1")));
            assertThat(PredicateAttributeCollector.collectNames(pred))
                    .containsExactly("deleted");
        }

        @Test @DisplayName("literals produce no attrs")
        void literals() {
            var pred = cmp(num("1"), ComparisonOperator.EQUAL, num("1"));
            assertThat(PredicateAttributeCollector.collectNames(pred)).isEmpty();
        }

        @Test @DisplayName("columnPart strips qualifier")
        void columnPartStripsQualifier() {
            assertThat(PredicateAttributeCollector.columnPart("Users.id")).isEqualTo("id");
            assertThat(PredicateAttributeCollector.columnPart("id")).isEqualTo("id");
            assertThat(PredicateAttributeCollector.columnPart("a.b.c")).isEqualTo("c");
        }
    }
}

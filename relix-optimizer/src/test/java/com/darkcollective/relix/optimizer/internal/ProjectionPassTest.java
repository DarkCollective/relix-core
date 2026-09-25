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
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
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

@DisplayName("Projection rules — PROJ-001..003")
final class ProjectionPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private RelNode applyProjection(RelNode node, SchemaAnnotations schemas) {
        return ProjectionPass.apply(node, "Q", schemas, ctx);
    }

    private RelNode applyProjection(RelNode node) {
        return applyProjection(node, SchemaAnnotations.empty());
    }

    private static ProjectedAttribute simple(Operand expr)            { return ProjectedAttribute.simple(expr); }
    private static ProjectedAttribute aliased(Operand expr, String a) { return ProjectedAttribute.aliased(expr, a); }

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

    private static SchemaAnnotations annotate(RelNode n1, Schema s1,
                                               RelNode n2, Schema s2) {
        var map = new IdentityHashMap<RelNode, Schema>();
        map.put(n1, s1);
        map.put(n2, s2);
        return new SchemaAnnotations(map);
    }

    // =========================================================================
    // PROJ-001 — redundant projection elimination
    // =========================================================================

    @Nested
    @DisplayName("PROJ-001 — redundant projection elimination")
    class Proj001 {

        @Test
        @DisplayName("π(a,b)(R) is redundant when input schema = {a, b}")
        void basicRedundant() {
            var base = rel("R");
            var proj = project(
                    List.of(simple(attr("a")), simple(attr("b"))), base);

            RelNode result = applyProjection(proj, annotate(base, schema("a", "b")));

            assertThat(result).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.PROJ_001, 1);
        }

        @Test
        @DisplayName("column order does not matter — different ordering is still redundant")
        void differentOrderRedundant() {
            var base = rel("R");
            var proj = project(
                    List.of(simple(attr("b")), simple(attr("a"))), base);

            RelNode result = applyProjection(proj, annotate(base, schema("a", "b")));

            assertThat(result).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.PROJ_001, 1);
        }

        @Test
        @DisplayName("column name comparison is case-insensitive")
        void caseInsensitiveMatch() {
            var base = rel("R");
            var proj = project(
                    List.of(simple(attr("Name")), simple(attr("ID"))), base);

            RelNode result = applyProjection(proj, annotate(base, schema("id", "name")));

            assertThat(result).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.PROJ_001, 1);
        }

        @Test
        @DisplayName("alias that equals the column name is still redundant")
        void aliasEqualToColumnNameIsRedundant() {
            var base = rel("R");
            var proj = project(
                    List.of(aliased(attr("a"), "a"), simple(attr("b"))), base);

            RelNode result = applyProjection(proj, annotate(base, schema("a", "b")));

            assertThat(result).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.PROJ_001, 1);
        }

        @Test
        @DisplayName("not redundant when alias renames a column")
        void aliasThatRenamesIsNotRedundant() {
            var base = rel("R");
            var proj = project(
                    List.of(aliased(attr("a"), "x"), simple(attr("b"))), base);

            RelNode result = applyProjection(proj, annotate(base, schema("a", "b")));

            assertThat(result).isSameAs(proj);
            assertThat(ctx).didNotFire(OptimizationCode.PROJ_001);
        }

        @Test
        @DisplayName("not redundant when projecting a subset of columns")
        void subsetNotRedundant() {
            var base = rel("R");
            var proj = project(
                    List.of(simple(attr("a"))), base);

            RelNode result = applyProjection(proj, annotate(base, schema("a", "b")));

            assertThat(result).isSameAs(proj);
            assertThat(ctx).didNotFire(OptimizationCode.PROJ_001);
        }

        @Test
        @DisplayName("not redundant when projecting a different column name")
        void differentColumnNotRedundant() {
            var base = rel("R");
            var proj = project(
                    List.of(simple(attr("a")), simple(attr("c"))), base);

            RelNode result = applyProjection(proj, annotate(base, schema("a", "b")));

            assertThat(result).isSameAs(proj);
            assertThat(ctx).didNotFire(OptimizationCode.PROJ_001);
        }

        @Test
        @DisplayName("not redundant when expression is computed (not a plain attribute ref)")
        void computedExpressionNotRedundant() {
            var base = rel("R");
            var computed = arith(attr("a"), ArithmeticOperator.PLUS, num("1"));
            var proj = project(
                    List.of(simple(computed), simple(attr("b"))), base);

            RelNode result = applyProjection(proj, annotate(base, schema("a", "b")));

            assertThat(result).isSameAs(proj);
            assertThat(ctx).didNotFire(OptimizationCode.PROJ_001);
        }

        @Test
        @DisplayName("skipped when no schema annotation is present for input")
        void noAnnotationIsSkipped() {
            var base = rel("R");
            var proj = project(
                    List.of(simple(attr("a")), simple(attr("b"))), base);

            RelNode result = applyProjection(proj, SchemaAnnotations.empty());

            assertThat(result).isSameAs(proj);
            assertThat(ctx).didNotFire(OptimizationCode.PROJ_001);
        }

        @Test
        @DisplayName("qualified attribute reference (table.col) is matched by column part")
        void qualifiedAttrIsRedundant() {
            var base = rel("R");
            var proj = project(
                    List.of(simple(attr("R.a")), simple(attr("R.b"))), base);

            RelNode result = applyProjection(proj, annotate(base, schema("a", "b")));

            assertThat(result).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.PROJ_001, 1);
        }

        @Test
        @DisplayName("redundant projection is applied recursively (bottom-up)")
        void recursivelyApplied() {
            var base = rel("R");
            var inner = project(List.of(simple(attr("a"))), base);
            var outer = project(List.of(simple(attr("a"))), inner);

            // Annotate both inner and base with single-column schema
            SchemaAnnotations schemas = annotate(
                    base,  schema("a"),
                    inner, schema("a"));

            // Bottom-up: inner is rewritten first → inner becomes base
            // Then outer (which now wraps base) is also redundant → becomes base
            RelNode result = applyProjection(outer, schemas);

            assertThat(result).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.PROJ_001, 2);
        }
    }

    // =========================================================================
    // PROJ-002 — consecutive projection merge
    // =========================================================================

    @Nested
    @DisplayName("PROJ-002 — consecutive projection merge")
    class Proj002 {

        @Test
        @DisplayName("π(a)(π(a, b)(R)) → π(a)(R)")
        void basicMerge() {
            var base = rel("R");
            var inner = project(
                    List.of(simple(attr("a")), simple(attr("b"))), base);
            var outer = project(List.of(simple(attr("a"))), inner);

            RelNode result = applyProjection(outer);

            assertThat(result).isNode(ProjectionNode.class);
            var merged = (ProjectionNode) result;
            assertThat(merged.input()).isSameAs(base);
            assertThat(merged.attributes()).hasSize(1);
            assertThat(((AttributeOperand) merged.attributes().get(0).expression()).name())
                    .isEqualTo("a");
            assertThat(merged.attributes().get(0).alias()).isEmpty();
            assertThat(ctx).fired(OptimizationCode.PROJ_002, 1);
        }

        @Test
        @DisplayName("outer alias is preserved: π(a as x)(π(a, b)(R)) → π(a as x)(R)")
        void outerAliasPreserved() {
            var base = rel("R");
            var inner = project(
                    List.of(simple(attr("a")), simple(attr("b"))), base);
            var outer = project(List.of(aliased(attr("a"), "x")), inner);

            RelNode result = applyProjection(outer);

            var merged = (ProjectionNode) result;
            assertThat(merged.input()).isSameAs(base);
            var pa = merged.attributes().get(0);
            assertThat(((AttributeOperand) pa.expression()).name()).isEqualTo("a");
            assertThat(pa.alias()).contains("x");
            assertThat(ctx).fired(OptimizationCode.PROJ_002, 1);
        }

        @Test
        @DisplayName("inner alias resolved by outer plain ref: π(n)(π(a as n, b)(R)) → π(a as n)(R)")
        void innerAliasResolvedByOuter() {
            var base = rel("R");
            var inner = project(
                    List.of(aliased(attr("a"), "n"), simple(attr("b"))), base);
            var outer = project(List.of(simple(attr("n"))), inner);

            RelNode result = applyProjection(outer);

            var merged = (ProjectionNode) result;
            assertThat(merged.input()).isSameAs(base);
            var pa = merged.attributes().get(0);
            assertThat(((AttributeOperand) pa.expression()).name()).isEqualTo("a");
            assertThat(pa.alias()).contains("n");
            assertThat(ctx).fired(OptimizationCode.PROJ_002, 1);
        }

        @Test
        @DisplayName("inner computed expression resolved: π(x)(π(a+1 as x, b)(R)) → π(a+1 as x)(R)")
        void innerComputedExpressionResolved() {
            var base = rel("R");
            var computed = arith(attr("a"), ArithmeticOperator.PLUS, num("1"));
            var inner = project(
                    List.of(aliased(computed, "x"), simple(attr("b"))), base);
            var outer = project(List.of(simple(attr("x"))), inner);

            RelNode result = applyProjection(outer);

            var merged = (ProjectionNode) result;
            assertThat(merged.input()).isSameAs(base);
            var pa = merged.attributes().get(0);
            assertThat(pa.expression()).isSameAs(computed);
            assertThat(pa.alias()).contains("x");
            assertThat(ctx).fired(OptimizationCode.PROJ_002, 1);
        }

        @Test
        @DisplayName("cannot merge when outer attr is a computed expression")
        void outerComputedExpressionBlocksMerge() {
            var base = rel("R");
            var computed = arith(attr("a"), ArithmeticOperator.PLUS, num("1"));
            var inner = project(List.of(simple(attr("a"))), base);
            var outer = project(List.of(simple(computed)), inner);

            RelNode result = applyProjection(outer);

            assertThat(result).isSameAs(outer);
            assertThat(ctx).didNotFire(OptimizationCode.PROJ_002);
        }

        @Test
        @DisplayName("cannot merge when outer references column not in inner output")
        void outerRefNotInInnerBlocksMerge() {
            var base = rel("R");
            var inner = project(List.of(simple(attr("a"))), base);
            var outer = project(
                    List.of(simple(attr("a")), simple(attr("b"))), inner);

            RelNode result = applyProjection(outer);

            assertThat(result).isSameAs(outer);
            assertThat(ctx).didNotFire(OptimizationCode.PROJ_002);
        }

        @Test
        @DisplayName("three stacked projections are merged step-by-step (PROJ-002 fires twice)")
        void threeStackedProjectionsMerge() {
            // bottom = π(a,b,c)(R), middle = π(a,b)(bottom), top = π(a)(middle)
            // Bottom-up: middle merges with bottom → π(a,b)(R);
            //            top  merges with result   → π(a)(R)
            var base   = rel("R");
            var bottom = project(
                    List.of(simple(attr("a")), simple(attr("b")), simple(attr("c"))), base);
            var middle = project(
                    List.of(simple(attr("a")), simple(attr("b"))), bottom);
            var top    = project(List.of(simple(attr("a"))), middle);

            RelNode result = applyProjection(top);

            assertThat(result).isNode(ProjectionNode.class);
            var merged = (ProjectionNode) result;
            assertThat(merged.input()).isSameAs(base);
            assertThat(merged.attributes()).hasSize(1);
            assertThat(((AttributeOperand) merged.attributes().get(0).expression()).name())
                    .isEqualTo("a");
            assertThat(ctx).fired(OptimizationCode.PROJ_002, 2);
        }

        @Test
        @DisplayName("multiple attribute merge")
        void multipleAttrMerge() {
            var base = rel("R");
            var inner = project(
                    List.of(simple(attr("a")), simple(attr("b")), simple(attr("c"))), base);
            var outer = project(
                    List.of(simple(attr("c")), simple(attr("a"))), inner);

            RelNode result = applyProjection(outer);

            var merged = (ProjectionNode) result;
            assertThat(merged.input()).isSameAs(base);
            assertThat(merged.attributes()).hasSize(2);
            assertThat(((AttributeOperand) merged.attributes().get(0).expression()).name())
                    .isEqualTo("c");
            assertThat(((AttributeOperand) merged.attributes().get(1).expression()).name())
                    .isEqualTo("a");
            assertThat(ctx).fired(OptimizationCode.PROJ_002, 1);
        }
    }

    // =========================================================================
    // PROJ-003 — projection push below selection
    // =========================================================================

    @Nested
    @DisplayName("PROJ-003 — projection push below selection")
    class Proj003 {

        @Test
        @DisplayName("π(a)(σ(a > 0)(R)) → σ(a > 0)(π(a)(R)) when pred attr ⊆ proj output")
        void basicPush() {
            var base = rel("R");
            var pred = cmp(attr("a"), ComparisonOperator.GREATER, num("0"));
            var sel  = select(pred, base);
            var proj = project(List.of(simple(attr("a"))), sel);

            RelNode result = applyProjection(proj);

            assertThat(result).isNode(SelectionNode.class);
            var outerSel = (SelectionNode) result;
            assertThat(outerSel.predicate()).isSameAs(pred);
            assertThat(outerSel.input()).isNode(ProjectionNode.class);
            var innerProj = (ProjectionNode) outerSel.input();
            assertThat(innerProj.input()).isSameAs(base);
            assertThat(ctx).fired(OptimizationCode.PROJ_003, 1);
        }

        @Test
        @DisplayName("cannot push when pred attrs not fully in projection output")
        void cannotPushWhenPredAttrsMissing() {
            var base = rel("R");
            var pred = cmp(attr("b"), ComparisonOperator.EQUAL, num("1"));
            var sel  = select(pred, base);
            // projection only has 'a', but pred needs 'b'
            var proj = project(List.of(simple(attr("a"))), sel);

            RelNode result = applyProjection(proj);

            assertThat(result).isSameAs(proj);
            assertThat(ctx).didNotFire(OptimizationCode.PROJ_003);
        }

        @Test
        @DisplayName("push when projection includes the aliased output that pred references")
        void pushThroughAlias() {
            var base = rel("R");
            var pred = cmp(attr("x"), ComparisonOperator.EQUAL, num("5"));
            var sel  = select(pred, base);
            // projection outputs alias "x" → pred can see "x"
            var proj = project(List.of(aliased(attr("a"), "x")), sel);

            RelNode result = applyProjection(proj);

            assertThat(result).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.PROJ_003, 1);
        }

        @Test
        @DisplayName("push when pred is a literal (no attribute references)")
        void pushLiteralPredicate() {
            var base = rel("R");
            // 1 = 1  — no attribute refs
            var pred = cmp(num("1"), ComparisonOperator.EQUAL, num("1"));
            var sel  = select(pred, base);
            var proj = project(List.of(simple(attr("a"))), sel);

            RelNode result = applyProjection(proj);

            assertThat(result).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.PROJ_003, 1);
        }

        @Test
        @DisplayName("projection is applied to both children bottom-up before PROJ-003 at root")
        void bottomUpRecursion() {
            // Build:  π(a)(σ(a>0)(π(a)(σ(a>0)(R))))
            // Bottom-up: inner π gets pushed, then outer π gets pushed
            var base  = rel("R");
            var pred  = cmp(attr("a"), ComparisonOperator.GREATER, num("0"));
            var iSel  = select(pred, base);
            var iProj = project(List.of(simple(attr("a"))), iSel);
            var oSel  = select(pred, iProj);
            var oProj = project(List.of(simple(attr("a"))), oSel);

            RelNode result = applyProjection(oProj);

            // Both projections should have been pushed below their selection
            assertThat(ctx).fired(OptimizationCode.PROJ_003, 2);
            // outermost should be a SelectionNode after push
            assertThat(result).isNode(SelectionNode.class);
        }

        @Test
        @DisplayName("cannot push when pred has attr not in projection output (qualified)")
        void qualifiedAttrInPredMustBeInProjection() {
            var base = rel("R");
            // pred uses "T.b" but projection only outputs "a"
            var pred = cmp(attr("T.b"), ComparisonOperator.EQUAL, num("1"));
            var sel  = select(pred, base);
            var proj = project(List.of(simple(attr("a"))), sel);

            RelNode result = applyProjection(proj);

            assertThat(result).isSameAs(proj);
            assertThat(ctx).didNotFire(OptimizationCode.PROJ_003);
        }
    }

    // =========================================================================
    // No-op cases — nodes other than ProjectionNode pass through unchanged
    // =========================================================================

    @Nested
    @DisplayName("No-op — non-projection nodes")
    class NoOp {

        @Test
        @DisplayName("RelationNode leaf is returned unchanged")
        void relationNodeUnchanged() {
            var r = rel("R");
            assertThat(applyProjection(r)).isSameAs(r);
        }

        @Test
        @DisplayName("SelectionNode without ProjectionNode input is returned unchanged")
        void selectionNodeNoProjection() {
            var base = rel("R");
            var pred = cmp(attr("a"), ComparisonOperator.EQUAL, num("1"));
            var sel  = select(pred, base);

            RelNode result = applyProjection(sel);

            assertThat(result).isSameAs(sel);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("DistinctNode over a relation passes through unchanged")
        void distinctNodeUnchanged() {
            var base = rel("R");
            var dist = distinct(base);

            RelNode result = applyProjection(dist);

            assertThat(result).isSameAs(dist);
        }

        @Test
        @DisplayName("NaturalJoinNode children are recursed but node is not transformed")
        void naturalJoinNoTransform() {
            var left  = rel("A");
            var right = rel("B");
            var join  = naturalJoin(left, right);

            RelNode result = applyProjection(join);

            assertThat(result).isSameAs(join);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("UnionNode children are recursed and node is returned unchanged when no rules fire")
        void unionNodeNoTransform() {
            var left  = rel("A");
            var right = rel("B");
            var union = union(left, right);

            RelNode result = applyProjection(union);

            assertThat(result).isSameAs(union);
            assertThat(ctx.records()).isEmpty();
        }

        @Test
        @DisplayName("projection rule fires inside binary node children")
        void projectionFiredInsideBinaryNode() {
            // left: π(a)(σ(a>0)(R)) — should be pushed (PROJ-003)
            var base = rel("R");
            var pred = cmp(attr("a"), ComparisonOperator.GREATER, num("0"));
            var sel  = select(pred, base);
            var proj = project(List.of(simple(attr("a"))), sel);

            var right = rel("S");
            var join  = naturalJoin(proj, right);

            RelNode result = applyProjection(join);

            assertThat(result).isNode(NaturalJoinNode.class);
            var resultJoin = (NaturalJoinNode) result;
            // Left child should have been rewritten by PROJ-003
            assertThat(resultJoin.left()).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.PROJ_003, 1);
        }
    }

    // =========================================================================
    // QueryOptimizer wiring
    // =========================================================================

    @Nested
    @DisplayName("QueryOptimizer wiring")
    class OptimizerWiring {

        @Test
        @DisplayName("PROJ-001 fires through QueryOptimizer when annotation is present")
        void proj001ThroughOptimizer() {
            var optimizer = new QueryOptimizer();
            var base      = rel("R");
            var proj      = project(
                    List.of(simple(attr("a")), simple(attr("b"))), base);

            var ctx2       = new OptimizationContext();
            SchemaAnnotations schemas = annotate(base, schema("a", "b"));

            RelNode result = optimizer.optimize(proj, "Q", schemas, ctx2);

            assertThat(result).isSameAs(base);
            assertThat(ctx2).fired(OptimizationCode.PROJ_001, 1);
        }

        @Test
        @DisplayName("PROJ-003 fires through QueryOptimizer")
        void proj003ThroughOptimizer() {
            var optimizer = new QueryOptimizer();
            var base      = rel("R");
            var pred      = cmp(attr("a"), ComparisonOperator.GREATER, num("0"));
            var sel       = select(pred, base);
            var proj      = project(List.of(simple(attr("a"))), sel);

            var ctx2 = new OptimizationContext();

            RelNode result = optimizer.optimize(proj, "Q", SchemaAnnotations.empty(), ctx2);

            // SEL-003 (selection pushdown past projection) fires in phase 4,
            // but PROJ-003 in the cleanup phase also fires in the other direction.
            // The net result is that the projection pass records PROJ-003.
            assertThat(ctx2).fired(OptimizationCode.PROJ_003);
        }

        @Test
        @DisplayName("PROJ-002 fires through QueryOptimizer")
        void proj002ThroughOptimizer() {
            var optimizer = new QueryOptimizer();
            var base      = rel("R");
            var inner     = project(
                    List.of(simple(attr("a")), simple(attr("b"))), base);
            var outer     = project(List.of(simple(attr("a"))), inner);

            var ctx2 = new OptimizationContext();

            RelNode result = optimizer.optimize(outer, "Q", SchemaAnnotations.empty(), ctx2);

            assertThat(result).isNode(ProjectionNode.class);
            var merged = (ProjectionNode) result;
            assertThat(merged.input()).isSameAs(base);
            assertThat(ctx2).fired(OptimizationCode.PROJ_002, 1);
        }
    }
}

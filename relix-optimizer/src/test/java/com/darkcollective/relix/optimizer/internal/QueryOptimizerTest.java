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
import com.darkcollective.relix.optimizer.TransformationRecord;
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.CompositionNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.SampleNode;
import com.darkcollective.relix.ast.SolveNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QueryOptimizer")
final class QueryOptimizerTest {

    private final QueryOptimizer optimizer = new QueryOptimizer();

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static ProjectedAttribute simple(AttributeOperand ao) {
        return ProjectedAttribute.simple(ao);
    }

    private static Schema schema(String... cols) {
        var defs = new java.util.ArrayList<ColumnDefinition>(cols.length);
        for (String col : cols) defs.add(new ColumnDefinition(col, ScalarType.NUMBER));
        return new Schema(defs);
    }

    /** Build a minimal SemanticModel with no queries and an empty symbol table. */
    private static SemanticModel emptyModel() {
        return new SemanticModel("default", new InMemorySymbolTable(),
                Map.of(), SchemaAnnotations.empty(), List.of());
    }

    /**
     * Build a SemanticModel that registers {@code qr} and issues a single
     * {@code query <name>;} statement.
     */
    private static SemanticModel modelWith(QueryRelationSymbol qr,
                                           SchemaAnnotations schemas) {
        var table = new InMemorySymbolTable();
        table.register(qr);
        return new SemanticModel("default", table, Map.of(), schemas,
                List.of(query(qr.declaredName())));
    }

    /** Model with a single named query over a source symbol (not a QR). */
    private static SemanticModel modelWithSource(String name, Schema schema) {
        var sym = SourceRelationSymbol.of(name, schema);
        var table = new InMemorySymbolTable();
        table.register(sym);
        return new SemanticModel("default", table, Map.of(), SchemaAnnotations.empty(),
                List.of(query(name)));
    }

    /** Model with a single inline expression query. */
    private static SemanticModel modelWithExpression(RelNode expression,
                                                      SchemaAnnotations schemas) {
        return new SemanticModel("default", new InMemorySymbolTable(),
                Map.of(), schemas,
                List.of(query(expression)));
    }

    // =========================================================================
    // optimize(RelNode, ...) — single-node entry point
    // =========================================================================

    @Nested
    @DisplayName("optimize(RelNode, ...) — single-node entry point")
    class SingleNodeOptimize {

        @Test
        @DisplayName("leaf RelationNode passes through unchanged")
        void leafRelationNodePassesThrough() {
            RelNode node = rel("Users");
            var ctx = new OptimizationContext();

            RelNode result = optimizer.optimize(
                    node, "Users", SchemaAnnotations.empty(), ctx);

            assertThat(result).isSameAs(node);
        }

        @Test
        @DisplayName("σ src = c (CLOSURE …) is folded to a single-source bound (CLOSURE-001) by the full pipeline")
        void selectionFoldedIntoClosure() {
            RelNode tree = select(
                    cmp(attr("src"), ComparisonOperator.EQUAL,
                            num("1")),
                    closure("src", "dst",rel("Edges")));
            var ctx = new OptimizationContext();

            RelNode result = optimizer.optimize(tree, "Q", SchemaAnnotations.empty(), ctx);

            assertThat(result).isNode(ClosureNode.class);
            assertThat(((ClosureNode) result).boundSource()).contains(num("1"));
            assertThat(ctx.recordsFor(OptimizationCode.CLOSURE_001)).hasSize(1);
        }

        @Test
        @DisplayName("σ src = c (TRACE …) is folded to a single-source bound (TRACE-001) by the full pipeline")
        void selectionFoldedIntoTrace() {
            RelNode tree = select(
                    cmp(attr("src"), ComparisonOperator.EQUAL,
                            num("1")),
                    new com.darkcollective.relix.ast.TraceNode(rel("Edges"),
                            "src", "dst", "cost",
                            com.darkcollective.relix.ast.ObjectiveSense.MINIMIZE, "route"));
            var ctx = new OptimizationContext();

            RelNode result = optimizer.optimize(tree, "Q", SchemaAnnotations.empty(), ctx);

            assertThat(result).isInstanceOf(com.darkcollective.relix.ast.TraceNode.class);
            assertThat(((com.darkcollective.relix.ast.TraceNode) result).boundSource())
                    .contains(num("1"));
            assertThat(ctx.recordsFor(OptimizationCode.TRACE_001)).hasSize(1);
        }

        @Test
        @DisplayName("leaf RelationNode records no transformations")
        void leafRelationNodeRecordsNothing() {
            RelNode node = rel("Users");
            var ctx = new OptimizationContext();

            optimizer.optimize(node, "Users", SchemaAnnotations.empty(), ctx);

            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("DIST-001 is wired: a redundant δ over γ is removed by the pipeline")
        void redundantDistinctEliminatedByPipeline() {
            var agg = groupBy(List.of("region"),
                    List.of(AggregateFunction.simple(AggregateOperator.SUM, "amount")),
                    rel("R"));
            var ctx = new OptimizationContext();

            RelNode result = optimizer.optimize(
                    distinct(agg), "Totals", SchemaAnnotations.empty(), ctx);

            assertThat(result).isSameAs(agg);
            assertThat(ctx.recordsFor(OptimizationCode.DIST_001)).isNotEmpty();
        }

        @Test
        @DisplayName("SORT-001 is wired: a redundant τ over a compatible τ is removed")
        void redundantSortEliminatedByPipeline() {
            var inner = sort(
                    List.of(asc("x")), rel("R"));
            var outer = sort(
                    List.of(asc("x")), inner);
            var ctx = new OptimizationContext();

            RelNode result = optimizer.optimize(outer, "Ordered", SchemaAnnotations.empty(), ctx);

            assertThat(result).isSameAs(inner);
            assertThat(ctx.recordsFor(OptimizationCode.SORT_001)).isNotEmpty();
        }

        @Test
        @DisplayName("null node throws NullPointerException")
        void nullNodeThrows() {
            var ctx = new OptimizationContext();
            assertThatThrownBy(() ->
                    optimizer.optimize(null, "Q", SchemaAnnotations.empty(), ctx))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("node");
        }

        @Test
        @DisplayName("null queryName throws NullPointerException")
        void nullQueryNameThrows() {
            var ctx = new OptimizationContext();
            assertThatThrownBy(() ->
                    optimizer.optimize(rel("R"), null,
                            SchemaAnnotations.empty(), ctx))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("queryName");
        }

        @Test
        @DisplayName("null schemas throws NullPointerException")
        void nullSchemasThrows() {
            var ctx = new OptimizationContext();
            assertThatThrownBy(() ->
                    optimizer.optimize(rel("R"), "Q", null, ctx))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("schemas");
        }

        @Test
        @DisplayName("null context throws NullPointerException")
        void nullContextThrows() {
            assertThatThrownBy(() ->
                    optimizer.optimize(rel("R"), "Q",
                            SchemaAnnotations.empty(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ctx");
        }
    }

    // =========================================================================
    // Symmetric difference & composition — every pass recurses through them
    // =========================================================================

    @Nested
    @DisplayName("Symmetric difference ∆ / composition ∘ — pass recursion")
    class SetCompositionRecursion {

        @Test
        @DisplayName("a ∆ of plain relations is preserved by all passes")
        void symmetricDifferencePreserved() {
            RelNode node = symmetricDifference(
                    rel("A"), rel("B"));
            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(SymmetricDifferenceNode.class);
        }

        @Test
        @DisplayName("a ∘ of plain relations is preserved by all passes")
        void compositionPreserved() {
            RelNode node = composition(
                    rel("A"), rel("B"));
            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(CompositionNode.class);
        }

        @Test
        @DisplayName("passes recurse into ∆ children — a splittable selection is rewritten under it")
        void recursesIntoSymmetricDifferenceChild() {
            // σ(a = 1 ∧ b = 2)(A) splits into nested selections (SEL-001),
            // proving the ∆ case rebuilds with the rewritten child.
            SelectionNode conjunctive = select(
                    and(
                            cmp(attr("a"),
                                    ComparisonOperator.EQUAL, num("1")),
                            cmp(attr("b"),
                                    ComparisonOperator.EQUAL, num("2"))),
                    rel("A"));
            RelNode node = symmetricDifference(conjunctive, rel("B"));

            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(SymmetricDifferenceNode.class);
            // The ∆ node was rebuilt around a rewritten left child (new instance),
            // proving the passes recurse through it.
            RelNode left = ((SymmetricDifferenceNode) result).left();
            assertThat(left).isNode(SelectionNode.class);
            assertThat(left).isNotSameAs(conjunctive);
        }

        @Test
        @DisplayName("passes recurse into ∘ children — a splittable selection is rewritten under it")
        void recursesIntoCompositionChild() {
            SelectionNode conjunctive = select(
                    and(
                            cmp(attr("a"),
                                    ComparisonOperator.EQUAL, num("1")),
                            cmp(attr("b"),
                                    ComparisonOperator.EQUAL, num("2"))),
                    rel("A"));
            RelNode node = composition(rel("B"), conjunctive);

            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(CompositionNode.class);
            RelNode right = ((CompositionNode) result).right();
            assertThat(right).isNode(SelectionNode.class);
            assertThat(right).isNotSameAs(conjunctive);
        }

        @Test
        @DisplayName("a ∀ of a plain relation is preserved by all passes")
        void universalPreserved() {
            RelNode node = universal(
                    List.of("k"),
                    cmp(attr("x"),
                            ComparisonOperator.GREATER, num("0")),
                    rel("A"));
            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(UniversalNode.class);
            assertThat(((UniversalNode) result).groupingAttributes()).containsExactly("k");
        }

        @Test
        @DisplayName("a SAMPLE of a plain relation is preserved by all passes")
        void samplePreserved() {
            RelNode node = sample(0.1, rel("A"));
            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(SampleNode.class);
            assertThat(((SampleNode) result).probability()).isEqualTo(0.1);
        }

        @Test
        @DisplayName("a TOP of a plain relation is preserved by all passes")
        void topKPreserved() {
            RelNode node = topK(List.of("k"),
                    List.of(desc("v")), 3,
                    rel("A"));
            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(TopKNode.class);
            assertThat(((TopKNode) result).count()).isEqualTo(3L);
        }

        @Test
        @DisplayName("passes recurse into the TOP input — a splittable selection is rewritten under it")
        void recursesIntoTopKInput() {
            SelectionNode conjunctive = select(
                    and(
                            cmp(attr("a"),
                                    ComparisonOperator.EQUAL, num("1")),
                            cmp(attr("b"),
                                    ComparisonOperator.EQUAL, num("2"))),
                    rel("A"));
            RelNode node = topK(List.of("k"),
                    List.of(desc("v")), 3, conjunctive);

            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(TopKNode.class);
            assertThat(((TopKNode) result).input()).isNotSameAs(conjunctive);
        }

        @Test
        @DisplayName("passes recurse into the SAMPLE input — a splittable selection is rewritten under it")
        void recursesIntoSampleInput() {
            SelectionNode conjunctive = select(
                    and(
                            cmp(attr("a"),
                                    ComparisonOperator.EQUAL, num("1")),
                            cmp(attr("b"),
                                    ComparisonOperator.EQUAL, num("2"))),
                    rel("A"));
            RelNode node = sample(0.5, conjunctive);

            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(SampleNode.class);
            assertThat(((SampleNode) result).input()).isNotSameAs(conjunctive);
        }

        @Test
        @DisplayName("a SOLVE of a plain relation is preserved by all passes")
        void solvePreserved() {
            RelNode node = solve(
                    attr("total"),
                    arith(attr("principal"),
                            ArithmeticOperator.MULTIPLY, attr("rate")),
                    rel("A"));
            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(SolveNode.class);
            assertThat(((SolveNode) result).left()).isEqualTo(attr("total"));
        }

        @Test
        @DisplayName("passes recurse into the SOLVE input — a splittable selection is rewritten under it")
        void recursesIntoSolveInput() {
            SelectionNode conjunctive = select(
                    and(
                            cmp(attr("a"),
                                    ComparisonOperator.EQUAL, num("1")),
                            cmp(attr("b"),
                                    ComparisonOperator.EQUAL, num("2"))),
                    rel("A"));
            RelNode node = solve(
                    attr("total"), attr("rate"), conjunctive);

            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(SolveNode.class);
            assertThat(((SolveNode) result).input()).isNotSameAs(conjunctive);
        }

        @Test
        @DisplayName("an OPTIMIZE of a plain relation is preserved by all passes")
        void optimizePreserved() {
            RelNode node = optimize(
                    com.darkcollective.relix.ast.ObjectiveSense.MAXIMIZE,
                    attr("value"),
                    List.of(new com.darkcollective.relix.ast.OptimizeConstraint(
                            attr("weight"),
                            ComparisonOperator.LESS_EQUAL, 100)),
                    List.of("region"),
                    rel("A"));
            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(OptimizeNode.class);
            assertThat(((OptimizeNode) result).groupingKeys()).containsExactly("region");
        }

        @Test
        @DisplayName("passes recurse into the OPTIMIZE input — a splittable selection is rewritten under it")
        void recursesIntoOptimizeInput() {
            SelectionNode conjunctive = select(
                    and(
                            cmp(attr("a"),
                                    ComparisonOperator.EQUAL, num("1")),
                            cmp(attr("b"),
                                    ComparisonOperator.EQUAL, num("2"))),
                    rel("A"));
            RelNode node = optimize(
                    com.darkcollective.relix.ast.ObjectiveSense.MAXIMIZE,
                    attr("value"),
                    List.of(new com.darkcollective.relix.ast.OptimizeConstraint(
                            attr("weight"),
                            ComparisonOperator.LESS_EQUAL, 100)),
                    List.of(), conjunctive);

            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(OptimizeNode.class);
            assertThat(((OptimizeNode) result).input()).isNotSameAs(conjunctive);
        }

        @Test
        @DisplayName("passes recurse into the ∀ input — a splittable selection is rewritten under it")
        void recursesIntoUniversalInput() {
            SelectionNode conjunctive = select(
                    and(
                            cmp(attr("a"),
                                    ComparisonOperator.EQUAL, num("1")),
                            cmp(attr("b"),
                                    ComparisonOperator.EQUAL, num("2"))),
                    rel("A"));
            RelNode node = universal(
                    List.of("a"),
                    cmp(attr("x"),
                            ComparisonOperator.GREATER, num("0")),
                    conjunctive);

            RelNode result = optimizer.optimize(
                    node, "Q", SchemaAnnotations.empty(), new OptimizationContext());

            assertThat(result).isNode(UniversalNode.class);
            assertThat(((UniversalNode) result).input()).isNotSameAs(conjunctive);
        }
    }

    // =========================================================================
    // optimize(SemanticModel) — null guard
    // =========================================================================

    @Nested
    @DisplayName("optimize(SemanticModel) — null guard")
    class ModelNullGuard {

        @Test
        @DisplayName("null model throws NullPointerException")
        void nullModelThrows() {
            assertThatThrownBy(() -> optimizer.optimize(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("model");
        }
    }

    // =========================================================================
    // optimize(SemanticModel) — empty model
    // =========================================================================

    @Nested
    @DisplayName("optimize(SemanticModel) — empty model")
    class EmptyModel {

        @Test
        @DisplayName("model with no root queries returns empty list")
        void emptyRootQueriesReturnsEmptyList() {
            assertThat(optimizer.optimize(emptyModel())).isEmpty();
        }
    }

    // =========================================================================
    // optimize(SemanticModel) — NamedQueryTarget
    // =========================================================================

    @Nested
    @DisplayName("optimize(SemanticModel) — NamedQueryTarget")
    class NamedTarget {

        @Test
        @DisplayName("QR symbol produces one OptimizationResult with the correct query name")
        void qrSymbolProducesResult() {
            var body = rel("R");
            var qr   = QueryRelationSymbol.of("MyView", schema("id"), body);
            var model = modelWith(qr, SchemaAnnotations.empty());

            var results = optimizer.optimize(model);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).queryName()).isEqualTo("MyView");
        }

        @Test
        @DisplayName("QR symbol with leaf body: original == optimized, no transformations")
        void qrLeafBodyUnchanged() {
            var body = rel("R");
            var qr   = QueryRelationSymbol.of("MyView", schema("id"), body);
            var model = modelWith(qr, SchemaAnnotations.empty());

            var result = optimizer.optimize(model).get(0);

            assertThat(result.original()).isSameAs(body);
            assertThat(result.optimized()).isSameAs(body);
            assertThat(result.applied()).isEmpty();
            assertThat(result.wasOptimized()).isFalse();
        }

        @Test
        @DisplayName("QR symbol: LIM-001 fires when body is λ(π(R))")
        void qrBodyOptimizedWithLim001() {
            var base  = rel("R");
            var proj  = project(List.of(simple(attr("a"))), base);
            var body  = limit(10, proj);          // λ(10)(π(a)(R))
            var qr    = QueryRelationSymbol.of("V", schema("a"), body);
            var model = modelWith(qr, SchemaAnnotations.empty());

            var result = optimizer.optimize(model).get(0);

            assertThat(result.wasOptimized()).isTrue();
            // LIM-001: outer result should be π, containing a λ inside
            assertThat(result.optimized()).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) result.optimized()).input())
                    .isNode(LimitNode.class);
            long lim001Count = result.applied().stream()
                    .filter(r -> r.code() == OptimizationCode.LIM_001)
                    .count();
            assertThat(lim001Count).isEqualTo(1L);
        }

        @Test
        @DisplayName("SourceRelationSymbol (non-QR) produces trivial leaf result")
        void sourceSymbolReturnsTrivialResult() {
            var model = modelWithSource("Orders", schema("id", "amount"));

            var results = optimizer.optimize(model);

            assertThat(results).hasSize(1);
            var r = results.get(0);
            assertThat(r.queryName()).isEqualTo("Orders");
            assertThat(r.optimized()).isNode(RelationNode.class);
            assertThat(((RelationNode) r.optimized()).name()).isEqualTo("Orders");
            assertThat(r.applied()).isEmpty();
        }

        @Test
        @DisplayName("unknown symbol name produces trivial leaf result")
        void unknownSymbolReturnsTrivialResult() {
            // Empty symbol table — 'Ghost' is not registered
            var model = new SemanticModel("default", new InMemorySymbolTable(),
                    Map.of(), SchemaAnnotations.empty(),
                    List.of(query("Ghost")));

            var results = optimizer.optimize(model);

            assertThat(results).hasSize(1);
            var r = results.get(0);
            assertThat(r.queryName()).isEqualTo("Ghost");
            assertThat(r.optimized()).isNode(RelationNode.class);
            assertThat(((RelationNode) r.optimized()).name()).isEqualTo("Ghost");
            assertThat(r.applied()).isEmpty();
        }

        @Test
        @DisplayName("schema annotations from model.nodeSchemas() are used by PROJ-001")
        void schemaAnnotationsUsedByPasses() {
            // R has schema {a:NUMBER}. π(a)(R) is redundant → PROJ-001 should fire.
            var base  = rel("R");
            var proj  = project(List.of(simple(attr("a"))), base);
            var schemas = new SchemaAnnotations(Map.of(base, schema("a")));

            var qr    = QueryRelationSymbol.of("V", schema("a"), proj);
            var table = new InMemorySymbolTable();
            table.register(qr);
            var model = new SemanticModel("default", table, Map.of(), schemas,
                    List.of(query("V")));

            var result = optimizer.optimize(model).get(0);

            // PROJ-001 eliminates redundant π → optimized should be base
            assertThat(result.optimized()).isSameAs(base);
            long proj001Count = result.applied().stream()
                    .filter(r -> r.code() == OptimizationCode.PROJ_001)
                    .count();
            assertThat(proj001Count).isEqualTo(1L);
        }
    }

    // =========================================================================
    // optimize(SemanticModel) — ExpressionQueryTarget
    // =========================================================================

    @Nested
    @DisplayName("optimize(SemanticModel) — ExpressionQueryTarget")
    class ExpressionTarget {

        @Test
        @DisplayName("inline expression produces one result with generated name 'query[1]'")
        void generatedNameIsQuery1() {
            var expr  = rel("R");
            var model = modelWithExpression(expr, SchemaAnnotations.empty());

            var results = optimizer.optimize(model);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).queryName()).isEqualTo("query[1]");
        }

        @Test
        @DisplayName("two inline expressions get sequential names query[1] and query[2]")
        void twoInlineExpressionsGetSequentialNames() {
            var model = new SemanticModel("default", new InMemorySymbolTable(),
                    Map.of(), SchemaAnnotations.empty(),
                    List.of(
                            query(rel("A")),
                            query(rel("B"))
                    ));

            var results = optimizer.optimize(model);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).queryName()).isEqualTo("query[1]");
            assertThat(results.get(1).queryName()).isEqualTo("query[2]");
        }

        @Test
        @DisplayName("inline leaf expression passes through unchanged with no transformations")
        void inlineLeafPassesThrough() {
            var expr  = rel("R");
            var model = modelWithExpression(expr, SchemaAnnotations.empty());

            var result = optimizer.optimize(model).get(0);

            assertThat(result.original()).isSameAs(expr);
            assertThat(result.optimized()).isSameAs(expr);
            assertThat(result.applied()).isEmpty();
        }

        @Test
        @DisplayName("LIM-001 fires on inline λ(π(R)) expression")
        void lim001FiresOnInlineExpression() {
            var base  = rel("R");
            var proj  = project(List.of(simple(attr("a"))), base);
            var body  = limit(10, proj);
            var model = modelWithExpression(body, SchemaAnnotations.empty());

            var result = optimizer.optimize(model).get(0);

            assertThat(result.wasOptimized()).isTrue();
            assertThat(result.optimized()).isNode(ProjectionNode.class);
        }

        @Test
        @DisplayName("inline expression inline-count is independent of named queries")
        void inlineCountIsIndependentOfNamedQueries() {
            // Mix: named query, then two inline expressions
            var base = rel("R");
            var qr   = QueryRelationSymbol.of("V", schema("a"), base);
            var table = new InMemorySymbolTable();
            table.register(qr);
            var model = new SemanticModel("default", table, Map.of(),
                    SchemaAnnotations.empty(),
                    List.of(
                            query("V"),
                            query(rel("A")),
                            query(rel("B"))
                    ));

            var results = optimizer.optimize(model);

            assertThat(results).hasSize(3);
            assertThat(results.get(0).queryName()).isEqualTo("V");
            assertThat(results.get(1).queryName()).isEqualTo("query[1]");
            assertThat(results.get(2).queryName()).isEqualTo("query[2]");
        }
    }

    // =========================================================================
    // optimize(SemanticModel) — ordering and multiple queries
    // =========================================================================

    @Nested
    @DisplayName("optimize(SemanticModel) — result ordering")
    class ResultOrdering {

        @Test
        @DisplayName("results are returned in declaration order")
        void resultsInDeclarationOrder() {
            var base1 = rel("R");
            var base2 = rel("S");
            var qr1   = QueryRelationSymbol.of("View1", schema("a"), base1);
            var qr2   = QueryRelationSymbol.of("View2", schema("b"), base2);
            var table = new InMemorySymbolTable();
            table.register(qr1);
            table.register(qr2);
            var model = new SemanticModel("default", table, Map.of(),
                    SchemaAnnotations.empty(),
                    List.of(
                            query("View1"),
                            query("View2")
                    ));

            var results = optimizer.optimize(model);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).queryName()).isEqualTo("View1");
            assertThat(results.get(1).queryName()).isEqualTo("View2");
        }

        @Test
        @DisplayName("returned list is unmodifiable")
        void returnedListIsUnmodifiable() {
            var model = emptyModel();
            var results = optimizer.optimize(model);
            assertThatThrownBy(() -> results.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("each query uses its own OptimizationContext (rule counts are isolated)")
        void eachQueryUsesItsOwnContext() {
            // Both views have the same optimizable body — LIM-001 should fire once per view
            var body1 = limit(10,
                    project(List.of(simple(attr("a"))), rel("R")));
            var body2 = limit(5,
                    project(List.of(simple(attr("b"))), rel("S")));
            var qr1   = QueryRelationSymbol.of("V1", schema("a"), body1);
            var qr2   = QueryRelationSymbol.of("V2", schema("b"), body2);
            var table = new InMemorySymbolTable();
            table.register(qr1);
            table.register(qr2);
            var model = new SemanticModel("default", table, Map.of(),
                    SchemaAnnotations.empty(),
                    List.of(
                            query("V1"),
                            query("V2")
                    ));

            var results = optimizer.optimize(model);

            assertThat(results).hasSize(2);
            // Each result should have exactly one LIM-001 record (not two)
            assertThat(results.get(0).applied()).hasSize(1);
            assertThat(results.get(0).applied().get(0).code()).isEqualTo(OptimizationCode.LIM_001);
            assertThat(results.get(1).applied()).hasSize(1);
            assertThat(results.get(1).applied().get(0).code()).isEqualTo(OptimizationCode.LIM_001);
        }
    }

    @Nested
    @DisplayName("WHY hard barrier (ADR-0018)")
    class WhyHardBarrier {

        /** Control: δ(δ(R)) collapses to δ(R) — the optimizer DOES rewrite this shape. */
        @Test
        @DisplayName("control — a redundant δ over a δ is eliminated when NOT under WHY")
        void controlDistinctEliminated() {
            RelNode tree = distinct(distinct(rel("R")));
            var ctx = new OptimizationContext();

            RelNode result = optimizer.optimize(tree, "Q", SchemaAnnotations.empty(), ctx);

            assertThat(result).isNode(DistinctNode.class);
            assertThat(((DistinctNode) result).input()).isNode(RelationNode.class);
            assertThat(ctx.recordsFor(OptimizationCode.DIST_001)).hasSize(1);
        }

        /** The same shape under WHY must be frozen — no rewrite inside the subtree. */
        @Test
        @DisplayName("no pass rewrites INSIDE a WHY subtree — δ(δ(R)) is frozen")
        void subtreeFrozen() {
            RelNode subtree = distinct(distinct(rel("R")));
            RelNode tree = new com.darkcollective.relix.ast.WhyNode(subtree);
            var ctx = new OptimizationContext();

            RelNode result = optimizer.optimize(tree, "Q", SchemaAnnotations.empty(), ctx);

            assertThat(result).isInstanceOf(com.darkcollective.relix.ast.WhyNode.class);
            // The frozen subtree is restored verbatim — both δ's survive.
            RelNode input = ((com.darkcollective.relix.ast.WhyNode) result).input();
            assertThat(input).isNode(DistinctNode.class);
            assertThat(((DistinctNode) input).input()).isNode(DistinctNode.class);
            // No DIST-001 fired across the barrier.
            assertThat(ctx.recordsFor(OptimizationCode.DIST_001)).isEmpty();
        }

        /** A selection above WHY must not be pulled below it (no rewrite across). */
        @Test
        @DisplayName("no pass rewrites ACROSS a WHY — a σ above it stays above")
        void selectionNotPushedAcross() {
            RelNode tree = select(
                    cmp(attr("region"), ComparisonOperator.EQUAL,
                            num("1")),
                    new com.darkcollective.relix.ast.WhyNode(rel("R")));
            var ctx = new OptimizationContext();

            RelNode result = optimizer.optimize(tree, "Q", SchemaAnnotations.empty(), ctx);

            // σ stays on top of the WHY; the WHY subtree is untouched.
            assertThat(result).isNode(SelectionNode.class);
            assertThat(((SelectionNode) result).input())
                    .isInstanceOf(com.darkcollective.relix.ast.WhyNode.class);
        }

        /**
         * The barrier has to cover the three <em>preamble</em> rewrites too, not only the
         * pipeline: {@link ViewInliner} replacing a view reference under a WHY with
         * {@code ρ(body)} costs the provenance evaluator the branch that threads lineage
         * into the body.
         */
        @Test
        @DisplayName("no PREAMBLE pass rewrites inside a WHY — a view under it is not inlined")
        void viewNotInlinedUnderWhy() {
            SemanticModel m = model("""
                    Base    := [| a | b |
                                | 1 | 2 |];
                    V       := { π a, b (Base) };
                    ViaView := { ω (V) };
                    query ViaView;
                    """);

            OptimizationResult result = optimizer.optimize(m).getFirst();

            assertThat(result.optimized()).isInstanceOf(com.darkcollective.relix.ast.WhyNode.class);
            // The view reference survives verbatim — the evaluator resolves it itself.
            assertThat(((com.darkcollective.relix.ast.WhyNode) result.optimized()).input())
                    .isNode(RelationNode.class);
            assertThat(result.applied().stream().map(TransformationRecord::code))
                    .doesNotContain(OptimizationCode.INLINE_001);
        }

        /** Control: the same view outside a WHY still inlines — the barrier is not a blanket opt-out. */
        @Test
        @DisplayName("control — a view NOT under a WHY is still inlined")
        void viewOutsideWhyStillInlined() {
            SemanticModel m = model("""
                    Base  := [| a | b |
                              | 1 | 2 |];
                    V     := { π a, b (Base) };
                    Outer := { σ a > 0 (V) };
                    query Outer;
                    """);

            OptimizationResult result = optimizer.optimize(m).getFirst();

            assertThat(result.applied().stream().map(TransformationRecord::code))
                    .contains(OptimizationCode.INLINE_001);
        }
    }
}

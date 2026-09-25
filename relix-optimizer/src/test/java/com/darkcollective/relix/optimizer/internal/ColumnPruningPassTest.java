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
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("ColumnPruningPass — PROJ-004")
final class ColumnPruningPassTest {

    // ── Fixtures ────────────────────────────────────────────────────────────────

    /** Orders(id, cust, amount, note) — four columns, so there is always width to shed. */
    private final RelationNode orders = rel("Orders");
    private final Schema ordersSchema = schemaOf("id", "cust", "amount", "note");

    /** Customers(cust, city, tier). Shares `cust` with Orders, so ⋈ has a join key. */
    private final RelationNode customers = rel("Customers");
    private final Schema customersSchema = schemaOf("cust", "city", "tier");

    private OptimizationContext ctx;
    private SchemaAnnotations schemas;

    @BeforeEach
    void setUp() {
        ctx = new OptimizationContext();
        schemas = new SchemaAnnotations(
                Map.of(orders, ordersSchema, customers, customersSchema));
    }

    private static Schema schemaOf(String... cols) {
        return new Schema(List.of(cols).stream()
                .map(c -> new ColumnDefinition(c, ScalarType.NUMBER))
                .toList());
    }

    private RelNode apply(RelNode node) {
        return ColumnPruningPass.apply(node, "Q", schemas, ctx);
    }

    // ── Tree builders ───────────────────────────────────────────────────────────

    private static ProjectionNode proj(RelNode input, String... cols) {
        return new ProjectionNode(List.of(cols).stream()
                .map(c -> ProjectedAttribute.simple(attr(c)))
                .toList(), input, SourceLocation.UNKNOWN);
    }

    private static Predicate on(String column) {
        return new ComparisonPredicate(attr(column),
                ComparisonOperator.GREATER, num("0"), SourceLocation.UNKNOWN);
    }

    private static Predicate joinOn(String leftCol, String rightCol) {
        return new ComparisonPredicate(attr(leftCol),
                ComparisonOperator.EQUAL, attr(rightCol), SourceLocation.UNKNOWN);
    }

    // ── Assertions ──────────────────────────────────────────────────────────────

    /** The output column names of a projection, in order. */
    private static List<String> columnsOf(RelNode node) {
        assertThat(node).isNode(ProjectionNode.class);
        return ((ProjectionNode) node).attributes().stream()
                .map(a -> ((AttributeOperand) a.expression()).name())
                .toList();
    }

    private boolean fired() {
        return ctx.records().stream().anyMatch(r -> r.code() == OptimizationCode.PROJ_004);
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("the requirement reaches the leaf")
    class ReachesLeaf {

        @Test
        @DisplayName("π over σ: the leaf keeps the projected and the filtered columns")
        void projectionOverSelection() {
            RelNode result = apply(proj(select(on("amount"), orders), "id"));

            // π id ( σ amount > 0 ( π id, amount (Orders) ) )
            SelectionNode selection = (SelectionNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(selection.input())).containsExactly("id", "amount");
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("γ needs only its grouping keys and aggregate arguments")
        void aggregation() {
            var group = groupByKeys(
                    List.of(GroupingKey.column("cust")),
                    List.of(AggregateFunction.aliased(AggregateOperator.SUM, "amount", "total")),
                    orders);

            RelNode result = apply(group);
            assertThat(columnsOf(((AggregationNode) result).input()))
                    .containsExactly("cust", "amount");
        }

        @Test
        @DisplayName("an aggregate over an expression keeps every column the expression reads")
        void aggregateOverExpression() {
            var product = new BinaryArithmeticExpression(attr("amount"),
                    ArithmeticOperator.MULTIPLY, attr("id"), SourceLocation.UNKNOWN);
            var group = groupByKeys(
                    List.of(GroupingKey.column("cust")),
                    List.of(AggregateFunction.aliased(AggregateOperator.SUM, product, "total")),
                    orders);

            assertThat(columnsOf(((AggregationNode) apply(group)).input()))
                    .containsExactly("id", "cust", "amount");
        }

        @Test
        @DisplayName("ARGMAX's yield expression counts as read")
        void aggregateYieldExpression() {
            var argmax = AggregateFunction.argAliased(
                    AggregateOperator.ARGMAX, "amount", "note", "top_note");
            var group = groupByKeys(List.of(GroupingKey.column("cust")),
                    List.of(argmax), orders);

            assertThat(columnsOf(((AggregationNode) apply(group)).input()))
                    .containsExactly("cust", "amount", "note");
        }

        @Test
        @DisplayName("τ adds its sort keys to what the parent asked for")
        void sort() {
            var sort = AstBuilders.sort(
                    List.of(desc("amount")), orders);

            RelNode result = apply(proj(sort, "id"));
            assertThat(columnsOf(((SortNode) ((ProjectionNode) result).input()).input()))
                    .containsExactly("id", "amount");
        }

        @Test
        @DisplayName("λ is column-transparent — the requirement passes straight through")
        void limit() {
            var limit = new LimitNode(Optional.empty(), 5L, orders, SourceLocation.UNKNOWN);

            RelNode result = apply(proj(limit, "id"));
            assertThat(columnsOf(((LimitNode) ((ProjectionNode) result).input()).input()))
                    .containsExactly("id");
        }

        @Test
        @DisplayName("columns are emitted in schema order, not requirement order")
        void schemaOrder() {
            RelNode result = apply(proj(select(on("id"), orders), "note", "cust"));
            SelectionNode selection = (SelectionNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(selection.input())).containsExactly("id", "cust", "note");
        }

        @Test
        @DisplayName("a function call's arguments are columns the query reads")
        void functionCallArguments() {
            // σ Abs(amount) > 0 — `amount` is read through the call, not directly.
            Predicate call = new ComparisonPredicate(
                    new FunctionCall("Abs", List.of(attr("amount")),
                            SourceLocation.UNKNOWN),
                    ComparisonOperator.GREATER, num("0"), SourceLocation.UNKNOWN);

            RelNode result = apply(proj(select(call, orders), "id"));
            SelectionNode selection = (SelectionNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(selection.input())).containsExactly("id", "amount");
        }

        @Test
        @DisplayName("a projected function call's arguments count too")
        void projectedFunctionCallArguments() {
            var projection = new ProjectionNode(List.of(ProjectedAttribute.aliased(
                    new FunctionCall("Abs", List.of(attr("amount")),
                            SourceLocation.UNKNOWN), "size")),
                    select(on("id"), orders), SourceLocation.UNKNOWN);

            var selection = (SelectionNode) ((ProjectionNode) apply(projection)).input();
            assertThat(columnsOf(selection.input())).containsExactly("id", "amount");
        }

        @Test
        @DisplayName("a qualified reference is matched on its column part")
        void qualifiedReference() {
            RelNode result = apply(proj(select(on("Orders.amount"), orders), "id"));
            SelectionNode selection = (SelectionNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(selection.input())).containsExactly("id", "amount");
        }
    }

    @Nested
    @DisplayName("joins split the requirement by side")
    class Joins {

        @Test
        @DisplayName("⋈ never drops a common column — those are its join keys")
        void naturalJoinKeepsCommonColumns() {
            // `cust` is read by nobody above, but it is the join key of Orders ⋈ Customers.
            var join = naturalJoin(orders, customers);
            RelNode result = apply(proj(join, "amount", "city"));

            var pruned = (NaturalJoinNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(pruned.left())).containsExactly("cust", "amount");
            assertThat(columnsOf(pruned.right())).containsExactly("cust", "city");
        }

        @Test
        @DisplayName("θ-join: each side gets what is read above plus the condition's columns")
        void thetaJoin() {
            var join = join(orders, customers, joinOn("Orders.cust", "Customers.cust"));
            RelNode result = apply(proj(join, "amount", "city"));

            var pruned = (ThetaJoinNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(pruned.left())).containsExactly("cust", "amount");
            assertThat(columnsOf(pruned.right())).containsExactly("cust", "city");
        }

        @Test
        @DisplayName("⋉ emits no right column, so the right side needs only the condition")
        void semiJoin() {
            var join = AstBuilders.semiJoin(orders, customers, joinOn("Orders.id", "Customers.city"));
            RelNode result = apply(proj(join, "amount"));

            var pruned = (SemiJoinNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(pruned.left())).containsExactly("id", "amount");
            assertThat(columnsOf(pruned.right())).containsExactly("city");
        }

        @Test
        @DisplayName("▷ prunes the same way as ⋉")
        void antiJoin() {
            var join = AstBuilders.antiJoin(orders, customers, joinOn("Orders.id", "Customers.city"));
            RelNode result = apply(proj(join, "amount"));

            var pruned = (AntiJoinNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(pruned.right())).containsExactly("city");
        }

        @Test
        @DisplayName("a condition column both sides have is kept on both — the split is by name")
        void ambiguousConditionColumnKeptOnBothSides() {
            // `cust` is in both schemas, so which side the qualifier `Orders.cust` meant
            // is not resolved here; keeping it on both is the conservative answer.
            var join = AstBuilders.semiJoin(orders, customers, joinOn("Orders.cust", "Customers.cust"));
            RelNode result = apply(proj(join, "amount"));

            var pruned = (SemiJoinNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(pruned.right())).containsExactly("cust");
        }

        @Test
        @DisplayName("× has no condition, so the requirement splits on schema membership alone")
        void product() {
            RelNode result = apply(proj(new ProductNode(orders, customers, SourceLocation.UNKNOWN),
                    "amount", "city"));

            var pruned = (ProductNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(pruned.left())).containsExactly("amount");
            assertThat(columnsOf(pruned.right())).containsExactly("city");
        }

        @Test
        @DisplayName("a join whose input has no schema annotation is left alone")
        void unannotatedJoinInput() {
            var unknown = rel("Unknown");
            var join = join(orders, unknown, joinOn("Orders.cust", "Unknown.k"));

            assertThat(apply(proj(join, "amount"))).isEqualTo(proj(join, "amount"));
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an unannotated *left* input also stops the split")
        void unannotatedLeftJoinInput() {
            var unknown = rel("Unknown");
            var join = join(unknown, customers, joinOn("Unknown.k", "Customers.cust"));

            assertThat(apply(proj(join, "city"))).isEqualTo(proj(join, "city"));
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an open-schema side cannot be split against, so it keeps everything")
        void openSchemaJoinSide() {
            var dynamic = rel("Dynamic");
            schemas = new SchemaAnnotations(
                    Map.of(orders, ordersSchema, dynamic, Schema.open()));
            var join = join(orders, dynamic, joinOn("Orders.cust", "Dynamic.k"));

            RelNode result = apply(proj(join, "amount"));
            var pruned = (ThetaJoinNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(pruned.left())).containsExactly("cust", "amount");
            assertThat(pruned.right()).isEqualTo(dynamic);   // untouched
        }

        @Test
        @DisplayName("a join with nothing to shed on either side is returned unchanged")
        void nothingToPruneOnEitherSide() {
            var join = new ProductNode(orders, customers, SourceLocation.UNKNOWN);
            RelNode node = proj(join, "id", "cust", "amount", "note", "city", "tier");

            assertThat(apply(node)).isEqualTo(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("the pairwise ∀ join is excluded — its result is not row matching")
        void pairwiseUniversalExcluded() {
            var join = new PairwiseUniversalNode(orders, customers,
                    joinOn("Orders.cust", "Customers.cust"), SourceLocation.UNKNOWN);

            assertThat(apply(proj(join, "amount"))).isEqualTo(proj(join, "amount"));
            assertThat(fired()).isFalse();
        }
    }

    @Nested
    @DisplayName("ρ maps the requirement back through the rename")
    class Rename {

        @Test
        @DisplayName("a relation-only ρ passes the requirement through untouched")
        void relationOnly() {
            var rename = rename("O", List.of(), orders);
            RelNode result = apply(proj(rename, "id"));

            var pruned = (RenameNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(pruned.input())).containsExactly("id");
        }

        @Test
        @DisplayName("the pair form maps each required output name back to its source column")
        void pairForm() {
            var rename = rename(Optional.of("O"), List.of(),
                    List.of(new RenameNode.RenamePair("amount", "value")),
                    orders);

            RelNode result = apply(proj(rename, "value", "id"));
            var pruned = (RenameNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(pruned.input())).containsExactly("id", "amount");
        }

        @Test
        @DisplayName("the positional form is arity-bound, so its input is not pruned")
        void positionalForm() {
            var rename = rename("O", List.of("a", "b", "c", "d"), orders);

            assertThat(apply(proj(rename, "a"))).isEqualTo(proj(rename, "a"));
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a pair-form ρ at the root has no requirement to map, so nothing is pruned")
        void pairFormUnderNoRequirement() {
            var rename = rename(Optional.of("O"), List.of(),
                    List.of(new RenameNode.RenamePair("amount", "value")),
                    orders);

            assertThat(apply(rename)).isEqualTo(rename);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a pair-form ρ over an unannotated input cannot be mapped through")
        void pairFormWithoutInputSchema() {
            var unknown = rel("Unknown");
            var rename = rename(Optional.of("O"), List.of(),
                    List.of(new RenameNode.RenamePair("a", "b")),
                    unknown);

            assertThat(apply(proj(rename, "b"))).isEqualTo(proj(rename, "b"));
            assertThat(fired()).isFalse();
        }
    }

    @Nested
    @DisplayName("the operators that must not prune")
    class Blocked {

        @Test
        @DisplayName("δ compares whole rows, so nothing is pruned below it")
        void distinct() {
            // π id (δ (Orders)) — pruning to `id` first would dedup on one column.
            assertThat(apply(proj(AstBuilders.distinct(orders), "id")))
                    .isEqualTo(proj(AstBuilders.distinct(orders), "id"));
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("⊎'s branches are matched positionally, so they are left alone")
        void unionAll() {
            var union = AstBuilders.unionAll(orders, orders);
            assertThat(apply(proj(union, "id"))).isEqualTo(proj(union, "id"));
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("μ is reached through the default arm, so nothing is pruned below it")
        void unnest() {
            var unnest = AstBuilders.unnest("note", false, Optional.empty(), orders);
            assertThat(apply(proj(unnest, "id"))).isEqualTo(proj(unnest, "id"));
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("but an unpruned operator still lets a π below it start a fresh requirement")
        void prunesBelowABlockingOperator() {
            // δ blocks pruning of its own input, but the π inside it prunes normally.
            RelNode result = apply(AstBuilders.distinct(proj(select(on("amount"), orders), "id")));

            var projection = (ProjectionNode) ((DistinctNode) result).input();
            assertThat(columnsOf(((SelectionNode) projection.input()).input()))
                    .containsExactly("id", "amount");
        }
    }

    @Nested
    @DisplayName("when pruning is not provably safe, nothing happens")
    class NoOp {

        @Test
        @DisplayName("a query that reads every column prunes nothing")
        void everythingRead() {
            RelNode node = proj(orders, "id", "cust", "amount", "note");
            assertThat(apply(node)).isEqualTo(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a leaf with no schema annotation is left alone")
        void unannotatedLeaf() {
            var unknown = rel("Unknown");
            assertThat(apply(proj(select(on("a"), unknown), "b")))
                    .isEqualTo(proj(select(on("a"), unknown), "b"));
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an open (schema-on-read) leaf cannot be enumerated, so it is left alone")
        void openSchema() {
            var dynamic = rel("Dynamic");
            schemas = new SchemaAnnotations(Map.of(dynamic, Schema.open()));

            RelNode node = proj(select(on("a"), dynamic), "id");
            assertThat(apply(node)).isEqualTo(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("the empty (zero-column) heading has nothing to prune")
        void emptySchema() {
            var nullary = rel("Nullary");
            schemas = new SchemaAnnotations(Map.of(nullary, Schema.empty()));

            RelNode node = proj(select(on("a"), nullary), "id");
            assertThat(apply(node)).isEqualTo(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("the `*:ANY` unresolved placeholder is not a prunable schema")
        void unresolvedPlaceholder() {
            var unresolved = rel("Unresolved");
            schemas = new SchemaAnnotations(Map.of(unresolved,
                    new Schema(List.of(new ColumnDefinition("*", ScalarType.ANY)))));

            RelNode node = proj(select(on("a"), unresolved), "id");
            assertThat(apply(node)).isEqualTo(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a required name the schema does not have means the derivation is not trusted")
        void requirementDisagreesWithSchema() {
            // `ghost` is in neither schema; rather than prune around it, bail.
            RelNode node = proj(select(on("ghost"), orders), "id");
            assertThat(apply(node)).isEqualTo(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a π already sitting directly on a leaf is not wrapped in another")
        void projectionDirectlyOnLeaf() {
            RelNode node = proj(orders, "id");
            assertThat(apply(node)).isEqualTo(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a γ that reads no column at all (COUNT of a literal) prunes nothing")
        void aggregateOverLiteral() {
            var count = AggregateFunction.aliased(AggregateOperator.COUNT,
                    num("1"), "n");
            var group = groupByKeys(List.<GroupingKey>of(), List.of(count),
                    orders);

            assertThat(apply(group)).isEqualTo(group);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an ungrouped γ (null grouping keys) does not blow up")
        void ungroupedAggregation() {
            var total = AggregateFunction.aliased(AggregateOperator.SUM, "amount", "total");
            var group = groupBy((List<String>) null, List.of(total), orders);

            assertThat(columnsOf(((AggregationNode) apply(group)).input()))
                    .containsExactly("amount");
        }
    }

    @Nested
    @DisplayName("an intermediate π is narrowed to what is read above it")
    class NarrowProjection {

        @Test
        @DisplayName("a column no operator above reads is dropped from the projection")
        void narrowsInPlace() {
            // γ cust, SUM(amount) ( π id, cust, amount (Orders) ) — `id` is dead.
            var inner = proj(orders, "id", "cust", "amount");
            var group = groupByKeys(
                    List.of(GroupingKey.column("cust")),
                    List.of(AggregateFunction.aliased(AggregateOperator.SUM, "amount", "total")),
                    inner);

            RelNode result = apply(group);
            assertThat(columnsOf(((AggregationNode) result).input()))
                    .containsExactly("cust", "amount");
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a computed attribute with no alias has no output name, so it is kept")
        void keepsUnnameableAttribute() {
            var computed = ProjectedAttribute.simple(new BinaryArithmeticExpression(
                    attr("amount"), ArithmeticOperator.MULTIPLY,
                    num("2"), SourceLocation.UNKNOWN));
            var inner = new ProjectionNode(
                    List.of(ProjectedAttribute.simple(attr("id")), computed),
                    orders, SourceLocation.UNKNOWN);

            RelNode result = apply(proj(inner, "id"));
            var narrowed = (ProjectionNode) ((ProjectionNode) result).input();
            assertThat(narrowed.attributes()).containsExactly(
                    ProjectedAttribute.simple(attr("id")), computed);
        }

        @Test
        @DisplayName("narrowing never empties a projection")
        void neverEmpties() {
            // The γ above reads `cust`; the π produces only `note`, so nothing may be
            // dropped (an empty π is not constructible, and the tree is preserved).
            var inner = proj(orders, "note");
            var group = groupByKeys(List.of(GroupingKey.column("cust")),
                    List.of(AggregateFunction.aliased(AggregateOperator.SUM, "amount", "total")),
                    inner);

            assertThat(((AggregationNode) apply(group)).input()).isEqualTo(inner);
        }

        @Test
        @DisplayName("an aliased attribute is matched on its alias")
        void matchesOnAlias() {
            var inner = new ProjectionNode(List.of(
                    ProjectedAttribute.aliased(attr("amount"), "value"),
                    ProjectedAttribute.simple(attr("id"))),
                    orders, SourceLocation.UNKNOWN);

            RelNode result = apply(proj(inner, "value"));
            var narrowed = (ProjectionNode) ((ProjectionNode) result).input();
            assertThat(narrowed.attributes()).containsExactly(
                    ProjectedAttribute.aliased(attr("amount"), "value"));
        }
    }

    @Nested
    @DisplayName("the transformation record")
    class Record {

        @Test
        @DisplayName("a leaf prune records PROJ-004 naming the relation and the width")
        void leafRecord() {
            apply(proj(select(on("amount"), orders), "id"));

            var record = ctx.records().stream()
                    .filter(r -> r.code() == OptimizationCode.PROJ_004)
                    .findFirst().orElseThrow();
            assertThat(record.relationName()).isEqualTo("Q");
            assertThat(record.detail()).contains("Orders").contains("2 of 4");
        }

        @Test
        @DisplayName("nothing is recorded when nothing is pruned")
        void silentWhenNoOp() {
            apply(proj(orders, "id", "cust", "amount", "note"));
            assertThat(ctx.records()).isEmpty();
        }
    }

    @Nested
    @DisplayName("case-insensitivity")
    class CaseInsensitive {

        @Test
        @DisplayName("a reference matches its schema column regardless of case")
        void mixedCase() {
            var mixed = rel("Mixed");
            Map<RelNode, Schema> annotations = new HashMap<>();
            annotations.put(mixed, schemaOf("ID", "Cust", "AMOUNT"));
            schemas = new SchemaAnnotations(annotations);

            RelNode result = apply(proj(select(on("amount"), mixed), "id"));
            SelectionNode selection = (SelectionNode) ((ProjectionNode) result).input();
            assertThat(columnsOf(selection.input())).containsExactly("ID", "AMOUNT");
        }
    }
}

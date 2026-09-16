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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.OffsetFunction;
import com.darkcollective.relix.ast.RankingFunction;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnProvenance;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.ast.Expr.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * Unit tests for {@link SchemaInferenceVisitor} and {@link SchemaInferenceEngine}.
 *
 * <p>Tests are organized by operation type. Each test:
 * <ol>
 *   <li>Builds a symbol table with one or more source relations.</li>
 *   <li>Constructs a RelNode tree for the operation under test.</li>
 *   <li>Runs the visitor and asserts on the returned {@code Optional<Schema>}.</li>
 *   <li>Verifies that nodes were annotated in the {@link SchemaAnnotations} map.</li>
 * </ol>
 */
@DisplayName("SchemaInferenceVisitor — schema inference for all RA operations")
final class SchemaInferenceTest {

    // =========================================================================
    // Test fixture — a few reusable source relations
    // =========================================================================

    private InMemorySymbolTable table;
    private SchemaAnnotations   annotations;
    private List<SemanticError> errors;

    /**
     * Pre-loaded relations:
     * <ul>
     *   <li>Users(id: NUMBER, name: STRING, dept_id: NUMBER)</li>
     *   <li>Depts(dept_id: NUMBER, dept_name: STRING)</li>
     *   <li>Orders(order_id: NUMBER, user_id: NUMBER, amount: NUMBER)</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        table       = new InMemorySymbolTable();
        annotations = new SchemaAnnotations();
        errors      = new ArrayList<>();

        register("Users",
                col("id",       ScalarType.NUMBER),
                col("name",     ScalarType.STRING),
                col("dept_id",  ScalarType.NUMBER));

        register("Depts",
                col("dept_id",  ScalarType.NUMBER),
                col("dept_name", ScalarType.STRING));

        register("Orders",
                col("order_id", ScalarType.NUMBER),
                col("user_id",  ScalarType.NUMBER),
                col("amount",   ScalarType.NUMBER));
    }

    // =========================================================================
    // Helper factories
    // =========================================================================

    private void register(String name, ColumnDefinition... cols) {
        Schema schema = new Schema(List.of(cols));
        table.register(new SourceRelationSymbol(
                "default", name, Provenance.BUILTIN, ShadowPolicy.PERMITTED, schema));
    }

    private static ColumnDefinition col(String name, ScalarType type) {
        return new ColumnDefinition(name, type);
    }

    private SchemaInferenceVisitor visitor() {
        return new SchemaInferenceVisitor(table, annotations, errors, "<test>", SemanticFixtures.FUNCTIONS);
    }

    private static ComparisonPredicate truePred() {
        return cmp(
                num("1"), ComparisonOperator.EQUAL, num("1"));
    }

    // =========================================================================
    // Leaf: RelationNode
    // =========================================================================

    @Nested
    @DisplayName("RelationNode — schema from symbol table")
    class RelationNodes {

        @Test
        @DisplayName("Known relation returns its registered schema")
        void knownRelation() {
            RelationNode node = rel("Users");
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
            assertThat(result.get().column("id")).isPresent();
            assertThat(result.get().column("id").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(annotations.hasSchema(node)).isTrue();
        }

        @Test
        @DisplayName("Lookup is case-insensitive")
        void caseInsensitiveLookup() {
            RelationNode node = rel("users");
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("Unknown relation returns empty and records an error")
        void unknownRelation() {
            RelationNode node = rel("NoSuchTable");
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isEmpty();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("NoSuchTable");
            assertThat(annotations.hasSchema(node)).isFalse();
        }
    }

    // =========================================================================
    // Passthrough operations: Selection, Sort, Limit, Distinct
    // =========================================================================

    @Nested
    @DisplayName("Passthrough operations — output schema == input schema")
    class PassthroughOps {

        @Test
        @DisplayName("SelectionNode passes input schema unchanged")
        void selectionPassthrough() {
            RelNode node = select(truePred(), rel("Orders"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
            assertThat(result.get().column("amount")).isPresent();
        }

        @Test
        @DisplayName("SortNode passes input schema unchanged")
        void sortPassthrough() {
            RelNode node = sort(
                    List.of(asc("name")),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
        }

        @Test
        @DisplayName("LimitNode passes input schema unchanged")
        void limitPassthrough() {
            RelNode node = limit(10L, rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
        }

        @Test
        @DisplayName("SampleNode passes input schema unchanged")
        void samplePassthrough() {
            RelNode node = sample(0.1, rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
        }

        @Test
        @DisplayName("ReservoirSampleNode passes input schema unchanged")
        void reservoirSamplePassthrough() {
            RelNode node = reservoirSample(10, rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
        }

        @Test
        @DisplayName("TopKNode passes input schema unchanged (full rows out)")
        void topKPassthrough() {
            RelNode node = topK(List.of("dept_id"),
                    List.of(desc("id")), 2,
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
        }

        @Test
        @DisplayName("CoverNode passes input schema unchanged (output ⊆ input rows)")
        void coverPassthrough() {
            RelNode node = cover(2, rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
            assertThat(result.get().column("id")).isPresent();
            assertThat(result.get().column("name")).isPresent();
            assertThat(result.get().column("dept_id")).isPresent();
        }

        @Test
        @DisplayName("CoverNode with strength 1 still passes through schema")
        void coverStrengthOnePassthrough() {
            RelNode node = cover(1, rel("Depts"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(2);
        }

        @Test
        @DisplayName("DistinctNode passes input schema unchanged")
        void distinctPassthrough() {
            RelNode node = distinct(rel("Depts"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(2);
        }

        @Test
        @DisplayName("Passthrough fails gracefully when input is unresolvable")
        void passthroughOnUnresolvable() {
            RelNode node = distinct(rel("Ghost"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // Cluster (connected components)
    // =========================================================================

    @Nested
    @DisplayName("ClusterNode — (node, label:NUMBER) output schema")
    class ClusterInference {

        @Test
        @DisplayName("emits the node column (from) plus a NUMBER label column")
        void nodeAndLabelSchema() {
            RelNode node = cluster("id", "dept_id", "cid",rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(2);
            assertThat(result.get().column("id")).isPresent();
            assertThat(result.get().column("id").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(result.get().column("cid")).isPresent();
            assertThat(result.get().column("cid").get().type()).isEqualTo(ScalarType.NUMBER);
            // The non-edge input columns are dropped.
            assertThat(result.get().column("name")).isEmpty();
        }

        @Test
        @DisplayName("the node column keeps the 'from' column type (e.g. STRING)")
        void preservesNodeType() {
            register("Links", col("a", ScalarType.STRING), col("b", ScalarType.STRING));
            RelNode node = cluster("a", "b", "cid",rel("Links"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("a").get().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("from == to leaves the node unannotated (validator reports it)")
        void sameColumnUnannotated() {
            RelNode node = cluster("id", "id", "cid",rel("Users"));
            assertThat(node.accept(visitor())).isEmpty();
        }

        @Test
        @DisplayName("label colliding with the node column leaves it unannotated")
        void labelCollisionUnannotated() {
            RelNode node = cluster("id", "dept_id", "id",rel("Users"));
            assertThat(node.accept(visitor())).isEmpty();
        }

        @Test
        @DisplayName("unresolvable input propagates as no schema")
        void unresolvableInput() {
            RelNode node = cluster("a", "b", "cid",rel("Ghost"));
            assertThat(node.accept(visitor())).isEmpty();
        }
    }

    // =========================================================================
    // Path (bounded variable-length reachability)
    // =========================================================================

    @Nested
    @DisplayName("PathNode — (from, to, depth:NUMBER) output schema")
    class PathInference {

        @Test
        @DisplayName("emits both endpoint columns plus a NUMBER depth column")
        void endpointsAndDepthSchema() {
            RelNode node = path("id", "dept_id", 1, 3, "depth",rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
            assertThat(result.get().column("id").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(result.get().column("dept_id").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(result.get().column("depth")).isPresent();
            assertThat(result.get().column("depth").get().type()).isEqualTo(ScalarType.NUMBER);
            // Non-edge input columns are dropped.
            assertThat(result.get().column("name")).isEmpty();
        }

        @Test
        @DisplayName("endpoint columns keep their own types (e.g. STRING)")
        void preservesEndpointTypes() {
            register("Links", col("a", ScalarType.STRING), col("b", ScalarType.STRING));
            RelNode node = path("a", "b", 1, 2, "depth",rel("Links"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("a").get().type()).isEqualTo(ScalarType.STRING);
            assertThat(result.get().column("b").get().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("from == to leaves the node unannotated (validator reports it)")
        void sameColumnUnannotated() {
            RelNode node = path("id", "id", 1, 3, "depth",rel("Users"));
            assertThat(node.accept(visitor())).isEmpty();
        }

        @Test
        @DisplayName("depth colliding with an endpoint leaves it unannotated")
        void depthCollisionUnannotated() {
            RelNode node = path("id", "dept_id", 1, 3, "id",rel("Users"));
            assertThat(node.accept(visitor())).isEmpty();
        }

        @Test
        @DisplayName("unresolvable input propagates as no schema")
        void unresolvableInput() {
            RelNode node = path("a", "b", 1, 2, "depth",rel("Ghost"));
            assertThat(node.accept(visitor())).isEmpty();
        }
    }

    // =========================================================================
    // Outer-union (⊔ / OUNION)
    // =========================================================================

    @Nested
    @DisplayName("OuterUnionNode — merged (column-union) output schema")
    class OuterUnionInference {

        @Test
        @DisplayName("merges left columns with the right's extra columns")
        void mergesColumns() {
            register("L", col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
            register("R", col("id", ScalarType.NUMBER), col("city", ScalarType.STRING));
            RelNode node = outerUnion(rel("L"), rel("R"));

            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get()).hasColumnNames("id", "name", "city");
            assertThat(result.get().column("name").get().type()).isEqualTo(ScalarType.STRING);
            assertThat(result.get().column("city").get().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("a shared column with differing types widens to ANY")
        void typeConflictWidensToAny() {
            register("L", col("k", ScalarType.NUMBER));
            register("R", col("k", ScalarType.STRING));
            RelNode node = outerUnion(rel("L"), rel("R"));

            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(1);
            assertThat(result.get().column("k").get().type()).isEqualTo(ScalarType.ANY);
        }

        @Test
        @DisplayName("fully disjoint inputs concatenate both column sets")
        void disjointColumnsConcatenate() {
            register("L", col("a", ScalarType.NUMBER));
            register("R", col("b", ScalarType.STRING));
            RelNode node = outerUnion(rel("L"), rel("R"));

            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get()).hasColumnNames("a", "b");
        }

        @Test
        @DisplayName("an unresolvable input propagates as no schema")
        void unresolvableInput() {
            register("L", col("id", ScalarType.NUMBER));
            RelNode node = outerUnion(rel("L"), rel("Ghost"));
            assertThat(node.accept(visitor())).isEmpty();
        }
    }

    // =========================================================================
    // Window (ROLLING)
    // =========================================================================

    @Nested
    @DisplayName("WindowNode — input schema + appended computed column")
    class WindowInference {

        private WindowNode rolling(AggregateOperator op, AttributeOperand arg, String out) {
            return window(new WindowFunction.AggregateWindow(op, arg),
                    List.of("user_id"), List.of(asc("amount")),
                    new WindowFrame.BoundedFrame(3), out, rel("Orders"));
        }

        @Test
        @DisplayName("appends the output column, preserving every input column")
        void appendsColumn() {
            Optional<Schema> result = rolling(AggregateOperator.SUM, attr("amount"), "run").accept(visitor());
            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(4); // order_id, user_id, amount, run
            assertThat(result.get().column("order_id")).isPresent();
            assertThat(result.get().column("run")).isPresent();
            assertThat(result.get().column("run").get().type()).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("COUNT yields a NUMBER column")
        void countIsNumber() {
            Optional<Schema> result = rolling(AggregateOperator.COUNT, attr("amount"), "c").accept(visitor());
            assertThat(result.get().column("c").get().type()).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("the output type follows the argument type (MAX over STRING → STRING)")
        void typeFollowsArgument() {
            register("Names", col("g", ScalarType.NUMBER), col("nm", ScalarType.STRING));
            RelNode node = window(
                    new WindowFunction.AggregateWindow(AggregateOperator.MAX, attr("nm")),
                    List.of("g"), List.of(asc("nm")),
                    new WindowFrame.CumulativeFrame(), "latest", rel("Names"));
            Optional<Schema> result = node.accept(visitor());
            assertThat(result.get().column("latest").get().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("a clashing output column name leaves the schema unchanged")
        void clashKeepsInputSchema() {
            Optional<Schema> result = rolling(AggregateOperator.SUM, attr("amount"), "amount").accept(visitor());
            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3); // unchanged input schema
        }

        @Test
        @DisplayName("unresolvable input propagates as no schema")
        void unresolvableInput() {
            RelNode node = window(
                    new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("x")),
                    List.of(), List.of(asc("x")),
                    new WindowFrame.BoundedFrame(2), "w", rel("Ghost"));
            assertThat(node.accept(visitor())).isEmpty();
        }

        @Test
        @DisplayName("a ranking function (RANK) appends a NUMBER column")
        void rankingAppendsNumber() {
            RelNode node = window(
                    new WindowFunction.RankingWindow(RankingFunction.RANK, Optional.empty()),
                    List.of("user_id"), List.of(desc("amount")),
                    new WindowFrame.PartitionFrame(), "rnk", rel("Orders"));
            Optional<Schema> result = node.accept(visitor());
            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(4);
            assertThat(result.get().column("rnk").get().type()).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("NTILE appends a NUMBER column")
        void ntileAppendsNumber() {
            RelNode node = window(
                    new WindowFunction.RankingWindow(RankingFunction.NTILE,
                            Optional.of(num("4"))),
                    List.of("user_id"), List.of(desc("amount")),
                    new WindowFrame.PartitionFrame(), "q", rel("Orders"));
            Optional<Schema> result = node.accept(visitor());
            assertThat(result.get().column("q").get().type()).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("LAG appends a column whose type follows the referenced expression")
        void lagFollowsExpressionType() {
            RelNode node = window(
                    new WindowFunction.OffsetWindow(OffsetFunction.LAG, attr("amount"),
                            Optional.of(num("1")), Optional.empty()),
                    List.of("user_id"), List.of(asc("amount")),
                    new WindowFrame.PartitionFrame(), "prev", rel("Orders"));
            Optional<Schema> result = node.accept(visitor());
            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(4);
            assertThat(result.get().column("prev").get().type()).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("FIRST_VALUE over a STRING column appends a STRING column")
        void firstValueFollowsStringType() {
            register("Names", col("g", ScalarType.NUMBER), col("nm", ScalarType.STRING));
            RelNode node = window(
                    new WindowFunction.OffsetWindow(OffsetFunction.FIRST_VALUE, attr("nm"),
                            Optional.empty(), Optional.empty()),
                    List.of("g"), List.of(asc("nm")),
                    new WindowFrame.PartitionFrame(), "first_nm", rel("Names"));
            Optional<Schema> result = node.accept(visitor());
            assertThat(result.get().column("first_nm").get().type()).isEqualTo(ScalarType.STRING);
        }
    }

    // =========================================================================
    // Trace (optimal-path extraction)
    // =========================================================================

    @Nested
    @DisplayName("TraceNode — (from:T, to:T, weight:NUMBER, path:array<T>) output schema")
    class TraceInference {

        @Test
        @DisplayName("emits from/to/weight/path columns with correct types")
        void basicTraceSchema() {
            RelNode node = trace("id", "dept_id", "dept_id",
                    ObjectiveSense.MINIMIZE, "route",rel("Users"));
            // Users has id:NUMBER, dept_id:NUMBER — both endpoints are NUMBER
            // We need a relation with numeric from/to/weight columns
            register("Edges",
                    col("src",  ScalarType.NUMBER),
                    col("dst",  ScalarType.NUMBER),
                    col("cost", ScalarType.NUMBER));
            RelNode trace = trace(
                    "src", "dst", "cost", ObjectiveSense.MINIMIZE, "route",rel("Edges"));
            Optional<Schema> result = trace.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.columns()).hasSize(4);
            assertThat(s.column("src")).isPresent();
            assertThat(s.column("src").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(s.column("dst")).isPresent();
            assertThat(s.column("dst").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(s.column("cost")).isPresent();
            assertThat(s.column("cost").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(s.column("route")).isPresent();
            assertThat(s.column("route").get().type()).isEqualTo(array(ScalarType.NUMBER));
        }

        @Test
        @DisplayName("node type from STRING from-column propagates to path array element type")
        void stringNodeType() {
            register("Routes",
                    col("origin", ScalarType.STRING),
                    col("dest",   ScalarType.STRING),
                    col("miles",  ScalarType.NUMBER));
            RelNode trace = trace(
                    "origin", "dest", "miles", ObjectiveSense.MINIMIZE, "stops",rel("Routes"));
            Optional<Schema> result = trace.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("origin").get().type()).isEqualTo(ScalarType.STRING);
            assertThat(result.get().column("stops").get().type())
                    .isEqualTo(array(ScalarType.STRING));
        }

        @Test
        @DisplayName("from == to leaves the node unannotated (validator reports it)")
        void sameColumnUnannotated() {
            register("Edges",
                    col("src", ScalarType.NUMBER),
                    col("cost", ScalarType.NUMBER));
            RelNode trace = trace(
                    "src", "src", "cost", ObjectiveSense.MINIMIZE, "route",rel("Edges"));
            assertThat(trace.accept(visitor())).isEmpty();
        }

        @Test
        @DisplayName("path column clashing with an endpoint column leaves it unannotated")
        void pathCollidesWithFromColumn() {
            register("Edges",
                    col("src",  ScalarType.NUMBER),
                    col("dst",  ScalarType.NUMBER),
                    col("cost", ScalarType.NUMBER));
            RelNode trace = trace(
                    "src", "dst", "cost", ObjectiveSense.MINIMIZE, "src",rel("Edges"));
            assertThat(trace.accept(visitor())).isEmpty();
        }

        @Test
        @DisplayName("unresolvable input propagates as no schema")
        void unresolvableInput() {
            RelNode trace = trace(
                    "a", "b", "w", ObjectiveSense.MINIMIZE, "route",rel("Ghost"));
            assertThat(trace.accept(visitor())).isEmpty();
        }
    }

    // =========================================================================
    // Projection
    // =========================================================================

    @Nested
    @DisplayName("ProjectionNode — schema derived from projected attributes")
    class Projections {

        @Test
        @DisplayName("Plain attribute reference takes name and type from input schema")
        void plainAttributeRef() {
            RelNode node = project(
                    List.of(ProjectedAttribute.simple(attr("id")),
                            ProjectedAttribute.simple(attr("name"))),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.columns()).hasSize(2);
            assertThat(s.column("id").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(s.column("name").get().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("Qualified attribute reference strips the qualifier")
        void qualifiedAttributeRef() {
            RelNode node = project(
                    List.of(ProjectedAttribute.simple(attr("Users.name"))),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("name")).isPresent();
        }

        @Test
        @DisplayName("Alias overrides the column name")
        void aliasedAttribute() {
            RelNode node = project(
                    List.of(ProjectedAttribute.aliased(attr("id"), "user_id")),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("user_id")).isPresent();
            assertThat(result.get().column("id")).isEmpty();
        }

        @Test
        @DisplayName("Arithmetic expression gets NUMBER type")
        void arithmeticExpression() {
            var expr = arith(
                    attr("amount"), ArithmeticOperator.MULTIPLY, num("1.1"));
            RelNode node = project(
                    List.of(ProjectedAttribute.aliased(expr, "adjusted")),
                    rel("Orders"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("adjusted").get().type())
                    .isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("Number literal becomes NUMBER column")
        void numberLiteral() {
            RelNode node = project(
                    List.of(ProjectedAttribute.aliased(num("42"), "magic")),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("magic").get().type())
                    .isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("String literal becomes STRING column")
        void stringLiteral() {
            RelNode node = project(
                    List.of(ProjectedAttribute.aliased(str("hello"), "greeting")),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("greeting").get().type())
                    .isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("Boolean literal becomes BOOLEAN column")
        void booleanLiteral() {
            RelNode node = project(
                    List.of(ProjectedAttribute.aliased(bool(true), "flag")),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("flag").get().type())
                    .isEqualTo(ScalarType.BOOLEAN);
        }

        @Test
        @DisplayName("Temporal literals become DATE/TIME/TIMESTAMP/DURATION columns")
        void temporalLiterals() {
            RelNode node = project(
                    List.of(
                            ProjectedAttribute.aliased(
                                    date(java.time.LocalDate.parse("2026-06-15")), "d"),
                            ProjectedAttribute.aliased(
                                    time(java.time.LocalTime.parse("13:40:00")), "t"),
                            ProjectedAttribute.aliased(
                                    timestamp(java.time.Instant.parse("2026-06-15T13:40:00Z")), "ts"),
                            ProjectedAttribute.aliased(
                                    duration(java.time.Duration.parse("PT30M")), "dur")),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("d").get().type()).isEqualTo(ScalarType.DATE);
            assertThat(result.get().column("t").get().type()).isEqualTo(ScalarType.TIME);
            assertThat(result.get().column("ts").get().type()).isEqualTo(ScalarType.TIMESTAMP);
            assertThat(result.get().column("dur").get().type()).isEqualTo(ScalarType.DURATION);
        }

        @Test
        @DisplayName("Unknown attribute falls back to ANY type")
        void unknownAttributeFallsBackToAny() {
            RelNode node = project(
                    List.of(ProjectedAttribute.simple(attr("nonexistent_col"))),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("nonexistent_col").get().type())
                    .isEqualTo(ScalarType.ANY);
        }

        @Test
        @DisplayName("Non-attribute expression without alias gets synthetic _col<N> name")
        void syntheticColumnName() {
            RelNode node = project(
                    List.of(ProjectedAttribute.simple(num("1"))),
                    rel("Orders"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("_col0")).isPresent();
        }

        @Test
        @DisplayName("Duplicate projected column names are deduplicated by suffixing")
        void duplicateColumnsDeduped() {
            // Project the same attribute twice with different aliases
            RelNode node = project(
                    List.of(ProjectedAttribute.simple(attr("id")),
                            ProjectedAttribute.simple(attr("id"))),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.columns()).hasSize(2);
            assertThat(s.column("id")).isPresent();
            assertThat(s.column("id_1")).isPresent();
        }
    }

    // =========================================================================
    // Rename
    // =========================================================================

    @Nested
    @DisplayName("RenameNode — relation name and/or column rename")
    class Renames {

        @Test
        @DisplayName("Empty attribute list — only relation name changes, schema unchanged")
        void renameRelationOnly() {
            RelNode node = rename("E", List.of(), rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.columns()).hasSize(3);
            assertThat(s.column("id")).isPresent();
            assertThat(s.column("name")).isPresent();
            assertThat(s.column("dept_id")).isPresent();
        }

        @Test
        @DisplayName("A relation rename over a partly-open join re-anchors the known columns and stays open")
        void renameOverPartlyOpenJoin() {
            // The shape an inlined view over `Users ⨝ Docs` takes (#971). Returning the
            // heading untouched left `id` answering to `Users` beneath the rename, so a
            // qualifier the rename had hidden still validated.
            table.register(new SourceRelationSymbol(
                    "default", "Docs", Provenance.BUILTIN, ShadowPolicy.PERMITTED, Schema.open()));

            Optional<Schema> result = rename("J", List.of(),
                    join(rel("Users"), rel("Docs"), truePred())).accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.isOpen()).as("the document side still resolves by name").isTrue();
            assertThat(s.columns()).isNotEmpty().allSatisfy(c -> assertThat(c.provenance())
                    .isEqualTo(new ColumnProvenance("J", c.name())));
            assertThat(s.qualifiedIndices("J", "id")).isEmpty();   // open: no positional answer
            assertThat(s.column("id")).get().extracting(ColumnDefinition::type)
                    .isEqualTo(ScalarType.NUMBER);
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("A relation rename over a fully open input passes it through")
        void renameOverFullyOpen() {
            table.register(new SourceRelationSymbol(
                    "default", "Docs", Provenance.BUILTIN, ShadowPolicy.PERMITTED, Schema.open()));

            Optional<Schema> result = rename("D", List.of(), rel("Docs")).accept(visitor());

            assertThat(result).contains(Schema.open());
        }

        @Test
        @DisplayName("A pair rename over a partly-open join renames its known columns and stays open (#977)")
        void pairRenameOverPartlyOpenJoin() {
            table.register(new SourceRelationSymbol(
                    "default", "Docs", Provenance.BUILTIN, ShadowPolicy.PERMITTED, Schema.open()));

            Optional<Schema> result = rename(Optional.empty(), List.of(),
                    List.of(renamePair("name", "full_name"), renamePair("title", "heading")),
                    join(rel("Users"), rel("Docs"), truePred())).accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.isOpen()).isTrue();
            assertThat(s.indexOf("name")).as("the known column is renamed").isNegative();
            assertThat(s.column("full_name")).get().extracting(ColumnDefinition::provenance)
                    .isEqualTo(new ColumnProvenance("Users", "full_name"));
            assertThat(s.indexOf("heading")).as("a document field is not declared by renaming it")
                    .isNegative();
            assertThat(s.column("heading")).as("…but the open heading still resolves it").isPresent();
        }

        @Test
        @DisplayName("A pair rename over a fully open input passes it through")
        void pairRenameOverFullyOpen() {
            table.register(new SourceRelationSymbol(
                    "default", "Docs", Provenance.BUILTIN, ShadowPolicy.PERMITTED, Schema.open()));

            Optional<Schema> result = rename(Optional.empty(), List.of(),
                    List.of(renamePair("title", "heading")), rel("Docs")).accept(visitor());

            assertThat(result).contains(Schema.open());
        }

        @Test
        @DisplayName("Non-empty attribute list renames columns positionally")
        void renameColumns() {
            RelNode node = rename("D", List.of("did", "dname"),
                    rel("Depts"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.columns()).hasSize(2);
            assertThat(s.column("did").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(s.column("dname").get().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("Partial rename — extra input columns keep original names")
        void partialRename() {
            // Users has 3 columns; only rename the first one
            RelNode node = rename("U", List.of("uid"),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.columns()).hasSize(3);
            assertThat(s.column("uid")).isPresent();
            assertThat(s.column("name")).isPresent();
            assertThat(s.column("dept_id")).isPresent();
        }

        @Test
        @DisplayName("Pair form renames the listed column and keeps the rest (#453)")
        void renamePairKeepsUnlisted() {
            RelNode node = rename(Optional.of("U"), List.of(),
                    List.of(new RenameNode.RenamePair("name", "full_name")),
                    rel("Users"));
            Schema s = node.accept(visitor()).orElseThrow();

            assertThat(s.columns()).hasSize(3);
            assertThat(s.column("full_name")).isPresent();
            assertThat(s.column("name")).isEmpty();          // renamed away
            assertThat(s.column("id")).isPresent();          // unlisted, kept
            assertThat(s.column("dept_id")).isPresent();     // unlisted, kept
            assertThat(s.column("full_name").get().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("Pair form re-anchors renamed column provenance to the new relation name")
        void renamePairReanchorsProvenance() {
            RelNode node = rename(Optional.of("U"), List.of(),
                    List.of(new RenameNode.RenamePair("name", "full_name")),
                    rel("Users"));
            Schema s = node.accept(visitor()).orElseThrow();

            assertThat(s.qualifiedIndices("U", "full_name")).containsExactly(s.indexOf("full_name"));
            assertThat(s.qualifiedIndices("U", "id")).containsExactly(s.indexOf("id"));
        }

        @Test
        @DisplayName("Pair form with no relation name keeps each column's origin qualifier")
        void renamePairNoRelationNameKeepsOrigin() {
            RelNode node = rename(Optional.empty(), List.of(),
                    List.of(new RenameNode.RenamePair("name", "full_name")),
                    rel("Users"));
            Schema s = node.accept(visitor()).orElseThrow();

            assertThat(s.column("full_name")).isPresent();
            // Origin relation stays "Users"; the logical column name follows the rename.
            assertThat(s.qualifiedIndices("Users", "full_name")).containsExactly(s.indexOf("full_name"));
            assertThat(s.qualifiedIndices("Users", "id")).containsExactly(s.indexOf("id"));
        }
    }

    // =========================================================================
    // Natural join
    // =========================================================================

    @Nested
    @DisplayName("NaturalJoinNode — shared columns deduplicated, non-shared appended")
    class NaturalJoins {

        @Test
        @DisplayName("Shared column appears once; unique columns from both sides present")
        void naturalJoin() {
            // Users has dept_id, Depts has dept_id → shared
            RelNode node = AstBuilders.naturalJoin(
                    rel("Users"), rel("Depts"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            // Users columns: id, name, dept_id
            // Depts columns: dept_id (shared — deduplicated), dept_name
            // Result: id, name, dept_id, dept_name
            assertThat(s.column("id")).isPresent();
            assertThat(s.column("name")).isPresent();
            assertThat(s.column("dept_id")).isPresent();
            assertThat(s.column("dept_name")).isPresent();
            assertThat(s.columns()).hasSize(4);
        }

        @Test
        @DisplayName("No shared columns — result is full concatenation")
        void naturalJoinNoShared() {
            // Orders has order_id, user_id, amount — no overlap with Depts(dept_id, dept_name)
            RelNode node = AstBuilders.naturalJoin(
                    rel("Orders"), rel("Depts"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(5); // 3 + 2
        }
    }

    // =========================================================================
    // Theta join and outer joins — concatenated schema
    // =========================================================================

    @Nested
    @DisplayName("ThetaJoin / OuterJoins / Product — concatenated schemas")
    class ConcatenatingJoins {

        @Test
        @DisplayName("ThetaJoin concatenates left and right schemas")
        void thetaJoin() {
            // Users(id,name,dept_id) × Orders(order_id,user_id,amount)  — no overlap
            RelNode node = join(
                    rel("Users"), rel("Orders"), truePred());
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(6);
            assertThat(result.get().column("id")).isPresent();
            assertThat(result.get().column("order_id")).isPresent();
        }

        @Test
        @DisplayName("A theta join with a schema-on-read side stays open")
        void openSideKeepsTheHeadingOpen() {
            // The natural-join arm refuses an open input and tells the user to write a
            // theta join instead, "which resolves its columns per row". That was a
            // promise the heading broke: concat dropped the open flag as soon as the
            // other side had columns, so every reference to a column of the document
            // became an analysis error — and the join then died at runtime, the
            // executor concatenating both rows against a heading claiming one width.
            table.register(new SourceRelationSymbol(
                    "default", "Docs", Provenance.BUILTIN, ShadowPolicy.PERMITTED, Schema.open()));

            Optional<Schema> result = join(rel("Docs"), rel("Users"), truePred()).accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().isOpen()).isTrue();
            assertThat(result.get().column("whatever_the_document_holds")).isPresent();
            assertThat(result.get().column("id"))
                    .as("the declared side keeps its own types")
                    .get().extracting(ColumnDefinition::type).isEqualTo(ScalarType.NUMBER);
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("Right-side duplicate column is suffixed with _r")
        void duplicateColumnSuffixed() {
            // Users(dept_id, ...) and Depts(dept_id, ...) share dept_id
            RelNode node = join(
                    rel("Users"), rel("Depts"), truePred());
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.column("dept_id")).isPresent();      // left
            assertThat(s.column("dept_id_r")).isPresent();    // right, renamed
        }

        @Test
        @DisplayName("Collided columns keep distinct source-relation provenance (#454)")
        void collidedColumnsCarryProvenance() {
            RelNode node = join(
                    rel("Users"), rel("Depts"), truePred());
            Schema s = node.accept(visitor()).orElseThrow();

            // Left dept_id resolves to Users; the renamed right dept_id_r to Depts.
            assertThat(s.qualifiedIndices("Users", "dept_id")).containsExactly(s.indexOf("dept_id"));
            assertThat(s.qualifiedIndices("Depts", "dept_id")).containsExactly(s.indexOf("dept_id_r"));
            // A relation not in scope resolves to nothing (a stale qualifier).
            assertThat(s.qualifiedIndices("Orders", "dept_id")).isEmpty();
        }

        @Test
        @DisplayName("LeftOuterJoin concatenates schemas")
        void leftOuterJoin() {
            RelNode node = leftJoin(
                    rel("Users"), rel("Orders"), truePred());
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(6);
        }

        @Test
        @DisplayName("RightOuterJoin concatenates schemas")
        void rightOuterJoin() {
            RelNode node = rightJoin(
                    rel("Users"), rel("Orders"), truePred());
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(6);
        }

        @Test
        @DisplayName("FullOuterJoin concatenates schemas")
        void fullOuterJoin() {
            RelNode node = fullJoin(
                    rel("Users"), rel("Orders"), truePred());
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(6);
        }

        @Test
        @DisplayName("Product concatenates schemas")
        void product() {
            RelNode node = AstBuilders.product(
                    rel("Users"), rel("Orders"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(6);
        }
    }

    // =========================================================================
    // Semi-join and anti-join — left schema only
    // =========================================================================

    @Nested
    @DisplayName("SemiJoin / AntiJoin — left schema only")
    class SemiAntiJoins {

        @Test
        @DisplayName("SemiJoin returns only the left schema")
        void semiJoin() {
            RelNode node = AstBuilders.semiJoin(
                    rel("Users"), rel("Orders"), truePred());
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3); // Users only
            assertThat(result.get().column("id")).isPresent();
            assertThat(result.get().column("order_id")).isEmpty();
        }

        @Test
        @DisplayName("AntiJoin returns only the left schema")
        void antiJoin() {
            RelNode node = AstBuilders.antiJoin(
                    rel("Users"), rel("Orders"), truePred());
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
            assertThat(result.get().column("amount")).isEmpty();
        }

        @Test
        @DisplayName("SemiJoin still visits right subtree (right errors still recorded)")
        void semiJoinRightErrorRecorded() {
            RelNode node = AstBuilders.semiJoin(
                    rel("Users"), rel("Ghost"), truePred());
            Optional<Schema> result = node.accept(visitor());

            // Left succeeded — result is present with left schema
            assertThat(result).isPresent();
            // Right failed — error was recorded
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("Ghost");
        }
    }

    // =========================================================================
    // Pairwise universal semi-join — left schema only
    // =========================================================================

    @Nested
    @DisplayName("PairwiseUniversal — left schema only")
    class PairwiseUniversal {

        @Test
        @DisplayName("USEMI returns only the left schema")
        void usemiReturnsLeftSchema() {
            RelNode node = pairwiseUniversal(
                    rel("Users"), rel("Orders"), truePred());
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3); // Users only
            assertThat(result.get().column("id")).isPresent();
            assertThat(result.get().column("order_id")).isEmpty();
        }

        @Test
        @DisplayName("USEMI still visits right subtree")
        void usemiRightErrorRecorded() {
            RelNode node = pairwiseUniversal(
                    rel("Users"), rel("Ghost"), truePred());
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("Ghost");
        }
    }

    // =========================================================================
    // Set operations — left schema
    // =========================================================================

    @Nested
    @DisplayName("Set operations — left schema returned")
    class SetOps {

        @Test
        @DisplayName("Union returns left schema")
        void union() {
            RelNode node = AstBuilders.union(
                    rel("Users"), rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
        }

        @Test
        @DisplayName("UnionAll returns left schema")
        void unionAll() {
            RelNode node = AstBuilders.unionAll(
                    rel("Orders"), rel("Orders"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
        }

        @Test
        @DisplayName("Intersection returns left schema")
        void intersection() {
            RelNode node = AstBuilders.intersection(
                    rel("Depts"), rel("Depts"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(2);
        }

        @Test
        @DisplayName("Difference returns left schema")
        void difference() {
            RelNode node = AstBuilders.difference(
                    rel("Users"), rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
        }

        @Test
        @DisplayName("Symmetric difference returns left schema")
        void symmetricDifference() {
            RelNode node = AstBuilders.symmetricDifference(
                    rel("Users"), rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3);
            assertThat(annotations.hasSchema(node)).isTrue();
        }

        @Test
        @DisplayName("Symmetric difference fails on unknown input (empty)")
        void symmetricDifferenceUnknownInput() {
            RelNode node = AstBuilders.symmetricDifference(
                    rel("NoSuchTable"), rel("Users"));
            assertThat(node.accept(visitor())).isEmpty();
        }
    }

    // =========================================================================
    // Composition
    // =========================================================================

    @Nested
    @DisplayName("CompositionNode — non-shared columns of both inputs")
    class Compositions {

        @Test
        @DisplayName("Result drops the shared columns and keeps the rest of both sides")
        void composition() {
            // Users(id, name, dept_id) ∘ Depts(dept_id, dept_name)
            // Shared: dept_id → result: id, name, dept_name
            RelNode node = AstBuilders.composition(
                    rel("Users"), rel("Depts"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.columns()).hasSize(3);
            assertThat(s.column("id")).isPresent();
            assertThat(s.column("name")).isPresent();
            assertThat(s.column("dept_name")).isPresent();
            assertThat(s.column("dept_id")).isEmpty();
            assertThat(annotations.hasSchema(node)).isTrue();
        }

        @Test
        @DisplayName("No shared columns is an error")
        void compositionNoSharedColumns() {
            // Orders(order_id, user_id, amount) ∘ Depts(dept_id, dept_name) — no overlap
            RelNode node = AstBuilders.composition(
                    rel("Orders"), rel("Depts"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isEmpty();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("share at least one column");
        }

        @Test
        @DisplayName("All columns shared yields an empty result — error recorded")
        void compositionAllShared() {
            register("UsersCopy",
                    col("id",      ScalarType.NUMBER),
                    col("name",    ScalarType.STRING),
                    col("dept_id", ScalarType.NUMBER));

            RelNode node = AstBuilders.composition(
                    rel("Users"), rel("UsersCopy"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isEmpty();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("empty");
        }

        @Test
        @DisplayName("Unknown input yields empty with no shared-column error")
        void compositionUnknownInput() {
            RelNode node = AstBuilders.composition(
                    rel("NoSuchTable"), rel("Users"));
            assertThat(node.accept(visitor())).isEmpty();
        }
    }

    // =========================================================================
    // Division
    // =========================================================================

    @Nested
    @DisplayName("DivisionNode — left columns minus right columns")
    class Divisions {

        @Test
        @DisplayName("Result contains left columns not present in right schema")
        void division() {
            // Users(id, name, dept_id) ÷ Depts(dept_id, dept_name)
            // Right columns present in left: dept_id
            // Result: id, name
            RelNode node = AstBuilders.division(
                    rel("Users"), rel("Depts"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.column("id")).isPresent();
            assertThat(s.column("name")).isPresent();
            assertThat(s.column("dept_id")).isEmpty();
            assertThat(s.column("dept_name")).isEmpty();
        }

        @Test
        @DisplayName("Division with no overlapping columns returns full left schema")
        void divisionNoOverlap() {
            // Orders(order_id, user_id, amount) ÷ Depts(dept_id, dept_name) — no overlap
            RelNode node = AstBuilders.division(
                    rel("Orders"), rel("Depts"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(3); // all left columns remain
        }

        @Test
        @DisplayName("Division result schema is empty when all left columns appear in right — error recorded")
        void divisionEmptyResult() {
            // Register a relation Superset(id, name, dept_id) — same columns as Users
            register("Superset",
                    col("id",      ScalarType.NUMBER),
                    col("name",    ScalarType.STRING),
                    col("dept_id", ScalarType.NUMBER));

            // Users(id, name, dept_id) ÷ Superset(id, name, dept_id)
            // Every left column appears in right → remaining = empty → error
            RelNode node = AstBuilders.division(
                    rel("Users"), rel("Superset"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isEmpty();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).message()).contains("empty");
        }
    }

    // =========================================================================
    // Nested types (NF²) — struct/array construction, COLLECT, UNNEST
    // =========================================================================

    @Nested
    @DisplayName("Nested types — struct/array/COLLECT/UNNEST infer nested schemas")
    class NestedTypes {

        @Test
        @DisplayName("array construction infers ArrayType of the element type")
        void arrayConstruction() {
            RelNode node = project(
                    List.of(ProjectedAttribute.aliased(
                            arrayOf(attr("amount")), "nums")),
                    rel("Orders"));
            Type t = node.accept(visitor()).orElseThrow().column("nums").orElseThrow().type();
            assertThat(t).isEqualTo(array(ScalarType.NUMBER));
        }

        @Test
        @DisplayName("a mixed-element array falls back to ArrayType(ANY)")
        void mixedArray() {
            RelNode node = project(
                    List.of(ProjectedAttribute.aliased(
                            arrayOf(attr("id"), attr("name")), "mixed")),
                    rel("Users"));
            Type t = node.accept(visitor()).orElseThrow().column("mixed").orElseThrow().type();
            assertThat(t).isEqualTo(array(ScalarType.ANY));
        }

        @Test
        @DisplayName("an empty array literal infers ArrayType(ANY)")
        void emptyArray() {
            RelNode node = project(
                    List.of(ProjectedAttribute.aliased(
                            arrayOf(), "empty")),
                    rel("Orders"));
            Type t = node.accept(visitor()).orElseThrow().column("empty").orElseThrow().type();
            assertThat(t).isEqualTo(array(ScalarType.ANY));
        }

        @Test
        @DisplayName("struct construction infers a StructType of its field types")
        void structConstruction() {
            RelNode node = project(
                    List.of(ProjectedAttribute.aliased(structOf(
                            new StructConstruction.Field("oid", attr("order_id")),
                            new StructConstruction.Field("amt", attr("amount"))), "o")),
                    rel("Orders"));
            Type t = node.accept(visitor()).orElseThrow().column("o").orElseThrow().type();
            assertThat(t).isEqualTo(struct(
                    new StructType.Field("oid", ScalarType.NUMBER),
                    new StructType.Field("amt", ScalarType.NUMBER)));
        }

        @Test
        @DisplayName("COLLECT (NEST) infers ArrayType of the collected column's type")
        void collectInfersArray() {
            RelNode node = groupBy(
                    List.of("user_id"),
                    List.of(AggregateFunction.simple(AggregateOperator.COLLECT, "amount")),
                    rel("Orders"));
            Type t = node.accept(visitor()).orElseThrow()
                    .column("collect_amount").orElseThrow().type();
            assertThat(t).isEqualTo(array(ScalarType.NUMBER));
        }

        @Test
        @DisplayName("UNNEST of an array column yields the element type (μ inverts NEST)")
        void unnestArrayColumn() {
            table.register(new SourceRelationSymbol("default", "Tagged",
                    Provenance.BUILTIN, ShadowPolicy.PERMITTED,
                    new Schema(List.of(
                            new ColumnDefinition("id", ScalarType.NUMBER),
                            new ColumnDefinition("labels", array(ScalarType.STRING))))));

            RelNode node = unnest("labels", false, rel("Tagged"));
            Schema out = node.accept(visitor()).orElseThrow();
            assertThat(out.column("labels").orElseThrow().type()).isEqualTo(ScalarType.STRING);
            assertThat(out.column("id").orElseThrow().type()).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("WITH ORDINALITY appends a NUMBER index column after the element")
        void unnestWithOrdinality() {
            table.register(new SourceRelationSymbol("default", "Tagged",
                    Provenance.BUILTIN, ShadowPolicy.PERMITTED,
                    new Schema(List.of(
                            new ColumnDefinition("id", ScalarType.NUMBER),
                            new ColumnDefinition("labels", array(ScalarType.STRING))))));

            RelNode node = unnest("labels", false, java.util.Optional.of("pos"),
                    rel("Tagged"));
            Schema out = node.accept(visitor()).orElseThrow();
            assertThat(out.columns()).extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("id:N", "labels:S", "pos:N");
        }
    }

    // =========================================================================
    // Universal quantification
    // =========================================================================

    @Nested
    @DisplayName("UniversalNode — output is the grouping-key columns")
    class Universals {

        @Test
        @DisplayName("Output schema is the grouping key, with the input column's type")
        void keySchema() {
            RelNode node = universal(
                    List.of("dept_id"), truePred(), rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.columns()).hasSize(1);
            assertThat(s.column("dept_id")).isPresent();
            assertThat(s.column("dept_id").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(s.column("name")).isEmpty();
            assertThat(annotations.hasSchema(node)).isTrue();
        }

        @Test
        @DisplayName("Multiple grouping keys preserved in order")
        void multipleKeys() {
            RelNode node = universal(
                    List.of("id", "name"), truePred(), rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(2);
            assertThat(result.get().column("id")).isPresent();
            assertThat(result.get().column("name")).isPresent();
        }

        @Test
        @DisplayName("No-key whole-relation ∀ infers the empty (closed) schema")
        void noKeyEmptySchema() {
            RelNode node = universal(
                    List.of(), truePred(), rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.isEmpty()).isTrue();
            assertThat(s.isOpen()).isFalse();
            assertThat(s.columns()).isEmpty();
            assertThat(errors).isEmpty();
            assertThat(annotations.hasSchema(node)).isTrue();
        }

        @Test
        @DisplayName("Unknown input yields empty")
        void unknownInput() {
            RelNode node = universal(
                    List.of("id"), truePred(), rel("NoSuchTable"));
            assertThat(node.accept(visitor())).isEmpty();
        }
    }

    // =========================================================================
    // Aggregation
    // =========================================================================

    @Nested
    @DisplayName("AggregationNode — groupby columns + aggregate result columns")
    class Aggregations {

        @Test
        @DisplayName("Group-by columns preserve their input types")
        void groupByPreservesTypes() {
            RelNode node = groupBy(
                    List.of("dept_id"),
                    List.of(AggregateFunction.simple(AggregateOperator.COUNT, "id")),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema s = result.get();
            assertThat(s.column("dept_id").get().type()).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("COUNT aggregate result is always NUMBER")
        void countIsNumber() {
            RelNode node = groupBy(
                    List.of("dept_id"),
                    List.of(AggregateFunction.simple(AggregateOperator.COUNT, "id")),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            // Default name: "count_id"
            assertThat(result.get().column("count_id").get().type())
                    .isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("SUM aggregate result is NUMBER")
        void sumIsNumber() {
            RelNode node = groupBy(
                    List.of(),
                    List.of(AggregateFunction.simple(AggregateOperator.SUM, "amount")),
                    rel("Orders"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("sum_amount").get().type())
                    .isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("Aggregate over an arithmetic expression infers NUMBER, named operator_expr")
        void aggregateOverExpressionInfersNumber() {
            RelNode node = groupBy(
                    List.of(),
                    List.of(AggregateFunction.of(AggregateOperator.SUM,
                            arith(attr("amount"),
                                    ArithmeticOperator.MULTIPLY, attr("amount")))),
                    rel("Orders"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("sum_expr").get().type()).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("Derived grouping key takes its alias and inferred NUMBER type (issue #375)")
        void groupsByExpressionKey() {
            RelNode node = groupByKeys(
                    List.of(GroupingKey.aliased(
                            arith(attr("amount"),
                                    ArithmeticOperator.MULTIPLY, num("2")), "dbl")),
                    List.of(AggregateFunction.simple(AggregateOperator.COUNT, "order_id")),
                    rel("Orders"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("dbl:N", "count_order_id:N");
        }

        @Test
        @DisplayName("Aliased aggregate uses the alias as column name")
        void aliasedAggregate() {
            RelNode node = groupBy(
                    List.of("dept_id"),
                    List.of(AggregateFunction.aliased(AggregateOperator.AVG, "amount", "avg_order")),
                    rel("Orders"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("avg_order")).isPresent();
        }

        @Test
        @DisplayName("MAX over a STRING column preserves STRING type")
        void maxPreservesStringType() {
            RelNode node = groupBy(
                    List.of(),
                    List.of(AggregateFunction.simple(AggregateOperator.MAX, "name")),
                    rel("Users"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("max_name").get().type())
                    .isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("MIN over a NUMBER column preserves NUMBER type")
        void minPreservesNumberType() {
            RelNode node = groupBy(
                    List.of(),
                    List.of(AggregateFunction.simple(AggregateOperator.MIN, "amount")),
                    rel("Orders"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("min_amount").get().type())
                    .isEqualTo(ScalarType.NUMBER);
        }
    }

    // =========================================================================
    // Annotations map
    // =========================================================================

    @Nested
    @DisplayName("SchemaAnnotations — every visited node gets an entry")
    class Annotations {

        @Test
        @DisplayName("All nodes in a selection → projection chain are annotated")
        void chainAnnotated() {
            RelationNode rel = rel("Users");
            SelectionNode sel = select(truePred(), rel);
            ProjectionNode proj = project(
                    List.of(ProjectedAttribute.simple(attr("name"))), sel);

            proj.accept(visitor());

            assertThat(annotations.hasSchema(rel)).isTrue();
            assertThat(annotations.hasSchema(sel)).isTrue();
            assertThat(annotations.hasSchema(proj)).isTrue();
        }

        @Test
        @DisplayName("Failed subtree leaves node unannotated")
        void failedSubtreeUnannotated() {
            RelationNode ghost = rel("Ghost");
            SelectionNode sel  = select(truePred(), ghost);

            sel.accept(visitor());

            assertThat(annotations.hasSchema(ghost)).isFalse();
            assertThat(annotations.hasSchema(sel)).isFalse();
        }
    }

    // =========================================================================
    // SchemaInferenceEngine — QueryRelationSymbol update + root-query annotation
    // =========================================================================

    @Nested
    @DisplayName("SchemaInferenceEngine — named view resolution and root-query annotation")
    class EngineTests {

        @Test
        @DisplayName("QueryRelationSymbol schema is updated from UNRESOLVED after engine runs")
        void queryRelationSchemaUpdated() {
            // Register a QueryRelationSymbol with UNRESOLVED_SCHEMA
            RelNode body = select(truePred(), rel("Users"));
            QueryRelationSymbol qrs = new QueryRelationSymbol(
                    "default", "ActiveUsers",
                    Provenance.USER, ShadowPolicy.PERMITTED,
                    SymbolCollector.UNRESOLVED_SCHEMA, body);
            table.register(qrs);

            // Run the engine
            var engine = new SchemaInferenceEngine(table, annotations, SemanticFixtures.FUNCTIONS);
            engine.infer(List.of());

            // Symbol should now have Users' schema (3 columns)
            var resolved = table.lookupRelation("ActiveUsers").orElseThrow();
            assertThat(resolved.schema().columns()).hasSize(3);
            assertThat(resolved.schema().equals(SymbolCollector.UNRESOLVED_SCHEMA)).isFalse();
        }

        @Test
        @DisplayName("QueryRelationSymbol body's nodes are annotated after engine runs")
        void bodyNodesAnnotated() {
            RelNode body = project(
                    List.of(ProjectedAttribute.simple(attr("id")),
                            ProjectedAttribute.simple(attr("name"))),
                    rel("Users"));
            QueryRelationSymbol qrs = new QueryRelationSymbol(
                    "default", "UserNames",
                    Provenance.USER, ShadowPolicy.PERMITTED,
                    SymbolCollector.UNRESOLVED_SCHEMA, body);
            table.register(qrs);

            var engine = new SchemaInferenceEngine(table, annotations, SemanticFixtures.FUNCTIONS);
            engine.infer(List.of());

            // All nodes in the body tree should be annotated
            assertThat(annotations.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("Second QueryRelationSymbol referencing first gets correct schema")
        void chainedViewsResolved() {
            // View1: SELECT id, name FROM Users
            RelNode view1Body = project(
                    List.of(ProjectedAttribute.simple(attr("id")),
                            ProjectedAttribute.simple(attr("name"))),
                    rel("Users"));
            table.register(new QueryRelationSymbol(
                    "default", "View1",
                    Provenance.USER, ShadowPolicy.PERMITTED,
                    SymbolCollector.UNRESOLVED_SCHEMA, view1Body));

            // View2: SELECT id FROM View1
            RelNode view2Body = project(
                    List.of(ProjectedAttribute.simple(attr("id"))),
                    rel("View1"));
            table.register(new QueryRelationSymbol(
                    "default", "View2",
                    Provenance.USER, ShadowPolicy.PERMITTED,
                    SymbolCollector.UNRESOLVED_SCHEMA, view2Body));

            var engine = new SchemaInferenceEngine(table, annotations, SemanticFixtures.FUNCTIONS);
            engine.infer(List.of());

            var view2 = table.lookupRelation("View2").orElseThrow();
            assertThat(view2.schema().columns()).hasSize(1);
            assertThat(view2.schema().column("id")).isPresent();
        }

        @Test
        @DisplayName("Engine with no query symbols and no root queries produces no errors")
        void emptyEngineRun() {
            var engine = new SchemaInferenceEngine(table, annotations, SemanticFixtures.FUNCTIONS);
            engine.infer(List.of());

            assertThat(engine.errors()).isEmpty();
        }
    }

    // =========================================================================
    // General recursion (FIX) — schema inference through the recursive body
    // =========================================================================

    @Nested
    @DisplayName("General recursion (FIX) — schema inference")
    class Fixpoint {

        @BeforeEach
        void registerEdges() {
            register("Edges", col("src", ScalarType.NUMBER), col("dst", ScalarType.NUMBER));
        }

        @Test
        @DisplayName("FIX output schema is the base schema")
        void outputIsBaseSchema() {
            // FIX R (Edges, σ true (R))
            RelNode step = select(truePred(), recRef("R"));
            FixpointNode fix = fixpoint("R", rel("Edges"), step);

            Optional<Schema> result = fix.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get()).hasColumnNames("src", "dst");
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("recursive reference is bound to (and annotated with) the base schema")
        void recursiveRefBoundToBaseSchema() {
            RecursiveRefNode ref = recRef("R");
            FixpointNode fix = fixpoint("R", rel("Edges"),
                    select(truePred(), ref));

            fix.accept(visitor());

            assertThat(annotations.get(ref)).isPresent();
            assertThat(annotations.get(ref).get()).hasColumnNames("src", "dst");
        }

        @Test
        @DisplayName("nested FIX of the same name shadows the outer binding")
        void nestedShadowing() {
            // FIX R (Edges, FIX R (Edges, σ true (R)))  — inner R binds the inner FIX
            RecursiveRefNode innerRef = recRef("R");
            FixpointNode inner = fixpoint("R", rel("Edges"),
                    select(truePred(), innerRef));
            FixpointNode outer = fixpoint("R", rel("Edges"), inner);

            Optional<Schema> result = outer.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get()).hasColumnNames("src", "dst");
            // inner ref resolves against the inner binder (same shape here) — no error
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("open base schema propagates an open output schema")
        void openBasePropagates() {
            table.register(new SourceRelationSymbol(
                    "default", "Docs", Provenance.BUILTIN, ShadowPolicy.PERMITTED, Schema.open()));
            FixpointNode fix = fixpoint("R", rel("Docs"),
                    select(truePred(), recRef("R")));

            Optional<Schema> result = fix.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().isOpen()).isTrue();
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("recursive reference outside any FIX scope is an error")
        void unboundReferenceErrors() {
            RecursiveRefNode ref = recRef("R");

            Optional<Schema> result = ref.accept(visitor());

            assertThat(result).isEmpty();
            assertThat(errors).anySatisfy(e ->
                    assertThat(e.message()).contains("Recursive reference 'R'")
                            .contains("not bound"));
        }

        @Test
        @DisplayName("an unresolved base relation propagates failure (no annotation)")
        void unresolvedBasePropagates() {
            FixpointNode fix = fixpoint("R", rel("Missing"),
                    select(truePred(), recRef("R")));

            Optional<Schema> result = fix.accept(visitor());

            assertThat(result).isEmpty();
            assertThat(annotations.get(fix)).isEmpty();
        }
    }

    // =========================================================================
    // Static helper tests — concatenateSchemas and naturalJoinSchema
    // =========================================================================

    @Nested
    @DisplayName("Static schema helpers — concatenation and natural join")
    class StaticHelpers {

        private static Schema schema(String... namePairs) {
            List<ColumnDefinition> cols = new ArrayList<>();
            for (int i = 0; i < namePairs.length; i += 2) {
                cols.add(new ColumnDefinition(namePairs[i],
                        ScalarType.fromString(namePairs[i + 1])));
            }
            return new Schema(cols);
        }

        @Test
        @DisplayName("Schema.concat —no duplicate names → simple concatenation")
        void concatNoDupes() {
            Schema left  = schema("a", "NUMBER", "b", "STRING");
            Schema right = schema("c", "NUMBER", "d", "BOOLEAN");
            Schema result = left.concat(right);

            assertThat(result.columns()).hasSize(4);
            assertThat(result.column("a")).isPresent();
            assertThat(result.column("d")).isPresent();
        }

        @Test
        @DisplayName("Schema.concat —duplicate right name suffixed with _r")
        void concatDuplicateSuffixed() {
            Schema left  = schema("a", "NUMBER", "b", "STRING");
            Schema right = schema("b", "NUMBER", "c", "STRING");
            Schema result = left.concat(right);

            assertThat(result.column("b")).isPresent();    // left's b
            assertThat(result.column("b_r")).isPresent();  // right's b renamed
            assertThat(result.column("c")).isPresent();
        }

        @Test
        @DisplayName("naturalJoinSchema — shared column appears once, unique right columns appended")
        void naturalJoin() {
            Schema left  = schema("id", "NUMBER", "key", "STRING");
            Schema right = schema("key", "STRING", "extra", "NUMBER");
            Schema result = SchemaInferenceVisitor.naturalJoinSchema(left, right);

            assertThat(result.columns()).hasSize(3); // id, key, extra
            assertThat(result.column("id")).isPresent();
            assertThat(result.column("key")).isPresent();
            assertThat(result.column("extra")).isPresent();
        }

        @Test
        @DisplayName("Schema.concat —_r suffix counter increments when _r is also taken")
        void concatDoubleClashIncrementsCounter() {
            // Left has: a, b, b_r  (so "b_r" is already taken)
            // Right has: b, c  →  right's "b" tries "b_r" (taken), so becomes "b_r1"
            Schema left  = schema("a", "NUMBER", "b", "STRING", "b_r", "NUMBER");
            Schema right = schema("b", "STRING", "c", "NUMBER");
            Schema result = left.concat(right);

            assertThat(result.column("b")).isPresent();     // left's b
            assertThat(result.column("b_r")).isPresent();   // left's b_r
            assertThat(result.column("b_r1")).isPresent();  // right's b, renamed twice
            assertThat(result.column("c")).isPresent();
        }
    }

    // =========================================================================
    // RelationNode — qualified name lookup
    // =========================================================================

    @Nested
    @DisplayName("RelationNode — qualified name (namespace.Name)")
    class QualifiedRelationName {

        @Test
        @DisplayName("Qualified name 'ns.Users' resolves via two-arg lookupRelation")
        void qualifiedNameResolved() {
            // Register Users in an explicit namespace
            table.register(new SourceRelationSymbol(
                    "analytics", "Users", Provenance.BUILTIN, ShadowPolicy.PERMITTED,
                    new Schema(List.of(col("id", ScalarType.NUMBER)))));

            RelationNode node = rel("analytics.Users");
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("id")).isPresent();
        }

        @Test
        @DisplayName("Relation with UNRESOLVED_SCHEMA propagates as empty")
        void unresolvedSchemaPropagatesEmpty() {
            // Register a QueryRelationSymbol with UNRESOLVED_SCHEMA
            table.register(new QueryRelationSymbol(
                    "default", "Broken", Provenance.USER, ShadowPolicy.PERMITTED,
                    SymbolCollector.UNRESOLVED_SCHEMA,
                    rel("DoesNotExist")));

            RelationNode node = rel("Broken");
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // ProjectionNode — FunctionCall and UnaryOperand expressions
    // =========================================================================

    @Nested
    @DisplayName("ProjectionNode — expression types")
    class ProjectionExpressionTypes {

        @Test
        @DisplayName("FunctionCall in projection gets function name as default column name")
        void functionCallProjection() {
            // π Len(name) (Users)
            var fnCall = new com.darkcollective.relix.ast.FunctionCall("Len",
                    List.of(attr("name")));
            var node = project(
                    List.of(ProjectedAttribute.simple(fnCall)),
                    rel("Users"));

            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().column("Len")).isPresent();
        }

        @Test
        @DisplayName("UnaryOperand in projection uses '_col0' default name")
        void unaryOperandProjection() {
            // π -(id) (Users) — negation of id
            var unary = new com.darkcollective.relix.ast.UnaryOperand(attr("id"));
            var node = project(
                    List.of(ProjectedAttribute.simple(unary)),
                    rel("Users"));

            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns()).hasSize(1);
            // Default name is "_col0" for non-attribute, non-function operands
            assertThat(result.get().columns().get(0).name()).isEqualTo("_col0");
        }
    }

    // =========================================================================
    // AggregationNode — empty aggregation error
    // =========================================================================

    @Nested
    @DisplayName("AggregationNode — degenerate cases")
    class AggregationEdgeCases {

        @Test
        @DisplayName("Aggregation with no groups and no aggregates returns empty and records error")
        void emptyAggregationReturnsEmpty() {
            // γ (no groups, no aggregates) (Users)
            var node = groupBy(
                    List.of(),   // no grouping attributes
                    List.of(),   // no aggregate functions
                    rel("Users"));

            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isEmpty();
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).containsIgnoringCase("no grouping");
        }
    }
}

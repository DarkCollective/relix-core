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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.json.JsonWriter;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import com.darkcollective.relix.symbol.table.SymbolTable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for {@link LogicalPlanJson}.
 */
final class LogicalPlanJsonTest {

    // ─── helpers ────────────────────────────────────────────────────────────
    private static final SymbolTable EMPTY_TABLE = new InMemorySymbolTable();

    private static SchemaAnnotations annotate(RelNode node, Schema schema) {
        Map<RelNode, Schema> m = new LinkedHashMap<>();
        m.put(node, schema);
        return new SchemaAnnotations(m);
    }

    private static Schema schema(String name, ScalarType type) {
        return new Schema(List.of(new ColumnDefinition(name, type)));
    }

    private static RelNode viewBody(SemanticModel model, String name) {
        return ((QueryRelationSymbol) model.symbolTable().lookupRelation(name).orElseThrow())
                .body();
    }

    @Nested
    class LeafNodes {

        @Test
        void serializesRelationWithClosedSchema() {
            var orders = rel("Orders");
            String json = LogicalPlanJson.toJson(
                    orders, EMPTY_TABLE, annotate(orders, schema("id", ScalarType.NUMBER)));

            assertThat(json).isEqualTo(
                    "{\"op\":\"Relation\",\"label\":\"Orders [?]\","
                  + "\"relation\":\"Orders\",\"kind\":\"?\","
                  + "\"schema\":{\"open\":false,"
                  + "\"columns\":[{\"name\":\"id\",\"type\":\"N\"}]},"
                  + "\"materialization\":\"stream\",\"children\":[]}");
        }

        @Test
        void absentSchemaSerializesAsNull() {
            var orders = rel("Orders");
            String json = LogicalPlanJson.toJson(
                    orders, EMPTY_TABLE, SchemaAnnotations.empty());
            assertThat(json).contains("\"schema\":null");
        }

        @Test
        void unresolvedPlaceholderSerializesAsNull() {
            var orders = rel("Orders");
            var placeholder = new Schema(List.of(new ColumnDefinition("*", ScalarType.ANY)));
            String json = LogicalPlanJson.toJson(
                    orders, EMPTY_TABLE, annotate(orders, placeholder));
            assertThat(json).contains("\"schema\":null");
        }

        @Test
        void openSchemaSerializesWithEmptyColumns() {
            var orders = rel("Orders");
            String json = LogicalPlanJson.toJson(
                    orders, EMPTY_TABLE, annotate(orders, Schema.open()));
            assertThat(json).contains("\"schema\":{\"open\":true,\"columns\":[]}");
        }

        @Test
        void columnLiterallyNamedStarIsNotTreatedAsUnresolved() {
            // The unresolved placeholder is specifically "*":ANY; a real column
            // named "*" with a concrete type must serialize as a normal column.
            var rel = rel("T");
            var s = new Schema(List.of(new ColumnDefinition("*", ScalarType.NUMBER)));
            String json = LogicalPlanJson.toJson(rel, EMPTY_TABLE, annotate(rel, s));
            assertThat(json)
                    .contains("\"name\":\"*\",\"type\":\"N\"")
                    .doesNotContain("\"schema\":null");
        }

        @Test
        void emitsEachScalarTypeCode() {
            var rel = rel("T");
            var s = new Schema(List.of(
                    new ColumnDefinition("n", ScalarType.NUMBER),
                    new ColumnDefinition("s", ScalarType.STRING),
                    new ColumnDefinition("b", ScalarType.BOOLEAN),
                    new ColumnDefinition("a", ScalarType.ANY)));
            String json = LogicalPlanJson.toJson(rel, EMPTY_TABLE, annotate(rel, s));
            assertThat(json).contains(
                    "{\"name\":\"n\",\"type\":\"N\"},"
                  + "{\"name\":\"s\",\"type\":\"S\"},"
                  + "{\"name\":\"b\",\"type\":\"B\"},"
                  + "{\"name\":\"a\",\"type\":\"?\"}");
        }
    }

    @Nested
    class MaterializationModes {

        @Test
        void streamForRelation() {
            String json = LogicalPlanJson.toJson(
                    rel("R"), EMPTY_TABLE, SchemaAnnotations.empty());
            assertThat(json).contains("\"materialization\":\"stream\"");
        }

        @Test
        void setForUnion() {
            var union = union(rel("A"), rel("B"));
            String json = LogicalPlanJson.toJson(union, EMPTY_TABLE, SchemaAnnotations.empty());
            assertThat(json)
                    .contains("\"op\":\"Union\"")
                    .contains("\"materialization\":\"set\"");
        }

        @Test
        void bagForUnionAll() {
            var union = unionAll(rel("A"), rel("B"));
            String json = LogicalPlanJson.toJson(union, EMPTY_TABLE, SchemaAnnotations.empty());
            assertThat(json)
                    .contains("\"op\":\"UnionAll\"")
                    .contains("\"materialization\":\"bag\"");
        }

        @Test
        void sortForSortNode() {
            var sort = sort(
                    List.of(desc("amount")),
                    rel("R"));
            String json = LogicalPlanJson.toJson(sort, EMPTY_TABLE, SchemaAnnotations.empty());
            assertThat(json)
                    .contains("\"op\":\"Sort\"")
                    .contains("\"label\":\"τ amount↓\"")
                    .contains("\"materialization\":\"sort\"");
        }
    }

    @Nested
    class Structure {

        @Test
        void binaryNodeHasTwoChildren() {
            var union = union(rel("A"), rel("B"));
            String json = LogicalPlanJson.toJson(union, EMPTY_TABLE, SchemaAnnotations.empty());
            assertThat(json)
                    .contains("\"label\":\"A [?]\"")
                    .contains("\"label\":\"B [?]\"")
                    // two leaf children, each with empty children arrays
                    .contains("\"children\":[]");
        }

        @Test
        void writeEmbedsIntoAnExistingWriter() {
            var rel = rel("R");
            JsonWriter w = new JsonWriter();
            w.beginObject().name("logicalPlan");
            LogicalPlanJson.write(w, rel, EMPTY_TABLE, SchemaAnnotations.empty());
            w.endObject();
            assertThat(w.toJson())
                    .startsWith("{\"logicalPlan\":{\"op\":\"Relation\"")
                    .endsWith("}}");
        }
    }

    @Nested
    class NullGuards {

        @Test
        void writeRejectsNullWriter() {
            assertThatNullPointerException().isThrownBy(() ->
                    LogicalPlanJson.write(null, rel("R"),
                            EMPTY_TABLE, SchemaAnnotations.empty()));
        }

        @Test
        void writeRejectsNullRoot() {
            assertThatNullPointerException().isThrownBy(() ->
                    LogicalPlanJson.write(new JsonWriter(), null,
                            EMPTY_TABLE, SchemaAnnotations.empty()));
        }

        @Test
        void writeRejectsNullTable() {
            assertThatNullPointerException().isThrownBy(() ->
                    LogicalPlanJson.write(new JsonWriter(), rel("R"),
                            null, SchemaAnnotations.empty()));
        }

        @Test
        void writeRejectsNullSchemas() {
            assertThatNullPointerException().isThrownBy(() ->
                    LogicalPlanJson.write(new JsonWriter(), rel("R"),
                            EMPTY_TABLE, null));
        }
    }

    @Nested
    class Integration {

        @Test
        void serializesAnalyzedViewWithRealSchemaAndKind() {
            String src = """
                    source Orders from database {
                        url: "jdbc:h2:mem:t",
                        table: "orders",
                        schema: { id: NUMBER, amount: NUMBER }
                    };
                    Big := { σ amount > 100 (Orders) };
                    query Big;
                    """;
            SemanticModel model = model(src);
            String json = LogicalPlanJson.toJson(
                    viewBody(model, "Big"), model.symbolTable(), model.nodeSchemas());

            assertThat(json)
                    .contains("\"op\":\"Selection\"")
                    .contains("\"label\":\"σ amount > 100\"")
                    .contains("\"op\":\"Relation\"")
                    .contains("\"relation\":\"Orders\"")
                    .contains("\"kind\":\"")
                    // selection preserves the input schema, fully resolved
                    .contains("\"name\":\"amount\",\"type\":\"N\"")
                    .doesNotContain("\"schema\":null");
        }
    }
}

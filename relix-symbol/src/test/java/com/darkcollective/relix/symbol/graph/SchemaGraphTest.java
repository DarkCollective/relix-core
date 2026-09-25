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
package com.darkcollective.relix.symbol.graph;

import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SchemaGraph (ADR-0024)")
final class SchemaGraphTest {

    private static SourceRelationSymbol relation(String name, String... columns) {
        List<ColumnDefinition> defs = java.util.Arrays.stream(columns)
                .map(c -> new ColumnDefinition(c, ScalarType.NUMBER))
                .toList();
        return SourceRelationSymbol.of(name, new Schema(defs));
    }

    private static final SourceRelationSymbol ORDERS =
            relation("Orders", "order_id", "customer_id");
    private static final SourceRelationSymbol CUSTOMERS =
            relation("Customers", "customer_id");
    private static final SourceRelationSymbol ITEMS =
            relation("OrderItems", "order_id", "product_id");

    private static Relationship edge(String name, SourceRelationSymbol from, String fromCol,
                                     SourceRelationSymbol to, String toCol, EdgeOrigin origin) {
        return new Relationship(name, Optional.empty(), false,
                Endpoint.unbounded(from, List.of(fromCol)),
                Endpoint.unbounded(to, List.of(toCol)),
                origin);
    }

    @Nested
    @DisplayName("construction and accessors")
    class Construction {

        @Test
        @DisplayName("EMPTY has no edges; of(List.of()) returns EMPTY")
        void emptyGraph() {
            assertThat(SchemaGraph.EMPTY.isEmpty()).isTrue();
            assertThat(SchemaGraph.EMPTY.relationships()).isEmpty();
            assertThat(SchemaGraph.of(List.of())).isSameAs(SchemaGraph.EMPTY);
        }

        @Test
        @DisplayName("of() preserves insertion order and is defensive")
        void ofPreservesOrder() {
            Relationship a = edge("A", ORDERS, "customer_id", CUSTOMERS, "customer_id",
                    EdgeOrigin.DECLARED);
            Relationship b = edge("B", ORDERS, "order_id", ITEMS, "order_id",
                    EdgeOrigin.DECLARED);
            SchemaGraph graph = SchemaGraph.of(List.of(a, b));

            assertThat(graph.relationships()).containsExactly(a, b);
            assertThatThrownBy(() -> graph.relationships().add(a))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("key() combines namespace and canonical name")
        void keyShape() {
            assertThat(SchemaGraph.key(ORDERS)).isEqualTo("default:orders");
        }
    }

    @Nested
    @DisplayName("edgesOf")
    class EdgesOf {

        @Test
        @DisplayName("returns edges incident on either endpoint")
        void bothDirections() {
            Relationship placed = edge("Placed By", ORDERS, "customer_id",
                    CUSTOMERS, "customer_id", EdgeOrigin.DECLARED);
            Relationship lines = edge("Lines", ORDERS, "order_id",
                    ITEMS, "order_id", EdgeOrigin.DECLARED);
            SchemaGraph graph = SchemaGraph.of(List.of(placed, lines));

            assertThat(graph.edgesOf(ORDERS)).containsExactly(placed, lines);
            assertThat(graph.edgesOf(CUSTOMERS)).containsExactly(placed);
            assertThat(graph.edgesOf(ITEMS)).containsExactly(lines);
        }

        @Test
        @DisplayName("matches by namespace + canonical name, not instance")
        void matchesByKey() {
            Relationship placed = edge("Placed By", ORDERS, "customer_id",
                    CUSTOMERS, "customer_id", EdgeOrigin.DECLARED);
            SchemaGraph graph = SchemaGraph.of(List.of(placed));

            SourceRelationSymbol sameNameDifferentCase = relation("ORDERS", "x");
            assertThat(graph.edgesOf(sameNameDifferentCase)).containsExactly(placed);
        }

        @Test
        @DisplayName("unknown relation has no incident edges")
        void unknownRelation() {
            SchemaGraph graph = SchemaGraph.of(List.of(
                    edge("X", ORDERS, "order_id", ITEMS, "order_id", EdgeOrigin.DECLARED)));
            assertThat(graph.edgesOf(relation("Elsewhere", "id"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("with and merge")
    class WithAndMerge {

        @Test
        @DisplayName("with() returns a new graph; the original is unchanged")
        void withIsPersistent() {
            Relationship a = edge("A", ORDERS, "customer_id", CUSTOMERS, "customer_id",
                    EdgeOrigin.DECLARED);
            Relationship b = edge("B", ORDERS, "order_id", ITEMS, "order_id",
                    EdgeOrigin.LEARNED);

            SchemaGraph one = SchemaGraph.EMPTY.with(a);
            SchemaGraph two = one.with(b);

            assertThat(SchemaGraph.EMPTY.isEmpty()).isTrue();
            assertThat(one.relationships()).containsExactly(a);
            assertThat(two.relationships()).containsExactly(a, b);
        }

        @Test
        @DisplayName("merge unions distinct edges, preserving order")
        void mergeUnion() {
            Relationship a = edge("A", ORDERS, "customer_id", CUSTOMERS, "customer_id",
                    EdgeOrigin.DECLARED);
            Relationship b = edge("B", ORDERS, "order_id", ITEMS, "order_id",
                    EdgeOrigin.LEARNED);

            SchemaGraph merged = SchemaGraph.of(List.of(a)).merge(SchemaGraph.of(List.of(b)));
            assertThat(merged.relationships()).containsExactly(a, b);
        }

        @Test
        @DisplayName("DECLARED wins a duplicate over LEARNED, in either merge order")
        void declaredWins() {
            Relationship declared = edge("Placed By", ORDERS, "customer_id",
                    CUSTOMERS, "customer_id", EdgeOrigin.DECLARED);
            Relationship learned = edge("Placed By", ORDERS, "customer_id",
                    CUSTOMERS, "customer_id", EdgeOrigin.LEARNED);

            SchemaGraph a = SchemaGraph.of(List.of(declared)).merge(SchemaGraph.of(List.of(learned)));
            SchemaGraph b = SchemaGraph.of(List.of(learned)).merge(SchemaGraph.of(List.of(declared)));

            assertThat(a.relationships()).containsExactly(declared);
            assertThat(b.relationships()).containsExactly(declared);
        }

        @Test
        @DisplayName("LEARNED wins a duplicate over INFERRED")
        void learnedBeatsInferred() {
            Relationship learned = edge("X", ORDERS, "customer_id",
                    CUSTOMERS, "customer_id", EdgeOrigin.LEARNED);
            Relationship inferred = edge("X", ORDERS, "customer_id",
                    CUSTOMERS, "customer_id", EdgeOrigin.INFERRED);

            SchemaGraph merged = SchemaGraph.of(List.of(inferred))
                    .merge(SchemaGraph.of(List.of(learned)));
            assertThat(merged.relationships()).containsExactly(learned);
        }

        @Test
        @DisplayName("a reversed duplicate collapses onto the original")
        void reversedDuplicate() {
            Relationship forward = edge("Placed By", ORDERS, "customer_id",
                    CUSTOMERS, "customer_id", EdgeOrigin.DECLARED);
            Relationship reversed = edge("Placed By", CUSTOMERS, "customer_id",
                    ORDERS, "customer_id", EdgeOrigin.LEARNED);

            SchemaGraph merged = SchemaGraph.of(List.of(forward))
                    .merge(SchemaGraph.of(List.of(reversed)));
            assertThat(merged.relationships()).containsExactly(forward);
        }

        @Test
        @DisplayName("same endpoints under different names are distinct edges")
        void differentNamesAreDistinct() {
            Relationship assignee = edge("Assignee", ORDERS, "customer_id",
                    CUSTOMERS, "customer_id", EdgeOrigin.DECLARED);
            Relationship reporter = edge("Reporter", ORDERS, "customer_id",
                    CUSTOMERS, "customer_id", EdgeOrigin.DECLARED);

            SchemaGraph merged = SchemaGraph.of(List.of(assignee))
                    .merge(SchemaGraph.of(List.of(reporter)));
            assertThat(merged.relationships()).containsExactly(assignee, reporter);
        }

        @Test
        @DisplayName("merging with EMPTY returns the non-empty side")
        void mergeWithEmpty() {
            SchemaGraph graph = SchemaGraph.of(List.of(
                    edge("A", ORDERS, "customer_id", CUSTOMERS, "customer_id",
                            EdgeOrigin.DECLARED)));
            assertThat(graph.merge(SchemaGraph.EMPTY)).isSameAs(graph);
            assertThat(SchemaGraph.EMPTY.merge(graph)).isSameAs(graph);
        }
    }

    @Nested
    @DisplayName("Endpoint and Relationship invariants")
    class Invariants {

        @Test
        @DisplayName("endpoint defaults are the unconstrained [0..*]")
        void endpointDefaults() {
            Endpoint e = Endpoint.unbounded(ORDERS, List.of("order_id"));
            assertThat(e.min()).isZero();
            assertThat(e.max()).isEmpty();
            assertThat(e.bounded()).isFalse();
            assertThat(e.boundsLabel()).isEqualTo("[0..*]");
        }

        @Test
        @DisplayName("bounded endpoint renders its label")
        void boundsLabel() {
            Endpoint e = new Endpoint(ITEMS, List.of("order_id"), 1, OptionalLong.of(50));
            assertThat(e.bounded()).isTrue();
            assertThat(e.boundsLabel()).isEqualTo("[1..50]");
        }

        @Test
        @DisplayName("endpoint rejects empty columns and negative min")
        void endpointValidation() {
            assertThatThrownBy(() -> new Endpoint(ORDERS, List.of(), 0, OptionalLong.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Endpoint(ORDERS, List.of("x"), -1, OptionalLong.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("relationship rejects a blank name and symmetric + inverse name")
        void relationshipValidation() {
            Endpoint a = Endpoint.unbounded(ORDERS, List.of("order_id"));
            Endpoint b = Endpoint.unbounded(ITEMS, List.of("order_id"));

            assertThatThrownBy(() -> new Relationship(" ", Optional.empty(), false, a, b,
                    EdgeOrigin.DECLARED))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Relationship("X", Optional.of("Y"), true, a, b,
                    EdgeOrigin.DECLARED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("symmetric");
        }

        @Test
        @DisplayName("selfReferential() is true only for self-edges")
        void selfReferential() {
            Relationship self = edge("Manages", ORDERS, "order_id", ORDERS, "customer_id",
                    EdgeOrigin.DECLARED);
            Relationship cross = edge("Lines", ORDERS, "order_id", ITEMS, "order_id",
                    EdgeOrigin.DECLARED);
            assertThat(self.selfReferential()).isTrue();
            assertThat(cross.selfReferential()).isFalse();
        }
    }

    @Test
    @DisplayName("toString reports the edge count — it is what a debugger and a log line show")
    void toStringReportsTheEdgeCount() {
        assertThat(SchemaGraph.EMPTY.toString()).contains("0 relationship");
        assertThat(SchemaGraph.of(List.of(
                edge("places", ORDERS, "customer_id", CUSTOMERS, "customer_id",
                        EdgeOrigin.DECLARED))).toString())
                .contains("1 relationship");
    }

    @Nested
    @DisplayName("an edge's identity")
    class EdgeIdentity {

        @Test
        @DisplayName("ignores the name, the origin and which way round the edge was written")
        void sameEdge() {
            Relationship declared = edge("places", ORDERS, "customer_id", CUSTOMERS, "customer_id",
                    EdgeOrigin.DECLARED);
            Relationship learned = edge("Orders_customer", CUSTOMERS, "CUSTOMER_ID", ORDERS, "Customer_Id",
                    EdgeOrigin.LEARNED);
            assertThat(learned.edgeIdentity()).isEqualTo(declared.edgeIdentity());
        }

        @Test
        @DisplayName("differs when the columns differ")
        void differentColumns() {
            Relationship byCustomer = edge("a", ORDERS, "customer_id", CUSTOMERS, "customer_id",
                    EdgeOrigin.DECLARED);
            Relationship byOrder = edge("a", ORDERS, "order_id", CUSTOMERS, "customer_id",
                    EdgeOrigin.DECLARED);
            assertThat(byOrder.edgeIdentity()).isNotEqualTo(byCustomer.edgeIdentity());
        }
    }
}

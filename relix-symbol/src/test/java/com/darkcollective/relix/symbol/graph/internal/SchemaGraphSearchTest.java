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
package com.darkcollective.relix.symbol.graph.internal;

import com.darkcollective.relix.symbol.graph.EdgeOrigin;
import com.darkcollective.relix.symbol.graph.Endpoint;
import com.darkcollective.relix.symbol.graph.Relationship;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SchemaGraphSearch — minimal-path (Steiner) search (ADR-0024 §3)")
final class SchemaGraphSearchTest {

    private static SourceRelationSymbol relation(String name, String... columns) {
        List<ColumnDefinition> defs = java.util.Arrays.stream(columns)
                .map(c -> new ColumnDefinition(c, ScalarType.NUMBER))
                .toList();
        return SourceRelationSymbol.of(name, new Schema(defs));
    }

    private static QueryRelationSymbol view(String name, String... columns) {
        List<ColumnDefinition> defs = java.util.Arrays.stream(columns)
                .map(c -> new ColumnDefinition(c, ScalarType.NUMBER))
                .toList();
        // A minimal QueryRelationSymbol; the body is irrelevant to path search.
        return QueryRelationSymbol.of(name, new Schema(defs), NO_BODY);
    }

    /** An inert view body; path search never inspects it. */
    private static final RelNode NO_BODY = rel("__view_body__");

    @Nested
    @DisplayName("JoinPath and PathSearchResult value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("JoinPath rejects an empty relation set")
        void joinPathRequiresRelation() {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> new JoinPath(List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("JoinPath exposes relationKeys, names and table count")
        void joinPathAccessors() {
            Relationship e12 = edge("Detail", T1, "b", T2, "b");
            Relationship e23 = edge("Lines", T2, "c", T3, "c");
            JoinPath path = new JoinPath(List.of(e12, e23), List.of(T1, T2, T3));
            assertThat(path.relationKeys()).containsExactly("default:table1", "default:table2", "default:table3");
            assertThat(path.relationshipNames()).containsExactly("Detail", "Lines");
            assertThat(path.tableCount()).isEqualTo(3);
            assertThat(path.trivial()).isFalse();
        }

        @Test
        @DisplayName("disconnected result is neither unique nor ambiguous and has no single path")
        void disconnectedPredicates() {
            PathSearchResult result = new PathSearchResult(List.of(), List.of(T1));
            assertThat(result.disconnected()).isTrue();
            assertThat(result.unique()).isFalse();
            assertThat(result.ambiguous()).isFalse();
            assertThat(result.single()).isEmpty();
        }
    }

    private static Relationship edge(String name, RelationSymbol from, String fromCol,
                                     RelationSymbol to, String toCol) {
        return new Relationship(name, Optional.empty(), false,
                Endpoint.unbounded(from, List.of(fromCol)),
                Endpoint.unbounded(to, List.of(toCol)),
                EdgeOrigin.DECLARED);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static final SourceRelationSymbol T1 = relation("Table1", "a", "b");
    private static final SourceRelationSymbol T2 = relation("Table2", "b", "c");
    private static final SourceRelationSymbol T3 = relation("Table3", "c", "d");

    @Nested
    @DisplayName("degenerate cases")
    class Degenerate {

        @Test
        @DisplayName("no terminals → empty result, nothing to join")
        void noTerminals() {
            SchemaGraph graph = SchemaGraph.of(List.of(edge("R", T1, "b", T2, "b")));
            PathSearchResult result = SchemaGraphSearch.search(graph, List.of());
            assertThat(result.paths()).isEmpty();
            assertThat(result.unreachableTerminals()).isEmpty();
            assertThat(result.unique()).isFalse();
            assertThat(result.ambiguous()).isFalse();
            assertThat(result.disconnected()).isFalse();
        }

        @Test
        @DisplayName("single terminal → trivial no-join path")
        void singleTerminal() {
            SchemaGraph graph = SchemaGraph.of(List.of(edge("R", T1, "b", T2, "b")));
            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(T1));

            assertThat(result.unique()).isTrue();
            JoinPath path = result.single().orElseThrow();
            assertThat(path.trivial()).isTrue();
            assertThat(path.edges()).isEmpty();
            assertThat(path.relations()).containsExactly(T1);
            assertThat(path.tableCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("duplicate terminals collapse by graph key")
        void duplicateTerminals() {
            SchemaGraph graph = SchemaGraph.of(List.of(edge("R", T1, "b", T2, "b")));
            SourceRelationSymbol t1Again = relation("TABLE1", "a", "b"); // same key, different case
            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(T1, t1Again));
            assertThat(result.unique()).isTrue();
            assertThat(result.single().orElseThrow().trivial()).isTrue();
        }
    }

    @Nested
    @DisplayName("unique path — the mechanical common case")
    class Unique {

        @Test
        @DisplayName("Table1 —1:1— Table2 —1:many— Table3: T1+T3 has a unique 2-edge path through T2")
        void t1t3ThroughT2() {
            Relationship e12 = edge("Detail", T1, "b", T2, "b");
            Relationship e23 = edge("Lines", T2, "c", T3, "c");
            SchemaGraph graph = SchemaGraph.of(List.of(e12, e23));

            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(T1, T3));

            assertThat(result.unique()).isTrue();
            JoinPath path = result.single().orElseThrow();
            assertThat(path.edges()).containsExactlyInAnyOrder(e12, e23);
            assertThat(path.relations()).containsExactlyInAnyOrder(T1, T2, T3);
            assertThat(path.tableCount()).isEqualTo(3);
            assertThat(path.relationshipNames()).containsExactlyInAnyOrder("Detail", "Lines");
        }

        @Test
        @DisplayName("adjacent terminals have a single one-edge path")
        void adjacent() {
            Relationship e12 = edge("Detail", T1, "b", T2, "b");
            SchemaGraph graph = SchemaGraph.of(List.of(e12,
                    edge("Lines", T2, "c", T3, "c")));

            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(T1, T2));
            assertThat(result.unique()).isTrue();
            assertThat(result.single().orElseThrow().edges()).containsExactly(e12);
        }

        @Test
        @DisplayName("minimal path uses the fewest tables when a longer detour also connects")
        void prefersFewerTables() {
            // Direct T1—T3, plus a longer detour T1—T2—T3. Terminals {T1,T3} → direct wins.
            Relationship direct = edge("Direct", T1, "a", T3, "d");
            Relationship e12 = edge("Detail", T1, "b", T2, "b");
            Relationship e23 = edge("Lines", T2, "c", T3, "c");
            SchemaGraph graph = SchemaGraph.of(List.of(direct, e12, e23));

            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(T1, T3));
            assertThat(result.unique()).isTrue();
            JoinPath path = result.single().orElseThrow();
            assertThat(path.edges()).containsExactly(direct);
            assertThat(path.tableCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("ambiguity — surfaced as 'did you mean?' by relationship name")
    class Ambiguity {

        @Test
        @DisplayName("two FKs from issues into users → two enumerated single-edge paths")
        void twoForeignKeys() {
            SourceRelationSymbol issues = relation("Issues", "id", "assignee_id", "reporter_id");
            SourceRelationSymbol users = relation("Users", "id");
            Relationship assignee = edge("Assignee", issues, "assignee_id", users, "id");
            Relationship reporter = edge("Reporter", issues, "reporter_id", users, "id");
            SchemaGraph graph = SchemaGraph.of(List.of(assignee, reporter));

            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(issues, users));

            assertThat(result.ambiguous()).isTrue();
            assertThat(result.single()).isEmpty();
            assertThat(result.paths()).hasSize(2);
            assertThat(result.paths().stream().flatMap(p -> p.relationshipNames().stream()))
                    .containsExactlyInAnyOrder("Assignee", "Reporter");
            // Each alternative is a single edge; the ambiguity is speakable by name.
            assertThat(result.paths()).allSatisfy(p -> assertThat(p.edges()).hasSize(1));
        }

        @Test
        @DisplayName("a 3-cycle over three terminals enumerates all spanning trees, deterministically")
        void cycleEnumeratesTrees() {
            Relationship e12 = edge("AB", T1, "b", T2, "b");
            Relationship e23 = edge("BC", T2, "c", T3, "c");
            Relationship e13 = edge("AC", T1, "a", T3, "d");
            SchemaGraph graph = SchemaGraph.of(List.of(e12, e23, e13));

            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(T1, T2, T3));

            assertThat(result.ambiguous()).isTrue();
            assertThat(result.paths()).hasSize(3);
            assertThat(result.paths()).allSatisfy(p -> assertThat(p.edges()).hasSize(2));
            // Deterministic ordering (by relationship names) regardless of traversal.
            PathSearchResult again = SchemaGraphSearch.search(graph, List.of(T1, T2, T3));
            assertThat(result.paths().stream().map(JoinPath::relationshipNames).toList())
                    .isEqualTo(again.paths().stream().map(JoinPath::relationshipNames).toList());
        }
    }

    @Nested
    @DisplayName("composite-key edge — joined on all paired columns, never a prefix")
    class CompositeKey {

        @Test
        @DisplayName("a composite FK is one edge carried intact on the path")
        void compositeIntact() {
            SourceRelationSymbol slot = relation("WarehouseSlot", "tenant_id", "product_id");
            SourceRelationSymbol product = relation("Product", "tenant_id", "id");
            Relationship stocks = new Relationship("Stocks", Optional.empty(), false,
                    Endpoint.unbounded(slot, List.of("tenant_id", "product_id")),
                    Endpoint.unbounded(product, List.of("tenant_id", "id")),
                    EdgeOrigin.DECLARED);
            SchemaGraph graph = SchemaGraph.of(List.of(stocks));

            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(slot, product));

            assertThat(result.unique()).isTrue();
            Relationship onPath = result.single().orElseThrow().edges().get(0);
            assertThat(onPath.source().columns()).containsExactly("tenant_id", "product_id");
            assertThat(onPath.target().columns()).containsExactly("tenant_id", "id");
        }
    }

    @Nested
    @DisplayName("self-referential edges are tolerated, never mis-selected")
    class SelfReferential {

        @Test
        @DisplayName("an inverse-named self-edge is not chosen as a spanning-tree edge")
        void inverseNamedSelfEdge() {
            SourceRelationSymbol employees = relation("Employees", "id", "manager_id");
            Relationship manages = new Relationship("Manages", Optional.of("Reports To"), false,
                    Endpoint.unbounded(employees, List.of("id")),
                    Endpoint.unbounded(employees, List.of("manager_id")),
                    EdgeOrigin.DECLARED);
            Relationship worksIn = edge("Works In", employees, "id", T2, "b");
            SchemaGraph graph = SchemaGraph.of(List.of(manages, worksIn));

            // Terminals {Employees, Table2}: the only useful edge is Works In; the
            // self-loop Manages must not appear.
            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(employees, T2));
            assertThat(result.unique()).isTrue();
            assertThat(result.single().orElseThrow().edges()).containsExactly(worksIn);
        }

        @Test
        @DisplayName("a symmetric self-edge is likewise tolerated on a single-terminal query")
        void symmetricSelfEdge() {
            SourceRelationSymbol products = relation("Products", "id", "related_id");
            Relationship crossSell = new Relationship("Cross Sells", Optional.empty(), true,
                    Endpoint.unbounded(products, List.of("id")),
                    Endpoint.unbounded(products, List.of("related_id")),
                    EdgeOrigin.DECLARED);
            SchemaGraph graph = SchemaGraph.of(List.of(crossSell));

            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(products));
            assertThat(result.unique()).isTrue();
            assertThat(result.single().orElseThrow().trivial()).isTrue();
        }
    }

    @Nested
    @DisplayName("derived (view) endpoints — participate only when nominated (§1.5 (ii))")
    class DerivedEndpoints {

        // Customers —placed— Orders (base) ; ActiveOrders (view) —active-placed— Customers
        private final SourceRelationSymbol customers = relation("Customers", "id");
        private final SourceRelationSymbol orders = relation("Orders", "id", "customer_id");
        private final QueryRelationSymbol activeOrders = view("ActiveOrders", "id", "customer_id");

        private final Relationship placed = edge("Placed", orders, "customer_id", customers, "id");
        private final Relationship activePlaced =
                edge("Active Placed", activeOrders, "customer_id", customers, "id");

        private SchemaGraph graph() {
            return SchemaGraph.of(List.of(placed, activePlaced));
        }

        @Test
        @DisplayName("un-nominated view is excluded; the base path wins")
        void baseWinsUnNominated() {
            PathSearchResult result = SchemaGraphSearch.search(graph(), List.of(orders, customers));
            assertThat(result.unique()).isTrue();
            assertThat(result.single().orElseThrow().edges()).containsExactly(placed);
        }

        @Test
        @DisplayName("a view reachable only through its own edge is unreachable un-nominated")
        void viewUnreachableUnNominated() {
            // Terminals {ActiveOrders as data, Customers} but ActiveOrders is not a
            // terminal here — instead ask for a terminal only reachable via the view.
            SourceRelationSymbol promo = relation("Promo", "order_id");
            Relationship promoEdge = edge("Promoted", activeOrders, "id", promo, "order_id");
            SchemaGraph g = SchemaGraph.of(List.of(placed, activePlaced, promoEdge));

            // Promo connects to the rest ONLY through the ActiveOrders view.
            PathSearchResult result = SchemaGraphSearch.search(g, List.of(customers, promo));
            assertThat(result.disconnected()).isTrue();
            assertThat(result.unreachableTerminals()).containsExactly(promo);
        }

        @Test
        @DisplayName("which end of the edge the view sits on makes no difference")
        void exclusionIsSymmetric() {
            // Every other case here declares the view as the edge's *source*. A schema
            // graph carries the direction the relationship was declared in, which is an
            // author's choice and not a fact about the data, so the participation rule
            // has to read both endpoints. Reading only one would let an un-nominated
            // view back into a path by the accident of how its edge was written.
            SourceRelationSymbol promo = relation("Promo", "order_id");
            Relationship intoTheView = edge("Promoted", promo, "order_id", activeOrders, "id");
            SchemaGraph g = SchemaGraph.of(List.of(placed, activePlaced, intoTheView));

            PathSearchResult result = SchemaGraphSearch.search(g, List.of(customers, promo));

            assertThat(result.disconnected())
                    .as("Promo reaches the rest only through the un-nominated view")
                    .isTrue();
            assertThat(result.unreachableTerminals()).containsExactly(promo);
        }

        @Test
        @DisplayName("nominating the view lets it participate as a path node")
        void viewParticipatesWhenNominated() {
            SourceRelationSymbol promo = relation("Promo", "order_id");
            Relationship promoEdge = edge("Promoted", activeOrders, "id", promo, "order_id");
            SchemaGraph g = SchemaGraph.of(List.of(placed, activePlaced, promoEdge));

            PathSearchResult result = SchemaGraphSearch.search(
                    g, List.of(customers, promo), List.of(activeOrders));
            assertThat(result.unique()).isTrue();
            JoinPath path = result.single().orElseThrow();
            assertThat(path.edges()).containsExactlyInAnyOrder(activePlaced, promoEdge);
            assertThat(path.relations()).contains(activeOrders);
        }

        @Test
        @DisplayName("a nominated view that is a terminal participates without an explicit nomination list")
        void viewAsTerminalParticipates() {
            PathSearchResult result = SchemaGraphSearch.search(graph(), List.of(activeOrders, customers));
            assertThat(result.unique()).isTrue();
            assertThat(result.single().orElseThrow().edges()).containsExactly(activePlaced);
        }

        @Test
        @DisplayName("a base and a filtered view of it as competing endpoints: base-only un-nominated, both nominated")
        void competingBaseAndViewEndpoints() {
            // The ADR §1.5 (ii) fixture: two equally-minimal length-2 paths to OrderItems —
            //   Customers → Orders       → OrderItems   (base)
            //   Customers → ActiveOrders → OrderItems   (view)
            SourceRelationSymbol orderItems = relation("OrderItems", "order_id");
            Relationship ordersItems = edge("Line Items", orders, "id", orderItems, "order_id");
            Relationship activeItems = edge("Active Line Items", activeOrders, "id", orderItems, "order_id");
            SchemaGraph g = SchemaGraph.of(List.of(placed, activePlaced, ordersItems, activeItems));

            // Un-nominated: the view is excluded, so exactly one minimal path — through the base.
            PathSearchResult base = SchemaGraphSearch.search(g, List.of(customers, orderItems));
            assertThat(base.unique()).isTrue();
            assertThat(base.single().orElseThrow().relations())
                    .contains(orders).doesNotContain(activeOrders);

            // Nominated: the view competes, doubling the region into two equally-minimal paths.
            PathSearchResult nominated = SchemaGraphSearch.search(
                    g, List.of(customers, orderItems), List.of(activeOrders));
            assertThat(nominated.ambiguous()).isTrue();
            assertThat(nominated.paths()).hasSize(2);
            assertThat(nominated.paths())
                    .anySatisfy(p -> assertThat(p.relations()).contains(orders))
                    .anySatisfy(p -> assertThat(p.relations()).contains(activeOrders));
        }
    }

    @Nested
    @DisplayName("safety valve")
    class SafetyValve {

        @Test
        @DisplayName("a tiny exploration cap short-circuits to no result")
        void capStopsGrowth() {
            // A path T1—T2—T3 whose spanning needs growth beyond one seed edge.
            SchemaGraph graph = SchemaGraph.of(List.of(
                    edge("AB", T1, "b", T2, "b"),
                    edge("BC", T2, "c", T3, "c")));
            PathSearchResult result =
                    SchemaGraphSearch.search(graph, List.of(T1, T3), List.of(), 0);
            assertThat(result.paths()).isEmpty();
            assertThat(result.unreachableTerminals()).isEmpty();
        }
    }

    @Nested
    @DisplayName("disconnected terminals")
    class Disconnected {

        @Test
        @DisplayName("two terminals in different components → disconnected, the stranded one reported")
        void differentComponents() {
            SourceRelationSymbol island = relation("Island", "x");
            SchemaGraph graph = SchemaGraph.of(List.of(edge("R", T1, "b", T2, "b")));

            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(T1, island));
            assertThat(result.disconnected()).isTrue();
            assertThat(result.paths()).isEmpty();
            assertThat(result.unreachableTerminals()).containsExactly(island);
        }

        @Test
        @DisplayName("a terminal absent from the graph entirely is unreachable")
        void terminalNotInGraph() {
            SourceRelationSymbol ghost = relation("Ghost", "g");
            SchemaGraph graph = SchemaGraph.of(List.of(edge("R", T1, "b", T2, "b")));
            PathSearchResult result = SchemaGraphSearch.search(graph, List.of(T2, ghost));
            assertThat(result.disconnected()).isTrue();
            assertThat(result.unreachableTerminals()).containsExactly(ghost);
        }
    }

    @Nested
    @DisplayName("deterministic ordering of an ambiguous result")
    class DeterministicOrder {

        @Test
        @DisplayName("paths sharing every relationship name are ordered by their relation keys")
        void tieBreaksOnRelationKeys() {
            // The sort is by relationship names, THEN by relation keys. Two paths whose
            // names differ are settled by the first comparator, which is every ambiguous
            // fixture so far — so the tie-break had never run. Reaching it needs two paths
            // with the *same* edge names and different nodes, which a graph is free to
            // have: an edge name is not unique.
            SourceRelationSymbol root = relation("Root", "id");
            SourceRelationSymbol viaA = relation("AlphaMid", "id", "leaf_id");
            SourceRelationSymbol viaB = relation("BetaMid", "id", "leaf_id");
            SourceRelationSymbol leaf = relation("Leaf", "id");

            SchemaGraph g = SchemaGraph.of(List.of(
                    edge("places", root, "id", viaA, "id"),
                    edge("places", root, "id", viaB, "id"),
                    edge("holds", viaA, "leaf_id", leaf, "id"),
                    edge("holds", viaB, "leaf_id", leaf, "id")));

            PathSearchResult result = SchemaGraphSearch.search(g, List.of(root, leaf));
            assertThat(result.ambiguous()).isTrue();
            assertThat(result.paths()).hasSize(2);
            assertThat(result.paths())
                    .allSatisfy(p -> assertThat(p.relationshipNames())
                            .as("both paths carry the same edge names, so only the keys separate them")
                            .containsExactly("places", "holds"));

            // The order is a property of the result, not of traversal: searching the same
            // graph built in the opposite edge order must give the same sequence.
            SchemaGraph reversed = SchemaGraph.of(List.of(
                    edge("holds", viaB, "leaf_id", leaf, "id"),
                    edge("holds", viaA, "leaf_id", leaf, "id"),
                    edge("places", root, "id", viaB, "id"),
                    edge("places", root, "id", viaA, "id")));
            // Compare which middle relation each path runs through, in order. The raw key
            // set is in first-appearance order, which is a traversal detail and differs
            // legitimately between the two constructions — the *relative order of the
            // paths* is the property the tie-break actually promises.
            assertThat(midOrder(SchemaGraphSearch.search(reversed, List.of(root, leaf))))
                    .isEqualTo(midOrder(result))
                    .containsExactly("AlphaMid", "BetaMid");
        }

        /** The middle relation of each path, in the order the search returned them. */
        private static List<String> midOrder(PathSearchResult result) {
            return result.paths().stream()
                    .map(p -> p.relations().stream()
                            .map(RelationSymbol::declaredName)
                            .filter(n -> n.endsWith("Mid"))
                            .findFirst().orElseThrow())
                    .toList();
        }

        @Test
        @DisplayName("a self-loop edge is skipped — it joins a relation to itself and adds no path")
        void selfLoopEdgeIsSkipped() {
            SourceRelationSymbol a = relation("A", "id", "parent_id");
            SourceRelationSymbol b = relation("B", "id");
            SchemaGraph g = SchemaGraph.of(List.of(
                    edge("reports to", a, "parent_id", a, "id"),
                    edge("links", a, "id", b, "id")));

            PathSearchResult result = SchemaGraphSearch.search(g, List.of(a, b));
            assertThat(result.unique()).isTrue();
            assertThat(result.single().orElseThrow().relationshipNames())
                    .as("the self-loop contributes nothing to reach B")
                    .containsExactly("links");
        }
    }
}

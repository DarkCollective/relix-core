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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.Severity;
import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.symbol.graph.EdgeOrigin;
import com.darkcollective.relix.symbol.graph.Endpoint;
import com.darkcollective.relix.symbol.graph.Relationship;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

@DisplayName("SchemaGraphBuilder — Phase 4.7 schema graph assembly (ADR-0024)")
final class SchemaGraphBuilderTest {

    private static final String ORDERS_AND_CUSTOMERS = """
            source Customers from database { url: "${DB}", table: "customers",
                schema: { customer_id: NUMBER, name: STRING } };
            source Orders from database { url: "${DB}", table: "orders",
                schema: { order_id: NUMBER, customer_id: NUMBER, status: STRING } };
            """;

    private static SemanticResult analyzeWithSupplemental(String src, SchemaGraph supplemental) {
        var analyzer = new SemanticAnalyzer(new InMemoryScriptLoader(Map.of()))
                .withSupplementalRelationships(supplemental);
        return analyzer.analyze(ScriptParser.parse(src, SemanticAnalyzer.STDIN_PATH),
                SemanticAnalyzer.STDIN_PATH);
    }

    @Nested
    @DisplayName("relate statements")
    class RelateAssembly {

        @Test
        @DisplayName("a valid relate builds one DECLARED edge with its bounds")
        void validRelate() {
            SemanticModel m = model(ORDERS_AND_CUSTOMERS + """
                    relate "Placed By" Orders.customer_id -> Customers.customer_id [1..1];
                    """);

            assertThat(m.schemaGraph().relationships()).hasSize(1);
            Relationship r = m.schemaGraph().relationships().get(0);
            assertThat(r.name()).isEqualTo("Placed By");
            assertThat(r.origin()).isEqualTo(EdgeOrigin.DECLARED);
            assertThat(r.source().relation().declaredName()).isEqualTo("Orders");
            assertThat(r.source().columns()).containsExactly("customer_id");
            assertThat(r.target().relation().declaredName()).isEqualTo("Customers");
            assertThat(r.target().min()).isEqualTo(1);
            assertThat(r.target().max()).isEqualTo(OptionalLong.of(1));
        }

        @Test
        @DisplayName("inverse-named self-edge and symmetric self-edge both assemble")
        void selfEdges() {
            SemanticModel m = model("""
                    source Employees from database { url: "${DB}", table: "emp",
                        schema: { id: NUMBER, manager_id: NUMBER } };
                    source Products from database { url: "${DB}", table: "prod",
                        schema: { id: NUMBER, related_id: NUMBER } };
                    relate "Manages" / "Reports To" Employees.id -> Employees.manager_id;
                    relate symmetric "Cross Sells" Products.id -> Products.related_id;
                    """);

            assertThat(m.schemaGraph().relationships()).hasSize(2);
            Relationship manages = m.schemaGraph().relationships().get(0);
            assertThat(manages.inverseName()).contains("Reports To");
            assertThat(manages.selfReferential()).isTrue();
            Relationship crossSells = m.schemaGraph().relationships().get(1);
            assertThat(crossSells.symmetric()).isTrue();
        }

        @Test
        @DisplayName("a symmetric edge across two DIFFERENT relations is rejected")
        void symmetricAcrossTwoRelationsIsRejected() {
            // Symmetric means the two roles are interchangeable, which only holds when
            // both ends name the same relation — "A relates to B" read backwards is a
            // claim about B relating to A, and a graph search would follow it in a
            // direction the data does not support. The existing symmetric fixture is a
            // self-edge, so the two-relation case never reached this arm.
            var result = analyze("""
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { customer_id: NUMBER } };
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { customer_id: NUMBER } };
                    relate symmetric "Pairs" Customers.customer_id -> Orders.customer_id;
                    """);
            assertThat(result.errors()).anySatisfy(e ->
                    assertThat(e.message())
                            .contains("symmetric")
                            .contains("two different relations"));
        }

        @Test
        @DisplayName("a symmetric edge with an unresolvable endpoint reports that, not the symmetry")
        void symmetricWithAnUnresolvableEndpoint() {
            // The symmetry check needs both endpoints resolved before it can compare their
            // relations, and each presence test is its own arm. An unknown relation has
            // already been reported by name; adding "symmetric across two relations" on
            // top would be a second complaint derived from the first, about a relation
            // that does not exist.
            var result = analyze("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { customer_id: NUMBER } };
                    relate symmetric "Pairs" Ghost.customer_id -> Orders.customer_id;
                    """);
            assertThat(result.errors())
                    .as("the unknown endpoint is reported")
                    .isNotEmpty()
                    .noneSatisfy(e -> assertThat(e.message()).contains("two different relations"));
        }

        @Test
        @DisplayName("a view (QR) endpoint is admitted — derived endpoints, §1.5")
        void viewEndpoint() {
            SemanticModel m = model(ORDERS_AND_CUSTOMERS + """
                    ActiveOrders := { σ status = "active" (Orders) };
                    relate "Customer Active Orders"
                        Customers.customer_id -> ActiveOrders.customer_id;
                    """);

            assertThat(m.schemaGraph().relationships()).hasSize(1);
            RelationSymbol target =
                    m.schemaGraph().relationships().get(0).target().relation();
            assertThat(target).isInstanceOf(QueryRelationSymbol.class);
            assertThat(target.declaredName()).isEqualTo("ActiveOrders");
        }

        @Test
        @DisplayName("edgesOf finds the edge from both endpoints")
        void edgesOfWorksOnAssembledGraph() {
            SemanticModel m = model(ORDERS_AND_CUSTOMERS + """
                    relate "Placed By" Orders.customer_id -> Customers.customer_id;
                    """);

            RelationSymbol orders = m.symbolTable().resolveRelation("Orders").orElseThrow();
            RelationSymbol customers = m.symbolTable().resolveRelation("Customers").orElseThrow();
            assertThat(m.schemaGraph().edgesOf(orders)).hasSize(1);
            assertThat(m.schemaGraph().edgesOf(customers)).hasSize(1);
        }

        @Test
        @DisplayName("no relationships → SchemaGraph.EMPTY on the model")
        void emptyGraphByDefault() {
            SemanticModel m = model(ORDERS_AND_CUSTOMERS);
            assertThat(m.schemaGraph().isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("references: blocks")
    class ReferencesAssembly {

        @Test
        @DisplayName("an FK arrow builds a DECLARED edge with implied max 1 on the target")
        void impliedMaxOne() {
            SemanticModel m = model("""
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { customer_id: NUMBER } };
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER },
                        references: { customer_id -> Customers.customer_id } };
                    """);

            assertThat(m.schemaGraph().relationships()).hasSize(1);
            Relationship r = m.schemaGraph().relationships().get(0);
            assertThat(r.name()).isEqualTo("customer_id");
            assertThat(r.origin()).isEqualTo(EdgeOrigin.DECLARED);
            assertThat(r.source().relation().declaredName()).isEqualTo("Orders");
            assertThat(r.source().max()).isEmpty();
            assertThat(r.target().min()).isZero();
            assertThat(r.target().max()).isEqualTo(OptionalLong.of(1));
        }

        @Test
        @DisplayName("composite FK builds one edge with paired multi-column endpoints")
        void compositeReference() {
            SemanticModel m = model("""
                    source Product from database { url: "${DB}", table: "product",
                        schema: { tenant_id: NUMBER, id: NUMBER } };
                    source Slots from database { url: "${DB}", table: "slots",
                        schema: { tenant_id: NUMBER, product_id: NUMBER },
                        references: { (tenant_id, product_id) -> Product(tenant_id, id) } };
                    """);

            Relationship r = m.schemaGraph().relationships().get(0);
            assertThat(r.name()).isEqualTo("tenant_id, product_id");
            assertThat(r.source().columns()).containsExactly("tenant_id", "product_id");
            assertThat(r.target().columns()).containsExactly("tenant_id", "id");
        }

        @Test
        @DisplayName("two FKs to the same relation stay two distinct edges (assignee/reporter)")
        void twoForeignKeysSameTarget() {
            SemanticModel m = model("""
                    source Users from database { url: "${DB}", table: "users",
                        schema: { id: NUMBER, name: STRING } };
                    source Issues from database { url: "${DB}", table: "issues",
                        schema: { id: NUMBER, assignee_id: NUMBER, reporter_id: NUMBER },
                        references: {
                            assignee_id -> Users.id,
                            reporter_id -> Users.id
                        } };
                    """);

            assertThat(m.schemaGraph().relationships())
                    .extracting(Relationship::name)
                    .containsExactly("assignee_id", "reporter_id");

            RelationSymbol users = m.symbolTable().resolveRelation("Users").orElseThrow();
            assertThat(m.schemaGraph().edgesOf(users)).hasSize(2);
        }

        @Test
        @DisplayName("references block on a CSV file source builds an edge")
        void csvSourceReferences() {
            SemanticModel m = model("""
                    source Countries from csv("countries.csv") {
                        schema: { code: STRING, name: STRING } };
                    source Cities from csv("cities.csv") {
                        schema: { name: STRING, country: STRING },
                        references: { country -> Countries.code } };
                    """);

            assertThat(m.schemaGraph().relationships()).hasSize(1);
            Relationship r = m.schemaGraph().relationships().get(0);
            assertThat(r.source().relation().declaredName()).isEqualTo("Cities");
            assertThat(r.target().relation().declaredName()).isEqualTo("Countries");
            assertThat(r.target().max()).isEqualTo(OptionalLong.of(1));
        }

        @Test
        @DisplayName("references block on an open JSON source builds an edge")
        void jsonSourceReferences() {
            SemanticModel m = model("""
                    source Users from database { url: "${DB}", table: "users",
                        schema: { id: NUMBER } };
                    source Docs from json("docs.json") {
                        references: { owner_id -> Users.id } };
                    """);

            assertThat(m.schemaGraph().relationships()).hasSize(1);
            Relationship r = m.schemaGraph().relationships().get(0);
            assertThat(r.source().relation().declaredName()).isEqualTo("Docs");
            // The open source's own columns resolve dynamically; the target
            // column was still validated against Users' declared schema.
            assertThat(r.source().columns()).containsExactly("owner_id");
        }

        @Test
        @DisplayName("an inline table's references clause builds an edge")
        void inlineTableReferences() {
            SemanticModel m = model("""
                    source Countries from database { url: "${DB}", table: "countries",
                        schema: { code: STRING, name: STRING } };
                    Cities := [
                    | name    | country |
                    |---------|---------|
                    | Chicago | US      |
                    ] references { country -> Countries.code };
                    """);

            assertThat(m.schemaGraph().relationships()).hasSize(1);
            Relationship r = m.schemaGraph().relationships().get(0);
            assertThat(r.name()).isEqualTo("country");
            assertThat(r.source().relation().declaredName()).isEqualTo("Cities");
            assertThat(r.target().relation().declaredName()).isEqualTo("Countries");
        }

        @Test
        @DisplayName("an inline-table reference validates its own columns")
        void inlineTableReferenceValidation() {
            SemanticResult result = analyze("""
                    source Countries from database { url: "${DB}", table: "countries",
                        schema: { code: STRING } };
                    Cities := [
                    | name    |
                    |---------|
                    | Chicago |
                    ] references { no_such_col -> Countries.code };
                    """);

            assertThat(result).hasErrors();
            assertThat(result.errors())
                    .anySatisfy(e -> assertThat(e.message())
                            .contains("'Cities'")
                            .contains("no column 'no_such_col'"));
        }

        @Test
        @DisplayName("references in an imported file contribute to the graph")
        void referencesFromImport() {
            SemanticModel m = model("""
                    import "lib.relix";
                    query { π name (Customers) };
                    """,
                    Map.of("lib.relix", """
                            source Customers from database { url: "${DB}", table: "c",
                                schema: { customer_id: NUMBER, name: STRING } };
                            source Orders from database { url: "${DB}", table: "o",
                                schema: { order_id: NUMBER, customer_id: NUMBER },
                                references: { customer_id -> Customers.customer_id } };
                            """));

            assertThat(m.schemaGraph().relationships()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("declared-edge validation — hard errors")
    class DeclaredValidation {



        @Test
        @DisplayName("unknown relation in a relate endpoint")
        void unknownRelation() {
            assertThat(analyze(ORDERS_AND_CUSTOMERS + """
                    relate "X" Orders.customer_id -> Nowhere.customer_id;
                    """))
                    .errorMessages()
                    .anySatisfy(msg -> assertThat(msg)
                            .contains("Unknown relation 'Nowhere'")
                            .contains("relationship 'X'"));
        }

        @Test
        @DisplayName("unknown target relation in a references entry")
        void unknownReferenceTarget() {
            assertThat(analyze("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER },
                        references: { customer_id -> Nowhere.customer_id } };
                    """))
                    .errorMessages()
                    .anySatisfy(msg -> assertThat(msg)
                            .contains("Unknown relation 'Nowhere'")
                            .contains("source 'Orders'"));
        }

        @Test
        @DisplayName("missing column on an endpoint relation")
        void missingColumn() {
            assertThat(analyze(ORDERS_AND_CUSTOMERS + """
                    relate "X" Orders.no_such_col -> Customers.customer_id;
                    """))
                    .errorMessages()
                    .anySatisfy(msg -> assertThat(msg)
                            .contains("'Orders'")
                            .contains("no column 'no_such_col'"));
        }

        @Test
        @DisplayName("unequal column-list lengths")
        void unequalColumnLists() {
            assertThat(analyze(ORDERS_AND_CUSTOMERS + """
                    relate "X" Orders(order_id, customer_id) -> Customers(customer_id);
                    """))
                    .errorMessages()
                    .anySatisfy(msg -> assertThat(msg).contains("equal length"));
        }

        @Test
        @DisplayName("bounds with max below min or max of zero")
        void invalidBounds() {
            assertThat(analyze(ORDERS_AND_CUSTOMERS + """
                    relate "X" Orders.customer_id -> Customers.customer_id [5..2];
                    """))
                    .errorMessages()
                    .anySatisfy(msg -> assertThat(msg).contains("Invalid multiplicity bounds"));

            assertThat(analyze(ORDERS_AND_CUSTOMERS + """
                    relate "X" Orders.customer_id -> Customers.customer_id [0..0];
                    """))
                    .errorMessages()
                    .anySatisfy(msg -> assertThat(msg).contains("Invalid multiplicity bounds"));
        }

        @Test
        @DisplayName("symmetric on a non-self edge")
        void symmetricNonSelfEdge() {
            assertThat(analyze(ORDERS_AND_CUSTOMERS + """
                    relate symmetric "X" Orders.customer_id -> Customers.customer_id;
                    """))
                    .errorMessages()
                    .anySatisfy(msg -> assertThat(msg).contains("symmetric"));
        }

        @Test
        @DisplayName("symmetric combined with an inverse name")
        void symmetricWithInverse() {
            assertThat(analyze("""
                    source Products from database { url: "${DB}", table: "p",
                        schema: { id: NUMBER, related_id: NUMBER } };
                    relate symmetric "X" / "Y" Products.id -> Products.related_id;
                    """))
                    .errorMessages()
                    .anySatisfy(msg -> assertThat(msg).contains("cannot also name an inverse"));
        }

        @Test
        @DisplayName("exact duplicate declaration")
        void duplicateDeclaration() {
            assertThat(analyze(ORDERS_AND_CUSTOMERS + """
                    relate "Placed By" Orders.customer_id -> Customers.customer_id;
                    relate "Placed By" Orders.customer_id -> Customers.customer_id;
                    """))
                    .errorMessages()
                    .anySatisfy(msg -> assertThat(msg)
                            .contains("Duplicate relationship declaration 'Placed By'"));
        }

        @Test
        @DisplayName("errors carry the declaration's source position")
        void errorPosition() {
            SemanticResult result = analyze(
                    "relate \"X\" Nowhere.a -> AlsoNowhere.b;");
            assertThat(result.errors())
                    .anySatisfy(e -> {
                        assertThat(e.message()).contains("Unknown relation 'Nowhere'");
                        assertThat(e.line()).isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("an invalid edge is excluded from the graph but analysis continues")
        void invalidEdgeExcluded() {
            SemanticResult result = analyze(ORDERS_AND_CUSTOMERS + """
                    relate "Bad" Orders.no_such_col -> Customers.customer_id;
                    relate "Good" Orders.customer_id -> Customers.customer_id;
                    """);

            assertThat(result).hasErrors();
            assertThat(result.model().orElseThrow().schemaGraph().relationships())
                    .extracting(Relationship::name)
                    .containsExactly("Good");
        }
    }

    @Nested
    @DisplayName("supplemental (session-learned) edges")
    class SupplementalEdges {

        private static SchemaGraph learnedEdge(SemanticModel from, String name,
                                               String sourceRel, String sourceCol,
                                               String targetRel, String targetCol) {
            RelationSymbol source = from.symbolTable().resolveRelation(sourceRel).orElseThrow();
            RelationSymbol target = from.symbolTable().resolveRelation(targetRel).orElseThrow();
            return SchemaGraph.EMPTY.with(new Relationship(name, Optional.empty(), false,
                    Endpoint.unbounded(source, List.of(sourceCol)),
                    Endpoint.unbounded(target, List.of(targetCol)),
                    EdgeOrigin.LEARNED));
        }

        @Test
        @DisplayName("a valid LEARNED edge is merged and visible on the model")
        void learnedEdgeMerged() {
            SemanticModel first = model(ORDERS_AND_CUSTOMERS);
            SchemaGraph learned = learnedEdge(first, "Placed By",
                    "Orders", "customer_id", "Customers", "customer_id");

            SemanticResult result = analyzeWithSupplemental(ORDERS_AND_CUSTOMERS, learned);

            assertThat(result).isFullyValid();
            SchemaGraph graph = result.model().orElseThrow().schemaGraph();
            assertThat(graph.relationships()).hasSize(1);
            assertThat(graph.relationships().get(0).origin()).isEqualTo(EdgeOrigin.LEARNED);
        }

        @Test
        @DisplayName("a stale LEARNED edge demotes to a warning and is excluded")
        void staleLearnedEdgeDropsWithWarning() {
            SemanticModel first = model(ORDERS_AND_CUSTOMERS);
            SchemaGraph learned = learnedEdge(first, "Placed By",
                    "Orders", "customer_id", "Customers", "customer_id");

            // Re-analyze a session where Customers no longer exists.
            String withoutCustomers = """
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER, status: STRING } };
                    """;
            SemanticResult result = analyzeWithSupplemental(withoutCustomers, learned);

            assertThat(result).hasNoErrors();
            assertThat(result.errors())
                    .anySatisfy(e -> {
                        assertThat(e.severity()).isEqualTo(Severity.WARNING);
                        assertThat(e.message()).contains("Dropped learned relationship 'Placed By'");
                    });
            assertThat(result.model().orElseThrow().schemaGraph().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a LEARNED edge whose column vanished is dropped with a warning")
        void staleColumnDropsWithWarning() {
            SemanticModel first = model(ORDERS_AND_CUSTOMERS);
            SchemaGraph learned = learnedEdge(first, "Placed By",
                    "Orders", "customer_id", "Customers", "customer_id");

            String customersWithoutKey = """
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { name: STRING } };
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER } };
                    """;
            SemanticResult result = analyzeWithSupplemental(customersWithoutKey, learned);

            assertThat(result).hasNoErrors();
            assertThat(result.errors())
                    .anySatisfy(e -> {
                        assertThat(e.severity()).isEqualTo(Severity.WARNING);
                        assertThat(e.message()).contains("no longer has column 'customer_id'");
                    });
            assertThat(result.model().orElseThrow().schemaGraph().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a DECLARED edge wins over the same LEARNED edge")
        void declaredWinsOverLearned() {
            SemanticModel first = model(ORDERS_AND_CUSTOMERS);
            SchemaGraph learned = learnedEdge(first, "Placed By",
                    "Orders", "customer_id", "Customers", "customer_id");

            SemanticResult result = analyzeWithSupplemental(ORDERS_AND_CUSTOMERS + """
                    relate "Placed By" Orders.customer_id -> Customers.customer_id [1..1];
                    """, learned);

            SchemaGraph graph = result.model().orElseThrow().schemaGraph();
            assertThat(graph.relationships()).hasSize(1);
            assertThat(graph.relationships().get(0).origin()).isEqualTo(EdgeOrigin.DECLARED);
        }

        @Test
        @DisplayName("surviving supplemental endpoints are re-bound to current symbols")
        void survivorsRebound() {
            SemanticModel first = model(ORDERS_AND_CUSTOMERS);
            SchemaGraph learned = learnedEdge(first, "Placed By",
                    "Orders", "customer_id", "Customers", "customer_id");

            SemanticResult second = analyzeWithSupplemental(ORDERS_AND_CUSTOMERS, learned);
            SemanticModel m = second.model().orElseThrow();
            RelationSymbol currentOrders = m.symbolTable().resolveRelation("Orders").orElseThrow();

            Relationship r = m.schemaGraph().relationships().get(0);
            assertThat(r.source().relation()).isSameAs(currentOrders);
        }
    }

    @Nested
    @DisplayName("derived (view) endpoint bounds — inheritance through a filter (§1.5 (i))")
    class DerivedEndpointBounds {

        private static Relationship edge(SemanticModel m, String name) {
            return m.schemaGraph().relationships().stream()
                    .filter(r -> r.name().equals(name)).findFirst().orElseThrow();
        }

        @Test
        @DisplayName("across a σ view, max is preserved and min relaxes to 0")
        void inheritsThroughFilter() {
            SemanticModel m = model(ORDERS_AND_CUSTOMERS + """
                    relate "Places" Customers.customer_id -> Orders.customer_id [1..10];
                    ActiveOrders := { σ status = "active" (Orders) };
                    relate "Customer Active Orders"
                        Customers.customer_id -> ActiveOrders.customer_id;
                    """);

            Endpoint active = edge(m, "Customer Active Orders").target();
            assertThat(active.relation().declaredName()).isEqualTo("ActiveOrders");
            assertThat(active.min()).isZero();
            assertThat(active.max()).isEqualTo(OptionalLong.of(10));
        }

        @Test
        @DisplayName("an unbounded base relationship yields an unbounded view endpoint")
        void inheritsUnboundedMax() {
            SemanticModel m = model(ORDERS_AND_CUSTOMERS + """
                    relate "Places" Customers.customer_id -> Orders.customer_id [1..*];
                    ActiveOrders := { σ status = "active" (Orders) };
                    relate "Customer Active Orders"
                        Customers.customer_id -> ActiveOrders.customer_id;
                    """);

            Endpoint active = edge(m, "Customer Active Orders").target();
            assertThat(active.min()).isZero();
            assertThat(active.max()).isEmpty();
        }

        @Test
        @DisplayName("the view side derives even when it is the edge's source endpoint")
        void derivesOnSourceSide() {
            SemanticModel m = model(ORDERS_AND_CUSTOMERS + """
                    relate "Places" Customers.customer_id -> Orders.customer_id [1..10];
                    ActiveOrders := { σ status = "active" (Orders) };
                    relate "Active Orders Of Customer"
                        ActiveOrders.customer_id -> Customers.customer_id;
                    """);

            Relationship r = edge(m, "Active Orders Of Customer");
            assertThat(r.source().relation().declaredName()).isEqualTo("ActiveOrders");
            assertThat(r.source().min()).isZero();
            assertThat(r.source().max()).isEqualTo(OptionalLong.of(10));
        }

        @Test
        @DisplayName("the base edge is matched from either end, and inherits that end's bounds")
        void baseEdgeMatchedFromEitherEnd() {
            // The base relationship is found by its two endpoints, whichever way round the
            // author wrote it — but what the view inherits is the bounds on the endpoint
            // that names the *base relation*, and a written cardinality describes the
            // target. So `Orders -> Customers [1..1]` says an order has one customer and
            // says nothing about how many orders a customer has: there is no maximum for
            // a view of Orders to inherit, and it stays unbounded rather than borrowing a
            // number that describes the other side.
            SemanticModel m = model(ORDERS_AND_CUSTOMERS + """
                    relate "Belongs To" Orders.customer_id -> Customers.customer_id [1..1];
                    ActiveOrders := { σ status = "active" (Orders) };
                    relate "Customer Active Orders"
                        Customers.customer_id -> ActiveOrders.customer_id;
                    """);

            Endpoint active = edge(m, "Customer Active Orders").target();
            assertThat(active.min()).isZero();
            assertThat(active.max())
                    .as("the [1..1] is Customers' bound, not Orders'")
                    .isEmpty();
        }

        @Test
        @DisplayName("bounds the author declared and the engine derived agree — no warning")
        void authorBoundsThatMatchAreNotWarnedAbout() {
            // The warning is for bounds that were overridden. Declaring the ones the
            // engine would derive anyway overrides nothing, and warning about it would
            // train a reader to ignore the warning.
            SemanticResult result = analyze(ORDERS_AND_CUSTOMERS + """
                    relate "Places" Customers.customer_id -> Orders.customer_id [1..10];
                    ActiveOrders := { σ status = "active" (Orders) };
                    relate "Customer Active Orders"
                        Customers.customer_id -> ActiveOrders.customer_id [0..10];
                    """);

            assertThat(result.errors()).isEmpty();
            Endpoint active = edge(result.model().orElseThrow(), "Customer Active Orders").target();
            assertThat(active.min()).isZero();
            assertThat(active.max()).isEqualTo(OptionalLong.of(10));
        }

        @Test
        @DisplayName("no inheritance across a γ view — bounds stay at their 0/∞ defaults")
        void noInheritanceAcrossAggregation() {
            SemanticModel m = model(ORDERS_AND_CUSTOMERS + """
                    relate "Places" Customers.customer_id -> Orders.customer_id [1..10];
                    OrderCounts := { γ customer_id, COUNT(order_id) → n (Orders) };
                    relate "Customer Order Counts"
                        Customers.customer_id -> OrderCounts.customer_id;
                    """);

            Endpoint counts = edge(m, "Customer Order Counts").target();
            assertThat(counts.min()).isZero();
            assertThat(counts.max()).isEmpty();
        }

        @Test
        @DisplayName("no base relationship to inherit from → bounds stay declared/default")
        void noBaseEdge() {
            SemanticModel m = model(ORDERS_AND_CUSTOMERS + """
                    ActiveOrders := { σ status = "active" (Orders) };
                    relate "Customer Active Orders"
                        Customers.customer_id -> ActiveOrders.customer_id;
                    """);

            Endpoint active = edge(m, "Customer Active Orders").target();
            assertThat(active.min()).isZero();
            assertThat(active.max()).isEmpty();
        }

        @Test
        @DisplayName("author-declared bounds on a filtering view are overridden, with a warning")
        void authorBoundsIgnoredWithWarning() {
            SemanticResult result = analyze(ORDERS_AND_CUSTOMERS + """
                    relate "Places" Customers.customer_id -> Orders.customer_id [1..10];
                    ActiveOrders := { σ status = "active" (Orders) };
                    relate "Customer Active Orders"
                        Customers.customer_id -> ActiveOrders.customer_id [3..5];
                    """);

            SemanticModel m = result.model().orElseThrow();
            Endpoint active = edge(m, "Customer Active Orders").target();
            assertThat(active.min()).isZero();
            assertThat(active.max()).isEqualTo(OptionalLong.of(10));

            assertThat(result.errors()).anySatisfy(e -> {
                assertThat(e.severity()).isEqualTo(Severity.WARNING);
                assertThat(e.message()).contains("were ignored");
                assertThat(e.message()).contains("ActiveOrders");
            });
        }
    }
}

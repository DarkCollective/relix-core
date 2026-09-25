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
package com.darkcollective.relix.semantic.graph;

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.graph.JoinResolution;
import com.darkcollective.relix.symbol.graph.JoinResolution.Ambiguous;
import com.darkcollective.relix.symbol.graph.JoinResolution.Passthrough;
import com.darkcollective.relix.symbol.graph.JoinResolution.Resolved;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import com.darkcollective.relix.symbol.table.SymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JoinPathResolver — engine-assembled joins over the schema graph (ADR-0024 §3)")
final class JoinPathResolverTest {

    /** An arbitrary (deliberately wrong) join condition the resolver should override. */
    private static Predicate dummy() {
        return cmp(
                attr("x"), ComparisonOperator.EQUAL, attr("y"));
    }

    private static RelNode join(RelNode left, RelNode right) {
        return AstBuilders.join(left, right, dummy());
    }

    private static JoinResolution resolve(SemanticModel m, RelNode program) {
        return JoinPathResolver.resolve(m.schemaGraph(), m.symbolTable(), program);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static final String TWO_FK = """
            source Users from database { url: "${DB}", table: "users",
                schema: { user_id: NUMBER, name: STRING } };
            source Issues from database { url: "${DB}", table: "issues",
                schema: { issue_id: NUMBER, assignee_id: NUMBER, reporter_id: NUMBER } };
            relate "Assignee" Issues.assignee_id -> Users.user_id;
            relate "Reporter" Issues.reporter_id -> Users.user_id;
            """;

    private static final String CHAIN = """
            source A from database { url: "${DB}", table: "a",
                schema: { a_id: NUMBER, b_ref: NUMBER } };
            source B from database { url: "${DB}", table: "b",
                schema: { b_id: NUMBER, c_ref: NUMBER } };
            source C from database { url: "${DB}", table: "c",
                schema: { c_id: NUMBER } };
            relate "AB" A.b_ref -> B.b_id;
            relate "BC" B.c_ref -> C.c_id;
            """;

    @Nested
    @DisplayName("ambiguity — the two-FK issues → users case")
    class Ambiguity {

        @Test
        @DisplayName("two FKs into the same parent enumerate both relationships by name")
        void twoForeignKeys() {
            SemanticModel m = model(TWO_FK);
            JoinResolution r = resolve(m, join(rel("Issues"), rel("Users")));

            assertThat(r).isInstanceOf(Ambiguous.class);
            Ambiguous amb = (Ambiguous) r;
            assertThat(amb.alternatives()).hasSize(2);
            assertThat(amb.alternatives().stream().map(JoinResolution.Alternative::label))
                    .containsExactlyInAnyOrder("Assignee", "Reporter");

            JoinResolution.Alternative assignee = amb.alternatives().stream()
                    .filter(a -> a.label().equals("Assignee")).findFirst().orElseThrow();
            assertThat(assignee.program())
                    .contains("Issues.assignee_id")
                    .contains("Users.user_id");
            assertThat(assignee.description()).contains("assignee_id");
        }
    }

    @Nested
    @DisplayName("unique path — mechanical assembly")
    class Unique {

        @Test
        @DisplayName("a two-table join has its wrong condition corrected to the graph edge")
        void correctsCondition() {
            SemanticModel m = model("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER } };
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { customer_id: NUMBER, name: STRING } };
                    relate "Placed" Orders.customer_id -> Customers.customer_id;
                    """);
            JoinResolution r = resolve(m, join(rel("Orders"), rel("Customers")));

            assertThat(r).isInstanceOf(Resolved.class);
            Resolved resolved = (Resolved) r;
            assertThat(resolved.program())
                    .contains("Orders.customer_id = Customers.customer_id")
                    .doesNotContain("x = y");   // the dummy condition is gone
        }

        @Test
        @DisplayName("a three-table chain assembles both hops with graph conditions")
        void chainAssembly() {
            SemanticModel m = model(CHAIN);
            JoinResolution r = resolve(m, join(join(rel("A"), rel("B")), rel("C")));

            assertThat(r).isInstanceOf(Resolved.class);
            assertThat(((Resolved) r).program())
                    .contains("A.b_ref = B.b_id")
                    .contains("B.c_ref = C.c_id");
        }

        @Test
        @DisplayName("a composite-key edge joins on all paired columns")
        void compositeKey() {
            SemanticModel m = model("""
                    source Product from database { url: "${DB}", table: "product",
                        schema: { tenant_id: NUMBER, id: NUMBER } };
                    source WarehouseSlot from database { url: "${DB}", table: "slot",
                        references: { (tenant_id, product_id) -> Product(tenant_id, id) },
                        schema: { tenant_id: NUMBER, product_id: NUMBER } };
                    """);
            JoinResolution r = resolve(m, join(rel("WarehouseSlot"), rel("Product")));

            assertThat(r).isInstanceOf(Resolved.class);
            assertThat(((Resolved) r).program())
                    .contains("WarehouseSlot.tenant_id = Product.tenant_id")
                    .contains("WarehouseSlot.product_id = Product.id");
        }

        @Test
        @DisplayName("a nominated view (QR) endpoint participates as a join leaf")
        void viewLeafParticipates() {
            SemanticModel m = model("""
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { customer_id: NUMBER, name: STRING } };
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER, status: STRING } };
                    ActiveOrders := { σ status = "active" (Orders) };
                    relate "Customer Active Orders"
                        Customers.customer_id -> ActiveOrders.customer_id;
                    """);
            JoinResolution r = resolve(m, join(rel("Customers"), rel("ActiveOrders")));
            assertThat(r).isInstanceOf(Resolved.class);
            assertThat(((Resolved) r).program())
                    .contains("Customers.customer_id = ActiveOrders.customer_id");
        }

        @Test
        @DisplayName("edge orientation is handled with the parent listed first")
        void reverseOrientation() {
            SemanticModel m = model("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER } };
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { customer_id: NUMBER } };
                    relate "Placed" Orders.customer_id -> Customers.customer_id;
                    """);
            // The referenced (target) relation is the left leaf, the FK owner the right.
            JoinResolution r = resolve(m, join(rel("Customers"), rel("Orders")));
            assertThat(r).isInstanceOf(Resolved.class);
            assertThat(((Resolved) r).program())
                    .contains("Orders.customer_id = Customers.customer_id");
        }

        @Test
        @DisplayName("a leaf order that is not a valid traversal is reordered along the path")
        void reordersNonTraversalLeafOrder() {
            // Leaves in order A, C, B over the chain A—B—C: C is not yet adjacent to
            // {A}, so it is deferred until B joins.
            SemanticModel m = model(CHAIN);
            JoinResolution r = resolve(m, join(join(rel("A"), rel("C")), rel("B")));
            assertThat(r).isInstanceOf(Resolved.class);
            assertThat(((Resolved) r).program())
                    .contains("A.b_ref = B.b_id")
                    .contains("B.c_ref = C.c_id");
        }

        @Test
        @DisplayName("the assembly is preserved under a unary spine (σ over the join)")
        void underUnarySpine() {
            SemanticModel m = model(CHAIN);
            RelNode spine = select(dummy(), join(join(rel("A"), rel("B")), rel("C")));
            JoinResolution r = resolve(m, spine);

            assertThat(r).isInstanceOf(Resolved.class);
            assertThat(((Resolved) r).program())
                    .contains("A.b_ref = B.b_id")
                    .contains("B.c_ref = C.c_id");
        }

        @Test
        @DisplayName("a natural-join region is also resolved to an explicit condition")
        void naturalJoinRegion() {
            SemanticModel m = model("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER } };
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { customer_id: NUMBER } };
                    relate "Placed" Orders.customer_id -> Customers.customer_id;
                    """);
            RelNode natural = naturalJoin(rel("Orders"), rel("Customers"));
            JoinResolution r = resolve(m, natural);
            assertThat(r).isInstanceOf(Resolved.class);
            assertThat(((Resolved) r).program())
                    .contains("Orders.customer_id = Customers.customer_id");
        }
    }

    @Nested
    @DisplayName("bounds facts (recorded, not prompted)")
    class Bounds {

        @Test
        @DisplayName("unconstrained default bounds cannot rule out fan-out or row-drop")
        void defaultsCannotRuleOut() {
            SemanticModel m = model(CHAIN);
            Resolved r = (Resolved) resolve(m, join(join(rel("A"), rel("B")), rel("C")));
            assertThat(r.bounds().fanOut()).isTrue();
            assertThat(r.bounds().rowDrop()).isTrue();
            assertThat(r.bounds().any()).isTrue();
        }

        @Test
        @DisplayName("fully-bounded [1..1] endpoints on both sides rule both out")
        void boundedIsClean() {
            SemanticModel m = model("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER } };
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { customer_id: NUMBER } };
                    relate "Placed" Orders.customer_id [1..1] -> Customers.customer_id [1..1];
                    """);
            Resolved r = (Resolved) resolve(m, join(rel("Orders"), rel("Customers")));
            assertThat(r.bounds().fanOut()).isFalse();
            assertThat(r.bounds().rowDrop()).isFalse();
            assertThat(r.bounds().any()).isFalse();
        }

        @Test
        @DisplayName("a declared max > 1 endpoint is a known fan-out without row-drop")
        void declaredFanOut() {
            SemanticModel m = model("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER } };
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { customer_id: NUMBER } };
                    relate "Placed" Orders.customer_id [1..50] -> Customers.customer_id [1..1];
                    """);
            Resolved r = (Resolved) resolve(m, join(rel("Orders"), rel("Customers")));
            assertThat(r.bounds().fanOut()).isTrue();    // Orders side admits up to 50
            assertThat(r.bounds().rowDrop()).isFalse();  // both mins are >= 1
        }
    }

    @Nested
    @DisplayName("passthrough — the graph adds nothing decidable")
    class Passthroughs {

        @Test
        @DisplayName("empty graph passes through")
        void emptyGraph() {
            SemanticModel m = model("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, customer_id: NUMBER } };
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { customer_id: NUMBER } };
                    """);
            JoinResolution r = resolve(m, join(rel("Orders"), rel("Customers")));
            assertThat(r).isInstanceOf(Passthrough.class);
            assertThat(((Passthrough) r).reason()).contains("no schema graph");
        }

        @Test
        @DisplayName("a single relation (no join) passes through")
        void singleRelation() {
            SemanticModel m = model(TWO_FK);
            JoinResolution r = resolve(m, rel("Issues"));
            assertThat(r).isInstanceOf(Passthrough.class);
        }

        @Test
        @DisplayName("a filter buried inside the join region is not rewritten")
        void buriedFilter() {
            SemanticModel m = model(CHAIN);
            // A ⋈ (σ (B)) ⋈ C — a selection inside the region blocks the structural rewrite.
            RelNode region = join(join(rel("A"), select(dummy(), rel("B"))), rel("C"));
            JoinResolution r = resolve(m, region);
            assertThat(r).isInstanceOf(Passthrough.class);
            assertThat(((Passthrough) r).reason()).contains("pure base-relation join");
        }

        @Test
        @DisplayName("a self-join (same relation twice) is out of scope")
        void selfJoin() {
            SemanticModel m = model(CHAIN);
            JoinResolution r = resolve(m, join(rel("A"), rel("A")));
            assertThat(r).isInstanceOf(Passthrough.class);
            assertThat(((Passthrough) r).reason()).contains("self-join");
        }

        @Test
        @DisplayName("an unresolved relation name passes through")
        void unresolvedRelation() {
            SemanticModel m = model(TWO_FK);
            JoinResolution r = resolve(m, join(rel("Ghost"), rel("Users")));
            assertThat(r).isInstanceOf(Passthrough.class);
            assertThat(((Passthrough) r).reason()).contains("unresolved relation Ghost");
        }

        @Test
        @DisplayName("a unique path that must add an intermediate table is not rewritten")
        void uniquePathIntroducesTable() {
            // Graph A—B—C, but the model joined only A and C: the sole path spans
            // {A,B,C} ≠ {A,C}, so a rewrite would silently add a table.
            SemanticModel m = model(CHAIN);
            JoinResolution r = resolve(m, join(rel("A"), rel("C")));
            assertThat(r).isInstanceOf(Passthrough.class);
            assertThat(((Passthrough) r).reason()).contains("introduces or omits tables");
        }

        @Test
        @DisplayName("ambiguous paths through different intermediates pass through")
        void ambiguousPathsDifferInTables() {
            SemanticModel m = model("""
                    source A from database { url: "${DB}", table: "a",
                        schema: { a_id: NUMBER, b_ref: NUMBER, d_ref: NUMBER } };
                    source B from database { url: "${DB}", table: "b",
                        schema: { b_id: NUMBER, c_ref: NUMBER } };
                    source D from database { url: "${DB}", table: "d",
                        schema: { d_id: NUMBER, c_ref2: NUMBER } };
                    source C from database { url: "${DB}", table: "c",
                        schema: { c_id: NUMBER } };
                    relate "AB" A.b_ref -> B.b_id;
                    relate "BC" B.c_ref -> C.c_id;
                    relate "AD" A.d_ref -> D.d_id;
                    relate "DC" D.c_ref2 -> C.c_id;
                    """);
            JoinResolution r = resolve(m, join(rel("A"), rel("C")));
            assertThat(r).isInstanceOf(Passthrough.class);
            assertThat(((Passthrough) r).reason()).contains("ambiguous paths differ");
        }

        @Test
        @DisplayName("relations with no connecting path pass through")
        void disconnected() {
            SemanticModel m = model("""
                    source A from database { url: "${DB}", table: "a",
                        schema: { a_id: NUMBER } };
                    source B from database { url: "${DB}", table: "b",
                        schema: { b_id: NUMBER } };
                    source C from database { url: "${DB}", table: "c",
                        schema: { c_id: NUMBER, a_ref: NUMBER } };
                    relate "CA" C.a_ref -> A.a_id;
                    """);
            // Join A and B — B is not in the graph, so no path spans {A, B}.
            JoinResolution r = resolve(m, join(rel("A"), rel("B")));
            assertThat(r).isInstanceOf(Passthrough.class);
        }
    }
}

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

import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.graph.EdgeOrigin;
import com.darkcollective.relix.symbol.graph.Relationship;
import com.darkcollective.relix.symbol.graph.SchemaGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JoinObserver — learning schema-graph edges from user-written joins (ADR-0024 §2.4)")
final class JoinObserverTest {

    // ── schema fixtures (no declared relationships unless a test adds them) ──────

    private static final String ORDERS_CUSTOMERS = """
            source Orders from database { url: "${DB}", table: "orders",
                schema: { order_id: NUMBER, customer_id: NUMBER, status: STRING } };
            source Customers from database { url: "${DB}", table: "customers",
                schema: { customer_id: NUMBER, name: STRING } };
            """;

    private static SemanticModel base() {
        return model(ORDERS_CUSTOMERS);
    }

    private static List<Relationship> observe(SemanticModel m, RelNode... trees) {
        return new JoinObserver(m.symbolTable(), m.schemaGraph()).observeTrees(List.of(trees));
    }

    private static Predicate eq(String left, String right) {
        return cmp(
                attr(left), ComparisonOperator.EQUAL, attr(right));
    }

    private static Relationship only(List<Relationship> edges) {
        assertThat(edges).hasSize(1);
        return edges.get(0);
    }

    // =========================================================================
    // Condition-based observation
    // =========================================================================

    @Nested
    @DisplayName("condition-based joins")
    class ConditionBased {

        @Test
        @DisplayName("a θ-join's qualified equality yields one edge between the two relations")
        void thetaJoin() {
            SemanticModel m = base();
            RelNode tree = join(rel("Orders"), rel("Customers"),
                    eq("Orders.customer_id", "Customers.customer_id"));

            Relationship edge = only(observe(m, tree));
            assertThat(edge.origin()).isEqualTo(EdgeOrigin.LEARNED);
            assertThat(edge.source().relation().declaredName()).isEqualTo("Orders");
            assertThat(edge.source().columns()).containsExactly("customer_id");
            assertThat(edge.target().relation().declaredName()).isEqualTo("Customers");
            assertThat(edge.target().columns()).containsExactly("customer_id");
        }

        @Test
        @DisplayName("all seven ConditionalJoinNode forms are observed — the condition is the evidence")
        void everyConditionalJoinForm() {
            SemanticModel m = base();
            Predicate c = eq("Orders.customer_id", "Customers.customer_id");
            RelNode o = rel("Orders");
            RelNode cu = rel("Customers");
            List<RelNode> forms = List.of(
                    join(o, cu, c),
                    leftJoin(o, cu, c),
                    rightJoin(o, cu, c),
                    fullJoin(o, cu, c),
                    semiJoin(o, cu, c),
                    antiJoin(o, cu, c),
                    pairwiseUniversal(o, cu, c));
            for (RelNode form : forms) {
                assertThat(observe(m, form))
                        .as(form.getClass().getSimpleName())
                        .hasSize(1);
            }
        }

        @Test
        @DisplayName("a two-conjunct condition becomes one composite-key edge, paired positionally")
        void compositeKey() {
            SemanticModel m = model("""
                    source Memberships from database { url: "${DB}", table: "memberships",
                        schema: { tenant_id: NUMBER, user_id: NUMBER, role: STRING } };
                    source Users from database { url: "${DB}", table: "users",
                        schema: { tenant_id: NUMBER, user_id: NUMBER, name: STRING } };
                    """);
            Predicate c = and(
                    eq("Memberships.tenant_id", "Users.tenant_id"),
                    eq("Memberships.user_id", "Users.user_id"));
            RelNode tree = join(rel("Memberships"), rel("Users"), c);

            Relationship edge = only(observe(m, tree));
            assertThat(edge.source().columns()).containsExactly("tenant_id", "user_id");
            assertThat(edge.target().columns()).containsExactly("tenant_id", "user_id");
        }

        @Test
        @DisplayName("bare (unqualified) columns are assigned to a side by schema membership")
        void bareColumnsBySchema() {
            SemanticModel m = model("""
                    source Orders from database { url: "${DB}", table: "orders",
                        schema: { order_id: NUMBER, cust_ref: NUMBER } };
                    source Customers from database { url: "${DB}", table: "customers",
                        schema: { customer_id: NUMBER, name: STRING } };
                    """);
            RelNode tree = join(rel("Orders"), rel("Customers"),
                    eq("cust_ref", "customer_id"));

            Relationship edge = only(observe(m, tree));
            assertThat(edge.source().columns()).containsExactly("cust_ref");
            assertThat(edge.target().columns()).containsExactly("customer_id");
        }

        @Test
        @DisplayName("a column shared by both schemas is resolved via its already-pinned partner")
        void ambiguousResolvedByPartner() {
            // customer_id lives in both; order_id pins Orders, so customer_id → Customers.
            SemanticModel m = base();
            RelNode tree = join(rel("Orders"), rel("Customers"),
                    eq("order_id", "customer_id"));

            Relationship edge = only(observe(m, tree));
            assertThat(edge.source().columns()).containsExactly("order_id");
            assertThat(edge.target().columns()).containsExactly("customer_id");
        }

        @Test
        @DisplayName("every orientation of (side, side) that is cross-side is learned the same way")
        void everyCrossSideOrientation() {
            // Six accepted combinations, written as six separate arms: LEFT/RIGHT and
            // RIGHT/LEFT, and AMBIGUOUS paired with either side in either position. The
            // learned edge must come out the same way round however the user wrote the
            // equality — an edge learned backwards would resolve a later :ask join to the
            // wrong pair of columns, which is a wrong answer rather than a missing one.
            // Over Orders(order_id, customer_id, status) ⨝ Customers(customer_id, name):
            // order_id is LEFT-only, name is RIGHT-only, customer_id is in both.
            record Case(String left, String right, String source, String target, String label) { }
            List<Case> cases = List.of(
                    new Case("order_id", "name", "order_id", "name", "LEFT = RIGHT"),
                    new Case("name", "order_id", "order_id", "name", "RIGHT = LEFT"),
                    new Case("customer_id", "order_id", "order_id", "customer_id",
                            "AMBIGUOUS = LEFT"),
                    new Case("customer_id", "name", "customer_id", "name",
                            "AMBIGUOUS = RIGHT"),
                    new Case("order_id", "customer_id", "order_id", "customer_id",
                            "LEFT = AMBIGUOUS"),
                    new Case("name", "customer_id", "customer_id", "name",
                            "RIGHT = AMBIGUOUS"));

            for (Case c : cases) {
                SemanticModel m = base();
                RelNode tree = join(rel("Orders"), rel("Customers"),
                        eq(c.left(), c.right()));
                Relationship edge = only(observe(m, tree));
                assertThat(edge.source().columns())
                        .as("%s — source", c.label()).containsExactly(c.source());
                assertThat(edge.target().columns())
                        .as("%s — target", c.label()).containsExactly(c.target());
            }
        }

        @Test
        @DisplayName("two ambiguous columns pin nothing, so no edge is learned")
        void bothSidesAmbiguousLearnsNothing() {
            // customer_id is in both schemas, so neither operand pins a side and there is
            // no evidence about which way the join runs. Guessing here would invent an
            // edge the user never demonstrated.
            SemanticModel m = base();
            RelNode tree = join(rel("Orders"), rel("Customers"),
                    eq("customer_id", "customer_id"));
            assertThat(observe(m, tree)).isEmpty();
        }

        @Test
        @DisplayName("a right-side-only equality contributes nothing, as a left-side one does")
        void rightSideEqualityLearnsNothing() {
            SemanticModel m = base();
            RelNode tree = join(rel("Orders"), rel("Customers"),
                    eq("Customers.customer_id", "name"));
            assertThat(observe(m, tree)).isEmpty();
        }

        @Test
        @DisplayName("a join whose side is itself a join adds nothing beyond the inner one")
        void aNestedSideAddsNoEdgeOfItsOwn() {
            // Both observation paths reduce each side to ONE leaf relation, and each checks
            // both sides. A side that is itself a join has no single relation to anchor an
            // edge to — so the outer join contributes nothing, while the inner join is
            // still observed on its own as the walk passes through it. The count is the
            // assertion: an outer edge would be a claim about a pair the user never joined.
            SemanticModel m = base();
            Predicate cond = eq("Orders.customer_id", "Customers.customer_id");
            RelNode nested = join(rel("Orders"), rel("Customers"), cond);
            List<Relationship> innerOnly = observe(base(), nested);
            assertThat(innerOnly).as("the inner join alone").hasSize(1);

            assertThat(observe(m, join(nested, rel("Customers"), cond)))
                    .as("nested on the left adds nothing").hasSameSizeAs(innerOnly);
            assertThat(observe(base(), join(rel("Orders"), nested, cond)))
                    .as("nested on the right adds nothing").hasSameSizeAs(innerOnly);
            assertThat(observe(base(), naturalJoin(nested, rel("Customers"))))
                    .as("the shared-column path checks both sides too")
                    .hasSameSizeAs(innerOnly);
            assertThat(observe(base(), naturalJoin(rel("Orders"), nested)))
                    .hasSameSizeAs(innerOnly);
        }

        @Test
        @DisplayName("an equality against a literal is no evidence, on either side")
        void anEqualityAgainstALiteralTeachesNothing() {
            // crossSidePair needs an attribute on BOTH sides; a filter conjoined into the
            // condition is not a join key and must not become a learned edge.
            SemanticModel m = base();
            assertThat(observe(m, join(rel("Orders"), rel("Customers"),
                    cmp(num("1"),
                            ComparisonOperator.EQUAL, attr("Customers.customer_id")))))
                    .as("literal on the left").isEmpty();
            assertThat(observe(m, join(rel("Orders"), rel("Customers"),
                    cmp(attr("Orders.order_id"),
                            ComparisonOperator.EQUAL, num("1")))))
                    .as("literal on the right").isEmpty();
        }

        @Test
        @DisplayName("a relation-only ρ is seen through — the alias is what the edge is named for")
        void aRelationOnlyRenameIsSeenThrough() {
            // ρ V (Orders) relabels the side without changing its columns, so the base
            // relation's names still hold and the join is still evidence. The edge is
            // anchored to the *alias*, because that is what a later reference will use.
            SemanticModel m = base();
            RelNode aliased = rename("V", List.of(), rel("Orders"));
            RelNode tree = join(aliased, rel("Customers"),
                    cmp(attr("V.customer_id"),
                            ComparisonOperator.EQUAL,
                            attr("Customers.customer_id")));

            Relationship edge = only(observe(m, tree));
            assertThat(edge.source().columns()).containsExactly("customer_id");
            assertThat(edge.target().columns()).containsExactly("customer_id");
        }

        @Test
        @DisplayName("a column-renaming ρ is NOT seen through — its column names no longer hold")
        void aColumnRenamingRenameIsOpaque() {
            SemanticModel m = base();
            RelNode renamed = rename("V", List.of("a", "b", "c"), rel("Orders"));
            assertThat(observe(m, join(renamed, rel("Customers"), eq("Orders.customer_id", "Customers.customer_id"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("a literal filter conjunct is ignored — only the cross-side equality is learned")
        void literalFilterIgnored() {
            SemanticModel m = base();
            Predicate c = and(
                    eq("Orders.customer_id", "Customers.customer_id"),
                    cmp(attr("Orders.status"),
                            ComparisonOperator.EQUAL, str("active")));
            RelNode tree = join(rel("Orders"), rel("Customers"), c);

            Relationship edge = only(observe(m, tree));
            assertThat(edge.source().columns()).containsExactly("customer_id");
        }

        @Test
        @DisplayName("a same-side equality contributes nothing (no cross-side evidence)")
        void sameSideEqualityIgnored() {
            SemanticModel m = base();
            RelNode tree = join(rel("Orders"), rel("Customers"),
                    eq("Orders.order_id", "Orders.customer_id"));

            assertThat(observe(m, tree)).isEmpty();
        }

        @Test
        @DisplayName("a non-equi (inequality) condition is not representable and yields nothing")
        void inequalityIgnored() {
            SemanticModel m = base();
            RelNode tree = join(rel("Orders"), rel("Customers"),
                    cmp(attr("Orders.customer_id"),
                            ComparisonOperator.GREATER, attr("Customers.customer_id")));

            assertThat(observe(m, tree)).isEmpty();
        }
    }

    // =========================================================================
    // Selection over cross product — the same assertion, written differently
    // =========================================================================

    @Nested
    @DisplayName("selection over a cross product")
    class SelectionOverProduct {

        @Test
        @DisplayName("σ over × yields the same edge as the equivalent θ-join")
        void sameAsThetaJoin() {
            SemanticModel m = base();
            RelNode theta = join(rel("Orders"), rel("Customers"),
                    eq("Orders.customer_id", "Customers.customer_id"));
            RelNode sigmaOverProduct = select(
                    eq("Orders.customer_id", "Customers.customer_id"),
                    product(rel("Orders"), rel("Customers")));

            Relationship fromTheta = only(observe(m, theta));
            Relationship fromSigma = only(observe(m, sigmaOverProduct));
            assertThat(fromSigma.source().relation().declaredName())
                    .isEqualTo(fromTheta.source().relation().declaredName());
            assertThat(fromSigma.source().columns()).isEqualTo(fromTheta.source().columns());
            assertThat(fromSigma.target().columns()).isEqualTo(fromTheta.target().columns());
        }

        @Test
        @DisplayName("a bare × with no selection carries no evidence")
        void bareProductLearnsNothing() {
            SemanticModel m = base();
            assertThat(observe(m, product(rel("Orders"), rel("Customers")))).isEmpty();
        }
    }

    // =========================================================================
    // AS-OF join — only the equality partition keys
    // =========================================================================

    @Nested
    @DisplayName("as-of join")
    class AsOf {

        @Test
        @DisplayName("only the equality partition key is learned; the ordering inequality is not")
        void partitionKeyOnly() {
            SemanticModel m = model("""
                    source Trades from database { url: "${DB}", table: "trades",
                        schema: { symbol: STRING, at: TIMESTAMP, qty: NUMBER } };
                    source Quotes from database { url: "${DB}", table: "quotes",
                        schema: { symbol: STRING, at: TIMESTAMP, price: NUMBER } };
                    """);
            Predicate condition = and(
                    eq("Trades.symbol", "Quotes.symbol"),
                    cmp(attr("Trades.at"),
                            ComparisonOperator.GREATER_EQUAL, attr("Quotes.at")));
            RelNode tree = asOfJoin(rel("Trades"), rel("Quotes"), condition);

            Relationship edge = only(observe(m, tree));
            assertThat(edge.source().columns()).containsExactly("symbol");
            assertThat(edge.target().columns()).containsExactly("symbol");
        }
    }

    // =========================================================================
    // Natural join / composition — matched columns
    // =========================================================================

    @Nested
    @DisplayName("natural join and composition")
    class MatchedColumns {

        @Test
        @DisplayName("a natural join learns an edge over its shared column names")
        void naturalJoin() {
            SemanticModel m = base();
            Relationship edge = only(observe(m, AstBuilders.naturalJoin(rel("Orders"), rel("Customers"))));
            assertThat(edge.source().columns()).containsExactly("customer_id");
            assertThat(edge.target().columns()).containsExactly("customer_id");
        }

        @Test
        @DisplayName("composition behaves the same as a natural join over shared columns")
        void composition() {
            SemanticModel m = base();
            Relationship edge = only(observe(m, AstBuilders.composition(rel("Orders"), rel("Customers"))));
            assertThat(edge.source().columns()).containsExactly("customer_id");
        }

        @Test
        @DisplayName("disjoint schemas share no columns, so nothing is learned")
        void disjointSchemasLearnNothing() {
            SemanticModel m = model("""
                    source A from database { url: "${DB}", table: "a", schema: { a_id: NUMBER } };
                    source B from database { url: "${DB}", table: "b", schema: { b_id: NUMBER } };
                    """);
            assertThat(observe(m, AstBuilders.naturalJoin(rel("A"), rel("B")))).isEmpty();
        }
    }

    // =========================================================================
    // Endpoint resolution — both sides must bottom out in a relation
    // =========================================================================

    @Nested
    @DisplayName("endpoint resolution")
    class EndpointResolution {

        @Test
        @DisplayName("a join over an aggregated intermediate is unobservable")
        void aggregatedIntermediate() {
            SemanticModel m = base();
            RelNode aggregated = groupBy(List.of("customer_id"), List.of(), rel("Orders"));
            RelNode tree = join(aggregated, rel("Customers"),
                    eq("customer_id", "Customers.customer_id"));

            assertThat(observe(m, tree)).isEmpty();
        }

        @Test
        @DisplayName("a join over a filtered intermediate is unobservable until derived endpoints land")
        void filteredIntermediate() {
            SemanticModel m = base();
            RelNode filtered = select(
                    cmp(attr("status"),
                            ComparisonOperator.EQUAL, str("active")),
                    rel("Orders"));
            RelNode tree = join(filtered, rel("Customers"),
                    eq("customer_id", "Customers.customer_id"));

            assertThat(observe(m, tree)).isEmpty();
        }

        @Test
        @DisplayName("a self-join through relation-only renames yields a self-referential edge")
        void selfJoin() {
            SemanticModel m = model("""
                    source Employees from database { url: "${DB}", table: "employees",
                        schema: { emp_id: NUMBER, manager_id: NUMBER, name: STRING } };
                    """);
            RelNode e = rename("E", List.of(), rel("Employees"));
            RelNode mgr = rename("M", List.of(), rel("Employees"));
            RelNode tree = join(e, mgr, eq("E.manager_id", "M.emp_id"));

            Relationship edge = only(observe(m, tree));
            assertThat(edge.selfReferential()).isTrue();
            assertThat(edge.symmetric()).isFalse();
            assertThat(edge.source().columns()).containsExactly("manager_id");
            assertThat(edge.target().columns()).containsExactly("emp_id");
        }

        @Test
        @DisplayName("a column-renaming rename is not unwrapped (renamed columns would misrecord)")
        void columnRenamingRenameNotUnwrapped() {
            SemanticModel m = base();
            RenameNode renamed = rename("O", List.of("oid", "cid", "st"), rel("Orders"));
            RelNode tree = join(renamed, rel("Customers"),
                    eq("O.cid", "Customers.customer_id"));

            assertThat(observe(m, tree)).isEmpty();
        }
    }

    // =========================================================================
    // Bounds — join type sets the bounds, never the gate
    // =========================================================================

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("an inner join leaves the unconstrained [0..*] default on both endpoints")
        void innerDefaults() {
            SemanticModel m = base();
            Relationship edge = only(observe(m, join(rel("Orders"), rel("Customers"),
                    eq("Orders.customer_id", "Customers.customer_id"))));
            assertThat(edge.source().boundsLabel()).isEqualTo("[0..*]");
            assertThat(edge.target().boundsLabel()).isEqualTo("[0..*]");
        }

        @Test
        @DisplayName("a left outer join carries min = 0 (the outer signal, coinciding with the default)")
        void leftOuterMinZero() {
            SemanticModel m = base();
            Relationship edge = only(observe(m, leftJoin(rel("Orders"), rel("Customers"),
                    eq("Orders.customer_id", "Customers.customer_id"))));
            assertThat(edge.source().min()).isZero();
            assertThat(edge.target().min()).isZero();
            assertThat(edge.source().bounded()).isFalse();
        }
    }

    // =========================================================================
    // De-noising
    // =========================================================================

    @Nested
    @DisplayName("de-noising")
    class DeNoising {

        @Test
        @DisplayName("an edge the graph already declares is not re-proposed, whatever its name")
        void alreadyDeclared() {
            SemanticModel m = model(ORDERS_CUSTOMERS + """
                    relate "Placed" Orders.customer_id -> Customers.customer_id;
                    """);
            RelNode tree = join(rel("Orders"), rel("Customers"),
                    eq("Orders.customer_id", "Customers.customer_id"));

            assertThat(observe(m, tree)).isEmpty();
        }

        @Test
        @DisplayName("the same join written across many trees collapses to one candidate")
        void repeatedObservationsCollapse() {
            SemanticModel m = base();
            RelNode a = join(rel("Orders"), rel("Customers"),
                    eq("Orders.customer_id", "Customers.customer_id"));
            RelNode b = semiJoin(rel("Orders"), rel("Customers"),
                    eq("Orders.customer_id", "Customers.customer_id"));

            assertThat(observe(m, a, b)).hasSize(1);
        }

        @Test
        @DisplayName("a reversed join (Customers ⋈ Orders) is the same edge walked the other way")
        void reversedDuplicateCollapses() {
            SemanticModel m = base();
            RelNode forward = join(rel("Orders"), rel("Customers"),
                    eq("Orders.customer_id", "Customers.customer_id"));
            RelNode reverse = join(rel("Customers"), rel("Orders"),
                    eq("Customers.customer_id", "Orders.customer_id"));

            assertThat(observe(m, forward, reverse)).hasSize(1);
        }
    }

    // =========================================================================
    // Traversal + model-level convenience
    // =========================================================================

    @Nested
    @DisplayName("traversal and model observation")
    class Traversal {

        @Test
        @DisplayName("a join nested beneath a selection is still found (the walk recurses)")
        void nestedUnderSelection() {
            SemanticModel m = base();
            RelNode join = join(rel("Orders"), rel("Customers"),
                    eq("Orders.customer_id", "Customers.customer_id"));
            RelNode tree = select(
                    cmp(attr("status"),
                            ComparisonOperator.EQUAL, str("active")),
                    join);

            assertThat(observe(m, tree)).hasSize(1);
        }

        @Test
        @DisplayName("observe(model) learns from a view body over FK-less sources")
        void observeFromModelView() {
            SemanticModel m = model(ORDERS_CUSTOMERS + """
                    Placed := { Orders ⨝ Orders.customer_id = Customers.customer_id Customers };
                    query Placed;
                    """);

            List<Relationship> edges = JoinObserver.observe(m);
            Relationship edge = only(edges);
            assertThat(edge.origin()).isEqualTo(EdgeOrigin.LEARNED);
            assertThat(edge.source().relation().declaredName()).isEqualTo("Orders");
            assertThat(edge.target().relation().declaredName()).isEqualTo("Customers");
        }

        @Test
        @DisplayName("observe(model) is empty when the only join is already a declared edge")
        void observeFromModelDeNoises() {
            SemanticModel m = model(ORDERS_CUSTOMERS + """
                    relate "Placed" Orders.customer_id -> Customers.customer_id;
                    Joined := { Orders ⨝ Orders.customer_id = Customers.customer_id Customers };
                    query Joined;
                    """);

            assertThat(JoinObserver.observe(m)).isEmpty();
        }
    }

    // =========================================================================
    // Naming
    // =========================================================================

    @Nested
    @DisplayName("naming")
    class Naming {

        @Test
        @DisplayName("a learned edge takes a provisional name of source relation plus its columns")
        void provisionalName() {
            SemanticModel m = base();
            Relationship edge = only(observe(m, join(rel("Orders"), rel("Customers"),
                    eq("Orders.customer_id", "Customers.customer_id"))));
            assertThat(edge.name()).isEqualTo("Orders_customer_id");
        }
    }
}

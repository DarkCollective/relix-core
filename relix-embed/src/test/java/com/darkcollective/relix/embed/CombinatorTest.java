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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.UnionNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static com.darkcollective.relix.ast.Expr.attr;
import static com.darkcollective.relix.ast.Expr.desc;
import static com.darkcollective.relix.ast.Expr.eq;
import static com.darkcollective.relix.ast.Expr.gt;
import static com.darkcollective.relix.ast.Expr.num;
import static com.darkcollective.relix.ast.Expr.str;

@DisplayName("Relation — the combinators")
final class CombinatorTest {

    private static final String SCHEMA = """
            source Orders from csv("./orders.csv") {
                header: true,
                schema: { id: NUMBER, customer_id: NUMBER, status: STRING, amount: NUMBER }
            };
            source Archived from csv("./archived.csv") {
                header: true,
                schema: { id: NUMBER, customer_id: NUMBER, status: STRING, amount: NUMBER }
            };
            """;

    private static Relix session() {
        Relix relix = Relix.open();
        relix.define(SCHEMA);
        return relix;
    }

    @Nested
    @DisplayName("composition")
    final class Composition {

        @Test
        @DisplayName("each combinator wraps the receiver, so chaining nests outward")
        void chainingNestsOutward() {
            try (Relix relix = session()) {
                Relation chained = relix.relation("Orders")
                        .select(eq(attr("status"), str("OPEN")))
                        .sort(desc("amount"))
                        .limit(10);

                assertThat(chained.node()).isNode(LimitNode.class)
                        .input().isNode(SortNode.class)
                        .input().isNode(SelectionNode.class)
                        .input().isRelation("Orders");
            }
        }

        @Test
        @DisplayName("the composed tree is the one the text form parses to")
        void matchesTheTextForm() {
            try (Relix relix = session()) {
                Relation built = relix.relation("Orders").select(gt(attr("amount"), num(100L)));
                Relation parsed = relix.relation("σ amount > 100 (Orders)");

                assertThat(built.node()).isEquivalentTo(parsed.node());
            }
        }

        @Test
        @DisplayName("a combinator does not mutate its receiver")
        void receiverIsUnchanged() {
            try (Relix relix = session()) {
                Relation orders = relix.relation("Orders");
                orders.distinct().limit(5);

                assertThat(orders.node()).isRelation("Orders");
            }
        }

        @Test
        @DisplayName("the composed relation carries a schema for its own new node")
        void composedNodeIsAnnotated() {
            try (Relix relix = session()) {
                Relation composed = relix.relation("Orders").distinct();

                // Composition extends the expression, so the annotations have to be
                // extended too — otherwise every terminal above would ask for a schema
                // that was never inferred.
                assertThat(composed.model().nodeSchemas().get(composed.node())).isPresent();
            }
        }

        @Test
        @DisplayName("composition keeps the environment, so the symbol table is unchanged")
        void environmentIsUnchanged() {
            try (Relix relix = session()) {
                Relation orders = relix.relation("Orders");
                Relation composed = orders.distinct();

                assertThat(composed.model().symbolTable()).isSameAs(orders.model().symbolTable());
            }
        }
    }

    @Nested
    @DisplayName("binary operators take a Relation, not a tree")
    final class Binary {

        @Test
        @DisplayName("union composes two relations of the session")
        void union() {
            try (Relix relix = session()) {
                Relation both = relix.relation("Orders").union(relix.relation("Archived"));

                assertThat(both.node()).isNode(UnionNode.class)
                        .left().isRelation("Orders");
                assertThat(both.node()).isNode(UnionNode.class)
                        .right().isRelation("Archived");
            }
        }

        @Test
        @DisplayName("a null right-hand side is rejected at the call, not deep inside")
        void nullRightIsRejected() {
            try (Relix relix = session()) {
                org.assertj.core.api.Assertions
                        .assertThatThrownBy(() -> relix.relation("Orders").union(null))
                        .isInstanceOf(NullPointerException.class)
                        .hasMessageContaining("relation");
            }
        }
    }

    @Nested
    @DisplayName("the names are the reference manual's")
    final class Naming {

        @Test
        @DisplayName("γ is one call, because γ is one node")
        void aggregateIsOneCall() {
            try (Relix relix = session()) {
                // Not .groupBy(k).agg(f): that is two calls because SQL has two clauses,
                // and it would be a wrong decomposition rather than a renaming.
                Relation totals = relix.relation("Orders").aggregate(
                        List.of("customer_id"),
                        List.of(AstBuilders.agg(AggregateOperator.SUM, "amount")));

                assertThat(totals.node())
                        .isEquivalentTo(relix.relation(
                                "γ customer_id, SUM(amount) (Orders)").node());
            }
        }

        @Test
        @DisplayName("λ is limit, because that is the operator's name — and it wraps")
        void limitWraps() {
            try (Relix relix = session()) {
                // λ over λ is well-defined and the tighter bound wins; rewriting an
                // existing λ would be smoothing over the algebra.
                Relation twice = relix.relation("Orders").limit(10).limit(5);

                assertThat(twice.node()).isNode(LimitNode.class)
                        .input().isNode(LimitNode.class);
            }
        }

        @Test
        @DisplayName("optimize is the solver operator; staging the rewriter is not this method")
        void optimizeIsTheOperator() {
            try (Relix relix = session()) {
                Relation chosen = relix.relation("Orders").optimize(
                        com.darkcollective.relix.ast.ObjectiveSense.MAXIMIZE,
                        attr("amount"),
                        List.of(AstBuilders.constraint(attr("amount"),
                                com.darkcollective.relix.ast.ComparisonOperator.LESS_EQUAL, 100d)),
                        List.of());

                assertThat(chosen.node())
                        .isNode(com.darkcollective.relix.ast.OptimizeNode.class);
            }
        }

        @Test
        @DisplayName("distinct is δ, and δ over δ is still two nodes")
        void distinctWraps() {
            try (Relix relix = session()) {
                assertThat(relix.relation("Orders").distinct().distinct().node())
                        .isNode(DistinctNode.class)
                        .input().isNode(DistinctNode.class);
            }
        }
    }
}

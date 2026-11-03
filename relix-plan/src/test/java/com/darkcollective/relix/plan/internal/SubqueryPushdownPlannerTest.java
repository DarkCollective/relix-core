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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.plan.PlanAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;

/**
 * The joins a subquery is written as, folded into one statement for a database (#261):
 * a semi or anti join as {@code [NOT] EXISTS}, a left outer join as {@code LEFT JOIN … ON},
 * and the first related row per left row as the dialect's nearest-row lateral.
 */
@DisplayName("Subquery joins — SQL pushdown")
final class SubqueryPushdownPlannerTest {

    private static String tables(String url) {
        return "connection db from database { url: \"" + url + "\" };\n"
                + "source Customers from db { table: \"customers\", schema: { id: NUMBER, name: STRING } };\n"
                + "source Orders from db { table: \"orders\", schema: { oid: NUMBER, cid: NUMBER, amount: NUMBER } };\n";
    }

    private static final String EXTRA = """
            Local := [| id | tag |
                      | 1  | a   |];
            def twice(x: NUMBER): NUMBER := { x * 2 };
            """;

    private static PhysicalNode plan(String url, String query) {
        SemanticModel m = model(tables(url) + EXTRA + "query { " + query + " };");
        RelNode logical = ((ExpressionQueryTarget) m.rootQueries().get(0).target()).expression();
        return new Planner(m.symbolTable(), m.nodeSchemas(), m.statistics(), m.sources(),
                m.connections(), QueryEventListener.NONE, BoundednessSource.ALL_BOUNDED, m.functions()).plan(logical);
    }

    private static String sql(String url, String query) {
        PhysicalNode plan = plan(url, query);
        assertThat(plan).isNode(PhysicalNode.PushedScan.class);
        return ((PhysicalNode.PushedScan) plan).nativeQuery();
    }

    /** The query is not folded into one statement for the database. */
    private static void notFolded(String query) {
        PhysicalNode plan = plan("jdbc:postgresql://h/db", query);
        org.assertj.core.api.Assertions.assertThat(plan).as(query).isNotInstanceOf(PhysicalNode.PushedScan.class);
    }

    private static final String TOP_ORDER = "Customers OUTER LATERAL (λ 1 (τ amount DESC"
            + " (σ cid = Customers.id ∧ amount > 10 (Orders))))";

    @Test
    @DisplayName("a semi join is EXISTS, an anti join NOT EXISTS, a σ on each side kept on its side")
    void existsJoins() {
        org.assertj.core.api.Assertions.assertThat(sql("jdbc:postgresql://h/db",
                "σ name ≠ 'x' (Customers) ⋉ Orders.cid = Customers.id σ amount > 5 (Orders)"))
                .endsWith("FROM \"customers\" \"Customers\" WHERE (\"Customers\".\"name\" <> 'x') AND EXISTS"
                        + " (SELECT 1 FROM \"orders\" \"Orders\" WHERE (\"Orders\".\"cid\" = \"Customers\".\"id\")"
                        + " AND (\"Orders\".\"amount\" > 5))");
        org.assertj.core.api.Assertions.assertThat(sql("jdbc:postgresql://h/db",
                "Customers ▷ Orders.cid = Customers.id Orders")).contains("NOT EXISTS (SELECT 1 FROM");
    }

    @Test
    @DisplayName("a left outer join keeps a right σ in its ON and a left σ in the WHERE")
    void leftJoin() {
        org.assertj.core.api.Assertions.assertThat(sql("jdbc:postgresql://h/db",
                "σ name ≠ 'x' (Customers) ⟕ Orders.cid = Customers.id σ amount > 5 (Orders)"))
                .endsWith("LEFT JOIN \"orders\" \"Orders\" ON (\"Orders\".\"cid\" = \"Customers\".\"id\")"
                        + " AND (\"Orders\".\"amount\" > 5) WHERE (\"Customers\".\"name\" <> 'x')");
    }

    @Test
    @DisplayName("the first related row is the nearest-row lateral, NULLs ordered last as the engine orders them")
    void topRowLateral() {
        org.assertj.core.api.Assertions.assertThat(sql("jdbc:postgresql://h/db", TOP_ORDER))
                .contains("LEFT JOIN LATERAL (SELECT").contains("LIMIT 1)").contains("ON TRUE")
                .contains("\"Orders\".\"cid\" = \"Customers\".\"id\"");
        org.assertj.core.api.Assertions.assertThat(sql("jdbc:sqlserver://h;databaseName=db", TOP_ORDER))
                .contains("OUTER APPLY (SELECT TOP 1");
        org.assertj.core.api.Assertions.assertThat(sql("jdbc:postgresql://h/db",
                TOP_ORDER.replace("OUTER LATERAL", "LATERAL").replace(" ∧ amount > 10", "")))
                .contains(" JOIN LATERAL (SELECT").doesNotContain("LEFT JOIN");
    }

    @Test
    @DisplayName("each fold declines a side that is not a table, or a condition or filter the database cannot spell")
    void declines() {
        notFolded("Customers ⋉ Local.id = Customers.id Local");
        notFolded("Local ⋉ Local.id = Orders.cid Orders");
        notFolded("Customers ⋉ Customers.id > 1 Customers");
        notFolded("Customers ⋉ Orders.cid = twice(Customers.id) Orders");
        notFolded("σ twice(id) = 2 (Customers) ⋉ Orders.cid = Customers.id Orders");
        notFolded("Customers ⋉ Orders.cid = Customers.id σ twice(amount) = 2 (Orders)");
        notFolded("Customers ⟕ Local.id = Customers.id Local");
        notFolded("Customers ⟕ Orders.cid = twice(Customers.id) Orders");
        notFolded("σ twice(id) = 2 (Customers) ⟕ Orders.cid = Customers.id Orders");
        notFolded("Customers ⟕ Orders.cid = Customers.id σ twice(amount) = 2 (Orders)");
    }

    @Test
    @DisplayName("a lateral folds only as the first row of an ordered, filtered table, beside a bare left table")
    void lateralDeclines() {
        notFolded("Customers OUTER LATERAL (σ cid = Customers.id (Orders))");
        notFolded("Customers OUTER LATERAL (λ 2 (τ amount (σ cid = Customers.id (Orders))))");
        notFolded("Customers OUTER LATERAL (LIMIT 1 OFFSET 1 (τ amount (σ cid = Customers.id (Orders))))");
        notFolded("Customers OUTER LATERAL (λ 1 (σ cid = Customers.id (Orders)))");
        notFolded("σ name ≠ 'x' (Customers) OUTER LATERAL (λ 1 (τ amount (σ cid = Customers.id (Orders))))");
        notFolded("Customers OUTER LATERAL (λ 1 (τ amount (σ cid = twice(Customers.id) (Orders))))");
        notFolded("Customers OUTER LATERAL (λ 1 (τ twice(amount) (σ cid = Customers.id (Orders))))");
        notFolded("Customers OUTER LATERAL (λ 1 (τ amount (Local)))");
    }
}

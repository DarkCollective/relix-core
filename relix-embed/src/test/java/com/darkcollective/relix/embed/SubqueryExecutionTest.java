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

import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Subqueries as values, EXISTS, IN and ANY/ALL over a relation, and LATERAL over an
 * expression (#261), each held to H2's answer for the SQL it stands for, over rows with
 * NULLs: a customer with no orders, an order with no customer and a customer with no id.
 */
@DisplayName("subqueries, evaluated")
final class SubqueryExecutionTest {

    private static final String DATA = """
            Customers := [| id   | name  |
                          | 1    | Alice |
                          | 2    | Bob   |
                          | 3    | Cara  |
                          | NULL | Dan   |];
            Orders := [| oid | cid  | amount |
                       | 10  | 1    | 120    |
                       | 11  | 1    | 80     |
                       | 12  | 2    | 45     |
                       | 13  | NULL | 60     |
                       | 14  | 4    | NULL   |];
            """;

    private static final String URL = "jdbc:h2:mem:embed_subqueries;DB_CLOSE_DELAY=-1";

    static {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE customers (id INT, name VARCHAR(10))");
            s.execute("INSERT INTO customers VALUES (1, 'Alice'), (2, 'Bob'), (3, 'Cara'), (NULL, 'Dan')");
            s.execute("CREATE TABLE orders (oid INT, cid INT, amount INT)");
            s.execute("INSERT INTO orders VALUES (10, 1, 120), (11, 1, 80), (12, 2, 45), (13, NULL, 60),"
                    + " (14, 4, NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> engine(String query) {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            List<String> rows = new ArrayList<>();
            relix.relation(query).toList().forEach(t -> {
                List<String> cells = new ArrayList<>();
                t.schema().columns().forEach(c -> {
                    Value v = t.get(c.name());
                    cells.add(v.isNull() ? "NULL" : v.asDisplayString());
                });
                rows.add(String.join("|", cells));
            });
            return rows.stream().sorted().toList();
        }
    }

    private static List<String> h2(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            int width = r.getMetaData().getColumnCount();
            while (r.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= width; i++) {
                    Object v = r.getObject(i);
                    cells.add(v == null ? "NULL" : v.toString());
                }
                rows.add(String.join("|", cells));
            }
        }
        return rows.stream().sorted().toList();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '#', textBlock = """
            π name, (γ COUNT(*) → n (σ cid = Customers.id (Orders))) → n (Customers) \
              # SELECT name, (SELECT COUNT(*) FROM orders o WHERE o.cid = c.id) FROM customers c
            π name, (γ MAX(amount) → m (σ cid = Customers.id (Orders))) → m (Customers) \
              # SELECT name, (SELECT MAX(amount) FROM orders o WHERE o.cid = c.id) FROM customers c
            π name, (π amount (λ 1 (τ amount DESC (σ cid = Customers.id (Orders))))) → top (Customers) \
              # SELECT name, (SELECT amount FROM orders o WHERE o.cid = c.id ORDER BY amount DESC NULLS LAST \
                LIMIT 1) FROM customers c
            π name, (γ COUNT(*) → n (σ cid = Customers.id (Orders))) + 1 → n (Customers) \
              # SELECT name, (SELECT COUNT(*) FROM orders o WHERE o.cid = c.id) + 1 FROM customers c
            π name (σ id = (γ MIN(cid) → m (Orders)) (Customers)) \
              # SELECT name FROM customers WHERE id = (SELECT MIN(cid) FROM orders)
            π name (σ EXISTS (σ cid = Customers.id (Orders)) (Customers)) \
              # SELECT name FROM customers c WHERE EXISTS (SELECT 1 FROM orders o WHERE o.cid = c.id)
            π name (σ NOT EXISTS (σ cid = Customers.id (Orders)) (Customers)) \
              # SELECT name FROM customers c WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.cid = c.id)
            π name (σ EXISTS (σ cid = Customers.id (Orders)) OR name = 'Dan' (Customers)) \
              # SELECT name FROM customers c WHERE EXISTS (SELECT 1 FROM orders o WHERE o.cid = c.id) \
                OR name = 'Dan'
            π name, EXISTS (σ cid = Customers.id (Orders)) → has (Customers) \
              # SELECT name, EXISTS (SELECT 1 FROM orders o WHERE o.cid = c.id) FROM customers c
            π name (σ id IN (π cid (Orders)) (Customers)) \
              # SELECT name FROM customers WHERE id IN (SELECT cid FROM orders)
            π name (σ id NOT IN (π cid (Orders)) (Customers)) \
              # SELECT name FROM customers WHERE id NOT IN (SELECT cid FROM orders)
            π name (σ id NOT IN (π cid (σ cid IS NOT NULL (Orders))) (Customers)) \
              # SELECT name FROM customers WHERE id NOT IN (SELECT cid FROM orders WHERE cid IS NOT NULL)
            π name (σ id < ANY (π cid (Orders)) (Customers)) \
              # SELECT name FROM customers WHERE id < ANY (SELECT cid FROM orders)
            π name (σ id >= ALL (π cid (σ cid IS NOT NULL (Orders))) (Customers)) \
              # SELECT name FROM customers WHERE id >= ALL (SELECT cid FROM orders WHERE cid IS NOT NULL)
            π name (σ id = ANY (π cid (σ cid > 100 (Orders))) (Customers)) \
              # SELECT name FROM customers WHERE id = ANY (SELECT cid FROM orders WHERE cid > 100)
            π name (σ id > ALL (π cid (σ cid > 100 (Orders))) (Customers)) \
              # SELECT name FROM customers WHERE id > ALL (SELECT cid FROM orders WHERE cid > 100)
            π name (σ NOT (id < ALL (π cid (Orders))) (Customers)) \
              # SELECT name FROM customers WHERE NOT (id < ALL (SELECT cid FROM orders))
            π name, oid, amount (Customers OUTER LATERAL (σ cid = Customers.id (Orders))) \
              # SELECT name, oid, amount FROM customers c LEFT JOIN orders o ON o.cid = c.id
            π name, oid (Customers LATERAL (σ cid = Customers.id (Orders))) \
              # SELECT name, oid FROM customers c JOIN orders o ON o.cid = c.id
            π name, oid (Customers LATERAL (λ 1 (τ amount DESC (σ cid = Customers.id (Orders))))) \
              # SELECT name, oid FROM customers c JOIN orders o ON o.cid = c.id WHERE o.amount = \
                (SELECT MAX(amount) FROM orders p WHERE p.cid = c.id)
            π name, amount (σ amount = (γ MAX(amount) → m (σ cid = Orders.cid (ρ O (Orders)))) (Orders \
                ⨝ Orders.cid = Customers.id Customers)) \
              # SELECT name, amount FROM orders o JOIN customers c ON o.cid = c.id WHERE o.amount = \
                (SELECT MAX(amount) FROM orders p WHERE p.cid = o.cid)
            π name (σ EXISTS (σ EXISTS (σ cid = Customers.id ∧ oid ≠ Orders.oid (ρ O2 (Orders))) (Orders)) \
                (Customers)) \
              # SELECT name FROM customers c WHERE EXISTS (SELECT 1 FROM orders o WHERE EXISTS (SELECT 1 FROM \
                orders o2 WHERE o2.cid = c.id AND o2.oid <> o.oid))
            """)
    void agreesWithTheDatabase(String relix, String sql) throws SQLException {
        assertThat(engine(relix)).containsExactlyElementsOf(h2(sql));
    }

    @Test
    @DisplayName("the issue's query gives a customer with no orders 0, as PostgreSQL does")
    void issueQuery() {
        assertThat(engine("π name, (γ COUNT(*) → n (σ cid = Customers.id (Orders))) → order_count (Customers)"))
                .containsExactly("Alice|2", "Bob|1", "Cara|0", "Dan|0");
    }

    @Test
    @DisplayName("the issue's query plans as a left join onto the groups, with no lateral left")
    void issueQueryPlansAsAJoin() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            EmbedAssertions.assertThat(relix.relation(
                    "π name, (γ COUNT(*) → n (σ cid = Customers.id (Orders))) → order_count (Customers)").optimized())
                    .rewrote(com.darkcollective.relix.optimizer.OptimizationCode.SUBQ_005)
                    .explains().doesNotContain("LateralJoin");
        }
    }

    @Test
    @DisplayName("a value whose subquery returns two rows is an error naming it and the row it read")
    void moreThanOneRow() {
        assertThatThrownBy(() -> engine("π name, (π oid (σ cid = Customers.id (Orders))) → oid (Customers)"))
                .hasMessageStartingWith("A subquery used as a value at line 1, column ")
                .hasMessageContaining("returned more than one row for Customers.id = 1; a value is one row's:"
                        + " aggregate with GROUP, or keep one row with LIMIT 1");
    }

    @Test
    @DisplayName("a value with no row is NULL, and an outer NULL matches nothing")
    void noRowIsNull() {
        assertThat(engine("π name, (π oid (σ cid = Customers.id ∧ amount > 100 (Orders))) → big (Customers)"))
                .containsExactly("Alice|10", "Bob|NULL", "Cara|NULL", "Dan|NULL");
    }

    @Test
    @DisplayName("a subquery is lowered wherever it sits in a column's expression")
    void insideOtherOperands() {
        assertThat(engine("π name, -(γ COUNT(*) → n (σ cid = Customers.id (Orders))) → neg,"
                + " [(γ COUNT(*) → n (σ cid = Customers.id (Orders)))] → arr,"
                + " { c: (γ COUNT(*) → n (σ cid = Customers.id (Orders))) } → rec (σ name = 'Alice' (Customers))"))
                .singleElement().asString().startsWith("Alice|-2|");
    }

    @Test
    @DisplayName("a lateral expression and a value inside a function's body read the function's row")
    void insideAFunction() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA + """
                    def counted(min: NUMBER): RELATION := {
                        π name, (γ COUNT(*) → n (σ cid = Customers.id ∧ amount >= min (Orders))) → n (Customers)
                    };
                    """);
            EmbedAssertions.assertThat(relix.relation("τ name (counted(80))")).tuples()
                    .extracting(t -> t.get("name").asDisplayString() + "|" + t.get("n").asDisplayString())
                    .containsExactly("Alice|2", "Bob|0", "Cara|0", "Dan|0");
        }
    }
}

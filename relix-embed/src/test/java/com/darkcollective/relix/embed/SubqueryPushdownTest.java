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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subqueries over a database's tables (#261): EXISTS, NOT EXISTS, IN, NOT IN, ANY, ALL
 * and a correlated LATERAL, once written as joins, are sent to the database whole, and
 * answer as the database does for the SQL they stand for, over rows with NULLs.
 */
@DisplayName("subqueries, pushed down")
final class SubqueryPushdownTest {

    private static final H2 DB = seeded();

    private static H2 seeded() {
        H2 h2 = new H2("jdbc:h2:mem:embed_subquery_pushdown;DB_CLOSE_DELAY=-1");
        try (Connection c = h2.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE customers (id INT, name VARCHAR(10))");
            s.execute("INSERT INTO customers VALUES (1, 'Alice'), (2, 'Bob'), (3, 'Cara'), (NULL, 'Dan')");
            s.execute("CREATE TABLE orders (oid INT, cid INT, amount INT)");
            s.execute("INSERT INTO orders VALUES (10, 1, 120), (11, 1, 80), (12, 2, 45), (13, NULL, 60),"
                    + " (14, 4, NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return h2;
    }

    private static List<String> h2(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = DB.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
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

    /** Each row: the Relix query over db's tables, what its plan sends, and the SQL H2 checks it against. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '#', textBlock = """
            π NAME (σ EXISTS (σ CID = db.CUSTOMERS.ID (db.ORDERS)) (db.CUSTOMERS)) # EXISTS (SELECT 1 \
              # SELECT name FROM customers c WHERE EXISTS (SELECT 1 FROM orders o WHERE o.cid = c.id)
            π NAME (σ NOT EXISTS (σ CID = db.CUSTOMERS.ID ∧ AMOUNT > 50 (db.ORDERS)) (db.CUSTOMERS)) # NOT EXISTS \
              # SELECT name FROM customers c WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.cid = c.id \
                AND o.amount > 50)
            π NAME (σ ID IN (π CID (db.ORDERS)) (db.CUSTOMERS)) # EXISTS (SELECT 1 \
              # SELECT name FROM customers WHERE id IN (SELECT cid FROM orders)
            π NAME (σ ID NOT IN (π CID (db.ORDERS)) (db.CUSTOMERS)) # NOT EXISTS \
              # SELECT name FROM customers WHERE id NOT IN (SELECT cid FROM orders)
            π NAME (σ ID >= ALL (π CID (σ CID IS NOT NULL (db.ORDERS))) (db.CUSTOMERS)) # NOT EXISTS \
              # SELECT name FROM customers WHERE id >= ALL (SELECT cid FROM orders WHERE cid IS NOT NULL)
            π NAME, OID (db.CUSTOMERS OUTER LATERAL (σ CID = db.CUSTOMERS.ID ∧ AMOUNT > 50 (db.ORDERS))) # LEFT JOIN \
              # SELECT name, oid FROM customers c LEFT JOIN orders o ON o.cid = c.id AND o.amount > 50
            """)
    void sendsTheJoinAndAgrees(String relix, String sent, String sql) throws SQLException {
        try (Relix pushed = Relix.builder().jdbc("db", DB).build()) {
            Relation relation = pushed.relation(relix);
            EmbedAssertions.assertThat(relation.optimized()).explains().contains("PushedScan").contains(sent.strip());
            List<String> rows = new ArrayList<>();
            relation.toList().forEach(t -> {
                List<String> cells = new ArrayList<>();
                t.schema().columns().forEach(c -> {
                    Value v = t.get(c.name());
                    cells.add(v.isNull() ? "NULL" : v.asDisplayString());
                });
                rows.add(String.join("|", cells));
            });
            assertThat(rows.stream().sorted().toList()).containsExactlyElementsOf(h2(sql));
        }
    }

    /**
     * The tree as written runs too. Pushing a subquery reads the schema annotation of the
     * nodes inside the predicate, and only the rewritten tree used to carry them: a caller
     * that skips the optimizer was told "No schema annotation for AggregationNode".
     */
    @ParameterizedTest(name = "as written: {0}")
    @CsvSource(delimiter = '#', textBlock = """
            π NAME (σ ID IN (π CID (db.ORDERS)) (db.CUSTOMERS))
            π NAME (σ ID NOT IN (π CID (db.ORDERS)) (db.CUSTOMERS))
            π NAME (σ ID = ANY (π CID (db.ORDERS)) (db.CUSTOMERS))
            π NAME (σ ID >= ALL (π CID (σ CID IS NOT NULL (db.ORDERS))) (db.CUSTOMERS))
            π NAME (σ EXISTS (σ CID = db.CUSTOMERS.ID (db.ORDERS)) (db.CUSTOMERS))
            π NAME (σ NOT EXISTS (σ CID = db.CUSTOMERS.ID ∧ AMOUNT > 50 (db.ORDERS)) (db.CUSTOMERS))
            """)
    void asWrittenRunsAndAgrees(String relix) {
        try (Relix pushed = Relix.builder().jdbc("db", DB).build()) {
            Relation relation = pushed.relation(relix);
            assertThat(rows(relation.asWritten())).containsExactlyInAnyOrderElementsOf(rows(relation));
        }
    }

    private static List<String> rows(Relation relation) {
        List<String> rows = new ArrayList<>();
        relation.toList().forEach(t -> {
            List<String> cells = new ArrayList<>();
            t.schema().columns().forEach(c -> {
                Value v = t.get(c.name());
                cells.add(v.isNull() ? "NULL" : v.asDisplayString());
            });
            rows.add(String.join("|", cells));
        });
        return rows;
    }

    /** A minimal {@link DataSource} over {@code DriverManager}. */
    private record H2(String url) implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }
        @Override public Connection getConnection(String u, String p) throws SQLException {
            return getConnection();
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}

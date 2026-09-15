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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static com.darkcollective.relix.ast.Expr.attr;
import static com.darkcollective.relix.ast.Expr.eq;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;

/**
 * Composing two relations whose analyses are not the same one.
 *
 * <p>A relation pins the model it was built against, which is what makes it a value. That
 * rule governs its own subtree; the operand it is combined with brought its own, and the
 * composition is a request for both. Two things put a name in one model and not the other
 * — a dotted reference, which introspects its table when the relation is built, and a
 * declaration the session made after one side was already pinned — and each is a case
 * here.
 */
@DisplayName("Relation — composing across two models")
final class ComposedModelTest {

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

    private static H2 warehouse(String name) throws SQLException {
        H2 ds = new H2("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("""
                    DROP TABLE IF EXISTS orders;
                    CREATE TABLE orders (order_id INT, status VARCHAR(16));
                    INSERT INTO orders VALUES (1, 'OPEN'), (2, 'SHIPPED'), (3, 'OPEN');
                    """);
        }
        return ds;
    }

    private static H2 archive(String name) throws SQLException {
        H2 ds = new H2("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("""
                    DROP TABLE IF EXISTS closed;
                    CREATE TABLE closed (order_id INT, closed_on VARCHAR(10));
                    INSERT INTO closed VALUES (2, '2026-01-04');
                    """);
        }
        return ds;
    }

    @Nested
    @DisplayName("a name only the other operand's model carries")
    final class NamesFromBothSides {

        @Test
        @DisplayName("a two-connection join composes to what the one-expression form returns")
        void twoConnectionJoin() throws SQLException {
            try (Relix relix = Relix.builder()
                    .jdbc("warehouse", warehouse("compose_wh_a"))
                    .jdbc("archive", archive("compose_ar_a"))
                    .build()) {

                // Each side introspects its own table as it is built, so each symbol lands
                // in that relation's model and nowhere else. Composed, both are in scope.
                List<Tuple> composed = relix.relation("ρ O (warehouse.orders)")
                        .join(relix.relation("ρ C (archive.closed)"),
                                eq(attr("O.ORDER_ID"), attr("C.ORDER_ID")))
                        .project("ORDER_ID", "STATUS", "CLOSED_ON")
                        .toList();

                List<Tuple> written = relix.relation("""
                        π ORDER_ID, STATUS, CLOSED_ON (
                            (ρ O (warehouse.orders)) ⨝ O.ORDER_ID = C.ORDER_ID (ρ C (archive.closed)))
                        """).toList();

                assertThat(composed).isEqualTo(written);
                assertThat(composed).singleElement().satisfies(row -> {
                    assertThat(row.longValue("ORDER_ID")).isEqualTo(2L);
                    assertThat(row.string("CLOSED_ON")).isEqualTo("2026-01-04");
                });
            }
        }

        @Test
        @DisplayName("the composed relation has a heading, so it can be inspected too")
        void composedRelationHasASchema() throws SQLException {
            try (Relix relix = Relix.builder()
                    .jdbc("warehouse", warehouse("compose_wh_b"))
                    .jdbc("archive", archive("compose_ar_b"))
                    .build()) {

                Relation joined = relix.relation("ρ O (warehouse.orders)")
                        .join(relix.relation("ρ C (archive.closed)"),
                                eq(attr("O.ORDER_ID"), attr("C.ORDER_ID")));

                assertThat(joined).schema().columnNamesAssert()
                        .contains("ORDER_ID", "STATUS", "CLOSED_ON");
                assertThat(joined).explains().contains("archive");
            }
        }

        @Test
        @DisplayName("a relation declared after the left was pinned is still in scope")
        void declaredAfterTheLeftWasPinned() {
            try (Relix relix = Relix.open()) {
                relix.table("Orders", List.of("id", "region_id"), List.of(
                        Map.of("id", "1", "region_id", "10"),
                        Map.of("id", "2", "region_id", "20")));
                Relation orders = relix.relation("Orders");     // pinned here

                relix.table("Regions", List.of("region_id", "name"),
                        List.of(Map.of("region_id", "10", "name", "North")));

                // The right operand was analysed against a newer model that does resolve
                // Regions; that model is what the composition needs, and it is not thrown
                // away by the left's having been pinned first.
                assertThat(orders.join(relix.relation("Regions"),
                                eq(attr("Orders.region_id"), attr("Regions.region_id")))
                        .project("id", "name"))
                        .tuples()
                        .singleElement()
                        .satisfies(row -> assertThat(row.string("name")).isEqualTo("North"));
            }
        }

        @Test
        @DisplayName("a function declared after the left was pinned is in scope too")
        void functionDeclaredAfterTheLeftWasPinned() {
            try (Relix relix = Relix.open()) {
                relix.table("Left", List.of(Map.of("id", "1")));
                Relation left = relix.relation("Left");

                relix.table("Right", List.of(Map.of("id", "2")));
                relix.define("def doubled(n: NUMBER) : NUMBER := { n * 2 };");

                // Function symbols merge on the same terms relations do: the right
                // operand resolved the call, and the composition keeps what resolved it.
                assertThat(left.union(relix.relation("π doubled(id) → id (Right)")))
                        .tuples()
                        .extracting(row -> row.longValue("id"))
                        .containsExactlyInAnyOrder(1L, 4L);
            }
        }

        @Test
        @DisplayName("a set operation too — the failure there named an internal invariant")
        void unionAcrossTwoModels() {
            try (Relix relix = Relix.open()) {
                relix.table("Open", List.of(Map.of("id", "1")));
                Relation open = relix.relation("Open");

                relix.table("Closed", List.of(Map.of("id", "2")));

                assertThat(open.union(relix.relation("Closed")))
                        .tuples()
                        .extracting(row -> row.longValue("id"))
                        .containsExactlyInAnyOrder(1L, 2L);
            }
        }
    }

    @Nested
    @DisplayName("what cannot be composed")
    final class Refusals {

        @Test
        @DisplayName("a name bound to a different relation on each side is refused, naming it")
        void conflictingNameIsRefused() {
            try (Relix relix = Relix.open()) {
                relix.table("Regions", List.of(Map.of("id", "1"), Map.of("id", "2")));
                relix.define("Chosen := { σ id = '1' (Regions) };");
                Relation first = relix.relation("Chosen");

                relix.define("Chosen := { σ id = '2' (Regions) };");
                Relation second = relix.relation("Chosen");

                // Which side wins is not a question the caller asked, and either answer
                // silently discards half of what they wrote.
                assertThatThrownBy(() -> first.union(second))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("Chosen");
            }
        }

        @Test
        @DisplayName("a source redeclared to read somewhere else is refused too")
        void conflictingSourceIsRefused() {
            try (Relix relix = Relix.open()) {
                relix.define("""
                        source Orders from csv("./east.csv") {
                            header: true, schema: { id: NUMBER }
                        };
                        """);
                Relation east = relix.relation("Orders");

                relix.define("""
                        source Orders from csv("./west.csv") {
                            header: true, schema: { id: NUMBER }
                        };
                        """);

                // The heading is the same on both sides, so the symbols agree; what
                // differs is where the rows come from, which is no less a different
                // relation for being invisible in the schema.
                assertThatThrownBy(() -> east.union(relix.relation("Orders")))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("Orders");
            }
        }

        @Test
        @DisplayName("two relations from different sessions are a different mistake")
        void relationsFromTwoSessionsAreRefused() {
            try (Relix one = Relix.open(); Relix two = Relix.open()) {
                one.table("Left", List.of(Map.of("id", "1")));
                two.table("Right", List.of(Map.of("id", "2")));

                assertThatThrownBy(() -> one.relation("Left").cross(two.relation("Right")))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("session");
            }
        }

        @Test
        @DisplayName("an unresolvable name is refused by name, not by internal invariant")
        void unresolvableNameIsNamed() {
            H2 unreachable = new H2("jdbc:h2:mem:compose_unreachable;IFEXISTS=TRUE");
            try (Relix relix = Relix.builder()
                    .jdbc("warehouse", unreachable).allowUnresolved().build()) {

                // The mode says an unresolvable name may still compose and render. It is
                // planning that cannot proceed, and what it could not resolve is known
                // here, so it is said here rather than as an IllegalStateException about
                // a missing schema annotation.
                Relation offline = relix.relation("warehouse.orders");
                assertThat(offline).renders().contains("warehouse.orders");
                assertThatThrownBy(offline::toList)
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("warehouse.orders");
            }
        }
    }
}

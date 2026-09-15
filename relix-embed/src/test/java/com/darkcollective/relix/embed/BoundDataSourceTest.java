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

import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;

/**
 * A session over a live handle, with no JDBC URL anywhere in the script.
 *
 * <p>This is what the {@code ConnectionProvider} seam and the {@code DataSourceRegistry}
 * were built for, seen from the outside: the AST names the connection, the registry holds
 * the handle, and a table is introspected through it exactly as a declared connection's
 * would be.
 */
@DisplayName("Relix — a session over a bound DataSource")
final class BoundDataSourceTest {

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

    private static H2 seeded(String name) throws SQLException {
        H2 ds = new H2("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS orders ("
                            + "order_id INT, customer_id INT, status VARCHAR(16), amount DECIMAL(10,2))");
        }
        return ds;
    }

    @Test
    @DisplayName("a bound connection needs no declaration in the script")
    void boundConnectionIsAlreadyDeclared() throws SQLException {
        try (Relix relix = Relix.builder().jdbc("warehouse", seeded("embed_declared")).build()) {
            // The session declared it on the caller's behalf when the handle was bound.
            assertThat(relix.definitions()).contains("connection warehouse from jdbc");
            assertThat(relix.definitions()).doesNotContain("url");
        }
    }

    @Test
    @DisplayName("a table is introspected through the handle, so its columns are typed")
    void introspectsThroughTheHandle() throws SQLException {
        try (Relix relix = Relix.builder().jdbc("warehouse", seeded("embed_introspect")).build()) {
            // The dotted form is what reaches catalog introspection: a source declaration
            // must state its own schema, a dotted reference asks the database for one.
            Relation orders = relix.relation("warehouse.orders");
            var schema = orders.model().nodeSchemas().get(orders.node()).orElseThrow();

            // Types come from DatabaseMetaData: nothing in the script declared them.
            assertThat(schema.column("ORDER_ID").orElseThrow().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(schema.column("STATUS").orElseThrow().type()).isEqualTo(ScalarType.STRING);
        }
    }

    @Test
    @DisplayName("an explicit dialect is carried into the declaration the session makes")
    void explicitDialectReachesTheDeclaration() throws SQLException {
        try (Relix relix = Relix.builder()
                .jdbc("warehouse", seeded("embed_dialect"), "postgres").build()) {
            assertThat(relix.definitions()).contains("dialect");
            assertThat(relix.definitions()).contains("postgres");
        }
    }

    @Test
    @DisplayName("an unreachable handle is rejected by default, naming the table")
    void unreachableHandleIsRejectedByDefault() {
        H2 broken = new H2("jdbc:h2:mem:embed_broken;IFEXISTS=TRUE");
        try (Relix relix = Relix.builder().jdbc("warehouse", broken).build()) {
            // The analyser cannot resolve a dotted name without the catalog, and the
            // overwhelmingly common cause of an unresolvable name is a typo. Default
            // strictness reports it here rather than deferring it to execution.
            assertThatThrownBy(() -> relix.relation("warehouse.orders"))
                    .isInstanceOf(RelixException.class)
                    .hasMessageContaining("orders");
        }
    }

    @Test
    @DisplayName("with allowUnresolved, an unreachable handle still composes")
    void unreachableHandleComposesWhenAllowed() {
        H2 broken = new H2("jdbc:h2:mem:embed_broken2;IFEXISTS=TRUE");
        try (Relix relix = Relix.builder()
                .jdbc("warehouse", broken).allowUnresolved().build()) {
            // This is the offline case the mode exists for: compose and render a query
            // against a database nothing can reach, at the cost of a weaker plan.
            assertThat(relix.relation("σ status = 'OPEN' (warehouse.orders)").node()).isNotNull();
        }
    }

    @Test
    @DisplayName("a declared schema needs nothing reachable, in either mode")
    void declaredSchemaNeedsNoDatabase() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    source Orders from csv("./nowhere.csv") {
                        header: true, schema: { order_id: NUMBER, status: STRING }
                    };
                    """);
            // No mode needed: the schema is in the script, so nothing is unresolved.
            assertThat(relix.relation("σ status = 'OPEN' (Orders)").node()).isNotNull();
        }
    }

    @Test
    @DisplayName("a session mixes a bound handle with a connection the script declares")
    void mixesBoundAndDeclared() throws SQLException {
        H2 bound = seeded("embed_mixed_bound");
        try (Relix relix = Relix.builder().jdbc("warehouse", bound).build()) {
            relix.define("""
                    connection legacy from jdbc { url: "jdbc:h2:mem:embed_mixed_legacy;DB_CLOSE_DELAY=-1" };
                    """);

            assertThat(relix.statements()).hasSize(2);   // the bound one, plus the declared one
            assertThat(relix.relation("warehouse.orders").node()).isNotNull();
        }
    }

    @Test
    @DisplayName("rows come back through the handle, with no url anywhere")
    void readsRowsThroughTheHandle() throws SQLException {
        H2 ds = seeded("embed_read");
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("INSERT INTO orders VALUES (1, 10, 'OPEN', 100)");
        }
        try (Relix relix = Relix.builder().jdbc("warehouse", ds).build()) {
            // Introspection alone proved nothing about execution: the two travel
            // different paths, and the read path is the one that asked a connection
            // with no url for its url — a driver check on a handle that opens its own
            // connections, with no URL for a driver to accept.
            assertThat(relix.relation("warehouse.orders")).tuples()
                    .singleElement()
                    .satisfies(row -> assertThat(row.longValue("ORDER_ID")).isEqualTo(1L));
        }
    }

    @Test
    @DisplayName("binding the same name twice keeps the later handle")
    void rebindingKeepsTheLatest() throws SQLException {
        try (Relix relix = Relix.builder()
                .jdbc("warehouse", seeded("embed_rebind_a"))
                .jdbc("warehouse", seeded("embed_rebind_b"))
                .build()) {
            assertThat(relix.statements()).hasSize(1);
        }
    }

    @Test
    @DisplayName("a blank connection name is refused at binding time")
    void blankNameRefused() throws SQLException {
        H2 ds = seeded("embed_blank");
        assertThatThrownBy(() -> Relix.builder().jdbc("  ", ds))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

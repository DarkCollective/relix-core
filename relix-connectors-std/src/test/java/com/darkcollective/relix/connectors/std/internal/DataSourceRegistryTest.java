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
package com.darkcollective.relix.connectors.std.internal;

import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DataSourceRegistry — binding a connection name to a live handle")
final class DataSourceRegistryTest {

    /** A minimal {@link DataSource} over {@code DriverManager}, counting handouts. */
    private static final class CountingDataSource implements DataSource {
        private final String url;
        private final AtomicInteger handouts = new AtomicInteger();

        CountingDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            handouts.incrementAndGet();
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

    private static CountingDataSource h2(String name) {
        return new CountingDataSource("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }

    /** The declaration an injected connection gets: a name and a type, no url. */
    private static ConnectionDeclaration injected(String name) {
        return connection(true, name, "jdbc", Map.of());
    }

    private static ConnectionDeclaration declared(String name, String url) {
        return connection(name, new DatabaseConnectionConfig(
                url, Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Nested
    @DisplayName("resolving a connection")
    final class Resolving {

        @Test
        @DisplayName("a registered name is served from its DataSource")
        void servesRegisteredName() throws SQLException {
            CountingDataSource ds = h2("reg_serve");
            DataSourceRegistry registry = new DataSourceRegistry().register("warehouse", ds);

            try (Connection c = registry.connectionFor(injected("warehouse"))) {
                assertThat(c.isClosed()).isFalse();
            }
            assertThat(ds.handouts).hasValue(1);
        }

        @Test
        @DisplayName("names are matched case-insensitively, like the rest of relix")
        void matchesNameCaseInsensitively() throws SQLException {
            DataSourceRegistry registry = new DataSourceRegistry().register("Warehouse", h2("reg_case"));
            assertThat(registry.holds("WAREHOUSE")).isTrue();
            try (Connection c = registry.connectionFor(injected("warehouse"))) {
                assertThat(c.isClosed()).isFalse();
            }
        }

        @Test
        @DisplayName("an unregistered name falls back, so one session may mix injected and declared")
        void unregisteredNameFallsBack() throws SQLException {
            DataSourceRegistry registry = new DataSourceRegistry().register("warehouse", h2("reg_mix_a"));

            ConnectionDeclaration legacy = declared("legacy", "jdbc:h2:mem:reg_mix_b;DB_CLOSE_DELAY=-1");
            try (Connection c = registry.connectionFor(legacy)) {
                assertThat(c.isClosed()).isFalse();
            }
            assertThat(registry.holds("legacy")).isFalse();
        }

        @Test
        @DisplayName("an unregistered name with no url reports the fallback's SQLException")
        void unregisteredUrllessFails() {
            DataSourceRegistry registry = new DataSourceRegistry();
            assertThatThrownBy(() -> registry.connectionFor(injected("nowhere")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("nowhere");
        }

        @Test
        @DisplayName("registering twice replaces the binding")
        void reregisterReplaces() throws SQLException {
            CountingDataSource first = h2("reg_first");
            CountingDataSource second = h2("reg_second");
            DataSourceRegistry registry = new DataSourceRegistry()
                    .register("warehouse", first)
                    .register("warehouse", second);

            registry.connectionFor(injected("warehouse")).close();
            assertThat(first.handouts).hasValue(0);
            assertThat(second.handouts).hasValue(1);
        }

        @Test
        @DisplayName("a blank name is rejected")
        void rejectsBlankName() {
            assertThatThrownBy(() -> new DataSourceRegistry().register("  ", h2("reg_blank")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("pooling")
    final class Pooling {

        @Test
        @DisplayName("a registered connection is never pooled again on top of its DataSource")
        void registeredConnectionsAreNotPooled() throws SQLException {
            CountingDataSource ds = h2("reg_pool");
            DataSourceRegistry registry = new DataSourceRegistry().register("warehouse", ds);
            ConnectionDeclaration conn = injected("warehouse");

            assertThat(registry.managesPooling(conn)).isTrue();
            try (ConnectionPool pool = new ConnectionPool(registry)) {
                Connection first = pool.borrow(conn);
                pool.release(conn, first);
                assertThat(first.isClosed())
                        .as("release returns it to the DataSource, which means closing it")
                        .isTrue();
                pool.release(conn, pool.borrow(conn));
            }
            assertThat(ds.handouts).hasValue(2);   // nothing was retained between borrows
        }

        @Test
        @DisplayName("a delegated connection is still pooled by the engine")
        void delegatedConnectionsArePooled() throws SQLException {
            DataSourceRegistry registry = new DataSourceRegistry().register("warehouse", h2("reg_pool_a"));
            ConnectionDeclaration legacy = declared("legacy", "jdbc:h2:mem:reg_pool_b;DB_CLOSE_DELAY=-1");

            assertThat(registry.managesPooling(legacy)).isFalse();
            try (ConnectionPool pool = new ConnectionPool(registry)) {
                Connection first = pool.borrow(legacy);
                pool.release(legacy, first);
                assertThat(pool.borrow(legacy)).isSameAs(first);
            }
        }
    }

    @Nested
    @DisplayName("dialect")
    final class DialectResolution {

        @Test
        @DisplayName("an explicit dialect wins and skips the probe entirely")
        void explicitDialectWins() {
            CountingDataSource ds = h2("reg_dialect_explicit");
            DataSourceRegistry registry = new DataSourceRegistry().register("warehouse", ds, "postgres");

            assertThat(registry.dialectFor("warehouse")).contains("postgres");
            assertThat(ds.handouts).as("no connection is opened to answer").hasValue(0);
        }

        @Test
        @DisplayName("a probed product with no dedicated dialect is empty, which is the generic one")
        void probedH2IsGeneric() {
            DataSourceRegistry registry = new DataSourceRegistry().register("warehouse", h2("reg_dialect_h2"));
            assertThat(registry.dialectFor("warehouse")).isEmpty();
        }

        @Test
        @DisplayName("the probe runs once per name, including when it finds nothing")
        void probeIsMemoized() {
            CountingDataSource ds = h2("reg_dialect_memo");
            DataSourceRegistry registry = new DataSourceRegistry().register("warehouse", ds);

            registry.dialectFor("warehouse");
            registry.dialectFor("warehouse");
            registry.dialectFor("warehouse");
            assertThat(ds.handouts).hasValue(1);
        }

        @Test
        @DisplayName("an unregistered name has no dialect")
        void unregisteredHasNoDialect() {
            assertThat(new DataSourceRegistry().dialectFor("nowhere")).isEmpty();
        }

        @Test
        @DisplayName("product names map to the tokens the planner understands")
        void mapsProductNames() {
            assertThat(DataSourceRegistry.dialectToken("PostgreSQL")).contains("postgres");
            assertThat(DataSourceRegistry.dialectToken("MySQL")).contains("mysql");
            assertThat(DataSourceRegistry.dialectToken("MariaDB")).contains("mariadb");
            assertThat(DataSourceRegistry.dialectToken("DuckDB")).contains("duckdb");
            assertThat(DataSourceRegistry.dialectToken("SQLite")).contains("sqlite");
            assertThat(DataSourceRegistry.dialectToken("Microsoft SQL Server")).contains("sqlserver");
            assertThat(DataSourceRegistry.dialectToken("H2")).isEmpty();
            assertThat(DataSourceRegistry.dialectToken(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the declaration it hands the engine")
    final class Declaration {

        @Test
        @DisplayName("names the connection, types it jdbc, and carries no url")
        void declarationHasNoUrl() {
            DataSourceRegistry registry = new DataSourceRegistry().register("Warehouse", h2("reg_decl"));

            ConnectionDeclaration decl = registry.declarationFor("Warehouse");
            assertThat(decl.name()).isEqualTo("warehouse");
            assertThat(decl.connectorType()).isEqualTo("jdbc");
            assertThat(decl.properties()).doesNotContainKey("url");
        }

        @Test
        @DisplayName("carries the resolved dialect, so the planner reaches the declared answer")
        void declarationCarriesDialect() {
            DataSourceRegistry registry =
                    new DataSourceRegistry().register("warehouse", h2("reg_decl_dialect"), "postgres");
            assertThat(registry.declarationFor("warehouse").properties())
                    .containsEntry("dialect", "postgres");
        }

        @Test
        @DisplayName("an unregistered name has no declaration to give")
        void unregisteredHasNoDeclaration() {
            assertThatThrownBy(() -> new DataSourceRegistry().declarationFor("nowhere"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nowhere");
        }
    }

    @Nested
    @DisplayName("introspection over an injected handle")
    final class Introspection {

        @Test
        @DisplayName("a schema is read from a connection that has no url at all")
        void introspectsInjectedConnection() throws SQLException {
            CountingDataSource ds = h2("reg_introspect");
            try (Connection c = ds.getConnection()) {
                c.createStatement().execute("CREATE TABLE orders (id INT, amount DECIMAL(10,2))");
            }
            DataSourceRegistry registry = new DataSourceRegistry().register("warehouse", ds);

            var schema = new JdbcCatalogProvider(registry)
                    .tableSchema(registry.declarationFor("warehouse"), "orders");
            assertThat(schema).isPresent();
            assertThat(schema.orElseThrow().columns()).extracting("name")
                    .containsExactly("ID", "AMOUNT");
        }
    }
}

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
package com.darkcollective.relix.connectors.std;

import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ConnectionProvider — the seam between a declaration and a live connection")
final class ConnectionProviderTest {

    private static ConnectionDeclaration jdbc(String name) {
        return connection(name, new DatabaseConnectionConfig(
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1",
                Optional.empty(), Optional.empty(), Optional.empty()));
    }

    /** A connection with coordinates that are not a URL — what an injected handle looks like. */
    private static ConnectionDeclaration urlless(String name, String type) {
        return connection(
                true, name, type, Map.of("database", "orders"));
    }

    @Nested
    @DisplayName("FROM_URL — the default, and today's behaviour")
    final class FromUrl {

        @Test
        @DisplayName("opens a connection from the declaration's url")
        void opensFromUrl() throws SQLException {
            try (Connection c = ConnectionProvider.FROM_URL.connectionFor(jdbc("prov_url"))) {
                assertThat(c.isClosed()).isFalse();
            }
        }

        @Test
        @DisplayName("a declaration with no url yields SQLException, not IllegalStateException")
        void urllessYieldsSqlException() {
            // ConnectionDeclaration.config() raises IllegalStateException, which no JDBC caller
            // catches. Reporting it as SQLException is what lets every caller degrade instead.
            assertThatThrownBy(() ->
                    ConnectionProvider.FROM_URL.connectionFor(urlless("orders", "mongodb")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("orders")
                    .hasMessageContaining("mongodb")
                    .hasMessageContaining("url");
        }

        @Test
        @DisplayName("does not manage its own pooling")
        void doesNotManagePooling() {
            assertThat(ConnectionProvider.FROM_URL.managesPooling(jdbc("prov_pooling"))).isFalse();
        }
    }

    @Nested
    @DisplayName("ConnectionPool honours the provider")
    final class PoolIntegration {

        @Test
        @DisplayName("fresh connections are opened through the provider, not around it")
        void opensThroughProvider() throws SQLException {
            AtomicInteger opens = new AtomicInteger();
            ConnectionProvider counting = c -> {
                opens.incrementAndGet();
                return ConnectionProvider.FROM_URL.connectionFor(c);
            };
            ConnectionDeclaration conn = jdbc("prov_counted");
            try (ConnectionPool pool = new ConnectionPool(counting)) {
                Connection first = pool.borrow(conn);
                pool.release(conn, first);
                Connection second = pool.borrow(conn);   // served from idle, not reopened
                assertThat(second).isSameAs(first);
                pool.release(conn, second);
            }
            assertThat(opens).hasValue(1);
        }

        @Test
        @DisplayName("a provider that manages pooling is never pooled on top: release closes")
        void managedPoolingBypassesTheIdleDeque() throws SQLException {
            ConnectionDeclaration conn = jdbc("prov_managed");
            ConnectionProvider managed = new ConnectionProvider() {
                @Override
                public Connection connectionFor(ConnectionDeclaration c) throws SQLException {
                    return ConnectionProvider.FROM_URL.connectionFor(c);
                }

                @Override
                public boolean managesPooling(ConnectionDeclaration c) {
                    return true;
                }
            };
            try (ConnectionPool pool = new ConnectionPool(managed)) {
                Connection first = pool.borrow(conn);
                pool.release(conn, first);
                assertThat(first.isClosed())
                        .as("release must hand the connection back to the pool that owns it")
                        .isTrue();

                Connection second = pool.borrow(conn);
                assertThat(second).isNotSameAs(first);   // nothing was retained to hand back
                pool.release(conn, second);
            }
        }

        @Test
        @DisplayName("one provider may manage pooling for some connections and not others")
        void poolingIsDecidedPerDeclaration() throws SQLException {
            ConnectionDeclaration managed = jdbc("prov_mixed_managed");
            ConnectionDeclaration pooled = jdbc("prov_mixed_pooled");
            ConnectionProvider mixed = new ConnectionProvider() {
                @Override
                public Connection connectionFor(ConnectionDeclaration c) throws SQLException {
                    return ConnectionProvider.FROM_URL.connectionFor(c);
                }

                @Override
                public boolean managesPooling(ConnectionDeclaration c) {
                    return c.name().equals(managed.name());
                }
            };
            try (ConnectionPool pool = new ConnectionPool(mixed)) {
                Connection m = pool.borrow(managed);
                pool.release(managed, m);
                assertThat(m.isClosed()).isTrue();

                Connection p = pool.borrow(pooled);
                pool.release(pooled, p);
                assertThat(p.isClosed()).isFalse();
                assertThat(pool.borrow(pooled)).isSameAs(p);
            }
        }

        @Test
        @DisplayName("a provider that cannot open reports SQLException through borrow")
        void borrowSurfacesProviderFailure() {
            try (ConnectionPool pool = new ConnectionPool()) {
                assertThatThrownBy(() -> pool.borrow(urlless("orders", "mongodb")))
                        .isInstanceOf(SQLException.class);
            }
        }

        @Test
        @DisplayName("a null provider is rejected")
        void rejectsNullProvider() {
            assertThatThrownBy(() -> new ConnectionPool(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("JdbcCatalogProvider honours the provider")
    final class CatalogIntegration {

        @Test
        @DisplayName("introspects through the provider it was given")
        void introspectsThroughProvider() throws SQLException {
            ConnectionDeclaration conn = jdbc("prov_catalog");
            try (Connection c = ConnectionProvider.FROM_URL.connectionFor(conn)) {
                c.createStatement().execute("CREATE TABLE t (id INT, name VARCHAR(10))");
            }
            AtomicInteger opens = new AtomicInteger();
            ConnectionProvider counting = d -> {
                opens.incrementAndGet();
                return ConnectionProvider.FROM_URL.connectionFor(d);
            };

            assertThat(new JdbcCatalogProvider(counting).tableSchema(conn, "t"))
                    .get()
                    .extracting(s -> s.columns().stream().map(col -> col.name()).toList())
                    .isEqualTo(List.of("ID", "NAME"));
            assertThat(opens).hasValue(1);
        }

        @Test
        @DisplayName("a non-JDBC connection degrades to 'schema unavailable' rather than raising")
        void urllessConnectionDegrades() {
            // One analyzer holds one CatalogProvider for every connection a script declares.
            // Reading connection.config() eagerly meant a mongodb connection raised
            // IllegalStateException out of analysis; it is an empty Optional, like any other
            // connection whose schema cannot be read.
            ConnectionDeclaration mongo = urlless("orders", "mongodb");
            assertThat(new JdbcCatalogProvider().tableSchema(mongo, "orders")).isEmpty();
            assertThat(new JdbcCatalogProvider().tableStatistics(mongo, "orders")).isEmpty();
        }

        @Test
        @DisplayName("a null provider is rejected")
        void rejectsNullProvider() {
            assertThatThrownBy(() -> new JdbcCatalogProvider(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}

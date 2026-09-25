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

import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JdbcDataSourceConnector — reading rows over JDBC (H2)")
final class JdbcDataSourceConnectorTest {

    private static Schema schema(ColumnDefinition... cols) {
        return new Schema(List.of(cols));
    }

    private static ColumnDefinition col(String name, ScalarType type) {
        return new ColumnDefinition(name, type);
    }

    /** Builds a model with one connection and one connection-table source named {@code relation}. */
    private static SemanticModel modelFor(String url, String relation, String table) {
        var conn = connection("db",
                new DatabaseConnectionConfig(url, Optional.empty(), Optional.empty(), Optional.empty()));
        var src = source(true, relation,
                connectionTable("db", table));
        return new SemanticModel("default", new InMemorySymbolTable(),
                Map.of(relation.toLowerCase(), src), Map.of("db", conn),
                SchemaAnnotations.empty(), List.of());
    }

    @Test
    @DisplayName("reads rows, mapping NULL and JDBC types to relix values")
    void readsRows() throws SQLException {
        String url = "jdbc:h2:mem:conn_read;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE people (id INT, name VARCHAR(50), active BOOLEAN)");
            st.execute("INSERT INTO people VALUES (1, 'Alice', TRUE), (2, 'Bob', FALSE), (3, NULL, NULL)");
        }

        Schema schema = schema(
                col("id", ScalarType.NUMBER), col("name", ScalarType.STRING), col("active", ScalarType.BOOLEAN));
        var connector = new JdbcDataSourceConnector(modelFor(url, "people", "people"));

        List<Row> rows;
        try (Stream<Row> s = connector.open("people", schema)) {
            rows = s.toList();
        }

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).hasValue("name", "Alice")
                .hasValue("id", "1")
                .hasValue("active", "true");
        assertThat(rows.get(2).get("name").isNull()).isTrue();    // NULL name
        assertThat(rows.get(2).get("active").isNull()).isTrue();  // NULL boolean
    }

    @Test
    @DisplayName("reads temporal columns as typed values; TIMESTAMP WITH TIME ZONE normalises to a UTC instant")
    void readsTemporalColumns() throws SQLException {
        String url = "jdbc:h2:mem:conn_temporal;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE events (d DATE, t TIME, ts TIMESTAMP, tstz TIMESTAMP WITH TIME ZONE)");
            st.execute("INSERT INTO events VALUES "
                    + "(DATE '2026-06-15', TIME '13:40:00', TIMESTAMP '2026-06-15 13:40:00', "
                    + "TIMESTAMP WITH TIME ZONE '2026-06-15 14:40:00+01')");
            st.execute("INSERT INTO events VALUES (NULL, NULL, NULL, NULL)");
        }

        Schema schema = schema(
                col("d", ScalarType.DATE), col("t", ScalarType.TIME),
                col("ts", ScalarType.TIMESTAMP), col("tstz", ScalarType.TIMESTAMP));
        var connector = new JdbcDataSourceConnector(modelFor(url, "events", "events"));

        List<Row> rows;
        try (Stream<Row> s = connector.open("events", schema)) {
            rows = s.toList();
        }

        assertThat(rows).hasSize(2);
        Row r = rows.get(0);
        assertThat(r.get("d")).isEqualTo(new DateValue(java.time.LocalDate.parse("2026-06-15")));
        assertThat(r.get("t")).isEqualTo(new TimeValue(java.time.LocalTime.parse("13:40:00")));
        // A zone-less SQL TIMESTAMP is interpreted as UTC (ADR-0013 Decision 2).
        assertThat(r.get("ts")).isEqualTo(new TimestampValue(java.time.Instant.parse("2026-06-15T13:40:00Z")));
        // 14:40+01:00 == 13:40Z
        assertThat(r.get("tstz")).isEqualTo(new TimestampValue(java.time.Instant.parse("2026-06-15T13:40:00Z")));

        Row nulls = rows.get(1);
        assertThat(nulls.get("d").isNull()).isTrue();
        assertThat(nulls.get("t").isNull()).isTrue();
        assertThat(nulls.get("ts").isNull()).isTrue();
        assertThat(nulls.get("tstz").isNull()).isTrue();
    }

    @Test
    @DisplayName("streams lazily — a partial read (findFirst) works and the stream closes cleanly")
    void streamsLazilyAndCloses() throws SQLException {
        String url = "jdbc:h2:mem:conn_lazy;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE people (id INT, name VARCHAR(50))");
            st.execute("INSERT INTO people VALUES (1,'Alice'),(2,'Bob'),(3,'Carol')");
        }
        Schema schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
        var connector = new JdbcDataSourceConnector(modelFor(url, "people", "people"));

        try (Stream<Row> s = connector.open("people", schema)) {
            Optional<Row> first = s.findFirst();   // consumes one row, then closes resources
            assertThat(first).isPresent();
            assertThat(first.get()).hasValue("id", "1");
        }
    }

    @Test
    @DisplayName("a connection dropped mid-scan fails the read rather than truncating it")
    void connectionDroppedMidScan() throws SQLException {
        String url = "jdbc:h2:mem:conn_drop;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE people (id INT, name VARCHAR(50))");
            st.execute("INSERT INTO people VALUES (1,'Alice'),(2,'Bob'),(3,'Carol')");
        }
        var config = connection("people_conn", new DatabaseConnectionConfig(
                url, Optional.empty(), Optional.empty(), Optional.empty()));
        Schema schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));

        // The provider seam is where a test gets a handle on the connection the scan is
        // reading through — the real driver's own, so the failure is the one a dropped
        // connection actually produces rather than one a mock was told to produce.
        List<Connection> opened = new ArrayList<>();
        ConnectionProvider recording = declaration -> {
            Connection conn = DriverManager.getConnection(url);
            opened.add(conn);
            return conn;
        };

        try (ConnectionPool pool = new ConnectionPool(recording)) {
            var connector = new JdbcDataSourceConnector(modelFor(url, "people", "people"), pool);
            Connection dropped;
            try (Stream<Row> s = connector.open("people", schema)) {
                Iterator<Row> rows = s.iterator();
                assertThat(rows.next()).hasValue("id", "1");

                dropped = opened.getLast();
                dropped.close();   // the connection goes away under a half-read scan

                // A truncated answer would be the dangerous outcome: the caller cannot tell
                // three rows that ended from three rows that were cut off at one.
                assertThatThrownBy(rows::next)
                        .isInstanceOf(EvaluationException.class)
                        .hasMessageContaining("people");
            }

            // The stream closed on the way out, so the broken connection was offered back to
            // the pool — and refused, rather than pooled for the next query to fail on.
            Connection fresh = pool.borrow(config);
            assertThat(fresh).isNotSameAs(dropped);
            assertThat(fresh.isClosed()).isFalse();
            pool.release(config, fresh);
        }
    }

    @Test
    @DisplayName("releases its connection to a shared pool for reuse; close() leaves a shared pool open")
    void sharedPoolReuse() throws SQLException {
        String url = "jdbc:h2:mem:conn_pool;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE people (id INT, name VARCHAR(50))");
            st.execute("INSERT INTO people VALUES (1,'Alice')");
        }
        var config = connection("people_conn", new DatabaseConnectionConfig(
                url, Optional.empty(), Optional.empty(), Optional.empty()));
        Schema schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));

        try (ConnectionPool pool = new ConnectionPool()) {
            var connector = new JdbcDataSourceConnector(modelFor(url, "people", "people"), pool);
            try (Stream<Row> s = connector.open("people", schema)) {
                s.toList();   // fully read, then the stream closes → connection returns to the pool
            }
            connector.close();   // shared pool: must NOT be closed

            Connection reused = pool.borrow(config);   // succeeds because the pool is still open
            assertThat(reused.isClosed()).isFalse();
            pool.release(config, reused);
        }
    }

    @Test
    @DisplayName("openQuery runs a pushed-down query and reads columns positionally")
    void openQueryReadsPositionally() throws SQLException {
        String url = "jdbc:h2:mem:conn_push;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE people (id INT, name VARCHAR(50))");
            st.execute("INSERT INTO people VALUES (1,'Alice'),(2,'Bob'),(3,'Carol')");
        }
        var connector = new JdbcDataSourceConnector(modelFor(url, "people", "people"));

        Schema projected = schema(col("name", ScalarType.STRING));
        List<Row> rows;
        try (Stream<Row> s = connector.openQuery("db",
                "SELECT name FROM people WHERE id >= 2 ORDER BY id", projected)) {
            rows = s.toList();
        }

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).hasValue("name", "Bob");
        assertThat(rows.get(1)).hasValue("name", "Carol");
    }

    @Test
    @DisplayName("openQuery on an unknown connection fails")
    void openQueryUnknownConnectionFails() {
        var connector = new JdbcDataSourceConnector(
                modelFor("jdbc:h2:mem:conn_push2;DB_CLOSE_DELAY=-1", "people", "people"));
        assertThatThrownBy(() -> connector.openQuery("missing", "SELECT 1", schema(col("x", ScalarType.NUMBER))))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("Unknown connection");
    }

    @Test
    @DisplayName("openQuery surfaces a SQL error as an EvaluationException")
    void openQueryBadSqlFails() throws SQLException {
        String url = "jdbc:h2:mem:conn_push3;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            c.createStatement().execute("CREATE TABLE present (id INT)");
        }
        var connector = new JdbcDataSourceConnector(modelFor(url, "present", "present"));
        assertThatThrownBy(() -> connector.openQuery("db", "SELECT * FROM nope",
                schema(col("id", ScalarType.NUMBER))).close())
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("JDBC error");
    }

    @Test
    @DisplayName("an unknown JDBC scheme gives a clear 'no driver' message, not 'No suitable driver'")
    void missingDriverGivesFriendlyError() {
        var connector = new JdbcDataSourceConnector(
                modelFor("jdbc:nosuchdb://host/db", "people", "people"));
        assertThatThrownBy(() -> connector.open("people", schema(col("id", ScalarType.NUMBER))).close())
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("No JDBC driver available")
                .hasMessageContaining("jdbc:nosuchdb://host/db");
    }

    @Test
    @DisplayName("a JDBC failure (missing table) surfaces as an EvaluationException")
    void missingTableFails() throws SQLException {
        String url = "jdbc:h2:mem:conn_missing;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            c.createStatement().execute("CREATE TABLE present (id INT)");
        }
        var connector = new JdbcDataSourceConnector(modelFor(url, "ghost", "ghost"));

        assertThatThrownBy(() -> connector.open("ghost", schema(col("id", ScalarType.NUMBER))).close())
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("JDBC error");
    }
}

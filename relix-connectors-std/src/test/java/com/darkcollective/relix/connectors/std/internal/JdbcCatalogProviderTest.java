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

import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.ScriptBuilders;
import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnStatistics;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JdbcCatalogProvider — schema introspection over JDBC (H2)")
final class JdbcCatalogProviderTest {

    private static ConnectionDeclaration connection(String url) {
        return ScriptBuilders.connection("db",
                new DatabaseConnectionConfig(url, Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("maps temporal SQL types to relix temporal types (ADR-0013)")
    void introspectsTemporalTypes() throws SQLException {
        String url = "jdbc:h2:mem:cat_temporal;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            c.createStatement().execute(
                    "CREATE TABLE events (d DATE, t TIME, ts TIMESTAMP, tstz TIMESTAMP WITH TIME ZONE)");
        }

        Schema s = new JdbcCatalogProvider().tableSchema(connection(url), "events").orElseThrow();
        assertThat(s.column("d").orElseThrow().type()).isEqualTo(ScalarType.DATE);
        assertThat(s.column("t").orElseThrow().type()).isEqualTo(ScalarType.TIME);
        assertThat(s.column("ts").orElseThrow().type()).isEqualTo(ScalarType.TIMESTAMP);
        assertThat(s.column("tstz").orElseThrow().type()).isEqualTo(ScalarType.TIMESTAMP);
    }

    @Test
    @DisplayName("introspects column names and maps SQL types to relix types")
    void introspectsSchema() throws SQLException {
        String url = "jdbc:h2:mem:cat_intro;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            c.createStatement().execute(
                    "CREATE TABLE people (id INT, name VARCHAR(50), active BOOLEAN, score NUMERIC(10,2))");
        }

        Optional<Schema> schema = new JdbcCatalogProvider().tableSchema(connection(url), "people");

        assertThat(schema).isPresent();
        Schema s = schema.get();
        assertThat(s.columns()).extracting(ColumnDefinition::name)
                .map(String::toLowerCase)
                .containsExactlyInAnyOrder("id", "name", "active", "score");
        assertThat(s.column("id").orElseThrow().type()).isEqualTo(ScalarType.NUMBER);
        assertThat(s.column("name").orElseThrow().type()).isEqualTo(ScalarType.STRING);
        assertThat(s.column("active").orElseThrow().type()).isEqualTo(ScalarType.BOOLEAN);
        assertThat(s.column("score").orElseThrow().type()).isEqualTo(ScalarType.NUMBER);
    }

    @Test
    @DisplayName("returns empty for an unknown table")
    void unknownTableIsEmpty() throws SQLException {
        String url = "jdbc:h2:mem:cat_missing;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            c.createStatement().execute("CREATE TABLE present (id INT)");
        }
        assertThat(new JdbcCatalogProvider().tableSchema(connection(url), "absent")).isEmpty();
    }

    @Test
    @DisplayName("returns empty (rather than throwing) when the database is unreachable")
    void unreachableIsEmpty() {
        ConnectionDeclaration bad = connection("jdbc:h2:tcp://127.0.0.1:1/nope");
        assertThat(new JdbcCatalogProvider().tableSchema(bad, "people")).isEmpty();
    }

    @Test
    @DisplayName("statistics report the row count and the primary key")
    void statisticsRowCountAndKey() throws SQLException {
        String url = "jdbc:h2:mem:cat_stats;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(50))");
            st.execute("INSERT INTO people VALUES (1,'A'),(2,'B'),(3,'C')");
        }

        Optional<RelationStatistics> stats =
                new JdbcCatalogProvider().tableStatistics(connection(url), "people");

        assertThat(stats).isPresent();
        assertThat(stats.get().rowCount()).hasValue(3L);
        assertThat(stats.get().keys()).hasSize(1);
        assertThat(stats.get().keys().get(0)).map(String::toLowerCase).containsExactly("id");
    }

    @Test
    @DisplayName("statistics report per-column distinct and null counts")
    void statisticsColumnDistinctAndNullCounts() throws SQLException {
        String url = "jdbc:h2:mem:cat_colstats;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE people (id INT, region VARCHAR(10))");
            // 4 rows: region has 2 distinct non-null values ('E','W') and 1 null
            st.execute("INSERT INTO people VALUES (1,'E'),(2,'W'),(3,'E'),(4,NULL)");
        }

        Optional<RelationStatistics> stats =
                new JdbcCatalogProvider().tableStatistics(connection(url), "people");

        assertThat(stats).isPresent();
        RelationStatistics s = stats.get();
        assertThat(s.rowCount()).hasValue(4L);

        ColumnStatistics id = columnOf(s, "id");
        assertThat(id.distinctCount()).hasValue(4L);   // all distinct
        assertThat(id.nullCount()).hasValue(0L);

        ColumnStatistics region = columnOf(s, "region");
        assertThat(region.distinctCount()).hasValue(2L);   // 'E','W'
        assertThat(region.nullCount()).hasValue(1L);
    }

    @Test
    @DisplayName("statistics are read for a table and column whose names are not plain identifiers")
    void statisticsForDelimitedNames() throws SQLException {
        String url = "jdbc:h2:mem:cat_delimited;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            var st = c.createStatement();
            st.execute("CREATE TABLE \"order-lines\" (lid INT, \"unit-price\" INT)");
            st.execute("INSERT INTO \"order-lines\" VALUES (1, 10), (2, 10), (3, NULL)");
        }

        Optional<RelationStatistics> stats =
                new JdbcCatalogProvider().tableStatistics(connection(url), "order-lines");

        assertThat(stats).isPresent();
        assertThat(stats.get().rowCount()).hasValue(3L);
        ColumnStatistics price = columnOf(stats.get(), "unit-price");
        assertThat(price.distinctCount()).hasValue(1L);
        assertThat(price.nullCount()).hasValue(1L);
    }

    /** Looks up a column's stats case-insensitively (the catalog stores names in its own case). */
    private static ColumnStatistics columnOf(RelationStatistics stats, String name) {
        return stats.columnStatistics().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no column statistics for '" + name + "'"));
    }

    @Test
    @DisplayName("statistics report an empty key list when the table has no primary key")
    void statisticsNoPrimaryKey() throws SQLException {
        String url = "jdbc:h2:mem:cat_nopk;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            c.createStatement().execute("CREATE TABLE log (msg VARCHAR(50))");
        }

        Optional<RelationStatistics> stats =
                new JdbcCatalogProvider().tableStatistics(connection(url), "log");

        assertThat(stats).isPresent();
        assertThat(stats.get().rowCount()).hasValue(0L);
        assertThat(stats.get().keys()).isEmpty();
    }

    @Test
    @DisplayName("statistics are empty for an unknown table")
    void statisticsUnknownTableEmpty() throws SQLException {
        String url = "jdbc:h2:mem:cat_stats_missing;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url)) {
            c.createStatement().execute("CREATE TABLE present (id INT)");
        }
        assertThat(new JdbcCatalogProvider().tableStatistics(connection(url), "absent")).isEmpty();
    }

    @Test
    @DisplayName("statistics are empty (rather than throwing) when unreachable")
    void statisticsUnreachableEmpty() {
        ConnectionDeclaration bad = connection("jdbc:h2:tcp://127.0.0.1:1/nope");
        assertThat(new JdbcCatalogProvider().tableStatistics(bad, "people")).isEmpty();
    }
}

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
import com.darkcollective.relix.symbol.ColumnStatistics;
import com.darkcollective.relix.symbol.RelationStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each database's own statistics, read the way analysis reads them, against the real
 * thing: what the catalogue queries in {@link StatisticsEstimates} name exists, the
 * connected user may read it, and the figures mean what the code takes them to mean.
 *
 * <p>Every case makes the same table — 1000 rows, a key, a column of ten distinct values
 * and a column that is NULL in a hundred rows — asks the database to gather its
 * statistics the way an administrator would, and expects the estimates to land near the
 * truth. Near, not on: an estimate is the database's, and a sampling collector is allowed
 * to be a little out.
 *
 * <p>In the {@code integration} tier, since each case needs a container.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("A table's statistics are the database's own estimates")
final class StatisticsEstimatesIntegrationTest {

    private static final int ROWS = 1000;

    private static ConnectionDeclaration declaration(JdbcDatabaseContainer<?> db) {
        return ScriptBuilders.connection("db", new DatabaseConnectionConfig(db.getJdbcUrl(),
                Optional.of(db.getUsername()), Optional.of(db.getPassword()), Optional.empty()));
    }

    private static Connection open(JdbcDatabaseContainer<?> db) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    /** The table: {@code id} a key, {@code grp} ten values, {@code opt} NULL in a tenth of the rows. */
    private static void create(JdbcDatabaseContainer<?> db, String table) throws SQLException {
        try (Connection c = open(db); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE " + table + " (id INT NOT NULL PRIMARY KEY, grp INT, opt INT)");
            for (int i = 0; i < ROWS; i += 100) {
                StringBuilder sql = new StringBuilder("INSERT INTO " + table + " (id, grp, opt) VALUES ");
                for (int j = i; j < i + 100; j++) {
                    sql.append(j == i ? "" : ", ").append('(').append(j).append(", ").append(j % 10)
                            .append(", ").append(j % 10 == 0 ? "NULL" : String.valueOf(j)).append(')');
                }
                s.execute(sql.toString());
            }
        }
    }

    private static void run(JdbcDatabaseContainer<?> db, String sql) throws SQLException {
        try (Connection c = open(db); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static RelationStatistics statistics(JdbcDatabaseContainer<?> db, String table) {
        return new JdbcCatalogProvider().tableStatistics(declaration(db), table).orElseThrow();
    }

    private static ColumnStatistics column(RelationStatistics stats, String name) {
        return stats.columnStatistics().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new AssertionError("no estimate for " + name + ": " + stats));
    }

    /** Within a tenth of {@code expected}: an estimate, not a count. */
    private static void near(long actual, long expected) {
        assertThat(actual).isBetween(expected - expected / 10, expected + expected / 10);
    }

    @Nested
    @DisplayName("PostgreSQL: pg_class and pg_stats")
    final class Postgres {

        @Container
        private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16");

        @Test
        @DisplayName("rows, distinct and null counts, after ANALYZE")
        void analysed() throws SQLException {
            create(DB, "analysed");
            run(DB, "ANALYZE analysed");
            RelationStatistics stats = statistics(DB, "analysed");
            near(stats.rowCount().orElseThrow(), ROWS);
            assertThat(column(stats, "grp").distinctCount()).hasValue(10L);
            near(column(stats, "opt").nullCount().orElseThrow(), 100);
            near(column(stats, "id").distinctCount().orElseThrow(), ROWS);
        }

        @Test
        @DisplayName("a table never analysed has no row estimate, rather than a wrong one")
        void neverAnalysed() throws SQLException {
            create(DB, "fresh");
            Optional<RelationStatistics> stats =
                    new JdbcCatalogProvider().tableStatistics(declaration(DB), "fresh");
            assertThat(stats.flatMap(s -> s.rowCount().isPresent()
                    ? Optional.of(s.rowCount().getAsLong()) : Optional.empty())).isEmpty();
        }
    }

    @Nested
    @DisplayName("MySQL: information_schema TABLES and STATISTICS")
    final class MySql {

        @Container
        private static final MySQLContainer<?> DB = new MySQLContainer<>("mysql:8.0.36")
                .withDatabaseName("relix").withUsername("relix").withPassword("relix");

        @Test
        @DisplayName("rows, and the distinct count of an indexed column, after ANALYZE TABLE")
        void analysed() throws SQLException {
            create(DB, "analysed");
            run(DB, "CREATE INDEX analysed_grp ON analysed (grp)");
            run(DB, "ANALYZE TABLE analysed");
            RelationStatistics stats = statistics(DB, "analysed");
            near(stats.rowCount().orElseThrow(), ROWS);
            assertThat(column(stats, "grp").distinctCount()).hasValue(10L);
        }
    }

    @Nested
    @DisplayName("SQL Server: sys.partitions")
    final class SqlServer {

        @Container
        private static final MSSQLServerContainer<?> DB =
                new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

        @Test
        @DisplayName("rows, which the server keeps current without a statistics run")
        void rows() throws SQLException {
            create(DB, "analysed");
            assertThat(statistics(DB, "analysed").rowCount()).hasValue(ROWS);
        }
    }

    @Nested
    @DisplayName("Db2: SYSCAT.TABLES and SYSCAT.COLUMNS")
    final class Db2 {

        @Container
        private static final Db2Container DB =
                new Db2Container(DockerImageName.parse("ibmcom/db2:11.5.8.0")).acceptLicense();

        @Test
        @DisplayName("rows, distinct and null counts, after RUNSTATS")
        void analysed() throws SQLException {
            create(DB, "ANALYSED");
            try (Connection c = open(DB); Statement s = c.createStatement()) {
                String schema = c.getSchema().trim();
                s.execute("CALL SYSPROC.ADMIN_CMD('RUNSTATS ON TABLE " + schema
                        + ".ANALYSED WITH DISTRIBUTION')");
            }
            RelationStatistics stats = statistics(DB, "ANALYSED");
            assertThat(stats.rowCount()).hasValue(ROWS);
            assertThat(column(stats, "GRP").distinctCount()).hasValue(10L);
            assertThat(column(stats, "OPT").nullCount()).hasValue(100L);
        }
    }
}

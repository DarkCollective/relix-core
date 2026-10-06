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

import com.darkcollective.relix.symbol.ColumnStatistics;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * A table's statistics as the database already holds them, read without touching the
 * table.
 *
 * <p>Every database worth planning against keeps its own estimate of a table's size,
 * maintained by its statistics collector for its own planner: PostgreSQL's
 * {@code pg_class.reltuples}, MySQL's {@code information_schema.TABLES.TABLE_ROWS}, and
 * so on. Reading one is a lookup in a system catalogue, so it costs the same for a table
 * of ten rows as for one of ten billion, which counting does not. Where the database
 * also keeps per-column figures, the distinct and null counts come from the same place.
 *
 * <p>Which catalogue to read is decided by the driver's product name rather than the
 * session's SQL dialect: the dialect is about how SQL is <em>written</em>, and H2, for
 * one, writes the generic dialect while keeping estimates of its own.
 *
 * <p>Everything here is best effort. A database that has never gathered statistics for
 * a table, a catalogue the connected user may not read, a product with no estimate at
 * all — each leaves the figure unknown, which is what statistics are allowed to be.
 */
final class StatisticsEstimates {

    private StatisticsEstimates() {
    }

    /** The system catalogue a product keeps its estimates in. */
    enum Source {
        POSTGRES, MYSQL, H2, SQLSERVER, DB2, DUCKDB, METADATA;

        /** {@return the source for a driver reporting {@code productName}} */
        static Source of(String productName) {
            String name = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
            if (name.contains("postgres")) return POSTGRES;
            if (name.contains("mysql") || name.contains("mariadb")) return MYSQL;
            if (name.equals("h2")) return H2;
            if (name.contains("sql server")) return SQLSERVER;
            if (name.startsWith("db2")) return DB2;
            if (name.contains("duckdb")) return DUCKDB;
            return METADATA;
        }
    }

    /**
     * The database's estimate of how many rows {@code table} holds.
     *
     * @param conn  an open connection
     * @param table the table, in the case the catalogue stores it
     * @return the estimate, or empty when the database has none
     */
    static OptionalLong rows(Connection conn, String table) {
        try {
            DatabaseMetaData meta = conn.getMetaData();
            return switch (Source.of(meta.getDatabaseProductName())) {
                // -1 (PostgreSQL 14+) or 0 with no pages (older) is "never analysed";
                // only the first is told apart, since an analysed empty table is also 0.
                case POSTGRES -> nonNegative(single(conn,
                        "SELECT reltuples::bigint FROM pg_class WHERE oid = to_regclass(?)",
                        quotePostgres(table)));
                case MYSQL -> nonNegative(single(conn,
                        "SELECT TABLE_ROWS FROM information_schema.TABLES "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", table));
                case H2 -> nonNegative(single(conn,
                        "SELECT ROW_COUNT_ESTIMATE FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_SCHEMA = CURRENT_SCHEMA AND TABLE_NAME = ?", table));
                case SQLSERVER -> nonNegative(single(conn,
                        "SELECT SUM(p.rows) FROM sys.partitions p "
                                + "WHERE p.object_id = OBJECT_ID(?) AND p.index_id IN (0, 1)",
                        "[" + table.replace("]", "]]") + "]"));
                // CARD is -1 until RUNSTATS has been run on the table.
                case DB2 -> nonNegative(single(conn,
                        "SELECT CARD FROM SYSCAT.TABLES "
                                + "WHERE TABSCHEMA = CURRENT SCHEMA AND TABNAME = ?", table));
                case DUCKDB -> nonNegative(single(conn,
                        "SELECT estimated_size FROM duckdb_tables() "
                                + "WHERE schema_name = current_schema() AND table_name = ?", table));
                case METADATA -> fromIndexInfo(meta, table);
            };
        } catch (SQLException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * The database's per-column estimates, for the products that keep them: distinct
     * and null counts from PostgreSQL's {@code pg_stats} and Db2's {@code SYSCAT.COLUMNS},
     * and distinct counts of single-column indexes from MySQL's
     * {@code information_schema.STATISTICS}. Any other column is left out, its figures
     * unknown.
     *
     * @param conn  an open connection
     * @param table the table, in the case the catalogue stores it
     * @param rows  the table's estimated row count, which a fraction is a fraction of
     * @return each column with an estimate; empty where the database keeps none
     */
    static Map<String, ColumnStatistics> columns(Connection conn, String table, OptionalLong rows) {
        Map<String, ColumnStatistics> stats = new LinkedHashMap<>();
        try {
            switch (Source.of(conn.getMetaData().getDatabaseProductName())) {
                case POSTGRES -> {
                    // n_distinct is a count when positive and minus a fraction of the rows
                    // when negative; null_frac is a fraction. Both need the row estimate.
                    if (rows.isEmpty()) {
                        return Map.of();
                    }
                    long n = rows.getAsLong();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT attname, n_distinct, null_frac FROM pg_stats "
                                    + "WHERE schemaname = current_schema() AND tablename = ?")) {
                        ps.setString(1, table);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                double distinct = rs.getDouble(2);
                                long d = distinct >= 0 ? Math.round(distinct) : Math.round(-distinct * n);
                                long nulls = Math.round(rs.getDouble(3) * n);
                                stats.put(rs.getString(1),
                                        new ColumnStatistics(OptionalLong.of(d), OptionalLong.of(nulls)));
                            }
                        }
                    }
                }
                case MYSQL -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT MAX(COLUMN_NAME), MAX(CARDINALITY) FROM information_schema.STATISTICS "
                                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                                    + "GROUP BY INDEX_NAME HAVING COUNT(*) = 1")) {
                        ps.setString(1, table);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                long distinct = rs.getLong(2);
                                if (!rs.wasNull()) {
                                    stats.put(rs.getString(1),
                                            new ColumnStatistics(OptionalLong.of(distinct), OptionalLong.empty()));
                                }
                            }
                        }
                    }
                }
                case DB2 -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT COLNAME, COLCARD, NUMNULLS FROM SYSCAT.COLUMNS "
                                    + "WHERE TABSCHEMA = CURRENT SCHEMA AND TABNAME = ?")) {
                        ps.setString(1, table);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                // -1 is "not collected" for both.
                                OptionalLong distinct = nonNegative(rs.getLong(2));
                                OptionalLong nulls = nonNegative(rs.getLong(3));
                                if (distinct.isPresent() || nulls.isPresent()) {
                                    stats.put(rs.getString(1), new ColumnStatistics(distinct, nulls));
                                }
                            }
                        }
                    }
                }
                default -> {
                    // No per-column estimate that does not mean reading the table.
                }
            }
        } catch (SQLException e) {
            return Map.of();
        }
        return stats;
    }

    /**
     * The table-statistic row of {@link DatabaseMetaData#getIndexInfo}, asked for
     * <em>approximately</em>: a driver that answers it exactly may scan, and one that
     * answers it approximately reads what its catalogue holds.
     */
    private static OptionalLong fromIndexInfo(DatabaseMetaData meta, String table) throws SQLException {
        try (ResultSet rs = meta.getIndexInfo(null, null, table, false, true)) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    return nonNegative(rs.getLong("CARDINALITY"));
                }
            }
        }
        return OptionalLong.empty();
    }

    private static OptionalLong single(Connection conn, String sql, String parameter) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, parameter);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return OptionalLong.empty();
                }
                long value = rs.getLong(1);
                return rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
            }
        }
    }

    private static OptionalLong nonNegative(OptionalLong value) {
        return value.isPresent() ? nonNegative(value.getAsLong()) : value;
    }

    private static OptionalLong nonNegative(long value) {
        return value >= 0 ? OptionalLong.of(value) : OptionalLong.empty();
    }

    /** A name {@code to_regclass} resolves as written, whatever its case. */
    private static String quotePostgres(String table) {
        return '"' + table.replace("\"", "\"\"") + '"';
    }
}

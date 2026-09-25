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
import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;
import com.darkcollective.relix.plan.internal.Dialect;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnStatistics;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * A {@link CatalogProvider} that introspects table schemas from a live database
 * over JDBC, used when a {@code connection.table} reference declares no schema.
 *
 * <p>Columns are read from {@link DatabaseMetaData#getColumns}, mapping SQL types
 * to relix {@link ScalarType}s.  Because identifier case-folding varies by dialect
 * (H2 folds to upper-case, PostgreSQL to lower-case), the table name is tried
 * as-given and then upper- and lower-cased.  Any failure (unreachable database,
 * unknown table) yields {@link Optional#empty()} rather than throwing, so analysis
 * degrades to a clear "schema unavailable" diagnostic.
 *
 * <p>Statistics ({@link #tableStatistics}) supply a {@code COUNT(*)} row count, the
 * primary-key columns, and per-column distinct/null counts (gathered in one
 * aggregate query), all read on demand.  Each lookup opens a fresh connection and
 * performs no caching; connection reuse arrives in a later phase.
 *
 * <p>Connections come from a {@link ConnectionProvider}, so a connection whose
 * coordinates are not a URL can still be introspected.  One analyzer holds one
 * catalog provider for every connection a script declares, including non-JDBC
 * ones; a declaration this provider cannot open is reported as
 * {@link Optional#empty()} — schema unavailable — rather than raised, which is
 * the same degradation an unreachable database already gets.
 */
public final class JdbcCatalogProvider implements CatalogProvider {

    private final ConnectionProvider connections;

    /** Creates a provider opening connections from {@link ConnectionProvider#FROM_URL}. */
    public JdbcCatalogProvider() {
        this(ConnectionProvider.FROM_URL);
    }

    /**
     * Creates a provider opening connections from {@code connections}.
     *
     * @param connections the source of introspection connections; must not be null
     */
    public JdbcCatalogProvider(ConnectionProvider connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
        try (Connection conn = connections.connectionFor(connection)) {
            DatabaseMetaData meta = conn.getMetaData();
            String resolved = resolveTableName(meta, table);
            if (resolved == null) {
                return Optional.empty();
            }
            return Optional.of(new Schema(readColumns(meta, resolved)));
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RelationStatistics> tableStatistics(
            ConnectionDeclaration connection, String table) {
        try (Connection conn = connections.connectionFor(connection)) {
            DatabaseMetaData meta = conn.getMetaData();
            String resolved = resolveTableName(meta, table);
            if (resolved == null) {
                return Optional.empty();
            }
            // The name as the catalog stores it, written as a pushed query writes it, so a
            // table whose name is not a plain identifier is counted rather than skipped.
            Dialect dialect = Dialect.of(connection);
            OptionalLong rows = rowCount(conn, dialect, resolved);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, ColumnStatistics> columnStats = columnStatistics(
                    conn, dialect, resolved, readColumns(meta, resolved), rows.getAsLong());
            return Optional.of(new RelationStatistics(rows, columnStats, primaryKey(meta, resolved)));
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    /**
     * Gathers per-column distinct and null counts in one aggregate query
     * ({@code SELECT COUNT(c), COUNT(DISTINCT c), … FROM table}).  Best-effort: any
     * failure (e.g. an un-aggregatable column type) yields an empty map so the row
     * count and keys are still returned.
     */
    private static Map<String, ColumnStatistics> columnStatistics(
            Connection conn, Dialect dialect, String table, List<ColumnDefinition> columns,
            long rowCount) {
        if (columns.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            String name = dialect.quote(columns.get(i).name());
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("COUNT(").append(name).append("), COUNT(DISTINCT ").append(name).append(")");
        }
        sql.append(" FROM ").append(dialect.table(table));

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql.toString())) {
            if (!rs.next()) {
                return Map.of();
            }
            Map<String, ColumnStatistics> stats = new LinkedHashMap<>();
            int index = 1;
            for (ColumnDefinition col : columns) {
                long nonNull = rs.getLong(index++);
                long distinct = rs.getLong(index++);
                long nulls = Math.max(0L, rowCount - nonNull);
                stats.put(col.name(),
                        new ColumnStatistics(OptionalLong.of(distinct), OptionalLong.of(nulls)));
            }
            return stats;
        } catch (SQLException e) {
            return Map.of();   // column statistics are advisory; keep row count + keys
        }
    }

    /**
     * Resolves {@code table} to the case the catalog actually stores, trying the
     * name as-given then upper- and lower-cased.  Returns null if no such table.
     */
    private static String resolveTableName(DatabaseMetaData meta, String table)
            throws SQLException {
        for (String candidate : List.of(table, table.toUpperCase(), table.toLowerCase())) {
            try (ResultSet rs = meta.getColumns(null, null, candidate, "%")) {
                if (rs.next()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static List<ColumnDefinition> readColumns(DatabaseMetaData meta, String table)
            throws SQLException {
        List<ColumnDefinition> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(null, null, table, "%")) {
            while (rs.next()) {
                columns.add(new ColumnDefinition(
                        rs.getString("COLUMN_NAME"),
                        mapSqlType(rs.getInt("DATA_TYPE"), rs.getString("TYPE_NAME"))));
            }
        }
        return columns;
    }

    /** Runs {@code SELECT COUNT(*)} on a resolved table name; empty on failure. */
    private static OptionalLong rowCount(Connection conn, Dialect dialect, String table) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + dialect.table(table))) {
            return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
        } catch (SQLException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Reads the table's primary key as a single candidate key (ordered by the
     * catalog's {@code KEY_SEQ}); an empty list when the table has no primary key.
     */
    private static List<List<String>> primaryKey(DatabaseMetaData meta, String table)
            throws SQLException {
        TreeMap<Short, String> ordered = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, null, table)) {
            while (rs.next()) {
                ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return ordered.isEmpty() ? List.of() : List.of(List.copyOf(ordered.values()));
    }

    static Connection open(DatabaseConnectionConfig config) throws SQLException {
        if (config.user().isPresent()) {
            return DriverManager.getConnection(
                    config.url(), config.user().get(), config.password().orElse(null));
        }
        return DriverManager.getConnection(config.url());
    }

    /**
     * Maps a column's {@link java.sql.Types} code and the driver's name for its type to
     * the closest relix {@link ScalarType}.
     *
     * <p>The name is consulted for the one family a code does not settle: a timestamp
     * carrying its own offset that a driver reports under a code of its own — SQL
     * Server's {@code datetimeoffset} is {@code -155} — which the code alone would leave
     * as {@code ANY}, a string once read. It is the list the connector reads such a
     * column by, so the catalog and the read cannot disagree about which columns are
     * instants.
     *
     * @param sqlType  the {@code DATA_TYPE} the driver reports
     * @param typeName the {@code TYPE_NAME} the driver reports; may be null
     * @return the relix type
     */
    static ScalarType mapSqlType(int sqlType, String typeName) {
        if (typeName != null && JdbcDataSourceConnector.OFFSET_BEARING_TYPE_NAMES
                .contains(typeName.toLowerCase(java.util.Locale.ROOT))) {
            return ScalarType.TIMESTAMP;
        }
        return mapSqlType(sqlType);
    }

    /** Maps a {@link java.sql.Types} code to the closest relix {@link ScalarType}. */
    static ScalarType mapSqlType(int sqlType) {
        return switch (sqlType) {
            case Types.BIT, Types.BOOLEAN -> ScalarType.BOOLEAN;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> ScalarType.NUMBER;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                 Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB -> ScalarType.STRING;
            // Temporal SQL types map to the typed relix temporal types (ADR-0013).
            // A zone-less SQL TIMESTAMP is read as a UTC instant (Decision 2).
            case Types.DATE -> ScalarType.DATE;
            case Types.TIME -> ScalarType.TIME;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> ScalarType.TIMESTAMP;
            default -> ScalarType.ANY;
        };
    }
}

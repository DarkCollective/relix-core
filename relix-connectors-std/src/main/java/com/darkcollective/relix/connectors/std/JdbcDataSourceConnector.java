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

import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.ast.TemporalLiterals;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A {@link DataSourceConnector} that reads connection-backed tables over JDBC.
 *
 * <p>For a relation declared as {@code source T from conn { … }} or referenced as
 * {@code conn.table}, this connector resolves the relation's
 * {@link ConnectionTableSourceConfig} (from {@link SemanticModel#sources()}) and
 * the matching {@link ConnectionDeclaration} (from {@link SemanticModel#connections()}),
 * opens a JDBC connection, runs {@code SELECT * FROM <table>}, and maps each
 * {@link ResultSet} row to a relix {@link Row} using the requested schema.
 *
 * <p>Rows are streamed lazily: the returned {@link Stream} pulls from the live
 * {@link ResultSet} and, when the stream is closed, closes the {@code ResultSet}
 * and {@link Statement} and <em>releases</em> the {@link Connection} back to a
 * {@link ConnectionPool} for reuse.  The executor closes every source stream
 * (materialising operators via try-with-resources, the terminal stream in
 * {@code QueryExecutor}), so connections are released deterministically.  A driver
 * fetch-size hint is set so drivers that honour it stream from a server-side
 * cursor rather than buffering the whole result.  Credentials are taken from the
 * (already env-substituted) connection configuration.
 *
 * <p>{@link #close()} closes the pool — but only when the connector created it
 * (the {@link #JdbcDataSourceConnector(SemanticModel)} constructor); a pool passed
 * in is owned by the caller.
 */
public final class JdbcDataSourceConnector implements DataSourceConnector {

    /** Driver hint for how many rows to fetch per round-trip while streaming. */
    private static final int FETCH_SIZE = 1_000;

    private final SemanticModel model;
    private final ConnectionPool pool;
    private final boolean ownsPool;
    /** Optional on-demand driver provisioner; null = no provisioning (legacy behaviour). */
    private final DriverProvisioner provisioner;

    /**
     * Creates a connector with its own {@link ConnectionPool}, closed by
     * {@link #close()}.
     *
     * @param model the semantic model whose sources and connections describe each
     *              JDBC-backed relation; must not be null
     */
    public JdbcDataSourceConnector(SemanticModel model) {
        this(model, new ConnectionPool(), true, null);
    }

    /**
     * Creates a connector backed by a shared {@link ConnectionPool}; the pool is
     * owned by the caller and is <em>not</em> closed by {@link #close()}.
     *
     * @param model the semantic model; must not be null
     * @param pool  the shared connection pool; must not be null
     */
    public JdbcDataSourceConnector(SemanticModel model, ConnectionPool pool) {
        this(model, pool, false, null);
    }

    /**
     * Creates a connector (with its own pool) that provisions a missing JDBC driver
     * on demand via {@code provisioner} before failing.
     *
     * @param model       the semantic model; must not be null
     * @param provisioner the driver provisioner consulted when no driver is registered
     *                    for a connection URL; must not be null
     */
    public JdbcDataSourceConnector(SemanticModel model, DriverProvisioner provisioner) {
        this(model, new ConnectionPool(), true, Objects.requireNonNull(provisioner, "provisioner"));
    }

    /**
     * Creates a connector over a shared {@link ConnectionPool} that also provisions a
     * missing JDBC driver on demand — the two halves of the other constructors at once.
     *
     * <p>This is the shape a long-lived host wants: the pool outlives any one query, so
     * it is the caller's and is <em>not</em> closed by {@link #close()}, while a pool
     * built over a {@link ConnectionProvider} that serves live handles is how a bound
     * {@code DataSource} reaches execution.
     *
     * @param model       the semantic model; must not be null
     * @param pool        the shared connection pool; must not be null
     * @param provisioner the driver provisioner consulted when no driver is registered
     *                    for a connection URL; must not be null
     */
    public JdbcDataSourceConnector(SemanticModel model, ConnectionPool pool,
                                   DriverProvisioner provisioner) {
        this(model, pool, false, Objects.requireNonNull(provisioner, "provisioner"));
    }

    private JdbcDataSourceConnector(SemanticModel model, ConnectionPool pool, boolean ownsPool,
                                    DriverProvisioner provisioner) {
        this.model = Objects.requireNonNull(model, "model");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.ownsPool = ownsPool;
        this.provisioner = provisioner;
    }

    @Override
    public void close() {
        if (ownsPool) {
            pool.close();
        }
    }

    @Override
    public Stream<Row> open(String relationName, Schema schema) {
        String key = relationName.toLowerCase(Locale.ROOT);
        SourceDeclaration decl = model.sources().get(key);
        if (decl == null || !(decl.config() instanceof ConnectionTableSourceConfig table)) {
            throw new EvaluationException(
                    "Relation '" + relationName + "' is not a connection-backed table");
        }
        ConnectionDeclaration connection =
                model.connections().get(table.connection().toLowerCase(Locale.ROOT));
        if (connection == null) {
            throw new EvaluationException(
                    "Unknown connection '" + table.connection() + "' for relation '" + relationName + "'");
        }

        String sql = "SELECT * FROM " + table.table();
        return streamQuery(connection, sql, schema, JdbcDataSourceConnector::readByName,
                "JDBC error reading table '" + table.table() + "' on connection '"
                + table.connection() + "'");
    }

    @Override
    public Stream<Row> openQuery(String connectionName, String nativeQuery, Schema schema) {
        ConnectionDeclaration connection =
                model.connections().get(connectionName.toLowerCase(Locale.ROOT));
        if (connection == null) {
            throw new EvaluationException(
                    "Unknown connection '" + connectionName + "' for pushed-down query");
        }
        return streamQuery(connection, nativeQuery, schema, JdbcDataSourceConnector::readByIndex,
                "JDBC error running pushed-down query on connection '" + connectionName + "'");
    }

    /** Reads a row by column name (used for whole-table {@code SELECT *} reads). */
    private static Row readByName(ResultSet rs, Schema schema) throws SQLException {
        List<Value> values = new ArrayList<>(schema.width());
        for (ColumnDefinition col : schema.columns()) {
            values.add(readValue(rs, col));
        }
        return ArrayRow.of(schema, values);
    }

    /** Reads a row by 1-based position (used for pushed-down queries whose select-list order
     *  matches the requested schema). */
    private static Row readByIndex(ResultSet rs, Schema schema) throws SQLException {
        List<ColumnDefinition> cols = schema.columns();
        List<Value> values = new ArrayList<>(cols.size());
        for (int i = 0; i < cols.size(); i++) {
            values.add(readValue(rs, i + 1, cols.get(i)));
        }
        return ArrayRow.of(schema, values);
    }

    /** Reads a single {@link ResultSet} row into a {@link Row}. */
    @FunctionalInterface
    private interface RowReader {
        Row read(ResultSet rs, Schema schema) throws SQLException;
    }

    /**
     * Ensures a JDBC driver is available for {@code url}, failing fast with a clear
     * message otherwise (instead of the cryptic {@code "No suitable driver found"}
     * deep in connection setup).  When a {@link DriverProvisioner} is configured, a
     * missing driver is provisioned on demand (downloaded from the driver catalog,
     * if permitted) before failing; otherwise the user is pointed at the external
     * driver directory, which is how every driver except the bundled H2 is supplied.
     */
    private void requireDriver(String url) {
        if (provisioner != null) {
            DriverProvisioner.Result result = provisioner.provision(url);
            switch (result.outcome()) {
                case ALREADY_AVAILABLE, PROVISIONED -> { /* driver ready */ }
                default -> throw new EvaluationException(
                        "No JDBC driver available for '" + url + "': " + result.message());
            }
            return;
        }
        try {
            DriverManager.getDriver(url);   // throws when no registered driver accepts the URL
        } catch (SQLException noDriver) {
            throw new EvaluationException(
                    "No JDBC driver available for '" + url + "'. Only H2 is bundled; place the"
                    + " driver JAR for this database in the relix driver directory ($RELIX_DRIVERS,"
                    + " default ~/.relix/drivers/) and re-run.");
        }
    }

    /**
     * Borrows a pooled connection, runs the query, and returns a lazy stream that
     * pulls rows from the live {@link ResultSet}.  When the stream is closed, the
     * {@code ResultSet} and {@link Statement} are closed and the connection is
     * released back to the pool.  A setup failure releases the connection and is
     * surfaced as an {@link EvaluationException}.
     *
     * <p>The driver check is skipped for a connection with no {@code url}: that is a
     * bound handle, which opens its own connections, so there is no URL for a driver to
     * accept and nothing for {@code DriverManager} to be asked about. Reading
     * {@code config()} unconditionally is the fourth caller of that shape, and it is the
     * one the pool cannot cover — {@code borrow} already tests for the property rather
     * than projecting the config.
     */
    private Stream<Row> streamQuery(ConnectionDeclaration connection, String sql, Schema schema,
                                    RowReader reader, String context) {
        if (connection.properties().containsKey("url")) {
            requireDriver(connection.config().url());
        }
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            conn = pool.borrow(connection);
            stmt = conn.createStatement();
            stmt.setFetchSize(FETCH_SIZE);
            rs = stmt.executeQuery(sql);
            return resultStream(connection, conn, stmt, rs, schema, reader, context);
        } catch (SQLException e) {
            closeQuietly(rs, stmt);
            pool.release(connection, conn);
            throw new EvaluationException(context + ": " + e.getMessage(), e);
        }
    }

    private Stream<Row> resultStream(ConnectionDeclaration connection, Connection conn, Statement stmt,
                                     ResultSet rs, Schema schema, RowReader reader, String context) {
        Iterator<Row> iterator = new Iterator<>() {
            private Boolean hasNext;   // null = not yet probed

            @Override
            public boolean hasNext() {
                if (hasNext == null) {
                    try {
                        hasNext = rs.next();
                    } catch (SQLException e) {
                        throw new EvaluationException(context + ": " + e.getMessage(), e);
                    }
                }
                return hasNext;
            }

            @Override
            public Row next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                hasNext = null;
                try {
                    return reader.read(rs, schema);
                } catch (SQLException e) {
                    throw new EvaluationException(context + ": " + e.getMessage(), e);
                }
            }
        };
        Spliterator<Row> spliterator = Spliterators.spliteratorUnknownSize(
                iterator, Spliterator.ORDERED | Spliterator.NONNULL);
        return StreamSupport.stream(spliterator, false)
                .onClose(() -> {
                    closeQuietly(rs, stmt);
                    pool.release(connection, conn);   // return the connection for reuse
                });
    }

    /** Closes the given resources in order, ignoring any close failures. */
    private static void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception _) {
                    // best-effort cleanup; a close failure must not mask the real result/error
                }
            }
        }
    }

    private static Value readValue(ResultSet rs, ColumnDefinition col) throws SQLException {
        String name = col.name();
        // JDBC sources are scalar; a nested-typed column is read as a raw object.
        ScalarType type = col.type() instanceof ScalarType s ? s : ScalarType.ANY;
        return switch (type) {
            case NUMBER -> {
                BigDecimal v = rs.getBigDecimal(name);
                yield v == null ? NullValue.INSTANCE : new NumberValue(v);
            }
            case STRING -> {
                String v = rs.getString(name);
                yield v == null ? NullValue.INSTANCE : new StringValue(v);
            }
            case BOOLEAN -> {
                boolean v = rs.getBoolean(name);
                yield rs.wasNull() ? NullValue.INSTANCE : BooleanValue.of(v);
            }
            case ANY -> mapObject(rs.getObject(name));
            // Typed temporal reads (ADR-0013 slice 5).
            case DATE, TIME, TIMESTAMP, DURATION -> readTemporal(rs, name, type, name);
        };
    }

    /** Reads a value by 1-based column index — used for pushed-down queries whose
     *  select-list order matches the requested schema. */
    private static Value readValue(ResultSet rs, int index, ColumnDefinition col) throws SQLException {
        ScalarType type = col.type() instanceof ScalarType s ? s : ScalarType.ANY;
        return switch (type) {
            case NUMBER -> {
                BigDecimal v = rs.getBigDecimal(index);
                yield v == null ? NullValue.INSTANCE : new NumberValue(v);
            }
            case STRING -> {
                String v = rs.getString(index);
                yield v == null ? NullValue.INSTANCE : new StringValue(v);
            }
            case BOOLEAN -> {
                boolean v = rs.getBoolean(index);
                yield rs.wasNull() ? NullValue.INSTANCE : BooleanValue.of(v);
            }
            case ANY -> mapObject(rs.getObject(index));
            // Typed temporal reads (ADR-0013 slice 5).
            case DATE, TIME, TIMESTAMP, DURATION -> readTemporal(rs, index, type, col.name());
        };
    }

    /**
     * Reads a temporal column as the matching typed {@link Value}, addressing the
     * column either by name ({@code String}) or 1-based index ({@code Integer}).
     *
     * <p>{@code DATE}/{@code TIME} read the zone-less civil value; {@code TIMESTAMP}
     * reads a UTC {@link Instant} (a zone-less SQL {@code TIMESTAMP} is interpreted
     * as UTC — ADR-0013 Decision 2; a {@code TIMESTAMP WITH TIME ZONE} carries its
     * own offset). A declared {@code DURATION} column (no standard SQL type maps to
     * it) is read as a driver {@link Duration} or parsed from an ISO-8601 string.
     */
    private static Value readTemporal(ResultSet rs, Object key, ScalarType type,
                                      String colName) throws SQLException {
        return switch (type) {
            case DATE -> {
                LocalDate d = getAs(rs, key, LocalDate.class);
                yield d == null ? NullValue.INSTANCE : new DateValue(d);
            }
            case TIME -> {
                LocalTime t = getAs(rs, key, LocalTime.class);
                yield t == null ? NullValue.INSTANCE : new TimeValue(t);
            }
            case TIMESTAMP -> {
                // Branch on the actual SQL type: a zone-less TIMESTAMP is read as a civil
                // LocalDateTime and interpreted as UTC (Decision 2); a TIMESTAMP WITH TIME
                // ZONE keeps its own offset.  (getObject(Instant.class) can't be used — a
                // driver applies the session zone to a zone-less TIMESTAMP.)
                int idx = key instanceof Integer i ? i : rs.findColumn((String) key);
                if (carriesOffset(rs.getMetaData(), idx)) {
                    java.time.OffsetDateTime odt = rs.getObject(idx, java.time.OffsetDateTime.class);
                    yield odt == null ? NullValue.INSTANCE : new TimestampValue(odt.toInstant());
                }
                java.time.LocalDateTime ldt = rs.getObject(idx, java.time.LocalDateTime.class);
                yield ldt == null ? NullValue.INSTANCE
                        : new TimestampValue(ldt.toInstant(java.time.ZoneOffset.UTC));
            }
            case DURATION -> {
                Object raw = getObject(rs, key);
                if (raw == null) yield NullValue.INSTANCE;
                if (raw instanceof Duration d) yield new DurationValue(d);
                try {
                    yield new DurationValue(TemporalLiterals.parseDuration(raw.toString()));
                } catch (DateTimeException e) {
                    throw new EvaluationException(
                            "JDBC: cannot read column '" + colName + "' as DURATION (got '"
                            + raw + "')");
                }
            }
            default -> throw new IllegalStateException("not a temporal type: " + type);
        };
    }

    /**
     * The type names a driver uses for a timestamp that carries its own offset, where
     * it does not also report {@link java.sql.Types#TIMESTAMP_WITH_TIMEZONE}.
     *
     * <p>There is exactly one, and it is PostgreSQL's: pgjdbc maps {@code timestamptz}
     * to {@code Types.TIMESTAMP} — the same code it gives {@code timestamp without time
     * zone} — so the type code alone cannot tell the two apart on the database where the
     * distinction matters most. Read as a {@code LocalDateTime}, which is what the
     * zone-less branch does, the driver refuses the column outright rather than
     * answering wrongly, so before this list a declared {@code TIMESTAMP} over a
     * {@code timestamptz} column was a query that could not run at all.
     *
     * <p>It holds what has been observed against a real driver and nothing else. A name
     * added from documentation would be the same claim this list exists to stop being
     * made.
     */
    private static final Set<String> OFFSET_BEARING_TYPE_NAMES = Set.of("timestamptz");

    /**
     * Whether column {@code idx} carries its own UTC offset, as opposed to being a civil
     * timestamp the engine reads as UTC (ADR-0013 Decision 2).
     *
     * @param meta the result set's metadata
     * @param idx  the 1-based column index
     * @return whether the column's value is an absolute instant in its own right
     */
    private static boolean carriesOffset(java.sql.ResultSetMetaData meta, int idx)
            throws SQLException {
        if (meta.getColumnType(idx) == java.sql.Types.TIMESTAMP_WITH_TIMEZONE) {
            return true;
        }
        String name = meta.getColumnTypeName(idx);
        return name != null
                && OFFSET_BEARING_TYPE_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /** {@code getObject(key, type)} addressing the column by name or 1-based index. */
    private static <T> T getAs(ResultSet rs, Object key, Class<T> type) throws SQLException {
        return key instanceof Integer i ? rs.getObject(i, type) : rs.getObject((String) key, type);
    }

    /** {@code getObject(key)} addressing the column by name or 1-based index. */
    private static Object getObject(ResultSet rs, Object key) throws SQLException {
        return key instanceof Integer i ? rs.getObject(i) : rs.getObject((String) key);
    }

    private static Value mapObject(Object value) {
        if (value == null) {
            return NullValue.INSTANCE;
        }
        if (value instanceof Number n) {
            return new NumberValue(new BigDecimal(n.toString()));
        }
        if (value instanceof Boolean b) {
            return BooleanValue.of(b);
        }
        return new StringValue(value.toString());
    }
}

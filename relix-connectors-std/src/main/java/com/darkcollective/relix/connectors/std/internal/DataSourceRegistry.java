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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds connection <em>names</em> to live JDBC {@link DataSource} handles, so a
 * program can hand the engine a database it already has open.
 *
 * <p>The AST models a connection declaratively — a name, a connector type and a
 * property map — and the engine's own modules cannot name {@code java.sql} at
 * all.  A live handle therefore cannot ride in the AST, and does not need to:
 * <strong>the AST names the connection and this registry maps that name to the
 * handle</strong>, the same shape {@code PlanEstimates} uses to sit beside a plan
 * rather than on it.
 *
 * <p>It is a {@link ConnectionProvider}, so it drops into both places that need a
 * connection.  A name it does not hold is delegated to
 * {@link ConnectionProvider#FROM_URL}, which is what lets one session mix
 * injected handles with connections a script declares in the ordinary way.
 *
 * <h2>Why a {@code DataSource} and not a {@code Connection}</h2>
 * <p>A single {@link Connection} is not accepted, including as a convenience.
 * The JDBC connector's row stream is lazy over a live {@code ResultSet} and holds
 * its connection until the stream closes, so any join with both sides on one
 * source opens two scans concurrently: one connection would either serialize or
 * corrupt them.  The failure would appear on the first join, long after the call
 * site that chose it.  A {@code DataSource} hands out as many as the query needs
 * and does its own pooling — which is why {@link #managesPooling} is {@code true}
 * for every registered name, and releasing closes rather than retaining.
 *
 * <h2>Dialect</h2>
 * <p>{@link ConnectionDeclaration}s normally get their dialect from a declared
 * {@code dialect:} or from the JDBC URL, and an injected handle has neither.  It
 * is read instead from {@link java.sql.DatabaseMetaData#getDatabaseProductName()}
 * — once per name, memoized, because it costs a connection.  Getting it wrong
 * costs pushdown, never correctness, so a probe that fails is simply no answer
 * and the generic dialect applies.
 *
 * <h2>Thread safety</h2>
 * <p>Registration is expected during setup and lookup during execution; the maps
 * are concurrent, so a late registration is safe but races with an in-flight
 * query the way any late configuration change would.
 */
public final class DataSourceRegistry implements ConnectionProvider {

    private final Map<String, DataSource> sources = new ConcurrentHashMap<>();
    private final Map<String, String> declaredDialects = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> probedDialects = new ConcurrentHashMap<>();
    private final ConnectionProvider fallback;

    /** Creates an empty registry delegating unregistered names to {@link ConnectionProvider#FROM_URL}. */
    public DataSourceRegistry() {
        this(ConnectionProvider.FROM_URL);
    }

    /**
     * Creates an empty registry delegating unregistered names to {@code fallback}.
     *
     * @param fallback the provider for names this registry does not hold; must not be null
     */
    public DataSourceRegistry(ConnectionProvider fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /**
     * Binds {@code name} to {@code dataSource}, replacing any previous binding.
     *
     * @param name       the connection name as the script or session spells it; must not be blank
     * @param dataSource the live handle; must not be null
     * @return this registry, for chaining
     */
    public DataSourceRegistry register(String name, DataSource dataSource) {
        sources.put(key(name), Objects.requireNonNull(dataSource, "dataSource"));
        return this;
    }

    /**
     * Binds {@code name} to {@code dataSource} with an explicit dialect, skipping
     * the metadata probe.
     *
     * @param name       the connection name; must not be blank
     * @param dataSource the live handle; must not be null
     * @param dialect    the dialect token (e.g. {@code "postgres"}); must not be null
     * @return this registry, for chaining
     */
    public DataSourceRegistry register(String name, DataSource dataSource, String dialect) {
        declaredDialects.put(key(name), Objects.requireNonNull(dialect, "dialect"));
        return register(name, dataSource);
    }

    /**
     * Returns whether {@code name} is bound to a handle here.
     *
     * @param name the connection name
     * @return whether this registry serves that name
     */
    public boolean holds(String name) {
        return name != null && sources.containsKey(key(name));
    }

    @Override
    public Connection connectionFor(ConnectionDeclaration connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        DataSource source = sources.get(key(connection.name()));
        return source == null ? fallback.connectionFor(connection) : source.getConnection();
    }

    @Override
    public boolean managesPooling(ConnectionDeclaration connection) {
        return connection != null && holds(connection.name());
    }

    /**
     * The dialect token for {@code name}: the one registered explicitly, else one
     * read from the database's own metadata, else empty.
     *
     * <p>The probe opens a connection, so it runs once per name and its answer —
     * including "could not tell" — is remembered.
     *
     * @param name the connection name
     * @return the dialect token, or empty when unknown
     */
    public Optional<String> dialectFor(String name) {
        String k = key(name);
        String declared = declaredDialects.get(k);
        if (declared != null) {
            return Optional.of(declared);
        }
        if (!sources.containsKey(k)) {
            return Optional.empty();
        }
        return probedDialects.computeIfAbsent(k, this::probeDialect);
    }

    /**
     * Builds the {@link ConnectionDeclaration} that names {@code name} to the
     * engine, carrying the resolved dialect so the planner reaches the same answer
     * it would for a declared connection.
     *
     * <p>It carries no {@code url}: there is none, and that is the case the
     * {@link ConnectionProvider} seam exists to make representable.
     *
     * @param name the connection name; must be registered
     * @return the declaration to install in a session
     * @throws IllegalArgumentException if no handle is bound to that name
     */
    public ConnectionDeclaration declarationFor(String name) {
        if (!holds(name)) {
            throw new IllegalArgumentException("no DataSource registered for connection '" + name + "'");
        }
        Map<String, String> properties = new LinkedHashMap<>();
        dialectFor(name).ifPresent(d -> properties.put("dialect", d));
        return new ConnectionDeclaration(
                true, key(name), "jdbc", properties, SourceLocation.UNKNOWN);
    }

    private Optional<String> probeDialect(String name) {
        try (Connection conn = sources.get(name).getConnection()) {
            return dialectToken(conn.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            return Optional.empty();   // no dialect is the generic dialect, which is always safe
        }
    }

    /**
     * Maps a JDBC product name to a dialect token the planner understands.
     * A product with no dedicated dialect yields empty, which is the generic one.
     */
    static Optional<String> dialectToken(String productName) {
        if (productName == null) {
            return Optional.empty();
        }
        String lower = productName.toLowerCase(Locale.ROOT);
        if (lower.contains("postgres")) {
            return Optional.of("postgres");
        }
        if (lower.contains("mariadb")) {
            return Optional.of("mariadb");
        }
        if (lower.contains("mysql")) {
            return Optional.of("mysql");
        }
        if (lower.contains("duckdb")) {
            return Optional.of("duckdb");
        }
        return Optional.empty();
    }

    /** Connection names are matched the way the rest of relix matches them: case-insensitively. */
    private static String key(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("connection name must not be blank");
        }
        return name.toLowerCase(Locale.ROOT);
    }
}

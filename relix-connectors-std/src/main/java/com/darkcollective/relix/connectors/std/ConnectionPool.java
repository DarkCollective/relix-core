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
import com.darkcollective.relix.plan.Dialect;
import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A small pool of reusable JDBC {@link Connection}s, keyed by
 * {@link DatabaseConnectionConfig}, so repeated scans and queries against the
 * same connection avoid the cost of opening a fresh physical connection each time.
 *
 * <p>Usage is borrow/release: {@link #borrow} hands out a validated idle
 * connection (or opens a new one), and {@link #release} returns it for reuse.  A
 * released connection that is broken — or that would exceed the per-config idle
 * bound — is closed rather than pooled.  {@link #close()} closes every idle
 * connection; it should be called once the owning connector is done.
 *
 * <p>The pool itself never closes a borrowed connection: the caller decides via
 * {@link #release} (reuse) versus closing it directly (e.g. after a setup
 * failure, where the connection may be in an unknown state).
 *
 * <p>Fresh connections come from a {@link ConnectionProvider}, so what a
 * connection <em>is</em> stays out of the pool.  A provider that
 * {@linkplain ConnectionProvider#managesPooling manages its own pooling} is not
 * pooled again on top: those connections are opened on demand and closed on
 * release, which is what returns them to the pool that actually owns them.  The
 * idle map is keyed by the JDBC coordinates rather than by the declaration, so
 * two declarations naming the same database still share, exactly as before — and
 * a provider-pooled connection needs no key at all, since nothing is retained.
 *
 * <h2>Thread safety</h2>
 * <p>Backed by concurrent collections and safe for concurrent borrow/release.
 * The per-config idle bound is a soft limit (a brief concurrent overshoot is
 * harmless).  {@link #close()} is intended to run once, after execution has
 * finished using the pool.
 */
public final class ConnectionPool implements AutoCloseable {

    /** Default maximum number of idle connections kept per distinct connection config. */
    static final int DEFAULT_MAX_IDLE = 8;

    /** Seconds allowed for the {@link Connection#isValid} liveness check. */
    private static final int VALIDATE_TIMEOUT_SECONDS = 2;

    private final int maxIdlePerConfig;
    private final ConnectionProvider provider;
    private final Map<DatabaseConnectionConfig, Deque<Connection>> idle = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * Creates a pool with the default per-config idle bound ({@value #DEFAULT_MAX_IDLE}),
     * opening fresh connections from {@link ConnectionProvider#FROM_URL}.
     */
    public ConnectionPool() {
        this(DEFAULT_MAX_IDLE, ConnectionProvider.FROM_URL);
    }

    /**
     * Creates a pool with the default idle bound, opening fresh connections from
     * {@code provider}.
     *
     * @param provider the source of new connections; must not be null
     */
    public ConnectionPool(ConnectionProvider provider) {
        this(DEFAULT_MAX_IDLE, provider);
    }

    /**
     * Creates a pool keeping at most {@code maxIdlePerConfig} idle connections per
     * distinct connection config.
     *
     * @param maxIdlePerConfig the per-config idle bound; must be positive
     */
    public ConnectionPool(int maxIdlePerConfig) {
        this(maxIdlePerConfig, ConnectionProvider.FROM_URL);
    }

    /**
     * Creates a pool keeping at most {@code maxIdlePerConfig} idle connections per
     * distinct connection config, opening fresh ones from {@code provider}.
     *
     * @param maxIdlePerConfig the per-config idle bound; must be positive
     * @param provider         the source of new connections; must not be null
     */
    public ConnectionPool(int maxIdlePerConfig, ConnectionProvider provider) {
        if (maxIdlePerConfig < 1) {
            throw new IllegalArgumentException("maxIdlePerConfig must be >= 1: " + maxIdlePerConfig);
        }
        this.maxIdlePerConfig = maxIdlePerConfig;
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * Borrows a usable connection for {@code connection}: a validated idle one if
     * available, otherwise a freshly opened one from the provider.
     *
     * <p>When the provider manages its own pooling for this declaration, nothing
     * idle is consulted and a connection is opened on demand.
     *
     * @param connection the declared connection; must not be null
     * @return an open connection the caller must {@link #release} or close
     * @throws SQLException if the pool is closed or a new connection cannot be opened
     */
    public Connection borrow(ConnectionDeclaration connection) throws SQLException {
        if (closed) {
            throw new SQLException("connection pool is closed");
        }
        if (!poolable(connection)) {
            return opened(connection);
        }
        Deque<Connection> queue = idle.get(connection.config());
        if (queue != null) {
            Connection conn;
            while ((conn = queue.pollFirst()) != null) {
                if (isUsable(conn)) {
                    return conn;
                }
                closeQuietly(conn);   // discard a stale/broken idle connection
            }
        }
        return opened(connection);
    }

    /**
     * A freshly opened connection, with its session time zone pinned to UTC.
     *
     * <p>Only a fresh one: an idle connection handed back out was pinned when it was
     * opened, and a session setting outlives a borrow.
     *
     * <h2>Why the pool is where this happens</h2>
     * A relix {@code TIMESTAMP} is an instant that the engine reads and computes at UTC.
     * Work the planner folds into SQL is computed by the <em>server</em>, in the
     * session's time zone — so a {@code date_trunc} or an {@code EXTRACT} that crossed
     * the wire answered a question about a different wall clock than the same operator
     * answered here, and a query returned different rows depending on whether it was
     * folded.
     *
     * <p>There is no other channel to say so. PostgreSQL's driver takes the session zone
     * from the client JVM's default and ignores one named in the URL, which made the
     * answer depend on the time zone of the machine the engine ran on. The pool is the
     * one point every JDBC connection passes through, whether relix opened it from a URL
     * or an embedder handed over a {@code DataSource}.
     *
     * <p>That last case is why this is worth stating rather than merely doing: the
     * setting persists on a connection returned to an embedder's own pool. It is applied
     * anyway, because the alternative is an engine whose temporal answers depend on
     * which of two connections it happened to be given.
     *
     * <p>A backend whose spelling of this is unknown — the generic dialect — is left
     * alone: a rejected statement would break the connection, which is a worse failure
     * than the wall clock it would have corrected.
     */
    private Connection opened(ConnectionDeclaration connection) throws SQLException {
        Connection conn = provider.connectionFor(connection);
        Optional<String> pin = Dialect.of(connection).pinSessionToUtcSql();
        if (pin.isEmpty()) {
            return conn;
        }
        try (Statement st = conn.createStatement()) {
            st.execute(pin.get());
        } catch (SQLException rejected) {
            closeQuietly(conn);
            throw rejected;
        }
        return conn;
    }

    /**
     * Returns a connection for reuse.  A broken connection, or one beyond the idle
     * bound, is closed instead of pooled.  Passing {@code null} is a no-op.
     *
     * <p>When the provider manages its own pooling for this declaration the
     * connection is closed rather than retained — that is what returns it to the
     * pool that owns it.
     *
     * @param connection the declaration the connection belongs to
     * @param conn       the connection to release; may be null
     */
    public void release(ConnectionDeclaration connection, Connection conn) {
        if (conn == null) {
            return;
        }
        if (closed || !poolable(connection) || !isUsable(conn)) {
            closeQuietly(conn);
            return;
        }
        Deque<Connection> queue =
                idle.computeIfAbsent(connection.config(), k -> new ConcurrentLinkedDeque<>());
        if (queue.size() >= maxIdlePerConfig) {
            closeQuietly(conn);
            return;
        }
        queue.offerFirst(conn);
    }

    /** Closes every idle connection.  Borrowed connections are unaffected. */
    @Override
    public void close() {
        closed = true;
        for (Deque<Connection> queue : idle.values()) {
            Connection conn;
            while ((conn = queue.pollFirst()) != null) {
                closeQuietly(conn);
            }
        }
        idle.clear();
    }

    /**
     * Whether this pool may retain connections for {@code connection}.
     *
     * <p>Two reasons it may not, and the second is not a policy choice.  The
     * provider may already pool them, in which case retaining would be pooling on
     * top of a pool.  Or the declaration may carry no JDBC {@code url} at all —
     * a connection whose coordinates are a handle, or one that is not JDBC — and
     * the idle map is keyed by those coordinates, so there is nothing to key on.
     * Reading {@link ConnectionDeclaration#config()} to find that out is what this
     * check exists to avoid: it raises {@link IllegalStateException}, which is not
     * what a JDBC caller catches.
     */
    private boolean poolable(ConnectionDeclaration connection) {
        return connection.properties().containsKey("url")
                && !provider.managesPooling(connection);
    }

    private static boolean isUsable(Connection conn) {
        try {
            return !conn.isClosed() && conn.isValid(VALIDATE_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            return false;
        }
    }

    private static void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
    }
}

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
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.internal.ConnectorRegistry;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.Schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link CatalogProvider} that asks the <em>connector</em> to describe its own tables.
 *
 * <p>{@link RelixConnector} has declared schema introspection since the SPI shipped, and
 * nothing called it: the only live catalog provider opened a JDBC connection, so a dotted
 * reference resolved its columns for a {@code jdbc} connection and for no other type. A
 * capability nothing reaches reads as supported and is not — so this dispatches by the
 * connection's own type token and lets any connector that can describe itself do so.
 *
 * <p>It is a <strong>decorator</strong>: what a connector declines falls through to the
 * delegate, which is how JDBC keeps the introspection it already had. That path is worth
 * more than a schema lookup — it maps SQL types, reads statistics, and serves a bound
 * {@code DataSource} as readily as a declared URL — so it is composed with rather than
 * replaced.
 *
 * <h2>Offline</h2>
 * A connector that cannot reach what it describes answers empty, and so does this, because
 * analysing with nothing reachable is a supported mode rather than a failure. A missing
 * file and an unreachable database degrade the same way.
 *
 * <h2>Lifetime</h2>
 * The registry is built once and closed with this provider, not per call: a
 * {@link ConnectorRegistry} costs a {@link java.util.ServiceLoader} scan and a
 * plugin-directory read, and a catalog is consulted once per dotted reference in a script
 * rather than once per script.
 */
public final class ConnectorCatalogProvider implements CatalogProvider, AutoCloseable {

    private final ConnectorRegistry registry;
    private final CatalogProvider delegate;

    /**
     * A provider over the discovered connectors, falling back to {@code delegate}.
     *
     * @param delegate answers what no connector describes; must not be null
     */
    public ConnectorCatalogProvider(CatalogProvider delegate) {
        this(ConnectorRegistry.create(), delegate);
    }

    /**
     * A provider over an explicit registry — for a test, or a host that assembled its own.
     *
     * @param registry the connectors to ask; must not be null, and is closed with this
     * @param delegate answers what no connector describes; must not be null
     */
    public ConnectorCatalogProvider(ConnectorRegistry registry, CatalogProvider delegate) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
        Optional<Schema> own = connector(connection)
                .flatMap(c -> c.tableSchema(config(connection), table));
        return own.isPresent() ? own : delegate.tableSchema(connection, table);
    }

    @Override
    public Optional<RelationStatistics> tableStatistics(ConnectionDeclaration connection,
                                                        String table) {
        Optional<RelationStatistics> own = connector(connection)
                .flatMap(c -> c.tableStatistics(config(connection), table));
        return own.isPresent() ? own : delegate.tableStatistics(connection, table);
    }

    /** No connector enumerates its tables, so this is the delegate's answer. */
    @Override
    public Optional<List<String>> tables(ConnectionDeclaration connection) {
        return delegate.tables(connection);
    }

    private Optional<RelixConnector> connector(ConnectionDeclaration connection) {
        return connection == null ? Optional.empty() : registry.forType(connection.connectorType());
    }

    /**
     * {@return the connection's properties as connector configuration} Read straight from
     * the declaration rather than through {@code config()}, which requires a {@code url}
     * and raises without one — a requirement that belongs to JDBC and not to every
     * connector a connection can name.
     */
    private static ConnectorConfig config(ConnectionDeclaration connection) {
        return new ConnectorConfig(connection.properties());
    }

    @Override
    public void close() {
        registry.close();
    }
}

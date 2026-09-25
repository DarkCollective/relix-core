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
package com.darkcollective.relix.processor.connector;

import com.darkcollective.relix.processor.connector.internal.ConnectorPluginLoader;
import com.darkcollective.relix.processor.connector.internal.ConnectorRegistry;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.Schema;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The plug-in boundary for an external data source — the open seam that replaces
 * the closed CSV/JSON/JDBC dispatch.
 *
 * <p>A connector handles one or more <em>type tokens</em> (the word after
 * {@code from} in a {@code connection} declaration — {@code "jdbc"}, {@code "csv"},
 * {@code "http"}, {@code "mongodb"}); the {@link ConnectorRegistry} dispatches to
 * it by that token.  All backend-specific configuration arrives per call as a
 * type-agnostic {@link ConnectorConfig}, so a connector is stateless with respect
 * to any one query and need not see the semantic model.
 *
 * <p>Capabilities are expressed by which optional methods a connector overrides,
 * not by boolean flags: schema introspection ({@link #tableSchema}) and statistics
 * ({@link #tableStatistics}) default to "unavailable", and pushdown
 * ({@link #openQuery}) defaults to "unsupported".  A SQL backend overrides
 * {@code openQuery}; a document store overrides {@code open} only.
 *
 * <p>Built-in connectors are discovered via {@link java.util.ServiceLoader} from
 * the module path; external connectors are loaded by {@link ConnectorPluginLoader}
 * from the connector directory ({@code ~/.relix/connectors/}).  Implementations
 * must therefore have a public no-argument constructor.
 */
public interface RelixConnector extends AutoCloseable {

    /**
     * The type tokens this connector handles, lower-cased — e.g. {@code {"jdbc"}},
     * {@code {"csv"}}, {@code {"mongodb"}}.  The registry uses these for dispatch.
     *
     * @return a non-empty set of handled type tokens
     */
    Set<String> handles();

    /**
     * Opens a stream of rows for a named table/collection/endpoint.  The caller
     * closes the returned stream.
     *
     * @param config the connector configuration for this relation
     * @param table  the table/collection/endpoint name within the source
     * @param schema the expected output schema (column order and types)
     * @return a stream of rows; the caller is responsible for closing it
     */
    Stream<Row> open(ConnectorConfig config, String table, Schema schema);

    /**
     * Introspects the schema of a table, when the backend supports it.  The
     * default returns empty — "not available", in which case the declared schema
     * is used as-is.
     *
     * @param config the connector configuration
     * @param table  the table/collection name
     * @return the introspected schema, or empty when unavailable
     */
    default Optional<Schema> tableSchema(ConnectorConfig config, String table) {
        return Optional.empty();
    }

    /**
     * Returns cardinality/column statistics for a table, when available, to feed
     * the cost model.  The default returns empty.
     *
     * @param config the connector configuration
     * @param table  the table/collection name
     * @return statistics, or empty when unavailable
     */
    default Optional<RelationStatistics> tableStatistics(ConnectorConfig config, String table) {
        return Optional.empty();
    }

    /**
     * Runs a pushed-down native query and streams the result — SQL text for a JDBC
     * backend, an aggregation pipeline for a document store.  The default rejects
     * pushdown, so a connector that does not support it never needs to implement
     * this (and the planner simply never produces a pushed query for it).
     *
     * @param config the connector configuration
     * @param query  the backend-native query (e.g. SQL)
     * @param schema the expected output schema; columns read in this order
     * @return a stream of rows; the caller is responsible for closing it
     * @throws UnsupportedOperationException if this connector has no pushdown
     */
    default Stream<Row> openQuery(ConnectorConfig config, String query, Schema schema) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support query pushdown");
    }

    /**
     * Releases any resources the connector holds (connection pools, clients).
     * The default does nothing.
     */
    @Override
    default void close() {
        // no resources by default
    }
}

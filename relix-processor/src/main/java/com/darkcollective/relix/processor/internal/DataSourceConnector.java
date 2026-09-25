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
package com.darkcollective.relix.processor.internal;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.symbol.Schema;

import java.util.stream.Stream;

/**
 * Plug-in boundary between the query executor and an external data source.
 *
 * <p>Implementations supply a {@link Stream} of {@link Row}s for a named
 * relation backed by data that lives outside the {@code .relix} script itself
 * — for example, a JDBC database table or an HTTP/CSV endpoint.
 *
 * <p>{@link com.darkcollective.relix.symbol.relation.InlineRelationSymbol inline
 * relations} are served directly from the symbol table without going through
 * this interface; the executor only calls a connector for
 * {@link com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol database}
 * and
 * {@link com.darkcollective.relix.symbol.relation.SourceRelationSymbol source}
 * relations.
 *
 * <p>The returned stream must be closed by the caller after use.  Connector
 * implementations should wrap their underlying resources (JDBC
 * {@code ResultSet}, HTTP response body, etc.) in a stream that releases those
 * resources on close.
 *
 * <p>A connector is free to choose either shape, and both are in the tree: the
 * CSV, JSON, and HTTP connectors read their whole payload before building a
 * stream, so theirs holds nothing; the JDBC connector's is lazy over a live
 * {@code ResultSet} and holds that {@code ResultSet}, its {@code Statement}, and a
 * pooled {@code Connection} until closed.  Because the lazy shape exists, the
 * engine treats <em>every</em> row stream as a resource — see the child-stream
 * lifecycle rule on {@code ChildDispatch}, which is what makes a
 * blocking operator close the input it drains.
 *
 * <p>This is a functional interface: simple connectors can be supplied as
 * lambdas.
 *
 * <h2>Native-query pushdown</h2>
 * When the planner folds a selection/projection/limit sub-tree over a database
 * connection into a single native query, it emits a
 * {@link com.darkcollective.relix.plan.PhysicalNode.PushedScan} and the executor
 * calls {@link #openQuery}.  The default implementation throws, so only connectors
 * that understand the native query language (e.g. the JDBC connector) need to
 * override it; a plan over a pushdown-incapable connector simply never produces a
 * {@code PushedScan}.
 *
 * @see ExecutionContext#inlineOnly(com.darkcollective.relix.semantic.SemanticModel)
 */
@FunctionalInterface
public interface DataSourceConnector extends AutoCloseable {

    /**
     * Opens a stream of rows for the named relation.
     *
     * @param relationName the canonical (lower-cased) relation name
     * @param schema       the expected schema; may be used to map source columns
     *                     to the correct positions and types
     * @return a stream of rows; the caller is responsible for closing it
     * @throws EvaluationException if the relation cannot be opened
     */
    Stream<Row> open(String relationName, Schema schema);

    /**
     * Releases any resources the connector holds (e.g. a pool of database
     * connections).  The default does nothing, so simple lambda connectors need
     * not implement it; resource-backed connectors override it.  Overrides do not
     * throw checked exceptions, so {@code try}-with-resources needs no extra catch.
     */
    @Override
    default void close() {
        // no resources by default
    }

    /**
     * Runs a pushed-down native query on a named connection and streams the result.
     *
     * <p>Columns are read positionally, in {@code schema} order, so the query's
     * result column order must match {@code schema}.  The default implementation
     * rejects pushdown; connectors that support native-query execution override it.
     *
     * @param connection  the canonical name of the connection to run the query on
     * @param nativeQuery the backend-native query text (SQL for JDBC, etc.)
     * @param schema      the expected output schema; columns are read by position
     * @return a stream of rows; the caller is responsible for closing it
     * @throws EvaluationException if the query cannot be run
     */
    default Stream<Row> openQuery(String connection, String nativeQuery, Schema schema) {
        throw new EvaluationException(
                "This data source does not support native-query pushdown (connection '" + connection + "')");
    }

    /**
     * Runs a pushed-down native query, dispatching by the connection's
     * {@code connectorType}: a SQL string for a {@code jdbc} connection, an
     * aggregation pipeline for a {@code mongodb} connection, etc.  The default ignores
     * the type and delegates to {@link #openQuery(String, String, Schema)}, so a
     * single-backend connector (e.g. the JDBC connector) need only override the
     * three-argument form; a composite connector overrides this to route by type.
     *
     * @param connectorType the connection's connector type token (e.g. {@code "jdbc"},
     *                      {@code "mongodb"})
     * @param connection    the canonical name of the connection to run the query on
     * @param nativeQuery   the backend-native query text
     * @param schema        the expected output schema
     * @return a stream of rows; the caller is responsible for closing it
     * @throws EvaluationException if the query cannot be run
     */
    default Stream<Row> openQuery(String connectorType, String connection, String nativeQuery, Schema schema) {
        return openQuery(connection, nativeQuery, schema);
    }
}

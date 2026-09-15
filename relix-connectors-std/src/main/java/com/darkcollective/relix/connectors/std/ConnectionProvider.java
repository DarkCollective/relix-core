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

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Turns a declared connection into a live JDBC {@link Connection}.
 *
 * <p>Exactly two places need one, and until this seam existed both reached the
 * same static opener: catalog introspection during analysis
 * ({@link JdbcCatalogProvider}) and query execution
 * ({@link ConnectionPool#borrow}).  Both began at
 * {@link ConnectionDeclaration#config()}, which requires a {@code url} — so a
 * connection whose coordinates are <em>not</em> a URL could not be served at all,
 * and failed during analysis rather than at the point of use.
 *
 * <p>The declaration is the argument rather than the projected
 * {@code DatabaseConnectionConfig} for exactly that reason: an implementation
 * that resolves a connection by <em>name</em> needs the name, and the projection
 * discards it.
 *
 * <h2>Pooling</h2>
 * <p>{@link #managesPooling} tells {@link ConnectionPool} whether a connection
 * from this provider is already pooled somewhere else.  It is asked per
 * declaration, not per provider, because one session may mix the two — a
 * provider may serve some connections from its own registry and delegate the rest
 * here.  When it answers {@code true} the engine's pool retains nothing: it opens
 * on demand and closes on release, which is what hands the connection back to
 * whatever pool actually owns it.
 */
@FunctionalInterface
public interface ConnectionProvider {

    /**
     * Opens a connection for the given declaration.
     *
     * @param connection the declared connection; must not be null
     * @return an open connection the caller owns
     * @throws SQLException if no connection can be opened for this declaration
     */
    Connection connectionFor(ConnectionDeclaration connection) throws SQLException;

    /**
     * Whether connections this provider returns for {@code connection} are pooled
     * by something other than {@link ConnectionPool}, so the engine must not
     * retain them.
     *
     * @param connection the declared connection
     * @return {@code true} to bypass engine-side pooling; {@code false} by default
     */
    default boolean managesPooling(ConnectionDeclaration connection) {
        return false;
    }

    /**
     * The default provider: opens a fresh connection from the declaration's
     * {@code url} through {@code DriverManager}, which is the behaviour every
     * caller had before this seam existed.
     *
     * <p>A declaration with no {@code url} yields a {@link SQLException} rather
     * than the {@link IllegalStateException} {@link ConnectionDeclaration#config()}
     * raises.  That distinction is the point: "this is not a JDBC connection" is a
     * condition a JDBC caller must be able to handle, and every caller here
     * already degrades on {@code SQLException} — introspection to "schema
     * unavailable", execution to an evaluation error naming the connection.
     */
    ConnectionProvider FROM_URL = connection -> {
        if (!connection.properties().containsKey("url")) {
            throw new SQLException("connection '" + connection.name() + "' (type '"
                    + connection.connectorType() + "') has no 'url' property");
        }
        return JdbcCatalogProvider.open(connection.config());
    };
}

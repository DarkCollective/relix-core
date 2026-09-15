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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A top-level {@code connection NAME from <type> { … }} declaration.
 *
 * <p>A connection introduces a named, typed set of coordinates; tables are then
 * referenced through it (as {@code NAME.table} or via a {@code source … from NAME}
 * binding).  The declaration itself defines no relation — it registers connection
 * metadata for the analyzer, optimizer, and execution engine.
 *
 * <p>The {@code connectorType} token after {@code from} selects the connector: {@code jdbc} (or its
 * permanent alias {@code database}),
 * {@code mongodb}, {@code http}, etc.  Configuration is a type-agnostic
 * {@link #properties() key/value map} — each connector reads the keys it
 * understands ({@code url}/{@code user}/{@code password}/{@code dialect} for JDBC;
 * {@code uri}/{@code database} for MongoDB).  {@link #config()} is a convenience
 * view that projects the JDBC keys into a {@link DatabaseConnectionConfig}.
 *
 * @param exported      whether the connection is re-exported to importing scripts
 * @param name          the connection name; must not be blank
 * @param connectorType the connector type token (lower-cased); must not be blank
 * @param properties    the raw configuration key/value pairs; must not be null
 * @param location      the source location of the {@code connection} keyword
 */
public record ConnectionDeclaration(
        boolean exported,
        String name,
        String connectorType,
        Map<String, String> properties,
        SourceLocation location
) implements Statement {

    public ConnectionDeclaration {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("connection name must not be blank");
        }
        Objects.requireNonNull(connectorType, "connectorType");
        if (connectorType.isBlank()) {
            throw new IllegalArgumentException("connection type must not be blank");
        }
        connectorType = connectorType.toLowerCase(Locale.ROOT);
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        Objects.requireNonNull(location, "location");
    }

    /**
     * Convenience constructor for an exported JDBC connection at an unknown
     * location, from a typed {@link DatabaseConnectionConfig}.  Keeps existing
     * call sites (and tests) working; the connector type is {@code "jdbc"}.
     *
     * @param name   the connection name
     * @param config the JDBC configuration
     */
    public ConnectionDeclaration(String name, DatabaseConnectionConfig config) {
        this(true, name, "jdbc", toProperties(config), SourceLocation.UNKNOWN);
    }

    /**
     * Projects the JDBC-relevant properties into a {@link DatabaseConnectionConfig}.
     * Intended for JDBC consumers; throws when there is no {@code url} (e.g. on a
     * non-JDBC connection that should never reach a JDBC code path).
     *
     * @return the JDBC view of this connection's properties
     */
    public DatabaseConnectionConfig config() {
        String url = properties.get("url");
        if (url == null) {
            throw new IllegalStateException(
                    "connection '" + name + "' (type '" + connectorType + "') has no 'url' property");
        }
        return new DatabaseConnectionConfig(
                url,
                Optional.ofNullable(properties.get("user")),
                Optional.ofNullable(properties.get("password")),
                Optional.ofNullable(properties.get("dialect")));
    }

    private static Map<String, String> toProperties(DatabaseConnectionConfig config) {
        Objects.requireNonNull(config, "config");
        Map<String, String> props = new LinkedHashMap<>();
        props.put("url", config.url());
        config.user().ifPresent(u -> props.put("user", u));
        config.password().ifPresent(p -> props.put("password", p));
        config.dialect().ifPresent(d -> props.put("dialect", d));
        return props;
    }
}

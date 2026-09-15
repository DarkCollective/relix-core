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
package com.darkcollective.relix.lang.ast.source;

import java.util.List;
import java.util.Objects;

/**
 * Configuration for a source bound to a table within a named
 * {@link DatabaseConnectionConfig connection}, e.g.
 * {@code source Orders from sales { table: "orders", schema: { … } }}.
 *
 * <p>Unlike {@link DatabaseSourceConfig} (which embeds its own JDBC URL), this
 * config references a connection by name; the JDBC coordinates come from the
 * matching {@code connection} declaration.
 *
 * <p>In phase 1 a {@code schema} must be declared; later phases will introspect
 * it from the database when omitted (the "hybrid" schema model).
 *
 * <p>An optional {@code references:} block declares foreign-key style
 * relationships from this source's columns to other relations.
 *
 * @param connection the referenced connection name; must not be blank
 * @param table      the table (or view) name within that connection; must not be blank
 * @param columns    the declared output columns; must not be null
 * @param references the declared column references; may be empty
 */
public record ConnectionTableSourceConfig(
        String connection,
        String table,
        List<ColumnSpec> columns,
        List<ColumnReference> references
) implements SourceConfig {

    public ConnectionTableSourceConfig {
        Objects.requireNonNull(connection, "connection");
        if (connection.isBlank()) {
            throw new IllegalArgumentException("connection name must not be blank");
        }
        Objects.requireNonNull(table, "table");
        if (table.isBlank()) {
            throw new IllegalArgumentException("table name must not be blank");
        }
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(references, "references");
        columns = List.copyOf(columns);
        references = List.copyOf(references);
    }

    /** Creates a config with no declared references. */
    public ConnectionTableSourceConfig(String connection, String table, List<ColumnSpec> columns) {
        this(connection, table, columns, List.of());
    }
}

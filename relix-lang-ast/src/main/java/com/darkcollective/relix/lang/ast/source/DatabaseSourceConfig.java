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
 * Configuration for a database-backed source (JDBC table or view).
 *
 * <p>Example:
 * <pre>
 *   source Users from database {
 *       url:   "${DB_URL}",
 *       table: "users",
 *       schema: {
 *           id:    NUMBER,
 *           name:  STRING,
 *           email: STRING
 *       }
 *   };
 * </pre>
 *
 * <p>All columns in a database source are implicitly {@link ColumnDirection#OUT};
 * predicate pushdown to SQL {@code WHERE} clauses is handled by the execution
 * layer based on column names, not by explicit {@code IN} bindings.
 *
 * <p>The {@code url} value may contain {@code ${VAR}} environment references.
 *
 * <p>An optional {@code references:} block declares foreign-key style
 * relationships from this source's columns to other relations:
 * <pre>
 *   references: { customer_id -&gt; Customers.customer_id }
 * </pre>
 *
 * @param url        the JDBC connection URL; must not be blank
 * @param table      the database table or view name; must not be blank
 * @param columns    the ordered column specifications; must not be empty
 * @param references the declared column references; may be empty
 */
public record DatabaseSourceConfig(
        String url,
        String table,
        List<ColumnSpec> columns,
        List<ColumnReference> references
) implements SourceConfig {

    public DatabaseSourceConfig {
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        Objects.requireNonNull(table, "table");
        if (table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        Objects.requireNonNull(references, "references");
        columns = List.copyOf(columns);
        references = List.copyOf(references);
    }

    /** Creates a config with no declared references. */
    public DatabaseSourceConfig(String url, String table, List<ColumnSpec> columns) {
        this(url, table, columns, List.of());
    }
}

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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.symbol.Schema;

import java.util.List;
import java.util.Objects;

/**
 * The fully-materialised result of executing a single {@code query} statement.
 *
 * <p>A {@code QueryResult} is produced by {@link QueryExecutor} for each
 * {@code query} statement in a relix script.  It captures:
 * <ul>
 *   <li>a human-readable {@link #label} suitable for display headers;</li>
 *   <li>the output {@link #schema} (column names and types);</li>
 *   <li>the fully-materialised list of {@link #rows}.</li>
 * </ul>
 *
 * <p>Instances are immutable.  The {@code rows} list is an unmodifiable copy.
 *
 * <p>For named queries ({@code query MyRelation;}), the label is the relation
 * name.  For inline expression queries ({@code query { π name (Users) };}),
 * the label is {@code "<expression N>"} where {@code N} is the 1-based
 * position of the query in the script.
 *
 * @param label  display label for this result; never null or blank
 * @param schema the output schema; never null
 * @param rows   the result rows in order; never null; may be empty
 */
public record QueryResult(String label, Schema schema, List<Row> rows) {

    public QueryResult {
        Objects.requireNonNull(label,  "label");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(rows,   "rows");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        rows = List.copyOf(rows);
    }

    /**
     * Returns {@code true} if this result contains no rows.
     *
     * @return {@code true} when {@link #rows} is empty
     */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * Returns the number of rows in this result.
     *
     * @return the row count; never negative
     */
    public int rowCount() {
        return rows.size();
    }
}

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

import com.darkcollective.relix.processor.internal.DocumentRow;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.symbol.Schema;

import java.util.List;

/**
 * A single tuple produced during query execution — an ordered, named sequence
 * of {@link Value}s whose structure is described by a {@link Schema}.
 *
 * <p>Column lookup by name is case-insensitive, matching the conventions of the
 * rest of the relix system.  Positional access by index is also supported for
 * internal use by operators that iterate columns in order.
 *
 * <p>Implementations must be immutable.
 */
public interface Row {

    /**
     * A row of {@code values} under {@code schema}, one value per column in order.
     *
     * <p>The row a connector produces: a {@code RelixConnector} reads its source and
     * hands the engine rows built here.
     *
     * @param schema the row's columns; must not be null
     * @param values one value per column; must not be null, and its size must match
     * @return the row
     * @throws IllegalArgumentException if the counts differ
     * @since 1.0
     */
    static Row of(Schema schema, List<Value> values) {
        return ArrayRow.of(schema, values);
    }

    /**
     * A row of {@code values} under {@code schema}, one value per column in order.
     *
     * @param schema the row's columns; must not be null
     * @param values one value per column
     * @return the row
     * @throws IllegalArgumentException if the counts differ
     * @since 1.0
     */
    static Row of(Schema schema, Value... values) {
        return ArrayRow.of(schema, values);
    }

    /**
     * Returns the schema that describes this row's columns.
     *
     * @return the row's schema; never {@code null}
     */
    Schema schema();

    /**
     * Returns the value in the named column (case-insensitive).
     *
     * @param columnName the column name to look up
     * @return the value; never {@code null} (absent values use
     *         {@link com.darkcollective.relix.value.NullValue#INSTANCE})
     * @throws IllegalArgumentException if no column with that name exists
     */
    Value get(String columnName);

    /**
     * Returns the value at the given zero-based column index.
     *
     * @param index zero-based column index
     * @return the value; never {@code null}
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    Value get(int index);

    /**
     * Returns the number of columns in this row.
     *
     * @return column count; always ≥ 1
     */
    int width();

    /**
     * Returns this row's column names in order.
     *
     * <p>For a closed-schema row these are the schema's column names.  For an
     * <em>open</em> (schema-on-read) row — see
     * {@link DocumentRow} — the schema declares no columns, so implementations
     * override this to report the document's actual top-level field names.  This
     * is what lets consumers (e.g. the result formatter) enumerate the columns of
     * a heterogeneous, schema-less row.
     *
     * @return the ordered, possibly empty list of column names; never null
     */
    default List<String> columnNames() {
        return schema().columns().stream().map(ColumnDefinition::name).toList();
    }
}

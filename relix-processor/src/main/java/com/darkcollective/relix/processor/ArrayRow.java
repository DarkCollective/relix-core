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

import com.darkcollective.relix.ast.AttributeNames;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.value.ValuePath;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A positional {@link Row} implementation backed by a fixed-size array of {@link Value}s.
 *
 * <p>Column-name lookup scans the schema's column list; for the small schemas typical
 * in relix queries this linear scan is negligible.  Values are stored in declaration
 * order matching {@link Schema#columns()}.
 *
 * <p>Instances are immutable — the value array is defensive-copied on construction.
 *
 * <p>Example:
 * <pre>{@code
 * Schema schema = new Schema(List.of(
 *     new ColumnDefinition("id",   ScalarType.NUMBER),
 *     new ColumnDefinition("name", ScalarType.STRING)
 * ));
 * Row row = ArrayRow.of(schema, NumberValue.of("1"), new StringValue("Alice"));
 * row.get("id");   // NumberValue(1)
 * row.get("NAME"); // StringValue("Alice")
 * row.get(0);      // NumberValue(1)
 * }</pre>
 */
public final class ArrayRow implements Row {

    private final Schema schema;
    private final Value[] values;

    /**
     * Trusted constructor: takes ownership of {@code values} without copying.
     * All callers pass a freshly-allocated array (or, in {@link #withSchema}, the
     * already-immutable backing array of another row), so no defensive copy is
     * required.
     */
    private ArrayRow(Schema schema, Value[] values) {
        this.schema = schema;
        this.values = values;
    }

    /**
     * Creates a row from a list of values in schema-column order.
     *
     * @param schema the row's schema; must not be {@code null}
     * @param values values in declaration order; must not be {@code null}, must match schema width
     * @return a new {@code ArrayRow}
     * @throws IllegalArgumentException if the number of values does not match the schema width
     */
    public static ArrayRow of(Schema schema, List<Value> values) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(values, "values");
        if (values.size() != schema.width()) {
            throw new IllegalArgumentException(
                    "Value count " + values.size() + " does not match schema width " + schema.width());
        }
        Value[] array = values.toArray(new Value[0]);
        for (int i = 0; i < array.length; i++) {
            Objects.requireNonNull(array[i], "values[" + i + "] must not be null");
        }
        return new ArrayRow(schema, array);
    }

    /**
     * Creates a row from varargs values in schema-column order.
     *
     * @param schema the row's schema; must not be {@code null}
     * @param values values in declaration order; must not be {@code null}, must match schema width
     * @return a new {@code ArrayRow}
     * @throws IllegalArgumentException if the number of values does not match the schema width
     */
    public static ArrayRow of(Schema schema, Value... values) {
        return of(schema, Arrays.asList(values));
    }

    /**
     * Returns a row with the same values as this one but a different schema of the
     * same width.  This is a metadata-only operation (e.g. a relation/column
     * rename): the underlying value array is shared, not copied, which is safe
     * because rows are immutable.
     *
     * @param newSchema the replacement schema; must have the same width as this row
     * @return a row exposing this row's values under {@code newSchema}
     * @throws IllegalArgumentException if {@code newSchema} has a different width
     */
    public ArrayRow withSchema(Schema newSchema) {
        Objects.requireNonNull(newSchema, "newSchema");
        if (newSchema.width() != values.length) {
            throw new IllegalArgumentException(
                    "Schema width " + newSchema.width() + " does not match row width " + values.length);
        }
        return new ArrayRow(newSchema, values);
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public Value get(String columnName) {
        Objects.requireNonNull(columnName, "columnName");
        // The four readings of a name, in the order Schema.resolvePath documents.
        // A dotted name is genuinely ambiguous — a relation-qualified reference
        // ("rooms.name") or a path into a nested column ("location.city") — and the
        // schema is what tells them apart. Trying them in the wrong order is not a
        // missing feature but a wrong answer: `skills.name` over a row that carries
        // both a `skills` struct and a top-level `name` used to return the top-level
        // one, silently.

        // 1. The whole name is a column — a delimited identifier that contains a dot.
        int whole = schema.indexOf(columnName);
        if (whole >= 0) {
            return values[whole];
        }
        String qualifier = AttributeNames.qualifierOf(columnName);
        if (qualifier == null) {
            throw new IllegalArgumentException("No column '" + columnName + "' in schema");
        }
        String bare = AttributeNames.stripQualifier(columnName);

        // 2. A relation-qualified reference resolves by source-relation provenance to
        //    the exact column it names — even above a join, where the physical column
        //    may have been renamed ("name_r") to disambiguate a collision.
        List<Integer> matches = schema.qualifiedIndices(qualifier, bare);
        if (matches.size() == 1) {
            return values[matches.get(0)];
        }

        // 3. A path into a struct column, the same access DocumentRow gives an open
        //    relation. The *schema* decides this, not the row: navigation is
        //    null-propagating, so a miss and a genuine NULL are the same value, and
        //    asking the row would make a stale qualifier look like a resolved path.
        //    A path into an ANY column resolves too — schema-on-read has no declared
        //    field to check against, and refusing it would make a nested column
        //    readable only where its shape was declared up front.
        if (schema.resolvePath(columnName).isPresent()) {
            Value nested = navigatePath(columnName);
            if (nested != null) {
                return nested;
            }
        }

        // 4. The bare name, for a schema carrying no provenance for that qualifier
        //    (legacy behaviour).
        int index = schema.indexOf(bare);
        if (index < 0) {
            throw new IllegalArgumentException("No column '" + columnName + "' in schema");
        }
        return values[index];
    }

    /**
     * Resolves {@code path} as {@code <column>.<field>…} against this row, or returns
     * {@code null} when its head names no column. The head is the longest prefix that
     * does: a name is split at its first dot, which is where the validator split it.
     */
    private Value navigatePath(String path) {
        int dot = path.indexOf('.');
        if (dot <= 0 || dot == path.length() - 1) {
            return null;
        }
        int head = schema.indexOf(path.substring(0, dot));
        if (head < 0) {
            return null;
        }
        return ValuePath.navigate(values[head], path.substring(dot + 1));
    }

    @Override
    public Value get(int index) {
        if (index < 0 || index >= values.length) {
            throw new IndexOutOfBoundsException(
                    "Index " + index + " out of bounds for row width " + values.length);
        }
        return values[index];
    }

    @Override
    public int width() {
        return values.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArrayRow other)) return false;
        return schema.equals(other.schema) && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return 31 * schema.hashCode() + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Row{");
        List<ColumnDefinition> cols = schema.columns();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(cols.get(i).name()).append("=").append(values[i].asDisplayString());
        }
        return sb.append('}').toString();
    }
}

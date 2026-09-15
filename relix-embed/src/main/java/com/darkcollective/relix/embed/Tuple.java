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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One row of a result, read as Java types.
 *
 * <p>A row the engine produces is a tuple of {@code Value}s, and {@code Value} carries
 * three methods: whether it is null, what type it is, and how to display it. That is the
 * right surface for a sealed core type — it is a model of a relational value, not a
 * conversion library — and it is thin for someone reading a result.
 *
 * <p>So the conversions live here, on the row view the embedding API hands back. The same
 * argument that puts this API on the frontend side of the engine boundary puts them here:
 * the engine keeps the model, the facade adapts it.
 *
 * <h2>Pattern matching is still the authoritative route</h2>
 *
 * <p>{@code Value} is a sealed hierarchy, so a {@code switch} over it is exhaustive and
 * the compiler will tell you about a case you forgot:
 *
 * {@snippet lang = "java":
 * String describe(Value value) {
 *     return switch (value) {
 *         case NumberValue n -> "number " + n.value();
 *         case StringValue s -> "string " + s.value();
 *         default            -> value.asDisplayString();
 *     };
 * }
 * }
 *
 * <p>The accessors below are a convenience for the common case, not a second authority.
 * Where a column's type genuinely varies — an {@code ANY} column over schema-on-read
 * data — the switch is the tool, because it makes the variation visible.
 *
 * <h2>What an accessor does with a value it did not expect</h2>
 *
 * <p><strong>A NULL comes back as Java {@code null}</strong>, in every accessor, which is
 * why none of them returns a primitive. A relational NULL is an absent value and Java
 * spells that the same way.
 *
 * <p><strong>A value of the wrong type raises.</strong> An accessor names a type, and
 * quietly coercing one type to another would make it a second, weaker definition of what
 * the column holds — the failure would then surface as a wrong answer rather than as a
 * wrong call. The one deliberate accommodation is {@link #string(String)} over a value
 * that is not a string, which is refused for exactly that reason: display formatting is
 * {@link Value#asDisplayString()}, and asking for it by that name says so.
 *
 * @since 1.0
 */
public final class Tuple implements Row {

    private final Row row;

    private Tuple(Row row) {
        this.row = Objects.requireNonNull(row, "row");
    }

    /**
     * Wraps an engine row as a readable tuple.
     *
     * @param row the row; must not be null
     * @return the row as a tuple, or {@code row} itself when it already is one
     */
    static Tuple of(Row row) {
        return row instanceof Tuple tuple ? tuple : new Tuple(row);
    }

    // -------------------------------------------------------------------------
    // The row itself
    // -------------------------------------------------------------------------

    @Override
    public Schema schema() {
        return row.schema();
    }

    @Override
    public Value get(String columnName) {
        return row.get(columnName);
    }

    @Override
    public Value get(int index) {
        return row.get(index);
    }

    @Override
    public int width() {
        return row.width();
    }

    @Override
    public List<String> columnNames() {
        return row.columnNames();
    }

    /**
     * Whether {@code column} holds NULL.
     *
     * <p>Every accessor already answers {@code null} for one, so this is for the case
     * where the distinction is the question rather than an aside.
     *
     * @param column the column name (case-insensitive); must exist
     * @return whether the value is NULL
     * @throws IllegalArgumentException if there is no such column
     * @since 1.0
     */
    public boolean isNull(String column) {
        return get(column).isNull();
    }

    // -------------------------------------------------------------------------
    // Typed access
    // -------------------------------------------------------------------------

    /**
     * {@return the value in {@code column} as a {@code String}, or null when it is NULL}
     *
     * @param column the column name (case-insensitive); must exist
     * @throws RelixException if the value is not a string — {@code Value.asDisplayString}
     *                        is how a value of any type is rendered for display
     *
     * @since 1.0
     */
    public String string(String column) {
        return typed(column, StringValue.class, StringValue::value);
    }

    /**
     * {@return the value in {@code column} as a {@code BigDecimal}, or null when it is NULL}
     *
     * <p>The exact form: a Relix number is a decimal, and this is the accessor that loses
     * nothing.
     *
     * @param column the column name (case-insensitive); must exist
     * @throws RelixException if the value is not a number
     * @since 1.0
     */
    public BigDecimal decimal(String column) {
        return typed(column, NumberValue.class, NumberValue::value);
    }

    /**
     * {@return the value in {@code column} as a {@code Long}, or null when it is NULL}
     *
     * <p>Named for the type rather than spelled {@code long} because {@code long} is a
     * keyword — and boxed, because a NULL has to come back as something.
     *
     * @param column the column name (case-insensitive); must exist
     * @throws RelixException if the value is not a number, or has a fractional part —
     *                        silently truncating one would be the wrong answer rather
     *                        than an approximate one
     *
     * @since 1.0
     */
    public Long longValue(String column) {
        BigDecimal value = decimal(column);
        if (value == null) {
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new RelixException("column '" + column + "' holds " + value
                    + ", which is not a whole number — read it with decimal(...)", e);
        }
    }

    /**
     * {@return the value in {@code column} as a {@code Double}, or null when it is NULL}
     *
     * <p>Lossy by construction, as any decimal-to-binary conversion is. Reach for
     * {@link #decimal(String)} where the exact value matters — money, most obviously.
     *
     * @param column the column name (case-insensitive); must exist
     * @throws RelixException if the value is not a number
     * @since 1.0
     */
    public Double doubleValue(String column) {
        BigDecimal value = decimal(column);
        return value == null ? null : value.doubleValue();
    }

    /**
     * {@return the value in {@code column} as a {@code Boolean}, or null when it is NULL}
     *
     * @param column the column name (case-insensitive); must exist
     * @throws RelixException if the value is not a boolean
     * @since 1.0
     */
    public Boolean booleanValue(String column) {
        return typed(column, BooleanValue.class, BooleanValue::value);
    }

    /**
     * {@return the {@code TIMESTAMP} in {@code column}, or null when it is NULL}
     *
     * @param column the column name (case-insensitive); must exist
     * @throws RelixException if the value is not a timestamp
     * @since 1.0
     */
    public Instant instant(String column) {
        return typed(column, TimestampValue.class, TimestampValue::value);
    }

    /**
     * {@return the {@code DATE} in {@code column}, or null when it is NULL}
     *
     * @param column the column name (case-insensitive); must exist
     * @throws RelixException if the value is not a date
     * @since 1.0
     */
    public LocalDate date(String column) {
        return typed(column, DateValue.class, DateValue::value);
    }

    /**
     * {@return the {@code TIME} in {@code column}, or null when it is NULL}
     *
     * @param column the column name (case-insensitive); must exist
     * @throws RelixException if the value is not a time
     * @since 1.0
     */
    public LocalTime time(String column) {
        return typed(column, TimeValue.class, TimeValue::value);
    }

    /**
     * {@return the {@code DURATION} in {@code column}, or null when it is NULL}
     *
     * @param column the column name (case-insensitive); must exist
     * @throws RelixException if the value is not a duration
     * @since 1.0
     */
    public Duration duration(String column) {
        return typed(column, DurationValue.class, DurationValue::value);
    }

    /**
     * {@return the elements of the array in {@code column}, or null when it is NULL}
     *
     * <p>What a {@code COLLECT} aggregate or an unexploded nested column holds. The
     * elements stay {@code Value}s: an array's elements need not share a type, so there
     * is nothing to convert them to.
     *
     * @param column the column name (case-insensitive); must exist
     * @throws RelixException if the value is not an array
     * @since 1.0
     */
    public List<Value> array(String column) {
        return typed(column, ArrayValue.class, ArrayValue::elements);
    }

    /**
     * {@return the fields of the struct in {@code column}, or null when it is NULL}
     *
     * <p>The fields stay {@code Value}s, for the reason {@link #array(String)}'s elements
     * do. A field of a nested struct is reachable by dotted name from the query itself,
     * which is usually the better place to descend.
     *
     * @param column the column name (case-insensitive); must exist
     * @throws RelixException if the value is not a struct
     * @since 1.0
     */
    public Map<String, Value> struct(String column) {
        return typed(column, StructValue.class, StructValue::fields);
    }

    /**
     * One accessor's whole body: NULL through as {@code null}, the expected kind
     * unwrapped, anything else refused by naming both types.
     */
    private <V extends Value, T> T typed(String column, Class<V> kind,
                                         java.util.function.Function<V, T> read) {
        Value value = get(column);
        if (value.isNull()) {
            return null;
        }
        if (!kind.isInstance(value)) {
            String wanted = kind.getSimpleName().replace("Value", "")
                    .toLowerCase(java.util.Locale.ROOT);
            throw new RelixException("column '" + column + "' holds " + value.type().display()
                    + ", not " + wanted + " — read it with get(...) and match on the type, "
                    + "or render it with get(...).asDisplayString()");
        }
        return read.apply(kind.cast(value));
    }

    @Override
    public String toString() {
        return columnNames().stream()
                .map(name -> name + "=" + get(name).asDisplayString())
                .reduce((a, b) -> a + ", " + b)
                .map(body -> "(" + body + ")")
                .orElse("()");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Tuple other && row.equals(other.row);
    }

    @Override
    public int hashCode() {
        return row.hashCode();
    }
}

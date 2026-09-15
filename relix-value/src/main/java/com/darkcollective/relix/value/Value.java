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
package com.darkcollective.relix.value;

import com.darkcollective.relix.symbol.ScalarType;

/**
 * A runtime scalar value produced or consumed during query execution.
 *
 * <p>The sealed hierarchy maps directly onto the type lattice in
 * {@link ScalarType}:
 * <ul>
 *   <li>{@link StringValue} — {@code STRING}</li>
 *   <li>{@link NumberValue} — {@code NUMBER}</li>
 *   <li>{@link BooleanValue} — {@code BOOLEAN}</li>
 *   <li>{@link NullValue} — absent value, compatible with all types</li>
 *   <li>{@link StructValue} — a nested object; reports {@link ScalarType#ANY}</li>
 *   <li>{@link ArrayValue} — a nested array; reports {@link ScalarType#ANY}</li>
 *   <li>{@link DateValue} — {@code DATE} (a {@link java.time.LocalDate})</li>
 *   <li>{@link TimeValue} — {@code TIME} (a {@link java.time.LocalTime})</li>
 *   <li>{@link TimestampValue} — {@code TIMESTAMP} (a UTC {@link java.time.Instant})</li>
 *   <li>{@link DurationValue} — {@code DURATION} (a {@link java.time.Duration})</li>
 * </ul>
 *
 * <p>Pattern matching over the sealed subtypes is the preferred way to
 * dispatch on value kind:
 * <pre>{@code
 * String display = switch (value) {
 *     case StringValue  s -> s.value();
 *     case NumberValue  n -> n.value().toPlainString();
 *     case BooleanValue b -> String.valueOf(b.value());
 *     case NullValue    n -> "NULL";
 *     default           -> value.asDisplayString();
 * };
 * }</pre>
 *
 * <p>A switch that names every subtype needs no {@code default} and will not compile
 * once a new one is added — which is the point of sealing the hierarchy.
 */
public sealed interface Value
        permits StringValue, NumberValue, BooleanValue, NullValue, StructValue, ArrayValue,
                DateValue, TimeValue, TimestampValue, DurationValue {

    /**
     * Returns {@code true} if this value is null (absent).
     *
     * @return whether this is a {@link NullValue}
     */
    boolean isNull();

    /**
     * Returns the {@link ScalarType} that describes this value's kind.
     * {@link NullValue} returns {@link ScalarType#ANY}.
     *
     * @return the scalar type
     */
    ScalarType type();

    /**
     * Returns a human-readable display string for this value, suitable for
     * output formatting. {@link NullValue} returns {@code "NULL"}.
     *
     * @return display string; never {@code null}
     */
    String asDisplayString();
}

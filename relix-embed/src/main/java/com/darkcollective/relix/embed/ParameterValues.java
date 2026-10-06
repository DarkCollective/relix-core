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

import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A bound parameter's Java value as the engine's, checked against the type the script
 * uses the parameter as.
 */
final class ParameterValues {

    private ParameterValues() {
    }

    /**
     * {@code value} as an engine value, refused when it is not of {@code type}.
     *
     * @param name  the parameter, without its {@code $}, for a diagnostic
     * @param value a number, string, boolean, date, time, instant (or offset or zoned
     *              date-time), duration, an engine {@link Value}, or null
     * @param type  the type the script uses the parameter as; {@code ANY} accepts any
     * @return the engine value
     * @throws RelixException naming the parameter when the value is of no kind a
     *         parameter can hold, or not of {@code type}
     */
    static Value of(String name, Object value, Type type) {
        Value converted = convert(name, value);
        if (!converted.isNull() && type != ScalarType.ANY && !type.equals(converted.type())) {
            throw new RelixException("parameter $" + name + " is compared with a " + type
                    + ", so it cannot be bound to the " + converted.type() + " " + value);
        }
        return converted;
    }

    private static Value convert(String name, Object value) {
        return switch (value) {
            case null -> NullValue.INSTANCE;
            case Value v -> v;
            case BigDecimal d -> new NumberValue(d);
            case BigInteger i -> new NumberValue(new BigDecimal(i));
            case Integer i -> new NumberValue(BigDecimal.valueOf(i));
            case Long l -> new NumberValue(BigDecimal.valueOf(l));
            case Short s -> new NumberValue(BigDecimal.valueOf(s));
            case Byte b -> new NumberValue(BigDecimal.valueOf(b));
            case Double d when Double.isFinite(d) -> new NumberValue(BigDecimal.valueOf(d));
            case Float f when Float.isFinite(f) -> new NumberValue(BigDecimal.valueOf(f));
            case String s -> new StringValue(s);
            case Character c -> new StringValue(c.toString());
            case Boolean b -> BooleanValue.of(b);
            case LocalDate d -> new DateValue(d);
            case LocalTime t -> new TimeValue(t);
            case Instant i -> new TimestampValue(i);
            case OffsetDateTime t -> new TimestampValue(t.toInstant());
            case ZonedDateTime t -> new TimestampValue(t.toInstant());
            case Duration d -> new DurationValue(d);
            default -> throw new RelixException("parameter $" + name + " cannot hold a "
                    + value.getClass().getName() + " (" + value + "); a parameter holds a"
                    + " number, string, boolean, date, time, instant or duration");
        };
    }

    /**
     * Two relations' bindings, for a relation composed of both.
     *
     * @throws RelixException when the two bind one parameter to different values, since
     *         the composition has one parameter of that name and so one value
     */
    static Map<String, Value> merge(Map<String, Value> left, Map<String, Value> right) {
        if (right.isEmpty()) {
            return left;
        }
        Map<String, Value> merged = new HashMap<>(left);
        right.forEach((key, value) -> {
            Value existing = merged.putIfAbsent(key, value);
            if (existing != null && !existing.equals(value)) {
                throw new RelixException("the two relations bind parameter $" + key
                        + " to different values, " + existing.asDisplayString() + " and "
                        + value.asDisplayString() + "; a query has one value per parameter");
            }
        });
        return merged;
    }

    /** {@code name} with any leading {@code $}, lower-cased: how bindings are keyed. */
    static String key(String name) {
        return (name.startsWith("$") ? name.substring(1) : name).toLowerCase(Locale.ROOT);
    }
}

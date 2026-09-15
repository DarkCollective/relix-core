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
package com.darkcollective.relix.function;

import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.CodePoints;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;

import java.util.Comparator;

/**
 * The order over values a {@link FunctionContext} supplies when nothing richer is
 * available — NULL last, and like compared with like.
 *
 * <p>This is a floor rather than the engine's own answer. An engine hands its real
 * comparator to the context, and that one knows more: it reads an ISO-8601 string as
 * the temporal value it spells, so a date that arrived as text from a schema-less
 * source ranks against a typed one. Here, two values of different kinds simply cannot
 * be ranked, and saying so is better than inventing an order between a number and a
 * date.
 *
 * <p>Nested values — a struct, an array — have no order at all and are rejected for
 * the same reason.
 */
final class ValueOrder implements Comparator<Value> {

    /** NULL sorts after every non-NULL value, as SQL's ascending order does. */
    static final Comparator<Value> NULLS_LAST = new ValueOrder();

    private ValueOrder() {
    }

    @Override
    public int compare(Value a, Value b) {
        if (a.isNull()) {
            return b.isNull() ? 0 : 1;
        }
        if (b.isNull()) {
            return -1;
        }
        return switch (a) {
            case NumberValue x when b instanceof NumberValue y -> x.value().compareTo(y.value());
            case StringValue x when b instanceof StringValue y ->
                    // By code point — see CodePoints.compare, and ValueComparator, which
                    // is the engine's own ordering and must agree with this one.
                    CodePoints.compare(x.value(), y.value());
            case BooleanValue x when b instanceof BooleanValue y ->
                    Boolean.compare(x.value(), y.value());
            case DateValue x when b instanceof DateValue y -> x.value().compareTo(y.value());
            case TimeValue x when b instanceof TimeValue y -> x.value().compareTo(y.value());
            case TimestampValue x when b instanceof TimestampValue y ->
                    x.value().compareTo(y.value());
            case DurationValue x when b instanceof DurationValue y ->
                    x.value().compareTo(y.value());
            default -> throw new IllegalArgumentException(
                    "Cannot compare " + a.type() + " with " + b.type());
        };
    }
}

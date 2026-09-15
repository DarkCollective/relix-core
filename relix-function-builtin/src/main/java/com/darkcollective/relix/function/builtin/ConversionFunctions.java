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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.darkcollective.relix.function.builtin.Arguments.parseNumber;
import static com.darkcollective.relix.function.builtin.Arguments.reject;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.ANY;
import static com.darkcollective.relix.symbol.ScalarType.NUMBER;
import static com.darkcollective.relix.symbol.ScalarType.STRING;

/**
 * The conversion built-ins: reading a value as a string or as a number.
 *
 * <p>These are the ones that say no. A conversion is a claim that the value means
 * something in the target type, and NULL, a nested value and a temporal value each mean
 * nothing as a number — so the call fails rather than inventing a zero. That is why
 * {@code IsNumeric} exists to ask first.
 */
final class ConversionFunctions {

    private static final Category CONVERSION = Category.of("conversion");

    private ConversionFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                CONVERSION.fn("CStr", STRING, PURE_DETERMINISTIC, List.of(p("value", ANY)),
                        args -> {
                            if (args.get(0).isNull()) {
                                throw reject("CStr: cannot convert NULL");
                            }
                            return new StringValue(args.get(0).asDisplayString());
                        }),

                // CInt rounds to a whole number; CDbl keeps what it was given. Both read
                // a boolean as 1/0 and a string by parsing it.
                CONVERSION.fn("CInt", NUMBER, PURE_DETERMINISTIC, List.of(p("value", ANY)),
                        args -> toNumber(args.get(0), "CInt", true)),

                CONVERSION.fn("CDbl", NUMBER, PURE_DETERMINISTIC, List.of(p("value", ANY)),
                        args -> toNumber(args.get(0), "CDbl", false)));
    }

    /**
     * Reads a value as a number.
     *
     * <p>The switch names every value kind, so a new one cannot slip through as a
     * silently wrong conversion — it stops compiling here instead.
     *
     * @param whole whether a number is rounded to a whole one, which is what separates
     *              {@code CInt} from {@code CDbl}. It applies to a value that already is
     *              a number: parsing text yields the number the text spells, so
     *              {@code CInt("2.6")} is 2.6 — the documented behaviour, and the reason
     *              {@code Round} exists as a separate step
     */
    private static Value toNumber(Value value, String function, boolean whole) {
        return switch (value) {
            case NumberValue n -> whole
                    ? new NumberValue(n.value().setScale(0, RoundingMode.HALF_UP))
                    : n;
            case StringValue text -> parseNumber(text.value(), function);
            case BooleanValue b ->
                    new NumberValue(b.value() ? BigDecimal.ONE : BigDecimal.ZERO);
            case NullValue ignored -> throw reject(function + ": cannot convert NULL");
            case StructValue ignored -> throw reject(function + ": cannot convert a nested value");
            case ArrayValue ignored -> throw reject(function + ": cannot convert a nested value");
            case DateValue ignored -> throw reject(function + ": cannot convert a temporal value");
            case TimeValue ignored -> throw reject(function + ": cannot convert a temporal value");
            case TimestampValue ignored -> throw reject(function + ": cannot convert a temporal value");
            case DurationValue ignored -> throw reject(function + ": cannot convert a temporal value");
        };
    }
}

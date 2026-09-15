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

import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Reading an argument of the type a function needs, and reporting the data when it is
 * not that type.
 *
 * <p>A wrong <em>type</em> is still checked per call, unlike a wrong <em>count</em>: the
 * engine checks the declared arity before invoking, but a column typed {@code ANY} can
 * carry anything, so {@code Len} of a number is a condition only the body can notice.
 *
 * <p>Every rejection here is an ordinary statement about the data — a value the function
 * cannot work with — and reads as one, naming the function and what it got. It is not a
 * report of a fault in the engine, and must never reach a user as a stack trace.
 */
final class Arguments {

    private Arguments() {
    }

    /** The string behind a {@code STRING} argument. */
    static String string(Value value, String function) {
        if (!(value instanceof StringValue text)) {
            throw reject(function + ": expected STRING argument, got " + value.type());
        }
        return text.value();
    }

    /** The number behind a {@code NUMBER} argument. */
    static BigDecimal number(Value value, String function) {
        if (!(value instanceof NumberValue n)) {
            throw reject(function + ": expected NUMBER argument, got " + value.type());
        }
        return n.value();
    }

    /**
     * A {@code NUMBER} argument as an {@code int} — a length, an index, a character
     * code. A number too large to be one, or with a fractional part that rounding
     * cannot absorb, is rejected rather than silently truncated.
     */
    static int integer(Value value, String function) {
        BigDecimal n = number(value, function);
        try {
            return n.setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException e) {
            throw reject(function + ": expected integer argument, got " + n);
        }
    }

    /**
     * Parses text into a number, reporting a bad value as a statement about the data.
     *
     * <p>A non-numeric string in imported data is expected, not exceptional, so it must
     * not escape as a raw {@link NumberFormatException} carrying a Java stack trace. The
     * message quotes the offending value, which is the thing the reader needs.
     */
    static NumberValue parseNumber(String text, String function) {
        try {
            return NumberValue.of(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    function + ": cannot convert \"" + text + "\" to a number", e);
        }
    }

    /** A value this function cannot work with, phrased for the person who wrote the query. */
    static IllegalArgumentException reject(String message) {
        return new IllegalArgumentException(message);
    }
}

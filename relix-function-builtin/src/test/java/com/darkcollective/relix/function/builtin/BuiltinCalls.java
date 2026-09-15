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

import com.darkcollective.relix.function.Argument;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.LazyScalarFunction;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Calling a built-in the way the engine will: look it up by name, hand it the arguments,
 * dispatch on which of the two shapes it is.
 *
 * <p>The engine checks the declared arity before invoking, so these helpers do too — a
 * behaviour test that passed an argument count the declaration forbids would be
 * exercising a path that never happens.
 */
final class BuiltinCalls {

    /** A clock pinned to a moment with a distinct value in every component. */
    static final Instant PINNED = Instant.parse("2026-07-08T13:40:30Z");

    static final FunctionContext CONTEXT =
            FunctionContext.of(Clock.fixed(PINNED, ZoneOffset.UTC));

    private static final Map<String, ScalarFunction> BY_NAME =
            new BuiltinFunctionLibrary().scalarFunctions().stream()
                    .collect(Collectors.toMap(
                            fn -> fn.signature().canonicalName(), Function.identity()));

    private BuiltinCalls() {
    }

    /** Every function the library offers, in declaration order. */
    static List<ScalarFunction> all() {
        return new BuiltinFunctionLibrary().scalarFunctions();
    }

    /** The function of that name, or a failure naming what was asked for. */
    static ScalarFunction function(String name) {
        ScalarFunction fn = BY_NAME.get(name.toLowerCase(Locale.ROOT));
        if (fn == null) {
            throw new AssertionError("no built-in named '" + name + "'");
        }
        return fn;
    }

    /** Invokes a function against the pinned clock. */
    static Value call(String name, Value... arguments) {
        return call(CONTEXT, name, arguments);
    }

    /** Invokes a function against a given context. */
    static Value call(FunctionContext context, String name, Value... arguments) {
        ScalarFunction fn = function(name);
        List<Value> values = Arrays.asList(arguments);
        if (!fn.signature().arity().accepts(values.size())) {
            throw new AssertionError(name + " does not accept " + values.size()
                    + " argument(s); it accepts " + fn.signature().arity().describe());
        }
        return switch (fn) {
            case StrictScalarFunction strict -> strict.invoke(context, values);
            case LazyScalarFunction lazy -> lazy.invoke(context,
                    values.stream().map(Argument::of).toList());
        };
    }

    /** The text of a STRING result. */
    static String text(Value value) {
        return ((StringValue) value).value();
    }

    /** The number of a NUMBER result. */
    static BigDecimal number(Value value) {
        return ((NumberValue) value).value();
    }

    /** The truth of a BOOLEAN result. */
    static boolean truth(Value value) {
        return ((BooleanValue) value).value();
    }

    /** A NUMBER argument. */
    static Value n(String literal) {
        return new NumberValue(new BigDecimal(literal));
    }

    /** A STRING argument. */
    static Value s(String literal) {
        return new StringValue(literal);
    }
}

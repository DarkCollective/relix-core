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
import com.darkcollective.relix.function.Arity;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.util.List;

import static com.darkcollective.relix.function.builtin.Arguments.reject;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.ANY;
import static com.darkcollective.relix.symbol.ScalarType.STRING;

/**
 * The conditional built-ins — the three special forms.
 *
 * <p>All three are lazy, and that is their meaning rather than an optimisation: the
 * branch a condition does not select may be an expression that would fail on this row.
 *
 * <pre>{@code IIf(IsNumeric(raw), CDbl(raw), 0)}</pre>
 *
 * <p>Each also returns <em>one of its arguments</em>, so its result type follows the
 * call rather than the declaration. Where the branches agree on a type the call has that
 * type; where they disagree the answer is genuinely unknown until the row is seen, and
 * the type is {@code ANY}.
 */
final class ConditionalFunctions {

    private static final Category CONDITIONAL = Category.of("conditional");

    private ConditionalFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                CONDITIONAL.lazy("IIf", ANY, PURE_DETERMINISTIC,
                        List.of(p("condition", ANY), p("trueValue", ANY), p("falseValue", ANY)),
                        Arity.exactly(3), ConditionalFunctions::iifReturnType,
                        ConditionalFunctions::iif),

                // Nz(value) and Nz(value, valueIfNull) differ only in whether the
                // replacement is given, so they are one function of arity 1 to 2.
                CONDITIONAL.lazy("Nz", ANY, PURE_DETERMINISTIC,
                        List.of(p("value", ANY), p("valueIfNull", ANY)),
                        Arity.between(1, 2), ConditionalFunctions::nzReturnType,
                        Spellings.sql("COALESCE", 2),
                        ConditionalFunctions::nz),

                // Coalesce takes as many candidates as the caller has. It was declared
                // with two parameters and evaluated over any number; the range is what
                // makes the declaration true.
                CONDITIONAL.lazy("Coalesce", ANY, PURE_DETERMINISTIC,
                        List.of(p("value", ANY), p("default", ANY)),
                        Arity.atLeast(1), Category::widest,
                        Spellings.sql("COALESCE"),
                        ConditionalFunctions::coalesce));
    }

    // ── Implementations ───────────────────────────────────────────────────────

    private static Value iif(List<Argument> arguments) {
        Value condition = arguments.get(0).value();
        if (condition.isNull()) {
            return NullValue.INSTANCE;
        }
        if (!(condition instanceof BooleanValue decided)) {
            throw reject("IIf: first argument must be BOOLEAN, got " + condition.type());
        }
        return arguments.get(decided.value() ? 1 : 2).value();
    }

    private static Value nz(List<Argument> arguments) {
        Value value = arguments.get(0).value();
        if (!value.isNull()) {
            return value;
        }
        // The replacement is returned as given, even when it is itself NULL; with no
        // replacement the substitute is the empty string.
        return arguments.size() == 2 ? arguments.get(1).value() : new StringValue("");
    }

    private static Value coalesce(List<Argument> arguments) {
        for (Argument argument : arguments) {          // stops at the first non-NULL
            Value value = argument.value();
            if (!value.isNull()) {
                return value;
            }
        }
        return NullValue.INSTANCE;
    }

    // ── Result types ──────────────────────────────────────────────────────────

    /** {@code IIf} returns one of its two branches; the condition's type says nothing. */
    private static ScalarType iifReturnType(List<ScalarType> argumentTypes) {
        return argumentTypes.size() == 3
                ? Category.widest(argumentTypes.subList(1, 3))
                : ANY;
    }

    /**
     * {@code Nz} returns its value or the replacement — and, with no replacement, the
     * empty string. So the one-argument form is a STRING only when its argument already
     * is one.
     */
    private static ScalarType nzReturnType(List<ScalarType> argumentTypes) {
        if (argumentTypes.size() == 1) {
            return argumentTypes.get(0) == STRING ? STRING : ANY;
        }
        return Category.widest(argumentTypes);
    }
}

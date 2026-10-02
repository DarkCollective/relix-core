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

import java.util.ArrayList;
import java.util.List;

import static com.darkcollective.relix.function.builtin.Arguments.reject;
import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.ANY;
import static com.darkcollective.relix.symbol.ScalarType.STRING;

/**
 * The conditional built-ins — the four special forms.
 *
 * <p>All four are lazy, and that is their meaning rather than an optimisation: the
 * branch a condition does not select may be an expression that would fail on this row.
 *
 * <pre>{@code IIf(IsNumeric(raw), CDbl(raw), 0)}</pre>
 *
 * <p>Each also returns <em>one of its arguments</em>, so its result type follows the
 * call rather than the declaration. Where the branches agree on a type the call has that
 * type; where they disagree the answer is genuinely unknown until the row is seen, and
 * the type is {@code ANY}.
 *
 * <p>{@code Switch} is the multi-branch form — a searched {@code CASE} — and the reason it
 * is a function rather than grammar: a conditional is scalar work, which in this engine is
 * a function, and the two-way {@code IIf} already is one. It runs in-engine only and is not
 * rendered to a backend {@code CASE}.
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
                        ConditionalFunctions::coalesce),

                // Switch(cond1, val1, cond2, val2, …[, default]) — the searched CASE. The
                // first condition that holds picks its value; a trailing odd argument is the
                // default, and with none and no match the result is NULL. Lazy like IIf — a
                // branch not chosen is never evaluated, so an earlier condition can guard a
                // later value. The three declared parameters stand for the shape; the arity
                // is what lets a call carry as many pairs as it needs.
                CONDITIONAL.lazy("Switch", ANY, PURE_DETERMINISTIC,
                        List.of(p("condition", ANY), p("value", ANY), p("default", ANY)),
                        Arity.atLeast(2), ConditionalFunctions::switchReturnType,
                        ConditionalFunctions::switchValue));
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

    private static Value switchValue(List<Argument> arguments) {
        int pairs = arguments.size() / 2;              // a trailing default is not a pair
        for (int i = 0; i < pairs; i++) {
            Value condition = arguments.get(2 * i).value();
            if (condition.isNull()) {
                continue;                              // a NULL condition is not a match, as in SQL CASE
            }
            if (!(condition instanceof BooleanValue decided)) {
                throw reject("Switch: condition " + (i + 1) + " must be BOOLEAN, got " + condition.type());
            }
            if (decided.value()) {
                return arguments.get(2 * i + 1).value();
            }
        }
        // An odd argument count leaves a trailing default; with none, an unmatched call is NULL.
        return arguments.size() % 2 == 1
                ? arguments.get(arguments.size() - 1).value()
                : NullValue.INSTANCE;
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

    /**
     * {@code Switch} returns one of its value branches — the odd-positioned arguments,
     * plus the trailing default when the argument count is odd. The conditions decide
     * <em>which</em> value, not its type, so only the values are weighed.
     */
    private static ScalarType switchReturnType(List<ScalarType> argumentTypes) {
        List<ScalarType> values = new ArrayList<>();
        for (int i = 1; i < argumentTypes.size(); i += 2) {
            values.add(argumentTypes.get(i));
        }
        if (argumentTypes.size() % 2 == 1) {
            values.add(argumentTypes.get(argumentTypes.size() - 1));
        }
        return Category.widest(values);
    }
}

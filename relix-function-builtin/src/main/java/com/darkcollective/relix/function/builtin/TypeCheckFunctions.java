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

import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.util.List;

import static com.darkcollective.relix.function.builtin.Category.PURE_DETERMINISTIC;
import static com.darkcollective.relix.function.builtin.Category.p;
import static com.darkcollective.relix.symbol.ScalarType.ANY;
import static com.darkcollective.relix.symbol.ScalarType.BOOLEAN;

/**
 * The type-check built-ins: asking about a value instead of computing from it.
 *
 * <p>Both answer for any value and neither can fail, which is what makes them usable as
 * the guard in front of something that can — {@code IsNumeric} before a conversion.
 */
final class TypeCheckFunctions {

    private static final Category TYPECHECK = Category.of("typecheck");

    private TypeCheckFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(
                // The one built-in whose SQL form is an operator rather than a call —
                // and an exact one: SQL's IS NULL asks the same question of the same
                // three-valued world, so there is no NULL rule to reconcile. SQL Server
                // has no boolean value for it to produce, so there it is the BIT the
                // question answers as, 1 or 0.
                TYPECHECK.fn("IsNull", BOOLEAN, PURE_DETERMINISTIC, List.of(p("value", ANY)),
                        (target, arguments) ->
                                !target.isFamily(PushdownTarget.SQL) || arguments.size() != 1
                                        ? java.util.Optional.empty()
                                        : target.isVariant(Spellings.SQLSERVER)
                                        ? java.util.Optional.of("(CASE WHEN " + arguments.get(0)
                                                + " IS NULL THEN 1 ELSE 0 END)")
                                        : java.util.Optional.of("(" + arguments.get(0) + " IS NULL)"),
                        args -> BooleanValue.of(args.get(0).isNull())),

                // "Numeric" means convertible, not typed NUMBER: a string of digits
                // counts, which is the question a schema-on-read source raises.
                TYPECHECK.fn("IsNumeric", BOOLEAN, PURE_DETERMINISTIC, List.of(p("value", ANY)),
                        args -> BooleanValue.of(isNumeric(args.get(0)))));
    }

    private static boolean isNumeric(Value value) {
        if (value instanceof NumberValue) {
            return true;
        }
        if (value instanceof StringValue text) {
            try {
                new BigDecimal(text.value());
                return true;
            } catch (NumberFormatException notANumber) {
                return false;
            }
        }
        return false;
    }
}

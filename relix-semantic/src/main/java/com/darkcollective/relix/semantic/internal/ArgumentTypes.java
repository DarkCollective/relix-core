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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Whether a library function's arguments have types it accepts.
 *
 * <p>A call's argument count was the only thing analysis checked, so
 * {@code DATE_TRUNC('month', DATE '2026-03-02')} or {@code SQRT('x')} analysed clean and
 * failed when it ran. Each argument whose type is known is now held to
 * {@link ScalarFunction#argumentTypes(int)}: the parameter's declared type, or the types
 * the function says it reads. An argument of unknown type ({@code ANY}, an untyped
 * {@code NULL}, a column of an open schema) passes, as does a call to a function the
 * script defines, whose body is what decides.
 */
final class ArgumentTypes {

    /** The order a list of accepted types is written in, and a cast's target chosen from. */
    private static final List<ScalarType> ORDER = List.of(
            ScalarType.TIMESTAMP, ScalarType.DATE, ScalarType.TIME, ScalarType.TIMESTAMPTZ,
            ScalarType.DURATION, ScalarType.NUMBER, ScalarType.STRING, ScalarType.BOOLEAN);

    private ArgumentTypes() {
    }

    /**
     * The message for the first argument of {@code call} whose type {@code resolved} does
     * not accept, or null when every argument's is accepted or unknown.
     *
     * @param infer the inferred type of an operand
     */
    static String error(FunctionCall call, ResolvedFunction resolved, Function<Operand, Type> infer) {
        List<Type> types = call.arguments().stream().map(arg -> known(arg, infer)).toList();
        if (resolved.definition().isEmpty()) {
            return null;
        }
        ScalarFunction function = resolved.definition().get();
        for (int i = 0; i < types.size(); i++) {
            if (!(types.get(i) instanceof ScalarType actual) || actual == ScalarType.ANY) {
                continue;
            }
            Set<ScalarType> accepted = function.argumentTypes(i);
            if (accepted.contains(actual)) {
                continue;
            }
            return call.functionName() + "'s " + argument(function, i) + " must be "
                    + described(accepted) + ", not a " + actual.name()
                    + hint(call.arguments().get(i), actual, accepted);
        }
        return null;
    }

    /**
     * The type of {@code operand} where it is known, else ANY. Inference types arithmetic
     * over an operand of unknown type as NUMBER, which is its best guess but not known:
     * {@code closed - opened} over an undeclared source may be a DURATION.
     */
    private static Type known(Operand operand, Function<Operand, Type> infer) {
        return switch (operand) {
            case BinaryArithmeticExpression arithmetic ->
                    known(arithmetic.left(), infer) == ScalarType.ANY
                            || known(arithmetic.right(), infer) == ScalarType.ANY
                            ? ScalarType.ANY : infer.apply(operand);
            case UnaryOperand unary ->
                    known(unary.operand(), infer) == ScalarType.ANY ? ScalarType.ANY : infer.apply(operand);
            default -> infer.apply(operand);
        };
    }

    /** {@code argument ts}, by the parameter's name where the signature gives one. */
    private static String argument(ScalarFunction function, int index) {
        List<ParameterDefinition> parameters = function.signature().parameters();
        return index < parameters.size()
                ? "argument " + parameters.get(index).name()
                : "argument " + (index + 1);
    }

    /** {@code a DATE, TIMESTAMP or TIMESTAMPTZ}: no type's name starts with a vowel. */
    private static String described(Set<ScalarType> accepted) {
        List<String> names = ordered(accepted).map(ScalarType::name).toList();
        return "a " + (names.size() == 1 ? names.get(0)
                : String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.get(names.size() - 1));
    }

    private static Stream<ScalarType> ordered(Set<ScalarType> types) {
        return ORDER.stream().filter(types::contains);
    }

    /**
     * The conversion that gives the function what it reads: a CAST of text that holds
     * one, or of a date to its midnight where a timestamp is wanted. Empty otherwise.
     */
    private static String hint(Operand argument, ScalarType actual, Set<ScalarType> accepted) {
        ScalarType target = ordered(accepted).findFirst().orElseThrow();
        String cast = "CAST(" + argument.prettyPrint() + " AS " + target.name() + ")";
        if (actual == ScalarType.STRING) {
            return "; write " + cast + " for text that holds one";
        }
        if (actual == ScalarType.DATE && target == ScalarType.TIMESTAMP) {
            return "; write " + cast + " for the date's midnight";
        }
        return "";
    }
}

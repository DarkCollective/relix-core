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
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.FunctionSignature;
import com.darkcollective.relix.function.LazyScalarFunction;
import com.darkcollective.relix.function.PushdownSpelling;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.Value;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Assembles the functions of one category — the terse construction the definition files
 * are written in.
 *
 * <p>A built-in used to be three separate registrations: metadata in the semantic
 * layer's registry, a lambda in the executor's table, and a spelling in each pushdown
 * renderer. The point of the port is that all of it is one call here, so a function
 * cannot be half-declared. The category label and the documentation key follow from the
 * category this builder belongs to, which is why the builder exists at all rather than
 * every definition repeating {@code "string"} twice.
 *
 * <p>Nothing here validates an argument count: the engine checks the declared
 * {@link Arity} before it invokes, so a body may read the arguments its declaration
 * allows.
 */
final class Category {

    /** A function that computes from its arguments alone — every builtin but three. */
    @FunctionalInterface
    interface Values {
        Value apply(List<Value> arguments);
    }

    /** A function that also reads the ambient context — the current-time three. */
    @FunctionalInterface
    interface Contextual {
        Value apply(FunctionContext context, List<Value> arguments);
    }

    /** A special form, which decides which of its arguments to evaluate. */
    @FunctionalInterface
    interface Deferred {
        Value apply(List<Argument> arguments);
    }

    /** Pure, deterministic — the property set nearly every builtin declares. */
    static final Set<FunctionProperty> PURE_DETERMINISTIC =
            Set.of(FunctionProperty.PURE, FunctionProperty.DETERMINISTIC);

    /** Pure and deterministic, and applying it twice changes nothing further. */
    static final Set<FunctionProperty> PURE_DETERMINISTIC_IDEMPOTENT =
            Set.of(FunctionProperty.PURE, FunctionProperty.DETERMINISTIC,
                    FunctionProperty.IDEMPOTENT);

    /**
     * No optimizer contract at all — a function that reads the clock or the RNG.
     * Declaring nothing is what stops the optimizer folding or de-duplicating a call
     * whose value is different the next time it is asked.
     */
    static final Set<FunctionProperty> VOLATILE = Set.of();

    /**
     * Reads ambient state that does not change while a query runs — the clock. One value
     * for the whole run, a different one next time, and never the backend's own.
     */
    static final Set<FunctionProperty> STABLE = Set.of(FunctionProperty.STABLE);

    private final String name;

    private Category(String name) {
        this.name = name;
    }

    /**
     * @param name the category label — {@code "string"}, {@code "math"}, …
     * @return a builder stamping that category onto every function it makes
     */
    static Category of(String name) {
        return new Category(Objects.requireNonNull(name, "name"));
    }

    /** Shorthand for a declared parameter. */
    static ParameterDefinition p(String name, ScalarType type) {
        return new ParameterDefinition(name, type);
    }

    /** A function of fixed arity, evaluated in-engine only. */
    ScalarFunction fn(String name, ScalarType returns, Set<FunctionProperty> properties,
                      List<ParameterDefinition> parameters, Values body) {
        return fn(name, returns, properties, parameters, Arity.exactly(parameters.size()), body);
    }

    /** A function whose argument count is a range — an optional trailing argument. */
    ScalarFunction fn(String name, ScalarType returns, Set<FunctionProperty> properties,
                      List<ParameterDefinition> parameters, Arity arity, Values body) {
        return strict(name, returns, properties, parameters, arity, PushdownSpelling.NONE,
                (context, arguments) -> body.apply(arguments));
    }

    /** A function of fixed arity that a backend can also evaluate itself. */
    ScalarFunction fn(String name, ScalarType returns, Set<FunctionProperty> properties,
                      List<ParameterDefinition> parameters, PushdownSpelling pushdown,
                      Values body) {
        return strict(name, returns, properties, parameters, Arity.exactly(parameters.size()),
                pushdown, (context, arguments) -> body.apply(arguments));
    }

    /**
     * A function whose argument count is a range and that a backend can also evaluate
     * itself. The spelling is handed however many arguments the call passed, so a
     * function of two forms decides per form whether it has one.
     */
    ScalarFunction fn(String name, ScalarType returns, Set<FunctionProperty> properties,
                      List<ParameterDefinition> parameters, Arity arity,
                      PushdownSpelling pushdown, Values body) {
        return strict(name, returns, properties, parameters, arity, pushdown,
                (context, arguments) -> body.apply(arguments));
    }

    /** A function that reads the ambient context — the clock, today — evaluated in-engine only. */
    ScalarFunction contextual(String name, ScalarType returns, Set<FunctionProperty> properties,
                              List<ParameterDefinition> parameters, Contextual body) {
        return strict(name, returns, properties, parameters, Arity.exactly(parameters.size()),
                PushdownSpelling.NONE, body);
    }

    /** A function that reads the ambient context and that a backend can also evaluate itself. */
    ScalarFunction contextual(String name, ScalarType returns, Set<FunctionProperty> properties,
                              List<ParameterDefinition> parameters, PushdownSpelling pushdown,
                              Contextual body) {
        return strict(name, returns, properties, parameters, Arity.exactly(parameters.size()),
                pushdown, body);
    }

    /**
     * A special form: its arguments arrive unevaluated, and one it does not ask for is
     * never evaluated.
     *
     * @param returnTypes the result type for a call's argument types — the reason these
     *                    three need more than a declared return type, since what they
     *                    return is one of the arguments
     */
    ScalarFunction lazy(String name, ScalarType returns, Set<FunctionProperty> properties,
                        List<ParameterDefinition> parameters, Arity arity,
                        Function<List<ScalarType>, ScalarType> returnTypes, Deferred body) {
        return lazy(name, returns, properties, parameters, arity, returnTypes,
                PushdownSpelling.NONE, body);
    }

    /**
     * A special form a backend can also evaluate itself.
     *
     * <p>Laziness is a property of <em>this</em> evaluator, not of the call: a backend
     * asked to evaluate a `CASE` skips the branch it does not take for its own reasons,
     * and the arguments the spelling is handed are rendered rather than evaluated. So
     * there is nothing here for a spelling to be careful about that a strict function's
     * is not — except that an argument with a side effect would be a different question,
     * and relix has none.
     */
    ScalarFunction lazy(String name, ScalarType returns, Set<FunctionProperty> properties,
                        List<ParameterDefinition> parameters, Arity arity,
                        Function<List<ScalarType>, ScalarType> returnTypes,
                        PushdownSpelling pushdown, Deferred body) {
        return new Lazy(signature(name, returns, properties, parameters, arity),
                returnTypes, pushdown, body);
    }

    private ScalarFunction strict(String name, ScalarType returns,
                                  Set<FunctionProperty> properties,
                                  List<ParameterDefinition> parameters, Arity arity,
                                  PushdownSpelling pushdown, Contextual body) {
        return new Strict(signature(name, returns, properties, parameters, arity),
                pushdown, body);
    }

    private FunctionSignature signature(String functionName, ScalarType returns,
                                        Set<FunctionProperty> properties,
                                        List<ParameterDefinition> parameters, Arity arity) {
        return new FunctionSignature(functionName, parameters, arity, returns, properties,
                name, Optional.of(documentationPage(functionName)));
    }

    /**
     * The reference page for a built-in, which is {@code functions/<category>/<name>.md}
     * for every one of them. Derived rather than declared per function: a hand-written
     * key is a second place to be wrong, and the pages are named after the functions.
     */
    private String documentationPage(String functionName) {
        return "functions/" + name + "/" + functionName.toLowerCase(Locale.ROOT) + ".md";
    }

    /** An ordinary function: signature, backend spelling, and the body behind them. */
    private record Strict(FunctionSignature signature, PushdownSpelling pushdown,
                          Contextual body) implements StrictScalarFunction {

        @Override
        public Value invoke(FunctionContext context, List<Value> arguments) {
            return body.apply(context, arguments);
        }
    }

    /** A special form, with the result-type rule its deferred arguments require. */
    private record Lazy(FunctionSignature signature,
                        Function<List<ScalarType>, ScalarType> returnTypes,
                        PushdownSpelling pushdown,
                        Deferred body) implements LazyScalarFunction {

        @Override
        public Value invoke(FunctionContext context, List<Argument> arguments) {
            return body.apply(arguments);
        }

        @Override
        public ScalarType returnTypeFor(List<ScalarType> argumentTypes) {
            return returnTypes.apply(argumentTypes);
        }
    }
}

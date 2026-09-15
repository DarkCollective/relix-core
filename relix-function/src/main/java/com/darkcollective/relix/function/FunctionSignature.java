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

import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The <em>form</em> of a scalar function — everything the engine reasons about
 * before anything is evaluated.
 *
 * <p>Analysis, validation, optimisation and pushdown planning all run against this
 * record and never against the implementation behind it. {@code Sin} and {@code Cos}
 * are the same form; what separates them is the lambda, and the lambda is of no
 * interest to a phase that is checking argument counts or deciding whether a call can
 * be folded.
 *
 * <p>The property set is a contract with the optimizer, not a description:
 * {@link FunctionProperty#PURE} permits hoisting and common-subexpression
 * elimination, and declaring it of a function that reads the clock or an RNG produces
 * wrong answers rather than slow ones. Declare conservatively.
 *
 * @param name        the canonical spelling, as it should appear in documentation and
 *                    diagnostics; matching is case-insensitive, so this is the display
 *                    form rather than a key
 * @param parameters  the declared parameters, in order. A function whose arity range is
 *                    wider than this list — a variadic {@code Coalesce}, an optional
 *                    trailing argument — declares the parameters it can name, and the
 *                    range in {@code arity} governs what a call may pass
 * @param arity       how many arguments a call may pass
 * @param returnType  the declared result type; see
 *                    {@link ScalarFunction#returnTypeFor(List)} for a result type that
 *                    depends on the argument types
 * @param properties  the optimizer contracts this function honours
 * @param category    the grouping a function index lists it under — {@code "string"},
 *                    {@code "math"}, {@code "datetime"}
 * @param docKey      the key of this function's documentation page, when it has one
 */
public record FunctionSignature(String name,
                                List<ParameterDefinition> parameters,
                                Arity arity,
                                ScalarType returnType,
                                Set<FunctionProperty> properties,
                                String category,
                                Optional<String> docKey) {

    /** The category a function falls into when it declares none. */
    public static final String UNCATEGORISED = "other";

    /**
     * @throws IllegalArgumentException if the name or category is blank
     */
    public FunctionSignature {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Function name must not be blank");
        }
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        Objects.requireNonNull(arity, "arity");
        Objects.requireNonNull(returnType, "returnType");
        properties = Set.copyOf(Objects.requireNonNull(properties, "properties"));
        Objects.requireNonNull(category, "category");
        if (category.isBlank()) {
            throw new IllegalArgumentException("Function category must not be blank");
        }
        Objects.requireNonNull(docKey, "docKey");
    }

    /**
     * A signature whose arity is exactly its parameter list, with no documentation key.
     *
     * @param name       the canonical spelling
     * @param returnType the declared result type
     * @param category   the grouping a function index lists it under
     * @param properties the optimizer contracts this function honours
     * @param parameters the declared parameters, in order
     * @return the signature
     */
    public static FunctionSignature of(String name,
                                       ScalarType returnType,
                                       String category,
                                       Set<FunctionProperty> properties,
                                       ParameterDefinition... parameters) {
        List<ParameterDefinition> params = List.of(parameters);
        return new FunctionSignature(name, params, Arity.exactly(params.size()),
                returnType, properties, category, Optional.empty());
    }

    /**
     * The key this signature is indexed and looked up under: the name, lower-cased.
     * Names are matched case-insensitively, so {@code UCase}, {@code ucase} and
     * {@code UCASE} are one function.
     *
     * @return the lower-cased name
     */
    public String canonicalName() {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * The declared result type, whatever the arguments are.
     *
     * <p>This is the answer for every function whose result type is fixed, which is
     * nearly all of them. A function whose result follows its arguments — a
     * conditional returning whichever branch it selects — states that rule by
     * overriding {@link ScalarFunction#returnTypeFor(List)}, which delegates here
     * unless it is overridden.
     *
     * @param argumentTypes the argument types at a call site, in order
     * @return {@link #returnType()}
     */
    public ScalarType returnTypeFor(List<ScalarType> argumentTypes) {
        Objects.requireNonNull(argumentTypes, "argumentTypes");
        return returnType;
    }

    /**
     * @param property the property to test
     * @return {@code true} when this function declares {@code property}
     */
    public boolean has(FunctionProperty property) {
        return properties.contains(property);
    }
}

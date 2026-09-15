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

import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The form of an aggregate — what the engine needs to plan a grouping before any row
 * is read.
 *
 * <p>It is the scalar {@link FunctionSignature} with two additions that only a
 * reduction has. {@link #skipsNulls()} states the SQL rule once instead of at every
 * site that has to honour it: an aggregate that skips NULLs counts values rather than
 * rows, and yields NULL for a group with no non-NULL value. {@link #properties()}
 * carries what the optimizer may conclude — that a duplicate-removing step below this
 * aggregate is redundant, say.
 *
 * @param name       the canonical spelling; matching is case-insensitive
 * @param parameters the declared parameters, in order. Most aggregates take one value
 *                   per row, but not all: a "row with the maximum" reduction takes the
 *                   ranking expression and the one to yield from it
 * @param arity      how many arguments a call may pass
 * @param returnType the declared result type.  A {@link Type} rather than a
 *                   {@link ScalarType}, because a reduction can produce a nested
 *                   value: one that gathers a group's values yields an array of
 *                   whatever it gathered.  See
 *                   {@link AggregateFunction#returnTypeFor(List)} for a result type
 *                   that follows the argument types
 * @param properties the optimizer contracts this aggregate honours
 * @param skipsNulls whether a row is ignored when <em>any</em> of its argument values
 *                   is NULL. True for the SQL reducers; false for one that gathers
 *                   values into an array, where a NULL is part of the group's contents,
 *                   and false for one whose NULL rule is its own — a "row with the
 *                   maximum" reduction skips a NULL rank but yields whatever the winning
 *                   row holds, NULL included
 * @param category   the grouping a function index lists it under
 * @param docKey     the key of this aggregate's documentation page, when it has one
 */
public record AggregateSignature(String name,
                                 List<ParameterDefinition> parameters,
                                 Arity arity,
                                 Type returnType,
                                 Set<AggregateProperty> properties,
                                 boolean skipsNulls,
                                 String category,
                                 Optional<String> docKey) {

    /** The category an aggregate falls into when it declares none. */
    public static final String AGGREGATE = "aggregate";

    /**
     * @throws IllegalArgumentException if the name or category is blank
     */
    public AggregateSignature {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Aggregate name must not be blank");
        }
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        Objects.requireNonNull(arity, "arity");
        Objects.requireNonNull(returnType, "returnType");
        properties = Set.copyOf(Objects.requireNonNull(properties, "properties"));
        Objects.requireNonNull(category, "category");
        if (category.isBlank()) {
            throw new IllegalArgumentException("Aggregate category must not be blank");
        }
        Objects.requireNonNull(docKey, "docKey");
    }

    /**
     * A NULL-skipping aggregate whose arity is exactly its parameter list, filed under
     * the default category with no documentation key.
     *
     * @param name       the canonical spelling
     * @param returnType the declared result type
     * @param properties the optimizer contracts this aggregate honours
     * @param parameters the declared parameters, in order
     * @return the signature
     */
    public static AggregateSignature of(String name,
                                        Type returnType,
                                        Set<AggregateProperty> properties,
                                        ParameterDefinition... parameters) {
        List<ParameterDefinition> params = List.of(parameters);
        return new AggregateSignature(name, params, Arity.exactly(params.size()),
                returnType, properties, true, AGGREGATE, Optional.empty());
    }

    /**
     * The key this signature is indexed and looked up under: the name, lower-cased.
     *
     * @return the lower-cased name
     */
    public String canonicalName() {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * The declared result type, whatever the arguments are — see
     * {@link AggregateFunction#returnTypeFor(List)} for the overridable rule.
     *
     * @param argumentTypes the argument types at a call site, in order
     * @return {@link #returnType()}
     */
    public Type returnTypeFor(List<Type> argumentTypes) {
        Objects.requireNonNull(argumentTypes, "argumentTypes");
        return returnType;
    }

    /**
     * @param property the property to test
     * @return {@code true} when this aggregate declares {@code property}
     */
    public boolean has(AggregateProperty property) {
        return properties.contains(property);
    }
}

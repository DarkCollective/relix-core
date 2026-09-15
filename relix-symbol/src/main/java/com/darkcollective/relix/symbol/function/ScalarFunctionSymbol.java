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
package com.darkcollective.relix.symbol.function;

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.ShadowPolicy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A scalar function symbol — a function that accepts zero or more scalar arguments
 * and returns a single scalar value.
 *
 * <p>Both built-in and user-defined functions are represented by this type; the
 * {@link com.darkcollective.relix.symbol.Provenance} field distinguishes them.
 * Built-in functions typically have an absent {@link #body()}, while user-defined
 * functions carry the body as a parsed {@link Operand} expression.
 *
 * <h2>Construction</h2>
 * <p>Use the {@link #builder(String)} factory to avoid specifying every optional field:
 * <pre>{@code
 * // A built-in, deterministic, pure absolute-value function
 * ScalarFunctionSymbol abs = ScalarFunctionSymbol.builder("abs")
 *     .namespace("builtin")
 *     .provenance(Provenance.BUILTIN)
 *     .shadowPolicy(ShadowPolicy.FORBIDDEN)
 *     .parameter("x", ScalarType.NUMBER)
 *     .returnType(ScalarType.NUMBER)
 *     .property(FunctionProperty.PURE)
 *     .property(FunctionProperty.DETERMINISTIC)
 *     .build();
 *
 * // A user-defined function with a body expression
 * ScalarFunctionSymbol discount = ScalarFunctionSymbol.builder("discount")
 *     .parameter("price", ScalarType.NUMBER)
 *     .parameter("pct",   ScalarType.NUMBER)
 *     .returnType(ScalarType.NUMBER)
 *     .body(arith(attr("price"), ArithmeticOperator.MULTIPLY,
 *                 arith(num("1"), ArithmeticOperator.MINUS,
 *                       arith(attr("pct"), ArithmeticOperator.DIVIDE, num("100")))))
 *     .build();
 * }</pre>
 *
 * <p>Both the parameter list and the property set are defensive-copied on
 * construction; the record is therefore immutable.
 *
 * @param namespace    the namespace this symbol belongs to; must not be blank
 * @param declaredName the name as written by the user; must not be blank
 * @param provenance   built-in or user-defined; must not be null
 * @param shadowPolicy replacement policy; must not be null
 * @param parameters   ordered parameter definitions; must not be null
 * @param returnType   the scalar type returned; must not be null
 * @param properties   optimizer hint flags; must not be null
 * @param body         the expression body for user-defined functions; absent for
 *                     opaque built-ins
 */
public record ScalarFunctionSymbol(
        String namespace,
        String declaredName,
        Provenance provenance,
        ShadowPolicy shadowPolicy,
        List<ParameterDefinition> parameters,
        ScalarType returnType,
        Set<FunctionProperty> properties,
        Optional<Operand> body
) implements FunctionSymbol {

    public ScalarFunctionSymbol {
        Objects.requireNonNull(namespace, "namespace");
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        Objects.requireNonNull(declaredName, "declaredName");
        if (declaredName.isBlank()) {
            throw new IllegalArgumentException("declaredName must not be blank");
        }
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(shadowPolicy, "shadowPolicy");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(body, "body");
        parameters = List.copyOf(parameters);
        properties = properties.isEmpty()
                ? Set.of()
                : Set.copyOf(properties);
    }

    /**
     * Returns a new {@link Builder} for constructing a {@code ScalarFunctionSymbol}
     * with the given declared name.
     *
     * <p>Defaults applied by the builder:
     * <ul>
     *   <li>{@code namespace} → {@code "default"}</li>
     *   <li>{@code provenance} → {@link Provenance#USER}</li>
     *   <li>{@code shadowPolicy} → {@link ShadowPolicy#PERMITTED}</li>
     *   <li>{@code returnType} → {@link ScalarType#ANY}</li>
     *   <li>{@code parameters} → empty list (zero-argument function)</li>
     *   <li>{@code properties} → empty set</li>
     *   <li>{@code body} → {@link Optional#empty()}</li>
     * </ul>
     *
     * @param name the declared function name; must not be blank
     * @return a fresh builder
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Fluent builder for {@link ScalarFunctionSymbol}.
     *
     * <p>Instances are obtained via {@link ScalarFunctionSymbol#builder(String)}.
     * The builder is single-use; call {@link #build()} once to produce the
     * immutable record.
     */
    public static final class Builder {

        private final String name;
        private String namespace = "default";
        private Provenance provenance = Provenance.USER;
        private ShadowPolicy shadowPolicy = ShadowPolicy.PERMITTED;
        private final List<ParameterDefinition> parameters = new ArrayList<>();
        private ScalarType returnType = ScalarType.ANY;
        private final Set<FunctionProperty> properties = EnumSet.noneOf(FunctionProperty.class);
        private Optional<Operand> body = Optional.empty();

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Function name must not be blank");
            }
            this.name = name;
        }

        /**
         * Sets the namespace (default: {@code "default"}).
         *
         * @param namespace must not be blank
         * @return this builder
         */
        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
         * Sets the provenance (default: {@link Provenance#USER}).
         *
         * @param provenance must not be null
         * @return this builder
         */
        public Builder provenance(Provenance provenance) {
            this.provenance = Objects.requireNonNull(provenance, "provenance");
            return this;
        }

        /**
         * Sets the shadow policy (default: {@link ShadowPolicy#PERMITTED}).
         *
         * @param shadowPolicy must not be null
         * @return this builder
         */
        public Builder shadowPolicy(ShadowPolicy shadowPolicy) {
            this.shadowPolicy = Objects.requireNonNull(shadowPolicy, "shadowPolicy");
            return this;
        }

        /**
         * Appends a parameter to the end of the parameter list.
         *
         * @param name the parameter name; must not be blank
         * @param type the parameter type; must not be null
         * @return this builder
         */
        public Builder parameter(String name, ScalarType type) {
            parameters.add(new ParameterDefinition(name, type));
            return this;
        }

        /**
         * Sets the return type (default: {@link ScalarType#ANY}).
         *
         * @param returnType must not be null
         * @return this builder
         */
        public Builder returnType(ScalarType returnType) {
            this.returnType = Objects.requireNonNull(returnType, "returnType");
            return this;
        }

        /**
         * Adds a single optimizer hint flag.
         *
         * @param property must not be null
         * @return this builder
         */
        public Builder property(FunctionProperty property) {
            properties.add(Objects.requireNonNull(property, "property"));
            return this;
        }

        /**
         * Sets the body expression for a user-defined function.
         *
         * @param body the scalar expression body; must not be null
         * @return this builder
         */
        public Builder body(Operand body) {
            this.body = Optional.of(Objects.requireNonNull(body, "body"));
            return this;
        }

        /**
         * Constructs the {@link ScalarFunctionSymbol} from the current builder state.
         *
         * @return a new immutable {@code ScalarFunctionSymbol}
         */
        public ScalarFunctionSymbol build() {
            return new ScalarFunctionSymbol(
                    namespace, name, provenance, shadowPolicy,
                    parameters, returnType, properties, body);
        }
    }
}

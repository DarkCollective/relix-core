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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A table-valued function symbol — a relation-returning user-defined function.
 *
 * <p>Where a {@link ScalarFunctionSymbol} accepts scalar arguments and yields a
 * single scalar value, a {@code RelationFunctionSymbol} accepts scalar arguments
 * and yields a whole relation: its {@link #body()} is a relational-algebra
 * {@link RelNode} expression, and its return "type" is a relation schema rather
 * than a {@link ScalarType}.  A table-valued function is declared with
 * {@code def name(p: T, …): RELATION := \{ <RA expression> \}} and invoked in
 * relation position (as a {@code RelationFunctionCall} {@link RelNode}).
 *
 * <p>Binding is by <em>substitution</em>: a call's argument expressions replace
 * the matching parameter references in the body, and the resulting relational
 * expression is inlined by the planner.  Because the binding is purely
 * syntactic, this models a <em>parameterized view</em>.
 *
 * <p>The {@link #returnSchema()} is the body's inferred output schema; it is
 * empty until the schema-inference phase has resolved it (mirroring how a
 * {@link com.darkcollective.relix.symbol.relation.QueryRelationSymbol}'s schema
 * is filled in after inference).
 *
 * <h2>{@link FunctionSymbol} contract</h2>
 * <p>A relation function carries no scalar return type; {@link #returnType()}
 * therefore reports {@link ScalarType#ANY} as a neutral placeholder (it is not
 * meaningful for a relation function — consumers that distinguish the two
 * function kinds should pattern-match on the concrete type).  It declares no
 * optimizer properties.
 *
 * @param namespace    the namespace this symbol belongs to; must not be blank
 * @param declaredName the name as written by the user; must not be blank
 * @param provenance   built-in or user-defined; must not be null
 * @param shadowPolicy replacement policy; must not be null
 * @param parameters   ordered parameter definitions; must not be null
 * @param body         the relational-algebra expression defining the result; must not be null
 * @param returnSchema the body's inferred output schema; absent until inference resolves it
 */
public record RelationFunctionSymbol(
        String namespace,
        String declaredName,
        Provenance provenance,
        ShadowPolicy shadowPolicy,
        List<ParameterDefinition> parameters,
        RelNode body,
        Optional<Schema> returnSchema
) implements FunctionSymbol {

    public RelationFunctionSymbol {
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
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(returnSchema, "returnSchema");
        parameters = List.copyOf(parameters);
    }

    /**
     * The scalar return type, reported as {@link ScalarType#ANY} — a relation
     * function does not return a scalar value (see {@link #returnSchema()}).
     *
     * @return {@link ScalarType#ANY}; never null
     */
    @Override
    public ScalarType returnType() {
        return ScalarType.ANY;
    }

    /**
     * Table-valued functions declare no optimizer hint flags.
     *
     * @return the empty set; never null
     */
    @Override
    public Set<FunctionProperty> properties() {
        return Set.of();
    }

    /**
     * Returns a copy of this symbol with its {@link #returnSchema()} resolved to
     * {@code schema} — used by the inference phase once the body's output schema
     * has been derived.
     *
     * @param schema the resolved body schema; must not be null
     * @return a new {@code RelationFunctionSymbol} carrying the resolved schema
     */
    public RelationFunctionSymbol withReturnSchema(Schema schema) {
        return new RelationFunctionSymbol(namespace, declaredName, provenance, shadowPolicy,
                parameters, body, Optional.of(Objects.requireNonNull(schema, "schema")));
    }

    /**
     * Returns a new {@link Builder} for a {@code RelationFunctionSymbol} with the
     * given declared name.
     *
     * <p>Builder defaults: {@code namespace} → {@code "default"},
     * {@code provenance} → {@link Provenance#USER}, {@code shadowPolicy} →
     * {@link ShadowPolicy#PERMITTED}, {@code parameters} → empty,
     * {@code returnSchema} → {@link Optional#empty()}.
     *
     * @param name the declared function name; must not be blank
     * @return a fresh builder
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Fluent builder for {@link RelationFunctionSymbol}.  Single-use; call
     * {@link #build()} once to produce the immutable record.
     */
    public static final class Builder {

        private final String name;
        private String namespace = "default";
        private Provenance provenance = Provenance.USER;
        private ShadowPolicy shadowPolicy = ShadowPolicy.PERMITTED;
        private final List<ParameterDefinition> parameters = new ArrayList<>();
        private RelNode body;
        private Optional<Schema> returnSchema = Optional.empty();

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
         * Sets the relational-algebra body expression.
         *
         * @param body the body; must not be null
         * @return this builder
         */
        public Builder body(RelNode body) {
            this.body = Objects.requireNonNull(body, "body");
            return this;
        }

        /**
         * Sets the resolved return schema (default: absent).
         *
         * @param schema the body's output schema; must not be null
         * @return this builder
         */
        public Builder returnSchema(Schema schema) {
            this.returnSchema = Optional.of(Objects.requireNonNull(schema, "schema"));
            return this;
        }

        /**
         * Constructs the {@link RelationFunctionSymbol} from the current state.
         *
         * @return a new immutable {@code RelationFunctionSymbol}
         */
        public RelationFunctionSymbol build() {
            return new RelationFunctionSymbol(
                    namespace, name, provenance, shadowPolicy, parameters, body, returnSchema);
        }
    }
}

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
package com.darkcollective.relix.symbol.relation;

import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;

import java.util.Objects;

/**
 * A relation symbol backed by an external data source whose transport details
 * (HTTP, CSV file, etc.) are held outside the symbol table.
 *
 * <p>This type carries only the information needed for semantic analysis —
 * the relation name, its column schema, and the standard symbol metadata.
 * The full source configuration (URL, method, pagination, extract spec, …)
 * lives in the semantic model produced by {@code relix-semantic}.
 *
 * <p>Use {@link #of(String, Schema)} for user-declared sources and
 * {@link #builtin(String, Schema)} for runtime-provided built-in sources:
 * <pre>{@code
 * SourceRelationSymbol weather = SourceRelationSymbol.of("current_weather",
 *     new Schema(List.of(
 *         new ColumnDefinition("city", ScalarType.STRING),
 *         new ColumnDefinition("temp", ScalarType.NUMBER)
 *     )));
 * }</pre>
 *
 * @param namespace    the namespace this symbol belongs to; must not be blank
 * @param declaredName the name as written by the user; must not be blank
 * @param provenance   built-in or user-defined; must not be null
 * @param shadowPolicy replacement policy for this symbol; must not be null
 * @param schema       the structural description of the relation; must not be null
 */
public record SourceRelationSymbol(
        String namespace,
        String declaredName,
        Provenance provenance,
        ShadowPolicy shadowPolicy,
        Schema schema
) implements RelationSymbol {

    public SourceRelationSymbol {
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
        Objects.requireNonNull(schema, "schema");
    }

    /**
     * Creates a user-defined source relation in the {@code "default"} namespace
     * with {@link ShadowPolicy#PERMITTED}.
     *
     * @param name   the relation name; must not be blank
     * @param schema the relation's column schema; must not be null
     * @return a new {@code SourceRelationSymbol}
     */
    public static SourceRelationSymbol of(String name, Schema schema) {
        return new SourceRelationSymbol("default", name, Provenance.USER, ShadowPolicy.PERMITTED, schema);
    }

    /**
     * Creates a built-in source relation in the {@code "builtin"} namespace
     * with {@link ShadowPolicy#FORBIDDEN}.
     *
     * @param name   the relation name; must not be blank
     * @param schema the relation's column schema; must not be null
     * @return a new {@code SourceRelationSymbol}
     */
    public static SourceRelationSymbol builtin(String name, Schema schema) {
        return new SourceRelationSymbol("builtin", name, Provenance.BUILTIN, ShadowPolicy.FORBIDDEN, schema);
    }
}

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

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A read-only system catalog relation in the reserved {@code relix.*} namespace.
 *
 * <p>System catalog relations represent the engine's self-description (the
 * {@code information_schema} analogue).
 * They are always {@link Provenance#BUILTIN}; unlike
 * {@link InlineRelationSymbol} — which stores user-declared inline data — this
 * type signals that the rows are engine-computed and the relation is
 * structurally read-only.  Callers pattern-matching on {@link RelationSymbol}
 * can distinguish catalog relations from user-declared inline data by type alone.
 *
 * <p>Catalog relations are registered by {@code CatalogBuilder} in the {@code
 * relix} namespace.  Those whose extents depend on inferred view schemas
 * ({@code relix.columns}, {@code relix.plan}) use the placeholder-then-fill
 * pattern: they are registered with an empty {@code rows} list before schema
 * inference and re-registered with the real extent after; the placeholder
 * registration uses {@link ShadowPolicy#PERMITTED} to allow the overwrite.
 *
 * <p>Both the outer row list and each inner map are defensive-copied on
 * construction.
 *
 * @param namespace    always {@code "relix"}; must not be blank
 * @param declaredName the catalog name (e.g. {@code "relations"}); must not be blank
 * @param provenance   always {@link Provenance#BUILTIN}; must not be null
 * @param shadowPolicy {@link ShadowPolicy#FORBIDDEN} for fixed-extent catalogs;
 *                     {@link ShadowPolicy#PERMITTED} for placeholder-then-fill;
 *                     must not be null
 * @param schema       the structural description of the relation; must not be null
 * @param rows         the catalog row data; must not be null (may be empty for
 *                     placeholders awaiting their post-inference fill)
 */
public record SystemRelationSymbol(
        String namespace,
        String declaredName,
        Provenance provenance,
        ShadowPolicy shadowPolicy,
        Schema schema,
        List<Map<String, Operand>> rows
) implements RelationSymbol {

    public SystemRelationSymbol {
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
        Objects.requireNonNull(rows, "rows");
        // Defensive copy — each inner map is also made unmodifiable
        rows = rows.stream().map(Map::copyOf).collect(Collectors.toUnmodifiableList());
    }
}

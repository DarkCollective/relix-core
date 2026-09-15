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

/**
 * A relation whose rows are declared inline in a {@code .relix} source file,
 * typically as a Markdown table.
 *
 * <p>Each row is represented as a {@code Map<String, Operand>} where the key is
 * the column name (case-preserved, matching the schema declaration) and the value
 * is a typed {@link Operand} — either a {@link com.darkcollective.relix.ast.StringOperand}
 * or a {@link com.darkcollective.relix.ast.NumberOperand}.
 *
 * <p>Type inference is performed by the loader/parser: a column whose every cell
 * parses as a numeric literal is assigned
 * {@link com.darkcollective.relix.symbol.ScalarType#NUMBER}; otherwise it is
 * {@link com.darkcollective.relix.symbol.ScalarType#STRING}.
 *
 * <p>Example (as produced by the parser from a Markdown table):
 * <pre>{@code
 * Schema schema = new Schema(List.of(
 *     new ColumnDefinition("id",   ScalarType.NUMBER),
 *     new ColumnDefinition("name", ScalarType.STRING)
 * ));
 * List<Map<String, Operand>> rows = List.of(
 *     Map.of("id", new NumberOperand("1"), "name", new StringOperand("Alice")),
 *     Map.of("id", new NumberOperand("2"), "name", new StringOperand("Bob"))
 * );
 * InlineRelationSymbol users = InlineRelationSymbol.of("Users", schema, rows);
 * }</pre>
 *
 * <p>Both the outer row list and each inner map are defensive-copied on construction.
 *
 * @param namespace    the namespace this symbol belongs to; must not be blank
 * @param declaredName the name as written by the user; must not be blank
 * @param provenance   built-in or user-defined; must not be null
 * @param shadowPolicy replacement policy for this symbol; must not be null
 * @param schema       the structural description of the relation; must not be null
 * @param rows         the inline row data; must not be null (may be empty)
 */
public record InlineRelationSymbol(
        String namespace,
        String declaredName,
        Provenance provenance,
        ShadowPolicy shadowPolicy,
        Schema schema,
        List<Map<String, Operand>> rows
) implements RelationSymbol {

    public InlineRelationSymbol {
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
        rows = rows.stream().map(Map::copyOf).collect(java.util.stream.Collectors.toUnmodifiableList());
    }

    /**
     * Creates a user-defined inline relation in the {@code "default"} namespace
     * with {@link ShadowPolicy#PERMITTED}.
     *
     * @param name   the relation name; must not be blank
     * @param schema the relation's column schema; must not be null
     * @param rows   the inline row data; must not be null
     * @return a new {@code InlineRelationSymbol}
     */
    public static InlineRelationSymbol of(String name, Schema schema, List<Map<String, Operand>> rows) {
        return new InlineRelationSymbol("default", name, Provenance.USER, ShadowPolicy.PERMITTED, schema, rows);
    }
}

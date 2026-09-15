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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;

import java.util.Objects;

/**
 * A named relational algebra expression — essentially a stored view.
 *
 * <p>The body is a {@link RelNode} representing the relational algebra expression
 * that defines this relation.  It is stored for later semantic analysis,
 * optimisation, and pretty-printing; no evaluation takes place at registration time.
 *
 * <p>A {@code QueryRelationSymbol} is typically produced by the {@code relix-lang}
 * parser when it encounters an assignment of the form:
 * <pre>
 *   ActiveUsers := { σ status = "active" (Users) };
 * </pre>
 *
 * <p>The {@link Schema} represents the <em>declared</em> output schema.  If the
 * schema cannot be determined statically from the body, callers may omit explicit
 * typing by using {@link com.darkcollective.relix.symbol.ScalarType#ANY} for all
 * columns.
 *
 * @param namespace    the namespace this symbol belongs to; must not be blank
 * @param declaredName the name as written by the user; must not be blank
 * @param provenance   built-in or user-defined; must not be null
 * @param shadowPolicy replacement policy for this symbol; must not be null
 * @param schema       the structural description of the result relation; must not be null
 * @param body         the relational algebra expression defining this relation;
 *                     must not be null
 */
public record QueryRelationSymbol(
        String namespace,
        String declaredName,
        Provenance provenance,
        ShadowPolicy shadowPolicy,
        Schema schema,
        RelNode body
) implements RelationSymbol {

    public QueryRelationSymbol {
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
        Objects.requireNonNull(body, "body");
    }

    /**
     * Creates a user-defined query relation in the {@code "default"} namespace
     * with {@link ShadowPolicy#PERMITTED}.
     *
     * @param name   the relation name; must not be blank
     * @param schema the declared output schema; must not be null
     * @param body   the relational algebra expression; must not be null
     * @return a new {@code QueryRelationSymbol}
     */
    public static QueryRelationSymbol of(String name, Schema schema, RelNode body) {
        return new QueryRelationSymbol("default", name, Provenance.USER, ShadowPolicy.PERMITTED, schema, body);
    }
}

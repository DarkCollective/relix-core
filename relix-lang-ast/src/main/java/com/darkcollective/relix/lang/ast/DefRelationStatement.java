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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.symbol.ParameterDefinition;

import java.util.List;
import java.util.Objects;

/**
 * A {@code def … : relation} statement — defines a table-valued (relation-returning)
 * user-defined function.
 *
 * <p>Syntax:
 * <pre>
 *   def recentOrders(cid: NUMBER): RELATION := {
 *       σ customer_id = cid ∧ status = "completed" (Orders)
 *   };
 *
 *   private def activeUsers(): RELATION := { σ status = "active" (Users) };
 * </pre>
 *
 * <p>The {@code : RELATION} return-type annotation distinguishes this from the
 * scalar {@link DefStatement}; the body is a fully-parsed relational-algebra
 * {@link RelNode} expression (delegated to the RA parser), not a scalar operand.
 *
 * <p>A {@code DefRelationStatement} corresponds directly to a
 * {@link com.darkcollective.relix.symbol.function.RelationFunctionSymbol} — the
 * semantic layer creates the symbol and registers it in the symbol table.
 *
 * @param exported   {@code true} unless the {@code private} modifier is present
 * @param name       the function name; must not be blank
 * @param parameters the typed parameter list; must not be null
 * @param body       the relational-algebra expression body; must not be null
 * @param location   the source location of this statement; never null
 */
public record DefRelationStatement(
        boolean exported,
        String name,
        List<ParameterDefinition> parameters,
        RelNode body,
        SourceLocation location
) implements Statement {

    public DefRelationStatement {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(location, "location");
        parameters = List.copyOf(parameters);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public DefRelationStatement(boolean exported, String name,
                                List<ParameterDefinition> parameters, RelNode body) {
        this(exported, name, parameters, body, SourceLocation.UNKNOWN);
    }

    /** Creates an exported table-valued-function def statement (the common case). */
    public static DefRelationStatement of(String name,
                                          List<ParameterDefinition> parameters,
                                          RelNode body) {
        return new DefRelationStatement(true, name, parameters, body, SourceLocation.UNKNOWN);
    }
}

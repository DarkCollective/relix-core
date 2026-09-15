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

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;

import java.util.List;
import java.util.Objects;

/**
 * A {@code def} statement — defines a scalar user-defined function.
 *
 * <p>Syntax:
 * <pre>
 *   def double(x: NUMBER): NUMBER := { x * 2 };
 *
 *   def label(score: NUMBER): STRING := {
 *       CASE WHEN score >= 90 THEN "A" WHEN score >= 70 THEN "B" ELSE "C" END
 *   };
 *
 *   private def helper(x: NUMBER): NUMBER := { x + 1 };
 * </pre>
 *
 * <p>The body is a fully-parsed scalar {@link Operand} expression, produced by
 * delegating the {@code { ... }} block to the operand parser.
 *
 * <p>A {@code DefStatement} corresponds directly to a
 * {@link com.darkcollective.relix.symbol.function.ScalarFunctionSymbol} with
 * a non-empty {@code body} — the semantic layer creates the symbol and
 * registers it in the symbol table.
 *
 * @param exported   {@code true} unless the {@code private} modifier is present
 * @param name       the function name; must not be blank
 * @param parameters the typed parameter list; must not be null
 * @param returnType the declared return type; must not be null
 * @param body       the scalar expression body; must not be null
 * @param location   the source location of this statement; never null
 */
public record DefStatement(
        boolean exported,
        String name,
        List<ParameterDefinition> parameters,
        ScalarType returnType,
        Operand body,
        SourceLocation location
) implements Statement {

    public DefStatement {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(location, "location");
        parameters = List.copyOf(parameters);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public DefStatement(boolean exported, String name, List<ParameterDefinition> parameters,
                        ScalarType returnType, Operand body) {
        this(exported, name, parameters, returnType, body, SourceLocation.UNKNOWN);
    }

    /** Creates an exported def statement (the common case). */
    public static DefStatement of(String name,
                                  List<ParameterDefinition> parameters,
                                  ScalarType returnType,
                                  Operand body) {
        return new DefStatement(true, name, parameters, returnType, body, SourceLocation.UNKNOWN);
    }
}

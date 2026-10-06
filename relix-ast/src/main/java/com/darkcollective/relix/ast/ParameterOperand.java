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
package com.darkcollective.relix.ast;

import com.darkcollective.relix.ast.visitor.OperandVisitor;

import java.util.Objects;

/**
 * A bound query parameter, {@code $name}: a scalar whose value is supplied from outside
 * the query text when it runs.
 *
 * <p>The value is never part of the expression. Printing the tree prints {@code $name},
 * a query pushed to a database sends it as a bind parameter, and so no value — a string
 * holding a quote included — can change what the query means. A parameter is matched
 * by name ignoring case, as a column is.
 *
 * @param name     the parameter's name, without the {@code $}; an identifier
 * @param location the source location of this operand; never null
 */
public record ParameterOperand(String name, SourceLocation location) implements Operand {
    public ParameterOperand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(location, "location");
        if (!isName(name)) {
            throw new IllegalArgumentException(
                    "A parameter name is an identifier — a letter or '_', then letters, digits"
                            + " or '_' — got '" + name + "'");
        }
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public ParameterOperand(String name) {
        this(name, SourceLocation.UNKNOWN);
    }

    private static boolean isName(String name) {
        if (name.isEmpty() || !(Character.isLetter(name.charAt(0)) || name.charAt(0) == '_')) {
            return false;
        }
        return name.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

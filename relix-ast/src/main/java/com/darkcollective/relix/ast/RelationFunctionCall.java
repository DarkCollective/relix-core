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

import com.darkcollective.relix.ast.visitor.RelNodeVisitor;

import java.util.List;
import java.util.Objects;

/**
 * A reference to a table-valued (relation-returning) user-defined function, used
 * wherever a relation is expected — a leaf node in the relational algebra tree.
 *
 * <p>Example: {@code recentOrders(2)} invokes the table-valued function
 * {@code recentOrders} with the scalar argument {@code 2}; it yields the relation
 * produced by the function's body with that argument bound to its parameter.
 *
 * <p>The {@link #arguments() arguments} are scalar {@link Operand} expressions
 * (not relational children), so this node has no {@code RelNode} children and is
 * treated as a leaf by {@link #children()}/{@link #mapChildren}.  Binding is by
 * substitution: the planner inlines the function's body with each parameter
 * replaced by the corresponding argument expression.
 *
 * @param functionName the name of the table-valued function; must not be blank
 * @param arguments     the scalar argument expressions, in order; must not be null
 * @param location      the source location of this node; never null
 */
public record RelationFunctionCall(String functionName, List<Operand> arguments,
                                   SourceLocation location) implements RelNode {

    public RelationFunctionCall {
        requireNonBlank(functionName, "functionName");
        Objects.requireNonNull(arguments, "arguments");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public RelationFunctionCall(String functionName, List<Operand> arguments) {
        this(functionName, arguments, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

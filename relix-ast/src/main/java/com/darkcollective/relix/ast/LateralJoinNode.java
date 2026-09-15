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
 * A correlated / lateral table-valued function application that produces one
 * sub-relation per row of its left input.
 *
 * <p>Syntax: {@code left LATERAL fn(arg, …)} — for each row produced by
 * {@code left}, the arguments are evaluated against that row, the function body
 * is instantiated with those values, and the resulting rows are concatenated with
 * the outer row to form the output.
 *
 * <p>Unlike {@link RelationFunctionCall} (a leaf whose arguments must be
 * constants), this node carries a relational {@code left} child so
 * {@link #children()} includes it in the structural traversal.  The TVF
 * arguments may reference columns from the left schema — that is the defining
 * property of a lateral join.
 *
 * @param left         the outer (driving) relation; must not be null
 * @param functionName the name of the table-valued function; must not be blank
 * @param arguments    scalar argument expressions (may reference left columns)
 * @param location     source location of the {@code LATERAL} keyword
 */
public record LateralJoinNode(RelNode left,
                               String functionName,
                               List<Operand> arguments,
                               SourceLocation location) implements RelNode {

    public LateralJoinNode {
        Objects.requireNonNull(left, "left");
        requireNonBlank(functionName, "functionName");
        Objects.requireNonNull(arguments, "arguments");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public LateralJoinNode(RelNode left, String functionName, List<Operand> arguments) {
        this(left, functionName, arguments, SourceLocation.UNKNOWN);
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

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

import java.util.Objects;

/**
 * A lateral join: its right side is evaluated once per row of its left input, and may read
 * that row's columns.
 *
 * <p>Two forms are written. {@code left LATERAL fn(arg, …)} calls a table-valued function
 * whose arguments may name left columns; the right side is then a
 * {@link RelationFunctionCall}. {@code left LATERAL (expression)} evaluates a relation
 * expression in which a qualified name whose relation the expression does not read,
 * {@code Customers.customer_id}, is a column of the left row. Each left row is joined to the
 * rows its right side yields; {@link LateralKind#OUTER OUTER} also keeps, once, a left row
 * for which the right side yields none, its right columns NULL.
 *
 * <p>Both inputs are {@linkplain #children() children}: a traversal sees the function call
 * or the expression as it sees any other input. What sets the right side apart is only that
 * it may read the left row, so it is analysed and run in the left row's scope.
 *
 * @param left     the outer (driving) relation; must not be null
 * @param right    the relation evaluated per left row: a {@link RelationFunctionCall} or any
 *                 relation expression; must not be null
 * @param kind     whether a left row with no right rows is dropped or kept; must not be null
 * @param location source location of the {@code LATERAL} keyword
 */
public record LateralJoinNode(RelNode left,
                              RelNode right,
                              LateralKind kind,
                              SourceLocation location) implements RelNode {

    public LateralJoinNode {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests: an inner lateral; uses {@link SourceLocation#UNKNOWN}. */
    public LateralJoinNode(RelNode left, RelNode right) {
        this(left, right, LateralKind.INNER, SourceLocation.UNKNOWN);
    }

    /**
     * Whether the right side is a table-valued function call, {@code L LATERAL fn(…)}, rather
     * than a relation expression in parentheses.
     *
     * @return true for the function form
     */
    public boolean isFunctionCall() {
        return right instanceof RelationFunctionCall;
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

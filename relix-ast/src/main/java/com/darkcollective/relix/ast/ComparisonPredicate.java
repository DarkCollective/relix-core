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

import com.darkcollective.relix.ast.visitor.PredicateVisitor;

import java.util.Objects;

/**
 * A binary comparison predicate ({@code left op right}).
 *
 * <p>Examples: {@code age > 18}, {@code name = "Alice"}, {@code price ≤ 100}
 *
 * @param left     the left operand; must not be null
 * @param operator the comparison operator; must not be null
 * @param right    the right operand; must not be null
 * @param location the source location of this predicate; never null
 */
public record ComparisonPredicate(
        Operand left,
        ComparisonOperator operator,
        Operand right,
        SourceLocation location
) implements Predicate {
    public ComparisonPredicate {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public ComparisonPredicate(Operand left, ComparisonOperator operator, Operand right) {
        this(left, operator, right, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(PredicateVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

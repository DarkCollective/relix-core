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
 * Represents a null predicate (IS NULL or IS NOT NULL) in relational algebra.
 * attribute = ⊥ represents IS NULL
 * attribute ≠ ⊥ represents IS NOT NULL
 *
 * @param operand  the operand to test for null; must not be null
 * @param isNull   {@code true} for IS NULL, {@code false} for IS NOT NULL
 * @param location the source location of this predicate; never null
 */
public record NullPredicate(
        Operand operand,
        boolean isNull,
        SourceLocation location
) implements Predicate {
    public NullPredicate {
        Objects.requireNonNull(operand, "operand");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public NullPredicate(Operand operand, boolean isNull) {
        this(operand, isNull, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(PredicateVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

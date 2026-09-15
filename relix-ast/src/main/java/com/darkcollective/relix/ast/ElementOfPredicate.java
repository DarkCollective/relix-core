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
 * Represents element-of predicates: element ∈ set_expression or element ∉ set_expression
 * Used for set membership testing (equivalent to SQL IN/NOT IN).
 *
 * @param element       the element to test; must not be null
 * @param setExpression the set expression to test membership in; must not be null
 * @param isNegated     {@code true} for ∉ (not element of), {@code false} for ∈ (element of)
 * @param location      the source location of this predicate; never null
 */
public record ElementOfPredicate(
    Operand element,
    Operand setExpression,
    boolean isNegated,
    SourceLocation location
) implements Predicate {
    public ElementOfPredicate {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(setExpression, "setExpression");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public ElementOfPredicate(Operand element, Operand setExpression, boolean isNegated) {
        this(element, setExpression, isNegated, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(PredicateVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

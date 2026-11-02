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
 * A comparison with every value of a one-column relation: {@code x > ALL (π v (S))},
 * {@code x = ANY (π v (S))}. {@code x IN (π v (S))} is {@code = ANY}, and {@code NOT IN} a
 * {@link NotPredicate} over it.
 *
 * <p>The comparisons are three-valued, as SQL's are. {@link Quantifier#ANY ANY} is true
 * when one of them is true, UNKNOWN when none is but one is UNKNOWN, and false otherwise;
 * {@link Quantifier#ALL ALL} is false when one is false, UNKNOWN when none is but one is
 * UNKNOWN, and true otherwise. Over no rows ANY is false and ALL true, whatever {@code x}
 * is.
 *
 * @param left       the value compared; must not be null
 * @param operator   the comparison; must not be null
 * @param quantifier whether some or every comparison must hold; must not be null
 * @param query      the one-column relation compared with; must not be null
 * @param location   the source location of the predicate's first token; never null
 */
public record QuantifiedPredicate(Operand left, ComparisonOperator operator, Quantifier quantifier,
                                  RelNode query, SourceLocation location) implements Predicate {

    public QuantifiedPredicate {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(quantifier, "quantifier");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public QuantifiedPredicate(Operand left, ComparisonOperator operator, Quantifier quantifier,
                               RelNode query) {
        this(left, operator, quantifier, query, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(PredicateVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

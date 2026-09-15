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
 * Represents a pattern-matching predicate: {@code operand LIKE pattern} or
 * {@code operand NOT LIKE pattern}.
 *
 * <p>The LIKE pattern uses SQL wildcard syntax:
 * <ul>
 *   <li>{@code %} — matches any sequence of zero or more characters.</li>
 *   <li>{@code _} — matches exactly one character.</li>
 *   <li>All other characters — match literally (case-sensitive).</li>
 * </ul>
 *
 * <p>Examples:
 * <pre>
 *   σ name LIKE "%smith%" (Users)
 *   σ email NOT LIKE "%@example.com" (Users)
 * </pre>
 *
 * @param operand  the expression whose string value is tested; must not be null
 * @param pattern  the LIKE pattern string literal; must not be null
 * @param negated  {@code true} for NOT LIKE, {@code false} for LIKE
 * @param location the source location of this predicate; never null
 */
public record PatternPredicate(
    Operand operand,
    Operand pattern,
    boolean negated,
    SourceLocation location
) implements Predicate {
    public PatternPredicate {
        Objects.requireNonNull(operand,  "operand");
        Objects.requireNonNull(pattern,  "pattern");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public PatternPredicate(Operand operand, Operand pattern, boolean negated) {
        this(operand, pattern, negated, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(PredicateVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

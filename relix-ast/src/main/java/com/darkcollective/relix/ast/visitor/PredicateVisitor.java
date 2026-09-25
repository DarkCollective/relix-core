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
package com.darkcollective.relix.ast.visitor;

import com.darkcollective.relix.ast.visitor.internal.PredicatePrettyPrinter;
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

/**
 * Visitor over the {@link com.darkcollective.relix.ast.Predicate} sealed hierarchy.
 *
 * <p>Implement this interface to process or transform boolean filter conditions.
 * Every permitted predicate type has a dedicated {@code visit} overload.
 *
 * @param <R> the return type produced by each visit method
 * @see com.darkcollective.relix.ast.Predicate#accept(PredicateVisitor)
 * @see PredicatePrettyPrinter
 */
public interface PredicateVisitor<R> {
    /** Visits a comparison predicate (=, ≠, &lt;, ≤, &gt;, ≥). */
    R visit(ComparisonPredicate node);
    /** Visits a logical conjunction (∧). */
    R visit(AndPredicate node);
    /** Visits a logical disjunction (∨). */
    R visit(OrPredicate node);
    /** Visits a logical negation (¬). */
    R visit(NotPredicate node);
    /** Visits a null test (= ⊥ or ≠ ⊥). */
    R visit(NullPredicate node);
    /** Visits a set-membership test (∈ or ∉). */
    R visit(ElementOfPredicate node);
    /** Visits a pattern-matching test (LIKE or NOT LIKE). */
    R visit(PatternPredicate node);
}

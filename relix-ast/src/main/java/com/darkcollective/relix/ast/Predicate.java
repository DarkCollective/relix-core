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

/**
 * Root sealed interface for boolean filter conditions.
 *
 * <p>Predicates appear as the condition of a {@link SelectionNode} and as the
 * join condition of theta-style joins. The hierarchy supports comparison
 * ({@link ComparisonPredicate}), logical connectives ({@link AndPredicate},
 * {@link OrPredicate}, {@link NotPredicate}), null testing
 * ({@link NullPredicate}), set membership ({@link ElementOfPredicate}), and
 * pattern matching ({@link PatternPredicate}).
 *
 * @see com.darkcollective.relix.ast.visitor.PredicateVisitor
 */
public sealed interface Predicate permits
        ComparisonPredicate,
        AndPredicate,
        OrPredicate,
        NotPredicate,
        NullPredicate,
        ElementOfPredicate,
        PatternPredicate {
    <R> R accept(PredicateVisitor<R> visitor);

    /** Returns the source location of the first token of this predicate. */
    SourceLocation location();
}

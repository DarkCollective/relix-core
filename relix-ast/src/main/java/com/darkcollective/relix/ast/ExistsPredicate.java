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
 * {@code EXISTS (relation)}: true when the relation has a row, false when it has none, and
 * never UNKNOWN. {@code NOT EXISTS} is a {@link NotPredicate} over it.
 *
 * <p>The relation may name the columns of the row being tested, qualified, as a
 * {@link ScalarSubqueryOperand} may.
 *
 * @param query    the relation tested for a row; must not be null
 * @param location the source location of {@code EXISTS}; never null
 */
public record ExistsPredicate(RelNode query, SourceLocation location) implements Predicate {

    public ExistsPredicate {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public ExistsPredicate(RelNode query) {
        this(query, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(PredicateVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

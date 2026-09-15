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
 * Logical negation (¬) — true when {@code predicate} is false.
 *
 * <p>Example: {@code ¬(archived = true)}
 *
 * @param predicate the predicate to negate; must not be null
 * @param location  the source location of this predicate; never null
 */
public record NotPredicate(Predicate predicate, SourceLocation location) implements Predicate {
    public NotPredicate {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public NotPredicate(Predicate predicate) {
        this(predicate, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(PredicateVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

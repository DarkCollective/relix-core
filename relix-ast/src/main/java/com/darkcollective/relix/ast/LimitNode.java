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
import java.util.Optional;

/**
 * Represents a limit operation (λ) in relational algebra.
 * Returns up to a specified number of tuples, optionally starting from an offset.
 *
 * @param offset   the number of tuples to skip; absent means no offset
 * @param count    the maximum number of tuples to return; must not be negative
 * @param input    the source relation; must not be null
 * @param location the source location of this node; never null
 */
public record LimitNode(Optional<Long> offset, Long count, RelNode input, SourceLocation location) implements RelNode {
    public LimitNode {
        if (offset.isPresent() && offset.get() < 0) {
            throw new IllegalArgumentException("Offset cannot be negative");
        }
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative");
        }
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public LimitNode(Optional<Long> offset, Long count, RelNode input) {
        this(offset, count, input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

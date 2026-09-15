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

import java.util.List;
import java.util.Objects;

/**
 * Represents a sort operation (τ) in relational algebra.
 * Sorts tuples by specified attributes in given directions.
 *
 * @param sortSpecs the sort specifications; must not be empty
 * @param input     the source relation; must not be null
 * @param location  the source location of this node; never null
 */
public record SortNode(List<SortSpecification> sortSpecs, RelNode input, SourceLocation location) implements RelNode {
    public SortNode {
        sortSpecs = List.copyOf(sortSpecs);
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        if (sortSpecs.isEmpty()) {
            throw new IllegalArgumentException("Sort requires at least one specification");
        }
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public SortNode(List<SortSpecification> sortSpecs, RelNode input) {
        this(sortSpecs, input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

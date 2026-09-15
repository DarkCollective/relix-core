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

/**
 * Symmetric difference (∆) — returns tuples that appear in exactly one of
 * {@code left} or {@code right}, but not in both, eliminating duplicates.
 *
 * <p>It is the union of the two one-sided differences:
 * {@code A ∆ B ≡ (A − B) ∪ (B − A)}.  SQL has no symmetric-difference operator;
 * this single operator replaces that double-difference idiom.  Like the other
 * set operators its two inputs must be union-compatible (equal width).
 *
 * <p>Example: {@code (A) ∆ (B)}
 *
 * @param left     the left input relation; must not be null
 * @param right    the right input relation; must not be null
 * @param location the source location of this node; never null
 */
public record SymmetricDifferenceNode(RelNode left, RelNode right, SourceLocation location)
        implements RelNode {
    public SymmetricDifferenceNode {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public SymmetricDifferenceNode(RelNode left, RelNode right) {
        this(left, right, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

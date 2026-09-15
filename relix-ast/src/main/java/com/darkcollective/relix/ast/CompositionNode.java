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
 * Relational composition (∘) — composes two relations on their shared columns,
 * like function composition.  For {@code R(a, b) ∘ S(b, c)} the result relates
 * {@code a} to {@code c}: it natural-joins the two inputs on their common
 * columns and then projects those shared columns away.
 *
 * <p>Equivalent to {@code π (non-shared columns) (R ⋈ S)}, but composition is a
 * named algebraic operation in its own right (and division's sibling), so it is
 * a first-class operator rather than that join-then-project idiom.
 *
 * <p>Example: {@code (R) ∘ (S)}
 *
 * @param left     the left input relation; must not be null
 * @param right    the right input relation; must not be null
 * @param location the source location of this node; never null
 */
public record CompositionNode(RelNode left, RelNode right, SourceLocation location)
        implements RelNode {
    public CompositionNode {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public CompositionNode(RelNode left, RelNode right) {
        this(left, right, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

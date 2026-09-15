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
 * General monotone recursion — the least-fixpoint binder {@code FIX}.
 *
 * <p>{@code FIX name (base, step)} binds the recursive relation {@code name} over
 * the recursive body {@code step}. The {@link #base()} is the non-recursive seed;
 * the {@link #step()} is the recursive case, evaluated repeatedly with {@code name}
 * bound to the relation accumulated so far. Occurrences of {@code name} inside
 * {@code step} are represented by {@link RecursiveRefNode} leaves (the binder is
 * lexically scoped — {@code name} is visible only within {@code step}, never within
 * {@code base} or outside the {@code FIX}). The fixpoint is the union of {@code base}
 * with every {@code step} iterate, computed under set semantics (each round dedups
 * and a tuple already derived is never re-added), so the iteration terminates — even
 * over cyclic input data.
 *
 * <p>This is the {@code WITH RECURSIVE} / single-rule Datalog equivalent; binary
 * transitive closure ({@link ClosureNode}) is its two-column special case. The
 * operator materialises a {@link MaterializationMode#SET set} and never pushes down
 * to a source.
 *
 * <p>Surface syntax: {@code FIX R (base, step)} (keyword-only, no glyph).
 *
 * @param name     the bound recursive relation name; must not be blank
 * @param base     the non-recursive seed relation; must not be null
 * @param step     the recursive body (references {@code name} via {@link RecursiveRefNode});
 *                 must not be null
 * @param location the source location of this node; never null
 */
public record FixpointNode(String name, RelNode base, RelNode step, SourceLocation location)
        implements RelNode {

    public FixpointNode {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Fixpoint name must not be blank");
        }
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public FixpointNode(String name, RelNode base, RelNode step) {
        this(name, base, step, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

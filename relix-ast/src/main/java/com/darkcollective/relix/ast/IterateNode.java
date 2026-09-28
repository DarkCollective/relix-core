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
 * Replace-each-round iteration — the binder {@code ITERATE}.
 *
 * <p>{@code ITERATE name (base, step) <stop>} binds {@code name} over {@code step}
 * exactly as {@link FixpointNode} does: occurrences of {@code name} inside
 * {@code step} are {@link RecursiveRefNode} leaves, and {@code name} is not in scope
 * in {@code base}. What differs is what a round does with the step's output.
 * {@code FIX} <em>adds</em> it to everything derived so far; {@code ITERATE}
 * <em>replaces</em> the relation with it. The relation bound to {@code name} in a
 * round is the whole of the previous round's output, and the result is the last
 * round's output.
 *
 * <p>Because nothing accumulates, the step is free of {@code FIX}'s restrictions: it
 * may reference {@code name} any number of times and through any operator —
 * aggregation, outer joins and difference included — which is what numeric
 * iterations such as PageRank and state machines such as cellular automata need.
 * The price is that nothing guarantees it stops, so {@link #stop()} says when it
 * does (see {@link IterateStop}).
 *
 * <p>The operator materialises a {@link MaterializationMode#SET set}: each round is
 * deduplicated, and {@link IterateStop.Stable} compares rounds as sets. It never
 * pushes down to a source.
 *
 * <p>Surface syntax: {@code ITERATE R (base, step) ROUNDS n},
 * {@code … UNTIL STABLE MAX n ROUNDS}, or
 * {@code … UNTIL c WITHIN ε PER k MAX n ROUNDS} (keyword-only, no glyph).
 *
 * @param name     the bound relation name; must not be blank
 * @param base     the relation the first round reads; must not be null
 * @param step     the body computing each round from the previous one; must not be null
 * @param stop     when the iteration stops; must not be null
 * @param location the source location of this node; never null
 */
public record IterateNode(String name, RelNode base, RelNode step, IterateStop stop,
                          SourceLocation location) implements RelNode {

    public IterateNode {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Iterate name must not be blank");
        }
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stop, "stop");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public IterateNode(String name, RelNode base, RelNode step, IterateStop stop) {
        this(name, base, step, stop, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

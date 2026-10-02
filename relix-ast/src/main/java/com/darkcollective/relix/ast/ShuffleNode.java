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
 * Random permutation (SHUFFLE) — returns every row of {@code input} exactly once,
 * in a uniformly random order.  It is the random-ordering sibling of {@code τ}
 * (SORT): where sort imposes a deterministic key order, shuffle imposes a random
 * one.
 *
 * <p>Cardinality is unchanged — this is the distinction from {@code SAMPLE n ROWS},
 * which also returns rows out of input order but <em>changes</em> the row count.
 * A full permutation requires holding every row, so SHUFFLE is a <b>blocking</b>
 * operator (Fisher–Yates over the buffered rows) and, like SORT, is rejected over a
 * provably unbounded input by the plan-time blocking-operator check.
 *
 * <p><b>Reproducibility:</b> when {@code seed} is present the permutation is
 * deterministic — identical runs produce the identical order.  Without a seed,
 * fresh randomness is drawn each run, exactly as unseeded {@code SAMPLE} does.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code SHUFFLE (Deck)} — a random permutation (non-deterministic)</li>
 *   <li>{@code SHUFFLE SEED 42 (Deck)} — a reproducible permutation</li>
 * </ul>
 *
 * @param seed     the optional RNG seed for a reproducible permutation; empty = non-deterministic
 * @param input    the relation to permute; must not be null
 * @param location the source location of this node; never null
 */
public record ShuffleNode(Optional<Long> seed, RelNode input, SourceLocation location)
        implements RelNode {
    public ShuffleNode {
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN} and no seed. */
    public ShuffleNode(RelNode input) {
        this(Optional.empty(), input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

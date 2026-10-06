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
 * Endless uniform draw with replacement (ROLL) — a die roll.  Given a finite
 * relation of "faces" ({@code input}), ROLL emits an <b>unbounded</b> relation
 * with the same heading, where each output row is an independent uniform draw from
 * the faces.  A six-row input is a six-sided die; each pull rolls it again.
 *
 * <p>It is the random-source sibling of the generator relations
 * ({@code Naturals}, {@code Primes}, {@code Range}) and the with-replacement
 * counterpart of the without-replacement random operators — {@code SAMPLE n ROWS}
 * (a subset) and {@link ShuffleNode} (a permutation).  Compose {@code λ} (LIMIT) to
 * take a finite number of rolls: {@code λ 3 (ROLL SEED 7 (Faces))}.
 *
 * <p><b>Boundedness — the inversion of a blocking operator.</b> ROLL's output is
 * totally unbounded regardless of input size, so it is the first non-leaf operator
 * that <em>produces</em> {@link com.darkcollective.relix.ast.MaterializationMode#STREAM
 * unboundedness}; a blocking operator above it (τ, γ, SHUFFLE, …) is rejected the
 * same way it is over any endless generator.  Its input, by contrast, must be
 * materialisable — the face set is buffered to be indexed — so an unbounded input
 * is itself a plan-time error.
 *
 * <p><b>Reproducibility:</b> when {@code seed} is present the sequence of draws is
 * deterministic — the k-th emitted row is a function of the seed and k.  Without a
 * seed, {@code ThreadLocalRandom} supplies fresh randomness each run, exactly as
 * unseeded {@code SAMPLE} and {@code SHUFFLE} do.
 *
 * <p><b>Weighted draws (a loaded die).</b> When {@code weight} is present, each face
 * is drawn with probability proportional to that non-negative {@code NUMBER}
 * expression evaluated over the face's columns — {@code ROLL BY w (Faces)}. A
 * zero-weight face is never drawn, a negative weight is an error, and an all-zero (or
 * empty) face set yields no rows, exactly as an empty {@code ROLL} does. Without it,
 * every face is equally likely.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code LIMIT 1 (ROLL (Die))} — one random face (non-deterministic)</li>
 *   <li>{@code LIMIT 3 (ROLL SEED 7 (Die))} — three reproducible rolls</li>
 *   <li>{@code LIMIT 3 (ROLL BY weight SEED 7 (Loot))} — three weighted draws</li>
 * </ul>
 *
 * @param seed     the optional RNG seed for a reproducible sequence of draws; empty = non-deterministic
 * @param weight   the optional per-face weight expression; empty = uniform draws
 * @param input    the finite relation of faces to draw from; must not be null
 * @param location the source location of this node; never null
 */
public record RollNode(Optional<Long> seed, Optional<Operand> weight, RelNode input,
                       SourceLocation location) implements RelNode {
    public RollNode {
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(weight, "weight");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN} and no seed or weight. */
    public RollNode(RelNode input) {
        this(Optional.empty(), Optional.empty(), input, SourceLocation.UNKNOWN);
    }

    /** Convenience constructor for an unweighted roll — the pre-weighting shape. */
    public RollNode(Optional<Long> seed, RelNode input, SourceLocation location) {
        this(seed, Optional.empty(), input, location);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

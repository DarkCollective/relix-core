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
 * Covering-reduction node — {@code COVER [EXACT] t (R)}.
 *
 * <p>{@code COVER t (R)} keeps a near-minimal subset of {@code R}'s rows such that
 * every distinct t-column value combination occurring in {@code R} occurs in the
 * output (PICT-style pairwise / t-way combinatorial test generation). It is a
 * windowed filter — output ⊆ input, output schema = input schema — the third
 * subset-selection operator after {@code TOP} and {@code OPTIMIZE}.
 *
 * <p>The {@link #strength()} is the covering strength {@code t}: a positive integer
 * specifying how many columns must be covered in every combination. Strength 2
 * (all-pairs) is the most common case.
 *
 * <p>When {@link #exact()} is {@code true} ({@code COVER EXACT t (R)}), the executor
 * uses a MIP set-cover formulation (via ojAlgo) to find a provably minimal suite
 * instead of the default greedy near-minimal algorithm.
 *
 * <p>Surface syntax: {@code COVER t (R)} or {@code COVER EXACT t (R)} (keyword-only, no glyph).
 *
 * @see com.darkcollective.relix.ast.TopKNode
 * @see com.darkcollective.relix.ast.OptimizeNode
 */
public record CoverNode(int strength, boolean exact, RelNode input, SourceLocation location)
        implements RelNode {

    public CoverNode {
        if (strength < 1) {
            throw new IllegalArgumentException("COVER strength must be ≥ 1, got " + strength);
        }
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
    }

    /** Backward-compatible constructor: {@code exact=false}. */
    public CoverNode(int strength, RelNode input, SourceLocation location) {
        this(strength, false, input, location);
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public CoverNode(int strength, boolean exact, RelNode input) {
        this(strength, exact, input, SourceLocation.UNKNOWN);
    }

    /** Convenience constructor for tests: {@code exact=false}, {@link SourceLocation#UNKNOWN}. */
    public CoverNode(int strength, RelNode input) {
        this(strength, false, input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

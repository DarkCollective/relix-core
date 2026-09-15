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
 * Reservoir (fixed-count) sampling (SAMPLE … ROWS) — keeps exactly {@code count}
 * rows of {@code input}, chosen uniformly at random without replacement.
 *
 * <p>Where {@link SampleNode} ({@code SAMPLE 0.1}) is a per-row coin flip whose
 * result size only <em>averages</em> {@code p × |input|}, this is the
 * <em>count</em>-based form: {@code SAMPLE 100 ROWS (R)} returns exactly 100 rows
 * (or all of them when the input has fewer than {@code count}).  It is the
 * composable, algebraic form of the {@code ORDER BY RANDOM() LIMIT n} idiom — but
 * without the full sort.
 *
 * <p><b>Reproducibility:</b> when {@code seed} is present the sampling is
 * deterministic — identical runs produce identical row selections via Vitter's
 * Algorithm R seeded with the given value.  Without a seed, fresh randomness is
 * drawn each run.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code SAMPLE 100 ROWS (Events)} — keeps a uniform 100-row sample (non-deterministic)</li>
 *   <li>{@code SAMPLE 500 ROWS SEED 2026 (Events)} — reproducible fixed-count sample</li>
 * </ul>
 *
 * @param count    the exact number of rows to keep; non-negative
 * @param seed     the optional RNG seed for reproducible sampling; empty = non-deterministic
 * @param input    the relation to sample; must not be null
 * @param location the source location of this node; never null
 */
public record ReservoirSampleNode(long count, Optional<Long> seed, RelNode input, SourceLocation location)
        implements RelNode {
    public ReservoirSampleNode {
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative");
        }
    }

    /** Convenience constructor without seed (non-deterministic); uses provided location. */
    public ReservoirSampleNode(long count, RelNode input, SourceLocation location) {
        this(count, Optional.empty(), input, location);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN} and no seed. */
    public ReservoirSampleNode(long count, RelNode input) {
        this(count, Optional.empty(), input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

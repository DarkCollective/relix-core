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
 * Bernoulli sampling (SAMPLE) — independently keeps each row of {@code input}
 * with probability {@code probability} (a fraction in {@code [0, 1]}).
 *
 * <p>It is the streaming, composable form of {@code TABLESAMPLE}: every row is an
 * independent coin flip, so the result size is approximately
 * {@code probability × |input|} and varies from run to run.
 *
 * <p><b>Reproducibility:</b> when {@code seed} is present the sampling is
 * deterministic — identical runs produce identical row selections.  Without a
 * seed, fresh randomness is drawn each run.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code SAMPLE 0.1 (Events)} — keeps roughly 10% of the rows (non-deterministic)</li>
 *   <li>{@code SAMPLE 0.8 SEED 42 (HistoricalData)} — reproducible 80% sample</li>
 * </ul>
 *
 * @param probability the per-row keep probability, in {@code [0, 1]}
 * @param seed        the optional RNG seed for reproducible sampling; empty = non-deterministic
 * @param input       the relation to sample; must not be null
 * @param location    the source location of this node; never null
 */
public record SampleNode(double probability, Optional<Long> seed, RelNode input, SourceLocation location)
        implements RelNode {
    public SampleNode {
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor without seed (non-deterministic); uses provided location. */
    public SampleNode(double probability, RelNode input, SourceLocation location) {
        this(probability, Optional.empty(), input, location);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN} and no seed. */
    public SampleNode(double probability, RelNode input) {
        this(probability, Optional.empty(), input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

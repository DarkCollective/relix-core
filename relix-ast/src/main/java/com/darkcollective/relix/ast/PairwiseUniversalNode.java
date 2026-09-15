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
 * Represents a pairwise universal semi-join (USEMI), the ∀ dual of semi-join (⋉).
 *
 * <p>Keeps left rows {@code ℓ} such that {@code ∀ r ∈ R : θ(ℓ, r)} — i.e., every
 * row in the right relation satisfies the condition when paired with the left row.
 * If the right relation is empty, all left rows pass (vacuously true).
 *
 * <p>Strict NULL semantics: a predicate that evaluates to UNKNOWN counts as not
 * satisfied, so any UNKNOWN match disqualifies the left row. This matches the
 * {@code NOT EXISTS (… WHERE NOT θ)} reading.
 *
 * <p>The output schema equals the left input schema (right columns are never in
 * the output), mirroring semi-join and anti-join.
 *
 * @param left      the left input relation; must not be null
 * @param right     the right input relation; must not be null
 * @param condition the join predicate; must not be null
 * @param location  the source location of this node; never null
 */
public record PairwiseUniversalNode(RelNode left, RelNode right, Predicate condition, SourceLocation location)
        implements ConditionalJoinNode {
    public PairwiseUniversalNode {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public PairwiseUniversalNode(RelNode left, RelNode right, Predicate condition) {
        this(left, right, condition, SourceLocation.UNKNOWN);
    }

    @Override
    public PairwiseUniversalNode rebuild(RelNode left, RelNode right, Predicate condition) {
        return new PairwiseUniversalNode(left, right, condition, location());
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

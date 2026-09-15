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

/**
 * The uniform shape shared by every condition-carrying binary join:
 * {@code (left, right, condition, location)}.
 *
 * <p>Seven of the join operators — θ-join, the three outer joins, semi-join,
 * anti-join, and the pairwise universal semi-join — differ only in their
 * runtime semantics; structurally they are identical. Consumers that treat
 * them uniformly (predicate/expression simplification, parameter substitution,
 * structural traversal) can match one {@code case ConditionalJoinNode j}
 * instead of seven copy-pasted arms, and reconstruct via
 * {@link #rebuild(RelNode, RelNode, Predicate)} without losing the concrete
 * type or its source location.
 *
 * <p>Consumers whose behaviour <em>differs</em> per join type (the planner's
 * algorithm choice, selection pushdown's side rules, cost estimation, IR
 * labels) should keep matching the concrete types — the sealed hierarchy keeps
 * those switches exhaustive either way.
 *
 * <p>{@link AsOfJoinNode} and {@link IntervalJoinNode} are deliberately
 * <em>not</em> members: the AS-OF join carries tolerance/window/tie-break
 * state beyond the uniform shape, and the interval join has endpoint columns
 * instead of a single predicate.
 */
public sealed interface ConditionalJoinNode extends RelNode
        permits ThetaJoinNode, LeftOuterJoinNode, RightOuterJoinNode,
                FullOuterJoinNode, SemiJoinNode, AntiJoinNode,
                PairwiseUniversalNode {

    /** The left input relation; never null. */
    RelNode left();

    /** The right input relation; never null. */
    RelNode right();

    /** The join predicate; never null. */
    Predicate condition();

    /**
     * Reconstructs this node with new inputs and/or condition, preserving the
     * concrete join type and this node's {@link #location()}.
     *
     * @param left      the new left input; must not be null
     * @param right     the new right input; must not be null
     * @param condition the new join predicate; must not be null
     * @return a node of the same concrete type; never null
     */
    ConditionalJoinNode rebuild(RelNode left, RelNode right, Predicate condition);
}

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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.plan.internal.Planner;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * The estimated row count the planner computed for each node of a physical plan —
 * the numbers behind {@code Planner.buildSide} and the merge-versus-hash choice,
 * kept so {@code :explain} can show them.
 *
 * <h2>Why beside the plan and not on it</h2>
 * <p>{@link PhysicalNode} is a sealed hierarchy of <em>records</em>. Carrying the
 * estimate as a component would touch every arm and every construction site, and —
 * the deciding argument — it would make the estimate part of <strong>node
 * identity</strong>: two structurally identical plans costed under different
 * statistics would stop being {@code equals}, which is a property the plan itself
 * has no business having. A side table keeps the nodes clean and matches what
 * {@code SchemaAnnotations} already does for logical nodes.
 *
 * <h2>Identity, not equality</h2>
 * <p>The map is an {@link IdentityHashMap}, because two sibling nodes of a plan can
 * easily be {@code equals} — {@code A ⨝ A} is a legal self-join, and its two
 * {@code Scan}s are equal records — while being estimated in different contexts.
 * Keying on equality would let one overwrite the other.
 *
 * <h2>Unknown is not zero</h2>
 * <p>{@link #rows} returns {@link OptionalLong#empty()} both for a node the planner
 * never estimated and for one whose estimate the cost model declined to make.
 * That is deliberately <em>not</em> collapsed to {@code 0}: the estimator is
 * documented as honest, returning empty rather than fabricating a number when a
 * contributing leaf has no row count, and a plan step that will produce nothing is a
 * materially different claim from one nobody costed. Renderers must keep the two
 * apart — {@code rows=?} versus {@code rows=0}, {@code null} versus {@code 0} in
 * JSON.
 *
 * <p>Instances are mutable during planning and effectively immutable afterwards;
 * they are not thread-safe, and a {@code Planner} plans one root.
 */
public final class PlanEstimates {

    private static final PlanEstimates NONE = new PlanEstimates();

    private final Map<PhysicalNode, Long> rows = new IdentityHashMap<>();

    /** Creates an empty set of estimates. */
    public PlanEstimates() {
    }

    /**
     * Returns a shared empty instance — every lookup is unknown.  Use it where a plan
     * is rendered outside a planning run (a hand-built plan in a test, or a printer
     * call that has no estimates to hand).
     *
     * @return the empty estimates; never null
     */
    public static PlanEstimates none() {
        return NONE;
    }

    /**
     * Records {@code estimate} for {@code node}, if it is present.  An absent
     * estimate is not stored, so "unknown" has exactly one representation.
     *
     * @param node     the plan node; must not be null
     * @param estimate the estimated row count, or empty if the cost model declined
     */
    public void record(PhysicalNode node, OptionalLong estimate) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(estimate, "estimate");
        if (estimate.isPresent()) {
            rows.put(node, estimate.getAsLong());
        }
    }

    /**
     * Returns the estimated row count for {@code node}.
     *
     * @param node the plan node; must not be null
     * @return the estimate, or empty when unknown — which is <em>not</em> zero
     */
    public OptionalLong rows(PhysicalNode node) {
        Objects.requireNonNull(node, "node");
        Long value = rows.get(node);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    /**
     * Returns whether any estimate at all was recorded — the cheap test a renderer
     * uses to decide whether to add a numeric column.
     *
     * @return {@code true} if at least one node has a known estimate
     */
    public boolean isEmpty() {
        return rows.isEmpty();
    }
}

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
 * Allen's interval algebra relations used in {@link IntervalJoinNode}.
 *
 * <p>Each constant describes the temporal relationship between a left interval
 * {@code [ℓ.start, ℓ.end)} and a right interval {@code [r.start, r.end)}, using
 * half-open intervals.
 *
 * <p>The thirteen base relations of Allen's algebra form seven converse pairs
 * (with {@link #EQUALS} as its own converse): for every relation {@code X} there
 * is a converse {@code X_BY} such that {@code ℓ X r} iff {@code r X_BY ℓ}. Stating
 * the converse explicitly lets you keep the left and right inputs in their natural
 * order rather than swapping them. Beyond the thirteen, {@link #INTERSECTS} is a
 * convenience superset (any shared time) that is <em>not</em> one of Allen's base
 * relations.
 *
 * <p><b>Semantics.</b> All thirteen base relations use strict endpoint bounds
 * (Allen 1983), so they are mutually exclusive and jointly exhaustive — any pair
 * of intervals stands in exactly one of them. Shared-boundary cases are named by
 * their own relation ({@link #STARTS}, {@link #FINISHES}, {@link #EQUALS}) rather
 * than folded into {@link #DURING}/{@link #CONTAINS}. The only overlap is the
 * convenience {@link #INTERSECTS}, which by construction subsumes every relation
 * that shares any time.
 */
public enum AllenRelation {
    /**
     * Intervals overlap in some time: {@code ℓ.start < r.end ∧ r.start < ℓ.end}
     * (symmetric — left and right are interchangeable for this test). A
     * convenience superset; not one of Allen's thirteen base relations.
     */
    INTERSECTS,

    /**
     * Left starts before right and ends within it:
     * {@code ℓ.start < r.start ∧ ℓ.end > r.start ∧ ℓ.end < r.end}.
     * Converse of {@link #OVERLAPPED_BY}.
     */
    OVERLAPS,

    /**
     * Right starts before left and ends within it (converse of {@link #OVERLAPS}):
     * {@code r.start < ℓ.start ∧ r.end > ℓ.start ∧ r.end < ℓ.end}.
     */
    OVERLAPPED_BY,

    /**
     * Left interval is strictly inside the right (both endpoints interior):
     * {@code r.start < ℓ.start ∧ ℓ.end < r.end}. Converse of {@link #CONTAINS}.
     */
    DURING,

    /**
     * Left interval strictly contains the right (both endpoints interior;
     * converse of {@link #DURING}): {@code ℓ.start < r.start ∧ r.end < ℓ.end}.
     */
    CONTAINS,

    /**
     * Both intervals start together and left finishes first:
     * {@code ℓ.start = r.start ∧ ℓ.end < r.end}. Converse of {@link #STARTED_BY}.
     */
    STARTS,

    /**
     * Both intervals start together and right finishes first (converse of
     * {@link #STARTS}): {@code ℓ.start = r.start ∧ r.end < ℓ.end}.
     */
    STARTED_BY,

    /**
     * Both intervals finish together and left starts later:
     * {@code ℓ.end = r.end ∧ r.start < ℓ.start}. Converse of {@link #FINISHED_BY}.
     */
    FINISHES,

    /**
     * Both intervals finish together and right starts later (converse of
     * {@link #FINISHES}): {@code ℓ.end = r.end ∧ ℓ.start < r.start}.
     */
    FINISHED_BY,

    /**
     * The intervals are identical: {@code ℓ.start = r.start ∧ ℓ.end = r.end}.
     * Self-converse.
     */
    EQUALS,

    /**
     * Left ends exactly where right begins (adjacent, no gap):
     * {@code ℓ.end = r.start}. Converse of {@link #MET_BY}.
     */
    MEETS,

    /**
     * Left begins exactly where right ends (adjacent, no gap; converse of
     * {@link #MEETS}): {@code ℓ.start = r.end}.
     */
    MET_BY,

    /**
     * Left ends before right begins: {@code ℓ.end < r.start}.
     * Converse of {@link #PRECEDED_BY}.
     */
    PRECEDES,

    /**
     * Left begins after right ends (converse of {@link #PRECEDES}):
     * {@code ℓ.start > r.end}.
     */
    PRECEDED_BY
}

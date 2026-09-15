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
 * Aggregate function operators used in {@link AggregateFunction}.
 */
public enum AggregateOperator {
    /** Sum of values in a group. */
    SUM,
    /** Average (mean) of values in a group. */
    AVG,
    /** Count of rows (or non-null values) in a group. */
    COUNT,
    /** Minimum value in a group. */
    MIN,
    /** Maximum value in a group. */
    MAX,
    /**
     * Gathers every value in a group into an array, in row order (the NEST /
     * {@code array_agg} / Mongo {@code $push} operation).  Unlike the scalar
     * reducers above, {@code COLLECT} produces a nested array-valued output
     * column — the inverse of {@code UNNEST}.  It is how a query <em>produces</em>
     * nested output.
     */
    COLLECT,
    /**
     * Returns a companion column's value from the row that <em>maximises</em> the
     * ranking column — the "argmax" reduction.  Written {@code ARGMAX(rank, yield)}:
     * within each group it finds the row whose {@code rank} column is largest and
     * yields that row's {@code yield} column value (the
     * {@link AggregateFunction#yieldExpr() yield expression}).
     *
     * <p>This is the row-with-the-maximum / "groupwise maximum" operation that SQL
     * expresses only awkwardly (a window function plus a filter, or a self-join
     * back to a {@code MAX} subquery).  Ties resolve to the first such row in input
     * order; rows with a null rank are ignored; an empty group yields null.  Like
     * {@code COLLECT} it has no portable SQL spelling, so it is never pushed down.
     */
    ARGMAX,
    /**
     * The minimising counterpart of {@link #ARGMAX} — {@code ARGMIN(rank, yield)}
     * yields the {@code yield} column value from the row whose {@code rank} column
     * is smallest.
     */
    ARGMIN
}

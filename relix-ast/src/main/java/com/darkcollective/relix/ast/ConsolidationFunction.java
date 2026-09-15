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
 * Consolidation functions for the {@link DownsampleNode} time-series aggregation operator.
 *
 * <p>Applied within each time bucket to reduce a group of input rows to a single
 * summary value per column (or a row count for {@link #COUNT}).
 *
 * <p>Each constant is named after the aggregate that performs the reduction, and that
 * name is what the engine resolves: {@code MIN} here is the same {@code MIN} a
 * {@code γ} computes, over the same values, with the same answer for a bucket whose
 * values are all NULL. The enum is the operator's <em>vocabulary</em> — what the
 * grammar admits after {@code USING} — not a second definition of what the reduction
 * does.
 */
public enum ConsolidationFunction {
    /** Arithmetic mean of numeric column values in the bucket. */
    AVG,
    /** Smallest column value in the bucket, by the engine's value order. */
    MIN,
    /** Largest column value in the bucket, by the engine's value order. */
    MAX,
    /** Sum of numeric column values in the bucket. */
    SUM,
    /** Number of rows in the bucket; produces a single {@code count} output column. */
    COUNT;

    /**
     * Whether this reduction is about the rows themselves rather than a column's values,
     * so a bucket yields one {@code count} column and no per-column ones.
     *
     * @return {@code true} for {@link #COUNT}
     */
    public boolean countsRows() {
        return this == COUNT;
    }

    /**
     * Whether this reduction is defined only over numbers.
     *
     * <p>{@link #AVG} and {@link #SUM} arithmetic is; {@link #MIN} and {@link #MAX} are
     * comparisons, and the engine orders dates, times and strings as readily as numbers
     * — so those two consolidate a column of any scalar type. Both the schema rule and
     * the executor read this rather than each deciding for itself, which is what stops
     * the inferred heading and the produced rows disagreeing.
     *
     * @return {@code true} for {@link #AVG} and {@link #SUM}
     */
    public boolean numericOnly() {
        return this == AVG || this == SUM;
    }
}

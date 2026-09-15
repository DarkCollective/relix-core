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
package com.darkcollective.relix.symbol;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Per-column statistics used by the cost model to refine cardinality estimates.
 *
 * <p>Both fields are optional: a {@code -1}-style "unknown" is modelled with an
 * empty {@link OptionalLong} rather than a sentinel, so a consumer can tell
 * "zero distinct values" from "distinct count not collected".
 *
 * <p>{@code distinctCount} drives the cost model's grouped-aggregation, equi-join
 * and equality-selectivity estimates under the uniformity assumption;
 * {@code nullCount} gives {@code IS NULL} / {@code IS NOT NULL} an exact frequency
 * rather than an assumed one.  Neither carries a min/max or a histogram, which is
 * why a range predicate ({@code x > 5}) is unestimable and the cost model falls
 * back to a flat selectivity for it.
 *
 * @param distinctCount the number of distinct (non-null) values, if known
 * @param nullCount     the number of {@code NULL} values, if known
 */
public record ColumnStatistics(OptionalLong distinctCount, OptionalLong nullCount) {

    /** A column-statistics value carrying no information. */
    public static final ColumnStatistics UNKNOWN =
            new ColumnStatistics(OptionalLong.empty(), OptionalLong.empty());

    /**
     * @param distinctCount distinct non-null value count; must not be null
     * @param nullCount     null value count; must not be null
     */
    public ColumnStatistics {
        Objects.requireNonNull(distinctCount, "distinctCount");
        Objects.requireNonNull(nullCount, "nullCount");
    }
}

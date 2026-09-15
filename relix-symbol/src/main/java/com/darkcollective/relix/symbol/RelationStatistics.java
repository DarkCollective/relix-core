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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Optimizer-facing statistics about a relation — cardinality and structural
 * metadata gathered from a data source (e.g. a database catalog).
 *
 * <p>Every field is optional or possibly-empty: a value that was not collected
 * is represented by {@link OptionalLong#empty()} or an empty collection, never a
 * sentinel.  The canonical {@link #UNKNOWN} instance carries no information and
 * is the safe default for sources that expose no statistics.
 *
 * @param rowCount         the estimated number of rows, if known
 * @param columnStatistics per-column statistics, keyed by column name; never null
 * @param keys             candidate keys (each an ordered list of column names
 *                         that together uniquely identify a row); never null
 */
public record RelationStatistics(
        OptionalLong rowCount,
        Map<String, ColumnStatistics> columnStatistics,
        List<List<String>> keys) {

    /** A statistics value carrying no information; the default for stat-less sources. */
    public static final RelationStatistics UNKNOWN =
            new RelationStatistics(OptionalLong.empty(), Map.of(), List.of());

    /**
     * Validates and defensively copies the column-statistics map and key lists.
     *
     * @param rowCount         estimated row count; must not be null
     * @param columnStatistics column name → statistics; must not be null
     * @param keys             candidate keys; must not be null
     */
    public RelationStatistics {
        Objects.requireNonNull(rowCount, "rowCount");
        Objects.requireNonNull(columnStatistics, "columnStatistics");
        Objects.requireNonNull(keys, "keys");
        columnStatistics = Map.copyOf(columnStatistics);
        keys = keys.stream().map(List::copyOf).toList();
    }

    /**
     * Creates a statistics value carrying only a row count.
     *
     * @param rowCount the estimated number of rows; must be non-negative
     * @return a statistics value with the given row count and no other data
     */
    public static RelationStatistics of(long rowCount) {
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must be non-negative: " + rowCount);
        }
        return new RelationStatistics(OptionalLong.of(rowCount), Map.of(), List.of());
    }

    /**
     * Returns the statistics for {@code column}, if any were collected.
     *
     * @param column the column name (case-sensitive, as stored)
     * @return the column statistics, or empty if none were recorded
     */
    public Optional<ColumnStatistics> column(String column) {
        return Optional.ofNullable(columnStatistics.get(column));
    }
}

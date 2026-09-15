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
package com.darkcollective.relix.cost;

/**
 * Ordinal cost tier for a relation or sub-expression, used by
 * {@link CostEstimator} to rank data sources from cheapest to most expensive.
 *
 * <p>The natural {@link #ordinal()} ordering represents increasing I/O cost:
 * {@code INLINE < FILE < REMOTE}.  Rules that compare two inputs use
 * ordinal comparison to decide which is cheaper.
 *
 * <p>There is deliberately <strong>no {@code VIEW} tier</strong>.  A named query
 * view has no I/O of its own — its cost is entirely that of the leaves its body
 * reads — and {@link CostEstimator} says so directly: {@code tierFor} maps a
 * {@code QueryRelationSymbol} to a recursive estimate of {@code body()}, so no
 * code path could ever return a {@code VIEW} constant.
 *
 * <p>The {@link #label()} method returns a short lower-case string suitable
 * for embedding in transformation detail messages.
 */
public enum CostTier {

    /**
     * Inline relation literal — data is embedded directly in the script and
     * held entirely in memory at parse time.  Zero I/O cost.
     */
    INLINE("inline"),

    /**
     * File-backed source — local file I/O (e.g. CSV).  Moderate cost.
     */
    FILE("file"),

    /**
     * Remote database source — JDBC network round-trip.  Highest cost tier.
     */
    REMOTE("remote");

    private final String label;

    CostTier(String label) {
        this.label = label;
    }

    /**
     * Returns a short lower-case label suitable for display in transformation
     * detail messages (e.g. {@code "inline"}, {@code "remote"}).
     *
     * @return the label string; never null
     */
    public String label() {
        return label;
    }
}

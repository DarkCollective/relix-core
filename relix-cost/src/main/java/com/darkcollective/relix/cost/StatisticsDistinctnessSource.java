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

import com.darkcollective.relix.symbol.RelationStatistics;

import java.util.Objects;

/**
 * A {@link DistinctnessSource} that reads duplicate-freeness off a leaf relation's
 * declared keys — the base-relation half of the seam, complementing the
 * generator-backed source that covers {@code Range}/{@code Naturals}/{@code Primes}.
 *
 * <p>A relation with at least one candidate key in its {@link RelationStatistics}
 * is duplicate-free by definition: a key uniquely identifies a row, so no two rows
 * can be equal. This is what lets {@code DIST-001} remove a {@code δ} applied
 * directly to a primary-keyed table.
 *
 * <p>The keys come from wherever the statistics did — for JDBC relations, the
 * primary key read from {@code DatabaseMetaData.getPrimaryKeys}; for inline
 * relations, single-column uniqueness computed exactly. A relation with statistics
 * but <em>no</em> declared key reports {@code false}: a row count alone says nothing
 * about duplicates. Sources that are never introspected (CSV, Mongo, HTTP) carry no
 * statistics at all and so correctly report {@code false}.
 *
 * <p><strong>Leaves only.</strong> This answers for a {@code RelationNode} as it
 * stands; distinctness through a projection of a key is a separate and harder
 * question that {@link PropertyDeriver} deliberately does not attempt.
 *
 * @see DistinctnessSource#anyOf(DistinctnessSource...)
 */
public final class StatisticsDistinctnessSource implements DistinctnessSource {

    private final StatisticsSource statistics;

    /**
     * Creates a distinctness source over the given statistics.
     *
     * @param statistics the per-relation statistics lookup; must not be null
     *                   (use {@link StatisticsSource#NONE} for none, which makes
     *                   this source equivalent to {@link DistinctnessSource#NONE})
     */
    public StatisticsDistinctnessSource(StatisticsSource statistics) {
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} when the named relation has at least one candidate key
     */
    @Override
    public boolean duplicateFreeLeaf(String relationName) {
        Objects.requireNonNull(relationName, "relationName");
        return statistics.forRelation(relationName)
                .map(stats -> !stats.keys().isEmpty())
                .orElse(false);
    }
}

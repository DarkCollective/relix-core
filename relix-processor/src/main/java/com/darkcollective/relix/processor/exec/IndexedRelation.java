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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Schema;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.stream.Stream;

/**
 * A materialised relation whose rows are partitioned into groups by a
 * composite key — the {@code Relation} type in the IBM flat-collection
 * taxonomy (Key, Element Equality, Unordered, Multiple).
 *
 * <p>Groups are stored in insertion order ({@link LinkedHashMap}) so that
 * output row ordering is stable and deterministic across executions.
 *
 * <p>This is the primary build structure for:
 * <ul>
 *   <li>Aggregation ({@code γ}) — rows are grouped by the grouping
 *       attributes; each group is then reduced to a single aggregate
 *       output row.</li>
 *   <li>Hash joins (future tasks) — the smaller input is indexed on the
 *       join key; the probe side performs key lookups via
 *       {@link #group(List)}.</li>
 * </ul>
 *
 * @param schema the schema shared by all rows in every group; never null
 * @param groups a map from group-key (list of values, one per grouping
 *               attribute) to the rows that belong to that group; stored
 *               as an unmodifiable view; never null
 */
public record IndexedRelation(Schema schema, SequencedMap<List<Value>, List<Row>> groups)
        implements MaterializedRelation {

    public IndexedRelation {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(groups, "groups");
        groups = new LinkedHashMap<>(groups);   // defensive copy
    }

    /**
     * Builds an {@code IndexedRelation} by partitioning {@code rows} on the
     * given key extractor.  Insertion order of the first-seen key is
     * preserved.
     *
     * @param schema      the row schema
     * @param rows        the rows to index; consumed once
     * @param keyExtractor a function from row to composite key
     */
    public static IndexedRelation build(Schema schema, Stream<Row> rows,
                                        java.util.function.Function<Row, List<Value>> keyExtractor) {
        LinkedHashMap<List<Value>, List<Row>> map = new LinkedHashMap<>();
        rows.forEach(row -> map.computeIfAbsent(keyExtractor.apply(row),
                k -> new java.util.ArrayList<>()).add(row));
        return new IndexedRelation(schema, map);
    }

    /**
     * Returns the rows belonging to the given group key, or an empty list
     * if no such group exists.
     */
    public List<Row> group(List<Value> key) {
        return groups.getOrDefault(key, List.of());
    }

    /** Returns the number of distinct groups. */
    public int groupCount() {
        return groups.size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Iterates groups in insertion order, yielding all rows from each
     * group before moving to the next.
     */
    @Override
    public Stream<Row> stream() {
        return groups.values().stream().flatMap(Collection::stream);
    }
}

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
import com.darkcollective.relix.symbol.Schema;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A materialised relation that contains only unique rows, preserving the
 * order in which rows were first inserted — the {@code Set} type in the
 * IBM flat-collection taxonomy (No Key, Element Equality, Unordered, Unique).
 *
 * <p>Row equality is structural: two rows are equal if they have the same
 * schema and all corresponding values are equal (as defined by
 * {@code Row.equals}).  {@link java.util.LinkedHashSet} is used as the
 * backing store so that insertion order is stable across executions.
 *
 * <p>This is the natural output type for:
 * <ul>
 *   <li>{@code DISTINCT} (deduplication of an input bag).</li>
 *   <li>{@code UNION} (deduplication of two concatenated inputs).</li>
 *   <li>{@code INTERSECTION} (rows common to both inputs).</li>
 *   <li>{@code DIFFERENCE} (rows in the left input not in the right).</li>
 * </ul>
 *
 * @param schema the schema shared by all rows; never null
 * @param rows   the set of unique rows; stored as an unmodifiable view of a
 *               {@code LinkedHashSet}; never null
 */
public record SetRelation(Schema schema, SequencedSet<Row> rows) implements MaterializedRelation {

    public SetRelation {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(rows,   "rows");
        rows = new LinkedHashSet<>(rows);   // defensive copy, preserves order
    }

    /**
     * Builds a {@code SetRelation} from an ordered stream of rows, silently
     * discarding any duplicates (later occurrences of a row that has already
     * been seen are dropped).
     */
    public static SetRelation of(Schema schema, Iterable<Row> source) {
        LinkedHashSet<Row> set = new LinkedHashSet<>();
        source.forEach(set::add);
        return new SetRelation(schema, set);
    }

    /**
     * Returns {@code true} if this set contains a row structurally equal
     * to {@code row}.
     */
    public boolean contains(Row row) {
        return rows.contains(row);
    }

    /** Number of unique rows. */
    public int size() {
        return rows.size();
    }

    @Override
    public Stream<Row> stream() {
        return rows.stream();
    }
}

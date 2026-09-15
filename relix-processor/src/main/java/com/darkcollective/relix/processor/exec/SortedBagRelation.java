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

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A materialised relation whose rows are kept in a stable sorted order and
 * may contain duplicates — the {@code Sorted bag} type in the IBM
 * flat-collection taxonomy (No Key, No Element Equality required, Sorted,
 * Multiple).
 *
 * <p>The sort order is determined externally by the {@code SortNode} executor,
 * which collects the input rows, applies a {@link java.util.Comparator}, and
 * stores the result here.  The list is immutable after construction.
 *
 * <p>This is the natural output type for the {@code SORT} (τ) operator.
 * Downstream operators that consume a sorted relation (e.g. a {@code LIMIT}
 * applied after a {@code SORT}) receive a stream in the correct order without
 * any additional sorting.
 *
 * @param schema the schema shared by all rows; never null
 * @param rows   the sorted list of rows; a defensive copy is stored; never null
 */
public record SortedBagRelation(Schema schema, List<Row> rows) implements MaterializedRelation {

    public SortedBagRelation {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(rows,   "rows");
        rows = List.copyOf(rows);
    }

    /** Factory that accepts a mutable list and stores a defensive copy. */
    public static SortedBagRelation of(Schema schema, List<Row> rows) {
        return new SortedBagRelation(schema, rows);
    }

    @Override
    public Stream<Row> stream() {
        return rows.stream();
    }
}

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
 * A materialised relation that preserves insertion order and allows duplicate
 * rows — the {@code Bag} type in the IBM flat-collection taxonomy.
 *
 * <p>This is the natural output type for:
 * <ul>
 *   <li>Join operators (every matching pair is emitted, regardless of
 *       duplicates).</li>
 *   <li>{@code UNION ALL} (both inputs are concatenated without
 *       deduplication).</li>
 *   <li>Aggregation results (one row per group, but groups may produce
 *       identical values).</li>
 * </ul>
 *
 * @param schema the schema shared by all rows; never null
 * @param rows   the ordered list of rows; never null; a defensive copy is stored
 */
public record BagRelation(Schema schema, List<Row> rows) implements MaterializedRelation {

    public BagRelation {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(rows,   "rows");
        rows = List.copyOf(rows);
    }

    /** Factory that accepts a mutable list and stores a defensive copy. */
    public static BagRelation of(Schema schema, List<Row> rows) {
        return new BagRelation(schema, rows);
    }

    @Override
    public Stream<Row> stream() {
        return rows.stream();
    }
}

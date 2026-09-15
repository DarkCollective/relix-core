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

import java.util.stream.Stream;

/**
 * A relation that has been fully materialised into an in-memory collection.
 *
 * <p>This sealed hierarchy maps the IBM collection taxonomy to the collection
 * types produced by the relational algebra operators that require full
 * materialisation:
 *
 * <ul>
 *   <li>{@link BagRelation} — unordered, allows duplicates (join output,
 *       {@code UNION ALL}).</li>
 *   <li>{@link SetRelation} — unordered, unique rows by value equality
 *       ({@code DISTINCT}, {@code UNION}, {@code INTERSECTION},
 *       {@code DIFFERENCE}).</li>
 *   <li>{@link SortedBagRelation} — sorted, allows duplicates
 *       ({@code SORT}).</li>
 *   <li>{@link IndexedRelation} — rows partitioned by a group key;
 *       primary build structure for aggregation and hash joins.</li>
 * </ul>
 *
 * <p>Callers obtain a lazy {@code Stream<Row>} view via {@link #stream()};
 * the stream may be consumed exactly once and need not be closed (the
 * underlying collection holds the data).
 */
public sealed interface MaterializedRelation
        permits BagRelation, SetRelation, SortedBagRelation, IndexedRelation {

    /** The schema shared by every row in this relation. */
    Schema schema();

    /**
     * Returns a lazy stream over all rows in this relation.
     * The stream may be consumed exactly once.
     */
    Stream<Row> stream();
}

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
package com.darkcollective.relix.processor.reference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

/**
 * PIVOT and UNPIVOT, stated the way the manual states them.
 *
 * <p>"For a group identified by groupKey values G, and distinct key values seen across all
 * rows: one output row per distinct group; the groupKey columns followed by one column per
 * distinct key value, named by the key's string form; each cell holds the valueColumn
 * value from the row in G whose keyColumn equals that header, and is NULL when no such row
 * exists." And for the fold back: "for an input row and listed columns, one output row
 * each, carrying the non-listed columns unchanged plus the source column's name and its
 * cell."
 *
 * <p>Two questions the manual leaves open, which writing this down forces into the open
 * because a reference implementation cannot decline to answer: what happens when a group
 * holds <em>two</em> rows for one key (the manual says "the row", assuming there is one),
 * and what a row whose key is NULL contributes. Both are settled here the way the engine
 * settles them, so the test states a behaviour rather than discovering it twice.
 *
 * <p>Neither operator pushes down, so no agreement suite reaches either.
 */
final class ReshapeReference {

    private ReshapeReference() {
    }

    /** A row as column → value, a null value standing for NULL. Order is the column order. */
    record Wide(SequencedMap<String, String> cells) {
    }

    /**
     * The wide form of {@code rows}.
     *
     * @param rows     the long rows, each column → value with null for NULL
     * @param perCols  the grouping columns, in output order; empty for the global pivot
     * @param keyCol   the column whose values become headers
     * @param valueCol the column the cells are taken from
     * @return one wide row per distinct group, in first-seen order
     */
    static List<Wide> pivot(List<SequencedMap<String, String>> rows, List<String> perCols,
                            String keyCol, String valueCol) {
        Set<String> headers = new LinkedHashSet<>();
        SequencedMap<List<String>, Map<String, String>> cells = new LinkedHashMap<>();
        Map<List<String>, SequencedMap<String, String>> groupColumns = new LinkedHashMap<>();

        for (SequencedMap<String, String> row : rows) {
            List<String> group = perCols.stream().map(row::get).toList();
            groupColumns.computeIfAbsent(group, g -> {
                SequencedMap<String, String> kept = new LinkedHashMap<>();
                perCols.forEach(col -> kept.put(col, row.get(col)));
                return kept;
            });
            Map<String, String> forGroup = cells.computeIfAbsent(group, g -> new LinkedHashMap<>());

            String header = row.get(keyCol);
            if (header != null) {
                headers.add(header);
                // Last one wins. The manual says "the row whose keyColumn equals that
                // header", which presumes there is one; when there are two the later
                // overwrites the earlier, and nothing anywhere says so.
                forGroup.put(header, row.get(valueCol));
            }
            // A row whose key is NULL contributes no header — but it has still been seen,
            // so its group exists and is emitted, with NULL in every cell it never filled.
        }

        List<Wide> wide = new ArrayList<>(cells.size());
        cells.forEach((group, forGroup) -> {
            SequencedMap<String, String> out = new LinkedHashMap<>(groupColumns.get(group));
            headers.forEach(header -> out.put(header, forGroup.get(header)));
            wide.add(new Wide(out));
        });
        return wide;
    }

    /**
     * The long form of {@code rows} — one output row per listed column of every input row,
     * whether or not the cell holds anything.
     *
     * @param rows     the wide rows
     * @param columns  the columns to fold, in order
     * @param nameCol  the column the folded column's name goes into
     * @param valueCol the column its cell goes into
     * @return {@code rows.size() * columns.size()} rows, in input order
     */
    static List<SequencedMap<String, String>> unpivot(List<Wide> rows, List<String> columns,
                                                      String nameCol, String valueCol) {
        List<SequencedMap<String, String>> out = new ArrayList<>();
        for (Wide row : rows) {
            for (String column : columns) {
                SequencedMap<String, String> folded = new LinkedHashMap<>();
                row.cells().forEach((name, value) -> {
                    if (!columns.contains(name)) {
                        folded.put(name, value);   // pass-through, in its original order
                    }
                });
                folded.put(nameCol, column);
                folded.put(valueCol, row.cells().get(column));
                out.add(folded);
            }
        }
        return out;
    }
}

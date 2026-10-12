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

import com.darkcollective.relix.function.Blocking;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The indexed input of a similarity join: each row filed under the keys its value's
 * {@link Blocking} gives, so that a probe finds the few rows it could match.
 *
 * <p>A row whose value the blocking refuses is a <em>wildcard</em>, a candidate for
 * every probe; a probe it refuses is a candidate against every row. Either way the pair
 * reaches the join's condition, as it would in a nested loop, and the condition decides
 * it — so a value the function cannot read fails there, with the function's own message.
 */
final class SimilarityIndex {

    private final Blocking blocking;
    private final int rows;
    private final Map<Object, List<Integer>> postings = new HashMap<>();
    private final List<Integer> wildcards = new ArrayList<>();

    private SimilarityIndex(Blocking blocking, int rows) {
        this.blocking = blocking;
        this.rows = rows;
    }

    /**
     * Files every row of the indexed input under its keys.
     *
     * @param rows     the indexed input, in order
     * @param column   the column of each row passed to the function
     * @param blocking the function's blocking for the join's comparison
     * @return the index
     */
    static SimilarityIndex build(List<Row> rows, int column, Blocking blocking) {
        SimilarityIndex index = new SimilarityIndex(blocking, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Collection<?> keys = keys(blocking, rows.get(i).get(column), true);
            if (keys == null) {
                index.wildcards.add(i);
                continue;
            }
            for (Object key : keys) {
                index.postings.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
        }
        return index;
    }

    /**
     * The positions of the indexed rows a probe value could match, ascending and each
     * once, so that the pairs come out in the order a nested loop would emit them.
     *
     * @param probe the probing row's value
     * @return the candidate positions
     */
    int[] candidates(Value probe) {
        Collection<?> keys = keys(blocking, probe, false);
        if (keys == null) {
            int[] all = new int[rows];
            Arrays.setAll(all, i -> i);
            return all;
        }
        int size = wildcards.size();
        List<List<Integer>> found = new ArrayList<>();
        for (Object key : keys) {
            List<Integer> posting = postings.get(key);
            if (posting != null) {
                found.add(posting);
                size += posting.size();
            }
        }
        int[] positions = new int[size];
        int n = 0;
        for (int position : wildcards) {
            positions[n++] = position;
        }
        for (List<Integer> posting : found) {
            for (int position : posting) {
                positions[n++] = position;
            }
        }
        Arrays.sort(positions);
        return Arrays.stream(positions).distinct().toArray();
    }

    /** A value's keys, or {@code null} when the blocking cannot place it. */
    private static Collection<?> keys(Blocking blocking, Value value, boolean indexing) {
        try {
            return indexing ? blocking.indexKeys(value) : blocking.probeKeys(value);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }
}

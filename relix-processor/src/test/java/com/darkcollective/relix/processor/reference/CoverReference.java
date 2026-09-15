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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a covering suite has to satisfy, stated the way the manual states it.
 *
 * <p>"COVER keeps a subset of candidate rows such that every distinct t-column value
 * combination occurring in the input occurs in at least one output row."
 *
 * <p>Which is a <em>property</em> rather than an answer. A greedy search picks one suite
 * among many that satisfy it — the tie-break makes that pick deterministic, but it is
 * still a choice, so there is no list of rows to compare against and there should not be.
 * What can be compared is the guarantee: enumerate the combinations the candidates demand,
 * and ask which ones the suite does not carry.
 *
 * <p>The one thing a coverage check cannot say on its own is that the suite is worth
 * having — returning every candidate satisfies it completely. That claim belongs to the
 * caller, and is asserted separately as a reduction.
 *
 * <p>Public, unlike its neighbours here, because two test classes in different packages
 * ask this same question and a second copy of it is a second thing to be wrong.
 */
public final class CoverReference {

    private CoverReference() {
    }

    /**
     * One demanded combination: which columns, and the values they carry together.
     *
     * @param columns the column positions, ascending
     * @param values  the values at those positions, in the same order
     */
    public record Combination(List<Integer> columns, List<String> values) {

        public Combination {
            columns = List.copyOf(columns);
            values = List.copyOf(values);
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                text.append(i > 0 ? ", " : "").append("c").append(columns.get(i))
                        .append("=").append(values.get(i));
            }
            return "(" + text + ")";
        }
    }

    /**
     * Every distinct {@code t}-column combination the rows carry.
     *
     * @param rows the rows, each a list of that row's displayed values
     * @param t    the strength; 1 to the row width
     * @return the combinations, without duplicates
     */
    public static Set<Combination> combinations(List<List<String>> rows, int t) {
        Set<Combination> found = new LinkedHashSet<>();
        if (rows.isEmpty()) {
            return found;
        }
        for (List<Integer> columns : columnSubsets(rows.getFirst().size(), t)) {
            for (List<String> row : rows) {
                found.add(new Combination(columns, columns.stream().map(row::get).toList()));
            }
        }
        return found;
    }

    /**
     * The combinations {@code candidates} demand that {@code suite} does not carry — empty
     * when the suite covers, and otherwise naming exactly what is missing.
     *
     * @param candidates the rows the suite was chosen from
     * @param suite      the chosen rows
     * @param t          the strength
     * @return the uncovered combinations
     */
    public static Set<Combination> uncovered(List<List<String>> candidates,
                                             List<List<String>> suite, int t) {
        Set<Combination> demanded = new LinkedHashSet<>(combinations(candidates, t));
        demanded.removeAll(combinations(suite, t));
        return demanded;
    }

    /** Every ascending choice of {@code t} column positions out of {@code width}. */
    static List<List<Integer>> columnSubsets(int width, int t) {
        List<List<Integer>> subsets = new ArrayList<>();
        if (t < 1 || t > width) {
            return subsets;
        }
        int[] chosen = new int[t];
        for (int i = 0; i < t; i++) {
            chosen[i] = i;
        }
        while (true) {
            List<Integer> subset = new ArrayList<>(t);
            for (int index : chosen) {
                subset.add(index);
            }
            subsets.add(List.copyOf(subset));

            int i = t - 1;
            while (i >= 0 && chosen[i] == width - t + i) {
                i--;
            }
            if (i < 0) {
                return subsets;
            }
            chosen[i]++;
            for (int j = i + 1; j < t; j++) {
                chosen[j] = chosen[j - 1] + 1;
            }
        }
    }
}

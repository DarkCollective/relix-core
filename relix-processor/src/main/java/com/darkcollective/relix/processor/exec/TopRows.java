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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The best {@code bound} rows of a stream under a comparator, holding no more than that
 * many at a time.
 *
 * <p>It is what {@code TOP} means. The operator reads its whole input either way — there
 * is no way to know the last row is not the largest without looking at it — but what it
 * <em>holds</em> while doing so is the difference between {@code k} rows and the
 * relation, and between {@code n log n} comparisons and {@code n log k}. A row worse than
 * the worst already kept is rejected on one comparison and never enters the heap, so the
 * common case — an input that is not in sort order — costs one comparison per row.
 *
 * <h2>Ties keep the order a stable sort would give them</h2>
 * A full sort of the group followed by {@code skip}/{@code limit} is stable, so among
 * rows the sort keys cannot separate the earlier one wins. A heap is not stable on its
 * own and would keep whichever of two tied rows it happened to compare first, which is a
 * different <em>answer</em> rather than a different order: {@code TOP 1} over a tie
 * returns one of them. So every row carries the ordinal it arrived at and the comparison
 * falls through to it, which makes the retained set exactly the one a stable sort would
 * have produced.
 *
 * <p>With no sort keys at all the ordinal is the whole comparison, so the buffer keeps
 * the first {@code bound} rows in arrival order — the reading a stable sort by nothing
 * gives, and one the sort-then-slice form could not reach at all ({@code List.sort(null)}
 * asks two rows to compare themselves).
 */
final class TopRows {

    /** A row and when it arrived, so that ties keep the order a stable sort would give them. */
    private record Arrival(long ordinal, Row row) {
    }

    private final int bound;
    private final Comparator<Arrival> best;
    private final PriorityQueue<Arrival> worstFirst;
    private long arrivals;

    /**
     * @param bound the most rows to hold; zero holds none
     * @param order the row order to keep the smallest of, or {@code null} to keep the
     *              rows that arrived first
     */
    TopRows(int bound, Comparator<Row> order) {
        this.bound = bound;
        this.best = order == null
                ? Comparator.comparingLong(Arrival::ordinal)
                : Comparator.<Arrival, Row>comparing(Arrival::row, order)
                        .thenComparingLong(Arrival::ordinal);
        this.worstFirst = new PriorityQueue<>(Math.min(bound, 16) + 1, best.reversed());
    }

    /**
     * Offers {@code row} to the buffer.
     *
     * @return whether the buffer grew — {@code false} both for a row turned away and for
     *         one that displaced a row already held, since neither changes what is held
     */
    boolean offer(Row row) {
        if (worstFirst.size() < bound) {
            worstFirst.add(new Arrival(arrivals++, row));
            return true;
        }
        Arrival arrival = new Arrival(arrivals++, row);
        if (bound == 0 || best.compare(arrival, worstFirst.peek()) >= 0) {
            return false;
        }
        worstFirst.poll();
        worstFirst.add(arrival);
        return false;
    }

    /** The rows held, in the order the comparator puts them. */
    List<Row> ordered() {
        List<Arrival> held = new ArrayList<>(worstFirst);
        held.sort(best);
        List<Row> rows = new ArrayList<>(held.size());
        for (Arrival arrival : held) {
            rows.add(arrival.row());
        }
        return rows;
    }
}

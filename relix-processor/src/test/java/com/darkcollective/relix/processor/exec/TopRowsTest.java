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

import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounded ranking buffer behind {@code TOP}.
 *
 * <p>Its whole claim is that it holds {@code bound} rows and still answers what a full
 * sort followed by {@code limit} would have answered, so every test here is written as
 * that comparison: the same rows through {@link java.util.List#sort} and through the
 * buffer must agree, including where the comparator cannot separate them.
 */
@DisplayName("TopRows — the best n rows, holding n")
final class TopRowsTest extends ProcessorTestSupport {

    private static final Schema SCHEMA = schema("id", "rank");

    /** A row whose {@code id} identifies it and whose {@code rank} is what it is sorted on. */
    private static Row row(int id, int rank) {
        return row(SCHEMA, num(id), num(rank));
    }

    /** Ascending by {@code rank} alone, so equal ranks are for the buffer to break. */
    private static final Comparator<Row> BY_RANK =
            Comparator.comparingInt(r -> Integer.parseInt(r.get("rank").asDisplayString()));

    private static List<Integer> ids(List<Row> rows) {
        return rows.stream().map(r -> Integer.parseInt(r.get("id").asDisplayString())).toList();
    }

    /** What a full sort then {@code limit} would keep — the answer the buffer must match. */
    private static List<Integer> sortedThenLimited(List<Row> input, int bound) {
        List<Row> sorted = new ArrayList<>(input);
        sorted.sort(BY_RANK);
        return ids(sorted.stream().limit(bound).toList());
    }

    private static TopRows fill(List<Row> input, int bound, Comparator<Row> order) {
        TopRows buffer = new TopRows(bound, order);
        input.forEach(buffer::offer);
        return buffer;
    }

    @Nested
    @DisplayName("what it keeps")
    class WhatItKeeps {

        @Test
        @DisplayName("the smallest n under the comparator, in that order")
        void keepsTheSmallest() {
            List<Row> input = List.of(row(1, 50), row(2, 10), row(3, 40), row(4, 20), row(5, 30));
            assertThat(ids(fill(input, 3, BY_RANK).ordered())).containsExactly(2, 4, 5);
            assertThat(ids(fill(input, 3, BY_RANK).ordered())).isEqualTo(sortedThenLimited(input, 3));
        }

        @Test
        @DisplayName("every row, when the bound is wider than the input")
        void boundWiderThanInput() {
            List<Row> input = List.of(row(1, 20), row(2, 10));
            assertThat(ids(fill(input, 10, BY_RANK).ordered())).containsExactly(2, 1);
        }

        @Test
        @DisplayName("nothing, when the bound is zero")
        void zeroBound() {
            List<Row> input = List.of(row(1, 20), row(2, 10));
            assertThat(fill(input, 0, BY_RANK).ordered()).isEmpty();
        }

        @Test
        @DisplayName("nothing, from an input with no rows")
        void emptyInput() {
            assertThat(fill(List.of(), 3, BY_RANK).ordered()).isEmpty();
        }

        /**
         * {@code λ offset, count} asks for {@code offset + count} rows, and both are
         * longs the grammar takes as written — so the sum can overflow one, and the
         * buffer needs an int. A bound past the largest list the JVM can hold is the
         * same bound as none, which is what the buffer has to behave like.
         */
        @Test
        @DisplayName("every row, when the bound is the largest there is")
        void theLargestBound() {
            List<Row> input = List.of(row(1, 20), row(2, 10));
            assertThat(ids(fill(input, Integer.MAX_VALUE, BY_RANK).ordered()))
                    .containsExactly(2, 1);
        }
    }

    @Nested
    @DisplayName("ties keep the order a stable sort would give them")
    class Ties {

        /**
         * The case a heap gets wrong on its own: three rows the comparator cannot
         * separate, of which only one is kept. Which one is a difference in the
         * <em>answer</em>, not in the order.
         */
        @Test
        @DisplayName("the earliest of equal rows wins the last place")
        void earliestWinsTheLastPlace() {
            List<Row> input = List.of(row(1, 10), row(2, 10), row(3, 10));
            assertThat(ids(fill(input, 1, BY_RANK).ordered())).containsExactly(1);
            assertThat(ids(fill(input, 2, BY_RANK).ordered())).containsExactly(1, 2);
        }

        @Test
        @DisplayName("a later row never displaces an equal one already held")
        void aLaterEqualRowDoesNotDisplace() {
            // 4 arrives after 1 and 2 with the same rank; the bound is full and it loses.
            List<Row> input = List.of(row(1, 10), row(2, 10), row(3, 99), row(4, 10));
            assertThat(ids(fill(input, 2, BY_RANK).ordered())).containsExactly(1, 2);
        }

        @Test
        @DisplayName("it agrees with a full sort on an input that is all ties")
        void agreesWithAFullSortThroughout() {
            List<Row> input = new ArrayList<>();
            for (int id = 1; id <= 20; id++) {
                input.add(row(id, id % 3));            // seven or so rows at each rank
            }
            for (int bound = 1; bound <= 20; bound++) {
                assertThat(ids(fill(input, bound, BY_RANK).ordered()))
                        .as("bound %d", bound)
                        .isEqualTo(sortedThenLimited(input, bound));
            }
        }
    }

    @Nested
    @DisplayName("with nothing to rank by")
    class NoComparator {

        /**
         * {@code TopKNode} does not require a sort key, and the shape it leaves —
         * a full sort with no comparator — cannot run at all: {@code List.sort(null)}
         * asks two rows to compare themselves. Arrival order is the reading a stable
         * sort by nothing gives.
         */
        @Test
        @DisplayName("it keeps the rows that arrived first")
        void keepsTheFirstToArrive() {
            List<Row> input = List.of(row(1, 50), row(2, 10), row(3, 40));
            assertThat(ids(fill(input, 2, null).ordered())).containsExactly(1, 2);
        }
    }
}

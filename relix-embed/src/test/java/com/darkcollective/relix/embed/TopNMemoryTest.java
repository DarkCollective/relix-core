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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NumberValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A bounded request over a large relation holds the rows it asked for, not the relation.
 *
 * <p>{@code LIM-003} — the {@code λ∘τ → TOP} fusion — has always said in so many words
 * that the point of turning a limit over a sort into one operator is that "the executor
 * keeps a bounded heap", and until this test the executor indexed every row and sorted
 * each group whole. Nothing could see the difference: the rewrite fired, the rows were
 * right, and the plan a user read named the operator that was supposed to be cheap.
 *
 * <p>The instrument is the row cap rather than a clock, for the reason the benchmark
 * tier counts rows: a cap of twenty over ten thousand rows is an exact, reproducible
 * statement about what the operator held, where a timing would flap. What it does
 * <em>not</em> claim is that fewer rows were read — the last row of an unsorted input may
 * be the largest, so a top-N reads all of them either way, and the counter here says so.
 */
@DisplayName("Top-N over a large relation")
final class TopNMemoryTest {

    private static final Schema SCORES = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("score", ScalarType.NUMBER),
            new ColumnDefinition("bucket", ScalarType.NUMBER)));

    private static final int ROWS = 10_000;

    /** A held buffer far smaller than the relation, and larger than any answer below. */
    private static final int CAP = 20;

    private static Row scoreRow(int id, int score) {
        return ArrayRow.of(SCORES, NumberValue.of(String.valueOf(id)),
                NumberValue.of(String.valueOf(score)),
                NumberValue.of(String.valueOf(id % 4)));
    }

    /**
     * Ten thousand rows whose scores rise to the end, so the largest arrive last and no
     * bounded buffer can be right by having stopped early.
     */
    private static Stream<Row> scores(AtomicInteger pulled) {
        return IntStream.rangeClosed(1, ROWS)
                .mapToObj(i -> {
                    pulled.incrementAndGet();
                    return scoreRow(i, i);
                });
    }

    private static Relix session(AtomicInteger pulled) {
        Relix relix = Relix.builder().maxMaterializedRows(CAP).build();
        relix.source("Scores", SCORES, () -> scores(pulled));
        return relix;
    }

    @Nested
    @DisplayName("a limit over a sort")
    final class LimitOverSort {

        @Test
        @DisplayName("returns the largest rows without holding more than it asked for")
        void holdsWhatItAskedFor() {
            AtomicInteger pulled = new AtomicInteger();
            try (Relix relix = session(pulled)) {
                assertThat(relix.relation("λ 3 (τ score DESC (Scores))")).rows()
                        .hasRowCount(3)
                        .hasRowAt(0, "10000", "10000", "0")
                        .hasRowAt(1, "9999", "9999", "3")
                        .hasRowAt(2, "9998", "9998", "2");
            }
            assertThat(pulled).hasValue(ROWS);   // every row read; three held
        }

        @Test
        @DisplayName("an offset is part of what is held, so it is taken before the count")
        void offsetIsHeldToo() {
            AtomicInteger pulled = new AtomicInteger();
            try (Relix relix = session(pulled)) {
                assertThat(relix.relation("λ 2, 2 (τ score DESC (Scores))")).rows()
                        .hasRowCount(2)
                        .hasRowAt(0, "9998", "9998", "2")
                        .hasRowAt(1, "9997", "9997", "1");
            }
        }

        @Test
        @DisplayName("the plain sort underneath still holds the relation, and says so")
        void theSortAloneIsStillBlocking() {
            AtomicInteger pulled = new AtomicInteger();
            try (Relix relix = session(pulled)) {
                assertThatThrownBy(() -> relix.relation("τ score DESC (Scores)").toList())
                        .hasMessageContaining("Sort")
                        .hasMessageContaining("buffered more than " + CAP);
            }
        }
    }

    @Nested
    @DisplayName("a TOP per group")
    final class TopPerGroup {

        @Test
        @DisplayName("holds its count per group, not the group")
        void holdsItsCountPerGroup() {
            AtomicInteger pulled = new AtomicInteger();
            try (Relix relix = session(pulled)) {
                // Four buckets of two and a half thousand rows each, one row kept from each.
                assertThat(relix.relation("TOP 1 score DESC PER bucket (Scores)")).rows()
                        .hasRowCount(4);
            }
            assertThat(pulled).hasValue(ROWS);
        }
    }
}

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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Everything a terminal can throw is this API's own exception.
 *
 * <p>The claim is one a caller writes code against: {@code catch (RelixException e)} around
 * a query is complete. It was not, and the way it failed is the reason this is a test
 * rather than a convention — a source that gave way mid-query surfaced as whatever the
 * connector threw, from a package the facade does not re-export, so the one type a caller
 * had been told about caught the typo and missed the outage.
 *
 * <p>Two windows, and only the first is guarded by wrapping the call: starting a query, and
 * <em>iterating</em> a lazy one. The second is the one that matters here, because by the
 * time it fails the exception is arriving inside the caller's own loop.
 */
final class FacadeFailureTypeTest {

    private static final Schema ROWS = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));

    /** Two rows, then the backend gives way — a dropped connection, in the small. */
    private static Stream<Row> failsAfterTwo() {
        return Stream.concat(
                IntStream.rangeClosed(1, 2).mapToObj(i -> (Row) ArrayRow.of(ROWS, NumberValue.of(String.valueOf(i)))),
                Stream.generate(() -> { throw new IllegalStateException("backend gone"); }));
    }

    private static Relix flaky() {
        Relix relix = Relix.open();
        relix.source("Flaky", ROWS, FacadeFailureTypeTest::failsAfterTwo);
        return relix;
    }

    private static Relix naturals() {
        Relix relix = Relix.open();
        relix.define("source Naturals from generator { name: \"Naturals\" };");
        return relix;
    }

    @Nested
    @DisplayName("a source that gives way")
    final class SourceFailure {

        @Test
        @DisplayName("fails a collecting terminal as a QueryExecutionException")
        void collecting() {
            try (Relix relix = flaky()) {
                assertThatThrownBy(() -> relix.relation("Flaky").toList())
                        .isInstanceOf(QueryExecutionException.class)
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("backend gone");
                assertThatThrownBy(() -> relix.relation("Flaky").run())
                        .isInstanceOf(QueryExecutionException.class);
                assertThatThrownBy(() -> relix.relation("Flaky").count())
                        .isInstanceOf(QueryExecutionException.class);
            }
        }

        @Test
        @DisplayName("fails a lazy stream mid-iteration, where the caller's loop is")
        void whileIterating() {
            try (Relix relix = flaky()) {
                List<String> seen = new java.util.ArrayList<>();
                assertThatThrownBy(() -> {
                    try (Stream<Tuple> rows = relix.relation("Flaky").stream()) {
                        rows.forEach(row -> seen.add(row.decimal("id").toString()));
                    }
                }).isInstanceOf(QueryExecutionException.class)
                  .hasMessageContaining("backend gone");
                // The rows already handed over are the point of the distinction: this is
                // the window a caller cannot guard by wrapping the call that opened it.
                assertThat(seen).containsExactly("1", "2");
            }
        }

        @Test
        @DisplayName("keeps the original failure as the cause, so nothing is buried")
        void keepsCause() {
            try (Relix relix = flaky()) {
                assertThatThrownBy(() -> relix.relation("Flaky").toList())
                        .hasCauseInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Nested
    @DisplayName("a relation that never ends")
    final class Unbounded {

        @Test
        @DisplayName("is refused by a collecting terminal as an UnboundedRelationException")
        void collecting() {
            try (Relix relix = naturals()) {
                assertThatThrownBy(() -> relix.relation("Naturals").toList())
                        .isInstanceOf(UnboundedRelationException.class)
                        .isInstanceOf(RelixException.class);
                assertThatThrownBy(() -> relix.relation("Naturals").run())
                        .isInstanceOf(UnboundedRelationException.class);
            }
        }

        @Test
        @DisplayName("is refused as the same type when the planner is the one that notices")
        void blockingOperator() {
            try (Relix relix = naturals()) {
                // A different route to one fact: τ must buffer, so the planner refuses
                // before a row is read. A caller who has to tell the two apart is being
                // asked about the engine's internals rather than about their query.
                assertThatThrownBy(() -> relix.relation("Naturals")
                        .sort(com.darkcollective.relix.ast.AstBuilders.asc("n")).stream())
                        .isInstanceOf(UnboundedRelationException.class);
                assertThatThrownBy(() -> relix.relation("Naturals").count())
                        .isInstanceOf(UnboundedRelationException.class);
            }
        }

        @Test
        @DisplayName("streams, because nothing about it is a failure")
        void streams() {
            try (Relix relix = naturals()) {
                try (Stream<Tuple> rows = relix.relation("Naturals").stream()) {
                    assertThat(rows.limit(3).toList()).hasSize(3);
                }
            }
        }
    }

    @Nested
    @DisplayName("a query that was never runnable")
    final class NotRunnable {

        @Test
        @DisplayName("stays a plain RelixException, since nothing ran")
        void refusals() {
            try (Relix relix = flaky()) {
                assertThatThrownBy(() -> relix.relation("σσσ"))
                        .isInstanceOf(RelixException.class)
                        .isNotInstanceOf(QueryExecutionException.class);
                assertThatThrownBy(() -> relix.relation("Nope"))
                        .isInstanceOf(RelixException.class)
                        .isNotInstanceOf(QueryExecutionException.class);
            }
            Relix closed = Relix.open();
            closed.close();
            assertThatThrownBy(() -> closed.relation("Flaky").toList())
                    .isInstanceOf(RelixException.class)
                    .isNotInstanceOf(QueryExecutionException.class);
        }
    }
}

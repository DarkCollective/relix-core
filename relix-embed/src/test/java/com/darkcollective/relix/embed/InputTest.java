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

import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;

/**
 * {@link Relix#input}: text arriving on a stream, named as a relation.
 */
@DisplayName("An input names text arriving on a stream as a relation")
final class InputTest {

    private static InputStream text(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static final String ORDERS_CSV = """
            id,customer,amount,paid,placed
            1,Ada,100,true,2026-01-02
            2,Grace,250,false,2026-01-03
            3,Ada,75,true,2026-02-01
            """;

    @Nested
    @DisplayName("the heading")
    final class Heading {

        @Test
        @DisplayName("is inferred from the header and the first records")
        void inferred() {
            try (Relix relix = Relix.open()) {
                relix.input("orders", Input.of(InputFormat.CSV, text(ORDERS_CSV)));
                assertThat(relix.relation("orders")).schema()
                        .hasColumnNames("id", "customer", "amount", "paid", "placed")
                        .hasColumn("id", ScalarType.NUMBER)
                        .hasColumn("customer", ScalarType.STRING)
                        .hasColumn("paid", ScalarType.BOOLEAN)
                        .hasColumn("placed", ScalarType.DATE);
            }
        }

        @Test
        @DisplayName("is the declared one when one is given")
        void declared() {
            Schema heading = new Schema(List.of(
                    new ColumnDefinition("customer", ScalarType.STRING),
                    new ColumnDefinition("amount", ScalarType.NUMBER)));
            try (Relix relix = Relix.open()) {
                relix.input("orders", Input.of(InputFormat.CSV, text(ORDERS_CSV)).schema(heading));
                assertThat(relix.relation("σ amount > 90 (orders)")).rows()
                        .hasColumns("customer", "amount").hasRowCount(2);
            }
        }

        @Test
        @DisplayName("from JSON is every key the sample uses, typed by its values")
        void json() {
            try (Relix relix = Relix.open()) {
                relix.input("events", Input.of(InputFormat.NDJSON, text("""
                        {"id": 1, "kind": "click", "at": "2026-01-02T10:00:00Z"}
                        {"id": 2, "kind": "view", "extra": {"x": 1}}
                        """)));
                assertThat(relix.relation("events")).schema()
                        .hasColumnNames("id", "kind", "at", "extra")
                        .hasColumn("id", ScalarType.NUMBER)
                        .hasColumn("kind", ScalarType.STRING)
                        .hasColumn("at", ScalarType.TIMESTAMP)
                        .hasColumn("extra", ScalarType.ANY);
                assertThat(relix.relation("π id (σ at IS NULL (events))")).rows().hasRow("2");
            }
        }

        @Test
        @DisplayName("a later record that does not fit it is an error naming the record")
        void laterMisfit() {
            try (Relix relix = Relix.open()) {
                relix.input("n", Input.of(InputFormat.CSV, text("x\n1\n2\nthree\n")).sample(2));
                assertThatThrownBy(() -> relix.relation("n").toList())
                        .isInstanceOf(QueryExecutionException.class)
                        .hasMessageContaining("line 4")
                        .hasMessageContaining("three");
            }
        }

        @Test
        @DisplayName("an input with no header row is refused when it is declared")
        void noHeader() {
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.input("e", Input.of(InputFormat.CSV, text(""))))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("header row");
            }
        }
    }

    @Nested
    @DisplayName("each format")
    final class Formats {

        @Test
        @DisplayName("TSV unescapes its fields")
        void tsv() {
            try (Relix relix = Relix.open()) {
                relix.input("t", Input.of(InputFormat.TSV, text("name\tnote\nAda\ta\\tb\n")));
                assertThat(relix.relation("π note (t)")).tuples().singleElement()
                        .satisfies(row -> assertThat(row.string("note")).isEqualTo("a\tb"));
            }
        }

        @Test
        @DisplayName("a JSON array is read as one document")
        void jsonArray() {
            try (Relix relix = Relix.open()) {
                relix.input("p", Input.of(InputFormat.JSON,
                        text("[{\"name\": \"Ada\", \"age\": 36}, {\"name\": \"Grace\", \"age\": 85}]")));
                assertThat(relix.relation("σ age > 50 (p)")).rows().hasRowCount(1).hasRow("Grace", "85");
            }
        }

        @Test
        @DisplayName("a JSON value of the wrong type for its column is refused")
        void jsonMisfit() {
            try (Relix relix = Relix.open()) {
                relix.input("p", Input.of(InputFormat.NDJSON, text("{\"n\": 1}\n{\"n\": \"two\"}\n")).sample(1));
                assertThatThrownBy(() -> relix.relation("p").toList())
                        .isInstanceOf(QueryExecutionException.class)
                        .hasMessageContaining("column 'n' is NUMBER");
            }
        }

        @Test
        @DisplayName("malformed JSON names its line")
        void malformed() {
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.input("p", Input.of(InputFormat.NDJSON, text("{\"n\": 1}\n{oops\n"))))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("line 2");
            }
        }
    }

    @Nested
    @DisplayName("reading it again")
    final class Rereading {

        @Test
        @DisplayName("a re-readable input is opened afresh for each scan")
        void reopened() {
            AtomicInteger opened = new AtomicInteger();
            try (Relix relix = Relix.open()) {
                relix.input("orders", Input.of(InputFormat.CSV, () -> {
                    opened.incrementAndGet();
                    return text(ORDERS_CSV);
                }));
                assertThat(relix.relation("orders")).hasRowCount(3);
                assertThat(relix.relation("orders")).hasRowCount(3);
                assertThat(opened.get()).as("one for the heading, one per scan").isEqualTo(3);
            }
        }

        @Test
        @DisplayName("a single-pass input replays what it read, to a second query and to a self-join")
        void replayed() {
            try (Relix relix = Relix.open()) {
                relix.input("orders", Input.of(InputFormat.CSV, text(ORDERS_CSV)));
                assertThat(relix.relation("orders")).hasRowCount(3);
                assertThat(relix.relation("orders")).hasRowCount(3);
                assertThat(relix.relation("orders ⋈ orders")).hasRowCount(3);
            }
        }

        @Test
        @DisplayName("an input joins a table the session already holds")
        void joinsATable() {
            try (Relix relix = Relix.open()) {
                relix.table("Customers", List.of("customer", "city"), List.of(
                        Map.of("customer", "Ada", "city", "London"),
                        Map.of("customer", "Grace", "city", "Arlington")));
                relix.input("orders", Input.of(InputFormat.NDJSON, text("""
                        {"customer": "Ada", "amount": 100}
                        {"customer": "Grace", "amount": 250}
                        """)));
                assertThat(relix.relation("π city, amount (σ amount > 150 (orders ⋈ Customers))"))
                        .rows().hasRowCount(1).hasRow("Arlington", "250");
            }
        }
    }

    @Nested
    @DisplayName("an unbounded input")
    final class Unbounded {

        /** One {@code {"n": i}} per line, for ever. */
        private static InputStream endless() {
            return new InputStream() {
                private long n;
                private byte[] line = new byte[0];
                private int at;

                @Override
                public int read() {
                    if (at == line.length) {
                        line = ("{\"n\": " + (n++) + "}\n").getBytes(StandardCharsets.UTF_8);
                        at = 0;
                    }
                    return line[at++];
                }
            };
        }

        @Test
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("streams rows as they arrive, and refuses a terminal that would collect it")
        void streams() {
            try (Relix relix = Relix.open()) {
                relix.input("feed", Input.of(InputFormat.NDJSON, endless()).sample(5).unbounded());
                try (Stream<Tuple> rows = relix.relation("σ n > 100 (feed)").stream()) {
                    assertThat(rows.limit(3).map(r -> r.longValue("n")).toList())
                            .containsExactly(101L, 102L, 103L);
                }
                assertThatThrownBy(() -> relix.relation("feed").toList())
                        .isInstanceOf(UnboundedRelationException.class);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"τ n (feed)", "γ COUNT(*) -> c (feed)"})
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("refuses a blocking operator over it when the stream is opened")
        void blockingRefused(String query) {
            try (Relix relix = Relix.open()) {
                relix.input("feed", Input.of(InputFormat.NDJSON, endless()).sample(1).unbounded());
                assertThatThrownBy(() -> relix.relation(query).stream())
                        .isInstanceOf(UnboundedRelationException.class)
                        .hasMessageContaining("unbounded");
            }
        }

        /** δ emits a row the first time it sees it, so it streams like σ and π. */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"π n (feed)", "δ (feed)"})
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("streams a non-blocking operator over it as rows arrive")
        void streamingOperatorStreams(String query) {
            try (Relix relix = Relix.open()) {
                relix.input("feed", Input.of(InputFormat.NDJSON, endless()).sample(1).unbounded());
                try (Stream<Tuple> rows = relix.relation(query).stream()) {
                    assertThat(rows.limit(2).map(r -> r.longValue("n")).toList())
                            .containsExactly(0L, 1L);
                }
            }
        }

        @Test
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("read from one stream, can be read by one scan only")
        void readOnce() {
            try (Relix relix = Relix.open()) {
                relix.input("feed", Input.of(InputFormat.NDJSON, endless()).unbounded().sample(1));
                try (Stream<Tuple> rows = relix.relation("feed").stream()) {
                    assertThat(rows.findFirst()).isPresent();
                }
                assertThatThrownBy(() -> relix.relation("feed").stream().findFirst())
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("one scan only");
            }
        }

        @Test
        @DisplayName("cannot be a JSON array")
        void notAnArray() {
            assertThatThrownBy(() -> Input.of(InputFormat.JSON, text("[]")).unbounded())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NDJSON");
        }
    }

    @Nested
    @DisplayName("the stream")
    final class Lifecycle {

        @Test
        @DisplayName("a single-pass input's stream closes with the session, read or not")
        void closedWithSession() {
            AtomicBoolean closed = new AtomicBoolean();
            InputStream tracked = new ByteArrayInputStream(ORDERS_CSV.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };
            try (Relix relix = Relix.open()) {
                relix.input("orders", Input.of(InputFormat.CSV, tracked));
                assertThat(closed.get()).isFalse();
            }
            assertThat(closed.get()).isTrue();
        }

        @Test
        @DisplayName("a sample must be at least one record, and a name not already taken")
        void validation() {
            assertThatThrownBy(() -> Input.of(InputFormat.CSV, text("x")).sample(0))
                    .isInstanceOf(IllegalArgumentException.class);
            try (Relix relix = Relix.open()) {
                relix.input("orders", Input.of(InputFormat.CSV, text(ORDERS_CSV)));
                assertThatThrownBy(() -> relix.input("orders", Input.of(InputFormat.CSV, text(ORDERS_CSV))))
                        .isInstanceOf(RelixException.class);
            }
        }

        @Test
        @DisplayName("an input reports what it was given")
        void accessors() {
            Input input = Input.of(InputFormat.TSV, () -> text("x")).sample(7).unbounded();
            assertThat(input.format()).isEqualTo(InputFormat.TSV);
            assertThat(input.sample()).isEqualTo(7);
            assertThat(input.isUnbounded()).isTrue();
            assertThat(input.isReReadable()).isTrue();
            assertThat(input.schema()).isEmpty();
        }
    }
}

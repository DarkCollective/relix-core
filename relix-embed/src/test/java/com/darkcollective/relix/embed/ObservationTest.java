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

import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.semantic.CatalogSnapshot;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NumberValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A run measures what it read, and the next plan uses it")
final class ObservationTest {

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    /**
     * A CSV source, because an inline table's statistics are already computed exactly
     * from its rows — there would be nothing for a measurement to improve on.
     */
    private static Relix ordersFrom(Path directory) throws IOException {
        Path csv = directory.resolve("orders.csv");
        Files.writeString(csv, """
                order_id,status,amount
                1,OPEN,100
                2,SHIPPED,250
                3,OPEN,75
                """);
        Relix relix = Relix.builder().baseDirectory(directory).build();
        relix.define("""
                source Orders from csv("orders.csv") {
                    header: true,
                    schema: { order_id: NUMBER, status: STRING, amount: NUMBER }
                };
                """);
        return relix;
    }

    @Nested
    @DisplayName("leaf scans")
    final class Leaves {

        @Test
        @DisplayName("a drained scan reports the relation's real size to the cost model")
        void measuresALeaf(@TempDir Path directory) throws IOException {
            try (Relix relix = ordersFrom(directory)) {
                Relation orders = relix.relation("Orders");

                var before = orders.plan();
                assertThat(before.estimates().rows(before.plan()))
                        .as("nothing has run, so the leaf has no measured size")
                        .isEmpty();

                orders.toList();

                var after = relix.relation("Orders").plan();
                assertThat(after.estimates().rows(after.plan())).hasValue(3);
            }
        }

        /**
         * The measurement is a fact about the relation, not about the query that read it,
         * so it sharpens an estimate for a query that has never run.
         */
        @Test
        @DisplayName("it sharpens a different query over the same relation")
        void sharpensADifferentQuery(@TempDir Path directory) throws IOException {
            try (Relix relix = ordersFrom(directory)) {
                relix.relation("Orders").toList();

                Relation selected = relix.relation("σ status = 'OPEN' (Orders)");
                var planned = selected.plan();
                assertThat(planned.estimates().rows(planned.plan()))
                        .as("a selectivity applied to a measured 3 rows, not to a guess")
                        .isPresent();
            }
        }

        /**
         * A scan the consumer stopped reading delivered a count about the consumer. Filing
         * it as the relation's size would make the next plan confident and wrong.
         */
        @Test
        @DisplayName("a partial read measures nothing")
        void partialReadMeasuresNothing(@TempDir Path directory) throws IOException {
            try (Relix relix = ordersFrom(directory)) {
                try (var rows = relix.relation("Orders").stream()) {
                    assertThat(rows.limit(1).toList()).hasSize(1);
                }

                var planned = relix.relation("Orders").plan();
                assertThat(planned.estimates().rows(planned.plan())).isEmpty();
            }
        }

        @Test
        @DisplayName("the scan is reported on the run's own feed, naming the relation")
        void reportsOnTheFeed(@TempDir Path directory) throws IOException {
            try (Relix relix = ordersFrom(directory)) {
                Rows result = relix.relation("Orders").run();

                assertThat(result.events())
                        .filteredOn(e -> e.code().equals("SCAN"))
                        .singleElement()
                        .satisfies(e -> {
                            assertThat(e.target()).contains("orders");
                            assertThat(e.metrics().rows()).hasValue(3);
                        });
            }
        }
    }

    @Nested
    @DisplayName("the expression as a whole")
    final class Expressions {

        @Test
        @DisplayName("a drained run records its own cardinality against its expression")
        void measuresTheRoot(@TempDir Path directory) throws IOException {
            try (Relix relix = ordersFrom(directory)) {
                relix.relation("σ status = 'OPEN' (Orders)").toList();

                Relation same = relix.relation("σ status = 'OPEN' (Orders)");
                var planned = same.plan();
                assertThat(planned.estimates().rows(planned.plan()))
                        .as("measured exactly, rather than 3 rows times a selectivity guess")
                        .hasValue(2);
            }
        }

        /**
         * The gate shared sub-expression elimination already needs, for the same reason:
         * a count that drifts by construction is a number that was true once.
         */
        @Test
        @DisplayName("a volatile expression is not memoised")
        void volatileIsNotMemoised(@TempDir Path directory) throws IOException {
            try (Relix relix = ordersFrom(directory)) {
                relix.relation("SAMPLE 0.5 (Orders)").toList();

                assertThat(relix.observedExpressions().isEmpty())
                        .as("an unseeded sample draws differently every time, so its count "
                                + "was true once rather than being a property of the query")
                        .isTrue();

                // The leaf underneath it is not volatile, and was read to the end.
                var planned = relix.relation("Orders").plan();
                assertThat(planned.estimates().rows(planned.plan())).hasValue(3);
            }
        }

        @Test
        @DisplayName("a deterministic expression is")
        void deterministicIsMemoised(@TempDir Path directory) throws IOException {
            try (Relix relix = ordersFrom(directory)) {
                relix.relation("σ status = 'OPEN' (Orders)").toList();

                assertThat(relix.observedExpressions().isEmpty()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("carrying a measurement out of the session")
    final class Snapshots {

        /** A connection-backed relation the test can actually serve rows for. */
        private static final class Ledger implements RelixConnector {
            @Override
            public java.util.Set<String> handles() {
                return java.util.Set.of("ledger");
            }

            @Override
            public java.util.stream.Stream<com.darkcollective.relix.processor.Row> open(
                    ConnectorConfig config, String table, Schema schema) {
                return java.util.stream.Stream.of(
                        ArrayRow.of(schema, NumberValue.of("1")),
                        ArrayRow.of(schema, NumberValue.of("2")),
                        ArrayRow.of(schema, NumberValue.of("3")),
                        ArrayRow.of(schema, NumberValue.of("4")));
            }
        }

        private static final String BOOKS = """
                connection books from ledger { origin: "in-process" };
                source Customers from books { table: "customers",
                    schema: { id: NUMBER } };
                """;

        /**
         * The loop the catalog snapshot was built to close: a run produces the measured
         * number, and the snapshot serves it to the next offline session.
         */
        @Test
        @DisplayName("a measured row count becomes an OBSERVED snapshot entry")
        void observedEntersTheSnapshot() {
            CatalogSnapshot snapshot;
            try (Relix relix = Relix.builder().connector(new Ledger()).build()) {
                relix.define(BOOKS);
                assertThat(relix.captureCatalog().entries())
                        .as("nothing has run and nothing is introspectable")
                        .isEmpty();

                relix.relation("Customers").toList();
                snapshot = relix.captureCatalog();
            }

            assertThat(snapshot.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.origin()).isEqualTo(CatalogSnapshot.Origin.OBSERVED);
                assertThat(entry.connection()).isEqualTo("books");
                assertThat(entry.table()).isEqualTo("customers");
                assertThat(entry.statistics().orElseThrow().rowCount()).hasValue(4);
            });
        }

        @Test
        @DisplayName("and survives the round trip, so the next session starts informed")
        void survivesTheRoundTrip() {
            String json;
            try (Relix relix = Relix.builder().connector(new Ledger()).build()) {
                relix.define(BOOKS);
                relix.relation("Customers").toList();
                json = relix.captureCatalog().toJson();
            }

            CatalogSnapshot replayed = CatalogSnapshot.parse(json);
            assertThat(replayed.entries()).singleElement()
                    .satisfies(entry ->
                            assertThat(entry.origin()).isEqualTo(CatalogSnapshot.Origin.OBSERVED));
        }
    }
}

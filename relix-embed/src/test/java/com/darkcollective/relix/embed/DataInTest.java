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

import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.FunctionLibrary;
import com.darkcollective.relix.function.FunctionSignature;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.plan.BoundednessException;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;

@DisplayName("Relix — Java data and Java functions going in")
final class DataInTest {

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    @Nested
    @DisplayName("table — rows the program already holds")
    final class Tables {

        @Test
        @DisplayName("becomes a relation the session can query")
        void becomesARelation() {
            try (Relix relix = Relix.open()) {
                relix.table("Regions", List.of(
                        row("region", "EMEA", "country", "United Kingdom"),
                        row("region", "APAC", "country", "Australia")));

                Relation regions = relix.relation("Regions");
                var schema = regions.model().nodeSchemas().get(regions.node()).orElseThrow();
                assertThat(schema.columns()).extracting("name")
                        .containsExactly("region", "country");
            }
        }

        @Test
        @DisplayName("a column of numbers types as NUMBER, everything else as STRING")
        void typesAreInferred() {
            try (Relix relix = Relix.open()) {
                relix.table("Prices", List.of(
                        row("sku", "A-1", "price", 9.99),
                        row("sku", "B-2", "price", new BigDecimal("14.50"))));

                Relation prices = relix.relation("Prices");
                var schema = prices.model().nodeSchemas().get(prices.node()).orElseThrow();
                assertThat(schema.column("price").orElseThrow().type()).isEqualTo(ScalarType.NUMBER);
                assertThat(schema.column("sku").orElseThrow().type()).isEqualTo(ScalarType.STRING);
            }
        }

        @Test
        @DisplayName("values are converted through Expr.lit, so typed Java values are accepted")
        void acceptsTypedValues() {
            try (Relix relix = Relix.open()) {
                // A timestamp survives as text, not as an exception — the documented cost
                // of an inline table, which infers only NUMBER and STRING.
                relix.table("Events", List.of(
                        row("id", 1L, "at", Instant.parse("2026-06-15T13:40:00Z"))));
                assertThat(relix.definitions()).contains("2026-06-15T13:40:00Z");
            }
        }

        @Test
        @DisplayName("it renders back as an inline table, so the session still round-trips")
        void rendersBack() {
            try (Relix relix = Relix.open()) {
                relix.table("Regions", List.of(row("region", "EMEA", "country", "UK")));

                String text = relix.definitions();
                try (Relix reloaded = Relix.open()) {
                    reloaded.define(text);
                    assertThat(reloaded.definitions()).isEqualTo(text);
                }
            }
        }

        @Test
        @DisplayName("a row disagreeing about its columns is refused, naming both headings")
        void refusesRaggedRows() {
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.table("Bad", List.of(
                        row("a", "1", "b", "2"),
                        row("a", "3"))))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("one heading");
            }
        }

        @Test
        @DisplayName("no rows is refused, because a relation still needs a heading")
        void refusesEmpty() {
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.table("Empty", List.of()))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("heading");
            }
        }

        @Test
        @DisplayName("a stated heading is the heading, whatever order the maps are in")
        void statedHeadingWins() {
            try (Relix relix = Relix.open()) {
                relix.table("Orders", List.of("order_id", "customer", "status", "amount"),
                        List.of(Map.of("amount", 100, "order_id", 1,
                                       "status", "OPEN", "customer", "Ada")));

                assertThat(relix.relation("Orders")).schema()
                        .hasColumnNames("order_id", "customer", "status", "amount");
            }
        }

        @Test
        @DisplayName("a column a row does not mention is NULL for that row")
        void anAbsentKeyIsNull() {
            try (Relix relix = Relix.open()) {
                relix.table("Readings", List.of("sensor", "value"), List.of(
                        Map.of("sensor", "a", "value", 10),
                        Map.of("sensor", "b")));

                assertThat(relix.relation("Readings")).tuples()
                        .extracting(t -> t.isNull("value"))
                        .containsExactly(false, true);
            }
        }

        @Test
        @DisplayName("a key the heading does not name is refused, not silently dropped")
        void refusesAnUnknownKey() {
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.table("Orders", List.of("id"),
                        List.of(Map.of("id", 1, "extra", 2))))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("extra");
            }
        }

        @Test
        @DisplayName("a heading naming a column twice is refused, case-insensitively")
        void refusesADuplicateColumn() {
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.table("Orders", List.of("id", "ID"),
                        List.of(Map.of("id", 1))))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("twice");
            }
        }

        @Test
        @DisplayName("a heading with no columns is refused")
        void refusesAnEmptyHeading() {
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.table("Orders", List.of(),
                        List.of(Map.of("id", 1))))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("at least one column");
            }
        }

        @Test
        @DisplayName("with the heading stated, no rows is expressible")
        void allowsNoRowsWhenTheHeadingIsStated() {
            try (Relix relix = Relix.open()) {
                // Not named "Empty": that is the EMPTY/DUM truth literal, so the
                // reference would parse as the keyword rather than as this relation.
                relix.table("Blank", List.of("id", "label"), List.of());

                Relation blank = relix.relation("Blank");
                assertThat(blank).schema().hasColumnNames("id", "label");
                assertThat(blank).isEmpty();
            }
        }

        @Test
        @DisplayName("a first row whose keys have no order is refused, naming the fix")
        void refusesAnUnorderedFirstRow() {
            try (Relix relix = Relix.open()) {
                // Map.of randomises its iteration order per JVM, so a heading taken from
                // one differs between runs of the same program.
                assertThatThrownBy(() -> relix.table("Orders",
                        List.of(Map.of("order_id", 1, "customer", "Ada"))))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("no defined order")
                        .hasMessageContaining("table(name, columns, rows)");
            }
        }

        @Test
        @DisplayName("a one-column row needs no order, because one key is in only one")
        void acceptsASingleEntryUnorderedRow() {
            try (Relix relix = Relix.open()) {
                relix.table("Carriers", List.of(Map.of("carrier", "Rail"), Map.of("carrier", "Air")));

                assertThat(relix.relation("Carriers")).count().isEqualTo(2);
            }
        }

        @Test
        @DisplayName("a HashMap is refused for the same reason Map.of is")
        void refusesAHashMap() {
            Map<String, Object> unordered = new java.util.HashMap<>();
            unordered.put("a", 1);
            unordered.put("b", 2);
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.table("Bad", List.of(unordered)))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("no defined order");
            }
        }

        @Test
        @DisplayName("it joins against a source declared in the ordinary way")
        void joinsAgainstADeclaredSource() {
            try (Relix relix = Relix.open()) {
                relix.define("""
                        source Orders from csv("./orders.csv") {
                            header: true, schema: { id: NUMBER, region: STRING }
                        };
                        """);
                relix.table("Regions", List.of(row("region", "EMEA", "country", "UK")));

                // The whole point of the call: in-memory data meeting an external source.
                assertThat(relix.relation("Orders ⋈ Regions").node()).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("functions — a library of the caller's own")
    final class Functions {

        /** A one-function library: {@code Triple(n)}. */
        private static final class TripleLibrary implements FunctionLibrary {
            private final int priority;

            TripleLibrary(int priority) {
                this.priority = priority;
            }

            @Override public String name() {
                return "test-triple";
            }

            @Override public int priority() {
                return priority;
            }

            @Override public List<ScalarFunction> scalarFunctions() {
                return List.of(new Triple());
            }
        }

        private static final class Triple implements StrictScalarFunction {
            @Override public FunctionSignature signature() {
                return FunctionSignature.of("Triple", ScalarType.NUMBER, "math",
                        Set.of(FunctionProperty.PURE, FunctionProperty.DETERMINISTIC),
                        param("n", ScalarType.NUMBER));
            }

            @Override public Value invoke(FunctionContext context, List<Value> arguments) {
                return new NumberValue(((NumberValue) arguments.getFirst()).value()
                        .multiply(BigDecimal.valueOf(3)));
            }
        }

        private static final String ORDERS = """
                source Orders from csv("./orders.csv") {
                    header: true, schema: { id: NUMBER, amount: NUMBER }
                };
                """;

        @Test
        @DisplayName("an installed function resolves where an unknown one does not")
        void installedFunctionResolves() {
            try (Relix plain = Relix.open()) {
                plain.define(ORDERS);
                assertThatThrownBy(() -> plain.relation("π Triple(amount) (Orders)"))
                        .isInstanceOf(RelixException.class);
            }

            try (Relix withLibrary = Relix.builder().functions(new TripleLibrary(1)).build()) {
                withLibrary.define(ORDERS);
                assertThat(withLibrary.relation("π Triple(amount) (Orders)").node()).isNotNull();
            }
        }

        @Test
        @DisplayName("it can be installed after the session opens")
        void installedAfterOpening() {
            try (Relix relix = Relix.open()) {
                relix.define(ORDERS);
                relix.functions(new TripleLibrary(1));
                assertThat(relix.relation("π Triple(amount) (Orders)").node()).isNotNull();
            }
        }

        @Test
        @DisplayName("the bundled library is still there — installing adds, it does not replace")
        void bundledLibrarySurvives() {
            try (Relix relix = Relix.builder().functions(new TripleLibrary(1)).build()) {
                relix.define(ORDERS);
                // Abs comes from the discovered default library; Triple from the installed
                // one. Two independently-populated catalogues would have lost one of them.
                assertThat(relix.relation("π Abs(amount), Triple(amount) (Orders)").node())
                        .isNotNull();
            }
        }
    }

    // -----------------------------------------------------------------------
    // source — rows the program produces on demand
    // -----------------------------------------------------------------------

    private static final Schema FEED = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("label", ScalarType.STRING)));

    private static Row feedRow(int id, String label) {
        return ArrayRow.of(FEED, NumberValue.of(String.valueOf(id)), new StringValue(label));
    }

    @Nested
    @DisplayName("source — a declared heading and a supplier called per scan")
    final class SuppliedSources {

        @Test
        @DisplayName("its rows come back through the row terminals")
        void servesRows() {
            try (Relix relix = Relix.open()) {
                relix.source("Feed", FEED, () -> Stream.of(feedRow(1, "one"), feedRow(2, "two")));

                assertThat(relix.relation("Feed")).rows()
                        .hasRowCount(2)
                        .hasRowAt(0, "1", "one")
                        .hasRowAt(1, "2", "two");
            }
        }

        @Test
        @DisplayName("the heading is declared, so a column keeps the type it was given")
        void headingIsDeclared() {
            Schema typed = new Schema(List.of(
                    new ColumnDefinition("at", ScalarType.TIMESTAMP),
                    new ColumnDefinition("id", ScalarType.NUMBER)));
            try (Relix relix = Relix.open()) {
                relix.source("Events", typed, Stream::empty);

                assertThat(relix.relation("Events")).schema()
                        .hasColumnNames("at", "id")
                        .hasColumn("at", ScalarType.TIMESTAMP)
                        .hasColumn("id", ScalarType.NUMBER);
            }
        }

        @Test
        @DisplayName("the supplier is called once per scan, not once per session")
        void calledPerScan() {
            AtomicInteger scans = new AtomicInteger();
            try (Relix relix = Relix.open()) {
                relix.source("Feed", FEED, () -> {
                    scans.incrementAndGet();
                    return Stream.of(feedRow(1, "one"));
                });

                relix.relation("Feed").toList();
                relix.relation("Feed").toList();
                assertThat(scans).hasValue(2);
            }
        }

        @Test
        @DisplayName("it composes with the rest of the algebra")
        void composes() {
            try (Relix relix = Relix.open()) {
                relix.source("Feed", FEED,
                        () -> Stream.of(feedRow(1, "one"), feedRow(2, "two"), feedRow(3, "three")));

                assertThat(relix.relation("σ id > 1 (Feed)")).count().isEqualTo(2);
            }
        }

        @Test
        @DisplayName("a name a built-in generator already holds is refused, not shadowed")
        void refusesAClash() {
            try (Relix relix = Relix.open()) {
                assertThatThrownBy(() -> relix.source("Range", FEED, Stream::empty))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("Range");
            }
        }
    }

    // -----------------------------------------------------------------------
    // materialize — a computed result, back in the session
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("materialize — a computed result, named and reusable")
    final class Materialized {

        @Test
        @DisplayName("the result becomes a relation the session can query")
        void becomesARelation() {
            try (Relix relix = Relix.open()) {
                relix.source("Feed", FEED,
                        () -> Stream.of(feedRow(1, "one"), feedRow(2, "two"), feedRow(3, "three")));

                relix.materialize("Big", relix.relation("σ id > 1 (Feed)"));

                assertThat(relix.relation("Big")).rows()
                        .hasRowCount(2)
                        .hasRowAt(0, "2", "two")
                        .hasRowAt(1, "3", "three");
            }
        }

        @Test
        @DisplayName("it carries the relation's own heading, not an inferred one")
        void keepsTheHeading() {
            Schema typed = new Schema(List.of(
                    new ColumnDefinition("at", ScalarType.TIMESTAMP),
                    new ColumnDefinition("id", ScalarType.NUMBER)));
            try (Relix relix = Relix.open()) {
                relix.source("Events", typed, () -> Stream.of(ArrayRow.of(typed,
                        new TimestampValue(Instant.parse("2026-06-15T13:40:00Z")),
                        NumberValue.of("1"))));

                relix.materialize("Snapshot", relix.relation("Events"));

                // The difference from draining to maps and calling table(...): that route
                // infers NUMBER or STRING, so a TIMESTAMP would come back a string.
                assertThat(relix.relation("Snapshot")).schema()
                        .hasColumnNames("at", "id")
                        .hasColumn("at", ScalarType.TIMESTAMP)
                        .hasColumn("id", ScalarType.NUMBER);
            }
        }

        @Test
        @DisplayName("a projection's heading is the projection's, not the input's")
        void keepsADerivedHeading() {
            try (Relix relix = Relix.open()) {
                relix.source("Feed", FEED, () -> Stream.of(feedRow(1, "one")));

                relix.materialize("Labels", relix.relation("π label (Feed)"));

                assertThat(relix.relation("Labels")).schema().hasColumnNames("label");
            }
        }

        @Test
        @DisplayName("the input is read once, at registration, however often the result is read")
        void drainsOnce() {
            AtomicInteger scans = new AtomicInteger();
            try (Relix relix = Relix.open()) {
                relix.source("Feed", FEED, () -> {
                    scans.incrementAndGet();
                    return Stream.of(feedRow(1, "one"), feedRow(2, "two"));
                });

                relix.materialize("Once", relix.relation("Feed"));
                assertThat(scans).hasValue(1);

                relix.relation("Once").toList();
                relix.relation("Once").toList();
                // The whole point: computing once and reusing the result.
                assertThat(scans).hasValue(1);
            }
        }

        @Test
        @DisplayName("it is a snapshot — the input moving on does not reach it")
        void isASnapshot() {
            AtomicInteger id = new AtomicInteger();
            try (Relix relix = Relix.open()) {
                relix.source("Feed", FEED,
                        () -> Stream.of(feedRow(id.incrementAndGet(), "row")));

                relix.materialize("Pinned", relix.relation("Feed"));
                relix.relation("Feed").toList();

                assertThat(relix.relation("Pinned")).rows()
                        .hasRowCount(1)
                        .hasRowAt(0, "1", "row");
            }
        }

        @Test
        @DisplayName("it composes with everything else the session declares")
        void composes() {
            try (Relix relix = Relix.open()) {
                relix.source("Feed", FEED,
                        () -> Stream.of(feedRow(1, "one"), feedRow(2, "two")));
                relix.materialize("Stage1", relix.relation("σ id = 1 (Feed)"));

                assertThat(relix.relation("Stage1 ⋈ Feed")).count().isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a name already taken is refused, as it is for a declared source")
        void refusesATakenName() {
            try (Relix relix = Relix.open()) {
                relix.source("Feed", FEED, Stream::empty);
                relix.materialize("Stage1", relix.relation("Feed"));

                assertThatThrownBy(() -> relix.materialize("Stage1", relix.relation("Feed")))
                        .isInstanceOf(RelixException.class)
                        .hasMessageContaining("Stage1");
            }
        }

        @Test
        @DisplayName("an unbounded relation is refused, as it is by the collecting terminals")
        void refusesUnbounded() {
            try (Relix relix = Relix.open()) {
                relix.define("source Naturals from generator { name: \"Naturals\" };");

                assertThatThrownBy(() -> relix.materialize("All", relix.relation("Naturals")))
                        .isInstanceOf(BoundednessException.class);
            }
        }

        @Test
        @DisplayName("a relation with no rows still registers, carrying its heading")
        void keepsAnEmptyResult() {
            try (Relix relix = Relix.open()) {
                relix.source("Feed", FEED, Stream::empty);

                relix.materialize("Nothing", relix.relation("Feed"));

                assertThat(relix.relation("Nothing")).isEmpty()
                        .schema().hasColumnNames("id", "label");
            }
        }
    }

    // -----------------------------------------------------------------------
    // connector — a backend of the caller's own
    // -----------------------------------------------------------------------

    /** A connector over rows held in the test, claiming a type token nothing ships. */
    private static final class FixtureConnector implements RelixConnector {

        private final List<Row> rows;
        private boolean closed;

        FixtureConnector(List<Row> rows) {
            this.rows = rows;
        }

        @Override
        public Set<String> handles() {
            return Set.of("fixture");
        }

        @Override
        public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
            return rows.stream();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final String FIXTURE_SESSION = """
            connection memstore from fixture { host: "in-process" };
            source Feed from memstore { table: "feed",
                schema: { id: NUMBER, label: STRING } };
            """;

    @Nested
    @DisplayName("connector — a backend of the caller's own, without a service declaration")
    final class Connectors {

        @Test
        @DisplayName("a source over its connection is served by it")
        void servesASource() {
            FixtureConnector connector = new FixtureConnector(
                    List.of(feedRow(1, "one"), feedRow(2, "two")));
            try (Relix relix = Relix.builder().connector(connector).build()) {
                relix.define(FIXTURE_SESSION);

                assertThat(relix.relation("Feed")).rows()
                        .hasRowCount(2)
                        .hasRowAt(0, "1", "one")
                        .hasRowAt(1, "2", "two");
            }
        }

        @Test
        @DisplayName("it can be installed after the session opens")
        void installedAfterOpening() {
            FixtureConnector connector = new FixtureConnector(List.of(feedRow(7, "seven")));
            try (Relix relix = Relix.open()) {
                relix.define(FIXTURE_SESSION);
                relix.connector(connector);

                assertThat(relix.relation("Feed")).count().isEqualTo(1);
            }
        }

        @Test
        @DisplayName("it survives a query — the session runs it, it does not own it")
        void notClosedByAQuery() {
            FixtureConnector connector = new FixtureConnector(List.of(feedRow(1, "one")));
            try (Relix relix = Relix.builder().connector(connector).build()) {
                relix.define(FIXTURE_SESSION);

                relix.relation("Feed").toList();
                assertThat(connector.closed).isFalse();
                // And so the second query still has a connector to read through.
                assertThat(relix.relation("Feed")).count().isEqualTo(1);
            }
        }

        @Test
        @DisplayName("without it, the unknown type is reported rather than guessed at")
        void unknownTypeIsReported() {
            try (Relix relix = Relix.open()) {
                relix.define(FIXTURE_SESSION);

                assertThatThrownBy(() -> relix.relation("Feed").toList())
                        .hasMessageContaining("fixture");
            }
        }
    }
}

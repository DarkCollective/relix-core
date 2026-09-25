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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.semantic.internal.BuiltinProvider;
import com.darkcollective.relix.semantic.internal.InMemoryScriptLoader;
import com.darkcollective.relix.semantic.internal.SemanticAnalyzer;
import com.darkcollective.relix.semantic.internal.SemanticResult;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.ScriptBuilders;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnStatistics;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CatalogSnapshot — metadata captured once and replayed offline")
final class CatalogSnapshotTest {

    private static final Instant EARLY = Instant.parse("2026-08-30T09:00:00Z");
    private static final Instant LATE = Instant.parse("2026-08-31T09:00:00Z");

    private static final ConnectionDeclaration WAREHOUSE =
            ScriptBuilders.connection("warehouse", "jdbc", Map.of("url", "jdbc:h2:mem:x"));

    private static final Schema ORDERS = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("placed", ScalarType.TIMESTAMP),
            new ColumnDefinition("tags", array(ScalarType.STRING)),
            new ColumnDefinition("addr", struct(new StructType.Field("city", ScalarType.STRING)))));

    private static final RelationStatistics ORDER_STATS = new RelationStatistics(
            OptionalLong.of(1200),
            Map.of("id", new ColumnStatistics(OptionalLong.of(1200), OptionalLong.of(0))),
            List.of(List.of("id")));

    private static CatalogSnapshot.Entry introspected() {
        return new CatalogSnapshot.Entry("warehouse", "orders",
                Optional.of(ORDERS), Optional.of(ORDER_STATS),
                CatalogSnapshot.Origin.INTROSPECTED, EARLY);
    }

    /** A live provider that answers for one table and knows nothing about any other. */
    private record OneTable(Schema schema, RelationStatistics statistics)
            implements CatalogProvider {

        @Override
        public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
            return table.equals("orders") ? Optional.of(schema) : Optional.empty();
        }

        @Override
        public Optional<RelationStatistics> tableStatistics(
                ConnectionDeclaration connection, String table) {
            return table.equals("orders") ? Optional.of(statistics) : Optional.empty();
        }
    }

    @Nested
    @DisplayName("replay — it is a CatalogProvider, so it goes where a live one goes")
    final class Replay {

        @Test
        @DisplayName("it answers for a table it recorded")
        void answersForARecordedTable() {
            CatalogSnapshot snapshot = CatalogSnapshot.of(List.of(introspected()), EARLY);

            assertThat(snapshot.tableSchema(WAREHOUSE, "orders")).contains(ORDERS);
            assertThat(snapshot.tableStatistics(WAREHOUSE, "orders")).contains(ORDER_STATS);
        }

        @Test
        @DisplayName("names are case-folded, as they are everywhere else")
        void namesAreCaseFolded() {
            CatalogSnapshot snapshot = CatalogSnapshot.of(List.of(introspected()), EARLY);

            assertThat(snapshot.tableSchema(WAREHOUSE, "ORDERS")).isPresent();
        }

        @Test
        @DisplayName("a table it never heard of is empty, not an error")
        void unknownTableIsEmpty() {
            CatalogSnapshot snapshot = CatalogSnapshot.of(List.of(introspected()), EARLY);

            assertThat(snapshot.tableSchema(WAREHOUSE, "invoices")).isEmpty();
            assertThat(CatalogSnapshot.empty().tableSchema(WAREHOUSE, "orders")).isEmpty();
        }

        @Test
        @DisplayName("it resolves a dotted reference through the analyser, with nothing reachable")
        void resolvesADottedReferenceOffline() {
            CatalogSnapshot snapshot = CatalogSnapshot.of(List.of(introspected()), EARLY);
            SemanticAnalyzer analyzer = new SemanticAnalyzer(
                    new InMemoryScriptLoader(Map.of()), BuiltinProvider.none(), snapshot);

            SemanticResult result = analyzer.analyze(com.darkcollective.relix.lang.ScriptParser.parse(
                    """
                    connection warehouse from database { url: "jdbc:h2:mem:nothing-here" };
                    query { σ id > 1 (warehouse.orders) };
                    """), "<test>");

            assertThat(result.isFullyValid()).isTrue();
        }

        @Test
        @DisplayName("it lists the tables it recorded for a connection, once each")
        void listsItsTables() {
            CatalogSnapshot snapshot = CatalogSnapshot.of(List.of(introspected(),
                    new CatalogSnapshot.Entry("Warehouse", "customers", Optional.of(ORDERS),
                            Optional.empty(), CatalogSnapshot.Origin.INTROSPECTED, EARLY),
                    new CatalogSnapshot.Entry("warehouse", "orders", Optional.empty(),
                            Optional.of(RelationStatistics.of(1)),
                            CatalogSnapshot.Origin.OBSERVED, LATE),
                    new CatalogSnapshot.Entry("lake", "events", Optional.of(ORDERS),
                            Optional.empty(), CatalogSnapshot.Origin.INTROSPECTED, EARLY)),
                    EARLY);

            assertThat(snapshot.tables(WAREHOUSE)).contains(List.of("orders", "customers"));
            assertThat(CatalogSnapshot.empty().tables(WAREHOUSE)).contains(List.of());
            assertThat(CatalogProvider.NONE.tables(WAREHOUSE))
                    .as("a provider that cannot enumerate says so")
                    .isEmpty();
        }

        @Test
        @DisplayName("a misspelt table gets a suggestion from the tables it recorded")
        void suggestsAMisspeltTable() {
            CatalogSnapshot snapshot = CatalogSnapshot.of(List.of(introspected()), EARLY);
            SemanticAnalyzer analyzer = new SemanticAnalyzer(
                    new InMemoryScriptLoader(Map.of()), BuiltinProvider.none(), snapshot);

            SemanticResult result = analyzer.analyze(com.darkcollective.relix.lang.ScriptParser.parse(
                    """
                    connection warehouse from database { url: "jdbc:h2:mem:nothing-here" };
                    query { π id (warehouse.ordrs) };
                    query { π id (warehouse.invoices) };
                    """), "<test>");

            assertThat(result.errors()).extracting(SemanticError::message)
                    .anySatisfy(m -> assertThat(m)
                            .startsWith("Cannot resolve schema for table 'ordrs'")
                            .endsWith(" — did you mean 'warehouse.orders'?"))
                    .anySatisfy(m -> assertThat(m)
                            .startsWith("Cannot resolve schema for table 'invoices'")
                            .as("nothing close, nothing suggested")
                            .doesNotContain("did you mean"));
        }
    }

    @Nested
    @DisplayName("provenance — a log of observations, not a merged picture")
    final class Provenance {

        @Test
        @DisplayName("a measured row count beats an introspected estimate")
        void observedWins() {
            RelationStatistics measured = RelationStatistics.of(1207);
            CatalogSnapshot snapshot = CatalogSnapshot.of(List.of(introspected()), EARLY)
                    .with(new CatalogSnapshot.Entry("warehouse", "orders",
                            Optional.empty(), Optional.of(measured),
                            CatalogSnapshot.Origin.OBSERVED, LATE));

            assertThat(snapshot.tableStatistics(WAREHOUSE, "orders")).contains(measured);
        }

        /**
         * The reason entries are picked per field rather than merged: the run that
         * measured the count saw rows, not a catalog, so it has no schema to offer — and
         * an entry assembled from two would have to invent an origin.
         */
        @Test
        @DisplayName("and does not take the schema down with it")
        void observedDoesNotDiscardTheSchema() {
            CatalogSnapshot snapshot = CatalogSnapshot.of(List.of(introspected()), EARLY)
                    .with(new CatalogSnapshot.Entry("warehouse", "orders",
                            Optional.empty(), Optional.of(RelationStatistics.of(1207)),
                            CatalogSnapshot.Origin.OBSERVED, LATE));

            assertThat(snapshot.tableSchema(WAREHOUSE, "orders")).contains(ORDERS);
        }

        @Test
        @DisplayName("within one origin the most recent observation wins")
        void mostRecentWins() {
            RelationStatistics fresh = RelationStatistics.of(9999);
            CatalogSnapshot snapshot = CatalogSnapshot.of(List.of(introspected()), EARLY)
                    .with(new CatalogSnapshot.Entry("warehouse", "orders",
                            Optional.empty(), Optional.of(fresh),
                            CatalogSnapshot.Origin.INTROSPECTED, LATE));

            assertThat(snapshot.tableStatistics(WAREHOUSE, "orders")).contains(fresh);
        }

        @Test
        @DisplayName("every observation is kept, so what was seen when stays readable")
        void everyObservationIsKept() {
            CatalogSnapshot snapshot = CatalogSnapshot.of(List.of(introspected()), EARLY)
                    .with(new CatalogSnapshot.Entry("warehouse", "orders",
                            Optional.empty(), Optional.of(RelationStatistics.of(1207)),
                            CatalogSnapshot.Origin.OBSERVED, LATE));

            assertThat(snapshot.entries()).hasSize(2)
                    .extracting(CatalogSnapshot.Entry::captured)
                    .containsExactly(EARLY, LATE);
        }
    }

    @Nested
    @DisplayName("capture — the model says which tables matter")
    final class Capture {

        private static final String SCRIPT = """
                connection warehouse from database { url: "jdbc:h2:mem:x" };
                source Orders from warehouse { table: "orders",
                    schema: { id: NUMBER } };
                source Invoices from warehouse { table: "invoices",
                    schema: { id: NUMBER } };
                query { Orders };
                """;

        @Test
        @DisplayName("it records what the live provider answers, and nothing else")
        void recordsWhatIsAnswered() {
            CatalogSnapshot snapshot = CatalogSnapshot.capture(
                    new OneTable(ORDERS, ORDER_STATS), model(SCRIPT),
                    Clock.fixed(LATE, ZoneOffset.UTC));

            assertThat(snapshot.entries()).singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.table()).isEqualTo("orders");
                        assertThat(entry.origin()).isEqualTo(CatalogSnapshot.Origin.INTROSPECTED);
                        assertThat(entry.captured()).isEqualTo(LATE);
                    });
            assertThat(snapshot.captured()).isEqualTo(LATE);
        }

        /**
         * An entry carrying neither half is indistinguishable, on replay, from a table
         * the snapshot never heard of — so recording one would be a row that says
         * nothing and can only mislead a reader.
         */
        @Test
        @DisplayName("a table the provider says nothing about is not recorded")
        void silenceIsNotRecorded() {
            CatalogSnapshot snapshot = CatalogSnapshot.capture(
                    CatalogProvider.NONE, model(SCRIPT), Clock.fixed(LATE, ZoneOffset.UTC));

            assertThat(snapshot.entries()).isEmpty();
        }

        @Test
        @DisplayName("captured live, replayed offline: the same schema comes back")
        void captureThenReplay() {
            CatalogSnapshot snapshot = CatalogSnapshot.capture(
                    new OneTable(ORDERS, ORDER_STATS), model(SCRIPT),
                    Clock.fixed(LATE, ZoneOffset.UTC));

            assertThat(snapshot.tableSchema(WAREHOUSE, "orders")).contains(ORDERS);
        }
    }

    @Nested
    @DisplayName("serialization")
    final class Serialization {

        @Test
        @DisplayName("a snapshot round-trips, nested types and all")
        void roundTrips() {
            CatalogSnapshot original = CatalogSnapshot.of(List.of(introspected()), EARLY)
                    .with(new CatalogSnapshot.Entry("warehouse", "invoices",
                            Optional.empty(), Optional.of(RelationStatistics.of(7)),
                            CatalogSnapshot.Origin.OBSERVED, LATE));

            CatalogSnapshot read = CatalogSnapshot.parse(original.toJson());

            assertThat(read.entries()).isEqualTo(original.entries());
            assertThat(read.captured()).isEqualTo(EARLY);
            assertThat(read.tableSchema(WAREHOUSE, "orders")).contains(ORDERS);
            assertThat(read.tableStatistics(WAREHOUSE, "invoices"))
                    .contains(RelationStatistics.of(7));
        }

        @Test
        @DisplayName("an empty snapshot round-trips too")
        void emptyRoundTrips() {
            assertThat(CatalogSnapshot.parse(CatalogSnapshot.empty().toJson()).entries()).isEmpty();
        }

        @Test
        @DisplayName("a document from a later format version is refused, not half-read")
        void refusesAFutureVersion() {
            String future = CatalogSnapshot.empty().toJson().replace("\"version\":1", "\"version\":2");

            assertThatThrownBy(() -> CatalogSnapshot.parse(future))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("version 2");
        }

        @Test
        @DisplayName("text that is not a snapshot is refused")
        void refusesNonsense() {
            assertThatThrownBy(() -> CatalogSnapshot.parse("[]"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CatalogSnapshot.parse("{}"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * What a log line or a failure message shows. Worth pinning because the useful facts
     * are the two a reader needs — how much it holds and when it was taken — and a
     * default {@code toString} would give neither.
     */
    @Test
    @DisplayName("prints its size and capture time")
    void describesItself() {
        CatalogSnapshot snapshot = CatalogSnapshot.of(List.of(introspected()), EARLY);

        assertThat(snapshot.toString())
                .startsWith("CatalogSnapshot[")
                .contains("1 entries")
                .contains(EARLY.toString());
    }
}
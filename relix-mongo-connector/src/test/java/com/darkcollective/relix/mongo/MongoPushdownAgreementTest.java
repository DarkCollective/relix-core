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
package com.darkcollective.relix.mongo;

import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.QueryExecutor;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.SemanticResult;
import com.darkcollective.relix.symbol.Schema;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs one query both ways — folded into an aggregation pipeline and evaluated in-engine —
 * over the same collection, and requires the same answer.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The SQL side got this in #645, and it found a real NULL-ordering bug on its first run.
 * MongoDB had no counterpart, and the gap was wider than "one backend is missing a test":
 * {@code MongoPushdownPlannerTest} and {@code MongoExpressionsTest} assert the pipeline
 * <em>string</em>, which is the renderer checked against itself, and the one container test
 * that ran a pipeline <strong>hand-wrote the envelope</strong>. So the renderer could have
 * emitted any pipeline at all and every test would still have passed.
 *
 * <p>MongoDB is also the only independent oracle available here. The engine and its manual
 * can be wrong together; a database somebody else wrote cannot be talked into agreeing.
 *
 * <h2>How the two runs differ</h2>
 *
 * <p>Only in the plan. {@code Planner} installs {@code MongoPushdownPlanner} only when it is
 * given a {@code mongodb} connection, so the same model with an empty connections map plans
 * a plain {@code Scan}. The connector is the same object in both runs and serves the rows
 * either way — same data, same schema, same connector.
 *
 * <p>Each case asserts the pushed run <em>was</em> pushed, by watching for the planner's
 * {@code PUSHDOWN} event. Without that, a query the renderer quietly declined would compare
 * two identical in-engine runs and pass while proving nothing.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("A pushed MongoDB pipeline returns what the in-engine plan returns")
final class MongoPushdownAgreementTest {

    private static final String DATABASE = "agreement";
    private static final String COLLECTION = "orders";

    @Container
    static final MongoDBContainer MONGO = MongoContainers.hostBound();

    /**
     * Documents chosen so every field has a missing-or-null somewhere and every predicate
     * has a boundary — plus a nested sub-document and an array, which is the shape a
     * document store is actually for and the one the coercion bug lived in.
     */
    @BeforeAll
    static void seed() {
        try (MongoClient client = MongoClients.create(MONGO.getConnectionString())) {
            client.getDatabase(DATABASE).getCollection(COLLECTION).insertMany(List.of(
                    order(1, "west", "AB-1", 100, List.of("red", "blue"), "Berlin",
                            Date.from(java.time.Instant.parse("2026-06-15T10:00:00Z"))),
                    order(2, "west", "AB-2", 250, List.of("red"), "Hamburg",
                            Date.from(java.time.Instant.parse("2026-06-16T10:00:00Z"))),
                    order(3, "east", "XY-1", 100, List.of(), "Berlin",
                            Date.from(java.time.Instant.parse("2026-06-17T10:00:00Z"))),
                    // amount and code absent entirely — a missing field, not a null one
                    new Document("oid", 4).append("region", "east")
                            .append("tags", List.of("green"))
                            .append("address", new Document("city", "Munich")),
                    // an explicit null, which Mongo distinguishes from absent and the
                    // engine does not
                    new Document("oid", 5).append("region", null).append("code", "AB-3")
                            .append("amount", 50).append("tags", null).append("address", null)));
        }
    }

    private static Document order(int oid, String region, String code, int amount,
                                  List<String> tags, String city, Date at) {
        return new Document("oid", oid)
                .append("region", region)
                .append("code", code)
                .append("amount", amount)
                .append("tags", tags)
                .append("address", new Document("city", city).append("zip", "0" + oid))
                .append("at", at);
    }

    private static String preamble() {
        return "connection mg from mongodb { uri: \"" + MONGO.getConnectionString() + "\", "
               + "database: \"" + DATABASE + "\" };\n"
               + "source Orders from mg {\n"
               + "    table: \"" + COLLECTION + "\",\n"
               + "    schema: { oid: NUMBER, region: STRING, code: STRING, amount: NUMBER,\n"
               + "              tags: ANY, address: ANY, at: TIMESTAMP }\n"
               + "};\n\n";
    }

    // ── the harness ─────────────────────────────────────────────────────────────

    /** One execution: the rows it produced, and whether the planner pushed anything. */
    private record Run(List<String> rows, boolean pushed) {}

    /**
     * Asserts that {@code expression} means the same thing folded into a pipeline as it
     * does evaluated in-engine.
     *
     * <p>Rows are compared without regard to order unless the expression bounds or sorts
     * them — an unordered relation has no order to disagree about, and MongoDB is free to
     * return one in any order it likes.
     */
    private static void assertAgrees(String expression) {
        String script = preamble() + "query { " + expression + " };";
        SemanticResult analysis = SemanticFixtures.analyze(script);
        assertThat(analysis.errors()).as("analysis of: %s", expression).isEmpty();
        SemanticModel model = analysis.model().orElseThrow();
        DataSourceConnector connector = mongoConnector(model);

        Run pushed = run(model, connector);
        Run inEngine = run(withoutConnections(model), connector);

        assertThat(pushed.pushed())
                .as("the planner folded this into a pipeline — otherwise both runs are the "
                    + "same in-engine plan and this case checks nothing: %s", expression)
                .isTrue();
        assertThat(inEngine.pushed())
                .as("the in-engine run pushed nothing: %s", expression)
                .isFalse();

        if (ordered(expression)) {
            assertThat(pushed.rows())
                    .as("pushed vs in-engine (order significant): %s", expression)
                    .containsExactlyElementsOf(inEngine.rows());
        } else {
            assertThat(pushed.rows())
                    .as("pushed vs in-engine: %s", expression)
                    .containsExactlyInAnyOrderElementsOf(inEngine.rows());
        }
    }

    /** True when the expression establishes an order the comparison must respect. */
    private static boolean ordered(String expression) {
        return expression.contains("τ") || expression.contains("λ") || expression.contains("TOP");
    }

    /**
     * The engine-level connector, delegating both reads to the plugin under test.
     *
     * <p>Deliberately a thin adapter rather than {@code CompositeDataSourceConnector}: that
     * one builds its registry from {@code ConnectorRegistry.create()}, which scans the
     * machine's {@code ~/.relix/connectors/} directory — so on a developer's box with the
     * plugin installed this suite could bind to a <em>different build</em> of the code it is
     * testing. The runtime's own dispatch is covered by
     * {@code CompositeDataSourceConnectorTest} instead, where a fake connector makes the
     * routing observable without a container.
     */
    private static DataSourceConnector mongoConnector(SemanticModel model) {
        MongoConnector mongo = new MongoConnector();
        ConnectorConfig config = new ConnectorConfig(Map.of(
                "uri", MONGO.getConnectionString(), "database", DATABASE));
        return new DataSourceConnector() {
            @Override
            public Stream<Row> open(String relationName, Schema schema) {
                return mongo.open(config, COLLECTION, schema);
            }

            @Override
            public Stream<Row> openQuery(String connectorType, String connection,
                                         String nativeQuery, Schema schema) {
                assertThat(connectorType).isEqualTo("mongodb");
                return mongo.openQuery(config, nativeQuery, schema);
            }
        };
    }

    private static Run run(SemanticModel model, DataSourceConnector connector) {
        List<String> rows = new ArrayList<>();
        boolean[] pushed = {false};
        QueryEventListener listener = event -> {
            if ("PUSHDOWN".equals(event.code()) && event.stage() == QueryEvent.Stage.PLAN) {
                pushed[0] = true;
            }
        };
        new QueryExecutor().executeStreaming(model, connector,
                (label, schema, stream) -> stream.forEach(row -> rows.add(render(row))),
                ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, listener);
        return new Run(rows, pushed[0]);
    }

    /** The same model with no connections, which is what makes the planner fold nothing. */
    private static SemanticModel withoutConnections(SemanticModel model) {
        return new SemanticModel(model.namespace(), model.symbolTable(), model.sources(),
                Map.of(), model.statistics(), model.nodeSchemas(), model.schemaGraph(),
                model.rootQueries(), model.functions());
    }

    private static String render(Row row) {
        return IntStream.range(0, row.width())
                .mapToObj(i -> row.get(i).isNull() ? "NULL" : row.get(i).asDisplayString())
                .reduce((a, b) -> a + "|" + b).orElse("");
    }

    // ── the corpus ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("σ → $match, over fields that are missing, null, or both")
    class Selections {

        @Test
        @DisplayName("comparisons")
        void comparisons() {
            assertAgrees("σ amount > 100 (Orders)");
            assertAgrees("σ amount ≥ 100 (Orders)");
            assertAgrees("σ amount < 100 (Orders)");
            assertAgrees("σ amount ≤ 100 (Orders)");
            assertAgrees("σ amount = 100 (Orders)");
            // $ne matches a document with no such field, where the engine's ≠ against a
            // missing value is UNKNOWN and drops the row — the guard MongoExpressions adds.
            assertAgrees("σ amount ≠ 100 (Orders)");
            assertAgrees("σ region = 'west' (Orders)");
            assertAgrees("σ region ≠ 'west' (Orders)");
        }

        @Test
        @DisplayName("null tests, where an absent field and a null one must read alike")
        void nullTests() {
            assertAgrees("σ amount = NULL (Orders)");
            assertAgrees("σ amount ≠ NULL (Orders)");
            assertAgrees("σ region = NULL (Orders)");
            assertAgrees("σ code = NULL (Orders)");
        }

        @Test
        @DisplayName("connectives")
        void connectives() {
            assertAgrees("σ amount > 50 ∧ region = 'west' (Orders)");
            assertAgrees("σ amount > 200 ∨ region = 'east' (Orders)");
            assertAgrees("σ amount > 50 ∧ code ≠ 'AB-1' (Orders)");
            assertAgrees("σ (amount > 50 ∧ region = 'west') ∨ oid = 3 (Orders)");
        }

        @Test
        @DisplayName("membership")
        void membership() {
            assertAgrees("σ region ∈ {'west', 'east'} (Orders)");
            assertAgrees("σ region ∉ {'west'} (Orders)");
            assertAgrees("σ amount ∈ {100, 250} (Orders)");
            assertAgrees("σ code ∉ {'AB-1', 'AB-2'} (Orders)");
        }

        @Test
        @DisplayName("pattern, including the negated form's $not + $regex")
        void patterns() {
            assertAgrees("σ code LIKE 'AB%' (Orders)");
            assertAgrees("σ code LIKE '%-1' (Orders)");
            assertAgrees("σ code LIKE 'AB-_' (Orders)");
            // $not with a $regex operator expression needs MongoDB 4.0.7+; nothing pinned
            // that floor before, and nothing had ever run this pipeline against a server.
            assertAgrees("σ code NOT LIKE 'AB%' (Orders)");
        }

        @Test
        @DisplayName("a TIMESTAMP literal, which rides as extended-JSON {\"$date\": …}")
        void timestampLiteral() {
            assertAgrees("σ at > TIMESTAMP '2026-06-15T12:00:00Z' (Orders)");
            assertAgrees("σ at ≤ TIMESTAMP '2026-06-16T10:00:00Z' (Orders)");
        }
    }

    @Nested
    @DisplayName("π → $project, μ → $unwind, λ → $skip/$limit")
    class OtherStages {

        @Test
        @DisplayName("a column-pruning projection")
        void projection() {
            assertAgrees("π oid, amount (Orders)");
            assertAgrees("π oid (Orders)");
        }

        @Test
        @DisplayName("unnest over an array field, including the empty and null arrays")
        void unnest() {
            // $unwind drops a document whose array is empty, absent or null; the engine's
            // inner μ does the same. oid 3 (empty), 5 (null) must vanish from both.
            assertAgrees("μ tags (Orders)");
        }

        @Test
        @DisplayName("limit and offset")
        void limits() {
            assertAgrees("λ 2 (τ oid (Orders))");
            assertAgrees("λ 1, 2 (τ oid (Orders))");
        }

        @Test
        @DisplayName("the full stack, in pipeline order")
        void fullStack() {
            assertAgrees("λ 3 (π oid, tags (μ tags (σ amount ≥ 100 (Orders))))");
        }
    }

    @Nested
    @DisplayName("nested values survive both routes identically")
    class NestedValues {

        @Test
        @DisplayName("a sub-document and an array read the same pushed or in-engine")
        void nestedRoundTrip() {
            // The coercion bug this suite was written alongside: a nested column used to
            // become the BSON document's Java toString. Both routes go through the same
            // toRow, so agreement alone would not have caught it — hence the absolute
            // assertion below as well.
            assertAgrees("π oid, address, tags (σ oid ≤ 2 (Orders))");
        }

        @Test
        @DisplayName("a sub-document arrives as a struct, not as its Java toString")
        void nestedIsAStructNotAString() {
            // The independent-oracle half: agreement says the two routes match, and this
            // says what they must both be. Two identically-wrong sides pass the first and
            // fail the second.
            SemanticModel model = SemanticFixtures.analyze(preamble()
                    + "query { π address (σ oid = 1 (Orders)) };").model().orElseThrow();

            List<String> rows = run(model, mongoConnector(model)).rows();

            assertThat(rows).singleElement().satisfies(rendered -> {
                assertThat(rendered).doesNotContain("Document{");
                assertThat(rendered).contains("Berlin");
            });
        }

        @Test
        @DisplayName("a column declared as a struct is read as the declared shape")
        void declaredNestedColumnIsRead() {
            // Until the grammar could spell a nested column (#684) this arm of
            // MongoConnector.coerce was unreachable from a script — ANY was the only route,
            // so the *declared* StructType path had no caller. This is that loop closed:
            // the declaration is written in Relix, and the connector honours it.
            String declared = "connection mg from mongodb { uri: \"" + MONGO.getConnectionString()
                    + "\", database: \"" + DATABASE + "\" };\n"
                    + "source Typed from mg {\n"
                    + "    table: \"" + COLLECTION + "\",\n"
                    + "    schema: { oid: NUMBER, address: { city: STRING, zip: STRING },\n"
                    + "              tags: [STRING] }\n"
                    + "};\n";
            SemanticModel model = SemanticFixtures.analyze(declared
                    + "query { π address.city → city (σ oid = 1 (Typed)) };").model().orElseThrow();

            assertThat(run(model, mongoConnector(model)).rows())
                    .as("the declared field types the path, and the connector fills it")
                    .containsExactly("Berlin");
        }

        @Test
        @DisplayName("a declared field the document omits is NULL, not absent")
        void declaredFieldMissingFromTheDocumentIsNull() {
            // oid 4 carries an address with a city and no zip. The struct takes its heading
            // from the declaration, so zip is present-and-NULL rather than missing — which
            // is what makes the column's shape the same for every row.
            String declared = "connection mg from mongodb { uri: \"" + MONGO.getConnectionString()
                    + "\", database: \"" + DATABASE + "\" };\n"
                    + "source Typed from mg {\n"
                    + "    table: \"" + COLLECTION + "\",\n"
                    + "    schema: { oid: NUMBER, address: { city: STRING, zip: STRING } }\n"
                    + "};\n";
            SemanticModel model = SemanticFixtures.analyze(declared
                    + "query { π address.zip → zip (σ oid = 4 (Typed)) };").model().orElseThrow();

            assertThat(run(model, mongoConnector(model)).rows()).containsExactly("NULL");
        }

        @Test
        @DisplayName("a struct field is reachable by name through a pushed scan")
        void structFieldIsReachable() {
            // #641 made a struct field reachable by name; that is worth nothing if the
            // connector never produces a struct.
            assertAgrees("π oid (σ oid = 1 (Orders))");
            SemanticModel model = SemanticFixtures.analyze(preamble()
                    + "query { π address.city → city (σ oid = 1 (Orders)) };").model().orElseThrow();

            assertThat(run(model, mongoConnector(model)).rows()).containsExactly("Berlin");
        }
    }

    @Nested
    @DisplayName("what the renderer declines still has to be right")
    class Fallbacks {

        @Test
        @DisplayName("an operator the renderer defers is evaluated in-engine over a pushed scan")
        void deferredOperatorsStillAgree() {
            // ADR-0011 defers τ and γ for this backend, and MongoExpressions declines a
            // general ¬ because the null-guard it would need names every field the inner
            // predicate reads. Declining costs a slower plan and never a wrong answer —
            // which is a claim about *rows*, so it is checked as one.
            //
            // Note these still report PUSHDOWN: a bare `Orders` leaf is itself a
            // PushedScan carrying an empty pipeline, so the scan is pushed and only the
            // operator above it runs in-engine. That is why there is no "nothing was
            // pushed" assertion here — the interesting property is that the two plans
            // agree, and MongoPushdownPlannerTest already pins which stages fold.
            assertAgrees("τ amount (Orders)");
            assertAgrees("γ region, SUM(amount) → total (Orders)");
            assertAgrees("σ ¬(amount > 50 ∧ region = 'west') (Orders)");
            assertAgrees("σ ¬(region = 'west') (Orders)");
        }

        @Test
        @DisplayName("a negated conjunction follows SQL's three-valued rule, not intuition")
        void negationIsThreeValued() {
            // The absolute oracle beside the relative one. oid 4 has no `amount` at all,
            // so `amount > 50` is UNKNOWN — but `UNKNOWN ∧ FALSE` is **FALSE**, not
            // UNKNOWN, so the negation keeps it. The row a reader expects to drop is
            // exactly the row that survives, which is why this is written down rather
            // than left to the agreement check: two identically-wrong sides agree.
            SemanticModel model = SemanticFixtures.analyze(preamble()
                    + "query { π oid (σ ¬(amount > 50 ∧ region = 'west') (Orders)) };")
                    .model().orElseThrow();

            assertThat(run(model, mongoConnector(model)).rows())
                    .as("oid 3 (east, 100), 4 (east, no amount) and 5 (null region, 50)")
                    .containsExactlyInAnyOrder("3", "4", "5");
        }
    }
}

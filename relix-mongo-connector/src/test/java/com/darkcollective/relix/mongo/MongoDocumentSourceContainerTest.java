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

import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration coverage for {@link MongoDocumentSource}: verifies the live
 * mongodb-driver-sync I/O boundary directly, without going through row coercion in
 * {@link MongoConnector}.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
final class MongoDocumentSourceContainerTest {

    private static final String DATABASE = "relixtest";
    private static final String COLLECTION = "documents";

    @Container
    static final MongoDBContainer MONGO = MongoContainers.loopbackBound();

    private final MongoDocumentSource source = new MongoDocumentSource();

    @BeforeEach
    void seed() {
        try (MongoClient client = MongoClients.create(MONGO.getConnectionString())) {
            var collection = client.getDatabase(DATABASE).getCollection(COLLECTION);
            collection.drop();
            collection.insertMany(List.of(
                    new Document("id", 1)
                            .append("name", "alice")
                            .append("active", true)
                            .append("score", 9)
                            .append("team", "red"),
                    new Document("id", 2)
                            .append("name", "bob")
                            .append("active", false)
                            .append("score", 4)
                            .append("team", "blue"),
                    new Document("id", 3)
                            .append("name", "carol")
                            .append("active", true)
                            .append("score", 7)
                            .append("team", "red")));
        }
    }

    @Test
    void documentsStreamsAllDocumentsFromCollection() {
        ConnectorConfig config = config();

        try (Stream<Map<String, Object>> documents = source.documents(config, COLLECTION)) {
            List<Map<String, Object>> result = documents.toList();

            assertThat(result).hasSize(3);
            assertThat(result).extracting(document -> document.get("name"))
                    .containsExactlyInAnyOrder("alice", "bob", "carol");
            assertThat(result).extracting(document -> document.get("active"))
                    .containsExactlyInAnyOrder(true, false, true);
        }
    }

    @Test
    void documentsReturnBsonDocumentsAsPlainMaps() {
        ConnectorConfig config = config();

        try (Stream<Map<String, Object>> documents = source.documents(config, COLLECTION)) {
            Map<String, Object> alice = documents
                    .filter(document -> document.get("name").equals("alice"))
                    .findFirst()
                    .orElseThrow();

            assertThat(alice).containsEntry("id", 1);
            assertThat(alice).containsEntry("name", "alice");
            assertThat(alice).containsEntry("active", true);
            assertThat(alice).containsEntry("score", 9);
            assertThat(alice).containsEntry("team", "red");
        }
    }

    @Test
    void documentsStreamCanBeClosedBeforeItIsFullyConsumed() {
        ConnectorConfig config = config();

        try (Stream<Map<String, Object>> documents = source.documents(config, COLLECTION)) {
            Map<String, Object> first = documents.findFirst().orElseThrow();

            assertThat(first).containsKeys("_id", "id", "name");
        }
    }

    @Test
    void aggregateRunsMatchStageInMongo() {
        ConnectorConfig config = config();
        List<Map<String, Object>> pipeline = List.of(
                Map.of("$match", Map.of("active", Map.of("$eq", true))));

        try (Stream<Map<String, Object>> documents = source.aggregate(config, COLLECTION, pipeline)) {
            List<Map<String, Object>> result = documents.toList();

            assertThat(result).hasSize(2);
            assertThat(result).extracting(document -> document.get("name"))
                    .containsExactlyInAnyOrder("alice", "carol");
        }
    }

    /**
     * The documents as plain maps, so they can be compared to one.
     *
     * <p>This source hands back {@code org.bson.Document}s under the SPI's
     * {@code Map<String, Object>} type, which is deliberate: the engine's own
     * consumer coerces each one to a {@code Row} immediately, so copying every
     * document into a {@code LinkedHashMap} on a streaming path would buy nothing.
     *
     * <p>But {@code Document.equals} begins {@code getClass() != o.getClass()}, so a
     * Document is <em>never</em> equal to any other {@code Map} however identical its
     * entries — which is why the failure printed the expected and actual values
     * looking exactly alike. Comparing entries is the assertion that was meant.
     */
    private static List<Map<String, Object>> entriesOf(Stream<Map<String, Object>> documents) {
        return documents.<Map<String, Object>>map(LinkedHashMap::new).toList();
    }

    @Test
    void aggregateRunsMultipleStagesInOrder() {
        ConnectorConfig config = config();
        List<Map<String, Object>> pipeline = List.of(
                Map.of("$match", Map.of("team", Map.of("$eq", "red"))),
                Map.of("$sort", Map.of("score", -1)),
                Map.of("$project", Map.of("_id", 0, "name", 1, "score", 1)),
                Map.of("$limit", 1));

        try (Stream<Map<String, Object>> documents = source.aggregate(config, COLLECTION, pipeline)) {
            assertThat(entriesOf(documents)).containsExactly(Map.of(
                    "name", "alice",
                    "score", 9));
        }
    }

    @Test
    void aggregateWithEmptyPipelineBehavesLikeCollectionScan() {
        ConnectorConfig config = config();

        try (Stream<Map<String, Object>> documents = source.aggregate(config, COLLECTION, List.of())) {
            List<Map<String, Object>> result = documents.toList();

            assertThat(result).hasSize(3);
            assertThat(result).extracting(document -> document.get("name"))
                    .containsExactlyInAnyOrder("alice", "bob", "carol");
        }
    }

    @Test
    void aggregateConvertsNestedPipelineMapsToBsonDocuments() {
        ConnectorConfig config = config();
        List<Map<String, Object>> pipeline = List.of(
                Map.of("$group", Map.of(
                        "_id", "$team",
                        "count", Map.of("$sum", 1),
                        "maxScore", Map.of("$max", "$score"))),
                Map.of("$sort", Map.of("_id", 1)));

        try (Stream<Map<String, Object>> documents = source.aggregate(config, COLLECTION, pipeline)) {
            assertThat(entriesOf(documents)).containsExactly(
                    Map.of("_id", "blue", "count", 1, "maxScore", 4),
                    Map.of("_id", "red", "count", 2, "maxScore", 9));
        }
    }

    @Test
    void missingUriConfigIsRejectedBeforeOpeningMongoClient() {
        ConnectorConfig config = new ConnectorConfig(Map.of("database", DATABASE));

        // ConnectorConfig.require documents EvaluationException, and has thrown it
        // since the SPI landed. These assertions named IllegalArgumentException and
        // nothing noticed, because the tier they live in is excluded from `build`.
        assertThatExceptionOfType(EvaluationException.class)
                .isThrownBy(() -> source.documents(config, COLLECTION))
                .withMessageContaining("uri");
    }

    @Test
    void missingDatabaseConfigIsRejectedWhenCollectionIsOpened() {
        ConnectorConfig config = new ConnectorConfig(Map.of("uri", MONGO.getConnectionString()));

        assertThatExceptionOfType(EvaluationException.class)
                .isThrownBy(() -> source.documents(config, COLLECTION))
                .withMessageContaining("database");
    }

    @Test
    void invalidAggregationPipelineClosesClientAndPropagatesDriverFailure() {
        ConnectorConfig config = config();
        List<Map<String, Object>> invalidPipeline = List.of(
                Map.of("$definitelyNotARealMongoStage", Map.of()));

        assertThatExceptionOfType(MongoCommandException.class)
                .isThrownBy(() -> {
                    try (Stream<Map<String, Object>> documents =
                                 source.aggregate(config, COLLECTION, invalidPipeline)) {
                        documents.toList();
                    }
                })
                .withMessageContaining("$definitelyNotARealMongoStage");
    }

    private static ConnectorConfig config() {
        return new ConnectorConfig(Map.of(
                "uri", MONGO.getConnectionString(),
                "database", DATABASE));
    }
}

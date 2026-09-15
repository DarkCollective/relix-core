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

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of {@link MongoConnector} against a real MongoDB running in a
 * throwaway container — exercising the live {@link MongoDocumentSource} and the
 * {@code mongodb-driver-sync} I/O path, not just the row mapping.
 *
 * <p>Requires Docker: the class is skipped automatically when Docker is not
 * available ({@code disabledWithoutDocker = true}), so it is a no-op in
 * environments (or CI lanes) without a Docker daemon.  No publishing of the
 * connector plugin is needed — the in-repo {@link MongoConnector} is driven directly.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
final class MongoConnectorContainerTest {

    private static final String DATABASE = "relixtest";
    private static final String COLLECTION = "users";

    @Container
    static final MongoDBContainer MONGO = MongoContainers.loopbackBound();

    @BeforeAll
    static void seed() {
        try (MongoClient client = MongoClients.create(MONGO.getConnectionString())) {
            client.getDatabase(DATABASE).getCollection(COLLECTION).insertMany(List.of(
                    new Document("id", 1).append("name", "alice").append("active", true),
                    new Document("id", 2).append("name", "bob").append("active", false)));
        }
    }

    @Test
    void readsDocumentsFromLiveMongo() {
        ConnectorConfig config = new ConnectorConfig(Map.of(
                "uri", MONGO.getConnectionString(),
                "database", DATABASE));
        Schema schema = new Schema(List.of(
                new ColumnDefinition("id", ScalarType.NUMBER),
                new ColumnDefinition("name", ScalarType.STRING),
                new ColumnDefinition("active", ScalarType.BOOLEAN)));

        try (var rows = new MongoConnector().open(config, COLLECTION, schema)) {
            List<Row> result = rows.toList();

            assertThat(result).hasSize(2);
            assertThat(result).extracting(r -> r.get("name"))
                    .containsExactlyInAnyOrder(new StringValue("alice"), new StringValue("bob"));
            Row alice = result.stream()
                    .filter(r -> r.get("name").equals(new StringValue("alice")))
                    .findFirst().orElseThrow();
            assertThat(alice.get("id")).isEqualTo(new NumberValue(new BigDecimal("1")));
        }
    }

    @Test
    void runsPushedMatchPipelineFromLiveMongo() {
        // The envelope a MongoPushdownPlanner-folded σ id = 1 (Users) produces — runs the
        // $match in the database and streams just the matching document.
        ConnectorConfig config = new ConnectorConfig(Map.of(
                "uri", MONGO.getConnectionString(),
                "database", DATABASE));
        Schema schema = new Schema(List.of(
                new ColumnDefinition("id", ScalarType.NUMBER),
                new ColumnDefinition("name", ScalarType.STRING)));
        String envelope = "{\"collection\": \"" + COLLECTION + "\", \"pipeline\": "
                + "[{\"$match\": {\"id\": {\"$eq\": 1}}}]}";

        try (var rows = new MongoConnector().openQuery(config, envelope, schema)) {
            List<Row> result = rows.toList();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("name")).isEqualTo(new StringValue("alice"));
            assertThat(result.get(0).get("id")).isEqualTo(new NumberValue(new BigDecimal("1")));
        }
    }
}

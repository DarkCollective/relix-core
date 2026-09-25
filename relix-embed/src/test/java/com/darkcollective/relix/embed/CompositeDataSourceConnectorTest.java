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

import com.darkcollective.relix.connectors.std.internal.ConnectionPool;
import com.darkcollective.relix.connectors.std.internal.FileResolver;
import com.darkcollective.relix.connectors.std.DriverProvisioner;
import com.darkcollective.relix.processor.connector.ConnectorProvisioner;
import com.darkcollective.relix.processor.connector.Fetcher;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.generator.GeneratorRegistry;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a relation reaches the connector that can read it.
 *
 * <p>{@link CompositeDataSourceConnector} is the runtime's dispatcher: it reads a source
 * declaration and routes to CSV, JSON, HTTP, JDBC, a generator, or — for a
 * {@code connection}-backed source whose type is not {@code jdbc} — to whatever
 * {@link com.darkcollective.relix.processor.connector.RelixConnector} the registry has for
 * that token. That last arm is how a MongoDB scan and a <em>pushed</em> MongoDB pipeline
 * both leave the engine, and it had <strong>no covered lines at all</strong>: the plugin's
 * own container tests reach it by calling {@code new MongoConnector()} directly, so the
 * wiring between the two had never executed.
 *
 * <p>The connector under test is exercised through a fake ({@link FakeRegisteredConnector})
 * handed to the constructor as a supplied connector, rather than through a real plugin, so
 * the test does not depend on what is installed in the developer's
 * {@code ~/.relix/connectors/} directory. A supplied connector goes into the same
 * {@code ConnectorRegistry} as a discovered one and is found by the same {@code forType}
 * lookup, so this routes exactly as a plugin does — and, unlike the
 * {@code META-INF/services} registration this replaced, it is visible only to the test
 * that asks for it. That matters here: this module's guide examples print the discovered
 * component inventory, which a service-registered fake would appear in.
 */
@DisplayName("CompositeDataSourceConnector — routing a relation to its connector")
final class CompositeDataSourceConnectorTest {

    @TempDir
    Path baseDir;

    private static final Schema SCHEMA = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.STRING)));

    @BeforeEach
    void forgetPreviousCalls() {
        FakeRegisteredConnector.reset();
    }

    /**
     * Downloads are off, so the fetcher is never called and refuses if it ever is — a
     * test that reached the network would be measuring the network.
     */
    private static final Fetcher NO_NETWORK = uri -> {
        throw new AssertionError("no test here may fetch: " + uri);
    };

    private CompositeDataSourceConnector connectorFor(SemanticModel model) {
        return new CompositeDataSourceConnector(model, baseDir,
                DriverProvisioner.create(false, NO_NETWORK),
                ConnectorProvisioner.create(false, NO_NETWORK),
                new GeneratorRegistry(), new ConnectionPool(),
                List.of(new FakeRegisteredConnector()),
                FileResolver.create(baseDir.resolve("cache"), false));
    }

    /** A script declaring one connection of {@code type} and one table-backed source on it. */
    private static SemanticModel modelWithConnection(String type) {
        return model("connection c from " + type + " { uri: \"u\", database: \"d\" };\n"
                + "source Docs from c { table: \"things\", schema: { id: STRING } };\n"
                + "query Docs;");
    }

    @Nested
    @DisplayName("a connection-backed source")
    final class ConnectionTable {

        @Test
        @DisplayName("routes to the registry's connector for its type")
        void routesToTheRegisteredConnector() {
            SemanticModel model = modelWithConnection(FakeRegisteredConnector.TYPE);

            try (var connector = connectorFor(model);
                 var rows = connector.open("Docs", SCHEMA)) {
                assertThat(rows.toList()).singleElement()
                        .satisfies(row -> assertThat(row.get("id").asDisplayString())
                                .isEqualTo("opened:things"));
            }

            assertThat(FakeRegisteredConnector.lastOpen).isNotNull();
            assertThat(FakeRegisteredConnector.lastOpen.argument())
                    .as("the connector is given the declared table, not the relation name")
                    .isEqualTo("things");
            assertThat(FakeRegisteredConnector.lastOpen.schema()).isEqualTo(SCHEMA);
        }

        @Test
        @DisplayName("is handed the connection's own properties as its config")
        void passesTheConnectionProperties() {
            SemanticModel model = modelWithConnection(FakeRegisteredConnector.TYPE);

            try (var connector = connectorFor(model);
                 var rows = connector.open("Docs", SCHEMA)) {
                rows.forEach(row -> { });
            }

            // Everything the connector needs to reach the store — a URI, a database — is
            // declared on the connection and nowhere else, so a config that lost them
            // would leave the plugin unable to connect at all.
            assertThat(FakeRegisteredConnector.lastOpen.config().require("uri")).isEqualTo("u");
            assertThat(FakeRegisteredConnector.lastOpen.config().require("database")).isEqualTo("d");
        }

        @Test
        @DisplayName("names the connection and the relation when no connector claims the type")
        void unknownTypeIsDiagnosed() {
            SemanticModel model = modelWithConnection("nosuchstore");

            try (var connector = connectorFor(model)) {
                assertThatThrownBy(() -> connector.open("Docs", SCHEMA))
                        .isInstanceOf(EvaluationException.class)
                        .hasMessageContaining("nosuchstore")
                        .hasMessageContaining("Docs");
            }
        }
    }

    @Nested
    @DisplayName("a pushed native query")
    final class PushedQuery {

        @Test
        @DisplayName("reaches the same connector's openQuery, with the query verbatim")
        void routesToTheRegisteredConnector() {
            SemanticModel model = modelWithConnection(FakeRegisteredConnector.TYPE);
            String nativeQuery = "{\"collection\": \"things\", \"pipeline\": []}";

            try (var connector = connectorFor(model);
                 var rows = connector.openQuery(FakeRegisteredConnector.TYPE, "c", nativeQuery, SCHEMA)) {
                assertThat(rows.toList()).singleElement()
                        .satisfies(row -> assertThat(row.get("id").asDisplayString())
                                .startsWith("queried:"));
            }

            assertThat(FakeRegisteredConnector.lastQuery).isNotNull();
            assertThat(FakeRegisteredConnector.lastQuery.argument())
                    .as("the planner's native query must arrive unaltered — the connector "
                        + "is the only thing that knows how to read it")
                    .isEqualTo(nativeQuery);
            assertThat(FakeRegisteredConnector.lastQuery.config().require("database")).isEqualTo("d");
        }

        @Test
        @DisplayName("is refused when the connection it names was never declared")
        void unknownConnectionIsDiagnosed() {
            SemanticModel model = modelWithConnection(FakeRegisteredConnector.TYPE);

            try (var connector = connectorFor(model)) {
                assertThatThrownBy(() -> connector.openQuery(
                        FakeRegisteredConnector.TYPE, "missing", "{}", SCHEMA))
                        .isInstanceOf(EvaluationException.class)
                        .hasMessageContaining("missing");
            }
        }
    }

    @Nested
    @DisplayName("the arms that do not go through the registry")
    final class OtherSources {

        @Test
        @DisplayName("a database source without a connection is refused, not routed")
        void databaseSourceIsRefused() {
            SemanticModel model = model(
                    "source T from database { url: \"jdbc:h2:mem:x\", table: \"t\", "
                    + "schema: { id: STRING } };\nquery T;");

            try (var connector = connectorFor(model)) {
                assertThatThrownBy(() -> connector.open("T", SCHEMA))
                        .isInstanceOf(EvaluationException.class)
                        .hasMessageContaining("database");
            }
        }

        @Test
        @DisplayName("an undeclared relation says so rather than failing later")
        void undeclaredRelationIsDiagnosed() {
            SemanticModel model = modelWithConnection(FakeRegisteredConnector.TYPE);

            try (var connector = connectorFor(model)) {
                assertThatThrownBy(() -> connector.open("Absent", SCHEMA))
                        .isInstanceOf(EvaluationException.class)
                        .hasMessageContaining("Absent");
            }
        }
    }
}

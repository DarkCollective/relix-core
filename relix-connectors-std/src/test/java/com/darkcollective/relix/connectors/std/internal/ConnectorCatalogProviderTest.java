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
package com.darkcollective.relix.connectors.std.internal;

import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.ScriptBuilders;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.internal.ConnectorRegistry;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connector-backed catalog, which is what makes {@code RelixConnector.tableSchema}
 * reachable at all.
 *
 * <p>The SPI has declared schema introspection since it shipped and nothing called it, so
 * a dotted reference resolved its columns for a {@code jdbc} connection and for no other
 * type. These tests are about the dispatch and the fallback rather than about any one
 * connector: what a connector describes it owns, what it declines the delegate answers,
 * and a connection whose type nothing handles is the delegate's alone.
 */
@DisplayName("ConnectorCatalogProvider — a connection's own connector describes its tables")
final class ConnectorCatalogProviderTest {

    private static final Schema OWN = new Schema(List.of(
            new ColumnDefinition("a", ScalarType.NUMBER)));

    private static final Schema DELEGATED = new Schema(List.of(
            new ColumnDefinition("z", ScalarType.STRING)));

    /** A connector that describes one table and declines every other. */
    private static final class Describing implements RelixConnector {
        private boolean closed;

        @Override
        public Set<String> handles() {
            return Set.of("describing");
        }

        @Override
        public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
            return Stream.of();
        }

        @Override
        public Optional<Schema> tableSchema(ConnectorConfig config, String table) {
            return "known".equals(table) ? Optional.of(OWN) : Optional.empty();
        }

        @Override
        public Optional<RelationStatistics> tableStatistics(ConnectorConfig config, String table) {
            return "known".equals(table)
                    ? Optional.of(new RelationStatistics(OptionalLong.of(42L), Map.of(), List.of()))
                    : Optional.empty();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A delegate that answers everything, so a fall-through is visible. */
    private static final CatalogProvider DELEGATE = new CatalogProvider() {
        @Override
        public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
            return Optional.of(DELEGATED);
        }

        @Override
        public Optional<RelationStatistics> tableStatistics(ConnectionDeclaration connection,
                                                            String table) {
            return Optional.of(new RelationStatistics(OptionalLong.of(7L), Map.of(), List.of()));
        }
    };

    private static ConnectionDeclaration connection(String type) {
        return ScriptBuilders.connection("c", type, Map.of("path", "x"));
    }

    private static ConnectorCatalogProvider provider(Describing connector) {
        return new ConnectorCatalogProvider(
                ConnectorRegistry.createWith(List.of(connector)), DELEGATE);
    }

    @Test
    @DisplayName("what the connector describes, the connector owns")
    void connectorWins() {
        try (ConnectorCatalogProvider p = provider(new Describing())) {
            assertThat(p.tableSchema(connection("describing"), "known")).contains(OWN);
        }
    }

    @Test
    @DisplayName("what the connector declines falls through to the delegate")
    void declineFallsThrough() {
        // The fallback is what keeps JDBC's introspection — which maps SQL types and reads
        // statistics — rather than replacing it with a lookup that knows less.
        try (ConnectorCatalogProvider p = provider(new Describing())) {
            assertThat(p.tableSchema(connection("describing"), "other")).contains(DELEGATED);
        }
    }

    @Test
    @DisplayName("a connection whose type nothing handles is the delegate's alone")
    void unknownTypeFallsThrough() {
        try (ConnectorCatalogProvider p = provider(new Describing())) {
            assertThat(p.tableSchema(connection("nothing-handles-this"), "known"))
                    .contains(DELEGATED);
        }
    }

    @Test
    @DisplayName("statistics dispatch the same way")
    void statistics() {
        try (ConnectorCatalogProvider p = provider(new Describing())) {
            assertThat(p.tableStatistics(connection("describing"), "known"))
                    .get().extracting(RelationStatistics::rowCount).isEqualTo(OptionalLong.of(42L));
            assertThat(p.tableStatistics(connection("describing"), "other"))
                    .get().extracting(RelationStatistics::rowCount).isEqualTo(OptionalLong.of(7L));
        }
    }

    @Test
    @DisplayName("the tables a connection holds are the delegate's answer")
    void tablesAreTheDelegates() {
        CatalogProvider listing = new CatalogProvider() {
            @Override
            public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
                return Optional.empty();
            }

            @Override
            public Optional<List<String>> tables(ConnectionDeclaration connection) {
                return Optional.of(List.of("orders"));
            }
        };
        try (ConnectorCatalogProvider p = new ConnectorCatalogProvider(
                ConnectorRegistry.createWith(List.of(new Describing())), listing)) {
            assertThat(p.tables(connection("describing"))).contains(List.of("orders"));
        }
    }

    @Test
    @DisplayName("a null connection is the delegate's, not a NullPointerException")
    void nullConnection() {
        try (ConnectorCatalogProvider p = provider(new Describing())) {
            assertThat(p.tableSchema(null, "known")).contains(DELEGATED);
        }
    }

    @Test
    @DisplayName("closing the provider does not close a connector it was handed")
    void closeLeavesAnInjectedConnectorAlone() {
        // The registry is held for the provider's life rather than built per call, so
        // closing the provider closes the registry — which closes what it *discovered* and
        // not what it was given, because a handed-in connector outlives any one registry.
        // That is the same ownership rule a bound DataSource follows, and this provider
        // inherits it rather than restating it.
        Describing connector = new Describing();
        try (ConnectorCatalogProvider p = new ConnectorCatalogProvider(
                ConnectorRegistry.createWith(List.of(connector)), DELEGATE)) {
            assertThat(p.tableSchema(connection("describing"), "known")).contains(OWN);
        }
        assertThat(connector.closed)
                .as("an injected connector is the caller's, so the registry leaves it open")
                .isFalse();
    }

    @Test
    @DisplayName("the GEDCOM connector describes both of its tables and nothing else")
    void gedcomDescribesItself() {
        GedcomConnector gedcom = new GedcomConnector();

        assertThat(gedcom.tableSchema(ConnectorConfig.empty(), "individuals"))
                .get().extracting(s -> s.columns().stream().map(ColumnDefinition::name).toList())
                .isEqualTo(List.of("id", "name", "surname", "sex", "birth", "death"));
        assertThat(gedcom.tableSchema(ConnectorConfig.empty(), "families"))
                .get().extracting(s -> s.columns().stream().map(ColumnDefinition::name).toList())
                .isEqualTo(List.of("id", "husband", "wife", "children"));
        assertThat(gedcom.tableSchema(ConnectorConfig.empty(), "events")).isEmpty();
    }

    @Test
    @DisplayName("it describes its tables without opening the file, so analysis works offline")
    void describesWithoutReadingTheFile() {
        // The shape is the format's, not the file's — so an absent file degrades the way an
        // unreachable database does, and a script still analyses.
        assertThat(new GedcomConnector().tableSchema(
                new ConnectorConfig(Map.of("path", "/no/such/file.ged")), "individuals"))
                .isPresent();
    }
}

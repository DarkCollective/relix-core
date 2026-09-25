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
package com.darkcollective.relix.processor.connector.internal;

import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link ConnectorRegistry} dispatch, conflict resolution, and discovery. */
final class ConnectorRegistryTest {

    /** Configurable fake connector that records whether it was closed. */
    private static final class FakeConnector implements RelixConnector {
        private final Set<String> handles;
        boolean closed;
        FakeConnector(String... handles) { this.handles = Set.of(handles); }
        @Override public Set<String> handles() { return handles; }
        @Override public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
            return Stream.empty();
        }
        @Override public void close() { closed = true; }
    }

    @Test
    void dispatchesByTokenCaseInsensitively() {
        FakeConnector jdbc = new FakeConnector("jdbc");
        ConnectorRegistry registry = new ConnectorRegistry(List.of(jdbc));

        assertThat(registry.forType("jdbc")).contains(jdbc);
        assertThat(registry.forType("JDBC")).contains(jdbc);
        assertThat(registry.forType("Jdbc")).contains(jdbc);
    }

    @Test
    void unknownTokenResolvesToEmpty() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(new FakeConnector("jdbc")));
        assertThat(registry.forType("mongodb")).isEmpty();
    }

    @Test
    void exposesAllHandledTokensLowerCased() {
        ConnectorRegistry registry = new ConnectorRegistry(
                List.of(new FakeConnector("JDBC", "postgres"), new FakeConnector("http")));
        assertThat(registry.types()).containsExactlyInAnyOrder("jdbc", "postgres", "http");
    }

    @Test
    void firstRegisteredWinsOnTokenClash() {
        FakeConnector first = new FakeConnector("dup");
        FakeConnector second = new FakeConnector("dup");
        ConnectorRegistry registry = new ConnectorRegistry(List.of(first, second));

        assertThat(registry.forType("dup")).contains(first);
    }

    @Test
    void closeClosesEveryConnector() {
        FakeConnector a = new FakeConnector("a");
        FakeConnector b = new FakeConnector("b");
        try (ConnectorRegistry registry = new ConnectorRegistry(List.of(a, b))) {
            assertThat(registry.types()).containsExactlyInAnyOrder("a", "b");
        }
        assertThat(a.closed).isTrue();
        assertThat(b.closed).isTrue();
    }

    /**
     * The engine ships no connector of its own (ADR-0026 D8), and this module's test
     * classpath carries no provider — so discovery over an empty plugin directory
     * finds nothing. That an installed provider <em>is</em> found is asserted where a
     * provider exists to find: {@code CsvConnectorDiscoveryTest} in relix-connectors-std.
     */
    @Test
    void createFindsNothingWithNoProviderInstalled(@TempDir Path emptyPluginDir) {
        try (ConnectorRegistry registry = ConnectorRegistry.create(emptyPluginDir)) {
            assertThat(registry.types()).isEmpty();
        }
    }

    @Test
    void createWithDispatchesToACallerSuppliedConnector(@TempDir Path emptyPluginDir) {
        FakeConnector supplied = new FakeConnector("fixture");
        try (ConnectorRegistry registry =
                     ConnectorRegistry.createWith(List.of(supplied), emptyPluginDir)) {
            assertThat(registry.forType("fixture")).contains(supplied);
        }
    }

    /**
     * A supplied connector is the caller's: the registry is built per query while such a
     * connector outlives every one of them, so closing it here would leave the second
     * query nothing to read through.
     */
    @Test
    void closeLeavesACallerSuppliedConnectorOpen(@TempDir Path emptyPluginDir) {
        FakeConnector supplied = new FakeConnector("fixture");
        try (ConnectorRegistry registry =
                     ConnectorRegistry.createWith(List.of(supplied), emptyPluginDir)) {
            assertThat(registry.types()).containsExactly("fixture");
        }
        assertThat(supplied.closed).isFalse();
    }
}

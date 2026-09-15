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
package com.darkcollective.relix.connectors.std;

import com.darkcollective.relix.processor.connector.ConnectorRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider half of the discovery claim (ADR-0026 D8/D9): the engine registers no
 * connector, so the CSV connector is found only because this module declares it as a
 * {@code RelixConnector} service — and it must be found on <em>both</em> paths.
 *
 * <p>The declaration is made twice, in {@code module-info} and in
 * {@code META-INF/services}, because a module path reads the first and Gradle's
 * classpath {@code test} reads the second. This test runs on the classpath, so it is
 * the guard on the second; {@code :relix-cli:distSmokeTest} and {@code jlinkSmokeTest}
 * cover the first. Losing either is silent — every source stops resolving at once,
 * with nothing a compiler can see.
 */
@DisplayName("ADR-0026 — the standard connectors are discovered as a service")
final class CsvConnectorDiscoveryTest {

    @Test
    @DisplayName("ConnectorRegistry.create finds the CSV connector with no plugin JARs present")
    void discoversTheCsvConnector(@TempDir Path emptyPluginDir) {
        try (ConnectorRegistry registry = ConnectorRegistry.create(emptyPluginDir)) {
            assertThat(registry.forType("csv")).get().isInstanceOf(CsvConnector.class);
        }
    }
}

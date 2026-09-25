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

import com.darkcollective.relix.connectors.std.DriverProvisioner;
import com.darkcollective.relix.processor.connector.internal.ConnectorPluginLoader;
import com.darkcollective.relix.processor.connector.ConnectorProvisioner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published members a front end uses besides a query's rows: the engine's version,
 * the tree as JSON, and where connectors and drivers are installed.
 */
@DisplayName("what a front end reads besides rows")
final class FrontEndSupportTest {

    @Test
    @DisplayName("the version is the one relix.version reports for the engine")
    void version() {
        try (Relix session = Relix.open()) {
            String reported = session.relation("π version (σ kind = \"engine\" (relix.version))")
                    .toList().getFirst().string("version");
            assertThat(Relix.version()).isEqualTo(reported).isNotBlank();
        }
    }

    @Test
    @DisplayName("renderJson is the tree as written, with each node's inferred heading")
    void renderJson() {
        try (Relix session = Relix.open()) {
            session.define("Orders := [| id | amount |\n| 1 | 120 |];");
            String json = session.relation("σ amount > 100 (Orders)").renderJson();
            assertThat(json)
                    .startsWith("{\"op\":\"Selection\"")
                    .contains("\"amount\"");
        }
    }

    @Test
    @DisplayName("the installed connectors include the shipped ones")
    void installedConnectors() {
        assertThat(ConnectorProvisioner.installedTypes()).contains("csv");
        assertThat(ConnectorProvisioner.defaultDirectory())
                .isEqualTo(ConnectorPluginLoader.defaultDirectory());
    }

    @Test
    @DisplayName("loading installed drivers answers with what it registered")
    void installedDrivers() {
        assertThat(DriverProvisioner.loadInstalled()).isNotNull();
    }
}

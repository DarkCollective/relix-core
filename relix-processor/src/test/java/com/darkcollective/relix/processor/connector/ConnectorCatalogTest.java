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
package com.darkcollective.relix.processor.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Unit tests for {@link ConnectorCatalog} parsing and type resolution. */
final class ConnectorCatalogTest {

    private static final String MANIFEST = """
            [
              { "type": "mongodb",
                "artifacts": [
                  { "url": "https://x/relix-mongo-connector.jar", "sha256": "aaa" },
                  { "url": "https://x/mongodb-driver-sync.jar",   "sha256": "bbb" }
                ] }
            ]
            """;

    @Test
    void parsesEntryWithMultipleArtifacts() {
        ConnectorCatalog catalog = ConnectorCatalog.parse(MANIFEST);
        assertThat(catalog.entries()).hasSize(1);
        ConnectorCatalog.Entry mongo = catalog.entries().get(0);
        assertThat(mongo.type()).isEqualTo("mongodb");
        assertThat(mongo.artifacts()).hasSize(2);
        assertThat(mongo.artifacts().get(0).fileName()).isEqualTo("relix-mongo-connector.jar");
    }

    @Test
    void forTypeMatchesCaseInsensitively() {
        ConnectorCatalog catalog = ConnectorCatalog.parse(MANIFEST);
        assertThat(catalog.forType("MongoDB")).isPresent();
        assertThat(catalog.forType("mongodb")).isPresent();
    }

    @Test
    void forTypeEmptyWhenNoEntry() {
        assertThat(ConnectorCatalog.parse(MANIFEST).forType("redis")).isEmpty();
    }

    @Test
    void bundledDefaultIsEmpty() {
        // No connector plugins are published yet; the bundled manifest is an empty array.
        assertThat(ConnectorCatalog.bundledDefault().entries()).isEmpty();
    }

    @Test
    void rejectsNonArrayManifest() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ConnectorCatalog.parse("{ \"type\": \"x\" }"))
                .withMessageContaining("array");
    }

    @Test
    void rejectsEntryMissingArtifacts() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ConnectorCatalog.parse("[ { \"type\": \"mongodb\" } ]"))
                .withMessageContaining("artifacts");
    }
}

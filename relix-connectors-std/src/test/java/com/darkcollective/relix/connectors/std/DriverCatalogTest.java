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

import com.darkcollective.relix.processor.connector.Artifact;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Unit tests for {@link DriverCatalog} parsing and URL resolution. */
final class DriverCatalogTest {

    private static final String MANIFEST = """
            [
              { "name": "PostgreSQL", "scheme": "jdbc:postgresql:",
                "artifacts": [ { "url": "https://x/postgresql-42.7.4.jar", "sha256": "abc" } ] },
              { "name": "PostgreSQL-SSL", "scheme": "jdbc:postgresql:ssl:",
                "artifacts": [ { "url": "https://x/pg-ssl.jar", "sha256": "def" } ] }
            ]
            """;

    @Test
    void parsesEntriesAndArtifacts() {
        DriverCatalog catalog = DriverCatalog.parse(MANIFEST);
        assertThat(catalog.entries()).hasSize(2);
        DriverCatalog.Entry pg = catalog.entries().get(0);
        assertThat(pg.name()).isEqualTo("PostgreSQL");
        assertThat(pg.scheme()).isEqualTo("jdbc:postgresql:");
        assertThat(pg.artifacts()).singleElement()
                .satisfies(a -> assertThat(a.sha256()).isEqualTo("abc"));
    }

    @Test
    void forUrlMatchesBySchemePrefixCaseInsensitively() {
        DriverCatalog catalog = DriverCatalog.parse(MANIFEST);
        assertThat(catalog.forUrl("JDBC:POSTGRESQL://host:5432/db")).get()
                .extracting(DriverCatalog.Entry::name).isEqualTo("PostgreSQL");
    }

    @Test
    void forUrlPrefersTheLongestMatchingScheme() {
        DriverCatalog catalog = DriverCatalog.parse(MANIFEST);
        assertThat(catalog.forUrl("jdbc:postgresql:ssl://host/db")).get()
                .extracting(DriverCatalog.Entry::name).isEqualTo("PostgreSQL-SSL");
    }

    @Test
    void forUrlEmptyWhenNoSchemeMatches() {
        DriverCatalog catalog = DriverCatalog.parse(MANIFEST);
        assertThat(catalog.forUrl("jdbc:oracle:thin:@host")).isEmpty();
    }

    @Test
    void artifactFileNameDerivedFromUrl() {
        var artifact = new Artifact(
                "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar", "h");
        assertThat(artifact.fileName()).isEqualTo("postgresql-42.7.4.jar");
    }

    @Test
    void artifactFileNameStripsQuery() {
        var artifact = new Artifact("https://x/driver.jar?token=abc", "h");
        assertThat(artifact.fileName()).isEqualTo("driver.jar");
    }

    @Test
    void bundledDefaultContainsPostgresAndMysql() {
        DriverCatalog catalog = DriverCatalog.bundledDefault();
        assertThat(catalog.forUrl("jdbc:postgresql://host/db")).isPresent();
        assertThat(catalog.forUrl("jdbc:mysql://host/db")).isPresent();
    }

    @Test
    void rejectsNonArrayManifest() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> DriverCatalog.parse("{ \"name\": \"x\" }"))
                .withMessageContaining("array");
    }

    @Test
    void rejectsEntryMissingArtifacts() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> DriverCatalog.parse("[ { \"name\": \"x\", \"scheme\": \"jdbc:x:\" } ]"))
                .withMessageContaining("artifacts");
    }

    @Test
    void rejectsArtifactMissingFields() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> DriverCatalog.parse(
                        "[ { \"name\": \"x\", \"scheme\": \"jdbc:x:\", \"artifacts\": [ { \"url\": \"u\" } ] } ]"))
                .withMessageContaining("sha256");
    }
}

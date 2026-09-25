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

import com.darkcollective.relix.processor.connector.internal.ConnectorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ConnectorProvisioner} — the on-demand connector-plugin
 * provisioning outcomes, including the full download → install → discover path using
 * a fabricated {@link FakeConnectorPlugin} JAR (no network).
 */
final class ConnectorProvisionerTest {

    private static final Fetcher THROWING = uri -> { throw new IOException("must not fetch"); };

    private static ConnectorCatalog catalog(String type, Artifact... artifacts) {
        return new ConnectorCatalog(List.of(new ConnectorCatalog.Entry(type, List.of(artifacts))));
    }

    @Test
    void unknownWhenNoCatalogEntry(@TempDir Path dir) {
        var provisioner = new ConnectorProvisioner(new ConnectorCatalog(List.of()), dir, THROWING, true);
        assertThat(provisioner.provision("mongodb").outcome())
                .isEqualTo(ConnectorProvisioner.Outcome.UNKNOWN);
    }

    @Test
    void disabledWhenDownloadsOff(@TempDir Path dir) {
        var catalog = catalog("mongodb", new Artifact("https://x/m.jar", "h"));
        var provisioner = new ConnectorProvisioner(catalog, dir, THROWING, false);

        ConnectorProvisioner.Result result = provisioner.provision("mongodb");

        assertThat(result.outcome()).isEqualTo(ConnectorProvisioner.Outcome.DISABLED);
        assertThat(result.message()).contains("--download-connectors");
    }

    @Test
    void failedWhenDownloadErrors(@TempDir Path dir) {
        var catalog = catalog("mongodb", new Artifact("https://x/m.jar", "h"));
        Fetcher boom = uri -> { throw new IOException("network down"); };
        var provisioner = new ConnectorProvisioner(catalog, dir, boom, true);

        ConnectorProvisioner.Result result = provisioner.provision("mongodb");

        assertThat(result.outcome()).isEqualTo(ConnectorProvisioner.Outcome.FAILED);
        assertThat(result.message()).contains("network down");
    }

    @Test
    void provisionedDownloadsPluginThatBecomesDiscoverable(@TempDir Path dir) throws Exception {
        byte[] jarBytes = fakePluginJarBytes();
        var catalog = catalog("fake", new Artifact("https://example.test/fake-connector.jar", sha256(jarBytes)));
        var provisioner = new ConnectorProvisioner(catalog, dir, uri -> jarBytes, true);

        ConnectorProvisioner.Result result = provisioner.provision("fake");

        assertThat(result.outcome()).isEqualTo(ConnectorProvisioner.Outcome.PROVISIONED);
        // The downloaded plugin is now discoverable by a fresh registry over the dir.
        assertThat(ConnectorRegistry.create(dir).forType("fake")).isPresent();
    }

    // ─── hosted / lazy catalog resolution ────────────────────────────────────

    @Test
    void resolveCatalogFetchesHttpsUrl() {
        String manifest = "[{\"type\":\"mongodb\",\"artifacts\":[{\"url\":\"https://x/m.jar\",\"sha256\":\"h\"}]}]";
        Fetcher fake = uri -> manifest.getBytes(StandardCharsets.UTF_8);

        ConnectorCatalog catalog = ConnectorProvisioner.resolveCatalog("https://example.test/c.json", fake);

        assertThat(catalog.forType("mongodb")).isPresent();
    }

    @Test
    void resolveCatalogReadsLocalFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("cat.json");
        java.nio.file.Files.writeString(file,
                "[{\"type\":\"redis\",\"artifacts\":[{\"url\":\"https://x/r.jar\",\"sha256\":\"h\"}]}]");

        ConnectorCatalog catalog = ConnectorProvisioner.resolveCatalog(file.toString(), THROWING);

        assertThat(catalog.forType("redis")).isPresent();
    }

    @Test
    void resolveCatalogFallsBackToEmptyOnFetchFailure() {
        // An offline/failed fetch must degrade to the empty bundled catalog, not throw.
        ConnectorCatalog catalog = ConnectorProvisioner.resolveCatalog("https://example.test/c.json", THROWING);
        assertThat(catalog.entries()).isEmpty();
    }

    @Test
    void resolveCatalogFallsBackToEmptyForMissingFile(@TempDir Path dir) {
        ConnectorCatalog catalog =
                ConnectorProvisioner.resolveCatalog(dir.resolve("nope.json").toString(), THROWING);
        assertThat(catalog.entries()).isEmpty();
    }

    @Test
    void catalogIsNotLoadedWhenDownloadsDisabled(@TempDir Path dir) {
        java.util.function.Supplier<ConnectorCatalog> exploding = () -> {
            throw new AssertionError("catalog must not be loaded when downloads are disabled");
        };
        var provisioner = new ConnectorProvisioner(exploding, dir, THROWING, false);

        assertThat(provisioner.provision("mongodb").outcome())
                .isEqualTo(ConnectorProvisioner.Outcome.DISABLED);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static byte[] fakePluginJarBytes() throws IOException {
        String classResource = FakeConnectorPlugin.class.getName().replace('.', '/') + ".class";
        String serviceResource = "META-INF/services/" + RelixConnector.class.getName();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry(classResource));
            jar.write(resourceBytes(classResource));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(serviceResource));
            jar.write((FakeConnectorPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] resourceBytes(String resource) throws IOException {
        try (InputStream in = ConnectorProvisionerTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("cannot locate compiled " + resource);
            }
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

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
import com.darkcollective.relix.processor.connector.Fetcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DriverProvisioner} — the on-demand driver provisioning
 * outcomes, including the full download → load → register → resolve path using a
 * fabricated {@link FakeJdbcDriver} JAR (no network).
 */
final class DriverProvisionerTest {

    private static final Fetcher THROWING = uri -> {
        throw new IOException("must not fetch");
    };

    @Test
    void alreadyAvailableWhenDriverRegistered(@TempDir Path driverDir) {
        // H2 is on the test runtime, so a jdbc:h2 driver is already registered.
        var provisioner = new DriverProvisioner(
                new DriverCatalog(List.of()), driverDir, THROWING, true);

        DriverProvisioner.Result result = provisioner.provision("jdbc:h2:mem:test");

        assertThat(result.outcome()).isEqualTo(DriverProvisioner.Outcome.ALREADY_AVAILABLE);
    }

    @Test
    void disabledWhenDownloadsOff(@TempDir Path driverDir) {
        var catalog = new DriverCatalog(List.of(new DriverCatalog.Entry(
                "Disabled", "jdbc:disabledtest:",
                List.of(new Artifact("https://x/d.jar", "h")))));
        var provisioner = new DriverProvisioner(catalog, driverDir, THROWING, false);

        DriverProvisioner.Result result = provisioner.provision("jdbc:disabledtest://host/db");

        assertThat(result.outcome()).isEqualTo(DriverProvisioner.Outcome.DISABLED);
        assertThat(result.message()).contains("--download-drivers");
    }

    @Test
    void unknownWhenNoCatalogEntry(@TempDir Path driverDir) {
        var provisioner = new DriverProvisioner(
                new DriverCatalog(List.of()), driverDir, THROWING, true);

        DriverProvisioner.Result result = provisioner.provision("jdbc:unknowntest://host/db");

        assertThat(result.outcome()).isEqualTo(DriverProvisioner.Outcome.UNKNOWN);
    }

    @Test
    void failedWhenDownloadErrors(@TempDir Path driverDir) {
        var catalog = new DriverCatalog(List.of(new DriverCatalog.Entry(
                "Fail", "jdbc:failtest:",
                List.of(new Artifact("https://x/f.jar", "h")))));
        Fetcher boom = uri -> { throw new IOException("network down"); };
        var provisioner = new DriverProvisioner(catalog, driverDir, boom, true);

        DriverProvisioner.Result result = provisioner.provision("jdbc:failtest://host/db");

        assertThat(result.outcome()).isEqualTo(DriverProvisioner.Outcome.FAILED);
        assertThat(result.message()).contains("network down");
    }

    @Test
    void provisionedDownloadsAndRegistersDriver(@TempDir Path driverDir) throws Exception {
        byte[] jarBytes = fakeDriverJarBytes();
        String sha = sha256(jarBytes);
        var catalog = new DriverCatalog(List.of(new DriverCatalog.Entry(
                "Faketest", "jdbc:faketest:",
                List.of(new Artifact("https://example.test/fake-jdbc.jar", sha)))));
        var provisioner = new DriverProvisioner(catalog, driverDir, uri -> jarBytes, true);

        DriverProvisioner.Result result = provisioner.provision("jdbc:faketest://host/db");

        assertThat(result.outcome()).isEqualTo(DriverProvisioner.Outcome.PROVISIONED);
        assertThat(driverRegisteredFor("jdbc:faketest://host/db")).isTrue();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static boolean driverRegisteredFor(String url) {
        try {
            return DriverManager.getDriver(url) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Packages {@link FakeJdbcDriver}'s class + a java.sql.Driver service entry into JAR bytes. */
    private static byte[] fakeDriverJarBytes() throws IOException {
        String classResource = FakeJdbcDriver.class.getName().replace('.', '/') + ".class";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry(classResource));
            jar.write(resourceBytes(classResource));
            jar.closeEntry();

            jar.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
            jar.write((FakeJdbcDriver.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] resourceBytes(String resource) throws IOException {
        try (InputStream in = DriverProvisionerTest.class.getClassLoader().getResourceAsStream(resource)) {
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

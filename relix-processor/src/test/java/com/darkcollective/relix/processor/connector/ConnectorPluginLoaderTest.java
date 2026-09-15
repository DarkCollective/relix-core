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
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ConnectorPluginLoader} — directory resolution, scan/skip
 * behaviour, and real external-plugin discovery from a JAR.
 *
 * <p>The fixture JAR packages the compiled {@link FakeConnectorPlugin} class with
 * a {@code META-INF/services} entry, then loads it through the loader.
 */
final class ConnectorPluginLoaderTest {

    private static final String PLUGIN_CLASS = FakeConnectorPlugin.class.getName();
    private static final String PLUGIN_RESOURCE = PLUGIN_CLASS.replace('.', '/') + ".class";
    private static final String SERVICE_RESOURCE = "META-INF/services/" + RelixConnector.class.getName();

    // ─── directory resolution ────────────────────────────────────────────────

    @Test
    void resolveDirectoryUsesOverrideWhenSet() {
        assertThat(ConnectorPluginLoader.resolveDirectory("/opt/relix/connectors"))
                .isEqualTo(Path.of("/opt/relix/connectors"));
    }

    @Test
    void resolveDirectoryTrimsOverride() {
        assertThat(ConnectorPluginLoader.resolveDirectory("  /opt/c  "))
                .isEqualTo(Path.of("/opt/c"));
    }

    @Test
    void resolveDirectoryFallsBackWhenNull() {
        assertThat(ConnectorPluginLoader.resolveDirectory(null))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".relix", "connectors"));
    }

    @Test
    void resolveDirectoryFallsBackWhenBlank() {
        assertThat(ConnectorPluginLoader.resolveDirectory("  "))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".relix", "connectors"));
    }

    // ─── scan / skip behaviour ───────────────────────────────────────────────

    @Test
    void missingDirectoryYieldsNoConnectors(@TempDir Path tmp) {
        assertThat(new ConnectorPluginLoader(tmp.resolve("absent")).load()).isEmpty();
    }

    @Test
    void emptyDirectoryYieldsNoConnectors(@TempDir Path tmp) {
        assertThat(new ConnectorPluginLoader(tmp).load()).isEmpty();
    }

    @Test
    void nonJarFilesAreIgnored(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("plugin.txt"), "not a jar");
        assertThat(new ConnectorPluginLoader(tmp).load()).isEmpty();
    }

    // ─── end-to-end external load ────────────────────────────────────────────

    @Test
    void loadsConnectorFromExternalJar(@TempDir Path tmp) throws Exception {
        buildPluginJar(tmp.resolve("fake-connector.jar"));
        Files.writeString(tmp.resolve("ignore.txt"), "x");   // stray non-jar

        List<RelixConnector> connectors = new ConnectorPluginLoader(tmp).load();

        assertThat(connectors).anyMatch(c -> c.handles().contains("fake"));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** Packages {@link FakeConnectorPlugin}'s class + a service entry into a JAR. */
    private static void buildPluginJar(Path jar) throws Exception {
        try (OutputStream fileOut = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(fileOut)) {

            jarOut.putNextEntry(new JarEntry(PLUGIN_RESOURCE));
            jarOut.write(classBytes());
            jarOut.closeEntry();

            jarOut.putNextEntry(new JarEntry(SERVICE_RESOURCE));
            jarOut.write((PLUGIN_CLASS + "\n").getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
    }

    private static byte[] classBytes() throws Exception {
        try (InputStream in = ConnectorPluginLoaderTest.class.getClassLoader()
                .getResourceAsStream(PLUGIN_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("cannot locate compiled " + PLUGIN_RESOURCE);
            }
            return in.readAllBytes();
        }
    }
}

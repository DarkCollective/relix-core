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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A built-in connector that cannot be loaded must fail as itself.
 *
 * <p>{@link ConnectorPluginLoader} has always guarded the connectors it loads from
 * out-of-tree JARs. Applying the guard only there was backwards: a connector shipped in
 * the artifact is at least as entitled to fail on its own, and the two halves of connector
 * discovery disagreeing meant a broken built-in took down the whole registry — and with it
 * every query, since a registry is built per execution.
 */
@DisplayName("Connector discovery — one unloadable built-in must not take down the scan")
final class ConnectorDiscoveryGuardTest {

    private static final String SPI =
            "com.darkcollective.relix.processor.connector.RelixConnector";
    private static final String UNLOADABLE =
            "com.darkcollective.relix.processor.connector.UnloadableConnector";
    private static final String ABSENT =
            "com.darkcollective.relix.processor.connector.NoSuchConnector";

    /**
     * Builds a registry with a synthetic services file on the context class loader, which
     * is where {@code ServiceLoader.load(Class)} resolves built-in providers from.
     */
    private static ConnectorRegistry registryWithServices(Path pluginDirectory,
                                                          String... providers)
            throws IOException {
        Path root = Files.createTempDirectory("relix-connector-discovery");
        Path services = root.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(SPI), String.join(System.lineSeparator(), providers));

        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        try (URLClassLoader loader =
                     new URLClassLoader(new URL[]{root.toUri().toURL()}, previous)) {
            current.setContextClassLoader(loader);
            return ConnectorRegistry.create(pluginDirectory);
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("an unloadable built-in is skipped rather than raising")
    void unloadableBuiltinIsSkipped(@TempDir Path plugins) throws IOException {
        try (ConnectorRegistry registry = registryWithServices(plugins, UNLOADABLE, ABSENT)) {
            assertThat(registry.forType("unloadable")).isEmpty();
        }
    }

    @Test
    @DisplayName("the connectors supplied by the caller survive a broken built-in")
    void suppliedConnectorsSurvive(@TempDir Path plugins) throws IOException {
        Path root = Files.createTempDirectory("relix-connector-discovery");
        Path services = root.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(SPI), UNLOADABLE);

        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        try (URLClassLoader loader =
                     new URLClassLoader(new URL[]{root.toUri().toURL()}, previous)) {
            current.setContextClassLoader(loader);
            try (ConnectorRegistry registry =
                         ConnectorRegistry.createWith(List.of(new FakeConnectorPlugin()), plugins)) {
                assertThat(registry.forType("fake")).isPresent();
                assertThat(registry.forType("unloadable")).isEmpty();
            }
        } finally {
            current.setContextClassLoader(previous);
        }
    }
}

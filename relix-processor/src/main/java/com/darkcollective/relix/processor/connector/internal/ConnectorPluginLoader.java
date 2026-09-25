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

import com.darkcollective.relix.processor.connector.RelixConnector;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Loads external {@link RelixConnector} plugins from JAR files in a <em>connector
 * directory</em> — {@code ~/.relix/connectors/} by default, overridable via
 * {@code RELIX_CONNECTORS}.
 *
 * <p>This is the {@link RelixConnector} analogue of {@code RelixDriverLoader}: a
 * connector that does not ship with relix (MongoDB, Redis, …) is dropped in as a
 * JAR and discovered via {@link ServiceLoader} with no rebuild.  Unlike the driver
 * loader, the {@link URLClassLoader}'s parent is this class's loader (not the
 * platform loader), because a plugin must see the {@link RelixConnector} API and
 * the {@code Row}/{@code Schema} types it references.
 *
 * <p>Loaders are retained for the JVM lifetime so the plugins keep working.
 */
public final class ConnectorPluginLoader {

    /** Environment variable overriding the default connector directory. */
    public static final String CONNECTORS_ENV = "RELIX_CONNECTORS";

    /** Keeps the class loaders alive for the JVM lifetime. */
    private static final List<URLClassLoader> RETAINED = new ArrayList<>();

    private final Path directory;

    /**
     * @param directory the directory to scan for connector JARs (need not exist)
     */
    public ConnectorPluginLoader(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /**
     * Resolves the default connector directory: {@code $RELIX_CONNECTORS} if set
     * and non-blank, otherwise {@code ~/.relix/connectors}.
     *
     * @return the resolved directory path (which may not exist yet)
     */
    public static Path defaultDirectory() {
        return resolveDirectory(System.getenv(CONNECTORS_ENV));
    }

    /** Package-private so the override branch is unit-testable without mutating the environment. */
    static Path resolveDirectory(String override) {
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return Path.of(System.getProperty("user.home"), ".relix", "connectors");
    }

    /**
     * Scans the directory and instantiates every {@link RelixConnector} service
     * found in its JARs.  A missing/non-directory path, or one with no connector
     * JARs, yields an empty list; a JAR with no connector service is skipped.
     *
     * @return the loaded connector instances, in JAR-name order
     */
    public List<RelixConnector> load() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> jars = jarsIn(directory);
        if (jars.isEmpty()) {
            return List.of();
        }

        // Parent = this loader so plugins can resolve the RelixConnector API + Row/Schema.
        URLClassLoader loader = new URLClassLoader(toUrls(jars), getClass().getClassLoader());
        retain(loader);

        List<RelixConnector> connectors = new ArrayList<>();
        ServiceLoader<RelixConnector> services = ServiceLoader.load(RelixConnector.class, loader);
        for (var it = services.iterator(); it.hasNext(); ) {
            try {
                connectors.add(it.next());
            } catch (ServiceConfigurationError badService) {
                // A JAR with a malformed or unloadable connector service: skip it.
            }
        }
        return List.copyOf(connectors);
    }

    private static List<Path> jarsIn(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static URL[] toUrls(List<Path> jars) {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            try {
                urls[i] = jars.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("cannot form URL for connector JAR " + jars.get(i), e);
            }
        }
        return urls;
    }

    private static synchronized void retain(URLClassLoader loader) {
        RETAINED.add(loader);
    }
}

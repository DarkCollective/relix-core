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

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Loads JDBC drivers from JAR files in an external <em>driver directory</em>,
 * rather than bundling them into the executable at build time.
 *
 * <p>The directory defaults to {@code ~/.relix/drivers/} and can be overridden
 * with the {@code RELIX_DRIVERS} environment variable.  Every {@code *.jar} in it
 * is loaded into a single {@link URLClassLoader}; {@link java.sql.Driver}
 * implementations are discovered via {@link ServiceLoader} and registered with
 * {@link DriverManager} through a {@link DriverShim} (see that class for why the
 * shim is required).  A JAR that contributes no driver service is silently
 * skipped, so unrelated JARs in the directory do no harm.
 *
 * <p>The loader is deliberately isolating: the {@code URLClassLoader}'s parent is
 * the platform class loader, so {@link ServiceLoader} sees only drivers from the
 * directory's JARs (plus the JDK), never drivers already on the application class
 * path.  The loaders are held for the JVM lifetime — closing them would make the
 * registered drivers unusable — so this is a one-shot, additive operation.
 *
 * <p>This replaces the previous approach of merging PostgreSQL/MySQL driver JARs
 * into the jlink image. Drivers placed here work without rebuilding relix, and
 * commercial drivers whose licences forbid redistribution can be supplied by the
 * user.
 */
public final class RelixDriverLoader {

    /** Environment variable overriding the default driver directory. */
    public static final String DRIVERS_ENV = "RELIX_DRIVERS";

    /** Keeps the class loaders alive for the JVM lifetime (drivers need them). */
    private static final List<URLClassLoader> RETAINED = new ArrayList<>();

    private final Path directory;

    /**
     * @param directory the directory to scan for driver JARs (need not exist)
     */
    public RelixDriverLoader(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /**
     * Resolves the default driver directory: {@code $RELIX_DRIVERS} if set and
     * non-blank, otherwise {@code ~/.relix/drivers}.
     *
     * @return the resolved directory path (which may not exist yet)
     */
    public static Path defaultDirectory() {
        return resolveDirectory(System.getenv(DRIVERS_ENV));
    }

    /**
     * Resolves the driver directory from a {@code $RELIX_DRIVERS} value: the value
     * itself when set and non-blank, otherwise {@code ~/.relix/drivers}. Package-private
     * so the override branch is unit-testable without mutating the process environment.
     */
    static Path resolveDirectory(String override) {
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return Path.of(System.getProperty("user.home"), ".relix", "drivers");
    }

    /**
     * Scans the directory, loads each JAR, and registers every discovered driver.
     *
     * <p>A missing or non-directory path, or a directory with no driver JARs,
     * yields an empty list. Each returned string describes one registered driver
     * (its implementation class and originating JAR file name), suitable for a
     * startup log line.
     *
     * @return descriptions of the drivers registered, in load order
     */
    public List<String> load() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> jars = jarsIn(directory);
        if (jars.isEmpty()) {
            return List.of();
        }

        URL[] urls = toUrls(jars);
        // Platform class loader as parent → ServiceLoader sees only the directory's
        // drivers, never the application class path's. The loader is retained, not
        // closed, because the registered drivers keep using it.
        URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
        retain(loader);

        List<String> registered = new ArrayList<>();
        ServiceLoader<Driver> services = ServiceLoader.load(Driver.class, loader);
        for (var it = services.iterator(); it.hasNext(); ) {
            Driver driver;
            try {
                driver = it.next();
            } catch (ServiceConfigurationError badService) {
                // A JAR with a malformed or unloadable Driver service: skip it.
                continue;
            }
            try {
                DriverManager.registerDriver(new DriverShim(driver));
            } catch (SQLException registrationFailed) {
                continue;
            }
            registered.add(describe(driver));
        }
        return List.copyOf(registered);
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
            } catch (java.net.MalformedURLException e) {
                throw new IllegalStateException("cannot form URL for driver JAR " + jars.get(i), e);
            }
        }
        return urls;
    }

    private static String describe(Driver driver) {
        String version = driver.getMajorVersion() + "." + driver.getMinorVersion();
        return driver.getClass().getName() + " v" + version;
    }

    private static synchronized void retain(URLClassLoader loader) {
        RETAINED.add(loader);
    }
}

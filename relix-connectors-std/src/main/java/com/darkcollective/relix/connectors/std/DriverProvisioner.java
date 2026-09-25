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

import com.darkcollective.relix.connectors.std.internal.RelixDriverLoader;
import com.darkcollective.relix.processor.connector.internal.DriverDownloader;
import com.darkcollective.relix.processor.connector.Fetcher;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provisions a JDBC driver on demand: when a connection references a database for
 * which no driver is registered, this looks the driver up in the
 * {@link DriverCatalog}, downloads it (with checksum verification) into the driver
 * directory, and registers it — so a database relix has never seen before just
 * works.
 *
 * <p>Downloading is <strong>opt-in</strong>: a provisioner created with
 * {@code enabled == false} never reaches the network and reports {@link Outcome#DISABLED}
 * — network egress is a deliberate user choice (the CLI {@code --download-drivers}
 * flag), not a silent default.
 */
public final class DriverProvisioner {

    /** The result of a provisioning attempt. */
    public enum Outcome {
        /** A driver for the URL was already registered; nothing to do. */
        ALREADY_AVAILABLE,
        /** The driver was downloaded and registered. */
        PROVISIONED,
        /** Downloading is disabled; the driver must be placed manually. */
        DISABLED,
        /** No catalog entry covers this URL's database. */
        UNKNOWN,
        /** Download or registration failed (see the message). */
        FAILED
    }

    /** A provisioning outcome plus a human-readable explanation. */
    public record Result(Outcome outcome, String message) {
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(message, "message");
        }
    }

    private final DriverCatalog catalog;
    private final Path driverDirectory;
    private final Fetcher fetcher;
    private final boolean enabled;

    /**
     * @param catalog         the catalog of downloadable drivers
     * @param driverDirectory the directory drivers are downloaded into and loaded from
     * @param fetcher         the network seam
     * @param enabled         whether downloading is permitted (user consent)
     */
    public DriverProvisioner(DriverCatalog catalog, Path driverDirectory, Fetcher fetcher, boolean enabled) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.driverDirectory = Objects.requireNonNull(driverDirectory, "driverDirectory");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.enabled = enabled;
    }

    /**
     * Builds a provisioner from the active catalog, the default driver directory,
     * and an HTTPS fetcher.
     *
     * @param enabled whether downloading is permitted
     * @return a production provisioner
     */
    public static DriverProvisioner create(boolean enabled) {
        return create(enabled, HttpFetcher.https());
    }

    /**
     * Builds a provisioner using a supplied {@link Fetcher} — e.g. one that reports
     * download progress to the terminal.
     *
     * @param enabled whether downloading is permitted
     * @param fetcher the fetcher used for downloads
     * @return a production provisioner
     */
    public static DriverProvisioner create(boolean enabled, Fetcher fetcher) {
        return new DriverProvisioner(
                DriverCatalog.load(), RelixDriverLoader.defaultDirectory(), fetcher, enabled);
    }

    /**
     * Registers every JDBC driver installed in {@code ~/.relix/drivers}, so a
     * connection can use a driver downloaded by an earlier run.
     *
     * <p>A host calls this once at startup. A missing directory, or one with no driver
     * JARs, registers nothing.
     *
     * @return one description per driver registered (its class and the JAR it came
     *         from), in load order; never null
     * @since 1.0
     */
    public static List<String> loadInstalled() {
        return new RelixDriverLoader(RelixDriverLoader.defaultDirectory()).load();
    }

    /** @return whether this provisioner is permitted to download */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Ensures a driver is available for the given JDBC URL, downloading it if
     * necessary and permitted.
     *
     * @param jdbcUrl the connection URL
     * @return the outcome of the attempt
     */
    public Result provision(String jdbcUrl) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        if (driverRegisteredFor(jdbcUrl)) {
            return new Result(Outcome.ALREADY_AVAILABLE, "driver already registered for " + jdbcUrl);
        }

        Optional<DriverCatalog.Entry> entry = catalog.forUrl(jdbcUrl);
        if (entry.isEmpty()) {
            return new Result(Outcome.UNKNOWN,
                    "no driver catalog entry covers '" + jdbcUrl + "'; place the driver JAR in "
                    + driverDirectory + " manually");
        }
        DriverCatalog.Entry driver = entry.get();

        if (!enabled) {
            return new Result(Outcome.DISABLED,
                    "driver for " + driver.name() + " is not installed; re-run with --download-drivers "
                    + "to fetch it, or place the JAR in " + driverDirectory);
        }

        try {
            List<Path> jars = new DriverDownloader(driverDirectory, fetcher).download(driver.artifacts());
            new RelixDriverLoader(driverDirectory).load();
            if (driverRegisteredFor(jdbcUrl)) {
                return new Result(Outcome.PROVISIONED,
                        "downloaded and registered " + driver.name() + " (" + jars.size() + " JAR(s))");
            }
            return new Result(Outcome.FAILED,
                    "downloaded " + driver.name() + " but no registered driver accepts " + jdbcUrl);
        } catch (IOException e) {
            return new Result(Outcome.FAILED, "failed to download " + driver.name() + ": " + e.getMessage());
        }
    }

    private static boolean driverRegisteredFor(String jdbcUrl) {
        try {
            return DriverManager.getDriver(jdbcUrl) != null;
        } catch (SQLException noDriver) {
            return false;
        }
    }
}

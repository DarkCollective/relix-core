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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Provisions a connector plugin on demand: when a connection names a
 * {@code connectorType} for which no {@link RelixConnector} is registered, this
 * looks the plugin up in the {@link ConnectorCatalog}, downloads its artifacts
 * (the connector JAR + its driver/runtime JARs, each checksum-verified) into the
 * connector directory, and reports the outcome — the caller then rebuilds its
 * {@link ConnectorRegistry} to pick up the new plugin. It is the SPI-side
 * analogue of {@code DriverProvisioner}.
 *
 * <p>The catalog is <strong>hosted and decoupled</strong> from the relix release: it
 * is fetched from {@link #DEFAULT_CATALOG_URL} (overridable via
 * {@code $RELIX_CONNECTOR_CATALOG}, which may be an {@code https://} URL or a local
 * file path) so a connector plugin can be published — and the catalog updated —
 * without rebuilding relix.  The catalog is loaded <em>lazily</em> and only when
 * downloading is actually needed, so no network call happens at startup or when
 * downloads are off.
 *
 * <p>Downloading is <strong>opt-in</strong>: a provisioner created with
 * {@code enabled == false} never reaches the network and reports {@link Outcome#DISABLED}.
 */
public final class ConnectorProvisioner {

    /**
     * The hosted catalog, served from the engine repository's {@code main} branch.
     *
     * <p>It resolves only while that repository is readable without credentials, which is
     * what {@code raw.githubusercontent.com} serves. A catalog that 404s is not a failure
     * here: provisioning is opt-in, and a caller who has not asked to download never
     * fetches it.
     */
    public static final String DEFAULT_CATALOG_URL =
            "https://raw.githubusercontent.com/DarkCollective/relix-core/main/connectors/relix-connectors.json";

    private static final System.Logger LOG = System.getLogger(ConnectorProvisioner.class.getName());

    /** The result of a provisioning attempt (the caller checks the registry for "already present"). */
    public enum Outcome {
        /** The plugin's artifacts were downloaded into the connector directory. */
        PROVISIONED,
        /** Downloading is disabled; the plugin must be installed manually. */
        DISABLED,
        /** No catalog entry covers this connector type. */
        UNKNOWN,
        /** Download failed (see the message). */
        FAILED
    }

    /** A provisioning outcome plus a human-readable explanation. */
    public record Result(Outcome outcome, String message) {
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(message, "message");
        }
    }

    private final Supplier<ConnectorCatalog> catalogSource;
    private final Path connectorDirectory;
    private final Fetcher fetcher;
    private final boolean enabled;

    /**
     * Creates a provisioner with a fixed, already-loaded catalog.
     *
     * @param catalog            the catalog of downloadable connector plugins
     * @param connectorDirectory the directory plugins are downloaded into and loaded from
     * @param fetcher            the network seam
     * @param enabled            whether downloading is permitted (user consent)
     */
    public ConnectorProvisioner(ConnectorCatalog catalog, Path connectorDirectory,
                                Fetcher fetcher, boolean enabled) {
        this(() -> Objects.requireNonNull(catalog, "catalog"), connectorDirectory, fetcher, enabled);
    }

    /** Creates a provisioner whose catalog is resolved lazily (e.g. fetched from a URL). */
    ConnectorProvisioner(Supplier<ConnectorCatalog> catalogSource, Path connectorDirectory,
                         Fetcher fetcher, boolean enabled) {
        this.catalogSource = Objects.requireNonNull(catalogSource, "catalogSource");
        this.connectorDirectory = Objects.requireNonNull(connectorDirectory, "connectorDirectory");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.enabled = enabled;
    }

    /**
     * Builds a provisioner that fetches the hosted catalog on demand (the location
     * is {@code $RELIX_CONNECTOR_CATALOG} if set, else {@link #DEFAULT_CATALOG_URL})
     * and downloads into the default connector directory.
     *
     * <p>The {@link Fetcher} is a parameter rather than a default because this class
     * lives in the engine and the engine owns no HTTP client: the caller that wants
     * downloads supplies the thing that performs them. Front-ends pass the reporting
     * fetcher that prints progress to the terminal.
     *
     * @param enabled whether downloading is permitted
     * @param fetcher the fetcher used for the catalog fetch and downloads
     * @return a production provisioner
     */
    public static ConnectorProvisioner create(boolean enabled, Fetcher fetcher) {
        Supplier<ConnectorCatalog> source = () -> resolveCatalog(catalogLocation(), fetcher);
        return new ConnectorProvisioner(source, ConnectorPluginLoader.defaultDirectory(), fetcher, enabled);
    }

    /**
     * The connector type tokens available to download from the catalog (e.g. for a
     * {@code relix connectors list}).  Loads the catalog (which may fetch the hosted
     * URL); returns an empty set if the catalog can't be loaded.
     *
     * @return the cataloged connector types, sorted
     */
    public java.util.SortedSet<String> catalogTypes() {
        java.util.SortedSet<String> types = new java.util.TreeSet<>();
        for (ConnectorCatalog.Entry entry : catalogSource.get().entries()) {
            types.add(entry.type());
        }
        return types;
    }

    /** @return whether this provisioner is permitted to download */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Downloads the plugin for a connector type, if cataloged and permitted.  The
     * catalog is fetched only here (lazily) and only when downloading is enabled.
     *
     * @param connectorType the connection's connector type token (e.g. {@code "mongodb"})
     * @return the outcome of the attempt
     */
    public Result provision(String connectorType) {
        Objects.requireNonNull(connectorType, "connectorType");
        if (!enabled) {
            return new Result(Outcome.DISABLED,
                    "connector for '" + connectorType + "' is not installed; re-run with "
                    + "--download-connectors to fetch it, or install it in " + connectorDirectory);
        }
        Optional<ConnectorCatalog.Entry> entry = catalogSource.get().forType(connectorType);
        if (entry.isEmpty()) {
            return new Result(Outcome.UNKNOWN,
                    "no connector catalog entry for type '" + connectorType + "'; install the plugin in "
                    + connectorDirectory + " manually");
        }
        try {
            List<Path> jars = new DriverDownloader(connectorDirectory, fetcher).download(entry.get().artifacts());
            return new Result(Outcome.PROVISIONED,
                    "downloaded connector '" + connectorType + "' (" + jars.size() + " JAR(s))");
        } catch (IOException e) {
            return new Result(Outcome.FAILED,
                    "failed to download connector '" + connectorType + "': " + e.getMessage());
        }
    }

    /**
     * Resolves the catalog location: the {@code relix.connector.catalog} system
     * property if set, else {@code $RELIX_CONNECTOR_CATALOG}, else the hosted URL.
     * (The system property is mainly a testing seam.)
     */
    private static String catalogLocation() {
        String property = System.getProperty("relix.connector.catalog");
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String override = System.getenv(ConnectorCatalog.CATALOG_ENV);
        return (override != null && !override.isBlank()) ? override.trim() : DEFAULT_CATALOG_URL;
    }

    /**
     * Loads the catalog from {@code location} — an {@code http(s)} URL (fetched via
     * {@code fetcher}) or a local file path.  Any failure (offline, missing file,
     * malformed) falls back to the bundled (empty) catalog, so provisioning degrades
     * to {@link Outcome#UNKNOWN} rather than throwing.  Package-private for testing.
     */
    static ConnectorCatalog resolveCatalog(String location, Fetcher fetcher) {
        try {
            if (location.startsWith("http://") || location.startsWith("https://")) {
                byte[] bytes = fetcher.fetch(URI.create(location));
                return ConnectorCatalog.parse(new String(bytes, StandardCharsets.UTF_8));
            }
            Path file = Path.of(location);
            if (Files.isRegularFile(file)) {
                return ConnectorCatalog.parse(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // Quiet by default: an unreachable/missing/malformed catalog degrades to the
            // empty bundled catalog (→ UNKNOWN / "none in catalog"), which already tells
            // the user enough; the detail is available at DEBUG for diagnosis.
            LOG.log(System.Logger.Level.DEBUG,
                    "could not load connector catalog from {0}: {1}", location, e.toString());
        }
        return ConnectorCatalog.bundledDefault();
    }
}

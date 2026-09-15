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
import com.darkcollective.relix.json.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A catalog of downloadable JDBC drivers — the manifest that lets relix fetch a
 * driver it does not have locally, complementing the external driver directory.
 *
 * <p>Each {@link Entry} maps a JDBC URL <em>scheme</em> prefix (e.g.
 * {@code "jdbc:postgresql:"}) to one or more downloadable {@link Artifact}s (a URL
 * plus a SHA-256 checksum).  {@link #forUrl(String)} resolves the entry whose
 * scheme prefixes a connection URL, so a {@link DriverProvisioner} knows what to
 * fetch for {@code jdbc:postgresql://host/db}.
 *
 * <p>A default catalog ships as the {@code relix-drivers.json} resource; it can be
 * overridden by a file named in {@code $RELIX_DRIVER_CATALOG}.  The manifest format
 * is a JSON array of entries:
 * <pre>{@code
 * [
 *   { "name": "PostgreSQL", "scheme": "jdbc:postgresql:",
 *     "artifacts": [ { "url": "https://.../postgresql-42.7.4.jar", "sha256": "…" } ] }
 * ]
 * }</pre>
 */
public final class DriverCatalog {

    /** Environment variable naming a manifest file that overrides the bundled default. */
    public static final String CATALOG_ENV = "RELIX_DRIVER_CATALOG";

    private static final String BUNDLED_RESOURCE = "/relix-drivers.json";

    /** A driver: a JDBC scheme prefix it satisfies and the artifacts to download. */
    public record Entry(String name, String scheme, List<Artifact> artifacts) {
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(scheme, "scheme");
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        }
    }

    private final List<Entry> entries;

    public DriverCatalog(List<Entry> entries) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** @return all catalog entries, in manifest order */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Finds the catalog entry whose scheme prefixes the given JDBC URL
     * (case-insensitive).  When several match, the longest (most specific) scheme
     * wins.
     *
     * @param jdbcUrl the connection URL, e.g. {@code "jdbc:postgresql://host/db"}
     * @return the matching entry, or empty when no catalog entry covers the URL
     */
    public Optional<Entry> forUrl(String jdbcUrl) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(e -> lower.startsWith(e.scheme().toLowerCase(Locale.ROOT)))
                .max((a, b) -> Integer.compare(a.scheme().length(), b.scheme().length()));
    }

    /**
     * Loads the active catalog: the file named by {@code $RELIX_DRIVER_CATALOG} if
     * set and present, otherwise the bundled default.
     *
     * @return the resolved catalog
     */
    public static DriverCatalog load() {
        String override = System.getenv(CATALOG_ENV);
        if (override != null && !override.isBlank()) {
            Path file = Path.of(override.trim());
            if (Files.isRegularFile(file)) {
                try {
                    return parse(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read driver catalog " + file, e);
                }
            }
        }
        return bundledDefault();
    }

    /** @return the catalog bundled as the {@code relix-drivers.json} resource */
    public static DriverCatalog bundledDefault() {
        try (InputStream in = DriverCatalog.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                return new DriverCatalog(List.of());
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read bundled driver catalog", e);
        }
    }

    /**
     * Parses a manifest from JSON text.
     *
     * @param json the manifest JSON (a top-level array of entries)
     * @return the parsed catalog
     * @throws IllegalArgumentException if the manifest is malformed
     */
    public static DriverCatalog parse(String json) {
        Object root = JsonReader.parse(json);
        if (!(root instanceof List<?> array)) {
            throw new IllegalArgumentException("driver catalog must be a JSON array of entries");
        }
        List<Entry> parsed = new ArrayList<>();
        for (Object element : array) {
            if (!(element instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException("each catalog entry must be a JSON object");
            }
            String name = string(entry, "name");
            String scheme = string(entry, "scheme");
            Object artifactsRaw = entry.get("artifacts");
            if (!(artifactsRaw instanceof List<?> artifactList)) {
                throw new IllegalArgumentException("entry '" + name + "' must have an 'artifacts' array");
            }
            List<Artifact> artifacts = new ArrayList<>();
            for (Object a : artifactList) {
                if (!(a instanceof Map<?, ?> artifact)) {
                    throw new IllegalArgumentException("each artifact must be a JSON object");
                }
                artifacts.add(new Artifact(string(artifact, "url"), string(artifact, "sha256")));
            }
            parsed.add(new Entry(name, scheme, artifacts));
        }
        return new DriverCatalog(parsed);
    }

    private static String string(Map<?, ?> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("missing or non-string field '" + key + "'");
        }
        return s;
    }
}

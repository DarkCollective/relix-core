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

import com.darkcollective.relix.json.JsonReader;


import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A catalog of downloadable connector plugins — the SPI-side analogue of the JDBC
 * {@code DriverCatalog}.  It lets relix fetch a connector plugin (e.g. MongoDB)
 * it does not have locally.
 *
 * <p>Each {@link Entry} maps a connector <em>type token</em> ({@code "mongodb"},
 * {@code "http"}, …) to the {@link Artifact}s that make up the plugin — the
 * connector JAR plus any driver/runtime JARs it needs — each with a SHA-256.
 * {@link #forType(String)} resolves the entry for a connection's
 * {@code connectorType}.
 *
 * <p>A default catalog ships as the {@code relix-connectors.json} resource (empty
 * until plugins are published); it can be overridden by a file named in
 * {@code $RELIX_CONNECTOR_CATALOG}.  Manifest format — a JSON array of entries:
 * <pre>{@code
 * [ { "type": "mongodb",
 *     "artifacts": [ { "url": "https://.../relix-mongo-connector.jar", "sha256": "…" },
 *                    { "url": "https://.../mongodb-driver-sync.jar",   "sha256": "…" } ] } ]
 * }</pre>
 */
public final class ConnectorCatalog {

    /** Environment variable naming a manifest file that overrides the bundled default. */
    public static final String CATALOG_ENV = "RELIX_CONNECTOR_CATALOG";

    private static final String BUNDLED_RESOURCE = "/relix-connectors.json";

    /** A connector plugin: the type token it provides and the artifacts to download. */
    public record Entry(String type, List<Artifact> artifacts) {
        public Entry {
            Objects.requireNonNull(type, "type");
            type = type.toLowerCase(Locale.ROOT);
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        }
    }

    private final List<Entry> entries;

    public ConnectorCatalog(List<Entry> entries) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** @return all catalog entries, in manifest order */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Finds the plugin for a connector type token (case-insensitive).
     *
     * @param connectorType the token (e.g. {@code "mongodb"})
     * @return the matching entry, or empty when no plugin is cataloged for the type
     */
    public Optional<Entry> forType(String connectorType) {
        Objects.requireNonNull(connectorType, "connectorType");
        String token = connectorType.toLowerCase(Locale.ROOT);
        return entries.stream().filter(e -> e.type().equals(token)).findFirst();
    }

    /** @return the catalog bundled as the {@code relix-connectors.json} resource */
    public static ConnectorCatalog bundledDefault() {
        try (InputStream in = ConnectorCatalog.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                return new ConnectorCatalog(List.of());
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read bundled connector catalog", e);
        }
    }

    /**
     * Parses a manifest from JSON text.
     *
     * @param json the manifest JSON (a top-level array of entries)
     * @return the parsed catalog
     * @throws IllegalArgumentException if the manifest is malformed
     */
    public static ConnectorCatalog parse(String json) {
        Object root = JsonReader.parse(json);
        if (!(root instanceof List<?> array)) {
            throw new IllegalArgumentException("connector catalog must be a JSON array of entries");
        }
        List<Entry> parsed = new ArrayList<>();
        for (Object element : array) {
            if (!(element instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException("each catalog entry must be a JSON object");
            }
            String type = string(entry, "type");
            Object artifactsRaw = entry.get("artifacts");
            if (!(artifactsRaw instanceof List<?> artifactList)) {
                throw new IllegalArgumentException("entry '" + type + "' must have an 'artifacts' array");
            }
            List<Artifact> artifacts = new ArrayList<>();
            for (Object a : artifactList) {
                if (!(a instanceof Map<?, ?> artifact)) {
                    throw new IllegalArgumentException("each artifact must be a JSON object");
                }
                artifacts.add(new Artifact(string(artifact, "url"), string(artifact, "sha256")));
            }
            parsed.add(new Entry(type, artifacts));
        }
        return new ConnectorCatalog(parsed);
    }

    private static String string(Map<?, ?> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("missing or non-string field '" + key + "'");
        }
        return s;
    }
}

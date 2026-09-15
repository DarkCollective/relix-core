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

import java.util.Objects;

/**
 * One downloadable JAR — a URL and its expected SHA-256 (lower-case hex) — shared by
 * the connector-plugin catalog ({@link ConnectorCatalog}) and the JDBC driver catalog
 * a connector provider keeps ({@code DriverCatalog}, in {@code relix-connectors-std}).
 */
public record Artifact(String url, String sha256) {

    public Artifact {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(sha256, "sha256");
    }

    /** The file name to store the JAR under, derived from the URL's last path segment. */
    public String fileName() {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? "artifact.jar" : name;
    }
}

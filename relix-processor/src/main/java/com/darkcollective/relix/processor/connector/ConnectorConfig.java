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

import com.darkcollective.relix.processor.eval.EvaluationException;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The raw, type-agnostic configuration for one connector-backed relation —
 * the key/value pairs from a {@code connection} or {@code source} declaration,
 * already environment-substituted.
 *
 * <p>Each {@link RelixConnector} defines its own keys: a JDBC connector reads
 * {@code url}/{@code user}/{@code password}; a file connector reads {@code path};
 * a MongoDB connector reads {@code uri}/{@code database}.  Keeping the config
 * untyped means a new connector needs no change to the registry or the
 * declaration AST — it simply documents and reads the keys it understands.
 *
 * <p>Keys are looked up case-sensitively (the connection-declaration parser owns
 * the canonical key names).  Instances are immutable.
 */
public record ConnectorConfig(Map<String, String> values) {

    public ConnectorConfig {
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    /** An empty configuration. */
    public static ConnectorConfig empty() {
        return new ConnectorConfig(Map.of());
    }

    /**
     * @param key the configuration key
     * @return the value if present, otherwise empty
     */
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /**
     * @param key the configuration key
     * @param defaultValue the value to return when {@code key} is absent
     * @return the configured value, or {@code defaultValue} when absent
     */
    public String getOrDefault(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    /**
     * Returns the value for {@code key}, failing when it is absent — for keys a
     * connector cannot operate without (e.g. {@code url} for JDBC).
     *
     * @param key the required configuration key
     * @return the configured value
     * @throws EvaluationException if the key is missing
     */
    public String require(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new EvaluationException("connector configuration is missing required key '" + key + "'");
        }
        return value;
    }
}

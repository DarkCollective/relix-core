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

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.symbol.Schema;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Built-in {@link RelixConnector} for local CSV files — the reference
 * implementation of the connector SPI.
 *
 * <p>Handles the {@code "csv"} type token.  Configuration keys:
 * <ul>
 *   <li>{@code path} — the CSV file path (required; the registry supplies it
 *       already resolved against the script's base directory).</li>
 *   <li>{@code header} — {@code "true"} (default) to read the first row as column
 *       names for case-insensitive mapping, {@code "false"} for positional mapping.</li>
 * </ul>
 *
 * <p>RFC 4180 parsing and per-type coercion are shared with the legacy
 * {@link CsvDataSourceConnector} via {@link CsvDataSourceConnector#readCsv}.  The
 * whole file is read eagerly, so the returned stream holds no file handle and
 * {@link #close()} is a no-op.
 */
public final class CsvConnector implements RelixConnector {

    /** Public no-arg constructor required for {@link java.util.ServiceLoader} discovery. */
    public CsvConnector() {
    }

    @Override
    public Set<String> handles() {
        return Set.of("csv");
    }

    @Override
    public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
        Path path = Path.of(config.require("path"));
        boolean header = Boolean.parseBoolean(config.getOrDefault("header", "true"));
        return CsvDataSourceConnector.readCsv(path, header, schema);
    }
}

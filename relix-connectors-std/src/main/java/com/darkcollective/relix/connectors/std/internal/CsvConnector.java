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
package com.darkcollective.relix.connectors.std.internal;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.symbol.Schema;

import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *   <li>{@code names} — the columns' names, one per line, when the source names them
 *       rather than declaring a schema: the columns are then read by position, and a
 *       header row, if there is one, is skipped.</li>
 * </ul>
 *
 * <p>The connector describes its own file ({@link #tableSchema}): the heading of a source
 * that declares none, inferred from the first {@value #SAMPLE} records by the rules
 * {@link StreamInput} applies to a stream.
 *
 * <p>RFC 4180 parsing and per-type coercion are shared with the legacy
 * {@link CsvDataSourceConnector} via {@link CsvDataSourceConnector#readCsv}.  The
 * whole file is read eagerly, so the returned stream holds no file handle and
 * {@link #close()} is a no-op.
 */
public final class CsvConnector implements RelixConnector, FileBacked {

    /** Public no-arg constructor required for {@link java.util.ServiceLoader} discovery. */
    public CsvConnector() {
    }

    @Override
    public Set<String> handles() {
        return Set.of("csv");
    }

    /** How many records a heading is inferred from. */
    public static final int SAMPLE = 1000;

    /**
     * The configuration a {@code csv("…")} source is opened and described with: its path,
     * its header flag, and the names it gives its columns, if it gives any.
     *
     * @param csv the source's configuration
     * @return the connector configuration, its path not yet resolved
     */
    public static ConnectorConfig config(CsvFileSourceConfig csv) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("path", csv.path());
        values.put("header", String.valueOf(csv.hasHeader()));
        if (!csv.names().isEmpty()) {
            values.put("names", String.join("\n", csv.names()));
        }
        return new ConnectorConfig(values);
    }

    @Override
    public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
        Path path = FileResolver.localPath(config);
        boolean header = Boolean.parseBoolean(config.getOrDefault("header", "true"));
        return CsvDataSourceConnector.readCsv(path, header, !names(config).isEmpty(), schema);
    }

    /**
     * The heading of the file the configuration names, inferred from its first records:
     * each column named by the header row or by {@code names}, and typed the narrowest
     * type its sampled values read as.
     *
     * @throws java.io.UncheckedIOException naming the file when it cannot be read
     * @throws com.darkcollective.relix.processor.EvaluationException when nothing names
     *         the columns — a file with no header row and no names
     */
    @Override
    public Optional<Schema> tableSchema(ConnectorConfig config, String table) {
        Path path = FileResolver.localPath(config);
        boolean header = Boolean.parseBoolean(config.getOrDefault("header", "true"));
        InputStream in;
        try {
            in = Files.newInputStream(path);
        } catch (NoSuchFileException e) {
            throw new UncheckedIOException("there is no file " + path, e);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path + ": " + e.getMessage(), e);
        }
        StreamInput input = StreamInput.open(StreamInput.Format.CSV, in, null, SAMPLE,
                "CSV file '" + path + "'", header, names(config));
        try (Stream<Row> ignored = input.rows()) {
            return Optional.of(input.schema());
        }
    }

    private static List<String> names(ConnectorConfig config) {
        String names = config.getOrDefault("names", "");
        return names.isEmpty() ? List.of() : List.of(names.split("\n"));
    }
}

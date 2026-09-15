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

import com.darkcollective.relix.ast.TemporalLiterals;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A {@link DataSourceConnector} that reads data from local CSV files.
 *
 * <p>The connector looks up each relation in the semantic model's
 * {@link SemanticModel#sources()} map to obtain the {@link CsvFileSourceConfig},
 * then opens the file at the configured path resolved against the supplied base
 * directory.
 *
 * <h2>Column mapping</h2>
 * <p>When {@link CsvFileSourceConfig#hasHeader()} is {@code true}, the first
 * CSV row is read as column names and matched case-insensitively against the
 * schema declared in the source config.  When {@code false}, columns are
 * matched positionally (CSV column 0 → schema column 0, etc.).
 *
 * <h2>Type coercion</h2>
 * <p>Each cell string is coerced to the declared schema type:
 * <ul>
 *   <li>{@code NUMBER} — parsed as a {@link BigDecimal}; throws
 *       {@link EvaluationException} if the cell is not a valid number.</li>
 *   <li>{@code STRING} — returned verbatim (no trimming).</li>
 *   <li>{@code ANY} — attempted as {@code NUMBER} first; falls back to
 *       {@code STRING}.</li>
 *   <li>{@code BOOLEAN} — {@code "true"}/{@code "1"} → {@code true};
 *       {@code "false"}/{@code "0"} → {@code false} (case-insensitive).</li>
 * </ul>
 * <p>Empty cells always become {@link NullValue#INSTANCE} regardless of type.
 *
 * <h2>CSV format</h2>
 * <p>Fields follow RFC 4180: a field enclosed in {@code "…"} may contain
 * commas and newlines; {@code ""} within a quoted field represents a literal
 * {@code "}.  Blank data lines are silently skipped.  A UTF-8 BOM at the
 * start of the file is stripped.
 *
 * <h2>Resource management</h2>
 * <p>This connector reads the entire file eagerly into memory; the returned
 * stream holds no open file handle, so closing the stream is a no-op.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SemanticResult result = analyzer.analyze(stream);
 * CsvDataSourceConnector csv = new CsvDataSourceConnector(
 *         result.model().orElseThrow(), Path.of("data/"));
 * List<QueryResult> results = executor.execute(result, csv);
 * }</pre>
 */
public final class CsvDataSourceConnector implements DataSourceConnector {

    private final SemanticModel model;
    private final Path          baseDir;

    /**
     * Creates a connector for the given semantic model and base directory.
     *
     * @param model   the fully-validated semantic model whose {@code sources} map
     *                provides CSV path and schema configuration; must not be null
     * @param baseDir the directory used to resolve relative CSV paths;
     *                must not be null
     */
    public CsvDataSourceConnector(SemanticModel model, Path baseDir) {
        this.model   = Objects.requireNonNull(model,   "model");
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
    }

    // =========================================================================
    // DataSourceConnector
    // =========================================================================

    /**
     * Opens a stream of rows for the named CSV-backed relation.
     *
     * @param relationName the canonical (lower-cased) relation name
     * @param schema       the expected output schema
     * @return a stream of rows materialised from the CSV file; no open file
     *         handle is retained after this method returns
     * @throws EvaluationException  if no CSV source declaration exists for the
     *                              relation, the file cannot be found, or a cell
     *                              cannot be coerced to its declared type
     * @throws UncheckedIOException if an I/O error occurs while reading the file
     */
    @Override
    public Stream<Row> open(String relationName, Schema schema) {
        String key  = relationName.toLowerCase(Locale.ROOT);
        SourceDeclaration decl = model.sources().get(key);
        if (decl == null) {
            throw new EvaluationException(
                    "No source declaration found for relation '" + relationName + "'");
        }
        if (!(decl.config() instanceof CsvFileSourceConfig csv)) {
            throw new EvaluationException(
                    "Relation '" + relationName + "' is not a CSV source");
        }
        Path path = baseDir.resolve(csv.path());
        return readCsv(path, csv.hasHeader(), schema);
    }

    // =========================================================================
    // CSV reading
    // =========================================================================

    /**
     * Reads a CSV file at {@code path} into a stream of rows for {@code schema}.
     * Package-private so the {@link CsvConnector} built-in {@code RelixConnector}
     * can reuse the same RFC 4180 parsing and type coercion.
     */
    static Stream<Row> readCsv(Path path, boolean hasHeader, Schema schema) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read CSV file: " + path, e);
        }

        // Strip UTF-8 BOM from the first line if present.
        if (!lines.isEmpty()) {
            String first = lines.get(0);
            if (first.startsWith("﻿")) {
                lines.set(0, first.substring(1));
            }
        }

        int     startRow   = 0;
        int[]   colMapping; // schema column index → CSV column index

        if (hasHeader) {
            if (lines.isEmpty()) {
                return Stream.empty();
            }
            List<String> headers = parseCsvLine(lines.get(0));
            colMapping = buildHeaderMapping(headers, schema, path);
            startRow   = 1;
        } else {
            colMapping = positionalMapping(schema.width());
        }

        List<Row> rows = new ArrayList<>(Math.max(0, lines.size() - startRow));
        for (int i = startRow; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;   // skip blank / comment-style lines
            List<String> cells = parseCsvLine(line);
            rows.add(buildRow(cells, colMapping, schema, path, i + 1));
        }
        return rows.stream();
    }

    // ── Column-index mapping ──────────────────────────────────────────────────

    /**
     * Builds an array mapping schema column index to CSV column index using
     * the header row.  Matching is case-insensitive; leading/trailing whitespace
     * in header names is stripped before comparison.
     *
     * @throws EvaluationException if a schema column has no matching header
     */
    private static int[] buildHeaderMapping(List<String> headers, Schema schema, Path path) {
        List<ColumnDefinition> cols = schema.columns();
        int[] mapping = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            String want = cols.get(i).name().toLowerCase(Locale.ROOT);
            int idx = -1;
            for (int j = 0; j < headers.size(); j++) {
                if (headers.get(j).strip().toLowerCase(Locale.ROOT).equals(want)) {
                    idx = j;
                    break;
                }
            }
            if (idx < 0) {
                throw new EvaluationException(
                        "CSV file '" + path + "' has no column '"
                        + cols.get(i).name() + "' (headers: " + headers + ")");
            }
            mapping[i] = idx;
        }
        return mapping;
    }

    /** Returns a trivial 0, 1, 2, … positional mapping. */
    private static int[] positionalMapping(int ncols) {
        int[] mapping = new int[ncols];
        for (int i = 0; i < ncols; i++) mapping[i] = i;
        return mapping;
    }

    // ── Row building ─────────────────────────────────────────────────────────

    /**
     * One row of the file, as the schema's columns.
     *
     * <p><b>A row with no field for a column is refused, not padded.</b> An <em>empty</em>
     * field is a NULL and is how a CSV says so; a <em>missing</em> one is a short row, and
     * reading the two as the same thing manufactured NULL data out of a malformed file
     * with nothing said about it. That is the one outcome worth ruling out here: the file
     * is already refused for a header it does not have and for a cell it cannot coerce, so
     * a row that ran out of fields is the same kind of problem reported the same way.
     *
     * <p>Extra fields are still ignored. A schema may deliberately name fewer columns than
     * the file carries — positionally that reads a prefix, and by header it picks the ones
     * it named — so a row having more than it needs is not a defect in the row.
     */
    private static Row buildRow(List<String> cells, int[] colMapping,
                                Schema schema, Path path, int lineNumber) {
        List<ColumnDefinition> cols   = schema.columns();
        List<Value>            values = new ArrayList<>(cols.size());
        for (int i = 0; i < cols.size(); i++) {
            int csvIdx = colMapping[i];
            if (csvIdx >= cells.size()) {
                throw new EvaluationException(
                        "CSV '" + path + "' line " + lineNumber
                        + ": no field " + (csvIdx + 1) + " for column '" + cols.get(i).name()
                        + "' — the row has " + cells.size() + " field(s). An empty field is a"
                        + " NULL; a row that ends early is a short row");
            }
            String cell = cells.get(csvIdx);
            // CSV sources are scalar; a nested-typed column reads as raw text (ANY).
            ScalarType type = cols.get(i).type() instanceof ScalarType s ? s : ScalarType.ANY;
            values.add(coerce(cell, type, cols.get(i).name(), path, lineNumber));
        }
        return ArrayRow.of(schema, values);
    }

    /**
     * Coerces a raw CSV cell string to a typed {@link Value}.
     * An empty cell always yields {@link NullValue#INSTANCE}.
     */
    private static Value coerce(String cell, ScalarType type,
                                 String colName, Path path, int lineNumber) {
        if (cell.isEmpty()) return NullValue.INSTANCE;
        return switch (type) {
            case NUMBER -> {
                try {
                    yield new NumberValue(new BigDecimal(cell.strip()));
                } catch (NumberFormatException e) {
                    throw new EvaluationException(
                            "CSV '" + path + "' line " + lineNumber
                            + ": cannot parse '" + cell
                            + "' as NUMBER for column '" + colName + "'");
                }
            }
            case STRING  -> new StringValue(cell);
            case ANY     -> {
                try {
                    yield new NumberValue(new BigDecimal(cell.strip()));
                } catch (NumberFormatException _) {
                    yield new StringValue(cell);
                }
            }
            case BOOLEAN -> {
                String lower = cell.strip().toLowerCase(Locale.ROOT);
                if (lower.equals("true")  || lower.equals("1")) yield BooleanValue.of(true);
                if (lower.equals("false") || lower.equals("0")) yield BooleanValue.of(false);
                throw new EvaluationException(
                        "CSV '" + path + "' line " + lineNumber
                        + ": cannot parse '" + cell
                        + "' as BOOLEAN for column '" + colName + "'");
            }
            // Temporal-typed cells parse against ISO-8601 (empty → NULL above,
            // malformed → error, mirroring the NUMBER path).  ADR-0013 slice 5.
            case DATE, TIME, TIMESTAMP, DURATION ->
                    coerceTemporal(cell.strip(), type, colName, path, lineNumber);
        };
    }

    /** Parses a temporal CSV cell against ISO-8601, wrapping in the matching {@link Value}. */
    private static Value coerceTemporal(String cell, ScalarType type,
                                        String colName, Path path, int lineNumber) {
        try {
            return switch (type) {
                case DATE      -> new DateValue(TemporalLiterals.parseDate(cell));
                case TIME      -> new TimeValue(TemporalLiterals.parseTime(cell));
                case TIMESTAMP -> new TimestampValue(TemporalLiterals.parseTimestamp(cell));
                case DURATION  -> new DurationValue(TemporalLiterals.parseDuration(cell));
                default -> throw new IllegalStateException("not a temporal type: " + type);
            };
        } catch (DateTimeException e) {
            throw new EvaluationException(
                    "CSV '" + path + "' line " + lineNumber
                    + ": cannot parse '" + cell + "' as " + type.name()
                    + " for column '" + colName + "'");
        }
    }

    // =========================================================================
    // RFC 4180 CSV line parser
    // =========================================================================

    /**
     * Parses a single CSV line into a list of field strings following RFC 4180.
     *
     * <ul>
     *   <li>Quoted fields ({@code "…"}) may contain commas.</li>
     *   <li>A doubled quote ({@code ""}) inside a quoted field represents a
     *       literal {@code "} character.</li>
     *   <li>Unquoted field content is returned verbatim (no whitespace trimming).</li>
     *   <li>A trailing comma produces a trailing empty field.</li>
     * </ul>
     *
     * <p>This method is package-private to allow direct unit testing.
     *
     * @param line a single line of CSV text; must not be {@code null}
     * @return the ordered list of field values; never null, never empty for a
     *         non-null input (an empty line returns a one-element list containing
     *         an empty string)
     */
    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        int i   = 0;
        int len = line.length();

        while (i <= len) {
            if (i == len) {
                // We reached the end after a comma: emit the trailing empty field.
                fields.add("");
                break;
            }
            if (line.charAt(i) == '"') {
                // Quoted field — consume until the closing (unescaped) quote.
                StringBuilder sb = new StringBuilder();
                i++; // skip opening '"'
                while (i < len) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < len && line.charAt(i + 1) == '"') {
                            // Escaped quote: "" → "
                            sb.append('"');
                            i += 2;
                        } else {
                            i++; // skip closing '"'
                            break;
                        }
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                fields.add(sb.toString());
                // Advance past the field-separator comma (if present).
                if (i < len && line.charAt(i) == ',') {
                    i++;
                } else if (i < len) {
                    // Junk after closing quote; skip to the next separator.
                    while (i < len && line.charAt(i) != ',') i++;
                    if (i < len) i++;
                }
                // i == len → outer loop will detect the trailing-comma case on
                // the next iteration only if the last char was a comma.
            } else {
                // Unquoted field — scan to the next comma.
                int start = i;
                while (i < len && line.charAt(i) != ',') i++;
                fields.add(line.substring(start, i));
                if (i < len) {
                    i++; // skip the comma
                } else {
                    break; // normal end of line — no trailing-comma field to emit
                }
            }
        }
        return fields;
    }
}

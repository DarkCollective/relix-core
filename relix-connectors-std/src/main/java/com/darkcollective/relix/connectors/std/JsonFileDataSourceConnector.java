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

import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.DocumentRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.JsonValues;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.value.ValuePath;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.Schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A {@link DataSourceConnector} that reads rows from a local JSON file as
 * <em>open</em> (schema-on-read) documents — the first native nested-data source.
 *
 * <p>The connector looks up each relation in the semantic model's
 * {@link SemanticModel#sources()} map to obtain its {@link JsonFileSourceConfig},
 * reads the file at the configured path (resolved against the supplied base
 * directory), parses it via {@link JsonValues}, and yields one
 * {@link DocumentRow} per record object.
 *
 * <h2>Record extraction</h2>
 * <p>Where the records live in the parsed document depends on
 * {@link JsonFileSourceConfig#records()}:
 * <ul>
 *   <li><strong>present</strong> — the path (e.g. {@code "data.items"}) is
 *       navigated null-propagatingly into the document; the value found there
 *       must be a JSON array, and each element becomes a row.</li>
 *   <li><strong>absent</strong> — the top-level value is the record source: an
 *       array yields one row per element; a single object yields a single row.</li>
 * </ul>
 *
 * <p>Each record element must be a JSON object ({@link StructValue}); a non-object
 * element raises an {@link EvaluationException} (a document row must be a struct).
 *
 * <h2>Schema</h2>
 * <p>JSON sources are open: the {@code schema} argument to {@link #open} is
 * ignored, and every row reports {@link Schema#open()} via its
 * {@link DocumentRow}.  Columns are resolved by path at query time.
 *
 * <h2>Resource management</h2>
 * <p>The file is read eagerly into memory; the returned stream holds no open
 * file handle, so closing it is a no-op.
 */
public final class JsonFileDataSourceConnector implements DataSourceConnector {

    private final SemanticModel model;
    private final Path          baseDir;

    /**
     * Creates a connector for the given semantic model and base directory.
     *
     * @param model   the fully-validated semantic model whose {@code sources} map
     *                provides JSON path configuration; must not be null
     * @param baseDir the directory used to resolve relative JSON paths;
     *                must not be null
     */
    public JsonFileDataSourceConnector(SemanticModel model, Path baseDir) {
        this.model   = Objects.requireNonNull(model,   "model");
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
    }

    /**
     * Opens a stream of document rows for the named JSON-backed relation.
     *
     * @param relationName the canonical (lower-cased) relation name
     * @param schema       ignored — JSON sources are open / schema-on-read
     * @return a stream of {@link DocumentRow}s, one per record object
     * @throws EvaluationException  if no JSON source declaration exists for the
     *                              relation, the records path does not resolve to
     *                              an array, or a record element is not an object
     * @throws UncheckedIOException if the file cannot be read
     */
    @Override
    public Stream<Row> open(String relationName, Schema schema) {
        String key = relationName.toLowerCase(Locale.ROOT);
        SourceDeclaration decl = model.sources().get(key);
        if (decl == null) {
            throw new EvaluationException(
                    "No source declaration found for relation '" + relationName + "'");
        }
        if (!(decl.config() instanceof JsonFileSourceConfig json)) {
            throw new EvaluationException(
                    "Relation '" + relationName + "' is not a JSON source");
        }
        Path path = baseDir.resolve(json.path());
        Value document = parse(path);
        List<Value> records = extractRecords(document, json.records(), path);
        return toRows(records, path).stream();
    }

    // =========================================================================
    // JSON reading
    // =========================================================================

    private static Value parse(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read JSON file: " + path, e);
        }
        try {
            return JsonValues.parse(text);
        } catch (JsonValues.JsonParseException e) {
            throw new EvaluationException("Malformed JSON in '" + path + "': " + e.getMessage());
        }
    }

    /**
     * Locates the array of record objects within the parsed document.  When
     * {@code recordsPath} is present, the value at that path must be an array;
     * a {@code null}/missing path or a non-array value is an error.  When absent,
     * a top-level array is used as-is, and a top-level object is wrapped as a
     * single-element list.
     */
    private static List<Value> extractRecords(Value document, Optional<String> recordsPath, Path path) {
        if (recordsPath.isPresent()) {
            Value located = ValuePath.navigate(document, recordsPath.get());
            if (located instanceof ArrayValue array) {
                return array.elements();
            }
            throw new EvaluationException(
                    "JSON records path '" + recordsPath.get() + "' in '" + path
                    + "' did not resolve to an array (found "
                    + describe(located) + ")");
        }
        if (document instanceof ArrayValue array) {
            return array.elements();
        }
        if (document instanceof StructValue) {
            return List.of(document);
        }
        throw new EvaluationException(
                "JSON file '" + path + "' top-level value must be an array or object (found "
                + describe(document) + "); declare a 'records' path if records are nested");
    }

    private static List<Row> toRows(List<Value> records, Path path) {
        List<Row> rows = new ArrayList<>(records.size());
        for (Value record : records) {
            if (!(record instanceof StructValue document)) {
                throw new EvaluationException(
                        "JSON record in '" + path + "' is not an object (found "
                        + describe(record) + "); each record must be a JSON object");
            }
            rows.add(new DocumentRow(document));
        }
        return rows;
    }

    private static String describe(Value value) {
        return value == null || value instanceof NullValue ? "null" : value.getClass().getSimpleName();
    }
}

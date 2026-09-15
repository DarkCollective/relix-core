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
package com.darkcollective.relix.lang.ast.source;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport configuration for a local JSON file source.
 *
 * <p>Unlike {@link CsvFileSourceConfig} or {@link DatabaseSourceConfig}, a JSON
 * file source declares <strong>no schema</strong>: it is an <em>open</em>
 * (schema-on-read) source whose rows are nested {@code StructValue} documents
 * navigated by path at query time.  This is the first source kind that exercises
 * the open-schema machinery end to end.
 *
 * <h2>Syntax</h2>
 * <pre>{@code
 * source Docs from json("docs.json");                       // top-level array of objects
 * source Docs from json("docs.json") { records: "data.items" };  // array at a nested path
 * }</pre>
 *
 * <p>{@code records}, when present, is a {@code ValuePath}-style path
 * (relix-processor, which this module does not depend on, hence no link):
 * {@code "a.b[0].items"} locating the array of record
 * objects within the parsed document.  When absent, the connector treats the
 * top-level value as the record source: an array yields one row per element, a
 * single object yields a single row.
 *
 * <p>An optional {@code references:} block declares foreign-key style
 * relationships from this source's columns to other relations.
 * Because a JSON source is open, its <em>own</em> referencing columns cannot be
 * checked against a declared schema — they resolve dynamically — but the target
 * relation and columns are validated as usual.
 *
 * @param path       the JSON file path, resolved against the connector's base
 *                   directory; must not be null or blank
 * @param records    optional path to the array of records within the document;
 *                   must not be null (use {@link Optional#empty()})
 * @param references the declared column references; may be empty
 */
public record JsonFileSourceConfig(String path, Optional<String> records,
                                   List<ColumnReference> references)
        implements SourceConfig {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if any component is null
     * @throws IllegalArgumentException if {@code path} is blank
     */
    public JsonFileSourceConfig {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(references, "references");
        references = List.copyOf(references);
    }

    /** Creates a config with no declared references. */
    public JsonFileSourceConfig(String path, Optional<String> records) {
        this(path, records, List.of());
    }
}

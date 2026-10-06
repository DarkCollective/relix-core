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

/**
 * Configuration for a local CSV file source.
 *
 * <p>Example:
 * <pre>
 *   source Products from csv("./products.csv") {
 *       schema: {
 *           id:    NUMBER,
 *           name:  STRING,
 *           price: NUMBER
 *       }
 *   };
 * </pre>
 *
 * <p>When {@link #hasHeader()} is {@code true} the first row of the file is
 * treated as a header row and matched against the schema column names.  When
 * {@code false} columns are matched positionally.
 *
 * <p>The schema may be left out, and is then inferred when the script is analysed, from
 * the start of the file: each column's name from the header row, its type the narrowest
 * its values read as. {@link #names()} names the columns instead — for a file with no
 * header row, or one whose header names are unusable — and the types are still inferred;
 * a source that gives names reads its columns by position, skipping a header row if it
 * has one. A source declares a schema or names, not both.
 *
 * <p>An optional {@code references:} block declares foreign-key style
 * relationships from this source's columns to other relations.
 *
 * @param path       the file system path to the CSV file; must not be blank
 * @param hasHeader  {@code true} if the first row contains column names
 * @param columns    the ordered column specifications; empty to infer them
 * @param references the declared column references; may be empty
 * @param names      the columns' names, in file order, their types to be inferred; empty
 *                   to take them from the schema or the header row
 */
public record CsvFileSourceConfig(
        String path,
        boolean hasHeader,
        List<ColumnSpec> columns,
        List<ColumnReference> references,
        List<String> names
) implements SourceConfig {

    public CsvFileSourceConfig {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(references, "references");
        Objects.requireNonNull(names, "names");
        columns = List.copyOf(columns);
        references = List.copyOf(references);
        names = List.copyOf(names);
        if (!columns.isEmpty() && !names.isEmpty()) {
            throw new IllegalArgumentException(
                    "a CSV source declares a schema or column names, not both");
        }
        if (names.stream().map(n -> n.toLowerCase(java.util.Locale.ROOT)).distinct().count()
                != names.size()) {
            throw new IllegalArgumentException("a CSV source names a column more than once");
        }
    }

    /** Creates a config with declared references and no names-only list. */
    public CsvFileSourceConfig(String path, boolean hasHeader, List<ColumnSpec> columns,
                               List<ColumnReference> references) {
        this(path, hasHeader, columns, references, List.of());
    }

    /** Creates a config with no declared references. */
    public CsvFileSourceConfig(String path, boolean hasHeader, List<ColumnSpec> columns) {
        this(path, hasHeader, columns, List.of());
    }

    /**
     * Whether the heading is inferred from the file when the script is analysed, rather
     * than declared.
     *
     * @return {@code true} when no schema is declared
     */
    public boolean infersSchema() {
        return columns.isEmpty();
    }
}

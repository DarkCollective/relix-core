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
 * <p>An optional {@code references:} block declares foreign-key style
 * relationships from this source's columns to other relations.
 *
 * @param path       the file system path to the CSV file; must not be blank
 * @param hasHeader  {@code true} if the first row contains column names
 * @param columns    the ordered column specifications; must not be empty
 * @param references the declared column references; may be empty
 */
public record CsvFileSourceConfig(
        String path,
        boolean hasHeader,
        List<ColumnSpec> columns,
        List<ColumnReference> references
) implements SourceConfig {

    public CsvFileSourceConfig {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        Objects.requireNonNull(references, "references");
        columns = List.copyOf(columns);
        references = List.copyOf(references);
    }

    /** Creates a config with no declared references. */
    public CsvFileSourceConfig(String path, boolean hasHeader, List<ColumnSpec> columns) {
        this(path, hasHeader, columns, List.of());
    }
}

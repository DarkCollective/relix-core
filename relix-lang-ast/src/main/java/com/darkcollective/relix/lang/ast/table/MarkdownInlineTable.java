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
package com.darkcollective.relix.lang.ast.table;

import java.util.List;
import java.util.Objects;

/**
 * An inline table in pipe-delimited Markdown format.
 *
 * <p>Source syntax:
 * <pre>
 *   Cities := [
 *   | name         | country | population |
 *   |--------------|---------|------------|
 *   | Chicago, IL  | US      | 2700000    |
 *   | London       | UK      | 8900000    |
 *   ];
 * </pre>
 *
 * <p>Separator rows (lines whose cells contain only {@code -} characters) are
 * recognised and discarded by the parser.  Cell values are trimmed of
 * leading and trailing whitespace.
 *
 * @param headers the column names from the header row; must not be empty
 * @param rows    the data rows; each inner list has the same length as
 *                {@code headers}
 */
public record MarkdownInlineTable(
        List<String> headers,
        List<List<String>> rows
) implements InlineTable {

    public MarkdownInlineTable {
        Objects.requireNonNull(headers, "headers");
        if (headers.isEmpty()) {
            throw new IllegalArgumentException("headers must not be empty");
        }
        Objects.requireNonNull(rows, "rows");
        headers = List.copyOf(headers);
        rows = rows.stream().map(List::copyOf).collect(java.util.stream.Collectors.toUnmodifiableList());
    }
}

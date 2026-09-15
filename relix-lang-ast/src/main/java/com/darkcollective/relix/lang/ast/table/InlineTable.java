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

/**
 * Sealed root interface for inline relation data embedded in a script.
 *
 * <p>Two surface syntaxes are supported:
 * <ul>
 *   <li>{@link MarkdownInlineTable} — pipe-delimited Markdown table.</li>
 *   <li>{@link CsvInlineTable} — comma-delimited CSV table.</li>
 * </ul>
 *
 * <p>Both formats expose the same logical structure: an ordered list of
 * column names ({@link #headers()}) and an ordered list of data rows
 * ({@link #rows()}), where each row is an ordered list of raw string cell values.
 */
public sealed interface InlineTable permits MarkdownInlineTable, CsvInlineTable {

    /** Returns the column names in declaration order. */
    List<String> headers();

    /** Returns the data rows, each as an ordered list of raw cell strings. */
    List<List<String>> rows();
}

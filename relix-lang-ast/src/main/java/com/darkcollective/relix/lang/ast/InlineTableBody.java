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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.lang.ast.source.ColumnReference;
import com.darkcollective.relix.lang.ast.table.InlineTable;

import java.util.List;
import java.util.Objects;

/**
 * An assignment body that holds an inline data table (Markdown or CSV format).
 *
 * <p>Examples:
 * <pre>
 *   Cities := [
 *   | name         | country |
 *   |--------------|---------|
 *   | Chicago, IL  | US      |
 *   ];
 *
 *   Cities := csv[
 *       name, country
 *       "Chicago, IL", US
 *   ];
 * </pre>
 *
 * <p>Cell values are stored as raw strings.  Type inference (STRING vs NUMBER)
 * and conversion to {@link com.darkcollective.relix.ast.Operand} values are
 * deferred to the semantic layer.
 *
 * <p>An inline table may declare foreign-key relationships with a trailing
 * {@code references} clause — the inline-table counterpart of the
 * {@code references:} field on source configs:
 * <pre>
 *   Cities := [
 *   | name    | country |
 *   |---------|---------|
 *   | Chicago | US      |
 *   ] references { country -> Countries.code };
 * </pre>
 *
 * @param table      the parsed inline table; must not be null
 * @param references declared column references; may be empty
 */
public record InlineTableBody(InlineTable table,
                              List<ColumnReference> references) implements AssignmentBody {

    public InlineTableBody {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(references, "references");
        references = List.copyOf(references);
    }

    /** Creates a body with no declared references. */
    public InlineTableBody(InlineTable table) {
        this(table, List.of());
    }
}

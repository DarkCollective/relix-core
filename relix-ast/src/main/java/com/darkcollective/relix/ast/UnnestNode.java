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
package com.darkcollective.relix.ast;

import com.darkcollective.relix.ast.visitor.RelNodeVisitor;

import java.util.Objects;
import java.util.Optional;

/**
 * Unnest (μ) — explodes an array-valued column into one row per element,
 * carrying the other columns through (≈ SQL {@code LATERAL UNNEST}, Mongo
 * {@code $unwind}).  This is the bridge that makes nested data relationally
 * queryable.
 *
 * <p>For each input row, the value of {@link #column()} is treated as an array;
 * the operator emits one output row per element with {@code column} bound to that
 * element (and the remaining columns unchanged).  The {@link #outer()} flag selects
 * the behaviour when the value is an empty array, {@code NULL}, missing, or not an
 * array:
 * <ul>
 *   <li><b>inner</b> ({@code outer == false}) — emit no rows (drop the input row);</li>
 *   <li><b>outer</b> ({@code outer == true}) — emit a single row with {@code column}
 *       bound to {@code NULL}.</li>
 * </ul>
 *
 * <p>When {@link #ordinalityColumn()} is present (the SQL {@code WITH ORDINALITY}
 * variant — {@code μ items WITH ORDINALITY pos}), an extra {@code NUMBER} column of
 * that name is appended carrying each element's <strong>1-based</strong> position in
 * its array.  In the {@code outer} no-element case the ordinality is {@code NULL}
 * alongside the {@code NULL} element.
 *
 * <p>Example: {@code μ items (Orders)} — one row per element of {@code items};
 * {@code μ items WITH ORDINALITY pos (Orders)} — likewise, plus a {@code pos} column
 * numbering the elements from 1.
 *
 * @param column           the name of the array column to unnest; must not be blank
 * @param outer            whether to preserve input rows whose array is empty/missing
 * @param ordinalityColumn the name of the appended 1-based index column, or empty for
 *                         no ordinality; never null
 * @param input            the source relation; must not be null
 * @param location         the source location of this node; never null
 */
public record UnnestNode(String column, boolean outer, Optional<String> ordinalityColumn,
                         RelNode input, SourceLocation location)
        implements RelNode {

    public UnnestNode {
        Objects.requireNonNull(column, "column");
        if (column.isBlank()) {
            throw new IllegalArgumentException("Unnest column must not be blank");
        }
        Objects.requireNonNull(ordinalityColumn, "ordinalityColumn");
        ordinalityColumn.ifPresent(n -> {
            if (n.isBlank()) {
                throw new IllegalArgumentException("Unnest ordinality column must not be blank");
            }
        });
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests: inner unnest, no ordinality, {@link SourceLocation#UNKNOWN}. */
    public UnnestNode(String column, RelNode input) {
        this(column, false, Optional.empty(), input, SourceLocation.UNKNOWN);
    }

    /** Convenience constructor for tests: no ordinality, {@link SourceLocation#UNKNOWN}. */
    public UnnestNode(String column, boolean outer, RelNode input) {
        this(column, outer, Optional.empty(), input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

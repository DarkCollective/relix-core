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

import java.util.List;
import java.util.Objects;

/**
 * Column-folding operator (UNPIVOT) — transforms selected columns into rows.
 *
 * <p>Given a list of source {@link #columns()} to fold, UNPIVOT folds each
 * named column into two new columns:
 *
 * <ul>
 *   <li>{@link #nameColumn()} — a {@code STRING} column whose value is the
 *       source column name.</li>
 *   <li>{@link #valueColumn()} — an {@code ANY} column whose value is the
 *       cell value from the source column.</li>
 * </ul>
 *
 * <p>For each input row, the operator emits one output row per column in
 * {@link #columns()}. The output schema is the input schema minus the listed
 * columns, plus {@link #nameColumn()}{@code :STRING} and
 * {@link #valueColumn()}{@code :ANY}.
 *
 * <p>Surface syntax:
 * <pre>
 *   UNPIVOT (jan, feb, mar) AS (month, revenue) (MonthlySales)
 * </pre>
 *
 * <p>UNPIVOT is a non-blocking, non-collapsing operator — a streaming flatMap
 * that transforms each input row into {@code N} output rows where {@code N} is
 * the number of columns listed ({@link MaterializationMode#STREAM}). It is
 * never pushed down to a source.
 *
 * @param columns     the ordered list of column names to fold into rows; must
 *                    not be null or empty
 * @param nameColumn  the name of the output column holding the source column
 *                    name; must not be blank
 * @param valueColumn the name of the output column holding the cell value; must
 *                    not be blank
 * @param input       the source relation; must not be null
 * @param location    the source location of this node; never null
 */
public record UnpivotNode(
        List<String> columns,
        String nameColumn,
        String valueColumn,
        RelNode input,
        SourceLocation location
) implements RelNode {

    public UnpivotNode {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("UNPIVOT columns list must not be empty");
        }
        Objects.requireNonNull(nameColumn, "nameColumn");
        if (nameColumn.isBlank()) {
            throw new IllegalArgumentException("UNPIVOT nameColumn must not be blank");
        }
        Objects.requireNonNull(valueColumn, "valueColumn");
        if (valueColumn.isBlank()) {
            throw new IllegalArgumentException("UNPIVOT valueColumn must not be blank");
        }
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        columns = List.copyOf(columns);
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public UnpivotNode(List<String> columns, String nameColumn, String valueColumn, RelNode input) {
        this(columns, nameColumn, valueColumn, input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

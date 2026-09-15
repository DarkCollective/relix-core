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
 * Row-pivoting operator (PIVOT) — spreads distinct values of a key column into
 * new columns.
 *
 * <p>The PIVOT operator groups rows by the optional {@link #groupKeys()} (or
 * treats the whole relation as one group when {@link #groupKeys()} is empty),
 * finds the distinct values of {@link #keyColumn()}, and emits one output row
 * per group. Each distinct key value becomes a new column whose cell value is
 * taken from {@link #valueColumn()}.
 *
 * <p>Because the output schema depends on the runtime data (the distinct key
 * values are not known at parse time), PIVOT always produces an open,
 * schema-on-read {@code Schema.open()} as its output schema. Column names in
 * the output are the string representations of the distinct key values.
 *
 * <p>Surface syntax:
 * <pre>
 *   PIVOT revenue BY month PER region (MonthlySales)
 *   PIVOT amount BY category (Transactions)
 * </pre>
 *
 * <p>PIVOT is a blocking operator ({@link MaterializationMode#BAG}) — it must
 * consume the entire input before it can emit any output, since it needs to
 * discover the full set of distinct key values first. It is never pushed down
 * to a source.
 *
 * @param valueColumn the column whose values fill the new pivot cells; must not
 *                    be blank
 * @param keyColumn   the column whose distinct values become new column headers;
 *                    must not be blank
 * @param groupKeys   the {@code PER} grouping columns; empty means one global
 *                    group; must not be null
 * @param input       the source relation; must not be null
 * @param location    the source location of this node; never null
 */
public record PivotNode(
        String valueColumn,
        String keyColumn,
        List<String> groupKeys,
        RelNode input,
        SourceLocation location
) implements RelNode {

    public PivotNode {
        Objects.requireNonNull(valueColumn, "valueColumn");
        if (valueColumn.isBlank()) {
            throw new IllegalArgumentException("PIVOT valueColumn must not be blank");
        }
        Objects.requireNonNull(keyColumn, "keyColumn");
        if (keyColumn.isBlank()) {
            throw new IllegalArgumentException("PIVOT keyColumn must not be blank");
        }
        Objects.requireNonNull(groupKeys, "groupKeys");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        groupKeys = List.copyOf(groupKeys);
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public PivotNode(String valueColumn, String keyColumn, List<String> groupKeys, RelNode input) {
        this(valueColumn, keyColumn, groupKeys, input, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

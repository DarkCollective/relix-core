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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A join on named shared columns — SQL's {@code JOIN … USING (a, b)} — in its inner,
 * left, right and full forms.
 *
 * <p>Rows match where every named column is equal on both sides, as an equality
 * condition on each would have it, so a NULL matches nothing. The output carries each
 * named column once, first and in the order named: the left row's value for an inner
 * or left join, the right row's for a right join, and whichever side is present for a
 * full join, as SQL's {@code COALESCE} of the two. Then come the left input's other
 * columns and the right input's other columns, a right column whose name the left
 * already uses taking the {@code _r} suffix every join gives it.
 *
 * <p>Unlike the natural join, which matches on <em>every</em> column the two inputs
 * share, only the named columns are matched; any other shared column stays twice.
 *
 * <p>Example: {@code Orders ⟕ USING (customer_id) Customers}, or in SQL's order,
 * {@code Orders LJOIN Customers USING (customer_id)}.
 *
 * @param kind     which rows an unmatched side keeps; never null
 * @param left     the left input relation; never null
 * @param right    the right input relation; never null
 * @param columns  the shared columns to match on; at least one, no name twice
 * @param location the source location of this node; never null
 */
public record UsingJoinNode(Kind kind, RelNode left, RelNode right, List<String> columns,
                            SourceLocation location) implements RelNode {

    /** Which unmatched rows the join keeps, as SQL's {@code [INNER | LEFT | RIGHT | FULL] JOIN}. */
    public enum Kind {
        /** Matched rows only. */
        INNER,
        /** Every left row, with NULLs where nothing on the right matches. */
        LEFT,
        /** Every right row, with NULLs where nothing on the left matches. */
        RIGHT,
        /** Every row of both sides. */
        FULL
    }

    public UsingJoinNode {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(location, "location");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("USING needs at least one column");
        }
        Set<String> seen = new HashSet<>();
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                throw new IllegalArgumentException("USING column must not be blank");
            }
            if (!seen.add(column.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("USING names column '" + column + "' twice");
            }
        }
        columns = List.copyOf(columns);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public UsingJoinNode(Kind kind, RelNode left, RelNode right, List<String> columns) {
        this(kind, left, right, columns, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

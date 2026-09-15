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
package com.darkcollective.relix.symbol.graph;

import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One side of a {@link Relationship}: a relation plus the ordered columns
 * joined at this side, carrying numeric multiplicity bounds.
 *
 * <p>The relation may be <em>any</em> {@link RelationSymbol} kind — including a
 * {@code QueryRelationSymbol}, so a relationship can target a derived view
 * ("active orders") exactly as it targets a base table.
 *
 * <p>Column lists are positional: the <i>i</i>-th column of one endpoint
 * equijoins the <i>i</i>-th column of the other. A composite foreign key is one
 * edge with multi-column endpoints, never two single-column edges.
 *
 * <p>The bounds state how many rows of <em>this</em> endpoint's relation may
 * match one row of the opposite endpoint. The defaults — {@code min = 0},
 * {@code max} absent (∞) — are the unconstrained reading: an omitted bound
 * asserts nothing, so the engine never over-commits. {@code min = 0} is the
 * outer-join signal (an inner join may drop opposite-side rows); a finite
 * {@code max} is a fan-out and selectivity fact.
 *
 * @param relation the relation at this endpoint; never null
 * @param columns  the ordered join columns; never empty
 * @param min      lower multiplicity bound; never negative (default 0)
 * @param max      upper multiplicity bound; empty = unbounded (default)
 */
public record Endpoint(
        RelationSymbol relation,
        List<String> columns,
        long min,
        OptionalLong max
) {

    public Endpoint {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        if (min < 0) {
            throw new IllegalArgumentException("min must not be negative");
        }
        Objects.requireNonNull(max, "max");
        columns = List.copyOf(columns);
    }

    /** Creates an endpoint with the default unconstrained bounds {@code [0..*]}. */
    public static Endpoint unbounded(RelationSymbol relation, List<String> columns) {
        return new Endpoint(relation, columns, 0, OptionalLong.empty());
    }

    /** Returns {@code true} if any bound was actually constrained beyond the 0/∞ defaults. */
    public boolean bounded() {
        return min > 0 || max.isPresent();
    }

    /** The display form of this endpoint's bounds, e.g. {@code [1..50]} or {@code [0..*]}. */
    public String boundsLabel() {
        return "[" + min + ".." + (max.isPresent() ? String.valueOf(max.getAsLong()) : "*") + "]";
    }
}

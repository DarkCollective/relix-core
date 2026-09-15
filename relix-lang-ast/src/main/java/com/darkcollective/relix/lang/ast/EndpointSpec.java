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

import com.darkcollective.relix.ast.SourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One endpoint of a {@link RelateStatement} — a relation reference plus the
 * ordered columns joined at this side, with optional multiplicity bounds.
 *
 * <p>Syntactic forms:
 * <pre>
 *   Orders.order_id                    // single-column shorthand
 *   Orders(order_id) [1..50]           // parenthesised, with bounds
 *   WarehouseSlot(tenant_id, product_id)   // composite key — ONE endpoint
 * </pre>
 *
 * <p>The relation reference is purely syntactic at this level; it is resolved
 * against the symbol table during semantic analysis. Omitted bounds default to
 * the unconstrained reading {@code [0..*]}: an absent {@code max} (empty
 * {@link OptionalLong}) means unbounded.
 *
 * <p>Bound <em>consistency</em> ({@code max ≥ min}, {@code max ≥ 1}) is
 * deliberately not enforced here — the semantic layer reports violations as
 * positioned diagnostics rather than construction failures.
 *
 * @param relationRef the referenced relation name (possibly namespace-qualified);
 *                    must not be blank
 * @param columns     the ordered column names at this endpoint; must not be empty
 * @param min         the lower multiplicity bound; must not be negative (default 0)
 * @param max         the upper multiplicity bound; empty = unbounded (∞)
 * @param location    the source location of the endpoint's first token
 */
public record EndpointSpec(
        String relationRef,
        List<String> columns,
        long min,
        OptionalLong max,
        SourceLocation location
) {

    public EndpointSpec {
        Objects.requireNonNull(relationRef, "relationRef");
        if (relationRef.isBlank()) {
            throw new IllegalArgumentException("relationRef must not be blank");
        }
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        if (min < 0) {
            throw new IllegalArgumentException("min must not be negative");
        }
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(location, "location");
        columns = List.copyOf(columns);
    }

    /** Creates an endpoint with the default unconstrained bounds {@code [0..*]}. */
    public static EndpointSpec unbounded(String relationRef, List<String> columns,
                                         SourceLocation location) {
        return new EndpointSpec(relationRef, columns, 0, OptionalLong.empty(), location);
    }
}

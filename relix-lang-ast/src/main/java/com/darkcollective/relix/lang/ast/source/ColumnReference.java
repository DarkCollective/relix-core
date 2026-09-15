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

import com.darkcollective.relix.ast.SourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * One entry of a {@code references:} block on a database or connection-table
 * source — a foreign-key style arrow from columns of the declaring
 * source to columns of another relation:
 * <pre>
 *   references: {
 *       customer_id -&gt; Customers.customer_id,
 *       (tenant_id, product_id) -&gt; Product(tenant_id, id)
 *   }
 * </pre>
 *
 * <p>Column lists are positional — the <i>i</i>-th source column equijoins the
 * <i>i</i>-th target column — and a composite foreign key is <em>one</em>
 * reference. Equal list lengths are validated semantically, with
 * a positioned diagnostic, not here.
 *
 * <p>The FK-arrow form implies an upper multiplicity bound of 1 on the target
 * endpoint (each referencing row points to at most one referenced row); the
 * relationship name defaults to the source column list. Use a standalone
 * {@code relate} statement for explicit names, inverse names, or bounds.
 *
 * @param sourceColumns  the referencing columns of the declaring source; must not be empty
 * @param targetRelation the referenced relation name; must not be blank
 * @param targetColumns  the referenced columns; must not be empty
 * @param location       the source location of the entry's first token
 */
public record ColumnReference(
        List<String> sourceColumns,
        String targetRelation,
        List<String> targetColumns,
        SourceLocation location
) {

    public ColumnReference {
        Objects.requireNonNull(sourceColumns, "sourceColumns");
        if (sourceColumns.isEmpty()) {
            throw new IllegalArgumentException("sourceColumns must not be empty");
        }
        Objects.requireNonNull(targetRelation, "targetRelation");
        if (targetRelation.isBlank()) {
            throw new IllegalArgumentException("targetRelation must not be blank");
        }
        Objects.requireNonNull(targetColumns, "targetColumns");
        if (targetColumns.isEmpty()) {
            throw new IllegalArgumentException("targetColumns must not be empty");
        }
        Objects.requireNonNull(location, "location");
        sourceColumns = List.copyOf(sourceColumns);
        targetColumns = List.copyOf(targetColumns);
    }

    /** The default relationship name for this unnamed edge: the source column list. */
    public String defaultName() {
        return String.join(", ", sourceColumns);
    }
}

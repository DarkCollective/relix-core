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
package com.darkcollective.relix.symbol;

import java.util.Locale;
import java.util.Objects;

/**
 * The source-relation origin of a schema {@link ColumnDefinition} — which
 * relation a column came from, and under what name, so that a
 * <em>relation-qualified</em> reference ({@code rooms.name}) can be resolved
 * precisely even after a join has renamed the physical column to disambiguate a
 * collision.
 *
 * <p>A join concatenates two schemas; when both sides expose a column of the
 * same name the right side's is renamed with an {@code _r} suffix
 * ({@link Schema#concat(Schema)}).  The renamed column's physical name is now
 * {@code name_r}, but its provenance still records {@code (rooms, name)} — so
 * {@code rooms.name} resolves to it, while {@code devices.name} resolves to the
 * un-renamed {@code name} on the left.  Ambiguity is impossible by construction:
 * a qualified reference matches at most one column, and a stale qualifier
 * (relation not in scope) resolves to none — a validation error rather than a
 * silent unqualified first-match.
 *
 * <p>Both fields are matched case-insensitively at resolution time, consistent
 * with the rest of relix's name handling.
 *
 * @param relation the name of the relation the column originates from (its
 *                 declared name, or the re-anchored name after a relation-rename
 *                 {@code ρ R (…)}); must not be blank
 * @param column   the column's name <em>within</em> that relation (its logical
 *                 name, which may differ from the possibly-suffixed physical name
 *                 in the concatenated schema); must not be blank
 */
public record ColumnProvenance(String relation, String column) {

    public ColumnProvenance {
        requireNonBlank(relation, "relation");
        requireNonBlank(column, "column");
    }

    /**
     * Whether this provenance is for the relation named {@code qualifier} and the
     * column named {@code columnName}, compared case-insensitively.
     *
     * @param qualifier  the relation qualifier to match; must not be {@code null}
     * @param columnName the column name to match; must not be {@code null}
     * @return {@code true} if both match case-insensitively
     */
    public boolean matches(String qualifier, String columnName) {
        Objects.requireNonNull(qualifier, "qualifier");
        Objects.requireNonNull(columnName, "columnName");
        return relation.equalsIgnoreCase(qualifier)
                && column.equalsIgnoreCase(columnName);
    }

    /**
     * Returns a copy of this provenance re-anchored to a different relation,
     * keeping the same logical column name.  Used by a whole-relation rename
     * {@code ρ NewName (…)}, which re-labels every column's origin to
     * {@code NewName}.
     *
     * @param newRelation the new relation name; must not be blank
     * @return the re-anchored provenance
     */
    public ColumnProvenance reanchor(String newRelation) {
        return new ColumnProvenance(newRelation, column);
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

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

import java.util.Objects;

/**
 * A single column in a relation schema — its name, {@link Type}, and optional
 * source-relation {@link ColumnProvenance}.
 *
 * <p>The {@code type} may be a {@link ScalarType} (the flat, all-scalar case) or
 * a nested {@link StructType}/{@link ArrayType} — relix is an NF² (non-first-
 * normal-form) algebra in which the classic flat relational model is the special
 * case where every column is scalar.  A column whose nested shape is
 * unknown (schema-on-read) is typed {@link ScalarType#ANY}.
 *
 * <p>Column names are case-preserved (the declared form is stored as-is), but
 * all schema-level lookups via {@link Schema#column(String)} are case-insensitive.
 *
 * <p>The optional {@link #provenance()} records which relation the column came
 * from and under what name, so that a relation-qualified reference
 * ({@code rooms.name}) resolves precisely above a join even when the physical
 * column has been renamed to disambiguate a collision.  Provenance is
 * <em>metadata</em>: it deliberately does <strong>not</strong> participate in
 * {@link #equals(Object)}/{@link #hashCode()}, so two columns with the same name
 * and type remain equal (and {@link Schema} equality — relied upon for
 * union-compatibility, deduplication, and set operations — is unchanged) whether
 * or not they carry provenance.
 *
 * @param name       the column name; must not be blank
 * @param type       the type of values in this column (scalar or nested); must not be null
 * @param provenance the column's source-relation origin, or {@code null} if unknown
 */
public record ColumnDefinition(String name, Type type, ColumnProvenance provenance) {

    public ColumnDefinition {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Column name must not be blank");
        }
        Objects.requireNonNull(type, "type");
        // provenance is optional (nullable) — a column need not know its origin.
    }

    /**
     * Convenience constructor for a column with no known provenance — the common
     * case (declared source schemas, projections of computed expressions, and most
     * call sites).
     *
     * @param name the column name; must not be blank
     * @param type the column type; must not be null
     */
    public ColumnDefinition(String name, Type type) {
        this(name, type, null);
    }

    /**
     * Returns a copy of this column with the given provenance.
     *
     * @param provenance the source-relation origin; may be {@code null} to clear it
     * @return a column identical to this one but carrying {@code provenance}
     */
    public ColumnDefinition withProvenance(ColumnProvenance provenance) {
        return new ColumnDefinition(name, type, provenance);
    }

    /**
     * Returns a copy of this column with a different physical name, preserving the
     * type and provenance.  Used when a join renames a colliding right-side column
     * (physical {@code name_r}) while keeping its {@code (relation, name)} origin.
     *
     * @param newName the replacement physical name; must not be blank
     * @return a column identical to this one but named {@code newName}
     */
    public ColumnDefinition withName(String newName) {
        return new ColumnDefinition(newName, type, provenance);
    }

    /**
     * Whether this column's provenance matches the relation-qualified reference
     * {@code qualifier.columnName} (case-insensitive).  Always {@code false} when
     * the column carries no provenance.
     *
     * @param qualifier  the relation qualifier
     * @param columnName the column name within that relation
     * @return {@code true} if this column originates from {@code qualifier} under
     *         the name {@code columnName}
     */
    public boolean matchesQualified(String qualifier, String columnName) {
        return provenance != null && provenance.matches(qualifier, columnName);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Equality is over {@code name} and {@code type} only — {@link #provenance()}
     * is excluded (see the class Javadoc).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColumnDefinition other)) return false;
        return name.equals(other.name) && type.equals(other.type);
    }

    /** {@inheritDoc} — over {@code name} and {@code type} only (provenance excluded). */
    @Override
    public int hashCode() {
        return 31 * name.hashCode() + type.hashCode();
    }
}

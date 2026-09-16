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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The structural description of a relation — an ordered list of named, typed columns.
 *
 * <p>Column names within a schema are unique in a case-insensitive sense:
 * {@code "UserId"} and {@code "userid"} are considered the same name and will
 * cause an {@link IllegalArgumentException} at construction time.
 *
 * <p>The column list is defensive-copied on construction; instances are
 * therefore immutable.  A lowercased name → position index is precomputed once
 * at construction so that {@link #indexOf(String)} and {@link #column(String)}
 * (and, in turn, per-row name lookups during query execution) run in constant
 * time without re-scanning or re-lowercasing the column list on every access.
 *
 * <p>Equality and hash code are defined purely over the ordered column list, so
 * the type behaves like the value object it conceptually is (and the way it did
 * when it was declared as a {@code record}).
 *
 * <p>Example:
 * <pre>{@code
 * Schema schema = new Schema(List.of(
 *     new ColumnDefinition("id",   ScalarType.NUMBER),
 *     new ColumnDefinition("name", ScalarType.STRING)
 * ));
 * schema.column("ID");  // Optional.of(ColumnDefinition("id", NUMBER))
 * schema.indexOf("ID"); // 0
 * }</pre>
 */
public final class Schema {

    private final List<ColumnDefinition> columns;

    /** Lowercased column name → zero-based position; immutable, derived from {@link #columns}. */
    private final Map<String, Integer> indexByName;

    /**
     * Whether this is an <em>open</em> (dynamic-document, schema-on-read) schema —
     * every {@link #column(String)} lookup it cannot answer from {@link #columns}
     * resolves to a {@link ScalarType#ANY} column, to be resolved at runtime (see
     * ADR-0001).  Usually there are no columns at all; a join between a dynamic
     * document and a declared relation is the case where some are known.
     */
    private final boolean open;

    /** Cached hash code; computed lazily (0 acts as the "not yet computed" sentinel). */
    private int hash;

    /**
     * Creates a schema from the given ordered column definitions.
     *
     * @param columns the ordered column definitions; must not be {@code null} or empty,
     *                and must not contain duplicate names (case-insensitive)
     * @throws IllegalArgumentException if {@code columns} is empty or contains a
     *                                  duplicate column name
     */
    public Schema(List<ColumnDefinition> columns) {
        this(columns, false);
    }

    /**
     * Creates a schema of known columns that may additionally be
     * {@linkplain #open() open} — a schema-on-read heading some of whose columns
     * <em>are</em> known, which is what a join between a dynamic document and a
     * declared relation produces.  Only {@link #concat(Schema)} makes one.
     */
    private Schema(List<ColumnDefinition> columns, boolean open) {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Schema must contain at least one column");
        }
        List<ColumnDefinition> copy = List.copyOf(columns);

        // Build the lookup index, detecting duplicate column names (case-insensitive).
        Map<String, Integer> index = new HashMap<>(copy.size() * 2);
        for (int i = 0; i < copy.size(); i++) {
            String key = copy.get(i).name().toLowerCase(Locale.ROOT);
            if (index.putIfAbsent(key, i) != null) {
                throw new IllegalArgumentException(
                        "Duplicate column name in schema: '" + copy.get(i).name() + "'");
            }
        }

        this.columns = copy;
        this.indexByName = Map.copyOf(index);
        this.open = open;
    }

    /**
     * Private constructor for the two distinguished zero-column schemas:
     * {@link #open()} ({@code open == true}) and {@link #empty()}
     * ({@code open == false}).  Both have no columns but mean opposite things
     * (see {@link #empty()}).
     */
    private Schema(boolean open) {
        this.columns = List.of();
        this.indexByName = Map.of();
        this.open = open;
    }

    /**
     * Returns an <em>open</em> schema — a dynamic document with no fixed columns,
     * for schema-on-read sources (e.g. a NoSQL collection or JSON endpoint with no
     * declared schema).  Every {@link #column(String)} lookup resolves to a
     * {@link ScalarType#ANY} column, so attribute/path references against it are
     * never reported as missing; they resolve at runtime.
     *
     * <p>An open schema is <em>distinct</em> from an absent/unresolved schema: it
     * is a legitimate, queryable state, not an error placeholder.
     *
     * @return the singleton-style open schema (a fresh instance; all are equal)
     */
    public static Schema open() {
        return new Schema(true);
    }

    /**
     * Returns the <em>empty</em> schema — a <em>closed</em> relation with no
     * columns.  This is the heading of the two nullary "truth" relations of the
     * relational algebra (Tutorial D's {@code TABLE_DEE}/{@code TABLE_DUM}): a
     * zero-column relation holds either one tuple (the empty tuple — "true") or no
     * tuples ("false").  It is produced today by the no-key whole-relation
     * universal quantifier {@code ∀ : P (R)}.
     *
     * <p>The empty schema is the <em>opposite</em> of an {@linkplain #open() open}
     * schema: both have zero columns, but an open schema resolves <em>every</em>
     * column reference (to {@link ScalarType#ANY}), whereas the empty schema
     * resolves <em>none</em>.  {@link #equals(Object)} keeps the two distinct.
     *
     * @return the empty (closed, zero-column) schema; a fresh instance, all equal
     */
    public static Schema empty() {
        return new Schema(false);
    }

    /**
     * Whether this schema is {@linkplain #open() open} (dynamic / schema-on-read).
     *
     * @return {@code true} for an open schema
     */
    public boolean isOpen() {
        return open;
    }

    /*
     * Note for anyone reading isOpen() as "has no columns": that held until a join
     * with a schema-on-read input kept its heading (#630 follow-up). It never was
     * the meaning — open says a reference this heading does not name still resolves
     * — and the executor was already written for the general case, which is how the
     * gap showed: ExecSupport.concatRows has an open branch producing a document
     * row, and it was unreachable while concat dropped the flag, so `open ⨝ typed`
     * died at runtime with "Value count 5 does not match schema width 2".
     */

    /**
     * Whether this schema is the {@linkplain #empty() empty} (closed, zero-column)
     * schema — the heading of the nullary truth relations.  Distinct from an
     * {@linkplain #open() open} schema, which is also column-less but resolves
     * every reference.
     *
     * @return {@code true} only for the closed zero-column schema
     */
    public boolean isEmpty() {
        return !open && columns.isEmpty();
    }

    /**
     * Returns the ordered column definitions of this schema.
     *
     * @return an immutable list of column definitions; empty for the
     *         {@linkplain #empty() empty} schema and for {@link #open()}, but not
     *         necessarily for an open heading a join has added known columns to
     */
    public List<ColumnDefinition> columns() {
        return columns;
    }

    /**
     * Returns the zero-based position of the column named {@code name}
     * (case-insensitively), or {@code -1} if no such column exists.
     *
     * @param name the column name to look up; must not be {@code null}
     * @return the column's position, or {@code -1} if absent
     */
    public int indexOf(String name) {
        Objects.requireNonNull(name, "name");
        Integer i = indexByName.get(name.toLowerCase(Locale.ROOT));
        return i == null ? -1 : i;
    }

    /**
     * Returns the column definition whose name matches {@code name}
     * (case-insensitively), or {@link Optional#empty()} if no such column exists.
     *
     * @param name the column name to look up
     * @return the matching column definition, or empty
     */
    public Optional<ColumnDefinition> column(String name) {
        Objects.requireNonNull(name, "name");
        int i = indexOf(name);
        if (i >= 0) {
            return Optional.of(columns.get(i));
        }
        if (open) {
            // Every other reference into an open document resolves — dynamically, to
            // ANY. A declared column answers first: a heading that knows a column's
            // type should not forget it because the relation beside it is dynamic.
            return Optional.of(new ColumnDefinition(name, ScalarType.ANY));
        }
        return Optional.empty();
    }

    /**
     * Whether this schema <em>declares</em> a column called {@code name} — the
     * question to ask before adding one.
     *
     * <p>Not the same question as {@link #column(String)}, which answers what a
     * <em>read</em> of that name would give. The two differ on an
     * {@linkplain #open() open} schema: a schema-on-read relation resolves any name
     * to {@code ANY}, because the shape arrives with the row, while declaring
     * nothing at all. Reading {@code column(name).isPresent()} as "the name is
     * taken" therefore made every name taken, and every operator that appends a
     * column — {@code SESSIONIZE}, {@code TRACE}, {@code WINDOW}, {@code TREE},
     * {@code WHY} — refused to run over any JSON, HTTP or MongoDB source.
     *
     * @param name the column name to test; must not be {@code null}
     * @return {@code true} only if this schema names that column itself
     */
    public boolean declares(String name) {
        Objects.requireNonNull(name, "name");
        return indexOf(name) >= 0;
    }

    /**
     * Resolves a possibly-dotted reference to the type it names, descending into
     * struct fields — {@code person.name}, {@code provenance.variables.relation}.
     *
     * <p>A whole-name match wins first, so a column whose name really does contain a
     * dot (written with a delimited identifier) is still reachable. Only then is the
     * name read as a path: its head must name a column, and each remaining segment a
     * field of the type before it.
     *
     * <p>A path into an {@code ANY} column resolves to {@code ANY} rather than
     * failing. Schema-on-read data has no declared shape to check a field name
     * against, so the alternative to trusting the reference is refusing every
     * reference — which would make a nested column readable only by relations that
     * declared their shape up front, exactly the case that cannot.
     *
     * <p>This is not the relation-qualified form ({@code Orders.amount}), which
     * resolves by source-relation provenance and is checked before this; the two are
     * spelled alike and a caller tries them in that order.
     *
     * @param name a column name, or a dotted path into one; must not be {@code null}
     * @return the type the reference names, or empty if nothing does
     */
    public Optional<Type> resolvePath(String name) {
        Objects.requireNonNull(name, "name");
        Optional<ColumnDefinition> whole = column(name);
        if (whole.isPresent()) {
            return whole.map(ColumnDefinition::type);
        }
        int dot = name.indexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return Optional.empty();
        }
        return column(name.substring(0, dot))
                .flatMap(head -> descend(head.type(), name.substring(dot + 1)));
    }

    /** Walks the remaining dotted segments of a path through nested struct fields. */
    private static Optional<Type> descend(Type type, String path) {
        Type current = type;
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) {
                return Optional.empty();
            }
            if (current == ScalarType.ANY) {
                return Optional.of(ScalarType.ANY);   // shape unknown until a row arrives
            }
            if (!(current instanceof StructType struct)) {
                return Optional.empty();
            }
            Optional<StructType.Field> field = struct.field(segment);
            if (field.isEmpty()) {
                return Optional.empty();
            }
            current = field.get().type();
        }
        return Optional.of(current);
    }

    /**
     * Returns the number of columns in this schema.
     *
     * @return column count — the columns that are <em>known</em>, which for an
     *         {@linkplain #isOpen() open} heading is not all a row may carry;
     *         {@code 0} for the {@linkplain #empty() empty} and {@link #open()}
     *         schemas
     */
    public int width() {
        return columns.size();
    }

    /**
     * Returns a new {@code Schema} formed by appending all columns of {@code other}
     * to this schema.
     *
     * <p>If a column from {@code other} has the same name (case-insensitively) as
     * an already-present column, it is renamed by appending {@code _r}; if that
     * still clashes, {@code _r1}, {@code _r2}, … are tried until a unique name is
     * found.
     *
     * <p><b>Openness survives.</b>  The result is {@linkplain #isOpen() open} if
     * either side is, whether or not the other side has columns: a reference into a
     * dynamic document still resolves through the join, and the columns the declared
     * side does name keep their own types.  Concatenating two column-less schemas is
     * the same rule at its limit — open if either is, otherwise the
     * {@linkplain #empty() empty} schema, which is what a join of two nullary truth
     * relations has to be.  Both are outside what the column-list constructor
     * accepts, so this is the one place the two zero-column headings survive being
     * combined.
     *
     * @param other the schema whose columns to append; must not be {@code null}
     * @return the concatenated schema; never {@code null}
     */
    public Schema concat(Schema other) {
        Objects.requireNonNull(other, "other");
        if (columns.isEmpty() && other.columns.isEmpty()) {
            return open || other.open ? open() : empty();
        }
        List<ColumnDefinition> cols = new ArrayList<>(columns);
        Set<String> existing = new LinkedHashSet<>();
        columns.forEach(c -> existing.add(c.name().toLowerCase(Locale.ROOT)));

        for (ColumnDefinition rc : other.columns()) {
            String name = rc.name();
            if (existing.contains(name.toLowerCase(Locale.ROOT))) {
                String candidate = name + "_r";
                int n = 1;
                while (existing.contains(candidate.toLowerCase(Locale.ROOT))) {
                    candidate = name + "_r" + n++;
                }
                name = candidate;
            }
            // Preserve the right column's source-relation provenance:
            // the physical name may become `name_r`, but its origin is unchanged,
            // so a qualified reference still resolves to it.
            cols.add(rc.withName(name));
            existing.add(name.toLowerCase(Locale.ROOT));
        }
        return new Schema(cols, open || other.open);
    }

    /**
     * Returns a schema with {@code replacement} as its known columns and this schema's
     * openness.
     *
     * <p>A rewrite of an open heading's known columns (re-anchoring their provenance
     * under a rename, say) has to leave the heading open: a document row under it
     * still carries fields the heading does not name. Building the result with
     * {@link #Schema(List)} would close it, and the executor would then size a
     * document row against a fixed width.
     *
     * @param replacement the new known columns, in order; must not be {@code null} or
     *                    empty, and must not contain duplicate names
     * @return a schema over {@code replacement}, open exactly when this one is
     * @throws IllegalArgumentException if {@code replacement} is empty or contains a
     *                                  duplicate column name
     */
    public Schema withColumns(List<ColumnDefinition> replacement) {
        return new Schema(replacement, open);
    }

    /**
     * Whether any column in this schema carries source-relation
     * {@link ColumnProvenance}.  Qualified-reference resolution and
     * validation only engage when provenance is present; a schema with none falls
     * back to the legacy qualifier-stripping behaviour.
     *
     * @return {@code true} if at least one column has non-null provenance
     */
    public boolean hasProvenance() {
        for (ColumnDefinition c : columns) {
            if (c.provenance() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the zero-based positions of every column whose source-relation
     * {@link ColumnProvenance} matches the qualified reference
     * {@code qualifier.columnName} (both matched case-insensitively).
     *
     * <p>By construction this yields at most one match for a well-formed schema:
     * distinct source relations keep their columns distinct, and a
     * whole-relation rename ({@code ρ R (…)}) re-anchors every origin to {@code R}
     * using the (unique) physical names.  More than one match can only arise from
     * a raw self-join of the same relation with no intervening rename — a genuine
     * ambiguity the validator reports.
     *
     * @param qualifier  the relation qualifier (e.g. {@code rooms})
     * @param columnName the column name within that relation (e.g. {@code name})
     * @return the matching positions in column order; empty for an
     *         {@linkplain #open() open} schema or when nothing matches
     */
    public List<Integer> qualifiedIndices(String qualifier, String columnName) {
        Objects.requireNonNull(qualifier, "qualifier");
        Objects.requireNonNull(columnName, "columnName");
        if (open) {
            return List.of();
        }
        List<Integer> matches = new ArrayList<>(1);
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).matchesQualified(qualifier, columnName)) {
                matches.add(i);
            }
        }
        return matches;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Schema other)) return false;
        return open == other.open && columns.equals(other.columns);
    }

    @Override
    public int hashCode() {
        // Cached: a schema is immutable, and its hash is consulted once per row
        // during deduplication and set operations.  The unlikely genuine-zero hash
        // is simply recomputed each time.
        int h = hash;
        if (h == 0) {
            h = columns.hashCode();
            hash = h;
        }
        return h;
    }

    @Override
    public String toString() {
        if (open) {
            return columns.isEmpty() ? "Schema[open]" : "Schema[open, columns=" + columns + "]";
        }
        return "Schema[columns=" + columns + "]";
    }
}

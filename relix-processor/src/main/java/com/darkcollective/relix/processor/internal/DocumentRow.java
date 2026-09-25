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
package com.darkcollective.relix.processor.internal;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ColumnProvenance;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.value.internal.ValuePath;
import com.darkcollective.relix.symbol.Schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link Row} backed by a nested {@link StructValue} document — the row shape
 * produced by an <em>open</em> (schema-on-read) source such as the JSON file
 * connector.
 *
 * <p>Unlike {@link ArrayRow} (fixed positional columns under a closed schema), a
 * document row has no fixed columns: {@link #schema()} is {@linkplain Schema#open()
 * open}, and {@link #get(String)} resolves a name as a <strong>null-propagating
 * path</strong> into the document ({@code user.name}, {@code items[0]}), returning
 * {@link com.darkcollective.relix.value.NullValue} for any miss rather
 * than throwing.  This is what lets heterogeneous documents be queried by path
 * without a declared schema.
 *
 * <p>A document row may also know the <em>origins</em> of its top-level fields — the
 * relation-qualified names each one answers to. A scan anchors every field to the
 * relation it read ({@link #reanchored(String)}), and a join records each field it
 * assembled ({@link #origins()}). That is what lets {@code products.name} and
 * {@code orders.product_id} name fields of the document rather than paths into fields
 * called {@code products} and {@code orders}. See {@link #get(String)}.
 */
public final class DocumentRow implements Row {

    private static final Schema OPEN = Schema.open();

    private final StructValue document;
    private final Map<String, List<ColumnProvenance>> origins;
    /**
     * The relation every field answers to under its own name, or {@code null}. A row has
     * an owner or explicit origins, never both: a scan or rename sets the first, a join
     * the second.
     */
    private final String owner;

    /**
     * @param document the backing document; must not be null
     */
    public DocumentRow(StructValue document) {
        this(document, Map.of());
    }

    /**
     * Creates a document row that knows where its fields came from.
     *
     * @param document the backing document; must not be null
     * @param origins  for each top-level field name (as the document spells it), the
     *                 relation-qualified names that field answers to; a field with no
     *                 entry answers to none. Must not be null
     */
    public DocumentRow(StructValue document, Map<String, List<ColumnProvenance>> origins) {
        this(document, copyOf(origins), null);
    }

    private DocumentRow(StructValue document, Map<String, List<ColumnProvenance>> origins, String owner) {
        this.document = Objects.requireNonNull(document, "document");
        this.origins = origins;
        this.owner = owner;
    }

    private static Map<String, List<ColumnProvenance>> copyOf(Map<String, List<ColumnProvenance>> origins) {
        Objects.requireNonNull(origins, "origins");
        Map<String, List<ColumnProvenance>> copy = new LinkedHashMap<>();
        origins.forEach((field, names) -> copy.put(field, List.copyOf(names)));
        return java.util.Collections.unmodifiableMap(copy);
    }

    /** {@return the backing document} */
    public StructValue document() {
        return document;
    }

    @Override
    public Schema schema() {
        return OPEN;
    }

    /**
     * {@return the explicit origins, keyed by field name — the relation-qualified names
     * a join recorded for each field it assembled; empty for any other row}
     *
     * @see #originsOf(String)
     */
    public Map<String, List<ColumnProvenance>> origins() {
        return origins;
    }

    /**
     * {@return the relation every field without an explicit origin answers to under its
     * own name — the relation a scan read this row from, or the name a rename gave it}
     */
    public Optional<String> owner() {
        return Optional.ofNullable(owner);
    }

    /**
     * The relation-qualified names the top-level field {@code field} answers to: its
     * explicit origins where it has them, else its {@linkplain #owner() owner's}, else
     * none.
     *
     * @param field a top-level field name, as the document spells it
     * @return the names it answers to; empty when it answers to none
     */
    public List<ColumnProvenance> originsOf(String field) {
        List<ColumnProvenance> explicit = origins.get(field);
        if (explicit != null) {
            return explicit;
        }
        return owner == null ? List.of() : List.of(new ColumnProvenance(owner, field));
    }

    /**
     * Returns this row as it reads under {@code relation}: every top-level field answers
     * to {@code relation} under its own name, and to nothing else. That is what a scan
     * of {@code relation} means, and what {@code ρ relation (…)} does to a declared
     * heading's provenance.
     *
     * @param relation the relation name; must not be blank
     * @return a row over the same document, anchored to {@code relation}
     */
    public DocumentRow reanchored(String relation) {
        Objects.requireNonNull(relation, "relation");
        if (relation.isBlank()) {
            throw new IllegalArgumentException("relation must not be blank");
        }
        return new DocumentRow(document, Map.of(), relation);
    }

    /**
     * Returns this row with its top-level fields renamed: {@code renames} maps a field
     * name, matched case-insensitively, to its new name. A name the document does not
     * carry is ignored — whether a field exists is a fact about each document. A field
     * keeps its position and value, and an explicit {@linkplain #origins() origin}
     * follows it under the new name, as a declared column's provenance does.
     *
     * @param renames old field name → new field name; must not be null
     * @return the renamed row, or this row when nothing it carries is renamed
     * @throws IllegalArgumentException if a new name collides with a field the
     *         document still carries, or with another renamed field
     */
    public DocumentRow renamed(Map<String, String> renames) {
        Map<String, String> byLower = new LinkedHashMap<>();
        renames.forEach((from, to) -> byLower.put(from.toLowerCase(Locale.ROOT), to));
        Map<String, Value> fields = new LinkedHashMap<>();
        Map<String, String> newNames = new LinkedHashMap<>();   // old name → new name
        boolean changed = false;
        for (Map.Entry<String, Value> field : document.fields().entrySet()) {
            String old = field.getKey();
            String to = byLower.get(old.toLowerCase(Locale.ROOT));
            String name = to != null ? to : old;
            changed |= to != null;
            for (Map.Entry<String, String> earlier : newNames.entrySet()) {
                if (earlier.getValue().equalsIgnoreCase(name)) {
                    // Name the rename, whichever of the two fields came first.
                    boolean thisRenamed = to != null;
                    String from = thisRenamed ? old : earlier.getKey();
                    String target = thisRenamed ? name : earlier.getValue();
                    String other = thisRenamed ? earlier.getValue() : old;
                    throw new IllegalArgumentException("renaming '" + from + "' to '" + target
                            + "' collides with the field '" + other + "' the document carries");
                }
            }
            fields.put(name, field.getValue());
            newNames.put(old, name);
        }
        if (!changed) {
            return this;
        }
        Map<String, List<ColumnProvenance>> moved = new LinkedHashMap<>();
        origins.forEach((field, names) -> {
            String name = newNames.getOrDefault(field, field);
            moved.put(name, names.stream()
                    .map(o -> name.equals(field) ? o : new ColumnProvenance(o.relation(), name))
                    .toList());
        });
        return new DocumentRow(new StructValue(fields), Collections.unmodifiableMap(moved), owner);
    }

    /**
     * Resolves {@code name} against the document, yielding {@code NullValue} on any miss.
     *
     * <p>A name is first read as a <em>relation-qualified</em> reference when its
     * leading segment is a relation some field {@linkplain #originsOf(String) originates} from:
     * {@code orders.product_id} is the field whose origin is {@code orders.product_id},
     * whatever the document calls it, and {@code products.details.city} navigates into
     * the field {@code products.details} names. A relation in scope that owns no such
     * field reads as NULL, the same answer a document gives for a field it does not
     * carry. Otherwise — and always, for a row with no origins — the name is a path into
     * the document: {@code "field"}, {@code "a.b"}, {@code "items[0].price"}.
     *
     * <p>The relation reading is tried first because that is the order a declared row
     * resolves a dotted name in, and a joined row must not answer the same query
     * differently for having one open input. Where two fields answer to the same
     * qualified name — a self-join with no rename — the first wins, as it does in a join
     * condition.
     */
    @Override
    public Value get(String name) {
        Objects.requireNonNull(name, "name");
        if (owner != null || !origins.isEmpty()) {
            Value qualified = qualified(name);
            if (qualified != null) {
                return qualified;
            }
        }
        return ValuePath.navigate(document, name);
    }

    /**
     * The qualified reading of {@code name}, or {@code null} when it is not one. A
     * relation name may itself contain a dot, so every split is tried, shortest first.
     */
    private Value qualified(String name) {
        for (int dot = name.indexOf('.'); dot > 0 && dot < name.length() - 1;
                dot = name.indexOf('.', dot + 1)) {
            String relation = name.substring(0, dot);
            if (isOriginRelation(relation)) {
                return owned(relation, name.substring(dot + 1));
            }
        }
        return null;
    }

    /** The field of {@code relation} that {@code rest} starts with, navigated into; NULL if none. */
    private Value owned(String relation, String rest) {
        int end = firstDelimiter(rest);
        String column = rest.substring(0, end);
        for (Map.Entry<String, List<ColumnProvenance>> entry : origins.entrySet()) {
            for (ColumnProvenance origin : entry.getValue()) {
                if (origin.matches(relation, column)) {
                    Value field = fieldValue(entry.getKey());
                    return ValuePath.navigate(field, rest.substring(end));
                }
            }
        }
        // Reached only for a relation the row answers to, and a row with an owner has
        // no explicit origins — so an owner here is the relation asked for.
        if (owner != null) {
            for (Map.Entry<String, Value> field : document.fields().entrySet()) {
                if (field.getKey().equalsIgnoreCase(column)) {
                    return ValuePath.navigate(field.getValue(), rest.substring(end));
                }
            }
        }
        return NullValue.INSTANCE;
    }

    private boolean isOriginRelation(String relation) {
        if (owner != null && owner.equalsIgnoreCase(relation)) {
            return true;
        }
        for (List<ColumnProvenance> names : origins.values()) {
            for (ColumnProvenance origin : names) {
                if (origin.relation().equalsIgnoreCase(relation)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Value fieldValue(String field) {
        Value value = document.fields().get(field);
        return value != null ? value : NullValue.INSTANCE;
    }

    /** The end of the first path segment of {@code path}: its first {@code .} or {@code [}. */
    private static int firstDelimiter(String path) {
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.' || c == '[') {
                return i;
            }
        }
        return path.length();
    }

    @Override
    public Value get(int index) {
        int i = 0;
        for (Value value : document.fields().values()) {
            if (i++ == index) {
                return value;
            }
        }
        throw new IndexOutOfBoundsException("index " + index + " for a " + width() + "-field document");
    }

    @Override
    public int width() {
        return document.fields().size();
    }

    /**
     * Returns the document's top-level field names in document order — the
     * dynamically-discovered columns of this open row.
     */
    @Override
    public java.util.List<String> columnNames() {
        return java.util.List.copyOf(document.fields().keySet());
    }

    /**
     * Returns a copy of this document row with the top-level field {@code field}
     * set to {@code value} (replacing an existing field case-insensitively, or
     * adding it).  Used by unnest to bind the exploded element back into the row.
     * The field keeps whatever {@linkplain #originsOf(String) origin} it had.
     *
     * @param field the field name
     * @param value the new value
     * @return a new document row
     */
    public DocumentRow with(String field, Value value) {
        Map<String, Value> fields = new LinkedHashMap<>(document.fields());
        String existing = null;
        String target = field.toLowerCase(Locale.ROOT);
        for (String key : fields.keySet()) {
            if (key.toLowerCase(Locale.ROOT).equals(target)) {
                existing = key;
                break;
            }
        }
        fields.put(existing != null ? existing : field, value);
        return new DocumentRow(new StructValue(fields), origins, owner);
    }
}

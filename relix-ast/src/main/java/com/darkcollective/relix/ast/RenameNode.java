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
import java.util.Optional;

/**
 * Rename (ρ) — renames a relation and optionally reassigns its attribute names.
 *
 * <p>Three column-naming forms, distinguished at parse time:
 * <ul>
 *   <li><b>relation-only</b> — {@code ρ E (Employees)}: rename the relation label,
 *       leave every column untouched. Both {@link #attributes()} and
 *       {@link #pairs()} are empty.</li>
 *   <li><b>positional</b> — {@code ρ E (id, name) (Employees)}: rename the columns
 *       positionally; the list must match the input arity exactly. Carried in
 *       {@link #attributes()}.</li>
 *   <li><b>pairs</b> — {@code ρ E (name → room_name, id → room_id) (Employees)}:
 *       rename only the listed columns ({@code old → new}); unlisted columns pass
 *       through unchanged. Carried in {@link #pairs()}. This is the textbook
 *       ρ_{a→b}(R) form.</li>
 * </ul>
 *
 * <p>The positional and pair forms are mutually exclusive — a single rename may
 * not mix bare names and {@code old → new} pairs.
 *
 * <p>The new relation name is optional: {@code ρ (name → room_name) (Employees)}
 * renames columns without re-anchoring the relation label, so surviving columns
 * keep their existing origin qualifier. When present, the relation name
 * re-anchors every output column's provenance to it (this is what makes a
 * self-join distinguishable).
 *
 * @param relationName the new relation name; empty for a column-only rename, and
 *                     when present must not be blank
 * @param attributes   positional new attribute names (arity-checked); empty
 *                     unless this is the positional form
 * @param pairs        {@code old → new} column renames; empty unless this is the
 *                     pair form
 * @param input        the source relation; must not be null
 * @param location     the source location of this node; never null
 */
public record RenameNode(Optional<String> relationName, List<String> attributes,
                         List<RenamePair> pairs, RelNode input, SourceLocation location)
        implements RelNode {

    /**
     * A single {@code old → new} column rename within the pair form.
     *
     * @param from the existing (source) column name; must not be blank
     * @param to   the new column name; must not be blank
     */
    public record RenamePair(String from, String to) {
        public RenamePair {
            requireNonBlank(from, "from");
            requireNonBlank(to, "to");
        }
    }

    public RenameNode {
        Objects.requireNonNull(relationName, "relationName");
        relationName.ifPresent(name -> requireNonBlank(name, "relationName"));
        attributes = List.copyOf(attributes);
        pairs = List.copyOf(pairs);
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(location, "location");
        if (!attributes.isEmpty() && !pairs.isEmpty()) {
            throw new IllegalArgumentException(
                    "rename cannot mix positional attribute names and old→new pairs");
        }
        if (relationName.isEmpty() && attributes.isEmpty() && pairs.isEmpty()) {
            throw new IllegalArgumentException(
                    "rename must supply a new relation name, positional attributes, or rename pairs");
        }
    }

    /**
     * Convenience constructor for the relation-and-positional-columns form (a new
     * relation name plus a possibly-empty positional attribute list). Preserves the
     * original {@code (String, List, RelNode, SourceLocation)} signature so existing
     * call sites are unchanged.
     */
    public RenameNode(String relationName, List<String> attributes, RelNode input, SourceLocation location) {
        this(Optional.of(relationName), attributes, List.of(), input, location);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public RenameNode(String relationName, List<String> attributes, RelNode input) {
        this(relationName, attributes, input, SourceLocation.UNKNOWN);
    }

    /** True iff this rename reassigns any column names (positional or pair form). */
    public boolean renamesColumns() {
        return !attributes.isEmpty() || !pairs.isEmpty();
    }

    /** Returns a copy of this node over {@code newInput}, preserving all rename metadata. */
    public RenameNode withInput(RelNode newInput) {
        return new RenameNode(relationName, attributes, pairs, newInput, location);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

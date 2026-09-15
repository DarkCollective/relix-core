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

import java.util.Objects;

/**
 * Utilities for working with possibly relation-qualified attribute names.
 *
 * <p>An attribute name may carry a relation qualifier separated by a {@code .},
 * for example {@code Users.id}. The qualifier is everything up to and including
 * the last {@code .}; the column name is everything after it.
 */
public final class AttributeNames {

    private AttributeNames() {
    }

    /**
     * Strips any relation qualifier from an attribute name, returning the bare
     * column name: {@code Users.id} yields {@code id}, and a bare {@code id} is
     * returned unchanged. The qualifier is everything up to and including the
     * last {@code .}.
     *
     * @param name a possibly-qualified attribute name; must not be null
     * @return the unqualified column name
     */
    public static String stripQualifier(String name) {
        Objects.requireNonNull(name, "name");
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /**
     * Returns the relation qualifier of an attribute name, or {@code null} if it
     * carries none: {@code rooms.name} yields {@code rooms}, and a bare
     * {@code name} yields {@code null}. The qualifier is everything up to (but not
     * including) the last {@code .}.
     *
     * @param name a possibly-qualified attribute name; must not be null
     * @return the relation qualifier, or {@code null} if the name is unqualified
     */
    public static String qualifierOf(String name) {
        Objects.requireNonNull(name, "name");
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : null;
    }

    /**
     * Returns the <em>head</em> of an attribute name — everything before the first
     * {@code .}, or the whole name when it has none: {@code location.city} and
     * {@code location.geo.lat} both yield {@code location}.
     *
     * <p>This is the column a name denotes under the <strong>struct-path</strong>
     * reading, and it is the other half of a genuine ambiguity. A dotted name may be
     * a relation-qualified reference ({@code Orders.amount} — the column {@code amount}
     * coming from {@code Orders}, whose column part is the <em>tail</em>, see
     * {@link #stripQualifier}) or a path into a nested column ({@code location.city} —
     * the field {@code city} of the column {@code location}, whose column part is the
     * head). The two readings are told apart against a schema, by
     * {@code Schema.resolvePath}, and never by the spelling alone.
     *
     * <p>Reach for this wherever a decision is about <em>which column a reference
     * touches</em> — a rewrite that moves an operator past the column, say. Using the
     * qualifier reading alone there is the mistake it exists to prevent: the head of
     * {@code skills.years} is {@code skills}, and a rule that only inspected the tail
     * {@code years} would conclude the reference has nothing to do with a
     * {@code skills} column.
     *
     * @param name a possibly-qualified or dotted attribute name; must not be null
     * @return the name's head — the column it names as a path
     */
    public static String pathHead(String name) {
        Objects.requireNonNull(name, "name");
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Whether {@code name} is a path <em>into</em> {@code column} — dotted, with
     * {@code column} as its head: {@code skills.years} is a path into {@code skills},
     * while {@code skills} and {@code Orders.skills} are not.
     *
     * @param name   a possibly-qualified or dotted attribute name; must not be null
     * @param column the column to test against; must not be null
     * @return whether the name reads a field beneath that column
     */
    public static boolean isPathInto(String name, String column) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(column, "column");
        int dot = name.indexOf('.');
        return dot > 0 && name.regionMatches(true, 0, column, 0, dot) && dot == column.length();
    }
}

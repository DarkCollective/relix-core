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
package com.darkcollective.relix.cost;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Logical properties of a relation, derived bottom-up over a
 * {@link com.darkcollective.relix.ast.RelNode} tree and consumed by the optimizer
 * and planner.
 *
 * <p>This first increment carries <strong>distinctness</strong> — whether the
 * relation is guaranteed free of duplicate rows — backed by a key model:
 * <ul>
 *   <li>{@link #wholeRowDistinct()} — the entire row is unique (the relation is a
 *       set), without enumerating the columns. Produced by operators whose output
 *       is a set regardless of input (δ, ∪, ∩, −, ∆, ÷, transitive closure).</li>
 *   <li>{@link #keys()} — explicit <em>candidate keys</em>, each a set of column
 *       names on which rows are unique (an empty key set means "at most one row").
 *       Produced where the unique columns are known structurally (a grouping
 *       aggregation's keys, a group-wise ∀'s keys).</li>
 * </ul>
 *
 * <p>A relation is {@linkplain #isDuplicateFree() duplicate-free} iff its whole row
 * is distinct or it has any candidate key — the question
 * {@code DIST-001} (redundant {@code δ} elimination) asks.
 *
 * <p>The carrier also records {@link #boundedness}. The
 * distinctness factories default it to {@link Boundedness#BOUNDED} (every relation
 * in the language is finite today); the boundedness-aware
 * {@link PropertyDeriver#derive(com.darkcollective.relix.ast.RelNode,
 * BoundednessSource)} overlays the derived value via {@link #withBoundedness}. A
 * physical ordering descriptor is the remaining Phase-C extension. Column names
 * are compared case-insensitively (stored lowercased), matching
 * {@link com.darkcollective.relix.symbol.Schema}.
 *
 * <p>Instances are immutable value objects.
 */
public final class RelationProperties {

    private static final RelationProperties NONE =
            new RelationProperties(false, Set.of(), Boundedness.BOUNDED);
    private static final RelationProperties WHOLE_ROW =
            new RelationProperties(true, Set.of(), Boundedness.BOUNDED);

    private final boolean wholeRowDistinct;
    private final Set<Set<String>> keys;
    private final Boundedness boundedness;

    private RelationProperties(boolean wholeRowDistinct, Set<Set<String>> keys,
                               Boundedness boundedness) {
        this.wholeRowDistinct = wholeRowDistinct;
        this.keys = keys;
        this.boundedness = boundedness;
    }

    /**
     * Properties of a relation about which nothing is known — not duplicate-free,
     * no candidate keys. The conservative default for leaves and operators that
     * may introduce duplicates.
     *
     * @return the empty properties; never null
     */
    public static RelationProperties none() {
        return NONE;
    }

    /**
     * Properties of a relation whose entire row is guaranteed unique (a set),
     * without enumerating its columns — e.g. the output of {@code δ} or a set
     * operation.
     *
     * @return whole-row-distinct properties; never null
     */
    public static RelationProperties wholeRow() {
        return WHOLE_ROW;
    }

    /**
     * Properties of a relation unique on exactly the given key columns (an empty
     * collection denotes a relation of at most one row).
     *
     * @param columns the key columns; must not be null (may be empty)
     * @return properties carrying that single candidate key; never null
     */
    public static RelationProperties key(Collection<String> columns) {
        Objects.requireNonNull(columns, "columns");
        Set<String> k = new LinkedHashSet<>();
        for (String c : columns) {
            k.add(Objects.requireNonNull(c, "column").toLowerCase(Locale.ROOT));
        }
        return new RelationProperties(false, Set.of(Set.copyOf(k)), Boundedness.BOUNDED);
    }

    /**
     * Returns a copy of these properties with the boundedness replaced — used by
     * the boundedness-aware derivation to overlay the structurally-derived
     * distinctness with a source-derived boundedness.
     *
     * @param newBoundedness the boundedness to set; must not be null
     * @return a copy carrying {@code newBoundedness}; never null
     */
    public RelationProperties withBoundedness(Boundedness newBoundedness) {
        Objects.requireNonNull(newBoundedness, "boundedness");
        return newBoundedness == boundedness
                ? this
                : new RelationProperties(wholeRowDistinct, keys, newBoundedness);
    }

    /**
     * Whether the relation is guaranteed to contain no duplicate rows — it is
     * whole-row distinct or has at least one candidate key.
     *
     * @return {@code true} if the relation is provably duplicate-free
     */
    public boolean isDuplicateFree() {
        return wholeRowDistinct || !keys.isEmpty();
    }

    /**
     * Whether the entire row is known to be unique without an enumerated key.
     *
     * @return {@code true} if the relation is whole-row distinct
     */
    public boolean wholeRowDistinct() {
        return wholeRowDistinct;
    }

    /**
     * The known candidate keys — each an immutable set of lowercased column names
     * on which rows are unique.
     *
     * <p><strong>Staged, not dead</strong>. Today the keys are read only
     * through {@link #isDuplicateFree()}, whose sole caller asks the yes/no question
     * {@code DIST-001} needs; <em>which</em> columns form the key is derived on every
     * derivation and never inspected. That is deliberate rather than accidental: the
     * rules that would consume the column sets — candidate-key survival through π, key
     * propagation through a join, and the set-op idempotence laws — are filed and
     * unshipped, and deriving the keys is what makes writing them a rule rather than a
     * rule plus a new derivation. Dropping the detail to a boolean now would have to be
     * undone by the first of them.
     *
     * <p>The key model that <em>is</em> load-bearing today is the separate
     * {@link com.darkcollective.relix.symbol.RelationStatistics#keys()} — read by
     * {@code StatisticsDistinctnessSource} (a keyed base relation is duplicate-free)
     * and by {@code Planner.indexBacked} (a merge key that prefixes a candidate key is
     * index-backed). The two are not interchangeable: those are <em>declared</em> keys
     * of a base table, these are keys <em>derived</em> through the operator tree.
     *
     * @return an immutable set of candidate keys; never null, possibly empty
     */
    public Set<Set<String>> keys() {
        return keys;
    }

    /**
     * The relation's boundedness — whether it is provably finite.
     *
     * @return the boundedness; never null
     */
    public Boundedness boundedness() {
        return boundedness;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RelationProperties other)) return false;
        return wholeRowDistinct == other.wholeRowDistinct
                && keys.equals(other.keys)
                && boundedness == other.boundedness;
    }

    @Override
    public int hashCode() {
        return Objects.hash(wholeRowDistinct, keys, boundedness);
    }

    @Override
    public String toString() {
        return "RelationProperties[wholeRowDistinct=" + wholeRowDistinct
                + ", keys=" + keys + ", boundedness=" + boundedness + "]";
    }
}

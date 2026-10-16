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

import com.darkcollective.relix.ast.internal.AstEquivalence;
import com.darkcollective.relix.ast.visitor.RelNodeVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * Represents an aggregation operation (γ) in relational algebra.
 * Groups tuples by the {@link GroupingKey grouping keys} and applies aggregate
 * functions.
 *
 * <p>A grouping key is a full {@link Operand} expression, not merely a column
 * reference, so a group may be formed over a derived value — e.g.
 * {@code γ YEAR(occurred_at) → yr, COUNT(id) (Events)} — with the common case
 * {@code γ customer_id, SUM(amount) (Orders)} simply grouping over a bare column.
 *
 * <h2>Grouping sets</h2>
 * <p>SQL's {@code ROLLUP}, {@code CUBE} and {@code GROUPING SETS} group the same rows
 * several ways in one aggregation. Each {@linkplain #groupingSets() grouping set} is a
 * list of indexes into {@link #groupingKeys()}: the rows are grouped by those keys, and
 * every other key is NULL in the rows the set produces. {@code ROLLUP (region, product)}
 * is the three sets {@code (region, product)}, {@code (region)} and {@code ()}. An empty
 * list of sets is the ordinary grouping, by every key at once, and a single set naming
 * every key is normalised to it.
 *
 * <p>The sets are held in a canonical shape, so that a node prints as text that parses
 * back to it: each set lists its indexes ascending, and read in order the sets name the
 * keys first in index order. Every key is in at least one set.
 *
 * @param groupingKeys the keys to group by; the output columns, in this order
 * @param groupingSets the grouping sets, as indexes into {@code groupingKeys}; empty
 *                     for the ordinary grouping by every key
 * @param aggregates   the aggregate functions to apply
 * @param input        the source relation
 * @param location     the source location of this node; never null
 */
public record AggregationNode(
        List<GroupingKey> groupingKeys,
        List<List<Integer>> groupingSets,
        List<AggregateFunction> aggregates,
        RelNode input,
        SourceLocation location
) implements RelNode {
    public AggregationNode {
        Objects.requireNonNull(location, "location");
        groupingSets = canonicalSets(groupingKeys == null ? 0 : groupingKeys.size(),
                Objects.requireNonNull(groupingSets, "groupingSets"));
    }

    /**
     * An ordinary grouping, by every key at once.
     *
     * @param groupingKeys the keys to group by
     * @param aggregates   the aggregate functions to apply
     * @param input        the source relation
     * @param location     the source location of this node; never null
     */
    public AggregationNode(List<GroupingKey> groupingKeys,
                           List<AggregateFunction> aggregates,
                           RelNode input,
                           SourceLocation location) {
        this(groupingKeys, List.of(), aggregates, input, location);
    }

    /**
     * Convenience constructor grouping by bare column names — the classic form,
     * used throughout call sites and tests that group by plain columns. Uses
     * {@link SourceLocation#UNKNOWN}. A {@code null} column list is preserved as a
     * null grouping (ungrouped aggregation).
     */
    public AggregationNode(List<String> groupingColumns,
                           List<AggregateFunction> aggregates,
                           RelNode input) {
        this(groupingColumns == null ? null : GroupingKey.columns(groupingColumns),
                aggregates, input, SourceLocation.UNKNOWN);
    }

    /**
     * This aggregation over another input, with the same keys, grouping sets and
     * aggregates.
     *
     * @param newInput the input to aggregate
     * @return the node over {@code newInput}
     */
    public AggregationNode withInput(RelNode newInput) {
        return new AggregationNode(groupingKeys, groupingSets, aggregates, newInput, location);
    }

    /**
     * This aggregation with other keys and aggregates, keeping its grouping sets and
     * input. The keys replace the current ones position for position.
     *
     * @param keys       the replacement keys, as many as there are now
     * @param aggregates the replacement aggregates
     * @return the node with them
     */
    public AggregationNode withKeysAndAggregates(List<GroupingKey> keys, List<AggregateFunction> aggregates) {
        return new AggregationNode(keys, groupingSets, aggregates, input, location);
    }

    /**
     * Whether this aggregation groups by more than one set of its keys — a
     * {@code ROLLUP}, {@code CUBE} or {@code GROUPING SETS}.
     *
     * @return {@code true} for several grouping sets
     */
    public boolean hasGroupingSets() {
        return !groupingSets.isEmpty();
    }

    /**
     * The grouping sets as they are run: the declared ones, or for an ordinary
     * grouping the one set of every key.
     *
     * @return the sets, each a list of key indexes ascending
     */
    public List<List<Integer>> effectiveGroupingSets() {
        if (hasGroupingSets()) {
            return groupingSets;
        }
        int n = groupingKeys == null ? 0 : groupingKeys.size();
        List<Integer> all = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            all.add(i);
        }
        return List.of(List.copyOf(all));
    }

    /**
     * Whether key {@code index} is in every grouping set, so that no row this node
     * produces has it rolled up to NULL.
     *
     * @param index a key index
     * @return {@code true} when every set groups by it
     */
    public boolean inEverySet(int index) {
        return effectiveGroupingSets().stream().allMatch(set -> set.contains(index));
    }

    /**
     * Whether this node produces a row over an empty input. It does when one of its
     * grouping sets is empty, as an ungrouped aggregate gives its one row of totals.
     *
     * @return {@code true} when an empty input still produces a row
     */
    public boolean producesRowOverEmptyInput() {
        return effectiveGroupingSets().stream().anyMatch(List::isEmpty);
    }

    /**
     * The key a {@code GROUPING(k)} argument names: the key whose expression is the
     * same as {@code argument} wherever each was written, or else, for a bare name,
     * the key whose output column it is.
     *
     * @param argument an argument of {@code GROUPING}
     * @return the key's index, or empty when it names none
     */
    public OptionalInt groupingIndex(Operand argument) {
        return groupingKeys == null ? OptionalInt.empty() : groupingIndex(groupingKeys, argument);
    }

    /**
     * The key of {@code groupingKeys} a {@code GROUPING(k)} argument names, as
     * {@link #groupingIndex(Operand)} finds it.
     *
     * @param groupingKeys the keys of a γ
     * @param argument     an argument of {@code GROUPING}
     * @return the key's index, or empty when it names none
     */
    public static OptionalInt groupingIndex(List<GroupingKey> groupingKeys, Operand argument) {
        for (int i = 0; i < groupingKeys.size(); i++) {
            if (AstEquivalence.equivalent(groupingKeys.get(i).expression(), argument)) {
                return OptionalInt.of(i);
            }
        }
        if (argument instanceof AttributeOperand a) {
            for (int i = 0; i < groupingKeys.size(); i++) {
                if (groupingKeys.get(i).outputName().equalsIgnoreCase(a.name())) {
                    return OptionalInt.of(i);
                }
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Checks the grouping sets and puts them in canonical shape: each set's indexes
     * ascending and distinct, every key in some set, the sets naming the keys first in
     * index order, and a lone set of every key replaced by the ordinary grouping.
     */
    private static List<List<Integer>> canonicalSets(int keyCount, List<List<Integer>> sets) {
        if (sets.isEmpty()) {
            return List.of();
        }
        List<List<Integer>> canonical = new ArrayList<>(sets.size());
        boolean[] named = new boolean[keyCount];
        int next = 0;
        for (List<Integer> set : sets) {
            TreeSet<Integer> sorted = new TreeSet<>();
            for (Integer index : set) {
                if (index == null || index < 0 || index >= keyCount) {
                    throw new IllegalArgumentException("grouping set names key " + index
                            + ", but there are " + keyCount + " keys");
                }
                sorted.add(index);
            }
            for (int index : sorted) {
                if (!named[index]) {
                    if (index != next) {
                        throw new IllegalArgumentException("grouping sets must name the keys first in"
                                + " the order they are listed: key " + next + " before key " + index);
                    }
                    named[index] = true;
                    next++;
                }
            }
            canonical.add(List.copyOf(sorted));
        }
        if (next != keyCount) {
            throw new IllegalArgumentException("grouping key " + next + " is in no grouping set");
        }
        // Every key is in some set, so a lone set is the one of every key.
        if (canonical.size() == 1) {
            return List.of();
        }
        return List.copyOf(canonical);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}

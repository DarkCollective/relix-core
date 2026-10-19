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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds a natural join that matches on more columns than it needs to: one where a
 * smaller set of the shared columns is already a candidate key of an input, so the
 * other shared columns can only remove rows. That is the mark of two tables that
 * happen to share a descriptive column such as {@code name}: {@code devices ⋈ rooms}
 * matches a device to its room only when the device is named like the room.
 *
 * <p>It is a warning, since matching on every shared column is what a natural join
 * means and is sometimes intended. The warning names the matched columns and the
 * {@code USING} join that matches on the key alone.
 *
 * <p>Keys are the collected candidate keys, {@code relix.keys}: exact for an inline
 * table, the primary key for an introspected JDBC table. An input's keys are read
 * through a view and through the operators that keep every row's identity (σ, τ, λ,
 * δ and a rename of the relation alone); any other input has no known key, and an
 * input with none says nothing, so a warning is never a guess.
 */
final class NaturalJoinChecks {

    private NaturalJoinChecks() {}

    /**
     * The warning for a natural join of {@code left} and {@code right}, if it has one.
     *
     * @param left        the left input
     * @param right       the right input
     * @param leftSchema  the left input's heading
     * @param rightSchema the right input's heading
     * @param symbols     the symbol table, to read a relation's statistics and a view's body
     * @param statistics  the collected statistics, by canonical relation name
     * @return the warning message, or empty
     */
    static Optional<String> warning(RelNode left, RelNode right, Schema leftSchema, Schema rightSchema,
                                    SymbolTable symbols, Map<String, RelationStatistics> statistics) {
        Set<String> rightNames = names(rightSchema);
        List<String> matched = leftSchema.columns().stream()
                .map(ColumnDefinition::name)
                .filter(name -> rightNames.contains(lower(name)))
                .toList();
        if (matched.size() < 2) {
            return Optional.empty();
        }
        Set<String> matchedLower = matched.stream().map(NaturalJoinChecks::lower).collect(Collectors.toSet());

        List<Set<String>> keys = new ArrayList<>();
        keys.addAll(keysOf(left, symbols, statistics, new HashSet<>()));
        keys.addAll(keysOf(right, symbols, statistics, new HashSet<>()));
        Optional<Set<String>> narrower = keys.stream()
                .filter(key -> matchedLower.containsAll(key) && key.size() < matchedLower.size())
                .min(Comparator.<Set<String>>comparingInt(Set::size)
                        .thenComparing(key -> !key.stream().allMatch(NaturalJoinChecks::looksLikeId))
                        .thenComparingInt(key -> firstPosition(key, matched)));
        if (narrower.isEmpty()) {
            return Optional.empty();
        }
        List<String> keyColumns = matched.stream().filter(c -> narrower.get().contains(lower(c))).toList();

        Optional<String> leftName = displayName(left);
        Optional<String> rightName = displayName(right);
        String using = "USING (" + String.join(", ", keyColumns) + ")";
        String explicit = leftName.isPresent() && rightName.isPresent()
                ? leftName.get() + " JOIN " + rightName.get() + " " + using
                : "a join " + using;
        String inputs = leftName.isPresent() && rightName.isPresent()
                ? " of " + leftName.get() + " and " + rightName.get() : "";
        return Optional.of("Natural join" + inputs + " matches on " + list(matched) + "; to join on "
                + list(keyColumns) + " alone, write " + explicit);
    }

    /**
     * The candidate keys of {@code node}'s rows, each a set of lower-cased column names;
     * empty when none is known.
     */
    private static List<Set<String>> keysOf(RelNode node, SymbolTable symbols,
                                            Map<String, RelationStatistics> statistics, Set<String> seen) {
        return switch (node) {
            case RelationNode r -> symbols.resolveRelation(r.name())
                    .filter(symbol -> seen.add(symbol.canonicalName()))
                    .map(symbol -> {
                        RelationStatistics stats = statistics.get(symbol.canonicalName());
                        if (stats != null && !stats.keys().isEmpty()) {
                            return stats.keys().stream()
                                    .map(key -> key.stream().map(NaturalJoinChecks::lower)
                                            .collect(Collectors.toUnmodifiableSet()))
                                    .toList();
                        }
                        return symbol instanceof QueryRelationSymbol view
                                ? keysOf(view.body(), symbols, statistics, seen)
                                : List.<Set<String>>of();
                    })
                    .orElse(List.of());
            case SelectionNode s -> keysOf(s.input(), symbols, statistics, seen);
            case SortNode s -> keysOf(s.input(), symbols, statistics, seen);
            case LimitNode l -> keysOf(l.input(), symbols, statistics, seen);
            case DistinctNode d -> keysOf(d.input(), symbols, statistics, seen);
            case RenameNode r when r.attributes().isEmpty() && r.pairs().isEmpty() ->
                    keysOf(r.input(), symbols, statistics, seen);
            default -> List.of();
        };
    }

    /** Whether a column's name reads as an identifier, to prefer it among keys of one size. */
    private static boolean looksLikeId(String column) {
        return column.equals("id") || column.endsWith("_id") || column.endsWith("id")
                || column.equals("key") || column.endsWith("_key")
                || column.equals("code") || column.endsWith("_code") || column.endsWith("_no");
    }

    private static int firstPosition(Set<String> key, List<String> matched) {
        for (int i = 0; i < matched.size(); i++) {
            if (key.contains(lower(matched.get(i)))) {
                return i;
            }
        }
        return matched.size();
    }

    private static String list(List<String> columns) {
        if (columns.size() == 1) {
            return columns.getFirst();
        }
        return String.join(", ", columns.subList(0, columns.size() - 1)) + " and " + columns.getLast();
    }

    private static Set<String> names(Schema schema) {
        return schema.columns().stream().map(c -> lower(c.name())).collect(Collectors.toSet());
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** The name an input answers to, when it answers to exactly one. */
    private static Optional<String> displayName(RelNode node) {
        return switch (node) {
            case RelationNode r -> Optional.of(r.name());
            case RenameNode r when r.relationName().isPresent() -> r.relationName();
            default -> node.children().size() == 1
                    ? displayName(node.children().getFirst())
                    : Optional.empty();
        };
    }
}

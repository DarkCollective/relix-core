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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A single grouping key of an aggregation ({@code γ}): the {@link Operand}
 * expression the rows are grouped by, with an optional alias for the output
 * column.
 *
 * <p>The expression is a full scalar expression, not merely a column reference,
 * so a group may be formed over a derived value — e.g.
 * {@code γ YEAR(occurred_at) → yr, COUNT(id) → n (Events)} groups by the parsed
 * year.  The common case {@code γ customer_id, SUM(amount) (Orders)} is simply a
 * grouping over an {@link AttributeOperand}.
 *
 * <p>Examples:
 * <ul>
 *   <li>Bare column: {@code customer_id}</li>
 *   <li>Aliased column: {@code customer_id → cust}</li>
 *   <li>Derived key: {@code DATE_TRUNC('day', ts) → day}</li>
 * </ul>
 *
 * @param expression the expression the rows are grouped by; never null
 * @param alias      the optional output column name
 */
public record GroupingKey(Operand expression, Optional<String> alias) {
    public GroupingKey {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(alias, "alias");
        alias.ifPresent(a -> {
            if (a.isBlank()) {
                throw new IllegalArgumentException("alias cannot be blank");
            }
        });
    }

    // ── Factories ───────────────────────────────────────────────────────────────

    /** A grouping key over a bare column, without an alias. */
    public static GroupingKey column(String attribute) {
        return new GroupingKey(new AttributeOperand(attribute), Optional.empty());
    }

    /** A grouping key over an arbitrary expression, without an alias. */
    public static GroupingKey of(Operand expression) {
        return new GroupingKey(expression, Optional.empty());
    }

    /** A grouping key over an arbitrary expression, with an alias. */
    public static GroupingKey aliased(Operand expression, String alias) {
        return new GroupingKey(expression, Optional.of(alias));
    }

    /** Wraps a list of bare column names as grouping keys (order preserved). */
    public static List<GroupingKey> columns(List<String> attributes) {
        return attributes.stream().map(GroupingKey::column).toList();
    }

    // ── Derived naming / classification ──────────────────────────────────────────

    /**
     * The name of the output column this grouping key produces: the
     * {@link #alias()} when present, otherwise the (unqualified) attribute name
     * for a bare column, the function name for a function call, or the synthetic
     * {@code group} for any other expression.  Schema inference and the optimizer
     * use this so the produced column name is consistent everywhere.
     *
     * @return the output column name; never blank
     */
    public String outputName() {
        return alias.orElseGet(() -> switch (expression) {
            case AttributeOperand a -> a.unqualifiedName();
            case FunctionCall fn    -> fn.functionName();
            default                 -> "group";
        });
    }

    /**
     * The input column name this key groups by, iff the key is a bare attribute
     * reference (the alias, which only renames the output, is irrelevant here);
     * empty for any derived expression.  Statistics lookups, streaming-γ gating,
     * and SQL {@code GROUP BY} pushdown use this to distinguish a plain column
     * from a computed key.
     *
     * @return the unqualified column name, or empty for an expression key
     */
    public Optional<String> columnName() {
        return expression instanceof AttributeOperand a
                ? Optional.of(a.unqualifiedName())
                : Optional.empty();
    }

    /**
     * Whether this key is a bare, unaliased column reference — the classic
     * grouping form whose output column equals the input column.
     *
     * @return {@code true} if the key is an unaliased {@link AttributeOperand}
     */
    public boolean isPlainColumn() {
        return expression instanceof AttributeOperand && alias.isEmpty();
    }

    /**
     * The input columns of {@code keys}, iff <em>every</em> key is a bare
     * attribute reference; empty if any key is a derived expression.  Used where
     * a grouping can only be reasoned about (ordering-based streaming, GROUP BY
     * pushdown) when it is entirely over plain columns.
     *
     * @param keys the grouping keys; must not be null
     * @return the ordered column names, or empty if any key is an expression
     */
    public static Optional<List<String>> plainColumns(List<GroupingKey> keys) {
        List<String> cols = new java.util.ArrayList<>(keys.size());
        for (GroupingKey key : keys) {
            Optional<String> col = key.columnName();
            if (col.isEmpty()) {
                return Optional.empty();
            }
            cols.add(col.get());
        }
        return Optional.of(List.copyOf(cols));
    }
}

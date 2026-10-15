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
import java.util.Optional;

/**
 * A single sort key: the {@link Operand} expression to order by, the direction
 * ({@link SortDirection#ASC} / {@link SortDirection#DESC}), and where its
 * {@code NULL}s go.
 *
 * <p>The expression is a full scalar expression, not merely a column reference,
 * so a relation may be ordered by a derived value — e.g.
 * {@code τ to_timestamp(logged) DESC (RawLogs)}.  The common case
 * {@code τ name (Users)} is simply an ordering by a bare {@link AttributeOperand}.
 *
 * <p>{@code nulls} is where a missing value lands in the output, whichever way the key
 * runs: {@link NullPlacement#NULLS_LAST}, the default, puts NULLs after every value
 * ascending and descending alike, and {@link NullPlacement#NULLS_FIRST} before them.
 * SQL's {@code NULLS FIRST} and {@code NULLS LAST} set it; a key written without either
 * holds {@code NULLS_LAST}, so writing the default out builds an equal key.
 *
 * @param expression the value to order by; never null
 * @param direction  ascending or descending; never null
 * @param nulls      where NULLs land in the output; never null
 */
public record SortSpecification(Operand expression, SortDirection direction, NullPlacement nulls) {
    public SortSpecification {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(nulls, "nulls");
    }

    /**
     * A sort key with NULLs last, the default placement.
     *
     * @param expression the value to order by; never null
     * @param direction  ascending or descending; never null
     */
    public SortSpecification(Operand expression, SortDirection direction) {
        this(expression, direction, NullPlacement.NULLS_LAST);
    }

    /**
     * Convenience constructor for a sort key over a bare column — wraps
     * {@code attribute} as an {@link AttributeOperand}. Keeps the many call sites
     * (and tests) that sort by a plain column name unchanged.
     */
    public SortSpecification(String attribute, SortDirection direction) {
        this(new AttributeOperand(attribute), direction);
    }

    /**
     * The input column name this key orders by, iff the key is a bare attribute
     * reference; empty for a derived expression. Ordering-property reasoning and
     * {@code ORDER BY} pushdown use this to distinguish a plain column from a
     * computed key.
     *
     * @return the unqualified column name, or empty for an expression key
     */
    public Optional<String> columnName() {
        return expression instanceof AttributeOperand a
                ? Optional.of(a.unqualifiedName())
                : Optional.empty();
    }

    /**
     * This sort key as Relix text, as a {@code τ} writes it: the expression, then
     * {@code DESC} for a descending key and {@code NULLS FIRST} for one that puts its
     * NULLs first. The same as
     * {@link #render(Spelling) render(Spelling.GLYPHS)}.
     *
     * @return the key as {@code .relix} text, such as {@code price * qty DESC}; never null
     * @since 1.0
     */
    public String render() {
        return render(Spelling.GLYPHS);
    }

    /**
     * This sort key as Relix text, as a {@code τ} writes it: the expression as
     * {@link Operand#prettyPrint(Spelling)} renders it, then {@code DESC} for a
     * descending key and {@code NULLS FIRST} for one that puts its NULLs first. An ascending key is written without a direction, which is
     * what a {@code τ} reads as ascending, so {@code SORT <key> (T)} parses back to
     * an equal key.
     *
     * @param spelling glyphs or ASCII keywords for a condition the expression holds;
     *                 must not be null
     * @return the key as {@code .relix} text, such as {@code price * qty DESC}; never null
     * @since 1.0
     */
    public String render(Spelling spelling) {
        String expr = expression.prettyPrint(spelling);
        String key = direction == SortDirection.DESC ? expr + " DESC" : expr;
        return nulls == NullPlacement.NULLS_FIRST ? key + " NULLS FIRST" : key;
    }

    /**
     * This key with {@code expression} in place of its own, keeping its direction and
     * NULL placement.
     *
     * @param expression the value to order by; never null
     * @return the key
     * @since 1.0
     */
    public SortSpecification withExpression(Operand expression) {
        return new SortSpecification(expression, direction, nulls);
    }

    /**
     * Where NULLs go in this key's <em>ascending</em> comparator, the one a descending
     * key reverses: {@link #nulls()} for an ascending key, and the other placement for a
     * descending one, so that reversing it lands NULLs where {@link #nulls()} says.
     *
     * <p>A comparator built from it and then reversed for {@link SortDirection#DESC}
     * orders exactly as this key does; the sort executor and the merge-join comparator
     * both build theirs that way.
     *
     * @return the placement before the direction is applied; never null
     */
    public NullPlacement nullPlacement() {
        if (direction == SortDirection.ASC) {
            return nulls;
        }
        return nulls == NullPlacement.NULLS_LAST ? NullPlacement.NULLS_FIRST : NullPlacement.NULLS_LAST;
    }
}

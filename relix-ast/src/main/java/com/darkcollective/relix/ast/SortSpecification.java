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
 * ({@link SortDirection#ASC} / {@link SortDirection#DESC}), and the implicit
 * {@code NULL} placement.
 *
 * <p>The expression is a full scalar expression, not merely a column reference,
 * so a relation may be ordered by a derived value — e.g.
 * {@code τ to_timestamp(logged) DESC (RawLogs)}.  The common case
 * {@code τ name (Users)} is simply an ordering by a bare {@link AttributeOperand}.
 *
 * <p>Null placement is not a surface-syntax option — the parser does not accept
 * {@code NULLS FIRST}/{@code NULLS LAST}, so a specification carries no explicit
 * placement; {@link #nullPlacement()} gives the SQL-standard default that matches
 * the direction.
 */
public record SortSpecification(Operand expression, SortDirection direction) {
    public SortSpecification {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(direction, "direction");
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
     * The effective {@link NullPlacement} for this sort key.  Returns the
     * SQL-standard default: {@link NullPlacement#NULLS_LAST} for
     * {@link SortDirection#ASC} and {@link NullPlacement#NULLS_FIRST} for
     * {@link SortDirection#DESC}.
     *
     * <p>This matches the existing sort-executor behaviour, and is used by the
     * merge-join comparator to guarantee consistent
     * {@code NULL} ordering on both merge inputs.
     *
     * @return the effective null placement; never null
     */
    public NullPlacement nullPlacement() {
        return direction == SortDirection.ASC ? NullPlacement.NULLS_LAST : NullPlacement.NULLS_FIRST;
    }
}

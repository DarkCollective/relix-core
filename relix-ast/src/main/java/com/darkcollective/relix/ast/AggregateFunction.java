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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents an aggregate function in an aggregation operation.
 * An aggregate function applies an operator (SUM, AVG, COUNT, MIN, MAX, COLLECT,
 * ARGMAX, ARGMIN) to an {@link Operand} {@code argument}, with an optional alias
 * for the result.
 *
 * <p>The argument is a full scalar expression, not merely a column reference, so
 * an aggregate may be computed over a derived value — e.g. {@code SUM(price * qty)}
 * or {@code MIN(Abs(delta))}.  The common case {@code SUM(amount)} is simply an
 * aggregate over an {@link AttributeOperand}.
 *
 * <p>The {@code ARGMAX}/{@code ARGMIN} operators additionally carry a
 * {@link #yieldExpr() yield expression}: {@code ARGMAX(rank, yield)} ranks rows by
 * {@code argument} and returns the {@code yieldExpr} value of the extremal row.
 * For every other operator the yield expression is empty.
 *
 * <p>Examples:
 * <ul>
 *   <li>Simple aggregate: {@code SUM(salary)}</li>
 *   <li>Derived aggregate: {@code SUM(price * qty)}, {@code MIN(Abs(delta))}</li>
 *   <li>Aliased aggregate: {@code SUM(salary) → total_salary}</li>
 *   <li>Argmax: {@code ARGMAX(amount, order_id) → biggest_order}</li>
 *   <li>Distinct: {@code COUNT(DISTINCT session) → sessions}</li>
 * </ul>
 *
 * <p>A {@linkplain #distinct() distinct} aggregate reads each distinct value of its
 * argument once per group, as SQL's {@code COUNT(DISTINCT x)} does. It is not
 * decomposable: a distinct count is not the sum of partial distinct counts.
 *
 * @param operator  the aggregate operator
 * @param argument  the expression the operator reads (the ranking expression for
 *                  ARGMAX/ARGMIN); never null
 * @param yieldExpr for ARGMAX/ARGMIN, the expression whose value is returned at the
 *                  extremal row; empty for every other operator
 * <p>An aggregate whose result depends on the order it reads its rows in —
 * {@code STRING_AGG}, {@code COLLECT} — may carry an {@linkplain #orderBy() ordering}:
 * the group's rows are read in that order, as SQL's {@code STRING_AGG(x, ', ' ORDER BY y)}
 * reads them. Every other aggregate has none.
 *
 * @param alias     the optional output column name
 * @param distinct  whether the aggregate reads each distinct argument value once
 * @param orderBy   the order the group's rows are read in; empty for the order they arrive in
 */
public record AggregateFunction(
        AggregateOperator operator,
        Operand argument,
        Optional<Operand> yieldExpr,
        Optional<String> alias,
        boolean distinct,
        List<SortSpecification> orderBy
) {
    public AggregateFunction {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(argument, "argument");
        Objects.requireNonNull(yieldExpr, "yieldExpr");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(orderBy, "orderBy");
        alias.ifPresent(a -> {
            if (a.isBlank()) {
                throw new IllegalArgumentException("alias cannot be blank");
            }
        });
        if (distinct && (operator == AggregateOperator.ARGMAX || operator == AggregateOperator.ARGMIN)) {
            throw new IllegalArgumentException(
                    operator + " ranks rows and has no distinct form");
        }
        if (operator == AggregateOperator.GROUPING
                && (distinct || yieldExpr.isPresent() || !orderBy.isEmpty())) {
            throw new IllegalArgumentException("GROUPING names grouping keys and takes nothing else");
        }
        orderBy = List.copyOf(orderBy);
    }

    /** An aggregate read in the order its rows arrive in. */
    public AggregateFunction(AggregateOperator operator, Operand argument,
                             Optional<Operand> yieldExpr, Optional<String> alias, boolean distinct) {
        this(operator, argument, yieldExpr, alias, distinct, List.of());
    }

    /**
     * An aggregate over every value of its argument, as written without
     * {@code DISTINCT}.
     */
    public AggregateFunction(AggregateOperator operator, Operand argument,
                             Optional<Operand> yieldExpr, Optional<String> alias) {
        this(operator, argument, yieldExpr, alias, false);
    }


    // ── String-based factories (wrap a column name as an AttributeOperand) ──────

    /**
     * Creates an aggregate over a bare column, without an alias.
     */
    public static AggregateFunction simple(AggregateOperator operator, String attribute) {
        return new AggregateFunction(operator, new AttributeOperand(attribute),
                Optional.empty(), Optional.empty());
    }

    /**
     * Creates an aggregate over a bare column, with an alias.
     */
    public static AggregateFunction aliased(AggregateOperator operator, String attribute, String alias) {
        return new AggregateFunction(operator, new AttributeOperand(attribute),
                Optional.empty(), Optional.of(alias));
    }

    /**
     * Creates an argmax/argmin-style aggregate that ranks by column {@code attribute}
     * and returns column {@code yieldColumn}, without an alias.
     *
     * @param operator    {@link AggregateOperator#ARGMAX} or {@link AggregateOperator#ARGMIN}
     * @param attribute   the ranking column
     * @param yieldColumn the column to return at the extremal row
     * @return the aggregate function
     */
    public static AggregateFunction arg(AggregateOperator operator, String attribute, String yieldColumn) {
        return new AggregateFunction(operator, new AttributeOperand(attribute),
                Optional.of(new AttributeOperand(yieldColumn)), Optional.empty());
    }

    /**
     * Creates an argmax/argmin-style aggregate over bare columns, with an alias.
     *
     * @param operator    {@link AggregateOperator#ARGMAX} or {@link AggregateOperator#ARGMIN}
     * @param attribute   the ranking column
     * @param yieldColumn the column to return at the extremal row
     * @param alias       the output column name
     * @return the aggregate function
     */
    public static AggregateFunction argAliased(AggregateOperator operator, String attribute,
                                               String yieldColumn, String alias) {
        return new AggregateFunction(operator, new AttributeOperand(attribute),
                Optional.of(new AttributeOperand(yieldColumn)), Optional.of(alias));
    }

    // ── Operand-based factories ─────────────────────────────────────────────────

    /**
     * Creates an aggregate over an arbitrary expression, without an alias.
     */
    public static AggregateFunction of(AggregateOperator operator, Operand argument) {
        return new AggregateFunction(operator, argument, Optional.empty(), Optional.empty());
    }

    /**
     * Creates an aggregate over an arbitrary expression, with an alias.
     */
    public static AggregateFunction aliased(AggregateOperator operator, Operand argument, String alias) {
        return new AggregateFunction(operator, argument, Optional.empty(), Optional.of(alias));
    }

    /**
     * SQL's {@code GROUPING(k, …)}: which of {@code keys} a row has rolled up, one bit
     * each, the last the lowest.
     *
     * @param keys  the grouping keys it reports on, as written in the grouping
     * @param alias the output column name
     * @return the aggregate function
     */
    public static AggregateFunction grouping(List<Operand> keys, String alias) {
        return new AggregateFunction(AggregateOperator.GROUPING,
                new ArrayConstruction(keys, SourceLocation.UNKNOWN), Optional.empty(), Optional.of(alias));
    }

    /**
     * The keys a {@link AggregateOperator#GROUPING GROUPING} reports on, in the order
     * written; empty for every other operator.
     *
     * @return the keys
     */
    public List<Operand> groupingArguments() {
        if (operator != AggregateOperator.GROUPING) {
            return List.of();
        }
        return argument instanceof ArrayConstruction keys ? keys.elements() : List.of(argument);
    }

    // ── Derived output naming ────────────────────────────────────────────────────

    /**
     * The name of the output column this aggregate produces: the {@link #alias()}
     * when present, otherwise a synthetic {@code operator_argument} name (e.g.
     * {@code sum_amount}, {@code min_Abs}, or {@code sum_expr} for a complex
     * expression).  Schema inference and the optimizer use this so the produced
     * column name is consistent everywhere.
     *
     * @return the output column name; never blank
     */
    public String outputName() {
        return alias.orElseGet(() ->
                operator.name().toLowerCase(Locale.ROOT) + "_" + argumentName());
    }

    /**
     * A short name for {@link #argument()}, mirroring projection column naming:
     * a bare attribute uses its (unqualified) name, a function call uses the
     * function name, and any other expression uses {@code "expr"}.
     */
    private String argumentName() {
        // GROUPING(region) is named for its key, as SUM(region) would be.
        Operand named = groupingArguments().size() == 1 ? groupingArguments().getFirst() : argument;
        return switch (named) {
            case AttributeOperand a -> a.unqualifiedName();
            case FunctionCall fn -> fn.functionName();
            default              -> "expr";
        };
    }
}

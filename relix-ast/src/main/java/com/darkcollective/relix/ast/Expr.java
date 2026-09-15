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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * The readable spelling of {@link AstBuilders} — comparisons by name, n-ary
 * connectives, and literals built from <strong>Java values</strong> rather than
 * from their source spelling.
 *
 * <p>{@code AstBuilders} follows one rule: a factory takes the record's own
 * components, in the record's own order. That rule is what keeps it honest, and it
 * is also what makes a hand-written expression verbose — a comparison names an
 * enum constant, and a number literal is a {@code String} because a
 * {@link NumberOperand} stores the digits as written. This class is the layer that
 * reads well at a call site, built entirely out of those same factories, so there
 * is one set of nodes and no second authority:
 *
 * {@snippet lang = "java":
 * // AstBuilders — the record's own shape
 * cmp(attr("id"), ComparisonOperator.EQUAL, num("42"))
 *
 * // Expr — the same node
 * eq(attr("id"), num(42))
 * }
 *
 * <h2>This is how a value is bound to a query</h2>
 *
 * <p>The question every embedder asks first is how to put a value into a query
 * safely. The answer is that a value never becomes text at all:
 *
 * {@snippet lang = "java":
 * // Never necessary, and this is the shape that invents an injection surface
 * // where the language never had one:
 * //     "σ id = " + orderId + " (Orders)"
 *
 * // The value becomes a NumberOperand node, and nothing is parsed:
 * select(eq(attr("id"), num(orderId)), rel("Orders"))
 * }
 *
 * <p>That is why {@link #num(long)}, {@link #lit(Object)} and the
 * {@code java.time} overloads exist: a caller holds a {@code long}, an
 * {@code Instant} or a {@code BigDecimal}, and formatting one into a string so a
 * builder can parse it back is exactly the round trip this surface removes.
 *
 * <p>Extends {@link AstBuilders}, so one {@code extends Expr} — or one static
 * import — reaches every factory as well as everything here.
 *
 * @see AstBuilders
 */
public abstract class Expr extends AstBuilders {

    // -------------------------------------------------------------------------
    // Literals from Java values
    // -------------------------------------------------------------------------

    /**
     * A numeric literal from a Java integer value.
     *
     * @param value the value
     * @return the operand
     */
    public static NumberOperand num(long value) {
        return new NumberOperand(Long.toString(value));
    }

    /**
     * A numeric literal from a Java floating-point value.
     *
     * <p>Rendered through {@link BigDecimal#valueOf(double)} with trailing zeros
     * stripped, so the literal reads as the shortest decimal that round-trips rather
     * than as the binary expansion — and {@code 5.0} spells {@code 5}, since
     * {@code Double.toString} keeps a trailing zero the value does not carry. Use
     * {@link #num(BigDecimal)} where the scale is itself meaningful.
     *
     * @param value the value
     * @return the operand
     */
    public static NumberOperand num(double value) {
        return new NumberOperand(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
    }

    /**
     * A numeric literal from an exact decimal value.
     *
     * @param value the value; must not be null
     * @return the operand
     */
    public static NumberOperand num(BigDecimal value) {
        return new NumberOperand(value.toPlainString());
    }

    /**
     * A date literal from a {@link LocalDate}.
     *
     * @param value the date; must not be null
     * @return the operand
     */
    public static DateOperand date(LocalDate value) {
        return new DateOperand(value);
    }

    /**
     * A time literal from a {@link LocalTime}.
     *
     * @param value the time; must not be null
     * @return the operand
     */
    public static TimeOperand time(LocalTime value) {
        return new TimeOperand(value);
    }

    /**
     * A timestamp literal from an {@link Instant}.
     *
     * @param value the instant; must not be null
     * @return the operand
     */
    public static TimestampOperand timestamp(Instant value) {
        return new TimestampOperand(value);
    }

    /**
     * A duration literal from a {@link Duration}.
     *
     * @param value the duration; must not be null
     * @return the operand
     */
    public static DurationOperand duration(Duration value) {
        return new DurationOperand(value);
    }

    /**
     * The literal operand for a Java value, chosen by its runtime type.
     *
     * <p>For binding a value whose type is only known at runtime — a parameter map,
     * a UI field, a row read from somewhere else. When the type <em>is</em> known,
     * the specific factory says more at the call site.
     *
     * <p>{@code null} becomes a {@link NullPredicate}'s business rather than an
     * operand: there is no null literal in the operand hierarchy, so it is rejected
     * here instead of silently becoming the string {@code "null"}.
     *
     * @param value the Java value; must not be null
     * @return the matching literal operand
     * @throws IllegalArgumentException if {@code value} is null or has no literal form
     */
    public static Operand lit(Object value) {
        return switch (value) {
            case null -> throw new IllegalArgumentException(
                    "no literal operand for null — use isNull(...) to test for absence");
            case Operand o -> o;
            case String s -> str(s);
            case Boolean b -> bool(b);
            case BigDecimal d -> num(d);
            case Double d -> num((double) d);
            case Float f -> num(f.doubleValue());
            case Number n -> num(n.longValue());
            case LocalDate d -> date(d);
            case LocalTime t -> time(t);
            case Instant i -> timestamp(i);
            case Duration d -> duration(d);
            default -> throw new IllegalArgumentException(
                    "no literal operand for " + value.getClass().getName());
        };
    }

    // -------------------------------------------------------------------------
    // Comparisons — the operator by name
    // -------------------------------------------------------------------------

    /**
     * {@code left = right}.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the predicate
     */
    public static ComparisonPredicate eq(Operand left, Operand right) {
        return cmp(left, ComparisonOperator.EQUAL, right);
    }

    /**
     * {@code left ≠ right}.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the predicate
     */
    public static ComparisonPredicate ne(Operand left, Operand right) {
        return cmp(left, ComparisonOperator.NOT_EQUAL, right);
    }

    /**
     * {@code left < right}.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the predicate
     */
    public static ComparisonPredicate lt(Operand left, Operand right) {
        return cmp(left, ComparisonOperator.LESS, right);
    }

    /**
     * {@code left ≤ right}.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the predicate
     */
    public static ComparisonPredicate le(Operand left, Operand right) {
        return cmp(left, ComparisonOperator.LESS_EQUAL, right);
    }

    /**
     * {@code left > right}.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the predicate
     */
    public static ComparisonPredicate gt(Operand left, Operand right) {
        return cmp(left, ComparisonOperator.GREATER, right);
    }

    /**
     * {@code left ≥ right}.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the predicate
     */
    public static ComparisonPredicate ge(Operand left, Operand right) {
        return cmp(left, ComparisonOperator.GREATER_EQUAL, right);
    }

    // -------------------------------------------------------------------------
    // Connectives — n-ary over the binary nodes
    // -------------------------------------------------------------------------

    /**
     * The conjunction of every given predicate, folded left so it nests the way a
     * parsed {@code a ∧ b ∧ c} does.
     *
     * <p>{@code AstBuilders.and} is strictly binary because {@link AndPredicate} is;
     * this is the n-ary spelling a caller assembling a filter list needs.
     *
     * @param predicates at least one predicate
     * @return the single predicate when one is given, else the left-folded conjunction
     * @throws IllegalArgumentException if no predicate is given
     */
    public static Predicate allOf(Predicate... predicates) {
        return fold(List.of(predicates), true);
    }

    /**
     * The conjunction of every predicate in {@code predicates}, folded left.
     *
     * @param predicates at least one predicate
     * @return the single predicate when one is given, else the left-folded conjunction
     * @throws IllegalArgumentException if the list is empty
     */
    public static Predicate allOf(List<Predicate> predicates) {
        return fold(predicates, true);
    }

    /**
     * The disjunction of every given predicate, folded left.
     *
     * @param predicates at least one predicate
     * @return the single predicate when one is given, else the left-folded disjunction
     * @throws IllegalArgumentException if no predicate is given
     */
    public static Predicate anyOf(Predicate... predicates) {
        return fold(List.of(predicates), false);
    }

    /**
     * The disjunction of every predicate in {@code predicates}, folded left.
     *
     * @param predicates at least one predicate
     * @return the single predicate when one is given, else the left-folded disjunction
     * @throws IllegalArgumentException if the list is empty
     */
    public static Predicate anyOf(List<Predicate> predicates) {
        return fold(predicates, false);
    }

    private static Predicate fold(List<Predicate> predicates, boolean conjunction) {
        if (predicates.isEmpty()) {
            throw new IllegalArgumentException(
                    (conjunction ? "allOf" : "anyOf") + " needs at least one predicate");
        }
        Predicate result = predicates.getFirst();
        for (Predicate next : predicates.subList(1, predicates.size())) {
            result = conjunction ? and(result, next) : or(result, next);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Null tests and membership
    // -------------------------------------------------------------------------

    /**
     * {@code operand IS NULL}.
     *
     * @param operand the operand to test
     * @return the predicate
     */
    public static NullPredicate isNull(Operand operand) {
        return nullPred(operand, true);
    }

    /**
     * {@code operand IS NOT NULL}.
     *
     * @param operand the operand to test
     * @return the predicate
     */
    public static NullPredicate isNotNull(Operand operand) {
        return nullPred(operand, false);
    }

    /**
     * {@code element ∈ {values…}}, with the set literal built for you.
     *
     * @param element the operand tested for membership
     * @param values  the set members
     * @return the predicate
     */
    public static ElementOfPredicate in(Operand element, Operand... values) {
        return elementOf(element, set(values));
    }

    // -------------------------------------------------------------------------
    // Arithmetic — the operator by name
    // -------------------------------------------------------------------------

    /**
     * {@code left + right}.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the expression
     */
    public static BinaryArithmeticExpression plus(Operand left, Operand right) {
        return arith(left, ArithmeticOperator.PLUS, right);
    }

    /**
     * {@code left - right}.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the expression
     */
    public static BinaryArithmeticExpression minus(Operand left, Operand right) {
        return arith(left, ArithmeticOperator.MINUS, right);
    }

    /**
     * {@code left * right}.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the expression
     */
    public static BinaryArithmeticExpression times(Operand left, Operand right) {
        return arith(left, ArithmeticOperator.MULTIPLY, right);
    }

    /**
     * {@code left / right}.
     *
     * @param left  the left operand
     * @param right the right operand
     * @return the expression
     */
    public static BinaryArithmeticExpression dividedBy(Operand left, Operand right) {
        return arith(left, ArithmeticOperator.DIVIDE, right);
    }
}

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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.processor.EvaluationException;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.internal.LikePatterns;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.util.List;
import java.util.Objects;

/**
 * Evaluates a {@link Predicate} against a {@link Row}, returning a boolean result.
 *
 * <p>Handles all seven subtypes of the sealed {@code Predicate} hierarchy:
 * comparison, logical AND/OR/NOT, null tests, set-membership tests, and pattern
 * (LIKE) matches.
 *
 * <p>NULL semantics are SQL's three-valued (Kleene) logic, and the evaluator is
 * three-valued internally: a predicate answers {@link Truth#TRUE},
 * {@link Truth#FALSE} or {@link Truth#UNKNOWN}.
 * <ul>
 *   <li>Any comparison ({@code =}, {@code !=}, {@code <}, …) with a NULL operand
 *       is UNKNOWN — including {@code NULL = NULL}.</li>
 *   <li>{@code NULL ∈ set} is UNKNOWN, and so is {@code x ∈ set} when {@code x}
 *       matches nothing but the set holds a NULL: the answer depends on a value
 *       nobody knows.</li>
 *   <li>A LIKE against a NULL is UNKNOWN.</li>
 *   <li>{@code IS NULL} / {@code IS NOT NULL} are the only predicates that are
 *       never UNKNOWN — asking about the absence itself always has an answer.</li>
 *   <li>{@code ¬UNKNOWN} is UNKNOWN, which is why a row can survive neither a
 *       predicate nor its negation.</li>
 * </ul>
 *
 * <p>{@link #evaluate} answers the only question an operator asks — <em>keep this
 * row?</em> — and keeps a row only when the predicate is TRUE. Every consumer (σ,
 * the joins, ∀, COVER) reads that boolean, so the three-valued detail stays inside
 * this class; the one place a truth value becomes data is a
 * {@code ConditionOperand}, where UNKNOWN surfaces as NULL rather than as false.
 *
 * <p>Three-valued is not a preference, it is the only reading under which the
 * engine agrees with itself. A σ the planner folds into a {@code WHERE} clause is
 * evaluated by the database, and SQL is three-valued; a two-valued engine returns
 * different rows for the same script depending on whether the predicate was pushed
 * down. {@code NOT LIKE} and {@code ∉} were already three-valued at the leaf, so
 * the alternative was not "leave it alone" but "change those too, and guard every
 * renderer". {@code NullSemanticsAgreementTest} (relix-connectors-std) holds the
 * two sides together over a live database.
 *
 * <p>AND and OR still short-circuit, on the value that determines the result on its
 * own: FALSE for AND, TRUE for OR. UNKNOWN determines nothing, so it does not.
 *
 * <p>Whether two values are equal or ordered is not decided here — that is
 * {@link ValueComparator}, which every operator reads, so a predicate means the
 * same thing in a selection as in a join condition. It is where the number rule
 * lives (values of different scale, {@code 1.0} and {@code 1}, compare equal) and
 * where a text cell is read as the boolean or timestamp it spells.
 */
public final class PredicateEvaluator {

    private final OperandEvaluator operandEvaluator;

    /**
     * Constructs a {@code PredicateEvaluator} using the given operand evaluator.
     *
     * @param operandEvaluator the evaluator for scalar expressions; must not be {@code null}
     */
    public PredicateEvaluator(OperandEvaluator operandEvaluator) {
        this.operandEvaluator = Objects.requireNonNull(operandEvaluator, "operandEvaluator");
    }

    /**
     * Evaluates {@code predicate} against the given {@code row}.
     *
     * @param predicate the predicate to evaluate; must not be {@code null}
     * @param row       the current row; must not be {@code null}
     * @return {@code true} if the predicate holds, {@code false} otherwise
     * @throws EvaluationException if operand evaluation fails
     */
    public boolean evaluate(Predicate predicate, Row row) {
        return truth(predicate, row) == Truth.TRUE;
    }

    /**
     * Evaluates {@code predicate} to one of the three truth values.
     *
     * <p>This is the whole of the NULL semantics; {@link #evaluate} is the
     * row-keeping question asked of it.
     *
     * @param predicate the predicate to evaluate; must not be {@code null}
     * @param row       the current row; must not be {@code null}
     * @return TRUE, FALSE or UNKNOWN
     * @throws EvaluationException if operand evaluation fails
     */
    Truth truth(Predicate predicate, Row row) {
        return switch (predicate) {
            case ComparisonPredicate  c -> evaluateComparison(c, row);
            case AndPredicate         a -> and(a, row);
            case OrPredicate          o -> or(o, row);
            case NotPredicate         n -> truth(n.predicate(), row).not();
            case NullPredicate        n -> Truth.of(evaluateNull(n, row));
            case ElementOfPredicate   e -> evaluateElementOf(e, row);
            case PatternPredicate     p -> evaluatePattern(p, row);
        };
    }

    /**
     * Kleene conjunction, short-circuiting on FALSE — the one value that settles an
     * AND on its own. UNKNOWN does not: {@code UNKNOWN ∧ FALSE} is FALSE, so the
     * right side is still needed.
     */
    private Truth and(AndPredicate a, Row row) {
        Truth left = truth(a.left(), row);
        if (left == Truth.FALSE) {
            return Truth.FALSE;
        }
        Truth right = truth(a.right(), row);
        if (right == Truth.FALSE) {
            return Truth.FALSE;
        }
        return left == Truth.UNKNOWN || right == Truth.UNKNOWN ? Truth.UNKNOWN : Truth.TRUE;
    }

    /** Kleene disjunction, short-circuiting on TRUE — the mirror of {@link #and}. */
    private Truth or(OrPredicate o, Row row) {
        Truth left = truth(o.left(), row);
        if (left == Truth.TRUE) {
            return Truth.TRUE;
        }
        Truth right = truth(o.right(), row);
        if (right == Truth.TRUE) {
            return Truth.TRUE;
        }
        return left == Truth.UNKNOWN || right == Truth.UNKNOWN ? Truth.UNKNOWN : Truth.FALSE;
    }

    // ── private evaluation helpers ──────────────────────────────────────────

    private Truth evaluateComparison(ComparisonPredicate pred, Row row) {
        Value left  = operandEvaluator.evaluate(pred.left(),  row);
        Value right = operandEvaluator.evaluate(pred.right(), row);
        if (left.isNull() || right.isNull()) return Truth.UNKNOWN;
        return Truth.of(switch (pred.operator()) {
            case EQUAL         ->  valuesEqual(left, right);
            case NOT_EQUAL     -> !valuesEqual(left, right);
            case LESS          -> compareOrdered(left, right) <  0;
            case LESS_EQUAL    -> compareOrdered(left, right) <= 0;
            case GREATER       -> compareOrdered(left, right) >  0;
            case GREATER_EQUAL -> compareOrdered(left, right) >= 0;
        });
    }

    private boolean evaluateNull(NullPredicate pred, Row row) {
        Value v = operandEvaluator.evaluate(pred.operand(), row);
        return pred.isNull() ? v.isNull() : !v.isNull();
    }

    private Truth evaluatePattern(PatternPredicate pred, Row row) {
        Value operandVal = operandEvaluator.evaluate(pred.operand(), row);
        Value patternVal = operandEvaluator.evaluate(pred.pattern(), row);
        if (operandVal.isNull() || patternVal.isNull()) return Truth.UNKNOWN;
        if (!(operandVal instanceof StringValue sv)) {
            throw new EvaluationException(
                    "LIKE operand must be a string; got " + operandVal.type());
        }
        if (!(patternVal instanceof StringValue pv)) {
            throw new EvaluationException(
                    "LIKE pattern must be a string; got " + patternVal.type());
        }
        Truth matches = Truth.of(likeMatch(sv.value(), pv.value()));
        return pred.negated() ? matches.not() : matches;
    }

    private static boolean likeMatch(String text, String pattern) {
        return text.matches(LikePatterns.toRegex(pattern));
    }

    /**
     * SQL's membership rule, which has two ways to be UNKNOWN: a NULL element, and
     * a NULL <em>in the set</em> when nothing else matched. {@code 3 ∈ {1, NULL}}
     * is not false — the NULL might have been the 3 — so {@code 3 ∉ {1, NULL}} is
     * not true either, and neither keeps the row.
     */
    private Truth evaluateElementOf(ElementOfPredicate pred, Row row) {
        Value element = operandEvaluator.evaluate(pred.element(), row);
        if (element.isNull()) return Truth.UNKNOWN;

        if (!(pred.setExpression() instanceof SetLiteralOperand setLiteral)) {
            throw new EvaluationException(
                    "IN predicate expects a set literal; got "
                    + pred.setExpression().getClass().getSimpleName());
        }
        List<Value> setValues = setLiteral.elements().stream()
                .map(el -> operandEvaluator.evaluate(el, row))
                .toList();

        boolean inSet = setValues.stream()
                .filter(v -> !v.isNull())
                .anyMatch(v -> valuesEqual(element, v));
        Truth member = inSet
                ? Truth.TRUE
                : setValues.stream().anyMatch(Value::isNull) ? Truth.UNKNOWN : Truth.FALSE;
        return pred.isNegated() ? member.not() : member;
    }

    /**
     * SQL's three truth values. UNKNOWN is what a comparison against NULL answers,
     * and it propagates: an operator keeps a row only when the predicate is TRUE, so
     * an UNKNOWN row is dropped by a predicate <em>and</em> by its negation.
     */
    enum Truth {
        TRUE, FALSE, UNKNOWN;

        static Truth of(boolean value) {
            return value ? TRUE : FALSE;
        }

        /** Kleene negation: TRUE and FALSE swap, UNKNOWN is its own negation. */
        Truth not() {
            return switch (this) {
                case TRUE    -> FALSE;
                case FALSE   -> TRUE;
                case UNKNOWN -> UNKNOWN;
            };
        }
    }

    // ── value comparison helpers ────────────────────────────────────────────

    /**
     * Equality and ordering both defer to {@link ValueComparator}, which owns the
     * one coercion rule the engine has. These used to be local copies, and the copy
     * drifted: this one coerced a string against a boolean, the comparator's coerced
     * a string against a temporal, and neither had the other's — so the same
     * predicate written as a selection and as a join gave different answers.
     */
    private static boolean valuesEqual(Value left, Value right) {
        return ValueComparator.equal(left, right);
    }

    private static int compareOrdered(Value left, Value right) {
        return ValueComparator.compareNonNull(left, right);
    }
}

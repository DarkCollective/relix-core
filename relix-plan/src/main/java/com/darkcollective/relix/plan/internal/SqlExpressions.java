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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.function.ScalarFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Translates relix {@link Predicate}s and {@link Operand}s into SQL text for
 * pushdown.  Every method returns {@link Optional#empty()} when the construct has
 * no faithful SQL form (e.g. a scalar {@code FunctionCall}), which the pushdown
 * planner treats as "not pushable" and falls back to in-engine evaluation.
 *
 * <p>How an attribute reference becomes a SQL column is decided by a
 * {@link ColumnRenderer}: single-table pushdown drops any qualifier, while
 * join pushdown maps each reference to an unambiguous {@code alias.column}.  A
 * renderer may itself return empty to reject an unresolvable or ambiguous
 * reference, which propagates up as "not pushable".
 *
 * <p>String literals are single-quoted with embedded quotes doubled.  This keeps
 * the generated SQL portable across the dialects relix targets without a
 * per-dialect literal layer.
 */
public final class SqlExpressions {

    private SqlExpressions() {
    }

    /** Resolves a relix attribute reference to a SQL column expression, or empty if unresolvable. */
    @FunctionalInterface
    interface ColumnRenderer {
        Optional<String> render(String attribute);

        /** A renderer that simply drops any qualifier prefix (single-table pushdown). */
        ColumnRenderer STRIP_QUALIFIER = attribute -> Optional.of(column(attribute));
    }

    /** Translates a predicate to a SQL boolean expression for the generic dialect. */
    static Optional<String> predicate(Predicate predicate, ColumnRenderer cols,
                                      PushdownFunctions functions) {
        return predicate(predicate, cols, Dialect.GENERIC, functions);
    }

    /** Translates a predicate to a SQL boolean expression, or empty if unsupported. */
    static Optional<String> predicate(Predicate predicate, ColumnRenderer cols, Dialect dialect,
                                      PushdownFunctions functions) {
        return predicate(predicate, cols, cols, dialect, functions);
    }

    /**
     * The same, distinguishing the two positions a string can be compared in.
     *
     * <p>{@code cols} renders a reference wherever the backend is asked whether two
     * values are <em>equal</em> — {@code =}, {@code ≠}, {@code IN}, {@code LIKE},
     * {@code IS NULL}. {@code ordering} renders it wherever the backend is asked which
     * of two values is <em>larger</em>, which is the four inequalities.
     *
     * <p>They differ only on a backend whose collation decides those two questions
     * differently, and there is one: Postgres compares equal strings by bytes and orders
     * them by locale, so {@code name = 'ada'} needs no help there and {@code name > 'B'}
     * does. Everywhere else the caller passes the same renderer twice.
     *
     * @param predicate the predicate to render
     * @param cols      how a reference renders in equality position
     * @param ordering  how a reference renders in inequality position
     * @param dialect   the target dialect
     * @param functions the spellings available for a function call
     * @return the SQL, or empty when some part of the predicate has none
     */
    static Optional<String> predicate(Predicate predicate, ColumnRenderer cols,
                                      ColumnRenderer ordering, Dialect dialect,
                                      PushdownFunctions functions) {
        return switch (predicate) {
            case ComparisonPredicate c -> binary(c.left(), sqlOp(c.operator()), c.right(),
                    ordersValues(c.operator()) ? ordering : cols, dialect, functions);
            case AndPredicate a -> combine(a.left(), "AND", a.right(), cols, ordering, dialect, functions);
            case OrPredicate o -> combine(o.left(), "OR", o.right(), cols, ordering, dialect, functions);
            case NotPredicate n -> predicate(n.predicate(), cols, ordering, dialect, functions)
                    .map(p -> "(NOT " + p + ")");
            case NullPredicate n -> operand(n.operand(), cols, dialect, functions)
                    .map(o -> o + (n.isNull() ? " IS NULL" : " IS NOT NULL"));
            case ElementOfPredicate e -> elementOf(e, cols, dialect, functions);
            case PatternPredicate p -> patternLike(p, cols, dialect, functions);
        };
    }

    /** Whether this comparison asks which of two values is larger, rather than whether they are equal. */
    private static boolean ordersValues(ComparisonOperator op) {
        return switch (op) {
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> true;
            case EQUAL, NOT_EQUAL -> false;
        };
    }

    /** Translates an operand to a SQL scalar expression for the generic dialect. */
    static Optional<String> operand(Operand operand, ColumnRenderer cols,
                                    PushdownFunctions functions) {
        return operand(operand, cols, Dialect.GENERIC, functions);
    }

    /** Translates an operand to a SQL scalar expression, or empty if unsupported. */
    static Optional<String> operand(Operand operand, ColumnRenderer cols, Dialect dialect,
                                    PushdownFunctions functions) {
        return switch (operand) {
            case AttributeOperand a -> cols.render(a.name());
            case NumberOperand n -> Optional.of(n.value());
            case StringOperand s -> Optional.of(dialect.stringLiteral(s.value()));
            case BooleanOperand b -> Optional.of(dialect.booleanLiteral(b.value()));
            // Temporal literals render per-dialect (ADR-0013), and a dialect with no
            // date types declines them.
            case DateOperand d -> dialect.dateLiteral(d.value());
            case TimeOperand t -> dialect.timeLiteral(t.value());
            case TimestampOperand ts -> dialect.timestampLiteral(ts.value());
            case DurationOperand d -> dialect.durationLiteral(d.value());
            case UnaryOperand u -> operand(u.operand(), cols, dialect, functions).map(o -> "(-" + o + ")");
            case BinaryArithmeticExpression e -> arithmetic(e, cols, dialect, functions);
            case SetLiteralOperand s -> setLiteral(s, cols, dialect, functions);
            // A function call pushes when the function itself supplies a spelling for
            // this dialect (ADR-0026): the planner knows no function names.
            case FunctionCall f -> functionCall(f, cols, dialect, functions);
            default -> Optional.empty();
        };
    }

    /** Strips any qualifier (text before the last dot) and returns the bare column name. */
    static String column(String name) {
        return AttributeNames.stripQualifier(name);
    }

    /** Returns the qualifier (text before the last dot), or empty if the name is unqualified. */
    static Optional<String> qualifier(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? Optional.of(name.substring(0, dot)) : Optional.empty();
    }

    private static Optional<String> binary(Operand left, String op, Operand right,
                                           ColumnRenderer cols, Dialect dialect,
                                           PushdownFunctions functions) {
        Optional<String> l = operand(left, cols, dialect, functions);
        Optional<String> r = operand(right, cols, dialect, functions);
        if (l.isEmpty() || r.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("(" + l.get() + " " + op + " " + r.get() + ")");
    }

    private static Optional<String> combine(Predicate left, String op, Predicate right,
                                            ColumnRenderer cols, ColumnRenderer ordering,
                                            Dialect dialect, PushdownFunctions functions) {
        Optional<String> l = predicate(left, cols, ordering, dialect, functions);
        Optional<String> r = predicate(right, cols, ordering, dialect, functions);
        if (l.isEmpty() || r.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("(" + l.get() + " " + op + " " + r.get() + ")");
    }

    private static Optional<String> arithmetic(BinaryArithmeticExpression e,
                                               ColumnRenderer cols, Dialect dialect,
                                           PushdownFunctions functions) {
        Optional<String> op = sqlOp(e.operator());
        if (op.isEmpty()) {
            return Optional.empty();
        }
        return binary(e.left(), op.get(), e.right(), cols, dialect, functions);
    }

    private static Optional<String> elementOf(ElementOfPredicate e, ColumnRenderer cols, Dialect dialect,
                                           PushdownFunctions functions) {
        Optional<String> element = operand(e.element(), cols, dialect, functions);
        Optional<String> set = operand(e.setExpression(), cols, dialect, functions);
        if (element.isEmpty() || set.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(element.get() + (e.isNegated() ? " NOT IN " : " IN ") + set.get());
    }

    private static Optional<String> patternLike(PatternPredicate p, ColumnRenderer cols, Dialect dialect,
                                           PushdownFunctions functions) {
        Optional<String> lhs = operand(p.operand(), cols, dialect, functions);
        Optional<String> rhs = operand(p.pattern(), cols, dialect, functions);
        if (lhs.isEmpty() || rhs.isEmpty()) {
            return Optional.empty();
        }
        // The dialect decides the spelling: SQLite's LIKE ignores case, so it matches a
        // literal pattern with GLOB instead, and needs the pattern's text to translate.
        Optional<String> literal = p.pattern() instanceof StringOperand s
                ? Optional.of(s.value()) : Optional.empty();
        return dialect.like(lhs.get(), rhs.get(), literal, p.negated());
    }

    private static Optional<String> setLiteral(SetLiteralOperand set, ColumnRenderer cols, Dialect dialect,
                                           PushdownFunctions functions) {
        StringJoiner joiner = new StringJoiner(", ", "(", ")");
        for (Operand element : set.elements()) {
            Optional<String> sql = operand(element, cols, dialect, functions);
            if (sql.isEmpty()) {
                return Optional.empty();
            }
            joiner.add(sql.get());
        }
        return Optional.of(joiner.toString());
    }

    private static String sqlOp(ComparisonOperator op) {
        return switch (op) {
            case EQUAL -> "=";
            case NOT_EQUAL -> "<>";
            case LESS -> "<";
            case LESS_EQUAL -> "<=";
            case GREATER -> ">";
            case GREATER_EQUAL -> ">=";
        };
    }

    /**
     * This dialect's spelling of an arithmetic operator, or empty where no backend
     * computes what the engine computes.
     *
     * <p><b>Division is declined, on every backend.</b> A relix {@code /} is exact decimal
     * arithmetic to ten places, rounded half-up; no {@code /} the planner can emit answers
     * the same question:
     *
     * <ul>
     *   <li>H2 and PostgreSQL divide two integers as integers, so {@code 81 / 2} is
     *       {@code 40} where the engine says {@code 40.5} — a σ over it returns different
     *       rows, not a differently-rounded number.</li>
     *   <li>MySQL does produce a decimal, and still disagrees: its scale is the operand's
     *       plus {@code div_precision_increment}, four by default, so {@code amount / 3}
     *       comes back {@code 33.3333} against the engine's {@code 33.3333333333}.</li>
     * </ul>
     *
     * <p>A cast would fix the first and not the second — a backend's own decimal scale is
     * a configuration rather than a spelling, and matching ten-place half-up rounding on
     * three backends is not something SQL text can promise. Division by zero is a third
     * disagreement in the same place: the engine raises, PostgreSQL raises, MySQL answers
     * NULL.
     *
     * <p>The whole expression declines with it, so a σ or π containing a division reads
     * the table and finishes here. That is the ordinary cost of a decline, and the reason
     * the renderer is allowed to decline at all: a slower plan, never a wrong answer.
     */
    private static Optional<String> sqlOp(ArithmeticOperator op) {
        return switch (op) {
            case PLUS     -> Optional.of("+");
            case MINUS    -> Optional.of("-");
            case MULTIPLY -> Optional.of("*");
            case DIVIDE   -> Optional.empty();
        };
    }

    /**
     * Renders a {@link FunctionCall} as a SQL expression, or empty when the function has
     * no spelling for this dialect.
     *
     * <p>The planner knows no function names.  It renders the arguments — recursively,
     * so a nested call or an arithmetic operand resolves through the same rules — and
     * asks the function itself how it is written for this backend (ADR-0026).  Declining
     * is always safe: the call is evaluated in-engine instead, which is what happens for
     * every function that ships no spelling, for a dialect a spelling has not been
     * confirmed against, for an argument that is not itself pushable, and for a call
     * that {@link PushdownFolding#mayFold} refuses to hand over at all.
     *
     * <p>The consequence worth stating is that a third-party library ships the
     * implementation and the spelling together, so its function folds into a pushed
     * query with no change here.
     */
    private static Optional<String> functionCall(FunctionCall f, ColumnRenderer cols,
                                                 Dialect dialect, PushdownFunctions functions) {
        // A call that is constant for the run — NOW() — is evaluated here and rendered as
        // the literal it came to, which the backend has no opinion about. Tried before
        // the fold check because such a call is by construction one the backend may NOT
        // be asked to evaluate: it would answer from its own clock.
        Optional<String> substituted = StableCalls.substitute(f, functions.catalog(), functions.context())
                .flatMap(literal -> operand(literal, cols, dialect, functions));
        if (substituted.isPresent()) {
            return substituted;
        }
        Optional<ScalarFunction> function = functions.scalar(f.functionName());
        if (function.isEmpty() || !PushdownFolding.mayFold(function.get())) {
            return Optional.empty();
        }
        List<String> rendered = new ArrayList<>(f.arguments().size());
        for (Operand argument : f.arguments()) {
            Optional<String> sql = operand(argument, cols, dialect, functions);
            if (sql.isEmpty()) {
                return Optional.empty();
            }
            rendered.add(sql.get());
        }
        return function.get().pushdown().render(dialect.pushdownTarget(), rendered);
    }

}

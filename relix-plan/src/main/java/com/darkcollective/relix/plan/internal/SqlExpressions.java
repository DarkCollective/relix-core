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

import com.darkcollective.relix.ast.QuantifiedPredicate;
import com.darkcollective.relix.ast.ExistsPredicate;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.NullOperand;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ConditionOperand;
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
import com.darkcollective.relix.ast.CollateOperand;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.ParameterOperand;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.function.PushdownSpelling;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
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
 *
 * <p>A bound parameter ({@code $name}) compared with a column or a value is sent as a
 * JDBC {@code ?}, never as a literal, so its value cannot change the statement.
 */
public final class SqlExpressions {

    private SqlExpressions() {
    }

    /** Resolves a relix attribute reference to a SQL column expression, or empty if unresolvable. */
    @FunctionalInterface
    interface ColumnRenderer {
        Optional<String> render(String attribute);

        /**
         * Whether a reference holds text, which decides how a {@code +} over it is
         * written: SQL adds numbers with {@code +} and joins strings with something else.
         *
         * <p>A renderer that knows no types answers {@link Text#NOT_STRING} for every
         * name, which is how a reference was read before any renderer knew one. One built
         * over a schema answers from it; see {@link #typed}.
         *
         * @param attribute the reference
         * @return what the reference is known to hold
         */
        default Text text(String attribute) {
            return Text.NOT_STRING;
        }

        /**
         * Whether a reference is known to hold a number, which is what lets {@code + - *}
         * over it be written as exact decimal arithmetic ({@link Dialect#exactNumber}): a
         * date or a boolean cast to a decimal is an error where the engine's operator
         * would have meant something else.
         *
         * <p>A renderer built without a schema knows nothing, and answers no.
         *
         * @param attribute the reference
         * @return {@code true} only when the reference is a NUMBER column
         */
        default boolean numeric(String attribute) {
            return false;
        }

        /**
         * {@code self}, answering {@link #text} from {@code schema}: a STRING column holds
         * text, an ANY column or a name the schema does not have may, and any other
         * column does not.
         *
         * @param self   how a reference renders
         * @param schema the columns a reference names
         * @return the renderer
         */
        static ColumnRenderer typed(ColumnRenderer self, Schema schema) {
            return new ColumnRenderer() {
                @Override
                public Optional<String> render(String attribute) {
                    return self.render(attribute);
                }

                @Override
                public Optional<Comparing> comparing() {
                    return self.comparing();
                }

                @Override
                public Text text(String attribute) {
                    return schema.column(column(attribute))
                            .map(c -> c.type() == ScalarType.STRING ? Text.STRING
                                    : c.type() == ScalarType.ANY ? Text.UNKNOWN : Text.NOT_STRING)
                            .orElse(Text.UNKNOWN);
                }

                @Override
                public boolean numeric(String attribute) {
                    return schema.column(column(attribute))
                            .map(c -> c.type() == ScalarType.NUMBER)
                            .orElse(false);
                }
            };
        }

        /**
         * The renderers a condition inside one of this renderer's operands compares
         * through, or empty when this renderer was built without knowing them.
         *
         * <p>A condition is a predicate, and a predicate's string comparisons go through
         * the comparison renderers (see {@link #predicate(Predicate, ColumnRenderer,
         * ColumnRenderer, Dialect, PushdownFunctions)}) so that a collation which is not
         * exact cannot change the answer. A select list renders through the plain
         * renderer, which does no such wrapping, so a condition written in one — the test
         * of an {@code IIf} — needs to be told what to compare through. Empty declines
         * the call rather than comparing under the column's collation.
         *
         * @return the comparison renderers, when known
         */
        default Optional<Comparing> comparing() {
            return Optional.empty();
        }

        /** A renderer that simply drops any qualifier prefix (single-table pushdown). */
        ColumnRenderer STRIP_QUALIFIER = attribute -> Optional.of(column(attribute));

        /**
         * {@code self}, knowing what a condition inside one of its operands compares
         * through.
         *
         * @param self     how a reference renders
         * @param equality how a reference renders where a condition tests equality
         * @param ordering how a reference renders where a condition orders two values
         * @return the renderer
         */
        static ColumnRenderer withComparing(ColumnRenderer self, ColumnRenderer equality,
                                        ColumnRenderer ordering) {
            Comparing comparing = new Comparing(equality, ordering);
            return new ColumnRenderer() {
                @Override
                public Optional<String> render(String attribute) {
                    return self.render(attribute);
                }

                @Override
                public Optional<Comparing> comparing() {
                    return Optional.of(comparing);
                }

                @Override
                public Text text(String attribute) {
                    return self.text(attribute);
                }

                @Override
                public boolean numeric(String attribute) {
                    return self.numeric(attribute);
                }
            };
        }
    }

    /** Whether an operand holds text, as far as rendering it can tell. */
    enum Text {
        /** It holds a string, or NULL. */
        STRING,
        /** It holds a number, a date, a boolean — anything but a string — or NULL. */
        NOT_STRING,
        /** It may hold either. */
        UNKNOWN;

        /** What the sum of two operands holds: a string only if both are, and unknown when they disagree. */
        Text plus(Text other) {
            return this == other ? this : UNKNOWN;
        }
    }

    /**
     * The two positions a predicate compares a string in — see
     * {@link #predicate(Predicate, ColumnRenderer, ColumnRenderer, Dialect, PushdownFunctions)}.
     *
     * @param equality how a reference renders where the backend is asked whether two
     *                 values are equal
     * @param ordering how a reference renders where it is asked which is larger
     */
    record Comparing(ColumnRenderer equality, ColumnRenderer ordering) {
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
        // An operand of this predicate may hold a condition of its own — an IIf in a
        // comparison — which compares exactly as this predicate does.
        ColumnRenderer equal = ColumnRenderer.withComparing(cols, cols, ordering);
        ColumnRenderer order = ColumnRenderer.withComparing(ordering, cols, ordering);
        return switch (predicate) {
            case ComparisonPredicate c -> comparison(c.left(), sqlOp(c.operator()), c.right(),
                    ordersValues(c.operator()) ? order : equal, dialect, functions);
            case AndPredicate a -> combine(a.left(), "AND", a.right(), cols, ordering, dialect, functions);
            case OrPredicate o -> combine(o.left(), "OR", o.right(), cols, ordering, dialect, functions);
            case NotPredicate n -> predicate(n.predicate(), cols, ordering, dialect, functions)
                    .map(p -> "(NOT " + p + ")");
            case NullPredicate n -> operand(n.operand(), equal, dialect, functions)
                    .map(o -> o + (n.isNull() ? " IS NULL" : " IS NOT NULL"));
            case ElementOfPredicate e -> elementOf(e, equal, dialect, functions);
            case PatternPredicate p -> patternLike(p, equal, dialect, functions);
            case ExistsPredicate e -> Optional.empty();
            case QuantifiedPredicate q -> Optional.empty();
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
            // A literal holding a placeholder character would read back as a parameter.
            case StringOperand s -> containsPlaceholder(s.value()) ? Optional.empty()
                    : Optional.of(dialect.stringLiteral(s.value()));
            case BooleanOperand b -> Optional.of(dialect.booleanLiteral(b.value()));
            case NullOperand ignored -> Optional.of("NULL");
            // Temporal literals render per-dialect (ADR-0013), and a dialect with no
            // date types declines them.
            case DateOperand d -> dialect.dateLiteral(d.value());
            case TimeOperand t -> dialect.timeLiteral(t.value());
            case TimestampOperand ts -> ts.isZoned() ? dialect.zonedTimestampLiteral(ts.atOffset())
                    : dialect.timestampLiteral(ts.value());
            case DurationOperand d -> dialect.durationLiteral(d.months(), d.value());
            case UnaryOperand u -> negation(u, cols, dialect, functions);
            case BinaryArithmeticExpression e -> arithmetic(e, cols, dialect, functions);
            case SetLiteralOperand s -> setLiteral(s, cols, dialect, functions);
            // A function call pushes when the function itself supplies a spelling for
            // this dialect (ADR-0026): the planner knows no function names.
            case FunctionCall f -> functionCall(f, cols, dialect, functions);
            case CollateOperand c -> collated(c, cols, dialect, functions);
            default -> Optional.empty();
        };
    }

    /**
     * One side of a comparison: a bound parameter compared with something that is not a
     * parameter renders as a placeholder ({@link #parameter}), and anything else as an
     * operand.
     *
     * <p>Only there. A placeholder's type is the database's to infer from its context,
     * and the other side of a comparison is the one context every backend reads it from;
     * {@code ? + 1}, a selected {@code ?} or {@code ? = ?} leaves Postgres asking what
     * type the parameter has. Such a parameter is not pushed, and the engine evaluates
     * it instead.
     */
    private static Optional<String> comparand(Operand side, Operand other, ColumnRenderer cols,
                                              Dialect dialect, PushdownFunctions functions) {
        if (side instanceof ParameterOperand p && !(other instanceof ParameterOperand)) {
            return Optional.of(parameter(p.name()));
        }
        return operand(side, cols, dialect, functions);
    }

    /** Opens a parameter's placeholder in rendered text; a private-use character. */
    static final char PARAMETER_OPEN = '\uE000';

    /** Closes a parameter's placeholder in rendered text; a private-use character. */
    static final char PARAMETER_CLOSE = '\uE001';

    /**
     * The placeholder a bound parameter is rendered as while a statement is assembled:
     * its name between two private-use characters. A statement is built from fragments
     * and the fragments are not concatenated in reading order, so the position of each
     * {@code ?} is only known once the text is complete; {@link #bind} reads them off it
     * then.
     */
    static String parameter(String name) {
        return PARAMETER_OPEN + name + String.valueOf(PARAMETER_CLOSE);
    }

    /**
     * A statement's text with each parameter placeholder replaced by {@code ?}, and the
     * names of the parameters each {@code ?} stands for, in order.
     *
     * @param sql        the text to send
     * @param parameters one name per {@code ?}, in the order they appear
     */
    record Bound(String sql, List<String> parameters) {
    }

    /** Replaces each placeholder in {@code sql} with {@code ?}, recording its name. */
    static Bound bind(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == PARAMETER_OPEN) {
                int close = sql.indexOf(PARAMETER_CLOSE, i);
                names.add(sql.substring(i + 1, close));
                out.append('?');
                i = close + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return new Bound(out.toString(), List.copyOf(names));
    }

    private static boolean containsPlaceholder(String text) {
        return text.indexOf(PARAMETER_OPEN) >= 0 || text.indexOf(PARAMETER_CLOSE) >= 0;
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

    /** A comparison: {@link #binary}, except that either side may be a parameter's placeholder. */
    private static Optional<String> comparison(Operand left, String op, Operand right,
                                               ColumnRenderer cols, Dialect dialect,
                                               PushdownFunctions functions) {
        Optional<String> l = comparand(left, right, cols, dialect, functions);
        Optional<String> r = comparand(right, left, cols, dialect, functions);
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
        // The engine's + adds two numbers and joins two strings. SQL spells the two
        // differently, so a + is sent only once it is known which it is, and the
        // dialect's own concatenation is used for the second.
        if (e.operator() == ArithmeticOperator.PLUS) {
            Text text = text(e, cols, functions);
            if (text == Text.UNKNOWN) {
                return Optional.empty();
            }
            if (text == Text.STRING) {
                Optional<String> l = operand(e.left(), cols, dialect, functions);
                Optional<String> r = operand(e.right(), cols, dialect, functions);
                if (l.isEmpty() || r.isEmpty()) {
                    return Optional.empty();
                }
                return dialect.concatenate(l.get(), r.get());
            }
        }
        // A date or timestamp moved by a duration literal is the one arithmetic that is
        // sent: the literal says what the other side is, and the dialect renders the
        // duration (calendar months included) in a form its own date arithmetic reads.
        if ((e.operator() == ArithmeticOperator.PLUS || e.operator() == ArithmeticOperator.MINUS)
                && (e.left() instanceof DurationOperand || e.right() instanceof DurationOperand)) {
            return binary(e.left(), e.operator() == ArithmeticOperator.PLUS ? "+" : "-",
                    e.right(), cols, dialect, functions);
        }
        // Every other + - * is sent only as exact decimal arithmetic. The engine's NUMBER is
        // arbitrary precision and reads a DOUBLE column as the decimal text it prints as,
        // while a backend adds, subtracts and multiplies in the column's own type: INT + 1
        // raises on H2, DuckDB, PostgreSQL and SQL Server where the engine answers
        // 2147483648, a DuckDB DECIMAL product can overflow, 0.1 * 3 over a DOUBLE comes
        // back 0.30000000000000004, and a raw `-` over two timestamps is an interval, a
        // number or an error depending on the backend, never the engine's DURATION. The
        // declared schema says NUMBER whatever the column is, so a dialect is asked for a
        // type that is exact for every numeric column (Dialect.exactNumber), and one with
        // none leaves the arithmetic to the engine. So does an operand not known to be a
        // number, which a cast to a decimal would turn into an error.
        if (!dialect.addsExactly() || !isNumber(e.left(), cols, functions)
                || !isNumber(e.right(), cols, functions)) {
            return Optional.empty();
        }
        Optional<String> op = sqlOp(e.operator());
        Optional<String> l = exactOperand(e.left(), cols, dialect, functions);
        Optional<String> r = exactOperand(e.right(), cols, dialect, functions);
        if (op.isEmpty() || l.isEmpty() || r.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("(" + l.get() + " " + op.get() + " " + r.get() + ")");
    }

    /**
     * Whether {@code operand} is known to hold a number: a number literal, a column the
     * renderer's schema types NUMBER, a call whose function returns NUMBER, or arithmetic
     * and negation over those.
     */
    private static boolean isNumber(Operand operand, ColumnRenderer cols, PushdownFunctions functions) {
        return switch (operand) {
            case NumberOperand ignored -> true;
            case AttributeOperand a -> cols.numeric(a.name());
            case UnaryOperand u -> isNumber(u.operand(), cols, functions);
            case BinaryArithmeticExpression b ->
                    isNumber(b.left(), cols, functions) && isNumber(b.right(), cols, functions);
            case FunctionCall f -> functions.scalar(f.functionName())
                    .map(fn -> fn.signature().returnType() == ScalarType.NUMBER).orElse(false);
            default -> false;
        };
    }

    /**
     * An operand of exact arithmetic, as an exact decimal: a literal or column cast, and
     * arithmetic or negation already exact, which is left as it is.
     */
    private static Optional<String> exactOperand(Operand operand, ColumnRenderer cols, Dialect dialect,
                                                 PushdownFunctions functions) {
        return switch (operand) {
            case NumberOperand n -> dialect.exactNumberLiteral(n.value());
            case BinaryArithmeticExpression b -> arithmetic(b, cols, dialect, functions);
            case UnaryOperand u -> exactOperand(u.operand(), cols, dialect, functions)
                    .map(o -> "(-" + o + ")");
            default -> operand(operand, cols, dialect, functions).flatMap(dialect::exactNumber);
        };
    }

    /**
     * A negated operand. A literal is negated as it is, which cannot leave any type's
     * range. Anything else is negated as an exact decimal, or not at all: the smallest
     * value of an integer type has no negation in that type, so {@code -n} over an
     * {@code INT} raises where the engine answers 2147483648.
     */
    private static Optional<String> negation(UnaryOperand u, ColumnRenderer cols, Dialect dialect,
                                             PushdownFunctions functions) {
        if (u.operand() instanceof NumberOperand n) {
            return Optional.of("(-" + n.value() + ")");
        }
        if (!dialect.addsExactly() || !isNumber(u.operand(), cols, functions)) {
            return Optional.empty();
        }
        return exactOperand(u, cols, dialect, functions);
    }

    /**
     * Whether {@code operand} holds text: a string literal does, a column as its renderer
     * says, a call as its function declares, and a sum only where both sides agree.
     * Anything else, a number or a difference, does not.
     */
    static Text text(Operand operand, ColumnRenderer cols, PushdownFunctions functions) {
        return switch (operand) {
            case StringOperand ignored -> Text.STRING;
            case AttributeOperand a -> cols.text(a.name());
            case FunctionCall f -> functions.scalar(f.functionName())
                    .map(fn -> switch (fn.signature().returnType()) {
                        case STRING -> Text.STRING;
                        case ANY -> Text.UNKNOWN;
                        default -> Text.NOT_STRING;
                    })
                    .orElse(Text.UNKNOWN);
            case BinaryArithmeticExpression e when e.operator() == ArithmeticOperator.PLUS ->
                    text(e.left(), cols, functions).plus(text(e.right(), cols, functions));
            case NullOperand ignored -> Text.UNKNOWN;
            default -> Text.NOT_STRING;
        };
    }

    /**
     * A value written under a collation. {@code BINARY} is the order every string
     * comparison here is rendered in already, by the caller's renderer, so it is the value
     * itself. {@code NOCASE} and a locale have no rendering: a database's case-insensitive
     * collation folds {@code ß}, accents and dotted capitals by rules of its own, and
     * {@code LOWER} is a collation's mapping rather than Java's (the reason {@code LCase}
     * is offered no backend), so a comparison, sort or membership under either runs in
     * the engine.
     */
    private static Optional<String> collated(CollateOperand c, ColumnRenderer cols, Dialect dialect,
                                             PushdownFunctions functions) {
        return c.collation().equals(CollateOperand.BINARY)
                ? operand(c.operand(), cols, dialect, functions)
                : Optional.empty();
    }

    private static Optional<String> elementOf(ElementOfPredicate e, ColumnRenderer cols, Dialect dialect,
                                           PushdownFunctions functions) {
        // SQL has no empty list, IN (), and nothing is a member of the empty set
        if (e.setExpression() instanceof SetLiteralOperand set && set.elements().isEmpty()) {
            return Optional.of(e.isNegated() ? "1 = 1" : "1 = 0");
        }
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
        return dialect.like(lhs.get(), rhs.get(), literal, p.escape(), p.negated());
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

    /**
     * This operator's SQL spelling, or empty for division.
     *
     * <p><b>Division is declined, on every backend.</b> A relix {@code /} is exact decimal
     * arithmetic to ten places, rounded half-up; no {@code /} the planner can emit answers
     * the same question. H2 and PostgreSQL divide two integers as integers, so
     * {@code 81 / 2} is {@code 40} where the engine says {@code 40.5}. MySQL's scale is the
     * operand's plus {@code div_precision_increment}, four by default, so {@code amount / 3}
     * comes back {@code 33.3333} against {@code 33.3333333333}. Division by zero is a third
     * disagreement: the engine raises, PostgreSQL raises, MySQL answers NULL. A cast would
     * fix the first and not the others, and matching ten-place half-up rounding is not
     * something SQL text can promise. The whole expression declines with it.
     */
    private static Optional<String> sqlOp(ArithmeticOperator op) {
        return switch (op) {
            case PLUS     -> Optional.of("+");
            case MINUS    -> Optional.of("-");
            case MULTIPLY -> Optional.of("*");
            case DIVIDE   -> Optional.empty();
        };
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
        PushdownSpelling spelling = function.get().pushdown();
        int arity = f.arguments().size();
        List<String> rendered = new ArrayList<>(arity);
        for (int position = 0; position < arity; position++) {
            Operand argument = f.arguments().get(position);
            Optional<String> sql = spelling.isCondition(position, arity)
                    ? condition(argument, cols, dialect, functions)
                    : operand(argument, cols, dialect, functions);
            if (sql.isEmpty()) {
                return Optional.empty();
            }
            rendered.add(sql.get());
        }
        return spelling.render(dialect.pushdownTarget(), rendered);
    }

    /**
     * An argument a spelling writes as a condition, rendered as a predicate.
     *
     * <p>Only a {@link ConditionOperand} is one. Anything else in that position — a
     * column, a call — is a value whose type this renderer cannot see, and a value the
     * engine would reject as a condition might be one a backend accepts, which would make
     * the folded query answer where the unfolded one raises. A boolean column is
     * therefore written as a comparison, {@code hot = true}, to fold.
     *
     * <p>The predicate compares through the renderers {@code cols} knows (see
     * {@link ColumnRenderer#comparing()}), and declines when it knows none.
     */
    static Optional<String> condition(Operand argument, ColumnRenderer cols, Dialect dialect,
                                      PushdownFunctions functions) {
        if (!(argument instanceof ConditionOperand c)) {
            return Optional.empty();
        }
        return cols.comparing().flatMap(comparing ->
                predicate(c.predicate(), comparing.equality(), comparing.ordering(), dialect, functions));
    }

}

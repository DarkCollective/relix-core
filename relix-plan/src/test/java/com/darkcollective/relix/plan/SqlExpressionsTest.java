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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.FunctionLibrary;
import com.darkcollective.relix.function.FunctionSignature;
import com.darkcollective.relix.function.PushdownSpelling;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.ast.Expr.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SqlExpressions — predicate/operand → SQL translation")
final class SqlExpressionsTest {

    /**
     * The installed function libraries — a function's SQL spelling comes from the
     * function itself, so a renderer with no catalogue folds no calls at all.
     */
    private static final PushdownFunctions FUNCTIONS =
            PushdownFunctions.of(FunctionCatalog.discover());

    private static Optional<String> render(Operand operand) {
        return SqlExpressions.operand(operand, SqlExpressions.ColumnRenderer.STRIP_QUALIFIER,
                FUNCTIONS);
    }

    private static Optional<String> render(Operand operand, Dialect dialect) {
        return SqlExpressions.operand(operand, SqlExpressions.ColumnRenderer.STRIP_QUALIFIER,
                dialect, FUNCTIONS);
    }

    private static Optional<String> renderP(Predicate predicate) {
        return SqlExpressions.predicate(predicate, SqlExpressions.ColumnRenderer.STRIP_QUALIFIER,
                FUNCTIONS);
    }

    @Nested
    @DisplayName("operands")
    class Operands {

        @Test
        @DisplayName("attribute drops any qualifier prefix")
        void attribute() {
            assertThat(render(attr("amount"))).hasValue("amount");
            assertThat(render(attr("Orders.amount"))).hasValue("amount");
        }

        @Test
        @DisplayName("literals: number, string (escaped), boolean")
        void literals() {
            assertThat(render(num("42"))).hasValue("42");
            assertThat(render(str("a'b"))).hasValue("'a''b'");
            assertThat(render(bool(true))).hasValue("TRUE");
            assertThat(render(bool(false))).hasValue("FALSE");
        }

        /**
         * A backslash is a per-backend question, and doubling the quote is only half of
         * the answer.
         *
         * <p>MySQL and MariaDB treat {@code \\} inside a string literal as an escape
         * unless the session sets {@code NO_BACKSLASH_ESCAPES}, so a rendered
         * {@code 'x\\''} ends its literal at the <em>second</em> quote rather than the
         * first — the value's own backslash escapes the quote that was doubled to contain
         * it, and the text after it is parsed as SQL. A value is not supposed to be able
         * to do that. Postgres and the generic dialect read a backslash literally
         * ({@code standard_conforming_strings}), so doubling it there would instead put a
         * second backslash into the value.
         */
        @Test
        @DisplayName("a backslash is escaped for MySQL and left alone elsewhere")
        void backslashInAStringLiteral() {
            assertThat(render(str("a\\b"), Dialect.MYSQL)).hasValue("'a\\\\b'");
            assertThat(render(str("a\\b"), Dialect.POSTGRES)).hasValue("'a\\b'");
            assertThat(render(str("a\\b"), Dialect.GENERIC)).hasValue("'a\\b'");
        }

        @Test
        @DisplayName("a value cannot end its own literal and become SQL")
        void aValueCannotEscapeItsLiteral() {
            // The injection this closes: the trailing backslash escapes the quote that
            // was doubled to contain the apostrophe, so everything after it is SQL.
            String hostile = "x\\' OR 1=1 -- ";

            assertThat(render(str(hostile), Dialect.MYSQL))
                    .hasValue("'x\\\\'' OR 1=1 -- '");
            assertThat(render(str(hostile), Dialect.POSTGRES))
                    .hasValue("'x\\'' OR 1=1 -- '");
        }

        @Test
        @DisplayName("unary negation and arithmetic")
        void unaryAndArithmetic() {
            assertThat(render(unary(attr("x")))).hasValue("(-x)");
            var expr = arith(
                    attr("price"), ArithmeticOperator.MULTIPLY, num("2"));
            assertThat(render(expr)).hasValue("(price * 2)");
        }

        @Test
        @DisplayName("a scalar function call is not translatable")
        void functionCallUnsupported() {
            assertThat(render(func("UCase",attr("name"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("an arithmetic expression containing an untranslatable operand is empty")
        void arithmeticWithUnsupportedOperand() {
            var expr = arith(
                    attr("x"), ArithmeticOperator.PLUS,
                    func("Sqr",attr("y")));
            assertThat(render(expr)).isEmpty();
        }
    }

    @Nested
    @DisplayName("predicates")
    class Predicates {

        @Test
        @DisplayName("comparison operators map to SQL")
        void comparisons() {
            assertThat(renderP(
                    cmp(attr("a"), ComparisonOperator.EQUAL, num("1"))))
                    .hasValue("(a = 1)");
            assertThat(renderP(
                    cmp(attr("a"), ComparisonOperator.NOT_EQUAL, num("1"))))
                    .hasValue("(a <> 1)");
            assertThat(renderP(
                    cmp(attr("a"), ComparisonOperator.GREATER_EQUAL, num("1"))))
                    .hasValue("(a >= 1)");
        }

        @Test
        @DisplayName("AND, OR, NOT compose")
        void booleans() {
            Predicate a = cmp(attr("a"), ComparisonOperator.GREATER, num("0"));
            Predicate b = cmp(attr("b"), ComparisonOperator.LESS, num("9"));
            assertThat(renderP(and(a, b)))
                    .hasValue("((a > 0) AND (b < 9))");
            assertThat(renderP(or(a, b)))
                    .hasValue("((a > 0) OR (b < 9))");
            assertThat(renderP(not(a)))
                    .hasValue("(NOT (a > 0))");
        }

        @Test
        @DisplayName("IS NULL / IS NOT NULL")
        void nullChecks() {
            assertThat(renderP(nullPred(attr("a"), true)))
                    .hasValue("a IS NULL");
            assertThat(renderP(nullPred(attr("a"), false)))
                    .hasValue("a IS NOT NULL");
        }

        @Test
        @DisplayName("IN / NOT IN over a set literal")
        void elementOf() {
            var set = set(num("1"), num("2"));
            assertThat(renderP(AstBuilders.elementOf(attr("a"), set)))
                    .hasValue("a IN (1, 2)");
            assertThat(renderP(notElementOf(attr("a"), set)))
                    .hasValue("a NOT IN (1, 2)");
        }

        @Test
        @DisplayName("a predicate over an untranslatable operand is empty")
        void unsupportedOperandMakesPredicateEmpty() {
            Predicate p = cmp(func("Len",attr("name")),
                    ComparisonOperator.GREATER, num("3"));
            assertThat(renderP(p)).isEmpty();
        }

        @Test
        @DisplayName("an AND with one untranslatable branch is empty")
        void andWithUnsupportedBranch() {
            Predicate ok = cmp(attr("a"), ComparisonOperator.EQUAL, num("1"));
            Predicate bad = cmp(func("Len",attr("n")),
                    ComparisonOperator.GREATER, num("3"));
            assertThat(renderP(and(ok, bad))).isEmpty();
        }

        @Test
        @DisplayName("…and the same with the untranslatable branch on the RIGHT")
        void untranslatableOnTheRightIsAlsoEmpty() {
            // Every one of these renders its two parts and then tests `l.isEmpty() ||
            // r.isEmpty()`. Putting the bad part first every time short-circuits on the
            // first arm, so nothing has shown that the second part is rendered at all —
            // and a renderer that ignored it would emit SQL with a missing operand.
            Operand bad = func("Len",attr("n"));
            Predicate okPred = cmp(attr("a"), ComparisonOperator.EQUAL, num("1"));
            Predicate badPred = cmp(bad, ComparisonOperator.GREATER, num("3"));

            assertThat(renderP(and(okPred, badPred)))
                    .as("AND, bad right branch").isEmpty();
            assertThat(renderP(or(okPred, badPred)))
                    .as("OR, bad right branch").isEmpty();
            assertThat(renderP(AstBuilders.elementOf(attr("a"),
                    set(bad))))
                    .as("IN, bad set").isEmpty();
            assertThat(renderP(like(attr("name"), bad)))
                    .as("LIKE, bad pattern").isEmpty();
            assertThat(render(arith(attr("x"),
                    ArithmeticOperator.PLUS, bad)))
                    .as("arithmetic, bad right operand").isEmpty();
            assertThat(renderP(cmp(attr("a"), ComparisonOperator.EQUAL, bad)))
                    .as("comparison, bad right operand").isEmpty();
        }

        @Test
        @DisplayName("LIKE renders as column LIKE 'pattern'")
        void likeRenders() {
            assertThat(renderP(like(attr("name"), str("%smith%"))))
                    .hasValue("(name LIKE '%smith%')");
        }

        @Test
        @DisplayName("NOT LIKE renders as column NOT LIKE 'pattern'")
        void notLikeRenders() {
            assertThat(renderP(notLike(attr("name"), str("%test%"))))
                    .hasValue("(name NOT LIKE '%test%')");
        }

        @Test
        @DisplayName("LIKE with a qualifier on the attribute drops the qualifier")
        void likeDropsQualifier() {
            assertThat(renderP(like(attr("Users.name"), str("A%"))))
                    .hasValue("(name LIKE 'A%')");
        }

        @Test
        @DisplayName("LIKE with a pattern containing single quotes escapes them")
        void likeEscapesSingleQuote() {
            assertThat(renderP(like(attr("name"), str("it's%"))))
                    .hasValue("(name LIKE 'it''s%')");
        }

        @Test
        @DisplayName("LIKE with an untranslatable operand falls back")
        void likeUntranslatableOperandFallsBack() {
            assertThat(renderP(like(
                    func("UCase",attr("name")), str("%A%"))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("temporal literals (ADR-0013)")
    class TemporalLiterals {

        private final com.darkcollective.relix.ast.DateOperand date =
                new com.darkcollective.relix.ast.DateOperand(java.time.LocalDate.parse("2026-06-15"));
        private final com.darkcollective.relix.ast.TimestampOperand ts =
                new com.darkcollective.relix.ast.TimestampOperand(java.time.Instant.parse("2026-06-15T13:40:00Z"));
        private final com.darkcollective.relix.ast.DurationOperand dur =
                new com.darkcollective.relix.ast.DurationOperand(java.time.Duration.parse("PT30M"));

        @Test
        @DisplayName("DATE/TIMESTAMP render (GENERIC default); a comparison folds")
        void temporalRendersGeneric() {
            assertThat(render(date)).hasValue("'2026-06-15'");
            assertThat(render(ts)).hasValue("'2026-06-15 13:40:00'");
            assertThat(renderP(cmp(attr("at"), ComparisonOperator.GREATER_EQUAL, ts)))
                    .hasValue("(at >= '2026-06-15 13:40:00')");
        }

        @Test
        @DisplayName("the dialect picks the literal form")
        void temporalRendersPerDialect() {
            assertThat(SqlExpressions.operand(ts, SqlExpressions.ColumnRenderer.STRIP_QUALIFIER, Dialect.POSTGRES, FUNCTIONS))
                    .hasValue("TIMESTAMP WITH TIME ZONE '2026-06-15T13:40:00Z'");
            assertThat(SqlExpressions.operand(date, SqlExpressions.ColumnRenderer.STRIP_QUALIFIER, Dialect.MYSQL, FUNCTIONS))
                    .hasValue("DATE '2026-06-15'");
        }

        @Test
        @DisplayName("a DURATION literal is not pushable with GENERIC dialect (falls back to in-engine)")
        void durationNotPushable() {
            assertThat(render(dur)).isEmpty();
            // …so a predicate containing it does not push as a whole
            assertThat(renderP(cmp(attr("held"), ComparisonOperator.GREATER, dur))).isEmpty();
        }

        @Test
        @DisplayName("a DURATION literal renders as INTERVAL on Postgres only (not MySQL or GENERIC)")
        void durationRendersAsIntervalOnPostgres() {
            var d = duration(Duration.parse("PT30M"));
            assertThat(SqlExpressions.operand(d, SqlExpressions.ColumnRenderer.STRIP_QUALIFIER, Dialect.POSTGRES, FUNCTIONS))
                    .hasValue("INTERVAL 'PT30M'");
            assertThat(SqlExpressions.operand(d, SqlExpressions.ColumnRenderer.STRIP_QUALIFIER, Dialect.MYSQL, FUNCTIONS))
                    .isEmpty();
            assertThat(SqlExpressions.operand(d, SqlExpressions.ColumnRenderer.STRIP_QUALIFIER, Dialect.GENERIC, FUNCTIONS))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("temporal functions (ADR-0013)")
    class TemporalFunctions {

        private static Optional<String> render(Operand op) {
            return SqlExpressions.operand(op, SqlExpressions.ColumnRenderer.STRIP_QUALIFIER,
                    FUNCTIONS);
        }

        private static Optional<String> render(Operand op, Dialect d) {
            return SqlExpressions.operand(op, SqlExpressions.ColumnRenderer.STRIP_QUALIFIER, d,
                    FUNCTIONS);
        }

        @Test
        @DisplayName("YEAR/MONTH/DAY/HOUR/MINUTE/SECOND → EXTRACT(unit FROM expr) — all dialects")
        void extractFunctions() {
            assertThat(render(func("YEAR",attr("at")))).hasValue("EXTRACT(YEAR FROM at)");
            assertThat(render(func("MONTH",attr("at")))).hasValue("EXTRACT(MONTH FROM at)");
            assertThat(render(func("DAY",attr("at")))).hasValue("EXTRACT(DAY FROM at)");
            assertThat(render(func("HOUR",attr("at")))).hasValue("EXTRACT(HOUR FROM at)");
            assertThat(render(func("MINUTE",attr("at")))).hasValue("EXTRACT(MINUTE FROM at)");
            assertThat(render(func("SECOND",attr("at")))).hasValue("EXTRACT(SECOND FROM at)");
        }

        @Test
        @DisplayName("function names are case-insensitive")
        void functionNameCaseInsensitive() {
            assertThat(render(func("year",attr("at")))).hasValue("EXTRACT(YEAR FROM at)");
            assertThat(render(func("Year",attr("at")))).hasValue("EXTRACT(YEAR FROM at)");
        }

        @Test
        @DisplayName("EXTRACT qualifies the inner attribute via the column renderer")
        void extractDropsQualifier() {
            assertThat(render(func("YEAR",attr("Trades.at"))))
                    .hasValue("EXTRACT(YEAR FROM at)");
        }

        @Test
        @DisplayName("EXTRACT with wrong arg count is not pushable")
        void extractWrongArgCount() {
            assertThat(render(func("YEAR"))).isEmpty();
            assertThat(render(func("YEAR",attr("a"), attr("b")))).isEmpty();
        }

        @Test
        @DisplayName("EXTRACT with an untranslatable inner operand falls back")
        void extractUntranslatableInner() {
            assertThat(render(func("YEAR",func("UCase",attr("at"))))).isEmpty();
        }

        @Test
        @DisplayName("DATE_TRUNC renders per dialect: Postgres a function, MySQL a format, GENERIC nothing")
        void dateTruncPerDialect() {
            var call = func("DATE_TRUNC",str("hour"), attr("at"));
            assertThat(render(call, Dialect.POSTGRES)).hasValue("date_trunc('hour', at)");
            // MySQL and MariaDB have no DATE_TRUNC function; DATE_FORMAT reaches the
            // same value, and the cast is what makes it a TIMESTAMP rather than a
            // string. The unit chooses the pattern, so the spelling reads it back out
            // of the literal the planner already rendered.
            assertThat(render(call, Dialect.MYSQL))
                    .hasValue("CAST(DATE_FORMAT(at, '%Y-%m-%d %H:00:00') AS DATETIME)");
            // GENERIC is an unidentified backend, and gets no spelling it has not been
            // confirmed against.
            assertThat(render(call, Dialect.GENERIC)).isEmpty();
        }

        @Test
        @DisplayName("MySQL declines a DATE_TRUNC whose unit is not a literal")
        void dateTruncMysqlNonLiteralUnit() {
            // The unit decides the shape of the SQL, so a unit that is only known at
            // run time cannot choose a pattern — and the call is evaluated in-engine,
            // where it can.
            assertThat(render(func("DATE_TRUNC", attr("unit"), attr("at")), Dialect.MYSQL))
                    .isEmpty();
        }

        @Test
        @DisplayName("DATE_TRUNC with wrong arg count is not pushable")
        void dateTruncWrongArgCount() {
            assertThat(render(func("DATE_TRUNC",str("hour")))).isEmpty();
            assertThat(render(func("DATE_TRUNC"))).isEmpty();
        }

        @Test
        @DisplayName("DATE_TRUNC with an untranslatable argument falls back")
        void dateTruncUntranslatableArg() {
            var call = func("DATE_TRUNC",
                    str("hour"),
                    func("UCase",attr("at")));
            assertThat(render(call, Dialect.POSTGRES)).isEmpty();
        }

        /**
         * These used to render {@code NOW()} and be evaluated by the database, which is
         * a different instant from the one the session was given — and the session's is
         * the one a pinned {@code Clock} makes reproducible. The call is refused here
         * and evaluated in-engine instead.
         */
        @Test
        @DisplayName("NOW() / CURRENT_DATE() / CURRENT_TIME() are not pushable at all")
        void currentTimeFunctions() {
            assertThat(render(func("NOW"))).isEmpty();
            assertThat(render(func("CURRENT_DATE"))).isEmpty();
            assertThat(render(func("CURRENT_TIME"))).isEmpty();
            assertThat(render(func("NOW", attr("x")))).isEmpty();
        }

        @Test
        @DisplayName("a function with no SQL spelling is not pushable")
        void functionWithoutASpelling() {
            assertThat(render(func("UCase",attr("name")))).isEmpty();
        }

        @Test
        @DisplayName("a name no library offers is not pushable")
        void unknownFunction() {
            assertThat(render(func("NoSuchFunction",attr("name"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("a temporal function inside a predicate folds into a comparison")
        void temporalFunctionInPredicate() {
            var year = func("YEAR",attr("at"));
            assertThat(SqlExpressions.predicate(
                    cmp(year, ComparisonOperator.GREATER, num("2024")),
                    SqlExpressions.ColumnRenderer.STRIP_QUALIFIER, FUNCTIONS))
                    .hasValue("(EXTRACT(YEAR FROM at) > 2024)");
        }
    }

    @Nested
    @DisplayName("a library's own function")
    class LibraryFunctions {

        /**
         * The claim this slice rests on: nothing in the planner names a function, so a
         * library that ships a spelling alongside its implementation folds into pushed
         * SQL with no change here.
         */
        @Test
        @DisplayName("folds into pushed SQL, exactly as a shipped one does")
        void thirdPartySpellingFolds() {
            var call = func("Haversine",attr("a"), attr("b"));

            assertThat(SqlExpressions.operand(call,
                    SqlExpressions.ColumnRenderer.STRIP_QUALIFIER, Dialect.POSTGRES,
                    PushdownFunctions.of(FunctionCatalog.of(new AcmeGeo()))))
                    .hasValue("ST_Distance(a, b)");
        }

        /**
         * The gate is a property of the call rather than a list of names, which is what
         * makes it hold for a library the engine has never heard of. {@code Tick} spells
         * itself for every SQL dialect and is still evaluated here, because it declares
         * no determinism — the same reading the optimizer already takes of it.
         */
        @Test
        @DisplayName("a library's volatile function is refused, whatever spelling it carries")
        void volatileThirdPartyFunctionIsRefused() {
            var call = func("Tick");

            assertThat(Tick.SPELLING.render(Dialect.POSTGRES.pushdownTarget(), List.of()))
                    .as("the library does offer a spelling — the planner is what refuses it")
                    .isPresent();
            assertThat(SqlExpressions.operand(call,
                    SqlExpressions.ColumnRenderer.STRIP_QUALIFIER, Dialect.POSTGRES,
                    PushdownFunctions.of(FunctionCatalog.of(new AcmeGeo()))))
                    .isEmpty();
        }

        @Test
        @DisplayName("declines a dialect its spelling does not claim, and is evaluated in-engine")
        void declinedDialectFallsBack() {
            var call = func("Haversine",attr("a"), attr("b"));

            assertThat(SqlExpressions.operand(call,
                    SqlExpressions.ColumnRenderer.STRIP_QUALIFIER, Dialect.MYSQL,
                    PushdownFunctions.of(FunctionCatalog.of(new AcmeGeo()))))
                    .isEmpty();
        }
    }

    /** A library of one function, spelled for PostGIS and nothing else. */
    private static final class AcmeGeo implements FunctionLibrary {

        @Override
        public String name() {
            return "acme-geo";
        }

        @Override
        public List<ScalarFunction> scalarFunctions() {
            return List.of(new Haversine(), new Tick());
        }
    }

    /** A library's own reader of system state: spelled everywhere, foldable nowhere. */
    private static final class Tick implements StrictScalarFunction {

        static final PushdownSpelling SPELLING =
                (target, args) -> target.isFamily("sql") && args.isEmpty()
                        ? Optional.of("acme_tick()")
                        : Optional.empty();

        @Override
        public FunctionSignature signature() {
            return FunctionSignature.of("Tick", ScalarType.NUMBER, "geo", Set.of());
        }

        @Override
        public PushdownSpelling pushdown() {
            return SPELLING;
        }

        @Override
        public Value invoke(FunctionContext context, List<Value> arguments) {
            throw new UnsupportedOperationException("not evaluated in this test");
        }
    }

    private static final class Haversine implements StrictScalarFunction {

        @Override
        public FunctionSignature signature() {
            // PURE and DETERMINISTIC are what let a call cross the boundary at all: the
            // planner will not hand a backend a call whose value depends on when it runs.
            return FunctionSignature.of("Haversine", ScalarType.NUMBER, "geo",
                    Set.of(FunctionProperty.PURE, FunctionProperty.DETERMINISTIC),
                    param("a", ScalarType.ANY),
                    param("b", ScalarType.ANY));
        }

        @Override
        public PushdownSpelling pushdown() {
            return (target, args) -> target.isVariant("postgres")
                    ? Optional.of("ST_Distance(" + args.get(0) + ", " + args.get(1) + ")")
                    : Optional.empty();
        }

        @Override
        public Value invoke(FunctionContext context, List<Value> arguments) {
            throw new UnsupportedOperationException("not evaluated in this test");
        }
    }
}

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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.function.ScalarFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spellings a backend that can evaluate a call itself is handed.
 *
 * <p>The expected text here is the text the planner's renderers emit today, character
 * for character — this is a port, and a difference is a bug in it rather than a
 * refreshed expectation. What the engine does with the string is not this module's
 * concern: it hands over arguments already rendered in the backend's syntax and expects
 * a call assembled from them, or empty.
 *
 * <p>Declining is always safe, so a spelling declines wherever there is no faithful
 * equivalent rather than emitting something the backend would read differently.
 */
@DisplayName("Backend spellings")
final class PushdownSpellingsTest {

    private static final PushdownTarget POSTGRES = PushdownTarget.sql("postgres");
    private static final PushdownTarget MYSQL = PushdownTarget.sql("mysql");
    private static final PushdownTarget GENERIC = PushdownTarget.sql("");
    private static final PushdownTarget DUCKDB = PushdownTarget.sql("duckdb");
    private static final PushdownTarget SQLITE = PushdownTarget.sql("sqlite");
    private static final PushdownTarget SQLSERVER = PushdownTarget.sql("sqlserver");
    private static final PushdownTarget MONGO = PushdownTarget.mongo();

    private static Optional<String> render(String function, PushdownTarget target,
                                           String... arguments) {
        return BuiltinCalls.function(function).pushdown().render(target, List.of(arguments));
    }

    @Nested
    @DisplayName("Component extraction")
    final class Extraction {

        @Test
        @DisplayName("becomes EXTRACT(unit FROM …) in every SQL dialect")
        void sql() {
            assertThat(render("YEAR", POSTGRES, "\"at\"")).contains("EXTRACT(YEAR FROM \"at\")");
            assertThat(render("MONTH", MYSQL, "`at`")).contains("EXTRACT(MONTH FROM `at`)");
            assertThat(render("DAY", GENERIC, "at")).contains("EXTRACT(DAY FROM at)");
            assertThat(render("HOUR", POSTGRES, "at")).contains("EXTRACT(HOUR FROM at)");
            assertThat(render("MINUTE", POSTGRES, "at")).contains("EXTRACT(MINUTE FROM at)");
            assertThat(render("SECOND", POSTGRES, "at")).contains("EXTRACT(SECOND FROM at)");
        }

        @Test
        @DisplayName("becomes the matching aggregation-pipeline operator in MongoDB")
        void mongo() {
            assertThat(render("YEAR", MONGO, "\"$at\"")).contains("{\"$year\": \"$at\"}");
            assertThat(render("MONTH", MONGO, "\"$at\"")).contains("{\"$month\": \"$at\"}");
            assertThat(render("DAY", MONGO, "\"$at\"")).contains("{\"$dayOfMonth\": \"$at\"}");
            assertThat(render("HOUR", MONGO, "\"$at\"")).contains("{\"$hour\": \"$at\"}");
            assertThat(render("MINUTE", MONGO, "\"$at\"")).contains("{\"$minute\": \"$at\"}");
            assertThat(render("SECOND", MONGO, "\"$at\"")).contains("{\"$second\": \"$at\"}");
        }

        @Test
        @DisplayName("declines an argument count it has no spelling for")
        void wrongArgumentCount() {
            assertThat(render("YEAR", POSTGRES)).isEmpty();
            assertThat(render("YEAR", POSTGRES, "a", "b")).isEmpty();
            assertThat(render("YEAR", MONGO)).isEmpty();
        }

        @Test
        @DisplayName("declines a backend family it knows nothing about")
        void unknownFamily() {
            assertThat(render("YEAR", new PushdownTarget("graphql", ""), "at")).isEmpty();
        }
    }

    @Nested
    @DisplayName("DATE_TRUNC")
    final class Truncation {

        @Test
        @DisplayName("takes relix's argument order in Postgres")
        void argumentOrder() {
            assertThat(render("DATE_TRUNC", POSTGRES, "'day'", "\"at\""))
                    .contains("date_trunc('day', \"at\")");
        }

        @Test
        @DisplayName("becomes a per-unit DATE_FORMAT in MySQL, cast back to a DATETIME")
        void mysqlPerUnit() {
            // MySQL and MariaDB have no DATE_TRUNC at all; the name belongs to Postgres,
            // BigQuery and Snowflake. DATE_FORMAT reaches the same value by writing out
            // the parts to keep and zeroing the rest — and the CAST is what makes the
            // result a TIMESTAMP the connector can read, rather than the string
            // DATE_FORMAT returns.
            assertThat(render("DATE_TRUNC", MYSQL, "'year'", "`at`"))
                    .contains("CAST(DATE_FORMAT(`at`, '%Y-01-01 00:00:00') AS DATETIME)");
            assertThat(render("DATE_TRUNC", MYSQL, "'month'", "`at`"))
                    .contains("CAST(DATE_FORMAT(`at`, '%Y-%m-01 00:00:00') AS DATETIME)");
            assertThat(render("DATE_TRUNC", MYSQL, "'day'", "`at`"))
                    .contains("CAST(DATE_FORMAT(`at`, '%Y-%m-%d 00:00:00') AS DATETIME)");
            assertThat(render("DATE_TRUNC", MYSQL, "'hour'", "`at`"))
                    .contains("CAST(DATE_FORMAT(`at`, '%Y-%m-%d %H:00:00') AS DATETIME)");
            assertThat(render("DATE_TRUNC", MYSQL, "'minute'", "`at`"))
                    .contains("CAST(DATE_FORMAT(`at`, '%Y-%m-%d %H:%i:00') AS DATETIME)");
            assertThat(render("DATE_TRUNC", MYSQL, "'second'", "`at`"))
                    .contains("CAST(DATE_FORMAT(`at`, '%Y-%m-%d %H:%i:%s') AS DATETIME)");
        }

        @Test
        @DisplayName("MySQL matches the unit however it is cased, as the engine does")
        void mysqlUnitCase() {
            assertThat(render("DATE_TRUNC", MYSQL, "'MONTH'", "`at`"))
                    .contains("CAST(DATE_FORMAT(`at`, '%Y-%m-01 00:00:00') AS DATETIME)");
        }

        /**
         * The MySQL arm is the only spelling that reads its own argument: the unit
         * arrives already rendered, so choosing a pattern means recovering the value
         * from the SQL literal. Everything it cannot recover declines, and declining
         * means the call is evaluated in-engine — which is why the failure mode is a
         * slower plan and never a wrong answer.
         */
        @Test
        @DisplayName("MySQL declines a unit it cannot read back out of the rendered SQL")
        void mysqlUnrecognisedUnit() {
            // Not a literal at all: a column, or an expression over one.
            assertThat(render("DATE_TRUNC", MYSQL, "`unit`", "`at`")).isEmpty();
            assertThat(render("DATE_TRUNC", MYSQL, "CONCAT('d','ay')", "`at`")).isEmpty();
            // Two literals and an operator, which opens and closes with a quote but is
            // not one value.
            assertThat(render("DATE_TRUNC", MYSQL, "'d'||'ay'", "`at`")).isEmpty();
            // A literal, but not a unit DATE_FORMAT can express — nor one relix has.
            assertThat(render("DATE_TRUNC", MYSQL, "'week'", "`at`")).isEmpty();
            assertThat(render("DATE_TRUNC", MYSQL, "''", "`at`")).isEmpty();
            assertThat(render("DATE_TRUNC", MYSQL, "'", "`at`")).isEmpty();
        }

        @Test
        @DisplayName("the generic dialect declines — an unidentified backend gets no guess")
        void genericDeclines() {
            assertThat(render("DATE_TRUNC", GENERIC, "'day'", "at")).isEmpty();
        }

        @Test
        @DisplayName("becomes $dateTrunc in MongoDB")
        void mongo() {
            assertThat(render("DATE_TRUNC", MONGO, "\"day\"", "\"$at\""))
                    .contains("{\"$dateTrunc\": {\"date\": \"$at\", \"unit\": \"day\"}}");
        }

        @Test
        @DisplayName("declines an argument count it has no spelling for")
        void wrongArgumentCount() {
            assertThat(render("DATE_TRUNC", POSTGRES, "'day'")).isEmpty();
            assertThat(render("DATE_TRUNC", MONGO, "'day'")).isEmpty();
        }

        @Test
        @DisplayName("declines a backend family it knows nothing about")
        void unknownFamily() {
            assertThat(render("DATE_TRUNC", new PushdownTarget("graphql", ""), "'day'", "at"))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("The current moment")
    final class CurrentMoment {

        /**
         * Every backend has a clock, and that is exactly the problem: it is not the
         * clock the session was given. These three read the instant from the context so
         * that a run against a pinned {@code Clock} is reproducible, and a spelling
         * would hand the call to a backend that has never heard of it.
         *
         * <p>The engine refuses a non-deterministic call whatever spelling it carries
         * ({@code PushdownFolding}), so this is the second of two locks rather than the
         * only one — but a spelling nothing can reach is a claim nothing checks.
         */
        @Test
        @DisplayName("declines every backend, because the session owns the clock")
        void declinesEverywhere() {
            for (String function : List.of("NOW", "CURRENT_DATE", "CURRENT_TIME")) {
                assertThat(render(function, POSTGRES)).isEmpty();
                assertThat(render(function, MYSQL)).isEmpty();
                assertThat(render(function, GENERIC)).isEmpty();
                assertThat(render(function, MONGO)).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("The functions a SQL backend computes the same way")
    final class SameEverywhere {

        @Test
        @DisplayName("the exact numeric ones")
        void exactNumerics() {
            assertThat(render("Abs", MYSQL, "`x`")).contains("ABS(`x`)");
            assertThat(render("Int", POSTGRES, "\"x\"")).contains("FLOOR(\"x\")");
            // CEILING rather than CEIL: both dialects have both, and CEILING is the
            // standard's name and the one an unidentified backend is likeliest to have.
            assertThat(render("Ceil", GENERIC, "x")).contains("CEILING(x)");
            assertThat(render("Sgn", GENERIC, "x")).contains("SIGN(x)");
            assertThat(render("Round", GENERIC, "x")).contains("ROUND(x)");
            assertThat(render("Round", GENERIC, "x", "2")).contains("ROUND(x, 2)");
        }

        @Test
        @DisplayName("the NULL ones, whose SQL is exact by construction")
        void nullHandling() {
            assertThat(render("Coalesce", GENERIC, "a", "b")).contains("COALESCE(a, b)");
            assertThat(render("Coalesce", GENERIC, "a", "b", "c")).contains("COALESCE(a, b, c)");
            assertThat(render("Nz", GENERIC, "a", "b")).contains("COALESCE(a, b)");
            // IS NULL is an operator rather than a call — the one spelling that is not
            // a function name and arguments.
            assertThat(render("IsNull", GENERIC, "x")).contains("(x IS NULL)");
        }

        @Test
        @DisplayName("Replace, the one string function whose SQL means the same thing")
        void replace() {
            assertThat(render("Replace", MYSQL, "`s`", "'a'", "'b'"))
                    .contains("REPLACE(`s`, 'a', 'b')");
        }

        /**
         * {@code Nz(value)} substitutes the empty string for a STRING and NULL for
         * anything else, so what it returns depends on the argument's <em>type</em> —
         * which a spelling is never told. There is nothing to render, so it declines and
         * the call is evaluated where the type is known.
         */
        @Test
        @DisplayName("Nz declines its one-argument form, whose answer depends on a type")
        void nzOneArgument() {
            assertThat(render("Nz", GENERIC, "a")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Fix — the rounding SQL never named once")
    final class TruncationTowardsZero {

        @Test
        @DisplayName("MySQL and Postgres each have a name for it; the generic dialect gets neither")
        void perDialect() {
            assertThat(render("Fix", MYSQL, "`x`")).contains("TRUNCATE(`x`, 0)");
            assertThat(render("Fix", POSTGRES, "\"x\"")).contains("TRUNC(\"x\")");
            assertThat(render("Fix", GENERIC, "x")).isEmpty();
        }
    }

    @Nested
    @DisplayName("The string library, where SQL's same-named function is not always the same function")
    final class Strings {

        /**
         * Counting and slicing agree with MySQL and with Postgres, and not with H2 — and
         * that split is the whole reason the spelling names its dialects instead of
         * answering for SQL at large. relix counts code points; MySQL and Postgres count
         * characters, which is the same thing; H2 counts UTF-16 code units, which is not
         * — so {@code CHAR_LENGTH} there is a different function wearing a familiar name.
         *
         * <p>Postgres was added when there was a Postgres to run: it is the agreement
         * suite that decides this, over a fixture holding an astral character, and the
         * dialect list here follows what that measured rather than what the manuals say.
         */
        @Test
        @DisplayName("counting and slicing are offered to the two dialects that count the same unit")
        void countingAndSlicing() {
            assertThat(render("Len", MYSQL, "`s`")).contains("CHAR_LENGTH(`s`)");
            assertThat(render("Left", MYSQL, "`s`", "2")).contains("LEFT(`s`, 2)");
            assertThat(render("Right", MYSQL, "`s`", "2")).contains("RIGHT(`s`, 2)");
            assertThat(render("Mid", MYSQL, "`s`", "2")).contains("SUBSTRING(`s`, 2)");
            assertThat(render("Mid", MYSQL, "`s`", "2", "3")).contains("SUBSTRING(`s`, 2, 3)");
            assertThat(render("Len", POSTGRES, "\"s\"")).contains("CHAR_LENGTH(\"s\")");
            assertThat(render("Left", POSTGRES, "\"s\"", "2")).contains("LEFT(\"s\", 2)");
            assertThat(render("Right", POSTGRES, "\"s\"", "2")).contains("RIGHT(\"s\", 2)");
            assertThat(render("Mid", POSTGRES, "\"s\"", "2")).contains("SUBSTRING(\"s\", 2)");
            assertThat(render("Mid", POSTGRES, "\"s\"", "2", "3")).contains("SUBSTRING(\"s\", 2, 3)");
        }

        @Test
        @DisplayName("and to no dialect that has not been run — the generic one included")
        void notOfferedElsewhere() {
            for (String function : List.of("Len", "Left", "Right", "Mid")) {
                for (PushdownTarget target : List.of(GENERIC, MONGO)) {
                    assertThat(render(function, target, "a", "b"))
                            .as("%s on %s", function, target).isEmpty();
                    assertThat(render(function, target, "a"))
                            .as("%s on %s", function, target).isEmpty();
                }
            }
        }

        /**
         * The three that decline everywhere, each one a property of SQL rather than of
         * the function — measured against a real MySQL by the agreement suite in
         * {@code relix-connectors-std}, not read off a manual.
         */
        @Test
        @DisplayName("case, trimming and search decline, whatever the dialect")
        void collationDependentDecline() {
            for (String function : List.of("UCase", "LCase", "Trim", "LTrim", "RTrim",
                    "InStr", "Chr", "Asc")) {
                for (PushdownTarget target : List.of(POSTGRES, MYSQL, GENERIC, MONGO)) {
                    assertThat(render(function, target, "a", "b"))
                            .as("%s on %s", function, target).isEmpty();
                    assertThat(render(function, target, "a"))
                            .as("%s on %s", function, target).isEmpty();
                }
            }
        }
    }

    /**
     * The transcendental numerics, which decline for two measured reasons rather than one
     * suspected one.
     *
     * <p><b>A backend computes a different number.</b> MySQL answers {@code TAN(4)} with
     * {@code 1.1578212823495775} and this JVM with {@code 1.1578212823495777} — one unit
     * in the last place, which is within what a transcendental implementation is allowed
     * and is enough to make the same query print different text depending on where it
     * ran. H2 agrees with the engine, and proves nothing by it: it computes on this JVM.
     *
     * <p><b>And four of them have no answer for some arguments.</b> {@code Sqr} rejects a
     * negative, {@code Log} a non-positive, and neither {@code Exp} nor {@code Power} can
     * return the infinity a {@code double} overflows to — where SQL hands back NULL or
     * NaN. A query that succeeds folded and raises unfolded is worse than one that
     * disagrees in the last digit.
     *
     * <p>Both are checked where they can be, by the agreement suite in
     * {@code relix-connectors-std}: the first needed a second backend to see at all, and
     * the second needed the harness to compare failures as well as rows.
     */
    /**
     * DuckDB, whose SQL is PostgreSQL's in every place a spelling differs — each one run
     * against a DuckDB by the agreement suite, not assumed from the resemblance.
     */
    @Nested
    @DisplayName("DuckDB takes PostgreSQL's spellings")
    final class DuckDb {

        @Test
        @DisplayName("date_trunc in relix's argument order, and TRUNC for Fix")
        void perDialect() {
            assertThat(render("DATE_TRUNC", DUCKDB, "'day'", "\"at\""))
                    .contains("date_trunc('day', \"at\")");
            assertThat(render("Fix", DUCKDB, "\"x\"")).contains("TRUNC(\"x\")");
        }

        @Test
        @DisplayName("it counts code points, so counting and slicing are offered to it")
        void countingAndSlicing() {
            assertThat(render("Len", DUCKDB, "\"s\"")).contains("CHAR_LENGTH(\"s\")");
            assertThat(render("Left", DUCKDB, "\"s\"", "2")).contains("LEFT(\"s\", 2)");
            assertThat(render("Right", DUCKDB, "\"s\"", "2")).contains("RIGHT(\"s\", 2)");
            assertThat(render("Mid", DUCKDB, "\"s\"", "2", "3")).contains("SUBSTRING(\"s\", 2, 3)");
        }
    }

    /**
     * SQLite, which lacks what the others share: no date types and so no {@code EXTRACT},
     * no {@code LEFT}/{@code RIGHT}/{@code CHAR_LENGTH}, and rounding in floating point.
     */
    @Nested
    @DisplayName("SQLite, spelled around what it lacks")
    final class Sqlite {

        @Test
        @DisplayName("counting and slicing under SQLite's own names")
        void countingAndSlicing() {
            assertThat(render("Len", SQLITE, "\"s\"")).contains("LENGTH(\"s\")");
            assertThat(render("Left", SQLITE, "\"s\"", "2")).contains("SUBSTR(\"s\", 1, 2)");
            assertThat(render("Mid", SQLITE, "\"s\"", "2")).contains("SUBSTR(\"s\", 2)");
            assertThat(render("Mid", SQLITE, "\"s\"", "2", "3")).contains("SUBSTR(\"s\", 2, 3)");
        }

        @Test
        @DisplayName("Right counts its start from the left, so n = 0 is empty rather than everything")
        void right() {
            assertThat(render("Right", SQLITE, "\"s\"", "2"))
                    .contains("SUBSTR(\"s\", MAX(LENGTH(\"s\") - 2, 0) + 1)");
        }

        @Test
        @DisplayName("no temporal function folds: there is no date type to read")
        void noTemporalFunctions() {
            for (String function : List.of("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND")) {
                assertThat(render(function, SQLITE, "\"at\"")).as(function).isEmpty();
            }
            assertThat(render("DATE_TRUNC", SQLITE, "'day'", "\"at\"")).isEmpty();
        }

        @Test
        @DisplayName("the rounding functions decline, answering in floating point there")
        void noRounding() {
            assertThat(render("Round", SQLITE, "x")).isEmpty();
            assertThat(render("Round", SQLITE, "x", "2")).isEmpty();
            assertThat(render("Int", SQLITE, "x")).isEmpty();
            assertThat(render("Ceil", SQLITE, "x")).isEmpty();
            assertThat(render("Fix", SQLITE, "x")).isEmpty();
        }

        @Test
        @DisplayName("each respelling answers only for its own argument count")
        void wrongArgumentCount() {
            assertThat(render("Left", SQLITE, "\"s\"")).isEmpty();
            assertThat(render("Right", SQLITE, "\"s\"", "2", "3")).isEmpty();
            assertThat(render("Int", POSTGRES, "x", "y")).isEmpty();
        }

        @Test
        @DisplayName("the exact ones fold as everywhere else")
        void exact() {
            assertThat(render("Abs", SQLITE, "x")).contains("ABS(x)");
            assertThat(render("Sgn", SQLITE, "x")).contains("SIGN(x)");
            assertThat(render("Replace", SQLITE, "s", "'a'", "'b'")).contains("REPLACE(s, 'a', 'b')");
            assertThat(render("IsNull", SQLITE, "x")).contains("(x IS NULL)");
        }
    }

    /**
     * SQL Server, whose T-SQL names half of these differently and whose default collation
     * ignores case — each spelling run against a SQL Server by the agreement suite.
     */
    @Nested
    @DisplayName("SQL Server, in T-SQL's own names")
    final class SqlServer {

        @Test
        @DisplayName("an extraction is DATEPART over the value switched to UTC")
        void extraction() {
            assertThat(render("YEAR", SQLSERVER, "[at]"))
                    .contains("DATEPART(YEAR, SWITCHOFFSET([at], '+00:00'))");
            assertThat(render("SECOND", SQLSERVER, "[at]"))
                    .contains("DATEPART(SECOND, SWITCHOFFSET([at], '+00:00'))");
        }

        @Test
        @DisplayName("DATE_TRUNC declines: DATETRUNC is 2022's, a version no declared dialect confirms")
        void noDateTrunc() {
            assertThat(render("DATE_TRUNC", SQLSERVER, "'day'", "[at]")).isEmpty();
        }

        @Test
        @DisplayName("ROUND always takes its places, and truncates through its third argument")
        void rounding() {
            assertThat(render("Round", SQLSERVER, "x")).contains("ROUND(x, 0)");
            assertThat(render("Round", SQLSERVER, "x", "2")).contains("ROUND(x, 2)");
            assertThat(render("Fix", SQLSERVER, "x")).contains("ROUND(x, 0, 1)");
            assertThat(render("Int", SQLSERVER, "x")).contains("FLOOR(x)");
            assertThat(render("Ceil", SQLSERVER, "x")).contains("CEILING(x)");
        }

        @Test
        @DisplayName("IsNull is the BIT the question answers as")
        void isNull() {
            assertThat(render("IsNull", SQLSERVER, "[x]"))
                    .contains("(CASE WHEN [x] IS NULL THEN 1 ELSE 0 END)");
        }

        @Test
        @DisplayName("Replace matches under a binary collation, the default ignoring case")
        void replace() {
            assertThat(render("Replace", SQLSERVER, "[s]", "N'a'", "N'b'"))
                    .contains("REPLACE(([s]) COLLATE Latin1_General_100_BIN2, N'a', N'b')");
        }

        @Test
        @DisplayName("counting and slicing decline: LEN counts UTF-16 units and drops trailing spaces")
        void noCountingOrSlicing() {
            for (String function : List.of("Len", "Left", "Right", "Mid")) {
                assertThat(render(function, SQLSERVER, "a")).as(function).isEmpty();
                assertThat(render(function, SQLSERVER, "a", "b")).as(function).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("the transcendental numerics decline: a different double, or no answer at all")
    void transcendentalsDecline() {
        for (String function : List.of("Sqr", "Log", "Exp", "Sin", "Cos", "Tan", "Atn",
                "Power")) {
            for (PushdownTarget target : List.of(POSTGRES, MYSQL, GENERIC, DUCKDB, SQLITE, SQLSERVER, MONGO)) {
                assertThat(render(function, target, "a"))
                        .as("%s on %s", function, target).isEmpty();
                assertThat(render(function, target, "a", "b"))
                        .as("%s on %s", function, target).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("every other built-in is evaluated in-engine, and declines every target")
    void everythingElseDeclines() {
        List<String> spelled = List.of("year", "month", "day", "hour", "minute", "second",
                "date_trunc", "abs", "int", "ceil", "sgn", "round", "fix", "replace",
                "coalesce", "nz", "isnull", "len", "left", "right", "mid");

        List<String> unexpected = BuiltinCalls.all().stream()
                .filter(fn -> !spelled.contains(fn.signature().canonicalName()))
                .filter(PushdownSpellingsTest::spellsAnything)
                .map(fn -> fn.signature().name())
                .collect(Collectors.toList());

        assertThat(unexpected)
                .as("a built-in that grew a backend spelling — welcome, but the planner's "
                        + "renderer tests are the regression net for what it emits")
                .isEmpty();
    }

    private static boolean spellsAnything(ScalarFunction fn) {
        List<String> arguments = List.of("a", "b", "c");
        for (PushdownTarget target : List.of(POSTGRES, MYSQL, GENERIC, DUCKDB, SQLITE, SQLSERVER, MONGO)) {
            for (int count = 0; count <= arguments.size(); count++) {
                if (fn.pushdown().render(target, arguments.subList(0, count)).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }
}

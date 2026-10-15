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

import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.processor.internal.QueryResult;
import com.darkcollective.relix.semantic.internal.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every built-in scalar function is invoked in its <em>documented</em> form and
 * its result is checked for correctness — the guard against a function being
 * broken as badly as {@code IIf} once was (its documented comparison-argument
 * form failed to parse, and nothing executed it, so nothing noticed).
 *
 * <p>Two protections working together:
 * <ol>
 *   <li>{@link #everyDocumentedInvocationIsReasonable()} runs one canonical
 *       invocation per function end-to-end (full analyze → plan → execute) and
 *       asserts the value — so a function that stops parsing, stops executing, or
 *       returns nonsense fails loudly.</li>
 *   <li>{@link #everyInstalledFunctionHasACoverageCase()} cross-checks the case
 *       table against the {@linkplain ExecutionContext#installedFunctions() installed
 *       function libraries}, so adding a function without a coverage case fails the
 *       build — the gap can never silently reopen. It reads the catalogue rather than
 *       one library's own table, which holds <em>every</em> installed library to this
 *       standard rather than only the shipped one.</li>
 * </ol>
 *
 * <p>Non-deterministic functions (NOW/CURRENT_DATE/CURRENT_TIME/Rand) are matched
 * by shape (regex); NULL-handling paths of Nz/Coalesce/IsNull are covered by
 * {@code OperandEvaluatorTest} (the {@code ⊥} null literal is only valid in
 * predicate position, not as a bare operand).
 */
@DisplayName("Function coverage — every built-in invoked in documented form")
final class FunctionCoverageTest {

    private enum Match { EXACT, NUMBER, REGEX }

    /** name = the impl-table key; invocation = documented form; expected per {@link Match}. */
    private record Case(String name, String invocation, Match match, String expected) {}

    private static Case exact(String n, String inv, String exp) { return new Case(n, inv, Match.EXACT, exp); }
    private static Case num(String n, String inv, String exp)   { return new Case(n, inv, Match.NUMBER, exp); }
    private static Case regex(String n, String inv, String re)  { return new Case(n, inv, Match.REGEX, re); }

    private static final String TS = "TIMESTAMP '2026-07-08T13:40:30Z'";

    private static final List<Case> CASES = List.of(
            // ── String ──────────────────────────────────────────────────────────
            num  ("len",     "Len(\"hello\")",                     "5"),
            exact("ucase",   "UCase(\"abc\")",                     "ABC"),
            exact("lcase",   "LCase(\"ABC\")",                     "abc"),
            exact("trim",    "Trim(\"  hi  \")",                   "hi"),
            exact("ltrim",   "LTrim(\"  hi\")",                    "hi"),
            exact("rtrim",   "RTrim(\"hi  \")",                    "hi"),
            exact("left",    "Left(\"hello\", 2)",                 "he"),
            exact("right",   "Right(\"hello\", 2)",                "lo"),
            exact("mid",     "Mid(\"hello\", 2, 3)",               "ell"),
            num  ("instr",   "InStr(\"hello\", \"l\")",            "3"),
            exact("chr",     "Chr(65)",                            "A"),
            num  ("asc",     "Asc(\"A\")",                         "65"),
            exact("replace", "Replace(\"a-b-c\", \"-\", \"+\")",   "a+b+c"),
            // ── Similarity ──────────────────────────────────────────────────────
            num  ("levenshtein",    "Levenshtein(\"kitten\", \"sitting\")",   "3"),
            num  ("editsimilarity", "EditSimilarity(\"abcd\", \"abxd\")",     "0.75"),
            num  ("jarowinkler",    "JaroWinkler(\"smith\", \"smith\")",      "1"),
            num  ("trigram",        "Trigram(\"a\", \"ab\")",                 "0.25"),
            exact("soundex",        "Soundex(\"Robert\")",                    "R163"),
            // ── Math ────────────────────────────────────────────────────────────
            num  ("abs",     "Abs(-7)",                            "7"),
            num  ("int",     "Int(-2.5)",                          "-3"),   // floor
            num  ("fix",     "Fix(-2.5)",                          "-2"),   // toward zero
            num  ("round",   "Round(3.14159, 2)",                  "3.14"),
            num  ("ceil",    "Ceil(2.1)",                          "3"),
            num  ("sgn",     "Sgn(-3)",                            "-1"),
            num  ("sqr",     "Sqr(9)",                             "3"),
            num  ("log",     "Log(1)",                             "0"),
            num  ("exp",     "Exp(0)",                             "1"),
            num  ("sin",     "Sin(0)",                             "0"),
            num  ("cos",     "Cos(0)",                             "1"),
            num  ("tan",     "Tan(0)",                             "0"),
            num  ("atn",     "Atn(0)",                             "0"),
            num  ("log10",   "Log10(1000)",                        "3"),
            num  ("asin",    "Round(Asin(1) * 2, 10)",             "3.1415926536"),
            num  ("acos",    "Acos(1)",                            "0"),
            num  ("atan2",   "Round(Atan2(1, -1), 10)",            "2.3561944902"),
            num  ("degrees", "Degrees(Pi())",                      "180"),
            num  ("radians", "Radians(180) - Pi()",                "0"),
            num  ("power",   "Power(2, 10)",                       "1024"),
            num  ("mod",     "Mod(-7, 3)",                         "-1"),
            num  ("pi",      "Pi()",                               "3.141592653589793"),
            regex("rand",    "Rand()",                             "^0(\\.\\d+)?$"),
            // ── Date/Time (typed, ADR-0013) ─────────────────────────────────────
            regex("now",          "NOW()",          "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$"),
            regex("current_date", "CURRENT_DATE()", "^\\d{4}-\\d{2}-\\d{2}$"),
            regex("current_time", "CURRENT_TIME()", "^\\d{2}:\\d{2}(:\\d{2})?(\\.\\d+)?$"),
            num  ("year",    "YEAR("   + TS + ")",                 "2026"),
            num  ("month",   "MONTH("  + TS + ")",                 "7"),
            num  ("day",     "DAY("    + TS + ")",                 "8"),
            num  ("hour",    "HOUR("   + TS + ")",                 "13"),
            num  ("minute",  "MINUTE(" + TS + ")",                 "40"),
            num  ("second",  "SECOND(" + TS + ")",                 "30"),
            num  ("dow",     "DOW("    + TS + ")",                 "3"),
            num  ("isodow",  "ISODOW(" + TS + ")",                 "3"),
            num  ("doy",     "DOY("    + TS + ")",                 "189"),
            num  ("week",    "WEEK("   + TS + ")",                 "28"),
            num  ("isoyear", "ISOYEAR(" + TS + ")",                "2026"),
            num  ("quarter", "QUARTER(" + TS + ")",                "3"),
            num  ("epoch",   "EPOCH("  + TS + ")",                 "1783518030"),
            exact("date_trunc", "DATE_TRUNC('day', " + TS + ")",  "2026-07-08T00:00:00Z"),
            num  ("minutes", "MINUTES(DURATION 'PT2H')",           "120"),
            num  ("seconds", "SECONDS(DURATION 'PT90S')",          "90"),
            num  ("days",    "DAYS(DURATION 'P3D')",               "3"),
            exact("to_date",      "to_date(\"2026-07-08\")",                   "2026-07-08"),
            exact("to_timestamp", "to_timestamp(\"2026-07-08T13:40:30Z\")",    "2026-07-08T13:40:30Z"),
            exact("to_time",      "to_time(\"13:40:30\")",                     "13:40:30"),
            // ── Conditional ─────────────────────────────────────────────────────
            exact("iif",     "IIf(3 > 2, \"y\", \"n\")",           "y"),   // comparison-arg regression guard
            exact("nz",      "Nz(\"present\", \"fallback\")",      "present"),
            exact("nullif",  "NULLIF(\"kept\", \"n/a\")",         "kept"),
            exact("coalesce","Coalesce(\"first\", \"second\")",    "first"),
            exact("switch",  "Switch(1 > 2, \"a\", 3 > 2, \"b\", \"c\")", "b"),  // first match wins; else default
            exact("choose",  "Choose(2, \"a\", \"b\", \"c\")",           "b"),  // 1-based positional pick
            // ── Type check ──────────────────────────────────────────────────────
            exact("isnull",   "IsNull(\"x\")",                     "false"),
            exact("isnumeric","IsNumeric(\"123\")",                "true"),
            // ── Conversion ──────────────────────────────────────────────────────
            exact("cstr",    "CStr(42)",                           "42"),
            num  ("cint",    "CInt(3.7)",                          "4"),
            num  ("cdbl",    "CDbl(\"3.14\")",                     "3.14"),
            exact("cbool",   "CBool(\"yes\")",                     "true"),
            // ── SQL names ───────────────────────────────────────────────────────
            exact("upper",       "UPPER(\"abc\")",                  "ABC"),
            exact("lower",       "LOWER(\"ABC\")",                  "abc"),
            num  ("length",      "LENGTH(\"hello\")",               "5"),
            num  ("char_length", "CHAR_LENGTH(\"hello\")",          "5"),
            exact("char",        "CHAR(65)",                         "A"),
            num  ("floor",       "FLOOR(-3.5)",                      "-4"),
            num  ("ceiling",     "CEILING(-3.5)",                    "-3"),
            num  ("sqrt",        "SQRT(9)",                          "3"),
            num  ("sign",        "SIGN(-7)",                         "-1"),
            num  ("ln",          "LN(1)",                            "0"),
            regex("random",      "RANDOM()",                         "^0(\\.\\d+)?$"),
            exact("substring",   "SUBSTRING(\"hello\" FROM 2 FOR 3)", "ell"),
            exact("substr",      "SUBSTR(\"hello\", 3)",             "llo"),
            num  ("strpos",      "POSITION(\"l\" IN \"hello\")",     "3"),
            num  ("charindex",   "CHARINDEX(\"l\", \"hello\")",      "3"),
            num  ("ascii",       "ASCII(\"A\")",                    "65"),
            exact("concat",      "CONCAT(\"a\", 1, \"b\")",           "a1b"),
            num  ("trunc",       "TRUNC(2.78, 1)",                   "2.7"),
            num  ("truncate",    "TRUNCATE(1234, -2)",               "1200"),
            exact("lpad",        "LPAD(\"hi\", 5, \"xy\")",           "xyxhi"),
            exact("rpad",        "RPAD(\"hi\", 3, \"!\")",            "hi!"),
            exact("reverse",     "REVERSE(\"hello\")",              "olleh"),
            exact("repeat",      "REPEAT(\"ab\", 3)",               "ababab"),
            exact("replicate",   "REPLICATE(\"ab\", 2)",            "abab"),
            exact("split_part",  "SPLIT_PART(\"a,b,c\", \",\", -1)",  "c"),
            exact("concat_ws",   "CONCAT_WS(\"-\", \"a\", 1)",        "a-1"),
            exact("initcap",     "INITCAP(\"hELLO wORLD\")",        "Hello World"),
            exact("regexp_like",    "REGEXP_LIKE(\"abc123\", \"[0-9]+\")",       "true"),
            exact("regexp_replace", "REGEXP_REPLACE(\"abc\", \"b\", \"X\")",     "aXc"),
            exact("regexp_substr",  "REGEXP_SUBSTR(\"abc123\", \"[0-9]+\")",     "123"),
            num  ("regexp_count",   "REGEXP_COUNT(\"a1b2\", \"[0-9]\")",         "2"),
            // ── Nested data ─────────────────────────────────────────────────────
            // Its argument is a constructed struct, since no literal spells an object.
            exact("entries", "Entries({ us: 3, gb: 7 })",
                    "[{key: us, value: 3}, {key: gb, value: 7}]"),
            // ── Ordering ────────────────────────────────────────────────────────
            num  ("least",    "LEAST(5, 2, 8)",                    "2"),
            num  ("greatest", "GREATEST(5, 2, 8)",                 "8")
    );

    /** Evaluates a scalar expression over a one-row relation, returning its display string. */
    private static String eval(String expression) {
        String src = "D := [| x |\n| 1 |];\nquery { π " + expression + " → r (D) };";
        SemanticResult result = analyze(src);
        assertThat(result.errors()).as("semantic errors for: %s", expression).isEmpty();
        List<QueryResult> results = new QueryExecutor().execute(result);
        return results.get(0).rows().get(0).get("r").asDisplayString();
    }

    private static boolean matches(Case c, String actual) {
        return switch (c.match()) {
            case EXACT  -> actual.equals(c.expected());
            case NUMBER -> new BigDecimal(actual).compareTo(new BigDecimal(c.expected())) == 0;
            case REGEX  -> actual.matches(c.expected());
        };
    }

    @Test
    @DisplayName("every documented invocation parses, executes, and returns the expected value")
    void everyDocumentedInvocationIsReasonable() {
        List<String> failures = new ArrayList<>();
        for (Case c : CASES) {
            try {
                String actual = eval(c.invocation());
                if (!matches(c, actual)) {
                    failures.add(String.format("%s: %s => \"%s\" (expected %s \"%s\")",
                            c.name(), c.invocation(), actual, c.match(), c.expected()));
                }
            } catch (RuntimeException e) {
                failures.add(String.format("%s: %s threw %s: %s",
                        c.name(), c.invocation(), e.getClass().getSimpleName(), e.getMessage()));
            }
        }
        assertThat(failures).as("function invocations that did not behave as documented").isEmpty();
    }

    @Test
    @DisplayName("every installed scalar function has a coverage case")
    void everyInstalledFunctionHasACoverageCase() {
        Set<String> installed = ExecutionContext.installedFunctions().scalars().stream()
                .map(ScalarFunction::name)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Set<String> covered = CASES.stream()
                .map(c -> c.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertThat(covered)
                .as("a scalar function is installed but has no documented-invocation "
                        + "coverage case in FunctionCoverageTest — add one so it can never "
                        + "silently break")
                .containsAll(installed);
        assertThat(installed)
                .as("no function library is installed, so this guard would pass "
                        + "vacuously")
                .isNotEmpty();
    }
}

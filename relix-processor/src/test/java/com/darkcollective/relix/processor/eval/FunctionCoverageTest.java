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
            num  ("power",   "Power(2, 10)",                       "1024"),
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
            exact("coalesce","Coalesce(\"first\", \"second\")",    "first"),
            // ── Type check ──────────────────────────────────────────────────────
            exact("isnull",   "IsNull(\"x\")",                     "false"),
            exact("isnumeric","IsNumeric(\"123\")",                "true"),
            // ── Conversion ──────────────────────────────────────────────────────
            exact("cstr",    "CStr(42)",                           "42"),
            num  ("cint",    "CInt(3.7)",                          "4"),
            num  ("cdbl",    "CDbl(\"3.14\")",                     "3.14"),
            // ── Nested data ─────────────────────────────────────────────────────
            // Its argument is a constructed struct, since no literal spells an object.
            exact("entries", "Entries({ us: 3, gb: 7 })",
                    "[{key: us, value: 3}, {key: gb, value: 7}]")
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

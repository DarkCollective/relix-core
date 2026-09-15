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

import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.PINNED;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every function this library declares is invoked once and its result checked — and a
 * function declared without such a case fails the build.
 *
 * <p>The per-category suites test each function properly, in the forms that matter for
 * it. This one tests nothing properly and every function once, which is the complement:
 * it is what notices a <em>new</em> declaration that nothing exercises, and a declaration
 * nothing exercises is a lambda nobody has ever run.
 *
 * <p>It runs against the SPI alone, with no engine anywhere near it. That is the claim
 * the whole design rests on — a library is testable by whoever wrote it — so the guard
 * has to live here rather than only in the executor's suite, which can only reach a
 * function through a script.
 */
@DisplayName("Coverage — every declared function is invoked")
final class FunctionCoverageTest {

    private enum Match { EXACT, NUMBER }

    /** name = the declared name; arguments = a canonical call; expected per {@link Match}. */
    private record Case(String name, List<Value> arguments, Match match, String expected) {
    }

    private static Case exact(String name, String expected, Value... arguments) {
        return new Case(name, List.of(arguments), Match.EXACT, expected);
    }

    private static Case num(String name, String expected, Value... arguments) {
        return new Case(name, List.of(arguments), Match.NUMBER, expected);
    }

    /** The pinned clock's instant as a value, so the temporal cases have a fixed answer. */
    private static final Value TS = new TimestampValue(PINNED);

    private static final List<Case> CASES = List.of(
            // ── String ──────────────────────────────────────────────────────────
            num("Len", "5", s("hello")),
            exact("UCase", "ABC", s("abc")),
            exact("LCase", "abc", s("ABC")),
            exact("Trim", "hi", s("  hi  ")),
            exact("LTrim", "hi", s("  hi")),
            exact("RTrim", "hi", s("hi  ")),
            exact("Left", "he", s("hello"), n("2")),
            exact("Right", "lo", s("hello"), n("2")),
            exact("Mid", "ell", s("hello"), n("2"), n("3")),
            num("InStr", "3", s("hello"), s("l")),
            exact("Chr", "A", n("65")),
            num("Asc", "65", s("A")),
            exact("Replace", "a+b+c", s("a-b-c"), s("-"), s("+")),
            // ── Math ────────────────────────────────────────────────────────────
            num("Abs", "7", n("-7")),
            num("Int", "-3", n("-2.5")),          // floor
            num("Fix", "-2", n("-2.5")),          // toward zero
            num("Round", "3.14", n("3.14159"), n("2")),
            num("Ceil", "3", n("2.1")),
            num("Sgn", "-1", n("-3")),
            num("Sqr", "3", n("9")),
            num("Log", "0", n("1")),
            num("Exp", "1", n("0")),
            num("Sin", "0", n("0")),
            num("Cos", "1", n("0")),
            num("Tan", "0", n("0")),
            num("Atn", "0", n("0")),
            num("Power", "1024", n("2"), n("10")),
            // ── Date/time ───────────────────────────────────────────────────────
            exact("NOW", "2026-07-08T13:40:30Z"),
            exact("CURRENT_DATE", "2026-07-08"),
            exact("CURRENT_TIME", "13:40:30"),
            num("YEAR", "2026", TS),
            num("MONTH", "7", TS),
            num("DAY", "8", TS),
            num("HOUR", "13", TS),
            num("MINUTE", "40", TS),
            num("SECOND", "30", TS),
            exact("DATE_TRUNC", "2026-07-08T00:00:00Z", s("day"), TS),
            num("MINUTES", "120", duration("PT2H")),
            num("SECONDS", "90", duration("PT90S")),
            num("DAYS", "3", duration("P3D")),
            exact("to_date", "2026-07-08", s("2026-07-08")),
            exact("to_timestamp", "2026-07-08T13:40:30Z", s("2026-07-08T13:40:30Z")),
            exact("to_time", "13:40:30", s("13:40:30")),
            // ── Conditional ─────────────────────────────────────────────────────
            exact("IIf", "y", com.darkcollective.relix.value.BooleanValue.TRUE, s("y"), s("n")),
            exact("Nz", "fallback", NullValue.INSTANCE, s("fallback")),
            exact("Coalesce", "first", NullValue.INSTANCE, s("first")),
            // ── Type check ──────────────────────────────────────────────────────
            exact("IsNull", "false", s("x")),
            exact("IsNumeric", "true", s("123")),
            // ── Conversion ──────────────────────────────────────────────────────
            exact("CStr", "42", n("42")),
            num("CInt", "4", n("3.7")),
            num("CDbl", "3.14", s("3.14")),
            // ── Nested ──────────────────────────────────────────────────────────
            exact("Entries", "[{key: us, value: 3}]", object("us", n("3"))));

    /**
     * {@code Rand} is deliberately absent from the table above and named here instead:
     * it has no expected value, and asserting a range is the job of {@code MathFunctionsTest}.
     */
    private static final Set<String> COVERED_ELSEWHERE = Set.of("rand");

    private static Value duration(String iso) {
        return new DurationValue(Duration.parse(iso));
    }

    /** A one-field struct, for the nested case — the only kind of argument no scalar is. */
    private static Value object(String field, Value value) {
        return new com.darkcollective.relix.value.StructValue(
                java.util.Map.of(field, value));
    }

    @Test
    @DisplayName("each canonical call returns the value the function documents")
    void everyCaseReturnsItsDocumentedValue() {
        List<String> failures = new ArrayList<>();
        for (Case c : CASES) {
            try {
                String actual = call(c.name(), c.arguments().toArray(Value[]::new))
                        .asDisplayString();
                if (!matches(c, actual)) {
                    failures.add(c.name() + " => \"" + actual + "\" (expected " + c.match()
                            + " \"" + c.expected() + "\")");
                }
            } catch (RuntimeException e) {
                failures.add(c.name() + " threw " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        }
        assertThat(failures).as("calls that did not behave as documented").isEmpty();
    }

    @Test
    @DisplayName("every declared function has a case, so a new one cannot arrive unrun")
    void everyDeclaredFunctionHasACase() {
        Set<String> declared = BuiltinCalls.all().stream()
                .map(fn -> fn.signature().canonicalName())
                .collect(Collectors.toSet());
        Set<String> covered = CASES.stream()
                .map(c -> c.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        assertThat(covered)
                .as("a function is declared but never invoked — add a canonical case, or "
                        + "name it in COVERED_ELSEWHERE with the suite that does invoke it")
                .containsAll(declared.stream()
                        .filter(name -> !COVERED_ELSEWHERE.contains(name))
                        .collect(Collectors.toSet()));
    }

    private static boolean matches(Case c, String actual) {
        return switch (c.match()) {
            case EXACT -> actual.equals(c.expected());
            case NUMBER -> new BigDecimal(actual).compareTo(new BigDecimal(c.expected())) == 0;
        };
    }
}

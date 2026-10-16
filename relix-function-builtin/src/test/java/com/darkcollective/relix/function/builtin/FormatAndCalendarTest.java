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
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.number;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TO_CHAR, FORMAT, AT TIME ZONE and the calendar helpers, against PostgreSQL's answers (#139). */
@DisplayName("Formatting, time zones and calendar helpers (#139)")
final class FormatAndCalendarTest {

    private static final Value NULL = NullValue.INSTANCE;

    private static Value ts(String iso) {
        return new TimestampValue(Instant.parse(iso));
    }

    private static Value date(String iso) {
        return new DateValue(LocalDate.parse(iso));
    }

    private static String toChar(Value v, String pattern) {
        return text(call("TO_CHAR", v, s(pattern)));
    }

    @Test
    @DisplayName("TO_CHAR writes each date-time field as PostgreSQL's to_char does")
    void dateTimePatterns() {
        Value t = ts("2026-07-08T13:40:30.123456Z");
        assertThat(toChar(t, "YYYY-MM-DD HH24:MI:SS.US")).isEqualTo("2026-07-08 13:40:30.123456");
        assertThat(toChar(t, "HH12:MI:SS.MS AM")).isEqualTo("01:40:30.123 PM");
        assertThat(toChar(t, "hh am a.m. P.M.")).isEqualTo("01 pm p.m. P.M.");
        assertThat(toChar(t, "Am Mon")).isEqualTo("pm Jul");
        assertThat(toChar(t, "Day, DD Month YYYY")).isEqualTo("Wednesday, 08 July      2026");
        assertThat(toChar(t, "FMDay, FMDD FMMonth YYYY")).isEqualTo("Wednesday, 8 July 2026");
        assertThat(toChar(t, "Mon MON mon DY Dy dy day")).isEqualTo("Jul JUL jul WED Wed wed wednesday");
        assertThat(toChar(t, "YYY YY Y DDD D ID IW WW Q")).isEqualTo("026 26 6 189 4 3 28 27 3");
        assertThat(toChar(t, "\"Quarter\" Q, \"week\" FMIW")).isEqualTo("Quarter 3, week 28");
        assertThat(toChar(date("2021-01-01"), "IYYY-IW YYYY")).isEqualTo("2020-53 2021");
        assertThat(toChar(date("2026-01-01"), "HH24:MI \"unclosed")).isEqualTo("00:00 unclosed");
        assertThat(toChar(ts("2026-07-08T00:05:00Z"), "HH12 FMHH12 FMX")).isEqualTo("12 12 FMX");
    }

    @Test
    @DisplayName("TO_CHAR writes a number by 9, 0, the point, the group and FM, as PostgreSQL's does")
    void numberPatterns() {
        assertThat(toChar(n("1234.5"), "9,999.00")).isEqualTo(" 1,234.50");
        assertThat(toChar(n("-1234.5"), "FM9G999D00")).isEqualTo("-1,234.50");
        assertThat(toChar(n("12"), "9999")).isEqualTo("   12");
        assertThat(toChar(n("-12"), "9999")).isEqualTo("  -12");
        assertThat(toChar(n("12"), "9,999")).isEqualTo("    12");
        assertThat(toChar(n("0.5"), "0.9")).isEqualTo(" 0.5");
        assertThat(toChar(n("0.5"), "9.9")).isEqualTo("  .5");
        assertThat(toChar(n("0"), "999")).isEqualTo("   0");
        assertThat(toChar(n("7"), "000")).isEqualTo(" 007");
        assertThat(toChar(n("12345"), "999")).isEqualTo(" ###");
        assertThat(toChar(n("12345.6"), "999.9")).isEqualTo(" ###.#");
        assertThat(toChar(n("1.5"), "FM9.99")).isEqualTo("1.5");
        assertThat(toChar(n("1"), "FM9.99")).isEqualTo("1.");
        assertThat(toChar(n("2.345"), "9.99")).isEqualTo(" 2.35");
        assertThat(toChar(n("5"), "9 \"x\"")).isEqualTo(" 5 \"x\"");
        assertThat(toChar(n("5"), "9909")).isEqualTo("   05");
        assertThat(toChar(n("5"), "0,000")).isEqualTo(" 0,005");
        assertThat(toChar(n("5"), "9,099")).isEqualTo("   005");
        assertThat(toChar(n("123456"), "9,999")).isEqualTo(" #,###");
        assertThat(toChar(n("-12345"), "999")).isEqualTo(" ###");
        assertThat(toChar(n("1.25"), "9.9x9")).isEqualTo(" 1.2x5");
    }

    @Test
    @DisplayName("TO_CHAR passes a NULL through and names a value it cannot write")
    void toCharEdges() {
        assertThat(call("TO_CHAR", NULL, s("YYYY")).isNull()).isTrue();
        assertThat(call("TO_CHAR", ts("2026-01-01T00:00:00Z"), NULL).isNull()).isTrue();
        assertThatThrownBy(() -> call("TO_CHAR", s("x"), s("9")))
                .hasMessage("TO_CHAR: expected TIMESTAMP, DATE or NUMBER, got STRING");
    }

    @Test
    @DisplayName("FORMAT groups the digits and rounds half away from zero, as MySQL's does")
    void format() {
        assertThat(text(call("FORMAT", n("1234567.891"), n("2")))).isEqualTo("1,234,567.89");
        assertThat(text(call("FORMAT", n("-1234.5"), n("0")))).isEqualTo("-1,235");
        assertThat(text(call("FORMAT", n("0.125"), n("2")))).isEqualTo("0.13");
        assertThat(text(call("FORMAT", n("12"), n("-1")))).isEqualTo("12");
        assertThat(call("FORMAT", NULL, n("2")).isNull()).isTrue();
        assertThat(call("FORMAT", n("1"), NULL).isNull()).isTrue();
    }

    @Test
    @DisplayName("AT TIME ZONE follows the zone's clock across both of a year's DST changes")
    void timeZones() {
        assertThat(call("TIMEZONE", s("Europe/London"), ts("2026-03-29T00:30:00Z"))).isEqualTo(ts("2026-03-29T00:30:00Z"));
        assertThat(call("TIMEZONE", s("Europe/London"), ts("2026-03-29T01:30:00Z"))).isEqualTo(ts("2026-03-29T02:30:00Z"));
        assertThat(call("TIMEZONE", s("europe/london"), ts("2026-10-25T00:30:00Z"))).isEqualTo(ts("2026-10-25T01:30:00Z"));
        assertThat(call("TIMEZONE", s("Europe/London"), ts("2026-10-25T01:30:00Z"))).isEqualTo(ts("2026-10-25T01:30:00Z"));
        assertThat(call("TIMEZONE", s("UTC"), ts("2026-10-25T01:30:00Z"))).isEqualTo(ts("2026-10-25T01:30:00Z"));
        assertThat(call("TIMEZONE", NULL, ts("2026-10-25T01:30:00Z")).isNull()).isTrue();
        assertThat(call("TIMEZONE", s("UTC"), NULL).isNull()).isTrue();
        assertThatThrownBy(() -> call("TIMEZONE", s("Mars/Olympus"), ts("2026-01-01T00:00:00Z")))
                .hasMessageStartingWith("TIMEZONE: unknown time zone 'Mars/Olympus'");
        assertThatThrownBy(() -> call("TIMEZONE", s("UTC+2"), ts("2026-01-01T00:00:00Z")))
                .hasMessageStartingWith("TIMEZONE: unknown time zone 'UTC+2'");
        assertThatThrownBy(() -> call("TIMEZONE", s("UTC"), date("2026-01-01")))
                .hasMessage("TIMEZONE: expected TIMESTAMP or TIMESTAMPTZ, got DATE");
    }

    @Test
    @DisplayName("MAKE_DATE builds a date and refuses one the calendar lacks")
    void makeDate() {
        assertThat(call("MAKE_DATE", n("2024"), n("2"), n("29"))).isEqualTo(date("2024-02-29"));
        assertThat(call("MAKE_DATE", NULL, n("2"), n("29")).isNull()).isTrue();
        assertThatThrownBy(() -> call("MAKE_DATE", n("2023"), n("2"), n("29")))
                .hasMessage("MAKE_DATE: date field value out of range: 2023-2-29");
        assertThatThrownBy(() -> call("MAKE_DATE", n("0"), n("1"), n("1")))
                .hasMessage("MAKE_DATE: date field value out of range: 0-1-1");
    }

    @Test
    @DisplayName("LAST_DAY is the month's end, leap years included")
    void lastDay() {
        assertThat(call("LAST_DAY", date("2024-02-10"))).isEqualTo(date("2024-02-29"));
        assertThat(call("LAST_DAY", date("2023-02-10"))).isEqualTo(date("2023-02-28"));
        assertThat(call("LAST_DAY", ts("2026-12-31T23:59:59Z"))).isEqualTo(date("2026-12-31"));
        assertThat(call("LAST_DAY", NULL).isNull()).isTrue();
        assertThatThrownBy(() -> call("LAST_DAY", s("x"))).hasMessage("LAST_DAY: expected DATE or TIMESTAMP, got STRING");
    }

    @Test
    @DisplayName("DATEDIFF counts the unit's boundaries between the two, at a month's end and a year's")
    void dateDiff() {
        assertThat(diff("month", date("2024-01-31"), date("2024-02-01"))).isEqualByComparingTo("1");
        assertThat(diff("MM", date("2024-02-01"), date("2024-01-31"))).isEqualByComparingTo("-1");
        assertThat(diff("year", ts("2025-12-31T23:59:59Z"), ts("2026-01-01T00:00:00Z"))).isEqualByComparingTo("1");
        assertThat(diff("quarter", date("2026-03-31"), date("2026-04-01"))).isEqualByComparingTo("1");
        assertThat(diff("week", date("2026-07-05"), date("2026-07-06"))).isEqualByComparingTo("1");
        assertThat(diff("wk", date("2026-07-06"), date("2026-07-12"))).isEqualByComparingTo("0");
        assertThat(diff("day", ts("2026-07-08T23:59:00Z"), ts("2026-07-09T00:01:00Z"))).isEqualByComparingTo("1");
        assertThat(diff("hour", ts("2026-07-08T10:59:00Z"), ts("2026-07-08T11:01:00Z"))).isEqualByComparingTo("1");
        assertThat(diff("minute", ts("2026-07-08T10:59:59Z"), ts("2026-07-08T11:00:00Z"))).isEqualByComparingTo("1");
        assertThat(diff("second", ts("2026-07-08T10:59:59.9Z"), ts("2026-07-08T11:00:00.1Z"))).isEqualByComparingTo("1");
        assertThat(call("DATEDIFF", s("day"), NULL, date("2026-01-01")).isNull()).isTrue();
        assertThatThrownBy(() -> call("DATEDIFF", s("fortnight"), date("2026-01-01"), date("2026-01-02")))
                .hasMessageStartingWith("DATEDIFF: unknown unit 'fortnight'");
    }

    private static BigDecimal diff(String unit, Value a, Value b) {
        return number(call("DATEDIFF", s(unit), a, b));
    }

    private static Optional<String> spell(String name, PushdownTarget target, String... args) {
        ScalarFunction fn = BuiltinCalls.all().stream().filter(f -> f.signature().name().equals(name))
                .findFirst().orElseThrow();
        return fn.pushdown().render(target, List.of(args));
    }

    @Test
    @DisplayName("MAKE_DATE, LAST_DAY and DATEDIFF fold where a backend counts as this does")
    void spellings() {
        PushdownTarget postgres = new PushdownTarget("sql", "postgres");
        PushdownTarget sqlServer = new PushdownTarget("sql", "sqlserver");
        PushdownTarget mysql = new PushdownTarget("sql", "mysql");
        assertThat(spell("MAKE_DATE", postgres, "y", "m", "d")).contains("make_date(y, m, d)");
        assertThat(spell("MAKE_DATE", new PushdownTarget("sql", "duckdb"), "y", "m", "d")).contains("make_date(y, m, d)");
        assertThat(spell("MAKE_DATE", sqlServer, "y", "m", "d")).contains("DATEFROMPARTS(y, m, d)");
        assertThat(spell("MAKE_DATE", mysql, "y", "m", "d")).isEmpty();
        assertThat(spell("MAKE_DATE", postgres, "y")).isEmpty();
        assertThat(spell("MAKE_DATE", new PushdownTarget("mongo", ""), "y", "m", "d")).isEmpty();
        assertThat(spell("LAST_DAY", mysql, "d")).contains("LAST_DAY(d)");
        assertThat(spell("LAST_DAY", postgres, "d")).isEmpty();
        assertThat(spell("DATEDIFF", sqlServer, "N'month'", "a", "b"))
                .contains("DATEDIFF(month, SWITCHOFFSET(a, '+00:00'), SWITCHOFFSET(b, '+00:00'))");
        assertThat(spell("DATEDIFF", sqlServer, "'dd'", "a", "b")).hasValueSatisfying(q -> assertThat(q).startsWith("DATEDIFF(day,"));
        assertThat(spell("DATEDIFF", sqlServer, "N'week'", "a", "b")).isEmpty();
        assertThat(spell("DATEDIFF", sqlServer, "unit", "a", "b")).isEmpty();
        assertThat(spell("DATEDIFF", sqlServer, "N'eon'", "a", "b")).isEmpty();
        assertThat(spell("DATEDIFF", sqlServer, "N'day'", "a")).isEmpty();
        assertThat(spell("DATEDIFF", postgres, "'day'", "a", "b")).isEmpty();
        assertThat(spell("DATEDIFF", new PushdownTarget("mongo", ""), "'day'", "a", "b")).isEmpty();
        for (String name : List.of("TO_CHAR", "FORMAT", "TIMEZONE")) {
            assertThat(spell(name, postgres, "a", "b")).as(name).isEmpty();
        }
    }
}

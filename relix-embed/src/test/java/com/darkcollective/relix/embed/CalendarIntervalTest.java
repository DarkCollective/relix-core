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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.value.DurationValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Calendar months in a DURATION (#142): each expectation is what PostgreSQL answers for
 * the same interval arithmetic at UTC, across month ends and leap years.
 */
@DisplayName("Calendar interval arithmetic, evaluated")
final class CalendarIntervalTest {

    private static String value(Relix relix, String expression) {
        return relix.relation("π " + expression + " → v (UNIT)").toList().getFirst().get("v").asDisplayString();
    }

    private static List<String> rows(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining(" ")))
                .toList();
    }

    @Test
    @DisplayName("the duration page's worked example: a month on and back from a month's end")
    void workedExample() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    Renewals := [| id | renewed    |
                    | 1  | 2026-01-31 |
                    | 2  | 2024-01-31 |
                    | 3  | 2026-03-31 |];
                    """);
            assertThat(rows(relix, "τ id (π id, CAST(renewed AS DATE) + INTERVAL '1 month' → plus_month, "
                    + "CAST(renewed AS DATE) - INTERVAL '1 month' → minus_month (Renewals))")).containsExactly(
                    "1 2026-02-28T00:00:00Z 2025-12-31T00:00:00Z",
                    "2 2024-02-29T00:00:00Z 2023-12-31T00:00:00Z",
                    "3 2026-04-30T00:00:00Z 2026-02-28T00:00:00Z");
        }
    }

    @Test
    @DisplayName("months move on the calendar, then the exact part, as in PostgreSQL")
    void arithmetic() {
        try (Relix relix = Relix.open()) {
            assertThat(value(relix, "DATE '2026-01-31' + DURATION 'P1M'")).isEqualTo("2026-02-28T00:00:00Z");
            assertThat(value(relix, "INTERVAL '1 year' + TIMESTAMP '2024-02-29T00:00:00Z'"))
                    .isEqualTo("2025-02-28T00:00:00Z");
            assertThat(value(relix, "TIMESTAMP '2026-03-31T09:00:00Z' - INTERVAL '1 month 1 hour'"))
                    .isEqualTo("2026-02-28T08:00:00Z");
            assertThat(value(relix, "DATE '2026-03-31' - INTERVAL '1 month'")).isEqualTo("2026-02-28T00:00:00Z");
            // At UTC the day London's clocks go forward is 24 hours like any other.
            assertThat(value(relix, "TIMESTAMP '2026-03-28T12:00:00Z' + INTERVAL '1 day'"))
                    .isEqualTo("2026-03-29T12:00:00Z");
            // A time of day has no month to move.
            assertThat(value(relix, "TIME '10:00:00' + INTERVAL '1 month 2 hours'")).isEqualTo("12:00");
            assertThat(value(relix, "TIME '10:00:00' - INTERVAL '1 month 2 hours'")).isEqualTo("08:00");
        }
    }

    @Test
    @DisplayName("durations add part by part, and scale with a month's fraction spilling into days")
    void durationAlgebra() {
        try (Relix relix = Relix.open()) {
            assertThat(value(relix, "DURATION 'P1Y' + DURATION 'PT1H' - DURATION 'P2M'")).isEqualTo("P10MT1H");
            assertThat(value(relix, "DURATION 'P1M' * 1.5")).isEqualTo("P1MT360H");
            assertThat(value(relix, "2 * INTERVAL '1 month'")).isEqualTo("P2M");
            assertThat(value(relix, "DURATION 'P1M' / 2")).isEqualTo("PT360H");
            assertThat(value(relix, "DURATION 'P1M' / DURATION 'P1D'")).isEqualTo("30");
            assertThat(value(relix, "-DURATION 'P1Y2M'")).isEqualTo("P-1Y-2M");
        }
    }

    @Test
    @DisplayName("a month compares as 30 days, in a condition, a group and a sort")
    void comparison() {
        try (Relix relix = Relix.open()) {
            assertThat(value(relix, "IIf(DURATION 'P1M' = DURATION 'P30D', 'same', 'different')")).isEqualTo("same");
            assertThat(value(relix, "IIf(DURATION 'P1M' < DURATION 'P31D', 'shorter', 'not')")).isEqualTo("shorter");
            String spans = "π DURATION 'P31D' → d (UNIT) ⊎ π DURATION 'P1M' → d (UNIT)"
                    + " ⊎ π DURATION 'P30D' → d (UNIT) ⊎ π DURATION 'PT1H' → d (UNIT)";
            assertThat(rows(relix, "τ d (" + spans + ")")).containsExactly("PT1H", "P1M", "PT720H", "PT744H");
            assertThat(rows(relix, "γ COUNT(*) → n (δ (" + spans + "))")).containsExactly("3");
            assertThat(value(relix, "DAYS(INTERVAL '1 month')")).isEqualTo("30");
            assertThat(value(relix, "EPOCH(INTERVAL '1 month')")).isEqualTo("2592000");
        }
    }

    @Test
    @DisplayName("DATEADD, ADD_MONTHS, DATE_ADD, DATE_SUB and AGE")
    void functions() {
        try (Relix relix = Relix.open()) {
            assertThat(value(relix, "DATEADD(month, 1, DATE '2024-01-31')")).isEqualTo("2024-02-29");
            assertThat(value(relix, "DATEADD(qq, -1, TIMESTAMP '2026-05-31T06:00:00Z')")).isEqualTo("2026-02-28T06:00:00Z");
            assertThat(value(relix, "DATEADD(week, 2, DATE '2026-12-25')")).isEqualTo("2027-01-08");
            assertThat(value(relix, "DATEADD(hour, 36, TIMESTAMP '2026-01-01T00:00:00Z')")).isEqualTo("2026-01-02T12:00:00Z");
            assertThat(value(relix, "DATEADD(mi, 90, TIMESTAMP '2026-01-01T00:00:00Z')")).isEqualTo("2026-01-01T01:30:00Z");
            assertThat(value(relix, "DATEADD(s, 1.9, TIMESTAMP '2026-01-01T00:00:00Z')")).isEqualTo("2026-01-01T00:00:01Z");
            assertThat(value(relix, "DATEADD('day', -1, DATE '2026-03-01')")).isEqualTo("2026-02-28");
            assertThat(value(relix, "TIMESTAMPADD(YEAR, 1, DATE '2024-02-29')")).isEqualTo("2025-02-28");
            assertThat(value(relix, "ADD_MONTHS(DATE '2026-02-28', 1)")).isEqualTo("2026-03-31");
            assertThat(value(relix, "ADD_MONTHS(TIMESTAMP '2026-01-30T08:00:00Z', 1)")).isEqualTo("2026-02-28T08:00:00Z");
            assertThat(value(relix, "DATE_ADD(DATE '2026-01-31', INTERVAL 1 MONTH)")).isEqualTo("2026-02-28");
            assertThat(value(relix, "DATE_SUB(TIMESTAMP '2026-03-31T09:00:00Z', INTERVAL '1 month 1 hour')"))
                    .isEqualTo("2026-02-28T08:00:00Z");
            assertThat(value(relix, "AGE(TIMESTAMP '2001-04-10T00:00:00Z', TIMESTAMP '1957-06-13T00:00:00Z')"))
                    .isEqualTo("P43Y9MT648H");
            assertThat(value(relix, "AGE(DATE '1957-06-13', DATE '2001-04-10')")).isEqualTo("P-43Y-9MT-648H");
            assertThat(value(relix, "AGE(TIMESTAMP '2026-03-01T01:00:00Z', TIMESTAMP '2026-02-28T23:00:00Z')"))
                    .isEqualTo("PT2H");
            for (String call : List.of("DATEADD(month, 1, NULL)", "ADD_MONTHS(NULL, 1)",
                    "DATE_ADD(NULL, INTERVAL 1 DAY)", "AGE(NULL, DATE '2026-01-01')")) {
                assertThat(value(relix, "IIf(" + call + " = NULL, 'null', 'value')")).as(call).isEqualTo("null");
            }
        }
    }

    @Test
    @DisplayName("the functions refuse what they cannot answer")
    void functionErrors() {
        try (Relix relix = Relix.open()) {
            for (String[] c : new String[][]{
                    {"DATEADD(fortnight, 1, DATE '2026-01-01')", "unknown unit"},
                    {"DATEADD(hour, 1, DATE '2026-01-01')", "a DATE moved by part of a day"},
                    {"DATEADD(day, 'x', DATE '2026-01-01')", "expected a NUMBER"},
                    {"DATEADD(day, 1, 'x')", "expected DATE or TIMESTAMP"},
                    {"DATE_ADD(DATE '2026-01-01', 3)", "expected a DURATION"}}) {
                assertThatThrownBy(() -> value(relix, c[0])).as(c[0]).hasMessageContaining(c[1]);
            }
        }
    }

    @Test
    @DisplayName("where only an exact length will do, a month is refused")
    void exactOnly() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    E := [| u | ts                   |
                    | a | 2026-01-01T00:00:00Z |];
                    F := [| v | at                   |
                    | b | 2026-01-01T00:00:00Z |];
                    """);
            assertThatThrownBy(() -> relix.relation("SESSIONIZE ts GAP DURATION 'P1M' PER u AS s (E)").toList())
                    .hasMessageContaining("GAP threshold must be an exact DURATION");
            assertThatThrownBy(() -> relix.relation("E ASOF E.ts >= F.at WITHIN INTERVAL '1 month' F").toList())
                    .hasMessageContaining("WITHIN tolerance must be an exact DURATION");
        }
    }

    @Test
    @DisplayName("a parameter binds a Period, or interval text, and a tuple will not read months as exact")
    void boundary() {
        try (Relix relix = Relix.open()) {
            EmbedAssertions.assertThat(relix.relation("π DATE '2026-01-31' + $p → v (UNIT)")
                    .bind("p", Period.ofMonths(1))).tuples()
                    .singleElement().extracting(t -> t.get("v").asDisplayString()).isEqualTo("2026-02-28T00:00:00Z");
            EmbedAssertions.assertThat(relix.relation("σ DURATION 'PT10M' < $p (π 1 → x (UNIT))")
                    .bind("p", "15 minutes")).hasRowCount(1);
            Tuple month = relix.relation("π INTERVAL '1 month' → v (UNIT)").toList().getFirst();
            assertThat(month.get("v")).isEqualTo(new DurationValue(1, Duration.ZERO));
            assertThatThrownBy(() -> month.duration("v")).hasMessageContaining("has calendar months");
            EmbedAssertions.assertThat(relix.relation("π INTERVAL '2 days' → v (UNIT)")).tuples()
                    .singleElement().extracting(t -> t.duration("v")).isEqualTo(Duration.ofDays(2));
        }
    }
}

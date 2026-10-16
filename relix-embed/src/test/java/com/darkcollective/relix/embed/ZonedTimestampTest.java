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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TIMESTAMPTZ keeps the offset a moment was recorded at, compares by instant as SQL does,
 * and converts with AT TIME ZONE as SQL Server converts a datetimeoffset (#143).
 */
@DisplayName("Zoned timestamps, evaluated")
final class ZonedTimestampTest {

    private static final String CALLS = """
            Calls := [| id | at                        |
            | 1  | 2026-03-01T09:00:00+02:00 |
            | 2  | 2026-03-01T07:00:00Z      |
            | 3  | 2026-03-01T08:30:00-05:00 |];
            """;

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
    @DisplayName("the literal page's worked example: local hour, and London's clock, per call")
    void workedExample() {
        try (Relix relix = Relix.open()) {
            relix.define(CALLS);
            assertThat(rows(relix, "τ id (π id, CAST(at AS TIMESTAMPTZ) → at, HOUR(CAST(at AS TIMESTAMPTZ)) → local_hour,"
                    + " CAST(at AS TIMESTAMPTZ) AT TIME ZONE 'Europe/London' → london (Calls))")).containsExactly(
                    "1 2026-03-01T09:00:00+02:00 9 2026-03-01T07:00:00Z",
                    "2 2026-03-01T07:00:00Z 7 2026-03-01T07:00:00Z",
                    "3 2026-03-01T08:30:00-05:00 8 2026-03-01T13:30:00Z");
            EmbedAssertions.assertThat(relix.relation("π CAST(at AS TIMESTAMPTZ) → at (Calls)")).schema()
                    .hasColumn("at", com.darkcollective.relix.symbol.ScalarType.TIMESTAMPTZ);
        }
    }

    @Test
    @DisplayName("compares, groups, deduplicates and sorts by instant, keeping each row's offset")
    void instantSemantics() {
        try (Relix relix = Relix.open()) {
            relix.define(CALLS);
            assertThat(value(relix, "IIf(TIMESTAMPTZ '2026-03-01T09:00:00+02:00' = TIMESTAMP '2026-03-01T07:00:00Z',"
                    + " 'same', 'different')")).isEqualTo("same");
            assertThat(value(relix, "IIf(TIMESTAMPTZ '2026-03-01T09:00:00+02:00' < TIMESTAMPTZ '2026-03-01T08:00:00+00:00',"
                    + " 'earlier', 'not')")).isEqualTo("earlier");
            assertThat(rows(relix, "γ CAST(at AS TIMESTAMPTZ) → at, COUNT(*) → n (Calls)"))
                    .containsExactlyInAnyOrder("2026-03-01T09:00:00+02:00 2", "2026-03-01T08:30:00-05:00 1");
            assertThat(rows(relix, "τ at DESC, id (π id, CAST(at AS TIMESTAMPTZ) → at (Calls))")).containsExactly(
                    "3 2026-03-01T08:30:00-05:00", "1 2026-03-01T09:00:00+02:00", "2 2026-03-01T07:00:00Z");
            assertThat(rows(relix, "π id (σ CAST(at AS TIMESTAMPTZ) = TIMESTAMP '2026-03-01T07:00:00Z' (Calls))"))
                    .containsExactlyInAnyOrder("1", "2");
        }
    }

    @Test
    @DisplayName("arithmetic keeps the offset and moves months on its own calendar")
    void arithmetic() {
        try (Relix relix = Relix.open()) {
            assertThat(value(relix, "TIMESTAMPTZ '2026-01-31T09:00:00+02:00' + INTERVAL '1 month'"))
                    .isEqualTo("2026-02-28T09:00:00+02:00");
            assertThat(value(relix, "INTERVAL '2 hours' + TIMESTAMPTZ '2026-03-01T23:00:00-05:00'"))
                    .isEqualTo("2026-03-02T01:00:00-05:00");
            assertThat(value(relix, "TIMESTAMPTZ '2026-03-01T09:00:00+02:00' - DURATION 'P1D'"))
                    .isEqualTo("2026-02-28T09:00:00+02:00");
            assertThat(value(relix, "TIMESTAMPTZ '2026-03-01T09:00:00+02:00' - TIMESTAMP '2026-03-01T06:00:00Z'"))
                    .isEqualTo("PT1H");
            assertThat(value(relix, "TIMESTAMP '2026-03-01T06:00:00Z' - TIMESTAMPTZ '2026-03-01T09:00:00+02:00'"))
                    .isEqualTo("PT-1H");
            assertThat(value(relix, "TIMESTAMPTZ '2026-03-01T09:00:00+02:00' - TIMESTAMPTZ '2026-03-01T09:00:00+05:00'"))
                    .isEqualTo("PT3H");
            for (String illegal : List.of("TIMESTAMPTZ '2026-03-01T09:00:00Z' + 1",
                    "DATE '2026-03-01' - TIMESTAMPTZ '2026-03-01T09:00:00Z'",
                    "TIMESTAMPTZ '2026-03-01T09:00:00Z' - DATE '2026-03-01'")) {
                assertThatThrownBy(() -> value(relix, illegal)).as(illegal).hasMessageContaining("cannot");
            }
        }
    }

    @Test
    @DisplayName("AT TIME ZONE gives the same instant at the zone's offset, across a DST change")
    void atTimeZone() {
        try (Relix relix = Relix.open()) {
            assertThat(value(relix, "TIMESTAMPTZ '2026-03-01T09:00:00+02:00' AT TIME ZONE 'America/New_York'"))
                    .isEqualTo("2026-03-01T02:00:00-05:00");
            assertThat(value(relix, "TIMESTAMPTZ '2026-03-29T00:30:00Z' AT TIME ZONE 'Europe/London'"))
                    .isEqualTo("2026-03-29T00:30:00Z");
            assertThat(value(relix, "TIMESTAMPTZ '2026-03-29T01:30:00Z' AT TIME ZONE 'Europe/London'"))
                    .isEqualTo("2026-03-29T02:30:00+01:00");
            // A TIMESTAMP still gives the zone's wall clock read at UTC.
            assertThat(value(relix, "TIMESTAMP '2026-03-29T01:30:00Z' AT TIME ZONE 'Europe/London'"))
                    .isEqualTo("2026-03-29T02:30:00Z");
        }
    }

    @Test
    @DisplayName("the date parts, TO_CHAR, DATE_TRUNC and the date functions read the local time")
    void localFields() {
        try (Relix relix = Relix.open()) {
            String tz = "TIMESTAMPTZ '2026-03-01T23:30:00-05:00'";
            assertThat(value(relix, "DAY(" + tz + ")")).isEqualTo("1");
            assertThat(value(relix, "MINUTE(" + tz + ")")).isEqualTo("30");
            assertThat(value(relix, "TO_CHAR(" + tz + ", 'YYYY-MM-DD HH24:MI')")).isEqualTo("2026-03-01 23:30");
            assertThat(value(relix, "DATE_TRUNC('day', " + tz + ")")).isEqualTo("2026-03-01T00:00:00-05:00");
            assertThat(value(relix, "EPOCH(TIMESTAMPTZ '1970-01-01T02:00:00+02:00')")).isEqualTo("0");
            assertThat(value(relix, "DATEDIFF(day, " + tz + ", TIMESTAMPTZ '2026-03-02T00:30:00-05:00')")).isEqualTo("1");
            assertThat(value(relix, "DATEADD(month, 1, TIMESTAMPTZ '2026-01-31T09:00:00+02:00')"))
                    .isEqualTo("2026-02-28T09:00:00+02:00");
            assertThat(value(relix, "ADD_MONTHS(TIMESTAMPTZ '2026-02-28T09:00:00+02:00', 1)"))
                    .isEqualTo("2026-03-31T09:00:00+02:00");
        }
    }

    @Test
    @DisplayName("a RANGE frame measures a TIMESTAMPTZ key by its instant")
    void rangeOverZonedKey() {
        try (Relix relix = Relix.open()) {
            relix.define(CALLS);
            assertThat(rows(relix, "τ id (π id, n (ROLLING COUNT(id) OVER RANGE BETWEEN DURATION 'PT1H' PRECEDING"
                    + " AND CURRENT ROW SORT at AS n (π id, CAST(at AS TIMESTAMPTZ) → at (Calls))))"))
                    .containsExactly("1 2", "2 2", "3 1");
        }
    }

    @Test
    @DisplayName("CAST converts to and from a TIMESTAMP, a DATE, a TIME and text")
    void conversions() {
        try (Relix relix = Relix.open()) {
            String tz = "TIMESTAMPTZ '2026-03-01T23:30:00-05:00'";
            assertThat(value(relix, "CAST(" + tz + " AS TIMESTAMP)")).isEqualTo("2026-03-02T04:30:00Z");
            assertThat(value(relix, "CAST(" + tz + " AS DATE)")).isEqualTo("2026-03-01");
            assertThat(value(relix, "CAST(" + tz + " AS TIME)")).isEqualTo("23:30");
            assertThat(value(relix, "CAST(" + tz + " AS TIMESTAMPTZ)")).isEqualTo("2026-03-01T23:30:00-05:00");
            assertThat(value(relix, "CAST(TIMESTAMP '2026-03-01T07:00:00Z' AS TIMESTAMP WITH TIME ZONE)"))
                    .isEqualTo("2026-03-01T07:00:00Z");
            assertThat(value(relix, "CAST(DATE '2026-03-01' AS TIMESTAMPTZ)")).isEqualTo("2026-03-01T00:00:00Z");
            assertThat(value(relix, "CAST('2026-07-01T09:00:00[Europe/London]' AS TIMESTAMPTZ)"))
                    .isEqualTo("2026-07-01T09:00:00+01:00");
            assertThat(value(relix, "CAST('2026-07-01T09:00:00' AS TIMESTAMPTZ)")).isEqualTo("2026-07-01T09:00:00Z");
            assertThatThrownBy(() -> value(relix, "CAST('2026-07-01T09:00:00[Europe/London' AS TIMESTAMPTZ)"))
                    .hasMessageContaining("to_timestamptz");
            assertThatThrownBy(() -> value(relix, "CAST('noon' AS TIMESTAMPTZ)")).hasMessageContaining("to_timestamptz");
            assertThatThrownBy(() -> value(relix, "CAST(1 AS TIMESTAMPTZ)")).hasMessageContaining("to_timestamptz");
            assertThatThrownBy(() -> value(relix, "TIMEZONE('UTC', 1)")).hasMessageContaining("TIMESTAMPTZ");
        }
    }

    @Test
    @DisplayName("a tuple reads the offset, the instant, and binds a record field of either")
    void tupleAndRecords() {
        try (Relix relix = Relix.open()) {
            EmbedAssertions.assertThat(relix.relation("π TIMESTAMPTZ '2026-03-01T09:00:00+02:00' → at (UNIT)")).tuples()
                    .singleElement().satisfies(t -> {
                        assertThat(t.offsetDateTime("at")).isEqualTo(OffsetDateTime.parse("2026-03-01T09:00:00+02:00"));
                        assertThat(t.instant("at")).isEqualTo(java.time.Instant.parse("2026-03-01T07:00:00Z"));
                    });
            EmbedAssertions.assertThat(relix.relation("σ TIMESTAMPTZ '2026-03-01T09:00:00+02:00' = $p (UNIT)")
                    .bind("p", "2026-03-01T07:00:00Z")).hasRowCount(1);
        }
    }
}

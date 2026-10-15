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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AT TIME ZONE across DST, and the calendar helpers at a month's end, in a query (#139). */
@DisplayName("AT TIME ZONE and calendar helpers, evaluated")
final class TimeZoneCalendarTest {

    private static List<String> column(Relix relix, String query, String name) {
        return relix.relation(query).toList().stream().map(t -> t.get(name).asDisplayString()).toList();
    }

    @Test
    @DisplayName("local times either side of both of London's 2026 clock changes, as the worked example shows")
    void dst() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    F := [| flight | departs              |
                    | BA1    | 2026-03-29T00:30:00Z |
                    | BA2    | 2026-03-29T01:30:00Z |
                    | BA3    | 2026-10-25T00:30:00Z |
                    | BA4    | 2026-10-25T01:30:00Z |];
                    """);
            assertThat(column(relix, "τ flight (π flight, TO_CHAR(CAST(departs AS TIMESTAMP) AT TIME ZONE "
                    + "'Europe/London', 'HH24:MI') → local (F))", "local"))
                    .containsExactly("00:30", "02:30", "01:30", "01:30");
            assertThat(column(relix, "τ flight (π flight, HOUR(CAST(departs AS TIMESTAMP) AT TIME ZONE "
                    + "'America/New_York') → h (F))", "h"))
                    .containsExactly("20", "21", "20", "21");
        }
    }

    @Test
    @DisplayName("month-end arithmetic: DATEDIFF, LAST_DAY, MAKE_DATE and TO_CHAR together")
    void monthEnd() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    S := [| id | started    | ended      |
                    | 1  | 2024-01-31 | 2024-02-01 |
                    | 2  | 2024-01-01 | 2024-12-31 |];
                    """);
            assertThat(column(relix, "τ id (π id, DATEDIFF(month, CAST(started AS DATE), CAST(ended AS DATE)) → m (S))", "m"))
                    .containsExactly("1", "11");
            assertThat(column(relix, "τ id (π id, LAST_DAY(CAST(ended AS DATE)) → e (S))", "e"))
                    .containsExactly("2024-02-29", "2024-12-31");
            assertThat(column(relix, "τ id (π id, TO_CHAR(MAKE_DATE(2024, id + 1, 1), 'FMMonth YYYY') → t (S))", "t"))
                    .containsExactly("February 2024", "March 2024");
            assertThat(column(relix, "τ id (π id, FORMAT(id * 1234.5, 1) → f (S))", "f"))
                    .containsExactly("1,234.5", "2,469.0");
        }
    }
}

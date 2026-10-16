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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ROLLING's SQL frames — ROWS and RANGE BETWEEN, and the whole partition — with
 * CUME_DIST and NTH_VALUE, over a sort key with ties, a gap and a NULL (#129). Each
 * expectation is what PostgreSQL answers for the same frame.
 */
@DisplayName("Window frames, CUME_DIST and NTH_VALUE, evaluated")
final class WindowFramesTest {

    // amount ties at 10, jumps from 20 to 50, and is NULL once.
    private static final String T = """
            T := [| id | grp | amount |
            | 1  | a   | 10     |
            | 2  | a   | 10     |
            | 3  | a   | 20     |
            | 4  | a   | 50     |
            | 5  | a   | NULL   |
            | 6  | b   | 7      |];
            """;

    // A day apart, a three-day gap, a tie, and a NULL — read as text, cast where used.
    private static final String E = """
            E := [| id | at                   |
            | 1  | 2024-01-01T00:00:00Z |
            | 2  | 2024-01-02T00:00:00Z |
            | 3  | 2024-01-05T00:00:00Z |
            | 4  | 2024-01-06T00:00:00Z |
            | 5  | 2024-01-06T00:00:00Z |
            | 6  | NULL                 |];
            """;

    private static List<String> column(Relix relix, String query, String name) {
        return relix.relation("τ id (" + query + ")").toList().stream()
                .map(t -> t.get(name).asDisplayString()).toList();
    }

    @Test
    @DisplayName("ROWS BETWEEN counts rows either side, and a frame past the edge is empty")
    void rows() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(column(relix, "ROLLING SUM(amount) OVER ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING "
                    + "SORT id PER grp AS s (T)", "s")).containsExactly("20", "40", "80", "70", "50", "7");
            assertThat(column(relix, "ROLLING COUNT(id) OVER ROWS BETWEEN 2 FOLLOWING AND UNBOUNDED FOLLOWING "
                    + "SORT id PER grp AS n (T)", "n")).containsExactly("3", "2", "1", "0", "0", "0");
            assertThat(column(relix, "ROLLING SUM(amount) OVER ROWS 1 PRECEDING SORT id PER grp AS s (T)", "s"))
                    .containsExactly("10", "20", "30", "70", "50", "7");
        }
    }

    @Test
    @DisplayName("RANGE measures the sort key: ties are peers, a gap leaves rows out, and a NULL sees only NULLs")
    void range() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(column(relix, "ROLLING COUNT(id) OVER RANGE BETWEEN 10 PRECEDING AND 10 FOLLOWING "
                    + "SORT amount PER grp AS n (T)", "n")).containsExactly("3", "3", "3", "1", "1", "1");
            assertThat(column(relix, "ROLLING SUM(amount) OVER RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW "
                    + "SORT amount PER grp AS s (T)", "s")).containsExactly("20", "20", "40", "90", "90", "7");
            // Descending, PRECEDING is the larger values.
            assertThat(column(relix, "ROLLING SUM(amount) OVER RANGE BETWEEN 30 PRECEDING AND CURRENT ROW "
                    + "SORT amount DESC PER grp AS s (T)", "s")).containsExactly("40", "40", "70", "50", "NULL", "7");
            // CURRENT ROW to CURRENT ROW is the peers alone, out to the partition's edges.
            assertThat(column(relix, "ROLLING COUNT(id) OVER RANGE BETWEEN CURRENT ROW AND CURRENT ROW "
                    + "SORT amount PER grp AS n (T)", "n")).containsExactly("2", "2", "1", "1", "1", "1");
            // NULLs first: the NULL row's offset frame is its peers, and other rows never reach it.
            assertThat(column(relix, "ROLLING COUNT(id) OVER RANGE BETWEEN 5 PRECEDING AND CURRENT ROW "
                    + "SORT amount NULLS FIRST PER grp AS n (T)", "n")).containsExactly("2", "2", "1", "1", "1", "1");
            // NULLs last lie past every value: a bound running toward them reaches them.
            assertThat(column(relix, "ROLLING COUNT(id) OVER RANGE BETWEEN 100 FOLLOWING AND UNBOUNDED FOLLOWING "
                    + "SORT amount PER grp AS n (T)", "n")).containsExactly("1", "1", "1", "1", "1", "0");
        }
    }

    @Test
    @DisplayName("a DURATION measures a timestamp key")
    void temporalRange() {
        try (Relix relix = Relix.open()) {
            relix.define(E);
            assertThat(column(relix, "ROLLING COUNT(id) OVER RANGE BETWEEN DURATION 'P3D' PRECEDING AND CURRENT ROW "
                    + "SORT ts AS n (π id, CAST(at AS TIMESTAMP) → ts (E))", "n"))
                    .containsExactly("1", "2", "2", "3", "3", "1");
            assertThatThrownBy(() -> relix.relation("ROLLING COUNT(id) OVER RANGE BETWEEN 1 PRECEDING "
                    + "AND CURRENT ROW SORT ts AS n (π id, CAST(at AS TIMESTAMP) → ts (E))").toList())
                    .hasMessageContaining("A RANGE frame measures");
            // A date is measured by a duration as a timestamp is.
            assertThat(column(relix, "ROLLING COUNT(id) OVER RANGE BETWEEN DURATION 'P1D' PRECEDING AND CURRENT ROW "
                    + "SORT d AS n (π id, CAST(at AS DATE) → d (E))", "n"))
                    .containsExactly("1", "2", "1", "3", "3", "1");
            relix.define(T);
            assertThatThrownBy(() -> relix.relation("ROLLING COUNT(id) OVER RANGE BETWEEN DURATION 'P1D' "
                    + "PRECEDING AND CURRENT ROW SORT amount AS n (T)").toList())
                    .hasMessageContaining("got a NUMBER key and a DURATION offset");
            assertThatThrownBy(() -> relix.relation("ROLLING COUNT(id) OVER RANGE BETWEEN 1 "
                    + "PRECEDING AND CURRENT ROW SORT grp AS n (T)").toList())
                    .hasMessageContaining("got a STRING key and a number offset");
        }
    }

    @Test
    @DisplayName("OVER PARTITION gives each row its partition's total, with or without a sort")
    void partition() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(column(relix, "ROLLING SUM(amount) OVER PARTITION PER grp AS total (T)", "total"))
                    .containsExactly("90", "90", "90", "90", "90", "7");
            assertThat(column(relix, "ROLLING MAX(amount) OVER PARTITION SORT id AS top (T)", "top"))
                    .containsOnly("50");
        }
    }

    @Test
    @DisplayName("CUME_DIST counts each row's peers, and NTH_VALUE reads the nth row of the partition")
    void functions() {
        try (Relix relix = Relix.open()) {
            relix.define(T);
            assertThat(column(relix, "WINDOW CUME_DIST() SORT amount PER grp AS c (T)", "c"))
                    .containsExactly("0.4", "0.4", "0.6", "0.8", "1", "1");
            assertThat(column(relix, "WINDOW NTH_VALUE(amount, 3) SORT id PER grp AS third (T)", "third"))
                    .containsExactly("20", "20", "20", "20", "20", "NULL");
        }
    }
}

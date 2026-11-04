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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** BOOL_AND, EVERY and BOOL_OR (#295), and MODE (#296), evaluated. */
@DisplayName("BOOL_AND, BOOL_OR and MODE, evaluated")
final class BooleanAndModeAggregatesTest {

    private static final String ORDERS = """
            O := [| region | status   | qty  |
            | east   | shipped  | 3    |
            | east   | shipped  | 1    |
            | east   | returned | 3    |
            | east   | shipped  | 1    |
            | west   | shipped  | 2    |
            | west   | shipped  | NULL |
            | north  | NULL     | NULL |];
            """;

    private static List<String> rows(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining("|")))
                .toList();
    }

    @Test
    @DisplayName("BOOL_AND and BOOL_OR fold a condition per group, skip NULLs, and are NULL for a group with no value")
    void booleanFolds() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(rows(relix, "τ region (γ region, BOOL_AND(status = 'shipped') → all_shipped, "
                    + "EVERY(qty > 1) → all_big, BOOL_OR(status = 'returned') → any_returned (O))"))
                    .containsExactly("east|false|false|true", "north|NULL|NULL|NULL", "west|true|true|false");
        }
    }

    @Test
    @DisplayName("with FILTER, under ROLLUP, and in a ROLLING window")
    void filterRollupRolling() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(rows(relix, "τ region (γ region, BOOL_AND(status = 'shipped') FILTER (WHERE qty < 3) → a (O))"))
                    .containsExactly("east|true", "north|NULL", "west|true");
            assertThat(rows(relix, "τ region (γ ROLLUP (region), BOOL_OR(status = 'returned') → r (O))"))
                    .containsExactly("east|true", "north|NULL", "west|false", "NULL|true");
            assertThat(rows(relix, "π qty, s (ROLLING BOOL_AND(status = 'shipped') OVER ALL ROWS SORT qty DESC "
                    + "AS s (σ region = 'east' (O)))"))
                    .containsExactly("3|true", "3|false", "1|false", "1|false");
        }
    }

    @Test
    @DisplayName("a DISTINCT below BOOL_AND changes nothing, which is what lets the optimizer drop it")
    void duplicateInsensitive() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(rows(relix, "τ region (γ region, BOOL_AND(status = 'shipped') → a (δ (π region, status (O))))"))
                    .isEqualTo(rows(relix, "τ region (γ region, BOOL_AND(status = 'shipped') → a (π region, status (O)))"));
        }
    }

    @Test
    @DisplayName("MODE takes the most frequent value, and a tie goes to the smallest, or the largest descending")
    void modeTies() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            // east: qty 3 twice and 1 twice.
            assertThat(rows(relix, "τ region (γ region, MODE(qty) → m, MODE() WITHIN GROUP (ORDER BY qty DESC) → d, "
                    + "STATS_MODE(status) → s (O))"))
                    .containsExactly("east|1|3|shipped", "north|NULL|NULL|NULL", "west|2|2|shipped");
            // An ORDER BY of another value breaks the tie by it: status 'returned' sorts first, at qty 3.
            assertThat(rows(relix, "σ region = 'east' (γ region, MODE(qty ORDER BY status) → m (O))"))
                    .containsExactly("east|3");
        }
    }

    @Test
    @DisplayName("MODE of strings and dates, and the reference page's worked example")
    void modeOfStringsAndDates() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    Sales := [
                    | region | code |
                    |--------|------|
                    | east   | B    |
                    | east   | A    |
                    | east   | B    |
                    | east   | A    |
                    | east   | C    |
                    | west   | C    |
                    | west   | C    |
                    | west   | A    |
                    ];
                    """);
            assertThat(rows(relix, "τ region (γ region, MODE(code) → usual, "
                    + "MODE() WITHIN GROUP (ORDER BY code DESC) → usual_desc (Sales))"))
                    .containsExactly("east|A|B", "west|C|C");
            assertThat(rows(relix, "γ MODE(d) → m (π DATE '2026-03-02' → d (Sales))"))
                    .containsExactly("2026-03-02");
        }
    }
}

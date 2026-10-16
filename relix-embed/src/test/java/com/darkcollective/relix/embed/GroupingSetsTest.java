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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ROLLUP, CUBE, GROUPING SETS and GROUPING() answer as PostgreSQL does (#125), over a key
 * that holds a NULL of its own, which only GROUPING tells from a subtotal's.
 */
@DisplayName("Grouping sets, evaluated")
final class GroupingSetsTest {

    private static final String SALES = """
            S := [| region | product | amount |
            | east   | pen     | 10     |
            | east   | ink     | 30     |
            | west   | pen     | 5      |
            | NULL   | pen     | 7      |];
            """;

    private static List<String> rows(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining(" ")))
                .toList();
    }

    @Test
    @DisplayName("ROLLUP gives every prefix of its keys, and GROUPING marks the rolled-up ones")
    void rollup() {
        try (Relix relix = Relix.open()) {
            relix.define(SALES);
            assertThat(rows(relix, "γ ROLLUP (region, product), SUM(amount) → total, "
                    + "GROUPING(region, product) → g (S)")).containsExactlyInAnyOrder(
                    "east pen 10 0", "east ink 30 0", "west pen 5 0", "NULL pen 7 0",
                    "east NULL 40 1", "west NULL 5 1", "NULL NULL 7 1",
                    "NULL NULL 52 3");
        }
    }

    @Test
    @DisplayName("CUBE gives every subset; GROUPING_ID is GROUPING's other name")
    void cube() {
        try (Relix relix = Relix.open()) {
            relix.define(SALES);
            assertThat(rows(relix, "γ CUBE (region, product), COUNT(*) → n, GROUPING_ID(product, region) → g (S)"))
                    .containsExactlyInAnyOrder(
                            "east pen 1 0", "east ink 1 0", "west pen 1 0", "NULL pen 1 0",
                            "east NULL 2 2", "west NULL 1 2", "NULL NULL 1 2",
                            "NULL pen 3 1", "NULL ink 1 1",
                            "NULL NULL 4 3");
        }
    }

    @Test
    @DisplayName("GROUPING SETS as listed, beside a plain key; a set listed twice gives its rows twice")
    void groupingSets() {
        try (Relix relix = Relix.open()) {
            relix.define(SALES);
            assertThat(rows(relix, "γ GROUPING SETS ((region), product, ()), MAX(amount) → hi, GROUPING(region) (S)"))
                    .containsExactlyInAnyOrder("east NULL 30 0", "west NULL 5 0", "NULL NULL 7 0",
                            "NULL pen 10 1", "NULL ink 30 1", "NULL NULL 30 1");
            EmbedAssertions.assertThat(relix.relation("γ GROUPING SETS ((region), product, ()), MAX(amount) → hi, "
                    + "GROUPING(region) (S)")).schema().hasColumnNames("region", "product", "hi", "grouping_region");
            assertThat(rows(relix, "γ product, ROLLUP (region), COUNT(*) → n (S)")).containsExactlyInAnyOrder(
                    "pen east 1", "pen west 1", "pen NULL 1", "ink east 1", "pen NULL 3", "ink NULL 1");
            assertThat(rows(relix, "γ GROUPING SETS ((product), (product)), COUNT(*) → n (S)"))
                    .containsExactlyInAnyOrder("pen 3", "ink 1", "pen 3", "ink 1");
        }
    }

    @Test
    @DisplayName("a derived key is named by GROUPING as written or by its alias; plain grouping is 0")
    void derivedKeys() {
        try (Relix relix = Relix.open()) {
            relix.define(SALES);
            assertThat(rows(relix, "γ ROLLUP (UCase(product) → p), SUM(amount) → t, GROUPING(UCase(product)) → a, "
                    + "GROUPING(p) → b (S)")).containsExactlyInAnyOrder("PEN 22 0 0", "INK 30 0 0", "NULL 52 1 1");
            assertThat(rows(relix, "γ product, GROUPING(product) → g (S)")).containsExactlyInAnyOrder("pen 0", "ink 0");
        }
    }

    @Test
    @DisplayName("over no rows only the empty set gives a row, as an ungrouped total does")
    void emptyInput() {
        try (Relix relix = Relix.open()) {
            relix.define(SALES);
            assertThat(rows(relix, "γ ROLLUP (region), COUNT(*) → n, SUM(amount) → t (σ amount > 100 (S))"))
                    .containsExactly("NULL 0 NULL");
            assertThat(rows(relix, "γ CUBE (region), COUNT(*) → n (σ amount > 100 (S))")).containsExactly("NULL 0");
            assertThat(rows(relix, "γ GROUPING SETS ((region), (product)), COUNT(*) → n (σ amount > 100 (S))"))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("a condition above keeps the subtotals' NULLs out, as HAVING does")
    void selectionAbove() {
        try (Relix relix = Relix.open()) {
            relix.define(SALES);
            assertThat(rows(relix, "σ region = 'east' (γ ROLLUP (region, product), SUM(amount) → t (S))"))
                    .containsExactlyInAnyOrder("east pen 10", "east ink 30", "east NULL 40");
            assertThat(rows(relix, "σ GROUPING_REGION = 1 (γ ROLLUP (region), COUNT(*) → n, GROUPING(region) (S))"))
                    .containsExactly("NULL 4 1");
        }
    }

    @Test
    @DisplayName("GROUPING of something that is not a grouping key is refused")
    void groupingOfANonKey() {
        try (Relix relix = Relix.open()) {
            relix.define(SALES);
            assertThatThrownBy(() -> relix.relation("γ ROLLUP (region), GROUPING(amount) → g (S)").toList())
                    .hasMessageContaining("amount is not a grouping key");
        }
    }
}

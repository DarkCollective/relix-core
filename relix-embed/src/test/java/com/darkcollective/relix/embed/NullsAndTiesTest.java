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

/**
 * {@code NULLS FIRST} / {@code NULLS LAST} and {@code WITH TIES} answer as PostgreSQL does
 * over the same rows: all-NULL keys, ties that span the cut, and an offset before it.
 */
@DisplayName("NULLS FIRST / LAST and WITH TIES, evaluated")
final class NullsAndTiesTest {

    private static final String SCORES = """
            Scores := [| id | team | score |
            | 1  | a    | 9     |
            | 2  | a    | 8     |
            | 3  | a    | NULL  |
            | 4  | b    | 8     |
            | 5  | b    | NULL  |
            | 6  | b    | 7     |
            | 7  | b    | 8.0   |];
            """;

    private static List<String> ids(Relix relix, String query) {
        return relix.relation(query).toList().stream().map(t -> t.get("id").asDisplayString()).toList();
    }

    @Test
    @DisplayName("NULL sorts as the largest value by default, and where a key says otherwise")
    void nullPlacement() {
        try (Relix relix = Relix.open()) {
            relix.define(SCORES);
            assertThat(ids(relix, "τ score, id (Scores)")).containsExactly("6", "2", "4", "7", "1", "3", "5");
            assertThat(ids(relix, "τ score NULLS LAST, id (Scores)"))
                    .containsExactly("6", "2", "4", "7", "1", "3", "5");
            // PostgreSQL's and Oracle's default for a descending key.
            assertThat(ids(relix, "τ score DESC, id (Scores)")).containsExactly("3", "5", "1", "2", "4", "7", "6");
            assertThat(ids(relix, "τ score DESC NULLS FIRST, id (Scores)"))
                    .containsExactly("3", "5", "1", "2", "4", "7", "6");
            // MySQL's, SQL Server's and SQLite's, which take NULL as the smallest value.
            assertThat(ids(relix, "τ score DESC NULLS LAST, id (Scores)"))
                    .containsExactly("1", "2", "4", "7", "6", "3", "5");
            assertThat(ids(relix, "τ score NULLS FIRST, id DESC (Scores)"))
                    .containsExactly("5", "3", "6", "7", "4", "2", "1");
        }
    }

    @Test
    @DisplayName("an order with NULLs last does not stand in for one with NULLs first")
    void sortIsNotEliminatedAcrossPlacements() {
        try (Relix relix = Relix.open()) {
            relix.define(SCORES);
            assertThat(ids(relix, "τ score NULLS FIRST (τ score (σ team = 'a' (Scores)))"))
                    .containsExactly("3", "2", "1");
        }
    }

    @Test
    @DisplayName("a key of nothing but NULLs leaves the next key to decide")
    void allNullKeys() {
        try (Relix relix = Relix.open()) {
            relix.define(SCORES);
            assertThat(ids(relix, "τ score NULLS FIRST, id DESC (σ score = NULL (Scores))"))
                    .containsExactly("5", "3");
            assertThat(ids(relix, "λ 1 WITH TIES (τ score NULLS FIRST (σ score = NULL (Scores)))"))
                    .containsExactlyInAnyOrder("3", "5");
        }
    }

    @Test
    @DisplayName("WITH TIES keeps every row tied with the last one taken, 8 and 8.0 alike")
    void tiesSpanningTheCut() {
        try (Relix relix = Relix.open()) {
            relix.define(SCORES);
            assertThat(ids(relix, "λ 2 (τ score DESC NULLS LAST, id (Scores))")).containsExactly("1", "2");
            assertThat(ids(relix, "λ 2 WITH TIES (τ score DESC NULLS LAST (Scores))"))
                    .containsExactlyInAnyOrder("1", "2", "4", "7");
            // The offset is skipped first; the ties are counted from the last row taken.
            assertThat(ids(relix, "λ 1, 1 WITH TIES (τ score DESC NULLS LAST (Scores))"))
                    .containsExactlyInAnyOrder("2", "4", "7");
            // NULLs tie with each other, and a descending key puts them first.
            assertThat(ids(relix, "λ 1 WITH TIES (τ score DESC (Scores))"))
                    .containsExactlyInAnyOrder("3", "5");
            assertThat(ids(relix, "λ 2 (τ score DESC, id (Scores))")).containsExactly("3", "5");
            // A cut that falls between two distinct values keeps no extra row.
            assertThat(ids(relix, "λ 1 WITH TIES (τ score DESC NULLS LAST (Scores))")).containsExactly("1");
            assertThat(ids(relix, "λ 0 WITH TIES (τ score DESC (Scores))")).isEmpty();
            assertThat(ids(relix, "λ 9, 2 WITH TIES (τ score DESC (Scores))")).isEmpty();
            assertThat(ids(relix, "λ 100 WITH TIES (τ score (Scores))")).hasSize(7);
        }
    }

    @Test
    @DisplayName("TOP … WITH TIES keeps each group's ties, and the plain TOP still cuts them")
    void topPerGroup() {
        try (Relix relix = Relix.open()) {
            relix.define(SCORES);
            assertThat(ids(relix, "TOP 1 WITH TIES score DESC NULLS LAST PER team (Scores)"))
                    .containsExactlyInAnyOrder("1", "4", "7");
            assertThat(ids(relix, "TOP 1 score DESC NULLS LAST PER team (Scores)")).hasSize(2);
            // A descending TOP takes each group's NULLs first, as its sort does.
            assertThat(ids(relix, "TOP 1 WITH TIES score DESC PER team (Scores)"))
                    .containsExactlyInAnyOrder("3", "5");
            assertThat(ids(relix, "TOP 1 score PER team (Scores)")).containsExactlyInAnyOrder("2", "6");
            assertThat(ids(relix, "TOP 1 WITH TIES score NULLS FIRST PER team (Scores)"))
                    .containsExactlyInAnyOrder("3", "5");
        }
    }

    @Test
    @DisplayName("a window's order places NULLs as τ does, and so decides RANK's ties")
    void windowOrder() {
        try (Relix relix = Relix.open()) {
            relix.define(SCORES);
            assertThat(column(relix, "τ id (WINDOW RANK() SORT score DESC NULLS FIRST PER team AS r (Scores))", "r"))
                    .containsExactly("2", "3", "1", "2", "1", "4", "2");
            assertThat(column(relix, "τ id (WINDOW RANK() SORT score DESC PER team AS r (Scores))", "r"))
                    .containsExactly("2", "3", "1", "2", "1", "4", "2");
            assertThat(column(relix, "τ id (WINDOW RANK() SORT score DESC NULLS LAST PER team AS r (Scores))", "r"))
                    .containsExactly("1", "2", "3", "1", "4", "3", "1");
            assertThat(column(relix, "τ id (WINDOW RANK() SORT score PER team AS r (Scores))", "r"))
                    .containsExactly("2", "1", "3", "2", "4", "1", "2");
        }
    }

    @Test
    @DisplayName("ROLLING runs along a descending order from its NULLs")
    void rollingOrder() {
        try (Relix relix = Relix.open()) {
            relix.define(SCORES);
            assertThat(column(relix,
                    "τ id (ROLLING COUNT(id) OVER ALL ROWS SORT score DESC, id PER team AS n (Scores))", "n"))
                    .containsExactly("2", "3", "1", "2", "1", "4", "3");
            assertThat(column(relix,
                    "τ id (ROLLING COUNT(id) OVER ALL ROWS SORT score DESC NULLS LAST, id PER team AS n (Scores))", "n"))
                    .containsExactly("1", "2", "3", "1", "4", "3", "2");
        }
    }

    @Test
    @DisplayName("an ordered STRING_AGG and COLLECT place NULLs as τ does")
    void orderedAggregates() {
        try (Relix relix = Relix.open()) {
            relix.define(SCORES);
            assertThat(column(relix,
                    "τ team (γ team, STRING_AGG(id, ',' ORDER BY score DESC, id) → s (Scores))", "s"))
                    .containsExactly("3,1,2", "5,4,7,6");
            assertThat(column(relix,
                    "τ team (γ team, STRING_AGG(id, ',' ORDER BY score DESC NULLS LAST, id) → s (Scores))", "s"))
                    .containsExactly("1,2,3", "4,7,6,5");
            assertThat(column(relix,
                    "τ team (γ team, STRING_AGG(id, ',' ORDER BY score, id) → s (Scores))", "s"))
                    .containsExactly("2,1,3", "6,4,7,5");
            assertThat(column(relix, "τ team (γ team, COLLECT(id ORDER BY score DESC, id) → c (Scores))", "c"))
                    .containsExactly("[3, 1, 2]", "[5, 4, 7, 6]");
        }
    }

    private static List<String> column(Relix relix, String query, String column) {
        return relix.relation(query).toList().stream().map(t -> t.get(column).asDisplayString()).toList();
    }
}

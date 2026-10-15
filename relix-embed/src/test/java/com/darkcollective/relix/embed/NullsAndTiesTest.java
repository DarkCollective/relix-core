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
    @DisplayName("NULLs go last by default in both directions, and where a key says otherwise")
    void nullPlacement() {
        try (Relix relix = Relix.open()) {
            relix.define(SCORES);
            assertThat(ids(relix, "τ score, id (Scores)")).containsExactly("6", "2", "4", "7", "1", "3", "5");
            assertThat(ids(relix, "τ score DESC, id (Scores)")).containsExactly("1", "2", "4", "7", "6", "3", "5");
            assertThat(ids(relix, "τ score DESC NULLS LAST, id (Scores)"))
                    .containsExactly("1", "2", "4", "7", "6", "3", "5");
            // PostgreSQL's own default for a descending key.
            assertThat(ids(relix, "τ score DESC NULLS FIRST, id (Scores)"))
                    .containsExactly("3", "5", "1", "2", "4", "7", "6");
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
            assertThat(ids(relix, "λ 2 (τ score DESC, id (Scores))")).containsExactly("1", "2");
            assertThat(ids(relix, "λ 2 WITH TIES (τ score DESC (Scores))"))
                    .containsExactlyInAnyOrder("1", "2", "4", "7");
            // The offset is skipped first; the ties are counted from the last row taken.
            assertThat(ids(relix, "λ 1, 1 WITH TIES (τ score DESC (Scores))"))
                    .containsExactlyInAnyOrder("2", "4", "7");
            // NULLs tie with each other.
            assertThat(ids(relix, "λ 1 WITH TIES (τ score DESC NULLS FIRST (Scores))"))
                    .containsExactlyInAnyOrder("3", "5");
            // A cut that falls between two distinct values keeps no extra row.
            assertThat(ids(relix, "λ 1 WITH TIES (τ score DESC (Scores))")).containsExactly("1");
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
            assertThat(ids(relix, "TOP 1 WITH TIES score DESC PER team (Scores)"))
                    .containsExactlyInAnyOrder("1", "4", "7");
            assertThat(ids(relix, "TOP 1 score DESC PER team (Scores)")).hasSize(2);
            assertThat(ids(relix, "TOP 1 WITH TIES score NULLS FIRST PER team (Scores)"))
                    .containsExactlyInAnyOrder("3", "5");
        }
    }

    @Test
    @DisplayName("a window ordered NULLS FIRST numbers the NULLs first")
    void windowOrder() {
        try (Relix relix = Relix.open()) {
            relix.define(SCORES);
            List<String> ranks = relix.relation(
                            "τ id (WINDOW RANK() SORT score DESC NULLS FIRST PER team AS r (Scores))")
                    .toList().stream().map(t -> t.get("r").asDisplayString()).toList();
            assertThat(ranks).containsExactly("2", "3", "1", "2", "1", "4", "2");
        }
    }
}

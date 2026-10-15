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
 * SQL's literal and operator spellings — {@code ''}, {@code ||}, {@code %} and exponent
 * notation — evaluate as the Relix forms they stand for, NULLs included.
 */
@DisplayName("SQL literal and operator spellings, evaluated")
final class SqlLiteralSpellingsTest {

    private static final String ROWS = """
            Rows := [| id | first | last  | qty  |
            | 1  | Ada   | Lovelace | 30   |
            | 2  | Alan  | NULL     | -7   |
            | 3  | NULL  | Turing   | NULL |];
            """;

    private static List<String> column(Relix relix, String query, String name) {
        return relix.relation(query).toList().stream()
                .map(t -> t.get(name).asDisplayString()).toList();
    }

    @Test
    @DisplayName("|| joins strings as + does, NULL when either side is")
    void concatenation() {
        try (Relix relix = Relix.open()) {
            relix.define(ROWS);
            assertThat(column(relix, "τ id (π id, first || ' ' || last → full (Rows))", "full"))
                    .isEqualTo(column(relix, "τ id (π id, first + ' ' + last → full (Rows))", "full"))
                    .containsExactly("Ada Lovelace", "NULL", "NULL");
        }
    }

    @Test
    @DisplayName("% is Mod: the remainder takes the dividend's sign, and a zero divisor is an error")
    void remainder() {
        try (Relix relix = Relix.open()) {
            relix.define(ROWS);
            assertThat(column(relix, "τ id (π id, qty % 4 → m (Rows))", "m"))
                    .isEqualTo(column(relix, "τ id (π id, Mod(qty, 4) → m (Rows))", "m"))
                    .containsExactly("2", "-3", "NULL");
            assertThatThrownBy(() -> relix.relation("π qty % 0 → m (Rows)").toList())
                    .hasMessageContaining("Mod: divisor must be non-zero");
        }
    }

    @Test
    @DisplayName("a doubled quote is one quote, and an exponent is the number it writes")
    void literals() {
        try (Relix relix = Relix.open()) {
            relix.define(ROWS);
            assertThat(column(relix, "π 'it''s' → s (σ id = 1 (Rows))", "s")).containsExactly("it's");
            assertThat(column(relix, "π \"say \"\"hi\"\"\" → s (σ id = 1 (Rows))", "s"))
                    .containsExactly("say \"hi\"");
            assertThat(column(relix, "π 1.5e2 + 2.5E-3 → n (σ id = 1 (Rows))", "n"))
                    .containsExactly("150.0025");
            assertThat(column(relix, "τ id (π id (σ qty * 1e1 > 2E2 (Rows)))", "id")).containsExactly("1");
        }
    }
}

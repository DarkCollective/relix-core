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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code OPTIMIZE} whose objective ties answers with the same rows on every read:
 * a view read twice in one script describes one selection, and a rerun gives the same
 * one again.
 */
@DisplayName("OPTIMIZE with tied optima — one selection, however often it is read")
final class OptimizeTieTest {

    /**
     * Many subsets reach the optimum of 25: any two of the four 10s and either 5. The
     * rule picks the fewest rows, then the earliest — A, B and E.
     */
    private static final String SCRIPT = """
            Items := [| Item | Value | Weight |
            | A | 10 | 3 |
            | B | 10 | 3 |
            | C | 10 | 3 |
            | D | 10 | 3 |
            | E | 5  | 1 |
            | F | 5  | 1 |
            | G | 0  | 0 |];
            Selection := { OPTIMIZE MAXIMIZE SUM(Value) SUBJECT TO SUM(Weight) <= 7 (Items) };
            query { Selection };
            Totals := { γ SUM(Value) → Value, SUM(Weight) → Weight (Selection) };
            query { Totals };
            """;

    @Test
    @DisplayName("the second query's totals are the sums of the first query's rows, on every run")
    void oneSelectionPerScript() {
        for (int run = 0; run < 25; run++) {
            try (Relix relix = Relix.open()) {
                List<Relation> results = relix.script(SCRIPT);
                List<Tuple> chosen = results.get(0).toList();
                Tuple totals = results.get(1).toList().getFirst();

                assertThat(chosen).extracting(t -> t.string("Item"))
                        .as("run %d", run)
                        .containsExactlyInAnyOrder("A", "B", "E");
                assertThat(totals.decimal("Value")).isEqualByComparingTo(sum(chosen, "Value"));
                assertThat(totals.decimal("Weight")).isEqualByComparingTo(sum(chosen, "Weight"));
            }
        }
    }

    private static BigDecimal sum(List<Tuple> rows, String column) {
        return rows.stream().map(t -> t.decimal(column)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

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

/** STDDEV, VARIANCE, the percentiles and MEDIAN, against PostgreSQL's answers on the same data (#127). */
@DisplayName("Statistical aggregates, evaluated")
final class StatisticalAggregatesTest {

    private static final String DATA = """
            M := [| g | x    |
            | a | 1    |
            | a | 2    |
            | a | 3    |
            | a | 4    |
            | a | NULL |
            | b | 7    |
            | c | NULL |];
            """;

    private static List<String> rows(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining(" ")))
                .toList();
    }

    @Test
    @DisplayName("the variance family: sample and population, NULL for a sample of one or none, NULLs ignored")
    void variance() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThat(rows(relix, "τ g (γ g, VAR_SAMP(x) → vs, VARIANCE(x) → v, VAR_POP(x) → vp, "
                    + "STDDEV_SAMP(x) → ss, STDDEV(x) → s, STDDEV_POP(x) → sp (M))"))
                    .containsExactly(
                            "a 1.6666666667 1.6666666667 1.25 1.2909944487 1.2909944487 1.1180339887",
                            "b NULL NULL 0 NULL NULL 0",
                            "c NULL NULL NULL NULL NULL NULL");
        }
    }

    @Test
    @DisplayName("the percentiles in both spellings, and MEDIAN; a descending order reads from the top")
    void percentiles() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThat(rows(relix, "τ g (γ g, PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY x) → p50, "
                    + "PERCENTILE_CONT(0.25, x) → p25, PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY x DESC) → p75, "
                    + "PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY x) → d50, PERCENTILE_DISC(0.25, x) → d25, "
                    + "MEDIAN(x) → med (M))"))
                    .containsExactly("a 2.5 1.75 3.25 2 1 2.5", "b 7 7 7 7 7 7", "c NULL NULL NULL NULL NULL NULL");
        }
    }

    @Test
    @DisplayName("they roll, and filter")
    void rollAndFilter() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThat(rows(relix, "τ x (π x, v (ROLLING VAR_POP(x) OVER 2 ROWS SORT x AS v (σ g = 'a' ∧ x ≠ NULL (M))))"))
                    .containsExactly("1 0", "2 0.25", "3 0.25", "4 0.25");
            assertThat(rows(relix, "γ MEDIAN(x) FILTER (WHERE g = 'a') → m (M)")).containsExactly("2.5");
        }
    }

    @Test
    @DisplayName("a fraction outside [0, 1], or a non-number, is refused")
    void refusals() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThatThrownBy(() -> relix.relation("γ PERCENTILE_CONT(1.5, x) → p (M)").toList())
                    .hasMessageContaining("percentile value 1.5 is not between 0 and 1");
            assertThatThrownBy(() -> relix.relation("γ STDDEV(g) → s (M)").toList())
                    .hasMessageContaining("STDDEV: expected NUMBER, got STRING");
        }
    }
}

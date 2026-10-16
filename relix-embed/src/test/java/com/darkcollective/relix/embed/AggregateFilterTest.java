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

/** {@code AGG(x) FILTER (WHERE c)} answers as PostgreSQL's does, including a group no row passes (#130). */
@DisplayName("Aggregate FILTER, evaluated")
final class AggregateFilterTest {

    private static final String ORDERS = """
            O := [| region | status | amount |
            | east   | open   | 10     |
            | east   | open   | 30     |
            | east   | closed | 5      |
            | west   | closed | 7      |
            | west   | closed | NULL   |];
            """;

    private static List<String> rows(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining(" ")))
                .toList();
    }

    @Test
    @DisplayName("COUNT, SUM and AVG over the rows that pass; a group none passes is COUNT 0 and NULL otherwise")
    void filtered() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(rows(relix, "τ region (γ region, COUNT(*) FILTER (WHERE status = 'open') → open_n, "
                    + "SUM(amount) FILTER (WHERE status = 'open') → open_sum, "
                    + "AVG(amount) FILTER (WHERE status = 'open') → open_avg, "
                    + "COUNT(amount) FILTER (WHERE status = 'closed') → closed_n, COUNT(*) → n (O))"))
                    .containsExactly("east 2 40 20 1 3", "west 0 NULL NULL 1 2");
        }
    }

    @Test
    @DisplayName("FILTER composes with DISTINCT, MIN/MAX and ARGMAX, and over the whole relation")
    void composes() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(rows(relix, "γ COUNT(DISTINCT region) FILTER (WHERE amount > 6) → regions, "
                    + "MAX(amount) FILTER (WHERE region = 'west') → west_max, "
                    + "ARGMAX(amount, status) FILTER (WHERE region = 'east' ∧ status = 'closed') → s (O)"))
                    .containsExactly("2 7 closed");
        }
    }
}

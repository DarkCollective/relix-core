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

@DisplayName("Operator edge cases found by the runtime audit")
final class OperatorEdgeCasesTest {

    private static List<String> rows(Relation relation) {
        return relation.toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining(" ")))
                .toList();
    }

    /** The optimised and as-written runs of one query give the same rows. */
    private static List<String> both(Relix relix, String query) {
        List<String> optimised = rows(relix.relation(query));
        assertThat(rows(relix.relation(query).asWritten())).isEqualTo(optimised);
        return optimised;
    }

    @Test
    @DisplayName("SESSIONIZE over a DATE column splits on the gap between days")
    void sessionizeDates() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    D := [| d          | n |
                    | 2026-01-01 | 1 |
                    | 2026-01-02 | 2 |
                    | 2026-01-03 | 3 |
                    | 2026-01-06 | 4 |
                    | 2026-01-07 | 5 |];
                    """);
            assertThat(both(relix, "τ n (SESSIONIZE d GAP DURATION 'P1D' AS s (D))")).containsExactly(
                    "2026-01-01 1 1", "2026-01-02 2 1", "2026-01-03 3 1", "2026-01-06 4 2", "2026-01-07 5 2");
            assertThat(both(relix, "τ n (SESSIONIZE d GAP DURATION 'PT24H' AS s (D))")).hasSize(5);
        }
    }

    @Test
    @DisplayName("a negative SESSIONIZE gap or ASOF tolerance is an analysis error")
    void negativeBounds() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    E0 := [| u | ts                   | n |
                    | a | 2026-01-01T00:00:00Z | 1 |];
                    F0 := [| v | at                   |
                    | b | 2026-01-01T00:00:00Z |];
                    E := { π u, CAST(ts AS TIMESTAMP) → ts, n (E0) };
                    F := { π v, CAST(at AS TIMESTAMP) → at (F0) };
                    """);
            assertThatThrownBy(() -> relix.relation("SESSIONIZE ts GAP DURATION 'PT-1S' AS s (E)").toList())
                    .hasMessageContaining("GAP threshold must not be negative");
            assertThatThrownBy(() -> relix.relation("E ASOF E.ts >= F.at WITHIN DURATION 'PT-2M' F").toList())
                    .hasMessageContaining("WITHIN tolerance must not be negative");
            assertThat(rows(relix.relation("E ASOF E.ts >= F.at WITHIN DURATION 'PT0S' F"))).hasSize(1);
        }
    }
}

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
 * {@code CAST} and {@code EXTRACT} answer as PostgreSQL's do: each cast with a NULL
 * passed through and a bad value named, and each field, ISO week at a year's edges
 * included.
 */
@DisplayName("CAST and EXTRACT, evaluated")
final class CastExtractTest {

    private static final String DATA = """
            Raw := [| id | qty | flag  | at                   |
            | 1  | 3   | yes   | 2026-07-08T13:40:30Z |
            | 2  |     | OFF   | 2021-01-01T00:00:00Z |];
            """;

    private static List<String> column(Relix relix, String query, String name) {
        return relix.relation(query).toList().stream().map(t -> t.get(name).asDisplayString()).toList();
    }

    @Test
    @DisplayName("each cast converts, and a NULL casts to NULL")
    void casts() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThat(column(relix, "τ id (π id, CAST(qty AS NUMBER) + 1 → n (Raw))", "n")).containsExactly("4", "NULL");
            assertThat(column(relix, "τ id (π id, CAST(qty AS STRING) → s (Raw))", "s")).containsExactly("3", "NULL");
            assertThat(column(relix, "τ id (π id, CAST(flag AS BOOLEAN) → b (Raw))", "b")).containsExactly("true", "false");
            assertThat(column(relix, "τ id (π id, CAST(at AS DATE) → d (Raw))", "d"))
                    .containsExactly("2026-07-08", "2021-01-01");
            assertThat(column(relix, "τ id (π id, CAST(CAST(at AS DATE) AS TIMESTAMP) → t (Raw))", "t"))
                    .containsExactly("2026-07-08T00:00:00Z", "2021-01-01T00:00:00Z");
            assertThat(column(relix, "τ id (π id, CAST(CAST(at AS TIMESTAMP) AS TIME) → t (Raw))", "t"))
                    .containsExactly("13:40:30", "00:00");
        }
    }

    @Test
    @DisplayName("a value the type cannot hold is refused, naming it")
    void badValue() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThatThrownBy(() -> relix.relation("π CAST(flag AS NUMBER) → n (Raw)").toList())
                    .hasMessageContaining("cannot convert \"yes\" to a number");
            assertThatThrownBy(() -> relix.relation("π CAST(flag AS DATE) → n (Raw)").toList())
                    .hasMessageContaining("to_date: invalid date 'yes'");
        }
    }

    @Test
    @DisplayName("every field, as PostgreSQL's EXTRACT gives it")
    void fields() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            String at = "CAST(at AS TIMESTAMP)";
            String query = "τ id (π id";
            for (String field : List.of("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "DOW", "ISODOW",
                    "DOY", "WEEK", "ISOYEAR", "QUARTER", "EPOCH")) {
                query += ", EXTRACT(" + field + " FROM " + at + ") → " + field.toLowerCase();
            }
            query += " (Raw))";
            List<String> first = relix.relation(query).toList().stream()
                    .map(t -> t.schema().columns().stream().skip(1).map(c -> t.get(c.name()).asDisplayString())
                            .collect(java.util.stream.Collectors.joining(" ")))
                    .toList();
            assertThat(first).containsExactly(
                    "2026 7 8 13 40 30 3 3 189 28 2026 3 1783518030",
                    "2021 1 1 0 0 0 5 5 1 53 2020 1 1609459200");
        }
    }
}

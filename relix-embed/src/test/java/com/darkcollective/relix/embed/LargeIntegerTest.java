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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;

/**
 * Integers above 2<sup>53</sup>, where a {@code double} can no longer hold every
 * integer, are read exactly: a 64-bit id written as a literal, in an inline-table cell
 * or in a CSV or JSON file keeps every digit, and two ids one apart compare unequal.
 */
@DisplayName("Integers above 2^53 are read exactly")
final class LargeIntegerTest {

    /** 2^53 + 1, the first integer a double rounds. */
    private static final String ABOVE = "9007199254740993";
    private static final String BELOW = "9007199254740992";
    /** 2^63 - 1, the largest BIGINT. */
    private static final String MAX = "9223372036854775807";

    private static final String T = """
            T := [
            | id | big              |
            |----|------------------|
            | 1  | 9007199254740993 |
            | 2  | 9223372036854775807 |
            ];
            """;

    @TempDir
    Path dir;

    /** Asserts {@code expression} answers one row whose {@code column} is exactly {@code expected}. */
    private static void holds(Relix relix, String expression, String column, String expected) {
        assertThat(relix.relation(expression)).tuples()
                .singleElement()
                .extracting(t -> t.decimal(column))
                .satisfies(d -> org.assertj.core.api.Assertions.assertThat(d)
                        .isEqualByComparingTo(new BigDecimal(expected)));
    }

    @Test
    @DisplayName("a literal keeps every digit, through arithmetic too")
    void literal() {
        try (Relix relix = Relix.builder().build()) {
            relix.define(T);
            holds(relix, "π " + ABOVE + " + 0 → lit (σ id = 1 (T))", "lit", ABOVE);
            holds(relix, "π " + MAX + " → lit (σ id = 1 (T))", "lit", MAX);
        }
    }

    @Test
    @DisplayName("an inline-table cell keeps every digit")
    void cell() {
        try (Relix relix = Relix.builder().build()) {
            relix.define(T);
            holds(relix, "σ id = 1 (T)", "big", ABOVE);
            holds(relix, "σ id = 2 (T)", "big", MAX);
            assertThat(relix.relation("σ id = 2 (T)")).tuples()
                    .extracting(t -> t.longValue("big"))
                    .containsExactly(Long.MAX_VALUE);
        }
    }

    @Test
    @DisplayName("a comparison tells apart two ids one apart")
    void comparison() {
        try (Relix relix = Relix.builder().build()) {
            relix.define(T);
            assertThat(relix.relation("σ big = " + ABOVE + " (T)")).hasRowCount(1);
            assertThat(relix.relation("σ big = " + BELOW + " (T)")).isEmpty();
            assertThat(relix.relation("σ big > " + BELOW + " ∧ big < " + MAX + " (T)")).hasRowCount(1);
        }
    }

    @Test
    @DisplayName("a CSV cell and a JSON number keep every digit")
    void files() throws IOException {
        Files.writeString(dir.resolve("ids.csv"), "id,big\n1," + ABOVE + "\n2," + MAX + "\n");
        Files.writeString(dir.resolve("ids.json"),
                "[{\"id\": 1, \"big\": " + ABOVE + "}, {\"id\": 2, \"big\": " + MAX + "}]");
        try (Relix relix = Relix.builder().baseDirectory(dir).build()) {
            relix.define("""
                    source Csv from csv("ids.csv") { };
                    source Json from json("ids.json");
                    """);
            for (String source : List.of("Csv", "Json")) {
                holds(relix, "σ big = " + ABOVE + " (" + source + ")", "big", ABOVE);
                assertThat(relix.relation("σ big = " + BELOW + " (" + source + ")")).isEmpty();
                holds(relix, "σ big = " + MAX + " (" + source + ")", "big", MAX);
            }
        }
    }
}

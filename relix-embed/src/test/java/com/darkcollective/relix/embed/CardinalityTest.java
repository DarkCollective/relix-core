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

/** {@code CARDINALITY} and {@code ARRAY_LENGTH} count a list's elements (#293). */
@DisplayName("CARDINALITY and ARRAY_LENGTH, evaluated")
final class CardinalityTest {

    private static final String ORDERS = """
            O := [| region | item  |
            | east   | pear  |
            | east   | apple |
            | east   | fig   |
            | west   | NULL  |
            | west   | kiwi  |];
            """;

    private static List<String> rows(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining("|")))
                .toList();
    }

    @Test
    @DisplayName("a COLLECTed list is counted, its NULL elements included, by either name")
    void overCollect() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(rows(relix, "τ region (π region, CARDINALITY(items) → k, ARRAY_LENGTH(items, 1) → l "
                    + "(γ region, COLLECT(item) → items (O)))"))
                    .containsExactly("east|3|3", "west|2|2");
        }
    }

    @Test
    @DisplayName("LENGTH of a list names CARDINALITY, and ARRAY_LENGTH refuses a second dimension")
    void refusals() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThatThrownBy(() -> relix.relation("π LENGTH(items) → k (γ region, COLLECT(item) → items (O))").toList())
                    .hasMessageContaining("use CARDINALITY for an array's length");
            assertThatThrownBy(() -> relix.relation("π ARRAY_LENGTH(items, 2) → k (γ region, COLLECT(item) → items (O))")
                    .toList())
                    .hasMessageContaining("the dimension must be 1");
            assertThatThrownBy(() -> relix.relation("π CARDINALITY(item) → k (O)").toList())
                    .hasMessageContaining("CARDINALITY: expected an array");
        }
    }
}

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

/**
 * {@code STRING_AGG}, {@code GROUP_CONCAT} and {@code LISTAGG} join a group's values as
 * PostgreSQL's {@code string_agg} does, and {@code ARRAY_TO_STRING} an array's (#128).
 */
@DisplayName("STRING_AGG and ARRAY_TO_STRING, evaluated")
final class StringAggTest {

    private static final String ORDERS = """
            O := [| region | item  | qty  |
            | east   | pear  | 3    |
            | east   | apple | 1    |
            | east   | fig   | 2    |
            | east   | apple | 4    |
            | west   | NULL  | 5    |
            | north  | kiwi  | NULL |];
            """;

    private static List<String> rows(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining("|")))
                .toList();
    }

    @Test
    @DisplayName("STRING_AGG joins in its ORDER BY, skips NULLs, and is NULL for a group with no values")
    void ordered() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(rows(relix, "τ region (γ region, STRING_AGG(item, ', ' ORDER BY item, qty DESC) → items (O))"))
                    .containsExactly("east|apple, apple, fig, pear", "north|kiwi", "west|NULL");
            assertThat(rows(relix, "γ STRING_AGG(qty, '+' ORDER BY qty DESC) → s (O)")).containsExactly("5+4+3+2+1");
            assertThat(rows(relix, "γ STRING_AGG(DISTINCT item, '/' ORDER BY item) → s (O)"))
                    .containsExactly("apple/fig/kiwi/pear");
        }
    }

    @Test
    @DisplayName("GROUP_CONCAT and LISTAGG are the same aggregate, with their engines' default separators")
    void spellings() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(rows(relix, "σ region = 'east' (γ region, GROUP_CONCAT(item ORDER BY qty) → a, "
                    + "GROUP_CONCAT(item ORDER BY qty SEPARATOR '; ') → b, "
                    + "LISTAGG(item) WITHIN GROUP (ORDER BY qty) → c, "
                    + "LISTAGG(item, '-') WITHIN GROUP (ORDER BY qty DESC) → d (O))"))
                    .containsExactly("east|apple,fig,pear,apple|apple; fig; pear; apple|applefigpearapple|apple-pear-fig-apple");
        }
    }

    @Test
    @DisplayName("COLLECT and ARRAY_AGG take an ORDER BY too")
    void collectOrdered() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(rows(relix, "σ region = 'east' (γ region, ARRAY_AGG(qty ORDER BY qty) → a, "
                    + "COLLECT(item ORDER BY item DESC) → b (O))"))
                    .containsExactly("east|[1, 2, 3, 4]|[pear, fig, apple, apple]");
        }
    }

    @Test
    @DisplayName("ARRAY_TO_STRING leaves NULL elements out, or writes them as its third argument")
    void arrayToString() {
        try (Relix relix = Relix.open()) {
            relix.define(ORDERS);
            assertThat(rows(relix, "π ARRAY_TO_STRING(items, ',') → a, ARRAY_TO_STRING(items, ',', '*') → b, "
                    + "ARRAY_TO_STRING(items, NULL) → c (γ COLLECT(item ORDER BY qty) → items (O))"))
                    .containsExactly("apple,fig,pear,apple,kiwi|apple,fig,pear,apple,*,kiwi|NULL");
            assertThatThrownBy(() -> relix.relation("π ARRAY_TO_STRING(qty, ',') → a (O)").toList())
                    .hasMessageContaining("ARRAY_TO_STRING: expected an array");
        }
    }
}

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
package com.darkcollective.relix.provenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NamedSemiring")
final class NamedSemiringTest {

    private static NamedSemiring entry(String name, List<String> aliases) {
        return new NamedSemiring(name, aliases, BooleanSemiring.INSTANCE, "A description.");
    }

    @Test
    @DisplayName("lower-cases the canonical name and every alias")
    void lowerCases() {
        NamedSemiring e = entry("Tropical", List.of("Shortest-Path", "MIN_PLUS"));
        assertThat(e.name()).isEqualTo("tropical");
        assertThat(e.aliases()).containsExactly("shortest-path", "min_plus");
    }

    @Test
    @DisplayName("allNames() is the canonical name followed by the aliases")
    void allNames() {
        assertThat(entry("a", List.of("b", "c")).allNames()).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("of() makes an entry with no aliases")
    void ofHasNoAliases() {
        NamedSemiring e = NamedSemiring.of("solo", BooleanSemiring.INSTANCE, "Alone.");
        assertThat(e.aliases()).isEmpty();
        assertThat(e.allNames()).containsExactly("solo");
    }

    @Test
    @DisplayName("copies the alias list, so a later mutation cannot reach it")
    void copiesAliases() {
        List<String> mutable = new java.util.ArrayList<>(List.of("b"));
        NamedSemiring e = entry("a", mutable);
        mutable.add("c");
        assertThat(e.aliases()).containsExactly("b");
    }

    @Test
    @DisplayName("a blank name is refused")
    void blankName() {
        assertThatThrownBy(() -> entry("  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("a blank description is refused, and the message names the semiring")
    void blankDescription() {
        assertThatThrownBy(() -> new NamedSemiring(
                "tropical", List.of(), BooleanSemiring.INSTANCE, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tropical");
    }

    @Test
    @DisplayName("a blank alias is refused, and the message names the semiring")
    void blankAlias() {
        assertThatThrownBy(() -> entry("tropical", List.of("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tropical");
    }

    @Test
    @DisplayName("a null name, alias, semiring or description is refused")
    void nulls() {
        assertThatThrownBy(() -> entry(null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> entry("a", Arrays.asList((String) null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NamedSemiring("a", List.of(), null, "d"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NamedSemiring("a", List.of(), BooleanSemiring.INSTANCE, null))
                .isInstanceOf(NullPointerException.class);
    }
}

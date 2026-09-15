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
package com.darkcollective.relix.symbol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ColumnDefinition — validation and accessors")
final class ColumnDefinitionTest {

    @Test
    @DisplayName("Stores name and type correctly")
    void storesNameAndType() {
        ColumnDefinition col = new ColumnDefinition("age", ScalarType.NUMBER);
        assertThat(col.name()).isEqualTo("age");
        assertThat(col.type()).isEqualTo(ScalarType.NUMBER);
    }

    @Test
    @DisplayName("Rejects blank name")
    void rejectsBlankName() {
        assertThatThrownBy(() -> new ColumnDefinition("  ", ScalarType.STRING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects empty name")
    void rejectsEmptyName() {
        assertThatThrownBy(() -> new ColumnDefinition("", ScalarType.STRING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects null name")
    void rejectsNullName() {
        assertThatThrownBy(() -> new ColumnDefinition(null, ScalarType.STRING))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects null type")
    void rejectsNullType() {
        assertThatThrownBy(() -> new ColumnDefinition("id", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Preserves original casing of name")
    void preservesNameCasing() {
        ColumnDefinition col = new ColumnDefinition("UserId", ScalarType.NUMBER);
        assertThat(col.name()).isEqualTo("UserId");
    }

    // ── provenance (#454) ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Two-arg constructor leaves provenance null")
    void twoArgHasNoProvenance() {
        assertThat(new ColumnDefinition("id", ScalarType.NUMBER).provenance()).isNull();
    }

    @Test
    @DisplayName("withProvenance / withName carry provenance and type")
    void withProvenanceAndName() {
        ColumnProvenance prov = new ColumnProvenance("rooms", "name");
        ColumnDefinition col = new ColumnDefinition("name", ScalarType.STRING).withProvenance(prov);
        assertThat(col.provenance()).isEqualTo(prov);

        ColumnDefinition renamed = col.withName("name_r");
        assertThat(renamed.name()).isEqualTo("name_r");
        assertThat(renamed.type()).isEqualTo(ScalarType.STRING);
        assertThat(renamed.provenance()).isEqualTo(prov);   // origin survives the rename
    }

    @Test
    @DisplayName("matchesQualified is provenance-aware and case-insensitive")
    void matchesQualified() {
        ColumnDefinition col = new ColumnDefinition("name_r", ScalarType.STRING)
                .withProvenance(new ColumnProvenance("rooms", "name"));
        assertThat(col.matchesQualified("rooms", "name")).isTrue();
        assertThat(col.matchesQualified("ROOMS", "NAME")).isTrue();
        assertThat(col.matchesQualified("devices", "name")).isFalse();
        assertThat(new ColumnDefinition("name", ScalarType.STRING)
                .matchesQualified("rooms", "name")).isFalse();   // no provenance
    }

    @Test
    @DisplayName("Equality and hashCode ignore provenance (Schema equality unaffected)")
    void equalityIgnoresProvenance() {
        ColumnDefinition bare = new ColumnDefinition("name", ScalarType.STRING);
        ColumnDefinition withProv = bare.withProvenance(new ColumnProvenance("rooms", "name"));
        ColumnDefinition otherProv = bare.withProvenance(new ColumnProvenance("devices", "name"));

        assertThat(withProv).isEqualTo(bare).isEqualTo(otherProv);
        assertThat(withProv.hashCode()).isEqualTo(bare.hashCode());
    }

    @Test
    @DisplayName("Equality reads both the name and the type, not only the name")
    void equalityReadsNameAndType() {
        ColumnDefinition base = new ColumnDefinition("name", ScalarType.STRING);
        assertThat(base)
                .as("a different name")
                .isNotEqualTo(new ColumnDefinition("other", ScalarType.STRING));
        assertThat(base)
                .as("the same name at a different type — the arm a name-only test never reaches")
                .isNotEqualTo(new ColumnDefinition("name", ScalarType.NUMBER));
        assertThat(base).isNotEqualTo("name");
        assertThat(base).isEqualTo(base);
    }
}

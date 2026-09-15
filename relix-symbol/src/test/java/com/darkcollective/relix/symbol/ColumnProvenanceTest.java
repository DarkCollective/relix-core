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

@DisplayName("ColumnProvenance — source-relation origin of a column (#454)")
final class ColumnProvenanceTest {

    @Test
    @DisplayName("stores relation and column names")
    void storesFields() {
        ColumnProvenance p = new ColumnProvenance("rooms", "name");
        assertThat(p.relation()).isEqualTo("rooms");
        assertThat(p.column()).isEqualTo("name");
    }

    @Test
    @DisplayName("rejects blank relation or column")
    void rejectsBlank() {
        assertThatThrownBy(() -> new ColumnProvenance(" ", "name"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnProvenance("rooms", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects null relation or column")
    void rejectsNull() {
        assertThatThrownBy(() -> new ColumnProvenance(null, "name"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ColumnProvenance("rooms", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("matches relation and column case-insensitively")
    void matchesCaseInsensitively() {
        ColumnProvenance p = new ColumnProvenance("Rooms", "Name");
        assertThat(p.matches("rooms", "name")).isTrue();
        assertThat(p.matches("ROOMS", "NAME")).isTrue();
        assertThat(p.matches("rooms", "id")).isFalse();
        assertThat(p.matches("devices", "name")).isFalse();
    }

    @Test
    @DisplayName("reanchor changes the relation, keeps the column")
    void reanchor() {
        ColumnProvenance p = new ColumnProvenance("rooms", "name").reanchor("R");
        assertThat(p.relation()).isEqualTo("R");
        assertThat(p.column()).isEqualTo("name");
    }
}

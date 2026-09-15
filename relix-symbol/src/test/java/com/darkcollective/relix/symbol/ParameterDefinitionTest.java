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

@DisplayName("ParameterDefinition — validation and accessors")
final class ParameterDefinitionTest {

    @Test
    @DisplayName("Stores name and type correctly")
    void storesNameAndType() {
        ParameterDefinition param = new ParameterDefinition("x", ScalarType.NUMBER);
        assertThat(param.name()).isEqualTo("x");
        assertThat(param.type()).isEqualTo(ScalarType.NUMBER);
    }

    @Test
    @DisplayName("Rejects blank name")
    void rejectsBlankName() {
        assertThatThrownBy(() -> new ParameterDefinition("  ", ScalarType.STRING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not be blank");
    }

    @Test
    @DisplayName("Rejects empty name")
    void rejectsEmptyName() {
        assertThatThrownBy(() -> new ParameterDefinition("", ScalarType.NUMBER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects null name")
    void rejectsNullName() {
        assertThatThrownBy(() -> new ParameterDefinition(null, ScalarType.STRING))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects null type")
    void rejectsNullType() {
        assertThatThrownBy(() -> new ParameterDefinition("x", null))
                .isInstanceOf(NullPointerException.class);
    }
}

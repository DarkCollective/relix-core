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

@DisplayName("ScalarType — type lattice and case-insensitive lookup")
final class ScalarTypeTest {

    @Test
    @DisplayName("fromString resolves lowercase type name")
    void fromStringResolvesLowercase() {
        assertThat(ScalarType.fromString("string")).isEqualTo(ScalarType.STRING);
        assertThat(ScalarType.fromString("number")).isEqualTo(ScalarType.NUMBER);
        assertThat(ScalarType.fromString("boolean")).isEqualTo(ScalarType.BOOLEAN);
        assertThat(ScalarType.fromString("any")).isEqualTo(ScalarType.ANY);
    }

    @Test
    @DisplayName("fromString resolves uppercase type name")
    void fromStringResolvesUppercase() {
        assertThat(ScalarType.fromString("STRING")).isEqualTo(ScalarType.STRING);
        assertThat(ScalarType.fromString("NUMBER")).isEqualTo(ScalarType.NUMBER);
    }

    @Test
    @DisplayName("fromString resolves mixed-case type name")
    void fromStringResolvesMixedCase() {
        assertThat(ScalarType.fromString("String")).isEqualTo(ScalarType.STRING);
        assertThat(ScalarType.fromString("Number")).isEqualTo(ScalarType.NUMBER);
        assertThat(ScalarType.fromString("Boolean")).isEqualTo(ScalarType.BOOLEAN);
    }

    @Test
    @DisplayName("fromString throws for unknown type name")
    void fromStringThrowsForUnknownName() {
        assertThatThrownBy(() -> ScalarType.fromString("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fromString throws for blank input")
    void fromStringThrowsForBlankInput() {
        assertThatThrownBy(() -> ScalarType.fromString(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fromString round-trips the temporal type names")
    void fromStringResolvesTemporal() {
        assertThat(ScalarType.fromString("date")).isEqualTo(ScalarType.DATE);
        assertThat(ScalarType.fromString("TIME")).isEqualTo(ScalarType.TIME);
        assertThat(ScalarType.fromString("Timestamp")).isEqualTo(ScalarType.TIMESTAMP);
        assertThat(ScalarType.fromString("DURATION")).isEqualTo(ScalarType.DURATION);
    }

    @Test
    @DisplayName("display() lower-cases every constant name")
    void displayLowerCases() {
        assertThat(ScalarType.DATE.display()).isEqualTo("date");
        assertThat(ScalarType.TIME.display()).isEqualTo("time");
        assertThat(ScalarType.TIMESTAMP.display()).isEqualTo("timestamp");
        assertThat(ScalarType.DURATION.display()).isEqualTo("duration");
    }

    @Test
    @DisplayName("code() returns the documented compact codes")
    void codeReturnsCompactCodes() {
        assertThat(ScalarType.NUMBER.code()).isEqualTo("N");
        assertThat(ScalarType.STRING.code()).isEqualTo("S");
        assertThat(ScalarType.BOOLEAN.code()).isEqualTo("B");
        assertThat(ScalarType.ANY.code()).isEqualTo("?");
        assertThat(ScalarType.DATE.code()).isEqualTo("D");
        assertThat(ScalarType.TIME.code()).isEqualTo("T");
        assertThat(ScalarType.TIMESTAMP.code()).isEqualTo("TS");
        assertThat(ScalarType.DURATION.code()).isEqualTo("DUR");
    }

    @Test
    @DisplayName("Every constant has a distinct code")
    void codesAreDistinct() {
        long distinct = java.util.Arrays.stream(ScalarType.values())
                .map(ScalarType::code)
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(ScalarType.values().length);
    }

    @Test
    @DisplayName("Eight constants are defined (four scalar + four temporal)")
    void eightConstantsDefined() {
        assertThat(ScalarType.values()).hasSize(8);
        assertThat(ScalarType.values())
                .containsExactlyInAnyOrder(
                        ScalarType.ANY, ScalarType.STRING,
                        ScalarType.NUMBER, ScalarType.BOOLEAN,
                        ScalarType.DATE, ScalarType.TIME,
                        ScalarType.TIMESTAMP, ScalarType.DURATION);
    }
}

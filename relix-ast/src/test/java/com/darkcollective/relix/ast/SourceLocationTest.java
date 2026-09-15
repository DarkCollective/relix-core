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
package com.darkcollective.relix.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SourceLocation}: construction, validation, the
 * {@link SourceLocation#UNKNOWN} sentinel, {@code toString()}, and
 * record equality.
 */
@DisplayName("SourceLocation — construction, validation, toString, equality")
final class SourceLocationTest {

    // =========================================================================
    // Construction — valid inputs
    // =========================================================================

    @Nested
    @DisplayName("Construction — valid inputs")
    class ValidConstruction {

        @Test
        @DisplayName("Stores all three fields")
        void storesAllFields() {
            var loc = new SourceLocation("weather.relix", 12, 5);
            assertThat(loc.filePath()).isEqualTo("weather.relix");
            assertThat(loc.line()).isEqualTo(12);
            assertThat(loc.column()).isEqualTo(5);
        }

        @Test
        @DisplayName("Zero line and column are allowed (no-position sentinel pattern)")
        void zeroLineAndColumnAllowed() {
            var loc = new SourceLocation("<unknown>", 0, 0);
            assertThat(loc.line()).isEqualTo(0);
            assertThat(loc.column()).isEqualTo(0);
        }

        @Test
        @DisplayName("Line 1, column 1 is the minimum real position")
        void minRealPosition() {
            var loc = new SourceLocation("a.relix", 1, 1);
            assertThat(loc.line()).isEqualTo(1);
            assertThat(loc.column()).isEqualTo(1);
        }

        @Test
        @DisplayName("Large line and column numbers are accepted")
        void largeLinesAndColumns() {
            var loc = new SourceLocation("a.relix", 10000, 999);
            assertThat(loc.line()).isEqualTo(10000);
            assertThat(loc.column()).isEqualTo(999);
        }
    }

    // =========================================================================
    // Construction — invalid inputs
    // =========================================================================

    @Nested
    @DisplayName("Construction — invalid inputs")
    class InvalidConstruction {

        @Test
        @DisplayName("Null filePath throws NullPointerException")
        void nullFilePathThrows() {
            assertThatThrownBy(() -> new SourceLocation(null, 1, 1))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("filePath");
        }

        @Test
        @DisplayName("Negative line throws IllegalArgumentException")
        void negativeLineThrows() {
            assertThatThrownBy(() -> new SourceLocation("a.relix", -1, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("line must be >= 0");
        }

        @Test
        @DisplayName("Negative column throws IllegalArgumentException")
        void negativeColumnThrows() {
            assertThatThrownBy(() -> new SourceLocation("a.relix", 1, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("column must be >= 0");
        }
    }

    // =========================================================================
    // UNKNOWN sentinel
    // =========================================================================

    @Nested
    @DisplayName("UNKNOWN sentinel")
    class UnknownSentinel {

        @Test
        @DisplayName("UNKNOWN has filePath '<unknown>'")
        void unknownFilePath() {
            assertThat(SourceLocation.UNKNOWN.filePath()).isEqualTo("<unknown>");
        }

        @Test
        @DisplayName("UNKNOWN has line 0")
        void unknownLine() {
            assertThat(SourceLocation.UNKNOWN.line()).isEqualTo(0);
        }

        @Test
        @DisplayName("UNKNOWN has column 0")
        void unknownColumn() {
            assertThat(SourceLocation.UNKNOWN.column()).isEqualTo(0);
        }

        @Test
        @DisplayName("UNKNOWN toString omits positional part")
        void unknownToString() {
            assertThat(SourceLocation.UNKNOWN.toString()).isEqualTo("<unknown>");
        }
    }

    // =========================================================================
    // toString
    // =========================================================================

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("Known location includes file, line, column")
        void knownLocationToString() {
            var loc = new SourceLocation("weather.relix", 12, 5);
            assertThat(loc.toString()).isEqualTo("weather.relix:12:5");
        }

        @Test
        @DisplayName("Line 0 (no position) omits positional suffix")
        void zeroLineToString() {
            var loc = new SourceLocation("a.relix", 0, 0);
            assertThat(loc.toString()).isEqualTo("a.relix");
        }
    }

    // =========================================================================
    // Record equality
    // =========================================================================

    @Nested
    @DisplayName("Record equality and hashCode")
    class EqualityTests {

        @Test
        @DisplayName("Same fields produce equal records")
        void sameFieldsAreEqual() {
            var a = new SourceLocation("a.relix", 3, 7);
            var b = new SourceLocation("a.relix", 3, 7);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Different file paths are not equal")
        void differentFilePathsNotEqual() {
            var a = new SourceLocation("a.relix", 3, 7);
            var b = new SourceLocation("b.relix", 3, 7);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Different line numbers are not equal")
        void differentLinesNotEqual() {
            var a = new SourceLocation("a.relix", 3, 7);
            var b = new SourceLocation("a.relix", 4, 7);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Different column numbers are not equal")
        void differentColumnsNotEqual() {
            var a = new SourceLocation("a.relix", 3, 7);
            var b = new SourceLocation("a.relix", 3, 8);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("UNKNOWN equals another SourceLocation with same fields")
        void unknownEqualsSentinelFields() {
            var loc = new SourceLocation("<unknown>", 0, 0);
            assertThat(loc).isEqualTo(SourceLocation.UNKNOWN);
        }
    }
}

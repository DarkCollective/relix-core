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
package com.darkcollective.relix.semantic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SemanticError — construction, factories, toString")
final class SemanticErrorTest {

    private static final String FILE = "./api.relix";

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("All fields stored correctly")
        void allFieldsStored() {
            var e = new SemanticError(FILE, 12, 5, "Unknown relation 'Foo'", Severity.ERROR);
            assertThat(e.filePath()).isEqualTo(FILE);
            assertThat(e.line()).isEqualTo(12);
            assertThat(e.column()).isEqualTo(5);
            assertThat(e.message()).isEqualTo("Unknown relation 'Foo'");
            assertThat(e.severity()).isEqualTo(Severity.ERROR);
        }

        @Test
        @DisplayName("Line and column may be zero (no position)")
        void zeroPositionIsAllowed() {
            var e = new SemanticError(FILE, 0, 0, "Import file not found", Severity.ERROR);
            assertThat(e.line()).isEqualTo(0);
            assertThat(e.column()).isEqualTo(0);
        }

        @Test
        @DisplayName("Null filePath throws NullPointerException")
        void nullFilePathThrows() {
            assertThatThrownBy(() -> new SemanticError(null, 1, 1, "msg", Severity.ERROR))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null message throws NullPointerException")
        void nullMessageThrows() {
            assertThatThrownBy(() -> new SemanticError(FILE, 1, 1, null, Severity.ERROR))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Blank message throws IllegalArgumentException")
        void blankMessageThrows() {
            assertThatThrownBy(() -> new SemanticError(FILE, 1, 1, "   ", Severity.ERROR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("Null severity throws NullPointerException")
        void nullSeverityThrows() {
            assertThatThrownBy(() -> new SemanticError(FILE, 1, 1, "msg", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class Factories {

        @Test
        @DisplayName("error() creates ERROR severity")
        void errorFactorySetsSeverity() {
            var e = SemanticError.error(FILE, 3, 7, "Type mismatch");
            assertThat(e.severity()).isEqualTo(Severity.ERROR);
            assertThat(e.filePath()).isEqualTo(FILE);
            assertThat(e.line()).isEqualTo(3);
            assertThat(e.column()).isEqualTo(7);
            assertThat(e.message()).isEqualTo("Type mismatch");
        }

        @Test
        @DisplayName("warning() creates WARNING severity")
        void warningFactorySetsSeverity() {
            var e = SemanticError.warning(FILE, 1, 1, "Unused import");
            assertThat(e.severity()).isEqualTo(Severity.WARNING);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("Error toString has expected format")
        void errorToString() {
            var e = SemanticError.error(FILE, 12, 5, "Unknown relation 'Foo'");
            assertThat(e.toString())
                    .isEqualTo("./api.relix:12:5: error: Unknown relation 'Foo'");
        }

        @Test
        @DisplayName("Warning toString has expected format")
        void warningToString() {
            var e = SemanticError.warning(FILE, 3, 1, "Unused import");
            assertThat(e.toString())
                    .isEqualTo("./api.relix:3:1: warning: Unused import");
        }
    }

    @Test
    @DisplayName("Record equality — same fields means equal")
    void recordEquality() {
        var a = SemanticError.error(FILE, 1, 1, "msg");
        var b = SemanticError.error(FILE, 1, 1, "msg");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}

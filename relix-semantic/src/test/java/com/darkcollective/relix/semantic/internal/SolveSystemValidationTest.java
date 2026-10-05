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
package com.darkcollective.relix.semantic.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * Validation of {@code SOLVE} over systems of equations and through user-defined
 * functions — the equation as checked being the expanded one that is solved.
 */
@DisplayName("SOLVE — systems and def expansion")
final class SolveSystemValidationTest {

    private static final String PAIRS = """
            Pairs := [
            | total | diff | a | b |
            |-------|------|---|---|
            | 10    | 2    |   |   |
            ];
            """;

    private static final String RODS = """
            Rods := [
            | length | L0 | k | temp |
            |--------|----|---|------|
            | 100.1  | 100|   | 30   |
            ];
            """;

    @Nested
    @DisplayName("a system")
    final class Systems {

        @Test
        @DisplayName("may name a column in several equations")
        void repeatsAColumnAcrossEquations() {
            assertThat(analyze(PAIRS + "query { SOLVE { total = a + b, diff = a - b } (Pairs) };"))
                    .hasNoErrors();
        }

        @Test
        @DisplayName("checks every equation's columns")
        void reportsAnUnknownColumnInAnyEquation() {
            assertThat(analyze(PAIRS + "query { SOLVE { total = a + b, diff = a - c } (Pairs) };"))
                    .hasErrorContaining("'c'");
        }

        @Test
        @DisplayName("reports a column of the wrong type once, however often it appears")
        void reportsATypeErrorOnce() {
            String src = """
                    T := [
                    | name | a | b |
                    |------|---|---|
                    | x    | 1 |   |
                    ];
                    query { SOLVE { name = a + b, name = a - b } (T) };
                    """;
            assertThat(analyze(src)).hasDiagnosticCount(1).hasErrorContaining("must be NUMBER");
        }

        @Test
        @DisplayName("rejects a built-in function in any equation")
        void rejectsABuiltIn() {
            assertThat(analyze(PAIRS + "query { SOLVE { total = a + b, diff = Abs(a) } (Pairs) };"))
                    .hasErrorContaining("cannot be inverted");
        }

        @Test
        @DisplayName("rejects an equation that names no column")
        void rejectsAColumnlessEquation() {
            assertThat(analyze(PAIRS + "query { SOLVE { total = a + b, 1 = 1 } (Pairs) };"))
                    .hasErrorContaining("at least one column");
        }
    }

    @Nested
    @DisplayName("a def")
    final class Defs {

        @Test
        @DisplayName("whose body is arithmetic is solved through")
        void expandsAnArithmeticDef() {
            String src = RODS + """
                    def predicted(L0: NUMBER, k: NUMBER, T: NUMBER) : NUMBER := { L0 * (1 + k * (T - 20)) };
                    query { SOLVE length = predicted(L0, k, temp) (Rods) };
                    """;
            assertThat(analyze(src)).hasNoErrors();
        }

        @Test
        @DisplayName("calling another def is followed through both")
        void expandsNestedDefs() {
            String src = RODS + """
                    def growth(k: NUMBER, T: NUMBER) : NUMBER := { k * (T - 20) };
                    def predicted(L0: NUMBER, k: NUMBER, T: NUMBER) : NUMBER := { L0 * (1 + growth(k, T)) };
                    query { SOLVE length = predicted(L0, k, temp) (Rods) };
                    """;
            assertThat(analyze(src)).hasNoErrors();
        }

        @Test
        @DisplayName("that uses a parameter twice is named when its argument repeats")
        void namesTheDefBehindARepeat() {
            String src = RODS + """
                    def square(x: NUMBER) : NUMBER := { x * x };
                    query { SOLVE length = square(k) (Rods) };
                    """;
            assertThat(analyze(src))
                    .hasErrorContaining("'k' appears more than once")
                    .hasErrorContaining("after expanding square");
        }

        @Test
        @DisplayName("whose body is not arithmetic still cannot be inverted")
        void rejectsANonArithmeticBody() {
            String src = RODS + """
                    def magnitude(x: NUMBER) : NUMBER := { Abs(x) };
                    query { SOLVE length = magnitude(k) (Rods) };
                    """;
            assertThat(analyze(src)).hasErrorContaining("cannot be inverted");
        }

        @Test
        @DisplayName("of the wrong arity is not expanded")
        void leavesAMismatchedCall() {
            String src = RODS + """
                    def twice(x: NUMBER) : NUMBER := { x * 2 };
                    query { SOLVE length = twice(k, L0) (Rods) };
                    """;
            assertThat(analyze(src)).hasErrorContaining("cannot be inverted");
        }
    }
}

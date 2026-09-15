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
package com.darkcollective.relix.solver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("LinearProgram — the numeric statement of a problem")
final class LinearProgramTest {

    @Nested
    @DisplayName("Variables")
    final class Variables {

        @Test
        @DisplayName("a binary variable is an integral 0/1 carrying its objective weight")
        void binary() {
            LinearProgram.Variable variable = LinearProgram.Variable.binary(7.5);
            assertThat(variable.integral()).isTrue();
            assertThat(variable.lower()).isZero();
            assertThat(variable.upper()).isEqualTo(1.0);
            assertThat(variable.weight()).isEqualTo(7.5);
        }

        @Test
        @DisplayName("a continuous variable keeps its range and is not integral")
        void continuous() {
            LinearProgram.Variable variable = LinearProgram.Variable.continuous(0.0, 0.4, -2.0);
            assertThat(variable.integral()).isFalse();
            assertThat(variable.lower()).isZero();
            assertThat(variable.upper()).isEqualTo(0.4);
            assertThat(variable.weight()).isEqualTo(-2.0);
        }
    }

    @Nested
    @DisplayName("Constraints")
    final class Constraints {

        @Test
        @DisplayName("coefficients are copied in and out, so a solver cannot mutate the program")
        void coefficientsAreDefensivelyCopied() {
            double[] source = {1.0, 2.0};
            var constraint = new LinearProgram.Constraint(source, LinearProgram.Relation.AT_MOST, 3.0);

            source[0] = 99.0;                       // mutate what was passed in
            constraint.coefficients()[1] = 99.0;    // mutate what was handed back

            assertThat(constraint.coefficients()).containsExactly(1.0, 2.0);
        }

        @Test
        @DisplayName("a null relation or coefficient array is rejected at construction")
        void rejectsNulls() {
            assertThatNullPointerException().isThrownBy(() ->
                    new LinearProgram.Constraint(null, LinearProgram.Relation.AT_MOST, 1.0));
            assertThatNullPointerException().isThrownBy(() ->
                    new LinearProgram.Constraint(new double[0], null, 1.0));
        }
    }

    @Nested
    @DisplayName("Programs")
    final class Programs {

        @Test
        @DisplayName("a constraint whose width does not match the variable count is rejected")
        void widthMismatchIsRejected() {
            List<LinearProgram.Variable> two = List.of(
                    LinearProgram.Variable.binary(1.0), LinearProgram.Variable.binary(1.0));
            var narrow = new LinearProgram.Constraint(
                    new double[]{1.0}, LinearProgram.Relation.AT_MOST, 1.0);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new LinearProgram(LinearProgram.Sense.MAXIMISE, two, List.of(narrow)))
                    .withMessageContaining("1 coefficients for 2 variables");
        }

        @Test
        @DisplayName("variables and constraints are copied, so the program is immutable")
        void componentsAreCopied() {
            List<LinearProgram.Variable> variables = new ArrayList<>();
            variables.add(LinearProgram.Variable.binary(1.0));
            var program = new LinearProgram(LinearProgram.Sense.MINIMISE, variables, List.of());

            variables.add(LinearProgram.Variable.binary(2.0));

            assertThat(program.variables()).hasSize(1);
            assertThat(program.constraints()).isEmpty();
        }

        @Test
        @DisplayName("a null sense is rejected")
        void rejectsNullSense() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new LinearProgram(null, List.of(), List.of()));
        }

        @Test
        @DisplayName("an unconstrained program is legal — every constraint is optional")
        void constraintsMayBeEmpty() {
            var program = new LinearProgram(LinearProgram.Sense.MAXIMISE,
                    List.of(LinearProgram.Variable.binary(1.0)), List.of());
            assertThat(program.constraints()).isEmpty();
            assertThat(program.sense()).isEqualTo(LinearProgram.Sense.MAXIMISE);
        }
    }
}

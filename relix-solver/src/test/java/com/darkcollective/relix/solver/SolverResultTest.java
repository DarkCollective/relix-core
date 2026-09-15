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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("SolverResult — solved, proved infeasible, or undecided")
final class SolverResultTest {

    @Test
    @DisplayName("a solved result carries the variable values")
    void solvedCarriesValues() {
        SolverResult result = SolverResult.solved(new double[]{1.0, 0.0});
        assertThat(result).isInstanceOfSatisfying(SolverResult.Solved.class,
                s -> assertThat(s.values()).containsExactly(1.0, 0.0));
    }

    @Test
    @DisplayName("infeasible and undetermined are different answers, not two spellings of one")
    void infeasibleIsNotUndetermined() {
        assertThat(SolverResult.infeasible()).isInstanceOf(SolverResult.Infeasible.class);
        assertThat(SolverResult.undetermined("FAILED"))
                .isInstanceOfSatisfying(SolverResult.Undetermined.class,
                        u -> assertThat(u.reason()).isEqualTo("FAILED"));
        assertThat(SolverResult.infeasible()).isNotInstanceOf(SolverResult.Undetermined.class);
    }

    @Test
    @DisplayName("every infeasible result is equal to every other — it carries nothing")
    void infeasibleResultsAreEqual() {
        assertThat(SolverResult.infeasible()).isEqualTo(SolverResult.infeasible());
    }

    @Test
    @DisplayName("neither values nor a reason may be null")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> SolverResult.solved(null));
        assertThatNullPointerException().isThrownBy(() -> SolverResult.undetermined(null));
    }
}

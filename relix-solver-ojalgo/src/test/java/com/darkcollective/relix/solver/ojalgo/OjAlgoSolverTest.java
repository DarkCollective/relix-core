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
package com.darkcollective.relix.solver.ojalgo;

import com.darkcollective.relix.solver.LinearProgram;
import com.darkcollective.relix.solver.MathProgrammingSolver;
import com.darkcollective.relix.solver.SolverCatalog;
import com.darkcollective.relix.solver.SolverResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("OjAlgoSolver — the shipped branch-and-bound behind OPTIMIZE")
final class OjAlgoSolverTest {

    private final MathProgrammingSolver solver = new OjAlgoSolver();

    /** Asserts the solver decided, and returns the values it decided on. */
    private static double[] solved(SolverResult result) {
        assertThat(result).isInstanceOf(SolverResult.Solved.class);
        return ((SolverResult.Solved) result).values();
    }

    @Test
    @DisplayName("solves a 0/1 knapsack to the optimum")
    void knapsack() {
        // Two items worth 6 and 5, weighing 2 and 3, in a bag that holds 4:
        // only the first fits alongside nothing else, and it is the better item.
        var program = new LinearProgram(
                LinearProgram.Sense.MAXIMISE,
                List.of(LinearProgram.Variable.binary(6.0), LinearProgram.Variable.binary(5.0)),
                List.of(new LinearProgram.Constraint(
                        new double[]{2.0, 3.0}, LinearProgram.Relation.AT_MOST, 4.0)));

        double[] values = solved(solver.solve(program));

        assertThat(values[0]).isEqualTo(1.0, within(1e-6));
        assertThat(values[1]).isZero();
    }

    @Test
    @DisplayName("allocates continuously within each variable's range")
    void continuousAllocation() {
        // Maximise 3a + 1b with a, b in [0, 1] and a + b = 1: put everything on a.
        var program = new LinearProgram(
                LinearProgram.Sense.MAXIMISE,
                List.of(LinearProgram.Variable.continuous(0.0, 1.0, 3.0),
                        LinearProgram.Variable.continuous(0.0, 1.0, 1.0)),
                List.of(new LinearProgram.Constraint(
                        new double[]{1.0, 1.0}, LinearProgram.Relation.EXACTLY, 1.0)));

        double[] values = solved(solver.solve(program));

        assertThat(values[0]).isEqualTo(1.0, within(1e-6));
        assertThat(values[1]).isEqualTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("an unsatisfiable program is reported as infeasible, not as a failure")
    void infeasible() {
        // One binary variable required to sum to at least 2 — no assignment does.
        var program = new LinearProgram(
                LinearProgram.Sense.MAXIMISE,
                List.of(LinearProgram.Variable.binary(1.0)),
                List.of(new LinearProgram.Constraint(
                        new double[]{1.0}, LinearProgram.Relation.AT_LEAST, 2.0)));

        assertThat(solver.solve(program)).isInstanceOf(SolverResult.Infeasible.class);
    }

    @Test
    @DisplayName("minimising a positive objective under a ≥ bound picks the cheapest cover")
    void minimisation() {
        // Set cover in miniature: two candidates, either one covers the demand;
        // minimising the count must choose exactly one.
        var program = new LinearProgram(
                LinearProgram.Sense.MINIMISE,
                List.of(LinearProgram.Variable.binary(1.0), LinearProgram.Variable.binary(1.0)),
                List.of(new LinearProgram.Constraint(
                        new double[]{1.0, 1.0}, LinearProgram.Relation.AT_LEAST, 1.0)));

        double[] values = solved(solver.solve(program));

        assertThat(values[0] + values[1]).isEqualTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("is discovered as a service, so an installed runtime finds it")
    void isDiscoverable() {
        assertThat(SolverCatalog.discover().solver())
                .get()
                .isInstanceOf(OjAlgoSolver.class);
    }

    @Test
    @DisplayName("names itself for diagnostics and takes the bundled priority")
    void identifiesItself() {
        assertThat(solver.name()).isEqualTo("ojAlgo");
        assertThat(solver.priority()).isZero();
    }
}

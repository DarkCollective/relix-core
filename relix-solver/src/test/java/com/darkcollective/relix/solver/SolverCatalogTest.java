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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SolverCatalog — which solver a runtime uses, and whether it has one")
final class SolverCatalogTest {

    private record NamedSolver(String name, int priority) implements MathProgrammingSolver {
        @Override public SolverResult solve(LinearProgram program) {
            return SolverResult.solved(new double[program.variables().size()]);
        }
    }

    @Test
    @DisplayName("an empty catalog offers no solver and says so")
    void emptyCatalog() {
        SolverCatalog catalog = SolverCatalog.empty();
        assertThat(catalog.isEmpty()).isTrue();
        assertThat(catalog.solver()).isEmpty();
        assertThat(catalog.solvers()).isEmpty();
    }

    @Test
    @DisplayName("the highest priority wins, so a replacement needs nothing removed")
    void highestPriorityWins() {
        var bundled = new NamedSolver("bundled", 0);
        var replacement = new NamedSolver("replacement", 10);

        SolverCatalog catalog = SolverCatalog.of(List.of(bundled, replacement));

        assertThat(catalog.solver()).contains(replacement);
        assertThat(catalog.solvers()).containsExactly(replacement, bundled);
        assertThat(catalog.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("the default priority is zero, so unranked providers keep their order")
    void defaultPriorityIsZero() {
        MathProgrammingSolver plain = new MathProgrammingSolver() {
            @Override public String name() { return "plain"; }
            @Override public SolverResult solve(LinearProgram p) { return SolverResult.infeasible(); }
        };
        assertThat(plain.priority()).isZero();

        var first = new NamedSolver("first", 0);
        assertThat(SolverCatalog.of(List.of(first, plain)).solver()).contains(first);
    }

    @Test
    @DisplayName("discovery finds nothing in a build with no provider on the test path")
    void discoveryOverThisModuleAlone() {
        // relix-solver's own test runtime installs no provider — the SPI is the whole
        // module — so this is the "runtime with no solver" case the planner reports.
        assertThat(SolverCatalog.discover().isEmpty()).isTrue();
        assertThat(SolverCatalog.installed().isEmpty()).isTrue();
    }
}

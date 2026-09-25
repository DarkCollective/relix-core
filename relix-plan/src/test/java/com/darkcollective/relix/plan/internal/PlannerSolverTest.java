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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.solver.LinearProgram;
import com.darkcollective.relix.solver.MathProgrammingSolver;
import com.darkcollective.relix.solver.SolverResult;
import com.darkcollective.relix.solver.SolverCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.plan.PlanAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * ADR-0026 S13 — a query that states a mathematical program is refused at plan time
 * when nothing can solve one.
 *
 * <p>The alternative is discovering it inside the executor, after the inputs have been
 * read, with a message about a missing service rather than about the operator the user
 * wrote. Only two operators are affected, which is why the check is per-operator: a
 * build with no solver runs everything else, {@code SOLVE} included — that one inverts
 * arithmetic in pure Java and never states a program.
 */
@DisplayName("Planner — OPTIMIZE and COVER EXACT need an installed solver")
final class PlannerSolverTest {

    private static final String OPTIMIZE_SRC = """
            Items := [
            | item | value | weight |
            |------|-------|--------|
            | a    | 6     | 2      |
            | b    | 5     | 3      |
            ];
            query { OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 4 (Items) };
            """;

    private static final String COVER_EXACT_SRC = """
            Configs := [
            | os    | browser |
            |-------|---------|
            | linux | ff      |
            | mac   | safari  |
            ];
            query { COVER EXACT 2 (Configs) };
            """;

    private static final String COVER_GREEDY_SRC = """
            Configs := [
            | os    | browser |
            |-------|---------|
            | linux | ff      |
            | mac   | safari  |
            ];
            query { COVER 2 (Configs) };
            """;

    /** A solver that is never asked to solve anything — its presence is the point. */
    private static final MathProgrammingSolver STUB = new MathProgrammingSolver() {
        @Override public String name() { return "stub"; }
        @Override public SolverResult solve(LinearProgram program) { return SolverResult.infeasible(); }
    };

    @Test
    @DisplayName("OPTIMIZE with no solver installed fails while planning, naming the operator")
    void optimizeWithoutASolverIsAPlanTimeError() {
        assertThatExceptionOfType(NoSolverInstalledException.class)
                .isThrownBy(() -> plan(OPTIMIZE_SRC, SolverCatalog.empty()))
                .withMessageContaining("OPTIMIZE")
                .withMessageContaining("relix-solver-ojalgo");
    }

    @Test
    @DisplayName("COVER EXACT with no solver installed fails while planning")
    void coverExactWithoutASolverIsAPlanTimeError() {
        assertThatExceptionOfType(NoSolverInstalledException.class)
                .isThrownBy(() -> plan(COVER_EXACT_SRC, SolverCatalog.empty()))
                .withMessageContaining("COVER EXACT");
    }

    @Test
    @DisplayName("greedy COVER plans with no solver at all — it states no program")
    void greedyCoverNeedsNoSolver() {
        assertThatCode(() -> plan(COVER_GREEDY_SRC, SolverCatalog.empty()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SOLVE plans with no solver at all — it inverts arithmetic in pure Java")
    void goalSeekNeedsNoSolver() {
        String src = """
                Readings := [
                | a | b | total |
                |---|---|-------|
                | 1 | 2 | 3     |
                ];
                query { SOLVE a + b = total (Readings) };
                """;
        assertThatCode(() -> plan(src, SolverCatalog.empty())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("any installed solver satisfies the check — the planner never solves")
    void anInstalledSolverIsEnoughToPlan() {
        assertThat(plan(OPTIMIZE_SRC, SolverCatalog.of(List.of(STUB))))
                .isNode(PhysicalNode.Optimize.class);
    }

    private static PhysicalNode plan(String src, SolverCatalog solvers) {
        SemanticModel model = model(src);
        RelNode logical = ((ExpressionQueryTarget) model.rootQueries().get(0).target()).expression();
        return new Planner(model.symbolTable(), model.nodeSchemas())
                .withSolvers(solvers)
                .plan(logical);
    }
}

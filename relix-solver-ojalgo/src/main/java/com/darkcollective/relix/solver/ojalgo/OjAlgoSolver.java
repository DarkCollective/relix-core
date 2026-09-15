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
import com.darkcollective.relix.solver.SolverResult;

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;
import org.ojalgo.type.context.NumberContext;


/**
 * The shipped {@link MathProgrammingSolver}: ojAlgo's deterministic branch-and-bound.
 *
 * <p>Every ojAlgo type in the build is named in this one class. Instances hold no
 * state and are therefore thread-safe; a model is built per solve.
 */
public final class OjAlgoSolver implements MathProgrammingSolver {

    /**
     * Feasibility tolerance for every model built here, loosened from the solver's
     * default of 12 significant digits.
     *
     * <p>At the default, branch-and-bound reports plainly feasible mixed-integer
     * problems as INFEASIBLE — a knapsack whose empty selection already satisfies
     * every {@code ≤} constraint cannot be infeasible, yet roughly 1% of random
     * instances came back that way, and a further 0.5% came back sub-optimal. The
     * lever is the <em>precision</em>, not the scale: at 10 significant digits or fewer,
     * 6000 random instances checked against brute force were exactly optimal;
     * tightening past the default makes it dramatically worse. See issue #579 for
     * the sweep, the four model formulations ruled out, and why neither an upgrade
     * (57.1.0 trades the false infeasibility for silent sub-optimality) nor a
     * downgrade (55.x ships no {@code module-info}) is the answer.
     */
    private static final NumberContext FEASIBILITY = NumberContext.of(10, 8);

    /** @return the solver's name, for diagnostics */
    @Override
    public String name() {
        return "ojAlgo";
    }

    @Override
    public SolverResult solve(LinearProgram program) {
        ExpressionsBasedModel model = new ExpressionsBasedModel();
        model.options.feasibility = FEASIBILITY;

        int count = program.variables().size();
        for (int i = 0; i < count; i++) {
            LinearProgram.Variable variable = program.variables().get(i);
            Variable added = model.addVariable("x" + i)
                    .lower(variable.lower())
                    .upper(variable.upper())
                    .weight(variable.weight());
            if (variable.integral()) {
                added.integer(true);
            }
        }

        int c = 0;
        for (LinearProgram.Constraint constraint : program.constraints()) {
            Expression expression = model.addExpression("c" + c++);
            double[] coefficients = constraint.coefficients();
            for (int i = 0; i < count; i++) {
                expression.set(i, coefficients[i]);
            }
            switch (constraint.relation()) {
                case AT_MOST  -> expression.upper(constraint.bound());
                case AT_LEAST -> expression.lower(constraint.bound());
                case EXACTLY  -> expression.level(constraint.bound());
            }
        }

        Optimisation.Result result = program.sense() == LinearProgram.Sense.MAXIMISE
                ? model.maximise()
                : model.minimise();
        if (!result.getState().isFeasible()) {
            // Only INFEASIBLE is a statement about the program; every other
            // non-feasible state — FAILED, UNBOUNDED, INVALID, UNEXPLORED — says the
            // search stopped without deciding, which the engine must not read as
            // "this group has no answer".
            return result.getState() == Optimisation.State.INFEASIBLE
                    ? SolverResult.infeasible()
                    : SolverResult.undetermined(result.getState().name());
        }
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = result.doubleValue(i);
        }
        return SolverResult.solved(values);
    }
}

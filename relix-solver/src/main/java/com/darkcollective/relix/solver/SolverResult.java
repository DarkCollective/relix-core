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

import java.util.Objects;

/**
 * What a solver has to say about a {@link LinearProgram}: it found an optimum, it
 * proved there is none, or it did not decide.
 *
 * <p>The third case is the reason this is a type rather than an {@code Optional}.
 * "No assignment satisfies the constraints" and "the search ran out of road" are
 * both answers with no values attached, and a caller that cannot tell them apart
 * has to guess — which in practice means reporting a failed search as an empty
 * result and returning rows that are quietly wrong. A provider says which it means;
 * the engine decides what to do about it.
 */
public sealed interface SolverResult {

    /**
     * An optimal assignment.
     *
     * @param values the value of each variable, positionally matching
     *               {@link LinearProgram#variables()}
     */
    record Solved(double[] values) implements SolverResult {

        /**
         * @param values the variable values; must not be null
         */
        public Solved {
            Objects.requireNonNull(values, "values");
        }
    }

    /**
     * The program is <em>proved</em> infeasible — no assignment satisfies its
     * constraints. A legitimate answer, and one the engine acts on normally.
     */
    record Infeasible() implements SolverResult {
    }

    /**
     * The search did not decide: it failed, hit a limit, found the program
     * unbounded or malformed, or stopped for any other reason short of an answer.
     * Distinct from {@link Infeasible} — nothing has been proved about the program.
     *
     * @param reason a short provider-supplied explanation, for the diagnostic the
     *               engine raises (e.g. the solver's own status name)
     */
    record Undetermined(String reason) implements SolverResult {

        /**
         * @param reason the explanation; must not be null
         */
        public Undetermined {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Returns a solved result.
     *
     * @param values the variable values
     * @return the result
     */
    static SolverResult solved(double[] values) {
        return new Solved(values);
    }

    /**
     * Returns the proved-infeasible result.
     *
     * @return the result
     */
    static SolverResult infeasible() {
        return new Infeasible();
    }

    /**
     * Returns an undecided result.
     *
     * @param reason a short explanation of why the search did not answer
     * @return the result
     */
    static SolverResult undetermined(String reason) {
        return new Undetermined(reason);
    }
}

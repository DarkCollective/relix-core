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

/**
 * Solves a {@link LinearProgram} — the provider seam behind the {@code OPTIMIZE}
 * operator and {@code COVER EXACT}.
 *
 * <p>The engine builds the program and reads the answer; a provider performs the
 * search. Nothing in this interface is relational, which is the point: a solver author
 * needs branch-and-bound, not a row model. The shipped provider is ojAlgo
 * ({@code relix-solver-ojalgo}); it compiles against this module and nothing else, so
 * anything it can do a third party can do.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader} through
 * {@link SolverCatalog}, and must be safe to call from several threads at once — the
 * engine solves one program per group and does not serialise them.
 */
public interface MathProgrammingSolver {

    /**
     * A short name for this solver, used in diagnostics.
     *
     * @return the solver's name, e.g. {@code "ojAlgo"}
     */
    String name();

    /**
     * Where this solver sits when more than one is installed: the highest priority
     * wins, and the shipped provider is {@code 0}, so dropping in a replacement works
     * without having to remove anything.
     *
     * @return this solver's priority
     */
    default int priority() {
        return 0;
    }

    /**
     * Solves a program.
     *
     * <p>Three outcomes, and a provider is expected to distinguish them rather than
     * collapse the last two: an optimal assignment
     * ({@link SolverResult.Solved}), a program <em>proved</em> to have none
     * ({@link SolverResult.Infeasible}), and a search that did not decide —
     * failed, cut off, unbounded, malformed ({@link SolverResult.Undetermined},
     * carrying a short reason). Infeasibility is a normal answer the engine acts on;
     * an undecided search is an error it reports, because a query cannot honestly
     * say "no rows" about a question nobody answered. An exception means the
     * provider itself broke.
     *
     * @param program the program to solve; must not be null
     * @return what the solver concluded; never {@code null}
     */
    SolverResult solve(LinearProgram program);
}

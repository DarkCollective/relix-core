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
 * A solver that cannot be instantiated, standing in for the shipped provider on a host
 * whose solver library is absent.
 *
 * <p>The failure is modelled where the real one occurs. {@code ServiceLoader} loads a
 * provider class with initialisation deferred, so a class that merely <em>mentions</em> a
 * missing type survives discovery and fails later; what makes the shipped provider fail
 * during discovery is a static field of a library type, which forces initialisation when
 * the provider is constructed. This raises the same error from the same place.
 */
public final class UnloadableTestSolver implements MathProgrammingSolver {

    static {
        if (Boolean.parseBoolean("true")) {
            throw new NoClassDefFoundError("org/example/AbsentLibraryType");
        }
    }

    /** Required by {@link java.util.ServiceLoader}. */
    public UnloadableTestSolver() {}

    @Override
    public String name() {
        return "unloadable-test-solver";
    }

    @Override
    public SolverResult solve(LinearProgram program) {
        return SolverResult.infeasible();
    }
}

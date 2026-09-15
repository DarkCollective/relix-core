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
/**
 * The shipped mathematical-programming provider — ojAlgo's deterministic
 * branch-and-bound behind the engine's solver seam.
 *
 * <p>This module is where {@code ojalgo} lives. It requires the SPI and the solver
 * library and nothing else, so it reaches nothing a third-party provider could not,
 * and the engine that used to link it directly now links nothing at all.
 *
 * <p>The provider is declared twice — here and in {@code META-INF/services} — because
 * a module path reads the first and a classpath reads the second.
 */
module com.darkcollective.relix.solver.ojalgo {
    requires com.darkcollective.relix.solver;
    requires ojalgo;

    provides com.darkcollective.relix.solver.MathProgrammingSolver
            with com.darkcollective.relix.solver.ojalgo.OjAlgoSolver;
}

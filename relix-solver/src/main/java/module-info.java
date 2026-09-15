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
 * The mathematical-programming SPI — the seam between the engine's two
 * mixed-integer-program operators and whatever solves them.
 *
 * <p>A leaf module: it names no other relix module and nothing outside
 * {@code java.base}, because a linear program is variables, coefficients and bounds.
 * That is what lets the planner (which must know whether a solver exists before a
 * query runs) and the executor (which uses it) share one seam without either one
 * depending on the other, and what lets a solver provider compile against this and
 * nothing else.
 */
module com.darkcollective.relix.solver {
    // Discovery is declared here so it happens once and no consumer needs a `uses`
    // clause of its own — ServiceLoader.load binds against the calling module.
    uses com.darkcollective.relix.solver.MathProgrammingSolver;

    exports com.darkcollective.relix.solver;
}

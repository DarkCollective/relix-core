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
 * The published descriptor of {@code com.darkcollective.relix:relix-solver-ojalgo}.
 *
 * <p>Inside this build the provider requires the solver SPI's own module; outside it that
 * module is part of {@code com.darkcollective.relix}, which exports the SPI package to this
 * module alone. So the descriptor a client's module path reads is this one, compiled
 * against the published engine rather than against the build's modules.
 */
module com.darkcollective.relix.solver.ojalgo {
    requires com.darkcollective.relix;
    requires ojalgo;

    provides com.darkcollective.relix.solver.MathProgrammingSolver
            with com.darkcollective.relix.solver.ojalgo.OjAlgoSolver;
}

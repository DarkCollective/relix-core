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
 * The mathematical-programming SPI: a {@link com.darkcollective.relix.solver.LinearProgram}
 * the engine builds, a {@link com.darkcollective.relix.solver.MathProgrammingSolver} a
 * provider implements, and the
 * {@link com.darkcollective.relix.solver.SolverCatalog} that discovers one.
 *
 * <p>The {@code OPTIMIZE} operator and {@code COVER EXACT} are mixed-integer programs.
 * The engine's part is turning rows and expressions into coefficients — which rows are
 * candidates, what a NULL coefficient means, how a constraint operator maps onto a
 * bound — and none of that is arithmetic a solver should re-decide. What crosses this
 * seam is therefore numbers only: no row, no expression, no schema.
 */
package com.darkcollective.relix.solver;

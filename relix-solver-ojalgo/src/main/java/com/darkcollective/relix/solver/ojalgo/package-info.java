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
 * The shipped mathematical-programming provider: ojAlgo's branch-and-bound behind
 * {@link com.darkcollective.relix.solver.MathProgrammingSolver}.
 *
 * <p>One class, and every ojAlgo type in the build is named inside it. It compiles
 * against the SPI and the solver library and nothing else — the same footing a
 * third-party provider (a CBC or HiGHS binding, say) would have.
 */
package com.darkcollective.relix.solver.ojalgo;

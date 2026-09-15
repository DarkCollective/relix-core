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
package com.darkcollective.relix.ast;

import java.util.Objects;

/**
 * Specifies the continuous LP (linear-programming) allocation mode for the
 * {@code OPTIMIZE ALLOCATE} operator.
 *
 * <p>Each candidate row is assigned a continuous decision variable in the closed
 * interval {@code [lo, hi]}.  The solver chooses a value for each row that
 * optimises the objective while satisfying the constraints; the chosen value is
 * emitted in a new output column named {@code columnName} (output schema =
 * input schema + 1 column).
 *
 * <p>Contrast with the default binary MIP mode, where each row's variable is
 * {@code {0, 1}} (in or out) and the output schema is unchanged (chosen rows
 * are emitted verbatim).
 *
 * @param lo         the lower bound of the per-row allocation variable; must be
 *                   finite and ≤ {@code hi}
 * @param hi         the upper bound of the per-row allocation variable; must be
 *                   finite and ≥ {@code lo}
 * @param columnName the name to give the new allocation column in the output
 *                   schema; must not be blank
 */
public record AllocationSpec(double lo, double hi, String columnName) {

    public AllocationSpec {
        if (!Double.isFinite(lo)) {
            throw new IllegalArgumentException("AllocationSpec lo must be finite, got " + lo);
        }
        if (!Double.isFinite(hi)) {
            throw new IllegalArgumentException("AllocationSpec hi must be finite, got " + hi);
        }
        if (lo > hi) {
            throw new IllegalArgumentException(
                    "AllocationSpec lo must be ≤ hi, got lo=" + lo + " hi=" + hi);
        }
        Objects.requireNonNull(columnName, "columnName");
        if (columnName.isBlank()) {
            throw new IllegalArgumentException("AllocationSpec columnName must not be blank");
        }
    }
}

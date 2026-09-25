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
package com.darkcollective.relix.plan.internal;

/**
 * Thrown at plan time when a query needs a mathematical-programming solver and none
 * is installed.
 *
 * <p>Two operators state a mixed-integer program rather than computing an answer
 * directly: {@code OPTIMIZE}, and {@code COVER EXACT}. Both are useless without a
 * solver, and both would otherwise discover that deep inside execution, after the
 * inputs had already been read. Reporting it while the plan is being built keeps the
 * failure where the cause is — the runtime's configuration, not the data.
 *
 * <p>This is a user-facing planning error, not a bug: the fix is to put a solver
 * provider on the module path. Every other operator works without one, which is why
 * the check is per-operator rather than a startup requirement.
 */
public final class NoSolverInstalledException extends RuntimeException {

    /**
     * @param operator the operator that needs a solver, named as the user wrote it
     */
    public NoSolverInstalledException(String operator) {
        super(operator + " needs a mathematical-programming solver and none is installed; "
                + "add one to the module path (the shipped provider is relix-solver-ojalgo)");
    }
}

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
package com.darkcollective.relix.symbol;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of a {@link com.darkcollective.relix.symbol.table.SymbolTable#register}
 * call.
 *
 * <p>Three outcomes are possible:
 * <ol>
 *   <li><b>Success</b> — {@link #registered()} is {@code true} and
 *       {@link #errors()} is empty.  The symbol is in the table.</li>
 *   <li><b>Registered with warnings</b> — {@link #registered()} is
 *       {@code true} and {@link #errors()} contains one or more
 *       {@link SymbolError.Kind#SHADOW_WARNING} entries.  The symbol replaced
 *       an existing definition; callers may want to surface the warnings.</li>
 *   <li><b>Rejected</b> — {@link #registered()} is {@code false} and
 *       {@link #errors()} contains one or more
 *       {@link SymbolError.Kind#SHADOW_FORBIDDEN} entries.  The table is
 *       unchanged.</li>
 * </ol>
 *
 * @param symbol     the symbol that was attempted; never null
 * @param registered {@code true} if the symbol was actually inserted or
 *                   replaced in the table
 * @param errors     diagnostics collected during registration; never null,
 *                   may be empty
 */
public record RegistrationResult(Symbol symbol, boolean registered, List<SymbolError> errors) {

    public RegistrationResult {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(errors, "errors");
        errors = List.copyOf(errors);
    }

    /**
     * Returns {@code true} if registration succeeded with no errors or warnings.
     *
     * @return {@code true} when registered and no errors
     */
    public boolean isSuccess() {
        return registered && errors.isEmpty();
    }

    /**
     * Returns {@code true} if registration succeeded but produced warnings.
     *
     * @return {@code true} when registered with at least one warning
     */
    public boolean hasWarnings() {
        return registered && !errors.isEmpty();
    }

    /**
     * Returns {@code true} if the symbol was rejected and not added to the table.
     *
     * @return {@code true} when not registered
     */
    public boolean isRejected() {
        return !registered;
    }
}

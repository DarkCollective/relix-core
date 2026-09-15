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

import java.util.Objects;

/**
 * A non-fatal diagnostic produced during symbol registration.
 *
 * <p>Errors are collected rather than thrown so that all conflicts in a
 * registration batch can be reported at once.  Callers inspect
 * {@link RegistrationResult#errors()} and decide how to surface them.
 *
 * <p>Two kinds exist:
 * <ul>
 *   <li>{@link Kind#SHADOW_FORBIDDEN} — registration was rejected because the
 *       existing symbol's {@link ShadowPolicy} is {@link ShadowPolicy#FORBIDDEN}.
 *       The table is unchanged.</li>
 *   <li>{@link Kind#SHADOW_WARNING} — registration succeeded but the new symbol
 *       replaced an existing one whose policy is
 *       {@link ShadowPolicy#WARN_AND_PERMIT}.  The table now holds the new
 *       symbol.</li>
 * </ul>
 *
 * @param kind       the error classification
 * @param namespace  the namespace in which the conflict occurred
 * @param symbolName the name (declared form) of the symbol being registered
 * @param message    a human-readable description of the problem
 */
public record SymbolError(Kind kind, String namespace, String symbolName, String message) {

    /**
     * Classification of symbol registration errors.
     */
    public enum Kind {

        /**
         * The existing symbol forbids shadowing; the incoming symbol was
         * rejected and the table was not modified.
         */
        SHADOW_FORBIDDEN,

        /**
         * The existing symbol permits shadowing with a warning; the incoming
         * symbol has been registered in place of the old one.
         */
        SHADOW_WARNING
    }

    public SymbolError {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(symbolName, "symbolName");
        Objects.requireNonNull(message, "message");
    }
}

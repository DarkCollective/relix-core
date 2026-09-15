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

/**
 * Governs what happens when a new symbol is registered under a name that is
 * already occupied in the same namespace.
 *
 * <p>The policy is checked on the <em>existing</em> symbol: if the symbol already
 * in the table declares itself {@code FORBIDDEN}, no new symbol may replace it.
 * If it declares {@code PERMITTED} or {@code WARN_AND_PERMIT}, the incoming
 * symbol takes its place.
 *
 * <p>Typical defaults:
 * <ul>
 *   <li>{@link Provenance#BUILTIN} symbols → {@code FORBIDDEN}</li>
 *   <li>{@link Provenance#USER} symbols → {@code PERMITTED}</li>
 * </ul>
 */
public enum ShadowPolicy {

    /**
     * The symbol may not be replaced.  Attempting to register a new symbol
     * under the same key produces a {@link SymbolError} of kind
     * {@link SymbolError.Kind#SHADOW_FORBIDDEN} and leaves the original
     * symbol unchanged.
     */
    FORBIDDEN,

    /**
     * The symbol may be silently replaced by any incoming definition.
     * No error or warning is produced.
     */
    PERMITTED,

    /**
     * The symbol may be replaced, but a {@link SymbolError} of kind
     * {@link SymbolError.Kind#SHADOW_WARNING} is added to the registration
     * result to alert callers that shadowing occurred.
     */
    WARN_AND_PERMIT
}

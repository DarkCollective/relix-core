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
 * Optimizer hint flags that may be attached to a function symbol.
 *
 * <p>Each property makes a contract between the function author and the query
 * optimizer.  Declaring a property that the function does not actually satisfy
 * may lead to incorrect query results, so these should be set conservatively.
 *
 * <p>Properties are stored as a {@link java.util.Set} on the function symbol,
 * so multiple flags may be combined freely.
 *
 * <p>Example — a pure, commutative addition built-in:
 * <pre>{@code
 * ScalarFunctionSymbol.builder("add")
 *     .property(FunctionProperty.COMMUTATIVE)
 *     .property(FunctionProperty.PURE)
 *     .build();
 * }</pre>
 */
public enum FunctionProperty {

    /**
     * {@code f(a, b) = f(b, a)} — the function produces the same result
     * regardless of argument order.  The optimizer may reorder arguments or
     * merge symmetric join conditions.
     */
    COMMUTATIVE,

    /**
     * The same argument values always produce the same return value.
     * The optimizer may cache or deduplicate calls with identical arguments.
     */
    DETERMINISTIC,

    /**
     * {@code f(f(x)) = f(x)} — applying the function a second time yields
     * the same result as applying it once.  The optimizer may eliminate
     * redundant nested calls.
     */
    IDEMPOTENT,

    /**
     * The function has no observable side-effects and reads no hidden state
     * (i.e. it is a pure mathematical function).  Implies
     * {@link #DETERMINISTIC}.  The optimizer may freely reorder, eliminate,
     * or hoist calls out of loops.
     */
    PURE,

    /**
     * The function reads ambient state, but that state does not change while a query
     * runs — so the call has <em>one</em> value for the whole run, and a different one
     * next time.  {@code NOW()} is the case: every row of a query sees the same instant,
     * and the query after it sees a later one.
     *
     * <p>This is the middle of three, and the reason two were not enough.
     * {@link #DETERMINISTIC} says a call may be handed to a backend, which a clock call
     * may not be — the backend would answer from its own clock rather than the one this
     * session was given.  Declaring nothing says the value may differ per row, which is
     * true of a random source and not of a clock.  Between them sits a call the engine
     * can evaluate <em>once</em> and send the resulting value, which is how a query over
     * "now" reaches the database without the database deciding when "now" is.
     *
     * <p>It is a claim about the run, so it obliges the engine as much as the function:
     * a stable call is evaluated against a clock that is read once and pinned for the
     * whole run, which is what makes "one value for the whole run" true rather than
     * merely intended.
     *
     * <p>Declaring it alongside {@link #DETERMINISTIC} says nothing new — a
     * deterministic call is already constant for any run — and a function that is
     * neither is per-row volatile, which is the safe default and needs no property.
     */
    STABLE
}

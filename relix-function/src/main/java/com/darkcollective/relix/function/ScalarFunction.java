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
package com.darkcollective.relix.function;

import com.darkcollective.relix.symbol.ScalarType;

import java.util.List;

/**
 * One scalar function a library supplies: its {@linkplain FunctionSignature form} plus
 * the processing behind it.
 *
 * <p>The hierarchy is sealed over exactly two ways of receiving arguments, so the
 * engine dispatches on a switch the compiler can check for completeness:
 *
 * <ul>
 *   <li>{@link StrictScalarFunction} — every argument is evaluated before the call.
 *       This is what a function wants unless it has a reason not to.</li>
 *   <li>{@link LazyScalarFunction} — arguments arrive as deferred
 *       {@link Argument}s, and one that is never asked for is never evaluated. This
 *       is what makes a conditional usable as a guard: the branch not taken may be
 *       an expression that would fail on this row.</li>
 * </ul>
 *
 * <p>Both are {@code non-sealed}, so implementing either is open to any library; it
 * is the choice <em>between</em> them that is closed.
 *
 * <p>The engine checks the declared {@link Arity} before invoking, so an
 * implementation may read the arguments its signature allows without counting them
 * first.
 */
public sealed interface ScalarFunction permits StrictScalarFunction, LazyScalarFunction {

    /**
     * The form of this function — name, parameters, arity, return type, properties.
     *
     * @return the signature; never {@code null}
     */
    FunctionSignature signature();

    /**
     * @return the canonical spelling of this function's name
     */
    default String name() {
        return signature().name();
    }

    /**
     * The result type of a call with these argument types.
     *
     * <p>Defaults to the declared return type, which is the answer whenever the
     * result type is fixed. Override it for a function whose result follows its
     * arguments — a conditional returning whichever branch it selects, a
     * {@code Coalesce} over a homogeneous list — and the engine's schema inference
     * uses the narrower type.
     *
     * @param argumentTypes the argument types at the call site, in order
     * @return the result type of that call
     */
    default ScalarType returnTypeFor(List<ScalarType> argumentTypes) {
        return signature().returnTypeFor(argumentTypes);
    }

    /**
     * How this function is spelled in a backend that could evaluate it itself.
     *
     * <p>Defaults to {@link PushdownSpelling#NONE} — no backend spelling, so the
     * engine evaluates every call. Supplying one is how a library's own function
     * folds into a pushed query alongside the built-ins.
     *
     * @return the pushdown spelling; never {@code null}
     */
    default PushdownSpelling pushdown() {
        return PushdownSpelling.NONE;
    }
}

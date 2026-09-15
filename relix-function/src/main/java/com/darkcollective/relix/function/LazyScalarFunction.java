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

import com.darkcollective.relix.value.Value;

import java.util.List;

/**
 * A scalar function that decides which of its arguments to evaluate — a special form.
 *
 * <p>Arguments arrive as deferred {@link Argument}s; one whose {@link Argument#value()}
 * is never called is never evaluated. That is what separates a conditional from an
 * ordinary three-argument function: in
 *
 * <pre>{@code IIf(IsNumeric(raw), CDbl(raw), 0)}</pre>
 *
 * the {@code CDbl(raw)} branch would fail on a row where {@code raw} is not numeric,
 * and on those rows it is not evaluated at all. A short-circuiting {@code Coalesce}
 * stops at its first non-NULL argument for the same reason.
 *
 * <p>Choose this only when skipping an argument is part of the function's meaning. A
 * function that always reads every argument gains nothing and pays a call through the
 * deferred wrapper for each one.
 */
public non-sealed interface LazyScalarFunction extends ScalarFunction {

    /**
     * Computes the result for one row, evaluating only the arguments it needs.
     *
     * @param context   the ambient state this call may read
     * @param arguments the deferred arguments, in call order; never {@code null} and of
     *                  a length the declared arity accepts
     * @return the result value; never {@code null}
     */
    Value invoke(FunctionContext context, List<Argument> arguments);
}

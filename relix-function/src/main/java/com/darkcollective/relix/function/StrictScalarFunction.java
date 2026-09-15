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
 * A scalar function whose arguments are all evaluated before it is called — the
 * ordinary kind.
 *
 * <p>The list handed over is a length the declared {@link Arity} allows, since the
 * engine checks that first, and its entries are never {@code null}: an absent value is
 * {@link com.darkcollective.relix.value.NullValue}, and most implementations begin by
 * returning NULL for a NULL input.
 *
 * <p>Throwing is how a function reports a value it cannot work with — a non-numeric
 * string handed to {@code Sqr}. The engine turns the message into a query-level
 * diagnostic, so it should read as a statement about the data rather than about Java.
 */
public non-sealed interface StrictScalarFunction extends ScalarFunction {

    /**
     * Computes the result for one row.
     *
     * @param context   the ambient state this call may read
     * @param arguments the evaluated arguments, in call order; never {@code null} and
     *                  of a length the declared arity accepts
     * @return the result value; never {@code null}
     */
    Value invoke(FunctionContext context, List<Value> arguments);
}

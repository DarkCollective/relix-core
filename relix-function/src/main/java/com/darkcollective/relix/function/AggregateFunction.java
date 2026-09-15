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

import com.darkcollective.relix.symbol.Type;

import java.util.List;

/**
 * One aggregate a library supplies: its {@linkplain AggregateSignature form} plus the
 * {@link Accumulator} that reduces a group.
 *
 * <p>Unlike {@link ScalarFunction} this is not sealed — an aggregate has one way of
 * receiving its input, a row at a time, so there is nothing for the engine to dispatch
 * over.
 */
public interface AggregateFunction {

    /**
     * The form of this aggregate — name, parameters, arity, return type, NULL handling.
     *
     * @return the signature; never {@code null}
     */
    AggregateSignature signature();

    /**
     * Creates the running state for one group.
     *
     * <p>Called once per group, and the returned accumulator is used by one group only,
     * so it may hold whatever mutable state the reduction needs.
     *
     * @param context the ambient state this reduction may read
     * @return a fresh accumulator; never {@code null}
     */
    Accumulator accumulator(FunctionContext context);

    /**
     * @return the canonical spelling of this aggregate's name
     */
    default String name() {
        return signature().name();
    }

    /**
     * The result type of a call with these argument types.
     *
     * <p>Defaults to the declared return type. Override it for an aggregate whose
     * result follows its input — one that yields a companion column's value has the
     * type of that column, whatever it is.
     *
     * @param argumentTypes the argument types at the call site, in order
     * @return the result type of that call
     */
    default Type returnTypeFor(List<Type> argumentTypes) {
        return signature().returnTypeFor(argumentTypes);
    }

    /**
     * How this aggregate is spelled in a backend that could compute it itself.
     *
     * <p>Defaults to {@link PushdownSpelling#NONE}, so the engine reduces every group
     * itself. A backend spelling is what lets a grouping be pushed into the source.
     *
     * @return the pushdown spelling; never {@code null}
     */
    default PushdownSpelling pushdown() {
        return PushdownSpelling.NONE;
    }
}

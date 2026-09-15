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

import java.util.Objects;
import java.util.function.Supplier;

/**
 * One argument of a {@link LazyScalarFunction} call, not yet evaluated.
 *
 * <p>Calling {@link #value()} evaluates the expression behind it; not calling it means
 * the expression never runs. The engine hands over arguments that remember their
 * result, so asking twice costs nothing and the function may read an argument wherever
 * it is convenient rather than caching it by hand.
 *
 * <p>Evaluation can fail — that is the point of deferring it — and the failure surfaces
 * from {@code value()} at the moment the function asks.
 */
@FunctionalInterface
public interface Argument {

    /**
     * Evaluates this argument, or returns the value already computed for it.
     *
     * @return the argument's value; never {@code null}
     */
    Value value();

    /**
     * An argument that is already a value.
     *
     * @param value the value to hand over
     * @return an argument yielding {@code value}
     */
    static Argument of(Value value) {
        Objects.requireNonNull(value, "value");
        return () -> value;
    }

    /**
     * An argument that runs {@code supplier} at most once, however often it is read.
     *
     * <p>This is the shape the engine supplies, and the reason a lazy function can read
     * the same argument in two branches without evaluating the expression twice.
     *
     * @param supplier the deferred evaluation; called at most once
     * @return a remembering argument over {@code supplier}
     */
    static Argument memoizing(Supplier<Value> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return new Argument() {
            private Value evaluated;

            @Override
            public Value value() {
                if (evaluated == null) {
                    evaluated = Objects.requireNonNull(
                            supplier.get(), "an argument evaluated to null");
                }
                return evaluated;
            }
        };
    }
}

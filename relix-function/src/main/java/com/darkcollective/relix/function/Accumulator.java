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
 * The running state of one aggregate over one group.
 *
 * <p>A fresh accumulator is created per group, fed one call to {@link #accumulate} per
 * row, and asked once for its {@link #finish() result}. It is not required to be
 * thread-safe: the engine gives each group its own.
 *
 * <p>Arguments arrive as a list because an aggregate is not always over a single value
 * — a "row with the maximum" reduction takes the ranking expression and the one to
 * yield from it, and a correlation takes two. Passing a list rather than a row keeps
 * the engine's representation of a row out of the SPI entirely.
 *
 * <p>NULL handling is declared, not implemented here: when the signature says the
 * aggregate {@linkplain AggregateSignature#skipsNulls() skips NULLs}, the engine drops
 * those rows before {@code accumulate} sees them.
 */
public interface Accumulator {

    /**
     * Folds one row's argument values into the running state.
     *
     * @param arguments the row's argument values, in call order; never {@code null}
     */
    void accumulate(List<Value> arguments);

    /**
     * The aggregate's value for the group, read once after the last row.
     *
     * <p>An accumulator that saw no rows returns the empty-group answer — NULL for the
     * SQL reducers, zero for a count, an empty array for a gathering aggregate.
     *
     * @return the group's result; never {@code null}
     */
    Value finish();

    /**
     * Folds another accumulator's state into this one, when the aggregate can be
     * computed in parts and combined.
     *
     * <p>The default declines, which is what an aggregate that cannot be split says.
     * Implementing it is what allows a group to be accumulated in more than one place
     * and the parts joined.
     *
     * @param other an accumulator of the same aggregate, whose state is folded into this
     * @throws UnsupportedOperationException if this aggregate cannot be combined from parts
     */
    default void merge(Accumulator other) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " cannot be merged");
    }
}

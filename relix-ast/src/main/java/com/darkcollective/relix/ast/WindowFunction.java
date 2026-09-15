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
package com.darkcollective.relix.ast;

import java.util.Objects;
import java.util.Optional;

/**
 * The computation a {@link WindowNode} performs per row.  A single
 * sealed hierarchy covers all three window families; the permit encodes what
 * differs while {@link WindowNode} holds what they share (partition keys, sort
 * specs, frame, output column).
 *
 * <ul>
 *   <li>{@link AggregateWindow} — sliding / cumulative aggregates
 *       ({@code SUM}, {@code AVG}, {@code COUNT}, {@code MIN}, {@code MAX});
 *       introduced by the {@code ROLLING} keyword (slice 2).</li>
 *   <li>{@link RankingWindow} — ranking functions
 *       ({@code ROW_NUMBER}, {@code RANK}, …); {@code WINDOW} keyword (slice 3).</li>
 *   <li>{@link OffsetWindow} — offset functions
 *       ({@code LAG}, {@code LEAD}, {@code FIRST_VALUE}, {@code LAST_VALUE});
 *       {@code WINDOW} keyword (slice 4).</li>
 * </ul>
 *
 * <p>All three forms are permitted by the one sealed interface, so every switch
 * over a window function is exhaustive and a consumer cannot silently ignore a
 * form it does not handle.
 */
public sealed interface WindowFunction
        permits WindowFunction.AggregateWindow, WindowFunction.RankingWindow,
                WindowFunction.OffsetWindow {

    /**
     * A sliding / cumulative aggregate over an {@link Operand} argument — the same
     * {@link AggregateOperator} + {@link Operand} pair used by {@code γ}.  Only
     * {@code SUM}, {@code AVG}, {@code COUNT}, {@code MIN}, {@code MAX} are valid in
     * a window (validated by {@code RelAlgebraValidator}); {@code COLLECT},
     * {@code ARGMAX}, {@code ARGMIN} are not.
     *
     * @param operator the aggregate operator; never null
     * @param argument the expression the operator reads; never null
     */
    record AggregateWindow(AggregateOperator operator, Operand argument) implements WindowFunction {
        public AggregateWindow {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(argument, "argument");
        }
    }

    /**
     * A ranking function (slice 3).  {@link #ntileCount()} carries the bucket count
     * for {@code NTILE} and is empty for every other ranking function.
     *
     * @param function   the ranking function; never null
     * @param ntileCount the {@code NTILE} bucket count (constant expression);
     *                   present only for {@link RankingFunction#NTILE}
     */
    record RankingWindow(RankingFunction function, Optional<Operand> ntileCount)
            implements WindowFunction {
        public RankingWindow {
            Objects.requireNonNull(function, "function");
            Objects.requireNonNull(ntileCount, "ntileCount");
        }
    }

    /**
     * An offset function (slice 4).
     *
     * @param function     the offset function; never null
     * @param expression   the value expression to read from the offset row; never null
     * @param offset       the row offset (constant expression); empty defaults to 1
     * @param defaultValue the value emitted when the offset falls outside the
     *                     partition; empty defaults to {@code NULL}
     */
    record OffsetWindow(OffsetFunction function, Operand expression,
                        Optional<Operand> offset, Optional<Operand> defaultValue)
            implements WindowFunction {
        public OffsetWindow {
            Objects.requireNonNull(function, "function");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(offset, "offset");
            Objects.requireNonNull(defaultValue, "defaultValue");
        }
    }
}

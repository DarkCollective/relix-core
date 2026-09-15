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

/**
 * A production stop folded into a monotone generator leaf by the optimizer's
 * {@code SelectionIntoGeneratorPass} ({@code GEN-001}).
 *
 * <p>An unbounded ascending generator ({@code Naturals}, {@code Primes}) produces
 * values forever, so a {@code σ} above it that only filters never terminates. When
 * the selection carries a top-level <em>upper bound</em> on the generator's
 * ascending value column ({@code n < k}, {@code n <= k}, {@code n = k}), that bound
 * is recorded here so the executor can <em>stop producing</em> (a {@code takeWhile}
 * over the ordered stream) once the threshold is passed — turning a non-terminating
 * scan into a finite one, and making the leaf {@linkplain
 * com.darkcollective.relix.ast.RelNode boundedness}-{@code BOUNDED}.
 *
 * <p>The {@code σ} that produced it always remains above the leaf as a residual
 * filter, so this bound only affects <em>how much is produced</em>, never the
 * result — the rewrite is correctness-preserving by construction. The original
 * selection predicate determines the {@link #operator()}:
 * <ul>
 *   <li>{@link ComparisonOperator#LESS} — stop while {@code column < limit} (exclusive);</li>
 *   <li>{@link ComparisonOperator#LESS_EQUAL} / {@link ComparisonOperator#EQUAL} —
 *       stop while {@code column <= limit} (inclusive; {@code =} relies on the
 *       residual σ for the exact match).</li>
 * </ul>
 *
 * @param column   the generator's ascending value column the bound applies to; must not be blank
 * @param operator the bounding comparison — one of {@code LESS}, {@code LESS_EQUAL}, {@code EQUAL}
 * @param limit    the constant upper-bound literal; must not be null
 */
public record ProduceBound(String column, ComparisonOperator operator, Operand limit) {

    public ProduceBound {
        Objects.requireNonNull(column, "column");
        if (column.isBlank()) {
            throw new IllegalArgumentException("ProduceBound column must not be blank");
        }
        Objects.requireNonNull(operator, "operator");
        if (operator != ComparisonOperator.LESS
                && operator != ComparisonOperator.LESS_EQUAL
                && operator != ComparisonOperator.EQUAL) {
            throw new IllegalArgumentException(
                    "ProduceBound operator must be an upper bound (<, <=, =), got " + operator);
        }
        Objects.requireNonNull(limit, "limit");
    }

    /** Whether the threshold itself is produced ({@code <=}/{@code =}) rather than excluded ({@code <}). */
    public boolean inclusive() {
        return operator != ComparisonOperator.LESS;
    }
}

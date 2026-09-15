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
 * A single linear constraint of an {@link OptimizeNode}:
 * {@code SUM(expr) op bound}.
 *
 * <p>{@code expr} is evaluated per candidate row to that row's coefficient; the
 * constraint bounds the sum of those coefficients over the chosen rows.  Only the
 * comparison operators {@code <=}, {@code >=}, and {@code =}
 * ({@link ComparisonOperator#LESS_EQUAL}, {@link ComparisonOperator#GREATER_EQUAL},
 * {@link ComparisonOperator#EQUAL}) are meaningful here; semantic validation
 * rejects the others.
 *
 * @param expr  the per-row coefficient expression summed over the chosen rows; never null
 * @param op    the comparison operator relating the sum to {@code bound}; never null
 * @param bound the right-hand-side constant
 */
public record OptimizeConstraint(Operand expr, ComparisonOperator op, double bound) {
    public OptimizeConstraint {
        Objects.requireNonNull(expr, "expr");
        Objects.requireNonNull(op, "op");
    }
}

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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.ast.RelNode;

import java.util.List;
import java.util.Objects;

/**
 * The immutable result of optimizing a single query's
 * {@link RelNode} tree.
 *
 * <p>Carries three things together:
 * <ol>
 *   <li>The {@link #original()} unoptimized tree as received from semantic
 *       analysis.</li>
 *   <li>The {@link #optimized()} rewritten tree; reference-equal to
 *       {@link #original()} when no rules fired.</li>
 *   <li>The ordered list of {@link TransformationRecord}s describing every
 *       change made.</li>
 * </ol>
 *
 * <p>Instances are created by {@link QueryOptimizer} and consumed by
 * {@link OptimizationReport}.
 *
 * @param queryName  name of the query or relation that was optimized;
 *                   must not be blank
 * @param original   the unoptimized source tree; must not be null
 * @param optimized  the rewritten tree; must not be null
 * @param applied    the transformations applied; must not be null;
 *                   empty when no rules fired
 */
public record OptimizationResult(
        String                   queryName,
        RelNode                  original,
        RelNode                  optimized,
        List<TransformationRecord> applied
) {
    public OptimizationResult {
        Objects.requireNonNull(queryName, "queryName");
        if (queryName.isBlank()) {
            throw new IllegalArgumentException("queryName must not be blank");
        }
        Objects.requireNonNull(original,  "original");
        Objects.requireNonNull(optimized, "optimized");
        Objects.requireNonNull(applied,   "applied");
        applied = List.copyOf(applied);
    }

    /**
     * Returns {@code true} when at least one transformation was applied to
     * this query's tree.
     *
     * @return {@code true} iff {@link #applied()} is non-empty
     */
    public boolean wasOptimized() {
        return !applied.isEmpty();
    }

    /**
     * Returns the number of transformations applied to this query.
     *
     * @return count ≥ 0; equal to {@code applied().size()}
     */
    public int transformationCount() {
        return applied.size();
    }
}

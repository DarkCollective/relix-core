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
package com.darkcollective.relix.cost;

import java.util.Optional;

/**
 * Supplies, for a <em>leaf</em> relation, the column on which it is a
 * monotone-ascending unbounded generator — the one fact the optimizer needs to
 * decide that an upper-bound selection ({@code n < k}) can be folded into the
 * producer as a termination stop ({@code GEN-001}).
 *
 * <p>It is the producer analogue of {@link DistinctnessSource} /
 * {@link BoundednessSource}: a leaf seam supplied by the runtime (which knows the
 * generator catalogue), consulted by the optimizer through the
 * {@code OptimizationContext}. A leaf that is not an ascending unbounded generator
 * returns {@link Optional#empty()} and is never bounded by this pass.
 *
 * <p>Only ascending, <em>unbounded</em> generators ({@code Naturals}, {@code Primes})
 * need report a column: a finite generator terminates on its own, and a non-monotone
 * one cannot be safely stopped by a value threshold.
 */
@FunctionalInterface
public interface MonotoneGeneratorSource {

    /** A source under which no leaf is a monotone generator (the production default off-path). */
    MonotoneGeneratorSource NONE = name -> Optional.empty();

    /**
     * Returns the ascending value column of the leaf named {@code relationName} when
     * it is a monotone-ascending unbounded generator, else {@link Optional#empty()}.
     *
     * @param relationName the leaf relation name; never null
     * @return the ascending column name, or empty when not an ascending unbounded generator
     */
    Optional<String> ascendingColumn(String relationName);
}

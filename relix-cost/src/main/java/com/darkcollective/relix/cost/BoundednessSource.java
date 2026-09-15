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

/**
 * Supplies the {@link Boundedness} of a <em>leaf</em> relation by name — the one
 * piece of boundedness that is not structural but depends on what the leaf
 * actually is.
 *
 * <p>Most leaves are {@link Boundedness#BOUNDED} — files, database tables, inline
 * and catalog relations, finite generators — and {@link #ALL_BOUNDED} is the right
 * source for a caller that knows it reads only those.  A generator source answers
 * for itself through this seam ({@code GeneratorBoundednessSource}):
 * {@code Naturals} and {@code Primes} report {@link Boundedness#UNBOUNDED}.
 */
@FunctionalInterface
public interface BoundednessSource {

    /** A source under which every leaf relation is {@link Boundedness#BOUNDED}. */
    BoundednessSource ALL_BOUNDED = name -> Boundedness.BOUNDED;

    /**
     * Returns the boundedness of the leaf relation named {@code relationName}.
     *
     * @param relationName the leaf relation name; never null
     * @return its boundedness; never null
     */
    Boundedness boundednessOf(String relationName);
}

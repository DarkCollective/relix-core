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
 * Whether a relation is guaranteed finite — a logical property propagated
 * bottom-up over a {@link com.darkcollective.relix.ast.RelNode} tree.
 *
 * <p>The three values form a lattice ordered by "how unbounded":
 * <pre>{@code  BOUNDED  <  UNKNOWN  <  UNBOUNDED }</pre>
 * <ul>
 *   <li>{@link #BOUNDED} — provably finite (files, database tables, inline and
 *       catalog relations, finite generators).</li>
 *   <li>{@link #UNKNOWN} — finiteness cannot be proven either way. The safety
 *       check does <em>not</em> reject {@code UNKNOWN} — only what is provably
 *       {@link #UNBOUNDED}.</li>
 *   <li>{@link #UNBOUNDED} — provably infinite (an unbounded generator such as
 *       {@code Naturals} or {@code Primes}, or a streaming source). No operator or
 *       default ever <em>produces</em> {@code UNBOUNDED}; it originates only at a leaf and
 *       propagates upward (the "contagious-only" invariant), so a tree whose
 *       leaves are all bounded is itself provably bounded.</li>
 * </ul>
 *
 * <p>This boundedness property is distinct from cardinality: an honestly
 * <em>unknown</em> row count (a JDBC table without statistics) is still
 * {@link #BOUNDED}, not {@code UNKNOWN}.
 */
public enum Boundedness {

    /** Provably finite. */
    BOUNDED,

    /** Finiteness cannot be proven; conservatively allowed (not rejected). */
    UNKNOWN,

    /** Provably infinite. */
    UNBOUNDED;

    /**
     * The least upper bound of this and {@code other} — the boundedness of an
     * operator combining inputs of these boundednesses. Because the lattice is a
     * total order, this is the more-unbounded of the two (so any unbounded input
     * makes the combination unbounded; the contagious-only invariant).
     *
     * @param other the other boundedness; must not be null
     * @return the more-unbounded of the two
     */
    public Boundedness lub(Boundedness other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }
}

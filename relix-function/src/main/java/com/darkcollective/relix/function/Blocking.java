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

import java.util.Collection;

/**
 * A two-argument function's promise about which pairs of values a comparison of its
 * result could accept, in a form a join can index.
 *
 * <p>A join on {@code F(left.x, right.y) >= t} indexes one input under
 * {@link #indexKeys} and, for each row of the other, looks up its {@link #probeKeys}.
 * Only the rows found that way are candidates, and every candidate is still confirmed by
 * the join's own condition, so the keys only ever <em>remove</em> pairs from
 * consideration and never decide a match.
 *
 * <h2>The contract</h2>
 * For every pair of values {@code a}, {@code b} for which {@code F(a, b)} satisfies the
 * comparison the blocking was offered for, both
 * {@code probeKeys(a) ∩ indexKeys(b)} and {@code probeKeys(b) ∩ indexKeys(a)} are
 * non-empty. The join picks which input to index, so the promise has to hold whichever
 * argument ends up probing. Keys are compared with {@link Object#equals}.
 *
 * <p>A blocking may be loose. A key shared by pairs that fail the comparison costs a call
 * to {@code F}. A missing key loses a row from the answer.
 *
 * <p>A NULL value is asked for its keys like any other. A function whose call is NULL
 * whenever an argument is NULL — so the comparison is UNKNOWN and the pair is never
 * kept — answers no keys for it. A value the blocking cannot read, NULL included, may be
 * refused with an {@link IllegalArgumentException}: its row is then compared with every
 * row of the other input, as it would be without a blocking, and the function itself
 * decides what each of those pairs means.
 */
public interface Blocking {

    /**
     * How a call's result is compared with its threshold, read with the call on the
     * left: {@code F(a, b) >= t} is {@link #AT_LEAST}.
     */
    enum Bound {
        /** {@code F(a, b) >= t}. */
        AT_LEAST,
        /** {@code F(a, b) > t}. */
        ABOVE,
        /** {@code F(a, b) <= t}. */
        AT_MOST,
        /** {@code F(a, b) < t}. */
        BELOW
    }

    /**
     * The keys a value of the indexed input is filed under.
     *
     * @param value an argument value, possibly NULL
     * @return its keys; empty when no row of the other input could match it
     * @throws IllegalArgumentException if the value is not one the function reads
     */
    Collection<?> indexKeys(Value value);

    /**
     * The keys a value of the probing input looks up.
     *
     * @param value an argument value, possibly NULL
     * @return its keys; empty when no row of the other input could match it
     * @throws IllegalArgumentException if the value is not one the function reads
     */
    Collection<?> probeKeys(Value value);
}

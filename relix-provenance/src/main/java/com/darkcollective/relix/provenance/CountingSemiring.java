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
package com.darkcollective.relix.provenance;

import java.math.BigInteger;
import java.math.BigDecimal;

/**
 * The counting semiring {@code (ℕ, +, ×, 0, 1)} — <em>bag semantics</em> (row
 * multiplicity) and, over a weighted closure, <em>path counting</em>.
 *
 * <p>{@link #plus ⊕} adds the multiplicities of alternative derivations,
 * {@link #times ⊗} multiplies the multiplicities of a join's inputs,
 * {@link #zero() 0} is {@code 0} (absent), and {@link #one() 1} is {@code 1}.
 *
 * <p>Annotations are {@link BigInteger} rather than a fixed-width integer because
 * multiplicities and path counts can grow without bound (a dense reachability
 * graph has exponentially many paths); arbitrary precision avoids silent overflow,
 * consistent with the engine's {@code BigInteger}-backed generators.
 */
public enum CountingSemiring implements Semiring<BigInteger> {

    /** The singleton instance. */
    INSTANCE;

    @Override
    public BigInteger zero() {
        return BigInteger.ZERO;
    }

    @Override
    public BigInteger one() {
        return BigInteger.ONE;
    }

    @Override
    public BigInteger plus(BigInteger a, BigInteger b) {
        return a.add(b);
    }

    @Override
    public BigInteger times(BigInteger a, BigInteger b) {
        return a.multiply(b);
    }

    /**
     * {@return the tuple's multiplicity} A numeric weight is read as a whole number of
     * occurrences, truncating any fractional part; a tuple with no numeric weight counts
     * {@link #one() once}, which is what makes an unweighted graph's annotation its
     * path count.
     */
    @Override
    public BigInteger base(BaseTuple tuple) {
        return tuple.weight().map(BigDecimal::toBigInteger).orElseGet(this::one);
    }

}

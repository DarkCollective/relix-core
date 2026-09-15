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

/**
 * The tropical (min-plus) semiring {@code (ℝ ∪ {+∞}, min, +, +∞, 0)} — the
 * <em>cheapest-derivation</em> algebra, i.e. <em>shortest path</em> when iterated
 * by a weighted transitive closure.
 *
 * <p>{@link #plus ⊕} is {@link Math#min} (among alternative derivations, keep the
 * cheapest), {@link #times ⊗} is addition (the cost of a joint derivation is the
 * sum of its parts), {@link #zero() 0} is {@code +∞} (unreachable / absent), and
 * {@link #one() 1} is {@code 0.0} (a free derivation). Annihilation holds because
 * {@code +∞ + x == +∞}, and {@code ⊗} distributes over {@code ⊕} because
 * {@code min(a, b) + c == min(a + c, b + c)}.
 *
 * <p>Weights are non-negative finite {@code double}s or {@code +∞}; {@code NaN} and
 * {@code -∞} are not valid annotations (they break the min/+ ordering).
 *
 * <h2>Exactness</h2>
 *
 * <p>{@code ⊕} is exact for every weight: {@link Math#min} chooses one of its operands
 * rather than computing a new value, so there is nothing to round and the law
 * {@code (a ⊕ b) ⊕ c == a ⊕ (b ⊕ c)} holds at any magnitude.
 *
 * <p>{@code ⊗} is {@code double} addition, and so is <strong>associative only where that
 * addition is exact</strong> — integral weights below 2^53, which covers hop counts and
 * whole-unit costs. It is not associative for weights that binary floating point cannot
 * represent exactly: with {@code a = b = 0.01} and {@code c = 0.04},
 * {@code (a ⊗ b) ⊗ c} is {@code 0.06} while {@code a ⊗ (b ⊗ c)} is
 * {@code 0.060000000000000005}. The consequence for a weighted closure is that a total
 * cost may differ in its last bits depending on the order edges were combined; the path
 * chosen by {@code ⊕} is unaffected unless two routes are within one ulp of each other.
 * Use integral weights — scaled to whole units, as money is usually held in cents — where
 * an exact total matters.
 */
public enum TropicalSemiring implements Semiring<Double> {

    /** The singleton instance. */
    INSTANCE;

    @Override
    public Double zero() {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public Double one() {
        return 0.0d;
    }

    @Override
    public Double plus(Double a, Double b) {
        return Math.min(a, b);
    }

    @Override
    public Double times(Double a, Double b) {
        return a + b;
    }
}

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
package com.darkcollective.relix.provenance.internal;

import com.darkcollective.relix.provenance.BooleanSemiring;
import com.darkcollective.relix.provenance.CountingSemiring;
import com.darkcollective.relix.provenance.NamedSemiring;
import com.darkcollective.relix.provenance.PathCostSemiring;
import com.darkcollective.relix.provenance.PolynomialSemiring;
import com.darkcollective.relix.provenance.SecurityLattice;
import com.darkcollective.relix.provenance.SemiringLibrary;
import com.darkcollective.relix.provenance.TropicalSemiring;
import java.util.List;

/**
 * The semirings this module ships: boolean existence, ℕ multiplicity, tropical
 * shortest-path, the security lattice, {@code ℕ[X]} lineage and cheapest-route.
 *
 * <p>It is an ordinary {@link SemiringLibrary} and is read through the same seam a
 * third-party one is, so the bundled six are not a privileged case that an installed
 * semiring has to be worked around. It is <em>not</em> discovered by
 * {@link java.util.ServiceLoader} — it lives in the same module as the catalog that reads
 * it and is registered directly, so the built-ins are present whatever the packaging.
 *
 * <p>It ships at {@link #priority() priority 0}, which is what lets an installed library
 * either replace one of these by declaring a higher priority or fill gaps around them by
 * declaring a lower one.
 */
public final class BuiltinSemiringLibrary implements SemiringLibrary {

    /** The single instance. */
    public static final BuiltinSemiringLibrary INSTANCE = new BuiltinSemiringLibrary();

    private static final List<NamedSemiring> SEMIRINGS = List.of(
            new NamedSemiring("boolean", List.of("set"), BooleanSemiring.INSTANCE,
                    "Existence — whether a tuple is derivable at all (set semantics)."),
            new NamedSemiring("counting", List.of("bag", "natural"), CountingSemiring.INSTANCE,
                    "Multiplicity over ℕ — how many derivations a tuple has (bag semantics, "
                            + "path count)."),
            new NamedSemiring("tropical", List.of("shortest-path"), TropicalSemiring.INSTANCE,
                    "Min-plus — the cheapest derivation's total cost (shortest path)."),
            new NamedSemiring("security", List.of("lattice"), SecurityLattice.INSTANCE,
                    "Clearance lattice — the level a tuple may be released at."),
            new NamedSemiring("lineage", List.of("polynomial", "why"), PolynomialSemiring.INSTANCE,
                    "Why-provenance over ℕ[X] — which base tuples a result was built from."),
            new NamedSemiring("cheapest-route", List.of("best-path"), PathCostSemiring.INSTANCE,
                    "Cheapest cost together with the route that achieves it."));

    private BuiltinSemiringLibrary() {
    }

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public List<NamedSemiring> semirings() {
        return SEMIRINGS;
    }
}

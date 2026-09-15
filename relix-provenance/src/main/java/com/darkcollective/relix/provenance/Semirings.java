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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a built-in {@link Semiring} by name — the lookup the surfacing layer
 * uses to turn a {@code --provenance=<name>} request into
 * a concrete semiring without the caller hard-wiring the singletons.
 *
 * <p>Names are case-insensitive. The canonical names mirror the built-ins, with a
 * couple of friendly aliases:
 * <ul>
 *   <li>{@code boolean} (alias {@code set}) → {@link BooleanSemiring} — existence /
 *       set semantics, the default;</li>
 *   <li>{@code counting} (aliases {@code bag}, {@code natural}) →
 *       {@link CountingSemiring} — multiplicity / path count;</li>
 *   <li>{@code tropical} (alias {@code shortest-path}) → {@link TropicalSemiring} —
 *       cheapest derivation / shortest path;</li>
 *   <li>{@code security} (alias {@code lattice}) → {@link SecurityLattice} —
 *       trust / access-control level;</li>
 *   <li>{@code lineage} (aliases {@code polynomial}, {@code why}) →
 *       {@link PolynomialSemiring} — full why-provenance {@code ℕ[X]};</li>
 *   <li>{@code cheapest-route} (alias {@code best-path}) →
 *       {@link PathCostSemiring} — combined cheapest cost + the route(s) achieving it
 *       (a weighted closure with a witness).</li>
 * </ul>
 *
 * <p>The lineage semiring requires per-base-tuple variable minting, so a consumer
 * that resolves it here must drive evaluation with a variable-minting lift rather
 * than the cheap {@code one()} lift (the cheap semirings need no such treatment).
 */
public final class Semirings {

    /** Canonical-and-alias name → semiring, in canonical declaration order. */
    private static final Map<String, Semiring<?>> BY_NAME = new LinkedHashMap<>();

    static {
        // Canonical names first so #names() lists them in a sensible order.
        BY_NAME.put("boolean",  BooleanSemiring.INSTANCE);
        BY_NAME.put("counting", CountingSemiring.INSTANCE);
        BY_NAME.put("tropical", TropicalSemiring.INSTANCE);
        BY_NAME.put("security", SecurityLattice.INSTANCE);
        BY_NAME.put("lineage",  PolynomialSemiring.INSTANCE);
        BY_NAME.put("cheapest-route", PathCostSemiring.INSTANCE);
        // Aliases.
        BY_NAME.put("set",           BooleanSemiring.INSTANCE);
        BY_NAME.put("bag",           CountingSemiring.INSTANCE);
        BY_NAME.put("natural",       CountingSemiring.INSTANCE);
        BY_NAME.put("shortest-path", TropicalSemiring.INSTANCE);
        BY_NAME.put("lattice",       SecurityLattice.INSTANCE);
        BY_NAME.put("polynomial",    PolynomialSemiring.INSTANCE);
        BY_NAME.put("why",           PolynomialSemiring.INSTANCE);
        BY_NAME.put("best-path",     PathCostSemiring.INSTANCE);
    }

    private Semirings() {
    }

    /**
     * Resolves a built-in semiring by (case-insensitive) name.
     *
     * @param name the semiring name or alias; may be {@code null}
     * @return the matching semiring, or empty if {@code name} is null or unknown
     */
    public static Optional<Semiring<?>> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_NAME.get(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * {@return the canonical built-in semiring names, in declaration order} Aliases
     * are omitted; this is the list to show in help and error messages.
     */
    public static java.util.List<String> names() {
        return java.util.List.of(
                "boolean", "counting", "tropical", "security", "lineage", "cheapest-route");
    }
}

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

import java.util.List;
import java.util.Optional;

/**
 * Resolves a {@link Semiring} by name — the lookup the surfacing layer uses to turn a
 * {@code --provenance=<name>} request into a concrete semiring without the caller
 * hard-wiring the singletons.
 *
 * <p>It answers from {@link SemiringCatalog#installed()}, so the names it resolves are the
 * bundled six <em>plus</em> every semiring an installed {@link SemiringLibrary} offers.
 * Names are case-insensitive, and each bundled semiring carries a friendly alias:
 * {@code set} → boolean, {@code bag}/{@code natural} → counting,
 * {@code shortest-path} → tropical, {@code lattice} → security,
 * {@code polynomial}/{@code why} → lineage, {@code best-path} → cheapest-route.
 *
 * <p>Use {@link SemiringCatalog} directly to read a semiring's description, to assemble a
 * catalog of your own, or to re-run discovery.
 */
public final class Semirings {

    private Semirings() {
    }

    /**
     * Resolves an installed semiring by (case-insensitive) name or alias.
     *
     * @param name the semiring name or alias; may be {@code null}
     * @return the matching semiring, or empty if {@code name} is null or unknown
     */
    public static Optional<Semiring<?>> byName(String name) {
        return SemiringCatalog.installed().byName(name);
    }

    /**
     * {@return the canonical names of every installed semiring} Aliases are omitted; this
     * is the list to show in help and error messages.
     */
    public static List<String> names() {
        return SemiringCatalog.installed().names();
    }
}

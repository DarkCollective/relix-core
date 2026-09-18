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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A {@link Semiring} together with the names it answers to and a line describing it —
 * one entry in the registry a {@code --provenance} request is resolved against.
 *
 * <p>Naming is kept out of {@link Semiring} deliberately. A semiring is an algebraic
 * structure and its identity is its operations; what it is <em>called</em> is registry
 * metadata, and the same structure can reasonably be offered twice under different names
 * (min-plus is "tropical" to one caller and "shortest-path" to another). Putting the name
 * here rather than on the algebra is what lets a library do that without declaring two
 * classes that compute the same thing.
 *
 * @param name        the canonical name, shown in listings and help; lower-cased on
 *                    construction, and must not be blank
 * @param aliases     other names resolving to the same semiring; may be empty, and each
 *                    is lower-cased on construction
 * @param semiring    the semiring itself
 * @param description one line saying what the annotation means — what a user reads when
 *                    choosing between installed semirings; must not be blank
 */
public record NamedSemiring(String name, List<String> aliases, Semiring<?> semiring,
                            String description) {

    /**
     * Canonicalises the entry: lower-cases every name, defensively copies the alias list,
     * and rejects a blank name or description.
     *
     * @throws NullPointerException     if any argument, or any alias, is null
     * @throws IllegalArgumentException if the name, an alias, or the description is blank
     */
    public NamedSemiring {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(aliases, "aliases");
        Objects.requireNonNull(semiring, "semiring");
        Objects.requireNonNull(description, "description");
        if (name.isBlank()) {
            throw new IllegalArgumentException("semiring name must not be blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("semiring '" + name + "' needs a description");
        }
        String canonical = name.toLowerCase(Locale.ROOT);
        List<String> lowered = new ArrayList<>(aliases.size());
        for (String alias : aliases) {
            Objects.requireNonNull(alias, "alias");
            if (alias.isBlank()) {
                throw new IllegalArgumentException("semiring '" + canonical + "' has a blank alias");
            }
            lowered.add(alias.toLowerCase(Locale.ROOT));
        }
        name = canonical;
        aliases = List.copyOf(lowered);
    }

    /**
     * An entry with no aliases.
     *
     * @param name        the canonical name
     * @param semiring    the semiring
     * @param description one line saying what the annotation means
     * @return the entry
     */
    public static NamedSemiring of(String name, Semiring<?> semiring, String description) {
        return new NamedSemiring(name, List.of(), semiring, description);
    }

    /**
     * {@return every name this entry answers to — its canonical name followed by its
     * aliases}
     */
    public List<String> allNames() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(name), aliases.stream())
                .toList();
    }
}

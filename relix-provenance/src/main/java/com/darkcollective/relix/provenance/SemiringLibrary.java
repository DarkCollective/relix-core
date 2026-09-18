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

/**
 * A provider of {@linkplain Semiring semirings} — the extension point through which an
 * annotation algebra the engine has never heard of becomes something a query can ask for
 * by name.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}, so a library is
 * declared both in a {@code module-info} {@code provides} clause and in a
 * {@code META-INF/services} file: a module path reads the first and a class path the
 * second, and a provider missing either is invisible in one of the two packagings without
 * failing anything a compiler can see.
 *
 * <h2>What a semiring has to supply</h2>
 * Four operations and, if the annotation depends on the data, {@link Semiring#base}.
 * Everything the engine does with an annotation is expressed in those five, which is what
 * makes an installed semiring the equal of a built-in one: weighted transitive closure,
 * the positive operators and the provenance surfacing path all go through exactly this
 * interface and recognise nothing by name.
 *
 * @see NamedSemiring
 * @see SemiringCatalog
 */
public interface SemiringLibrary {

    /**
     * A short identifier for this library, used in diagnostics — including the one that
     * reports two libraries offering the same semiring name.
     *
     * @return the library's name; never {@code null} or blank
     */
    String name();

    /**
     * Which library wins when two offer a semiring of the same name: the higher priority.
     *
     * <p>The bundled library ships at {@code 0}, so a library declaring a higher priority
     * replaces built-in semirings it re-declares, and one declaring a lower priority fills
     * gaps without disturbing them. Either way the engine reports the clash rather than
     * resolving it silently.
     *
     * <p>Ties are broken by discovery order, which is not something to rely on: two
     * libraries that both want to own a name should differ in priority.
     *
     * @return this library's priority; higher wins
     */
    default int priority() {
        return 0;
    }

    /**
     * The semirings this library offers.
     *
     * @return the semirings, each with the names it answers to; never {@code null}
     */
    List<NamedSemiring> semirings();
}

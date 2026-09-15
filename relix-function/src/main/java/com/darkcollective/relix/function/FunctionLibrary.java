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

import java.util.List;
import java.util.Optional;

/**
 * A collection of functions offered to the engine — the unit that is installed,
 * discovered and versioned.
 *
 * <p>A library is found with {@link java.util.ServiceLoader}, so an implementation needs
 * a public no-argument constructor and must be declared as a provider both in its
 * {@code module-info} and in {@code META-INF/services}: a module path reads the first
 * and a class path reads the second, and a library that declares only one of them works
 * in exactly one of the two ways the engine is run.
 *
 * <p>What a library offers is expressed by which methods it overrides. Both default to
 * offering nothing, so a library of scalar functions never mentions aggregates.
 *
 * <pre>{@code
 * public final class GeoFunctions implements FunctionLibrary {
 *     public String name() { return "acme-geo"; }
 *     public List<ScalarFunction> scalarFunctions() { return List.of(new Haversine()); }
 * }
 * }</pre>
 */
public interface FunctionLibrary {

    /**
     * A short identifier for this library, used in diagnostics — including the one that
     * reports two libraries offering the same function name.
     *
     * @return the library's name; never {@code null} or blank
     */
    String name();

    /**
     * Which library wins when two offer a function of the same name: the higher priority.
     *
     * <p>The default library ships at {@code 0}, so a library declaring a higher
     * priority replaces built-in functions it re-declares, and one declaring a lower
     * priority fills gaps without disturbing them. Either way the engine reports the
     * clash rather than resolving it silently.
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
     * The scalar functions this library offers.
     *
     * @return the scalar functions; empty by default
     */
    default List<ScalarFunction> scalarFunctions() {
        return List.of();
    }

    /**
     * The aggregates this library offers.
     *
     * @return the aggregates; empty by default
     */
    default List<AggregateFunction> aggregateFunctions() {
        return List.of();
    }

    /**
     * This library's documentation for one of its functions, as Markdown.
     *
     * <p>A library serves its own pages rather than the engine reading them out of it:
     * a module's resources are its own — under JPMS they are not even visible from
     * outside without an explicit {@code opens} — and a library that keeps its
     * documentation somewhere other than a resource file (generating it, or fetching it)
     * can still answer.
     *
     * <p>The key is the one a {@linkplain FunctionSignature#docKey() signature} declares.
     * A library is asked only for keys, and should answer only for keys, that its own
     * functions declare: it is being asked for <em>its</em> page, not for whatever
     * happens to sit at that path.
     *
     * @param docKey the documentation key of one of this library's functions
     * @return the Markdown page, or empty when this library has no page under that key
     */
    default Optional<String> documentation(String docKey) {
        return Optional.empty();
    }
}

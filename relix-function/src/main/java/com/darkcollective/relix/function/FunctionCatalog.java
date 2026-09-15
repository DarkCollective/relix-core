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

import com.darkcollective.relix.symbol.ScalarType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Every function available to a run, indexed by name.
 *
 * <p>The catalogue is built once — from the installed {@linkplain FunctionLibrary
 * libraries} — and then only read. Building it once matters: two catalogues assembled
 * separately can disagree about what a name means, and the disagreement shows up as
 * analysis accepting a call that evaluation cannot make. Discover once, and pass the
 * result along with the rest of the analysis.
 *
 * <pre>{@code
 * FunctionCatalog catalog = FunctionCatalog.discover();
 * Optional<ScalarFunction> upper = catalog.scalar("UCase");
 * }</pre>
 *
 * <p>Lookup is case-insensitive throughout: {@code UCase}, {@code ucase} and
 * {@code UCASE} are one function. When two libraries offer the same name the higher
 * {@linkplain FunctionLibrary#priority() priority} wins, and the loss is logged rather
 * than passed over — a replaced built-in should be a decision, not a surprise.
 *
 * <p>Holding a function here does not put it in a symbol table. The engine registers a
 * function on first reference, so a script's symbol table — and the IR dump printed
 * from it — lists the functions that script actually calls rather than the whole
 * library. This catalogue is the thing that answers "is there such a function, and what
 * is its form"; registration stays a consequence of a script mentioning the name.
 */
public final class FunctionCatalog {

    private static final System.Logger LOG =
            System.getLogger(FunctionCatalog.class.getName());

    private final Map<String, ScalarFunction> scalars;
    private final Map<String, AggregateFunction> aggregates;
    private final List<FunctionLibrary> libraries;

    private FunctionCatalog(List<FunctionLibrary> libraries) {
        Map<String, ScalarFunction> scalarIndex = new LinkedHashMap<>();
        Map<String, AggregateFunction> aggregateIndex = new LinkedHashMap<>();
        Map<String, FunctionLibrary> owners = new LinkedHashMap<>();

        for (FunctionLibrary library : ordered(libraries)) {
            for (ScalarFunction fn : requireList(library, library.scalarFunctions(), "scalarFunctions")) {
                String key = fn.signature().canonicalName();
                if (scalarIndex.putIfAbsent(key, fn) == null) {
                    owners.put("scalar:" + key, library);
                } else {
                    LOG.log(System.Logger.Level.WARNING,
                            clashMessage("scalar function", fn.signature().name(),
                                    owners.get("scalar:" + key), library));
                }
            }
            for (AggregateFunction fn : requireList(library, library.aggregateFunctions(), "aggregateFunctions")) {
                String key = fn.signature().canonicalName();
                if (aggregateIndex.putIfAbsent(key, fn) == null) {
                    owners.put("aggregate:" + key, library);
                } else {
                    LOG.log(System.Logger.Level.WARNING,
                            clashMessage("aggregate", fn.signature().name(),
                                    owners.get("aggregate:" + key), library));
                }
            }
        }

        // Insertion-ordered, not Map.copyOf: the offering order is part of what
        // scalars()/aggregates()/names() promise, and an immutable Map does not keep it.
        this.scalars = Collections.unmodifiableMap(scalarIndex);
        this.aggregates = Collections.unmodifiableMap(aggregateIndex);
        // Kept in claiming order, so a documentation lookup is answered by the same
        // library that would have won the name.
        this.libraries = List.copyOf(ordered(libraries));
    }

    /**
     * Builds a catalogue from every {@link FunctionLibrary} installed on the module
     * path or the class path.
     *
     * <p>Discovery is declared here rather than by each consumer, because
     * {@link ServiceLoader} resolves services against the module that calls it: doing it
     * in one place means no consumer needs a service declaration of its own, and there
     * is one catalogue rather than several.
     *
     * @return a catalogue over the discovered libraries; empty when none is installed
     */
    public static FunctionCatalog discover() {
        return new FunctionCatalog(loadInstalled());
    }

    /**
     * Every installed {@link FunctionLibrary}, skipping any that cannot be instantiated.
     *
     * <p>A library that fails to load fails <em>as itself</em>: the remaining libraries
     * are still indexed and the engine still runs, which is what the SPI's claim — that
     * anything a shipped library can do a third party can do — requires of the failure
     * path too. A third-party library compiled against a dependency the host does not
     * have is the likeliest way to reach this, and it must not be able to stop the
     * engine from starting.
     *
     * <p>Only {@code next()} is guarded, deliberately. {@link ServiceLoader}'s three
     * lookup iterators all park a provider's failure in a {@code nextError} field during
     * {@code hasNext()} and throw it from {@code next()}, so this catch sees every one of
     * them. Guarding {@code hasNext()} as well would be worse than redundant: {@code
     * next()} is what clears that field, so a loop that swallowed a {@code hasNext()}
     * failure and asked again would spin.
     */
    private static List<FunctionLibrary> loadInstalled() {
        List<FunctionLibrary> found = new ArrayList<>();
        ServiceLoader<FunctionLibrary> services = ServiceLoader.load(FunctionLibrary.class);
        for (var it = services.iterator(); it.hasNext(); ) {
            try {
                found.add(it.next());
            } catch (ServiceConfigurationError unloadable) {
                LOG.log(System.Logger.Level.WARNING,
                        "skipping a FunctionLibrary that could not be loaded; the "
                                + "functions it declares will be unknown to this run",
                        unloadable);
            }
        }
        return found;
    }

    /**
     * Builds a catalogue over the discovered libraries <em>plus</em> the given ones.
     *
     * <p>For a program that installs a library of its own without publishing it as a
     * service. The SPI's claim is that anything the shipped library can do a third party
     * can do; requiring a {@code META-INF/services} entry to exercise that inside one's
     * own program would be a gap in the claim rather than a feature of it.
     *
     * <p>Discovery stays here, for the reason {@link #discover()} gives — a caller
     * enumerating services itself would need a {@code uses} declaration of its own, and
     * two independently-populated catalogues can disagree about what a name means.
     *
     * <p>Clashes are resolved by {@link FunctionLibrary#priority()} exactly as they are
     * among discovered libraries, so an extra library must declare a higher priority than
     * the bundled {@code 0} to replace a built-in rather than merely to add to it.
     *
     * @param extra libraries to index alongside the discovered ones; must not be null
     * @return a catalogue over both
     */
    public static FunctionCatalog discoverWith(Collection<FunctionLibrary> extra) {
        Objects.requireNonNull(extra, "extra");
        List<FunctionLibrary> all = new ArrayList<>(extra);
        all.addAll(loadInstalled());
        return new FunctionCatalog(all);
    }

    /**
     * Builds a catalogue over exactly these libraries, bypassing discovery.
     *
     * @param libraries the libraries to index
     * @return a catalogue over {@code libraries}
     */
    public static FunctionCatalog of(FunctionLibrary... libraries) {
        return of(List.of(libraries));
    }

    /**
     * Builds a catalogue over exactly these libraries, bypassing discovery.
     *
     * @param libraries the libraries to index
     * @return a catalogue over {@code libraries}
     */
    public static FunctionCatalog of(Collection<FunctionLibrary> libraries) {
        return new FunctionCatalog(List.copyOf(
                Objects.requireNonNull(libraries, "libraries")));
    }

    /**
     * The catalogue holding nothing — useful where a caller must supply one and the
     * functions are irrelevant.
     *
     * @return an empty catalogue
     */
    public static FunctionCatalog empty() {
        return new FunctionCatalog(List.of());
    }

    /**
     * Looks up a scalar function by name, case-insensitively.
     *
     * @param name the function name as written at the call site
     * @return the function, or empty when no library offers that name
     */
    public Optional<ScalarFunction> scalar(String name) {
        return Optional.ofNullable(scalars.get(key(name)));
    }

    /**
     * Looks up an aggregate by name, case-insensitively.
     *
     * @param name the aggregate name as written at the call site
     * @return the aggregate, or empty when no library offers that name
     */
    public Optional<AggregateFunction> aggregate(String name) {
        return Optional.ofNullable(aggregates.get(key(name)));
    }

    /**
     * Resolves a scalar call against this catalogue.
     *
     * <p>A name identifies one function, so the argument types are accepted and not
     * consulted; the parameter is here because resolution by argument type is a
     * property of the lookup rather than of a caller, and a caller written against this
     * form keeps working if the rule ever sharpens. To ask what a call returns, take the
     * resolved function's {@link ScalarFunction#returnTypeFor(List)}.
     *
     * @param name          the function name as written at the call site
     * @param argumentTypes the argument types at that call site, in order
     * @return the function, or empty when no library offers that name
     */
    public Optional<ScalarFunction> resolve(String name, List<ScalarType> argumentTypes) {
        Objects.requireNonNull(argumentTypes, "argumentTypes");
        return scalar(name);
    }

    /**
     * @return every scalar function in the catalogue, in the order the libraries offered
     *         them
     */
    /**
     * The libraries this catalogue was built over, in claiming order.
     *
     * <p>Which implementations are actually installed is otherwise unanswerable from
     * inside a running program — discovery is a {@code ServiceLoader} scan, and a
     * missing provider fails nothing a compiler can see. This is what lets a process
     * report its own inventory.
     *
     * @return the libraries, highest priority first; never null
     */
    public List<FunctionLibrary> libraries() {
        return libraries;
    }

    public Collection<ScalarFunction> scalars() {
        return List.copyOf(scalars.values());
    }

    /**
     * @return every aggregate in the catalogue, in the order the libraries offered them
     */
    public Collection<AggregateFunction> aggregates() {
        return List.copyOf(aggregates.values());
    }

    /**
     * The canonical spelling of every name in the catalogue — scalar functions first,
     * then aggregates. These are display forms; lookup is case-insensitive.
     *
     * @return the declared names
     */
    public Set<String> names() {
        Set<String> all = new LinkedHashSet<>();
        scalars.values().forEach(fn -> all.add(fn.signature().name()));
        aggregates.values().forEach(fn -> all.add(fn.signature().name()));
        return Collections.unmodifiableSet(all);
    }

    /**
     * The documentation page a library holds under {@code docKey}, as Markdown.
     *
     * <p>Asked of the libraries in the order they get to claim a name, so the library
     * whose function a caller resolved is the one that answers for it. The engine never
     * reads a library's resources itself — see
     * {@link FunctionLibrary#documentation(String)} for why that is the seam.
     *
     * @param docKey the key a {@link FunctionSignature#docKey()} declared
     * @return the Markdown page, or empty when no installed library has one
     */
    public Optional<String> documentation(String docKey) {
        Objects.requireNonNull(docKey, "docKey");
        return libraries.stream()
                .map(library -> library.documentation(docKey))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * @return {@code true} when no library offered a single function — which, outside a
     *         deliberately {@link #empty()} catalogue, means no library was found
     */
    public boolean isEmpty() {
        return scalars.isEmpty() && aggregates.isEmpty();
    }

    /**
     * Libraries in the order they get to claim a name: highest priority first, and among
     * equals, discovery order — {@link List#sort} is stable, so a tie leaves the two
     * where discovery put them.
     */
    private static List<FunctionLibrary> ordered(List<FunctionLibrary> libraries) {
        List<FunctionLibrary> sorted = new ArrayList<>(libraries);
        sorted.forEach(library -> Objects.requireNonNull(library, "library"));
        sorted.sort(Comparator.comparingInt(FunctionLibrary::priority).reversed());
        return sorted;
    }

    /**
     * The wording of a name clash. Kept as a method rather than inlined so the message
     * is asserted directly: a warning nobody can read is a warning nobody acts on.
     */
    static String clashMessage(String kind, String name,
                               FunctionLibrary winner, FunctionLibrary loser) {
        return "Two libraries offer the " + kind + " '" + name + "': keeping the one from '"
                + winner.name() + "' (priority " + winner.priority() + "), ignoring '"
                + loser.name() + "' (priority " + loser.priority() + ")";
    }

    private static <T> List<T> requireList(FunctionLibrary library, List<T> functions,
                                           String method) {
        if (functions == null) {
            throw new NullPointerException(
                    "Function library '" + library.name() + "' returned null from " + method + "()");
        }
        return functions;
    }

    private static String key(String name) {
        return Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
    }
}

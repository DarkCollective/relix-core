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
package com.darkcollective.relix.solver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * The installed {@link MathProgrammingSolver}s, discovered with
 * {@link ServiceLoader}.
 *
 * <p>Discovery lives here, in the module that declares the SPI, for the reason it does
 * for every other provider seam: {@code ServiceLoader.load} binds against the calling
 * module, so a consumer needs no {@code uses} clause of its own — and asking twice can
 * produce two answers. {@link #installed()} therefore discovers <em>once</em> per JVM
 * and hands the same catalog to the planner (which checks a solver exists before the
 * query runs) and to the executor (which uses it).
 *
 * <p>An empty catalog is a supported state, not an error: a build that installs no
 * solver still parses, analyses, optimises and executes everything except the two
 * operators that need one, and those report it before any rows are read. Discovery
 * reaches that state by <em>any</em> route, an installed provider that cannot be
 * instantiated included — see {@link #discover()}.
 */
public final class SolverCatalog {

    private static final System.Logger LOG = System.getLogger(SolverCatalog.class.getName());

    /** Discovered once: two independently-loaded catalogs could disagree. */
    private static final class Installed {
        private static final SolverCatalog CATALOG = discover();
    }

    private final List<MathProgrammingSolver> solvers;

    private SolverCatalog(List<MathProgrammingSolver> solvers) {
        this.solvers = List.copyOf(solvers);
    }

    /**
     * The catalog of solvers installed in this runtime, discovered on first use.
     *
     * @return the installed catalog, possibly empty
     */
    public static SolverCatalog installed() {
        return Installed.CATALOG;
    }

    /**
     * Runs {@link ServiceLoader} discovery afresh. Prefer {@link #installed()};
     * this exists for a host that changes its class loader between runs.
     *
     * <p>A provider that cannot be instantiated is skipped with a warning rather than
     * failing the scan. That is what makes the empty catalog this class documents as
     * supported actually reachable: the shipped provider is bundled with its solver
     * library, so an installed-but-unloadable solver is what a host that removes that
     * library has, and aborting discovery there would take down every query rather than
     * the two operators that need a solver.
     *
     * @return a catalog over the currently discoverable solvers, possibly empty
     */
    public static SolverCatalog discover() {
        List<MathProgrammingSolver> found = new ArrayList<>();
        ServiceLoader<MathProgrammingSolver> services =
                ServiceLoader.load(MathProgrammingSolver.class);
        // Only next() is guarded, deliberately. ServiceLoader's three lookup iterators
        // all park a provider's failure in a `nextError` field during hasNext() and
        // throw it from next(), so this catch sees every one of them. Guarding hasNext()
        // as well would be worse than redundant: next() is what clears that field, so a
        // loop that swallowed a hasNext() failure and asked again would spin.
        for (var it = services.iterator(); it.hasNext(); ) {
            try {
                found.add(it.next());
            } catch (ServiceConfigurationError unloadable) {
                LOG.log(System.Logger.Level.WARNING,
                        "skipping a MathProgrammingSolver that could not be loaded; "
                                + "OPTIMIZE and COVER EXACT will report that no solver "
                                + "is installed", unloadable);
            }
        }
        return of(found);
    }

    /**
     * A catalog over an explicit list — the seam a test or an embedder uses to install
     * its own solver without touching the module path.
     *
     * @param solvers the solvers to offer, highest priority winning
     * @return a catalog over them
     */
    public static SolverCatalog of(List<MathProgrammingSolver> solvers) {
        List<MathProgrammingSolver> sorted = new ArrayList<>(
                Objects.requireNonNull(solvers, "solvers"));
        sorted.sort(Comparator.comparingInt(MathProgrammingSolver::priority).reversed());
        return new SolverCatalog(sorted);
    }

    /**
     * A catalog with no solver in it — what a runtime that installs no provider has.
     *
     * @return the empty catalog
     */
    public static SolverCatalog empty() {
        return new SolverCatalog(List.of());
    }

    /**
     * The solver to use: the highest-priority installed one.
     *
     * @return the chosen solver, or empty if none is installed
     */
    public Optional<MathProgrammingSolver> solver() {
        return solvers.isEmpty() ? Optional.empty() : Optional.of(solvers.getFirst());
    }

    /**
     * @return whether no solver is installed
     */
    public boolean isEmpty() {
        return solvers.isEmpty();
    }

    /**
     * Every installed solver, highest priority first.
     *
     * @return the installed solvers
     */
    public List<MathProgrammingSolver> solvers() {
        return solvers;
    }
}

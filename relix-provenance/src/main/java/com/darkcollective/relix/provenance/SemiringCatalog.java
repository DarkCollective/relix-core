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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Which semirings are installed — the bundled six plus every one a discovered
 * {@link SemiringLibrary} offers, resolved by name.
 *
 * <p>Discovery happens once. Two independently-populated catalogs can disagree about what
 * a name means, and a name is what a {@code --provenance} request carries, so
 * {@link #installed()} memoises a single scan and everything else reads it.
 *
 * <h2>Clashes</h2>
 * Libraries are consulted in descending {@link SemiringLibrary#priority() priority}, and
 * the first to claim a name keeps it. A loss is reported through
 * {@link System.Logger} rather than being resolved silently — a semiring that turned out
 * not to be the one installed is otherwise indistinguishable from one that is.
 */
public final class SemiringCatalog {

    private static final System.Logger LOG = System.getLogger(SemiringCatalog.class.getName());

    /** Holder idiom: the scan runs on first use of {@link #installed()}, not on class load. */
    private static final class Installed {
        private static final SemiringCatalog CATALOG = discover();
    }

    /** Canonical-and-alias name → entry, in claiming order. */
    private final Map<String, NamedSemiring> byName;
    private final List<NamedSemiring> entries;

    private SemiringCatalog(List<SemiringLibrary> libraries) {
        List<SemiringLibrary> ordered = new ArrayList<>(libraries);
        // A stable sort, so equal priorities keep discovery order rather than being
        // reshuffled — which would make a clash between them report differently per run.
        ordered.sort(Comparator.comparingInt(SemiringLibrary::priority).reversed());

        Map<String, NamedSemiring> resolved = new LinkedHashMap<>();
        Map<String, String> owner = new LinkedHashMap<>();
        List<NamedSemiring> listed = new ArrayList<>();
        for (SemiringLibrary library : ordered) {
            for (NamedSemiring entry : library.semirings()) {
                boolean claimed = false;
                for (String name : entry.allNames()) {
                    String held = owner.get(name);
                    if (held != null) {
                        LOG.log(System.Logger.Level.WARNING,
                                "semiring '" + name + "' offered by library '" + library.name()
                                        + "' is already claimed by '" + held + "'; ignoring");
                        continue;
                    }
                    owner.put(name, library.name());
                    resolved.put(name, entry);
                    claimed = true;
                }
                if (claimed) {
                    listed.add(entry);
                }
            }
        }
        this.byName = Map.copyOf(resolved);
        this.entries = List.copyOf(listed);
    }

    /**
     * {@return the catalog of installed semirings} Discovery runs once, on first call.
     */
    public static SemiringCatalog installed() {
        return Installed.CATALOG;
    }

    /**
     * Runs {@link ServiceLoader} discovery afresh, over the bundled library plus every
     * installed one. Prefer {@link #installed()}; this exists for a host that changes its
     * class loader between runs.
     *
     * <p>A provider that fails to load is logged and skipped rather than failing the scan,
     * so one broken library on the path does not take the built-in semirings with it.
     *
     * @return a freshly discovered catalog
     */
    public static SemiringCatalog discover() {
        List<SemiringLibrary> libraries = new ArrayList<>();
        libraries.add(BuiltinSemiringLibrary.INSTANCE);
        // Only next() is guarded: ServiceLoader's iterator throws on the provider that
        // could not be instantiated, and skipping it must not abandon the ones after it.
        var iterator = ServiceLoader.load(SemiringLibrary.class).iterator();
        while (iterator.hasNext()) {
            try {
                libraries.add(iterator.next());
            } catch (ServiceConfigurationError e) {
                LOG.log(System.Logger.Level.WARNING, "skipping unloadable semiring library", e);
            }
        }
        return new SemiringCatalog(libraries);
    }

    /**
     * A catalog of exactly the given libraries — the bundled six are <em>not</em> added.
     * For a test or an embedder assembling its own set.
     *
     * @param libraries the libraries to read; must not be null
     * @return the catalog
     */
    public static SemiringCatalog of(List<SemiringLibrary> libraries) {
        return new SemiringCatalog(List.copyOf(Objects.requireNonNull(libraries, "libraries")));
    }

    /**
     * A catalog holding the bundled semirings and the given libraries, which are consulted
     * ahead of the bundled ones at equal priority.
     *
     * @param extra the additional libraries; must not be null
     * @return the catalog
     */
    public static SemiringCatalog withBuiltins(List<SemiringLibrary> extra) {
        Objects.requireNonNull(extra, "extra");
        List<SemiringLibrary> all = new ArrayList<>(extra);
        all.add(BuiltinSemiringLibrary.INSTANCE);
        return new SemiringCatalog(all);
    }

    /** {@return an empty catalog, holding no semirings at all} */
    public static SemiringCatalog empty() {
        return new SemiringCatalog(List.of());
    }

    /**
     * Resolves a semiring by (case-insensitive) name or alias.
     *
     * @param name the name; may be {@code null}
     * @return the matching semiring, or empty if {@code name} is null or unknown
     */
    public Optional<Semiring<?>> byName(String name) {
        return entry(name).map(NamedSemiring::semiring);
    }

    /**
     * Resolves a registry entry by (case-insensitive) name or alias — the semiring
     * together with what it is called and what it means.
     *
     * @param name the name; may be {@code null}
     * @return the matching entry, or empty if {@code name} is null or unknown
     */
    public Optional<NamedSemiring> entry(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * {@return every installed semiring, in claiming order} Each appears once, under its
     * canonical name; aliases resolve through {@link #byName} but are not listed.
     */
    public List<NamedSemiring> entries() {
        return entries;
    }

    /**
     * {@return the canonical names of every installed semiring, in claiming order} This is
     * the list to show in help and error messages.
     */
    public List<String> names() {
        return entries.stream().map(NamedSemiring::name).toList();
    }
}

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
package com.darkcollective.relix.semantic;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * What is installed in this process, for the parts of it the engine cannot see.
 *
 * <p>Provider discovery is a {@code ServiceLoader} scan, so <em>which implementations are
 * actually installed</em> is not a question a running program can otherwise answer — and
 * it is the first one any support conversation asks. A missing provider fails nothing a
 * compiler or a unit test can see; the packaged artifacts are smoke-tested for exactly
 * that reason, and this gives the same answer at runtime, from inside the process.
 *
 * <p>It is a seam because <strong>visibility decides who can answer</strong>. The engine
 * knows its own version and the function libraries it resolves against, because it holds
 * them. It cannot know which connectors, solvers or JDBC drivers are installed: those
 * live above it, and a JDBC driver is behind a {@code java.sql} dependency no engine
 * module is allowed to have. So whoever assembled the process contributes the rest, which
 * is the same division every other catalog seam here is drawn on.
 *
 * <p>The extent reaches a query as {@code relix.version}, because introspection has one
 * authority: the {@code relix.*} relations <em>are</em> the introspection API, and a Java
 * accessor beside them would be a second one to drift from.
 */
@FunctionalInterface
public interface ComponentInventory {

    /**
     * One installed thing.
     *
     * @param name    what it calls itself — a library's name, a solver's, a connector's
     *                type token, a driver's class
     * @param kind    what sort of thing it is: {@code engine}, {@code facade},
     *                {@code function-library}, {@code connector}, {@code solver},
     *                {@code driver}
     * @param version its version, or {@code unknown} where the runtime cannot say — a
     *                provider on the class path often carries no version at all, and
     *                naming it is the answer that matters
     */
    record Component(String name, String kind, String version) {

        /** The version reported when nothing in the runtime knows one. */
        public static final String UNKNOWN = "unknown";

        public Component {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(version, "version");
        }

        /**
         * The component a class belongs to, versioned as well as the runtime allows.
         *
         * <p>Tries the module descriptor first and the package's implementation version
         * second, because the two are populated by different packagings — a modular
         * image has the first, an ordinary jar the second, and a plain class path
         * neither. Reporting {@link #UNKNOWN} is the honest third case.
         *
         * @param name  the component's name
         * @param kind  the component's kind
         * @param owner a class belonging to it
         * @return the component
         */
        public static Component of(String name, String kind, Class<?> owner) {
            Objects.requireNonNull(owner, "owner");
            String version = owner.getModule().getDescriptor() == null ? null
                    : owner.getModule().getDescriptor().rawVersion().orElse(null);
            if (version == null) {
                version = owner.getPackage() == null ? null
                        : owner.getPackage().getImplementationVersion();
            }
            return new Component(name, kind, version == null ? UNKNOWN : version);
        }
    }

    /** Kind tokens, so a producer and a reader spell them the same way. */
    String ENGINE = "engine";
    /** @see #ENGINE */
    String FACADE = "facade";
    /** @see #ENGINE */
    String FUNCTION_LIBRARY = "function-library";
    /** @see #ENGINE */
    String CONNECTOR = "connector";
    /** @see #ENGINE */
    String SOLVER = "solver";
    /** @see #ENGINE */
    String DRIVER = "driver";

    /**
     * {@return the components this host can see, in whatever order it reports them}
     */
    List<Component> components();

    /** An inventory that knows of nothing beyond what the engine itself reports. */
    ComponentInventory NONE = List::of;

    /**
     * The engine's own version, as the build stamped it.
     *
     * <p>Read from a resource this module owns rather than from the module descriptor,
     * because it has to be right in every packaging — including a plain class path,
     * where a descriptor is not there to ask.
     *
     * @return the version, or {@code unknown} if the resource is missing
     */
    static String engineVersion() {
        try (InputStream in = ComponentInventory.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return Component.UNKNOWN;
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", Component.UNKNOWN);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

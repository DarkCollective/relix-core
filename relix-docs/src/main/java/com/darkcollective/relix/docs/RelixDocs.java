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
package com.darkcollective.relix.docs;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Relix language reference, for a program that wants to show it.
 *
 * <p>A tool can put the page for what its user typed in front of them with nothing but
 * this artifact installed: {@link #referencePages()} lists every page with the words it
 * is found by, and {@link #referencePage(String)} returns its markdown. The engine never
 * reads the pages, so an application that does not show them leaves this artifact out.
 *
 * <p>A function's page is not here. It belongs to the library that offers the function,
 * and the engine's function catalogue serves it, so a library installed later is
 * documented the same way the built-in one is.
 *
 * @since 1.0
 */
public final class RelixDocs {

    private RelixDocs() {
    }

    /**
     * Every page of the language reference: operators, joins, set operations,
     * aggregates, predicates, literals, statements, and the guide pages.
     *
     * <p>Each entry says what the page is about and the words it can be looked up by; the
     * markdown is {@link #referencePage(String)} of its path.
     *
     * @return the pages, in the reference's own order; never null
     * @since 1.0
     */
    public static List<ReferencePage> referencePages() {
        return ReferenceIndex.ALL;
    }

    /**
     * The markdown of one reference page.
     *
     * @param path the page's path, as {@link ReferencePage#path()} gives it, such as
     *             {@code operators/select.md}; must not be null
     * @return the page, or empty if the reference has none at that path
     * @since 1.0
     */
    public static Optional<String> referencePage(String path) {
        Objects.requireNonNull(path, "path");
        return ReferenceIndex.read(path);
    }
}

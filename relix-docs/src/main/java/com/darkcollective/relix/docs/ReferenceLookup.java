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

import com.darkcollective.relix.function.FunctionCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The language reference and the installed functions' pages, as one index a tool looks
 * a word up in.
 *
 * <p>Every program that shows the reference needs the same thing: what the user typed —
 * a glyph, a keyword, a name — to find a page, whether the page is about the language or
 * about a function some library offers. The language pages are {@link
 * RelixDocs#referencePages()}. A function's page belongs to its library, which serves it
 * through {@link FunctionCatalog#documentation(String)}, so a function installed later is
 * found the same way a built-in one is, and the index cannot list a function that does
 * not exist.
 *
 * <pre>{@code
 * ReferenceLookup docs = ReferenceLookup.of(FunctionCatalog.discover());
 * docs.lookup("σ");           // the Selection page, as are "select" and "selection"
 * docs.lookup("fix");         // the FIX operator: a keyword keeps a name it shares
 * docs.function("fix");       // the Fix function, asked for as a function
 * }</pre>
 *
 * <h2>Which page a word finds</h2>
 * <p>A word finds the first page that claims it, and pages claim words in this order:
 * the language pages, then the caller's own pages, then the functions. So a language
 * keyword keeps a name it shares with a function — {@code fix} is the recursion operator
 * — and the function stays reachable through {@link #function(String)}, which searches
 * functions only. Words are matched ignoring case and surrounding space.
 *
 * <h2>Function pages</h2>
 * <p>Each documented function is a {@link ReferencePage} of category
 * {@value #FUNCTION_CATEGORY}: its path is the signature's {@code docKey}, its title,
 * symbol and key are the function's name, and its summary is what the page says of
 * itself in its {@code # Name: X (…)} heading, or the name when it has none. A function
 * with no {@code docKey}, or whose library serves no page under it, is not indexed.
 *
 * <p>An index is immutable and safe to share between threads. Markdown is read when it
 * is asked for, not when the index is built.
 *
 * @since 1.0
 */
public final class ReferenceLookup {

    /** The category every function page is listed under. */
    public static final String FUNCTION_CATEGORY = "function";

    /** {@code # Name: Len (string length)} — a page's own one-line summary. */
    private static final Pattern TITLE =
            Pattern.compile("^#\\s*Name:\\s*(\\S+)\\s*\\(([^)]*)\\)", Pattern.MULTILINE);

    /** Where a page's markdown is read from. */
    private enum Source { LANGUAGE, EXTRA, FUNCTION }

    private record Entry(ReferencePage page, Source source) {
    }

    private final FunctionCatalog functions;
    /** Reads a caller's page by path; null when the caller gave no pages. */
    private final Function<String, Optional<String>> extraPages;

    /** Normalised word → the entry that claimed it first. */
    private final Map<String, Entry> byKey = new HashMap<>();

    /** Every entry, language pages first, then the caller's, then functions. */
    private final List<Entry> entries = new ArrayList<>();

    /** Normalised function name → its entry, whatever the shared index gave the name to. */
    private final Map<String, ReferencePage> byFunction = new LinkedHashMap<>();

    /** Category → its function pages, in the order the libraries offered them. */
    private final Map<String, List<ReferencePage>> functionsByCategory;

    private ReferenceLookup(FunctionCatalog functions, List<ReferencePage> extra,
                            Function<String, Optional<String>> extraPages) {
        this.functions = Objects.requireNonNull(functions, "functions");
        this.extraPages = extraPages;
        for (ReferencePage page : RelixDocs.referencePages()) {
            register(new Entry(page, Source.LANGUAGE));
        }
        for (ReferencePage page : List.copyOf(extra)) {
            register(new Entry(page, Source.EXTRA));
        }
        Map<String, List<ReferencePage>> categories = new LinkedHashMap<>();
        functions.scalars().forEach(fn -> addFunction(categories, fn.signature().name(),
                fn.signature().category(), fn.signature().docKey()));
        functions.aggregates().forEach(fn -> addFunction(categories, fn.signature().name(),
                fn.signature().category(), fn.signature().docKey()));
        Map<String, List<ReferencePage>> frozen = new LinkedHashMap<>();
        categories.forEach((category, pages) -> frozen.put(category, List.copyOf(pages)));
        this.functionsByCategory = Collections.unmodifiableMap(frozen);
    }

    /**
     * An index of the language reference and of the functions {@code functions} documents.
     *
     * @param functions the function catalogue whose pages are indexed — usually
     *                  {@link FunctionCatalog#discover()}; must not be null
     * @return the index
     * @since 1.0
     */
    public static ReferenceLookup of(FunctionCatalog functions) {
        // No caller's pages, so no reader: an EXTRA entry is never made to ask it.
        return new ReferenceLookup(functions, List.of(), null);
    }

    /**
     * An index of the language reference, the caller's own pages, and the functions
     * {@code functions} documents.
     *
     * <p>A tool adds a page about itself this way, found by its keys as a language page
     * is. A caller's page cannot take a word from a language page, and a function cannot
     * take one from a caller's page.
     *
     * @param functions the function catalogue whose pages are indexed; must not be null
     * @param extra     the caller's pages, in the order they claim words; must not be
     *                  null
     * @param markdown  reads one of {@code extra}'s pages, given its
     *                  {@link ReferencePage#path() path}; empty when it has none. Must
     *                  not be null
     * @return the index
     * @since 1.0
     */
    public static ReferenceLookup of(FunctionCatalog functions, List<ReferencePage> extra,
                                     Function<String, Optional<String>> markdown) {
        Objects.requireNonNull(extra, "extra");
        Objects.requireNonNull(markdown, "markdown");
        return new ReferenceLookup(functions, extra, markdown);
    }

    /**
     * The markdown of the page a word finds.
     *
     * @param key a glyph, keyword or name, such as {@code σ}, {@code select} or
     *            {@code Len}; must not be null
     * @return the page, or empty when no page is found by {@code key}
     * @since 1.0
     */
    public Optional<String> lookup(String key) {
        return entry(key).flatMap(this::page);
    }

    /**
     * The page a word finds, for a hint such as "see {@code relix doc σ}" that names a page
     * without showing it.
     *
     * @param key a glyph, keyword or name; must not be null
     * @return the page, or empty when no page is found by {@code key}
     * @since 1.0
     */
    public Optional<ReferencePage> entry(String key) {
        return Optional.ofNullable(byKey.get(normalise(key))).map(Entry::page);
    }

    /**
     * A function's page, found by the function's name among functions only — so it
     * answers even for a function whose name a language keyword has.
     *
     * @param name the function's name, in any case; must not be null
     * @return its page, or empty when no installed library documents a function of that
     *         name
     * @since 1.0
     */
    public Optional<ReferencePage> function(String name) {
        return Optional.ofNullable(byFunction.get(normalise(name)));
    }

    /**
     * The documented functions, grouped by the category their signatures declare
     * ({@code string}, {@code math}, {@code datetime}, …).
     *
     * @return category → its function pages, categories and functions in the order the
     *         libraries offered them; empty when no function is documented
     * @since 1.0
     */
    public Map<String, List<ReferencePage>> functionsByCategory() {
        return functionsByCategory;
    }

    /**
     * Every page of one category, in the index's order.
     *
     * @param category a {@link ReferencePage#category()}, such as {@code operator}, or
     *                 {@value #FUNCTION_CATEGORY}; must not be null
     * @return its pages; empty for a category no page has
     * @since 1.0
     */
    public List<ReferencePage> inCategory(String category) {
        Objects.requireNonNull(category, "category");
        return entries.stream()
                .map(Entry::page)
                .filter(page -> page.category().equals(category))
                .toList();
    }

    /**
     * The markdown of a page, read from whichever source holds it: this artifact for a
     * language page, the caller for one of its own, the function's library for a
     * function's.
     *
     * @param page a page of this index; must not be null
     * @return the markdown, or empty when the page is not in this index or its source has
     *         nothing at its path
     * @since 1.0
     */
    public Optional<String> page(ReferencePage page) {
        Objects.requireNonNull(page, "page");
        return entries.stream()
                .filter(entry -> entry.page().equals(page))
                .findFirst()
                .flatMap(this::markdown);
    }

    private Optional<String> markdown(Entry entry) {
        String path = entry.page().path();
        return switch (entry.source()) {
            case LANGUAGE -> RelixDocs.referencePage(path);
            case EXTRA -> extraPages.apply(path);
            case FUNCTION -> functions.documentation(path);
        };
    }

    /**
     * Indexes one entry under each of its keys that no earlier entry claimed, so the
     * order entries are registered in is the precedence.
     */
    private void register(Entry entry) {
        entries.add(entry);
        for (String key : entry.page().keys()) {
            byKey.putIfAbsent(normalise(key), entry);
        }
    }

    private void addFunction(Map<String, List<ReferencePage>> categories, String name,
                             String category, Optional<String> docKey) {
        if (docKey.isEmpty()) {
            return;
        }
        Optional<String> markdown = functions.documentation(docKey.get());
        if (markdown.isEmpty()) {
            return;
        }
        ReferencePage page = new ReferencePage(docKey.get(), FUNCTION_CATEGORY, name, name,
                summaryOf(markdown.get(), name), List.of(name.toLowerCase(Locale.ROOT)));
        byFunction.putIfAbsent(normalise(name), page);
        categories.computeIfAbsent(category, unused -> new ArrayList<>()).add(page);
        register(new Entry(page, Source.FUNCTION));
    }

    /**
     * The one-line summary a page gives of itself in {@code # Name: X (…)}, starting with
     * a capital; the function's name when the page has no such heading, since an empty
     * summary would read as a fault in the tool showing it.
     */
    private static String summaryOf(String markdown, String name) {
        Matcher matcher = TITLE.matcher(markdown);
        if (!matcher.find() || matcher.group(2).isBlank()) {
            return name;
        }
        String summary = matcher.group(2).strip();
        return Character.toUpperCase(summary.charAt(0)) + summary.substring(1);
    }

    private static String normalise(String key) {
        return Objects.requireNonNull(key, "key").strip().toLowerCase(Locale.ROOT);
    }
}

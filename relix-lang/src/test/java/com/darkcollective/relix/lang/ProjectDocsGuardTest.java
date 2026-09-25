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
package com.darkcollective.relix.lang;

import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the project docs against the failure mode that
 * {@link ReferenceExampleParseTest} cannot catch: not a snippet that stopped
 * parsing, but a doc that stopped being <em>complete</em> — a module added
 * without a module-map row, an operator added without a page.
 *
 * <p>The two doc families rot differently. {@code docs/reference} has been
 * parse-checked for a while and its examples have held up; {@code .claude/*.md}
 * had no guard at all and drifted — it accumulated {@code //} line comments,
 * which Relix does not have, and its module map was missing three modules.
 * Prose cannot be verified, but anything <em>enumerable from code</em> can be,
 * and that covers the omissions.
 *
 * <p>The {@code docs/reference} guards here cover a third rot mode: a page that is
 * present and correct but <em>invisible</em>. {@code advanced/repl.md} was written in
 * ordinary Markdown rather than the project's {@code # Label:} section format, so it
 * rendered as an empty entry in the PDF, lost its headings in {@code :doc}, and
 * yielded no {@code extract_manual.py} training pairs — three silent failures from one
 * non-conforming file, with nothing to catch any of them.
 *
 * <p>Lives in {@code relix-lang} beside the other doc guard: it is the lowest
 * module that can see both the AST enums and (via the repo root) the docs. The
 * family's third member, {@code WorkedExampleOutputTest}, sits in
 * {@code relix-console} instead — checking that a printed result is still what
 * the engine produces needs a module that can execute a plan.
 */
@DisplayName("Project docs stay complete against the code")
final class ProjectDocsGuardTest {

    /**
     * The internal map, which covers everything this build contains.
     *
     * <p>Skipped where {@code .claude} is absent: the public engine tree carries the
     * architecture document below and not this one, and a guard that fails for the
     * material it was deliberately not given is a guard that gets deleted.
     */
    @Test
    @DisplayName("every Gradle module has a CLAUDE.md module-map row, and vice versa")
    void moduleMapCoversEveryModule() {
        Path claude = repoRoot().resolve(".claude/CLAUDE.md");
        assumeTrue(Files.isRegularFile(claude), "no .claude/CLAUDE.md in this tree");

        Set<String> declared = declaredModules();
        Set<String> documented = moduleRowsIn(claude);

        assertThat(declared).as("modules were read from settings.gradle").isNotEmpty();

        Set<String> undocumented = new LinkedHashSet<>(declared);
        undocumented.removeAll(documented);
        assertThat(undocumented)
                .as("modules in settings.gradle with no row in CLAUDE.md's Module Map — "
                        + "add one describing what the module owns")
                .isEmpty();

        Set<String> stale = new LinkedHashSet<>(documented);
        stale.removeAll(declared);
        assertThat(stale)
                .as("CLAUDE.md module-map rows naming a module that no longer exists — "
                        + "remove or rename the row")
                .isEmpty();
    }

    /**
     * The published map, which covers exactly the modules the public tree carries.
     *
     * <p>Two documents describe the same modules and neither is a copy of the other:
     * CLAUDE.md's rows are why a decision was made, addressed to whoever maintains this
     * next, and ARCHITECTURE.md's are what a module holds, addressed to someone reading
     * the source for the first time. That is the split {@code javadoc} already draws
     * between a class comment and the reasoning inside a method.
     *
     * <p>The list is {@code ext.publicModules} in the root build, read from there rather
     * than repeated here — it is also what the export uses to assemble the tree, and a
     * second copy would disagree with it the first time a module moved.
     */
    @Test
    @DisplayName("ARCHITECTURE.md documents exactly the modules the public tree carries")
    void architectureMapCoversEveryPublicModule() {
        Set<String> publicModules = publicModules();
        Set<String> documented = moduleRowsIn(repoRoot().resolve("ARCHITECTURE.md"));

        assertThat(publicModules)
                .as("ext.publicModules was read from the root build.gradle")
                .isNotEmpty();

        Set<String> undocumented = new LinkedHashSet<>(publicModules);
        undocumented.removeAll(documented);
        assertThat(undocumented)
                .as("modules in ext.publicModules with no row in ARCHITECTURE.md — the "
                        + "public tree ships them, so its own map has to describe them")
                .isEmpty();

        Set<String> extra = new LinkedHashSet<>(documented);
        extra.removeAll(publicModules);
        assertThat(extra)
                .as("ARCHITECTURE.md rows naming a module the public tree does not carry "
                        + "— a reader cannot open what was not shipped")
                .isEmpty();
    }

    @Test
    @DisplayName("every comparison operator's display symbol appears on the spellings page")
    void syntaxDocCoversEveryComparisonOperator() {
        String syntax = read(repoRoot().resolve("docs/reference/language/spellings.md"));

        List<String> missing = new ArrayList<>();
        for (ComparisonOperator op : ComparisonOperator.values()) {
            if (!syntax.contains(op.symbol())) {
                missing.add(op.name() + " (" + op.symbol() + ")");
            }
        }
        assertThat(missing)
                .as("comparison operators whose symbol is absent from the spellings page — the "
                        + "operator table is the Unicode↔ASCII contract and must list them all")
                .isEmpty();
    }

    @Test
    @DisplayName("every aggregate has a reference page, linked from the reference index")
    void everyAggregateHasAPage() {
        Path root = repoRoot();
        String index = read(root.resolve("docs/reference/README.md"));

        List<String> missingPage = new ArrayList<>();
        List<String> unlinked = new ArrayList<>();
        for (AggregateOperator op : AggregateOperator.values()) {
            String page = op.name().toLowerCase(java.util.Locale.ROOT) + ".md";
            if (!Files.isRegularFile(root.resolve("docs/reference/aggregates").resolve(page))) {
                missingPage.add(op.name());
            } else if (!index.contains("aggregates/" + page)) {
                unlinked.add(op.name());
            }
        }
        assertThat(missingPage)
                .as("aggregates with no docs/reference/aggregates/<name>.md page")
                .isEmpty();
        assertThat(unlinked)
                .as("aggregate pages not registered in docs/reference/README.md — the index "
                        + "must stay navigable (CLAUDE.md documentation rules)")
                .isEmpty();
    }

    @Test
    @DisplayName("every reference page uses the # Label: section format")
    void everyReferencePageUsesTheSectionFormat() {
        List<String> offenders = new ArrayList<>();
        for (Path page : referencePages()) {
            Set<String> sections = sectionLabels(read(page));
            List<String> missing = new ArrayList<>(REQUIRED_SECTIONS);
            missing.removeAll(sections);
            if (!missing.isEmpty()) {
                offenders.add(relativeToRoot(page) + " is missing " + missing);
            }
        }
        assertThat(offenders)
                .as("""
                        reference pages that do not use the project's `# Label:` section format. \
                        This is not a style rule: all three consumers parse that format and each \
                        fails silently without it — the PDF renders the page as an empty entry \
                        (docs/pdf/generate_pdf.py parse_page), the terminal `:doc` output loses its \
                        headings (DocRenderer), and docs/llm/extract_manual.py mines no training \
                        pairs from it. advanced/repl.md was exactly this, undetected""")
                .isEmpty();
    }

    @Test
    @DisplayName("no reference page carries a top-level heading the renderers cannot read")
    void everyTopLevelHeadingIsASectionLabel() {
        List<String> offenders = new ArrayList<>();
        for (Path page : referencePages()) {
            String text = read(page);
            Matcher m = TOP_LEVEL_HEADING.matcher(text);
            while (m.find()) {
                String heading = m.group(1).strip();
                if (!SECTION.matcher(m.group()).lookingAt()) {
                    offenders.add(relativeToRoot(page) + ": # " + heading);
                }
            }
        }
        assertThat(offenders)
                .as("""
                        top-level headings on a reference page that are not `# Label:` sections. \
                        A page is a run of those sections and nothing else, so a heading without \
                        the trailing colon is not a heading to any renderer: it and its whole body \
                        are folded into the section above and printed as raw Markdown, hash and \
                        unrendered backticks included. The sibling test above cannot see this — the \
                        page still carries every required section, and only the extra one is lost. \
                        Twenty-five pages were in exactly that state, `# Pushdown` on twenty-four \
                        of them, in the shipped PDF as well as on the site""")
                .isEmpty();
    }

    @Test
    @DisplayName("every reference page is registered in the index and reachable from :doc")
    void everyReferencePageIsRegisteredAndReachable() {
        Path root = repoRoot();
        String index = read(root.resolve("docs/reference/README.md"));
        // The second half of this check asks whether a page is in an index a tool can
        // find it by: the published jar's (Relix.referencePages()), or, for a page about a
        // front end, that front end's. Where neither file is present the index half still
        // runs: a tree without them still has a manual whose pages must be reachable from
        // its own contents.
        String docEntry = null;
        for (String index2 : List.of(
                "relix-embed/src/main/java/com/darkcollective/relix/embed/ReferenceIndex.java",
                "relix-console/src/main/java/com/darkcollective/relix/console/docs/DocEntry.java")) {
            Path f = root.resolve(index2);
            if (Files.isRegularFile(f)) {
                docEntry = (docEntry == null ? "" : docEntry) + read(f);
            }
        }

        List<String> unlinked = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        for (Path page : referencePages()) {
            String href = relativeToReference(page);
            if (!index.contains("(" + href + ")")) unlinked.add(href);
            // A per-function page is not listed in DocEntry: its key is derived from the
            // declaration of the function whose page it is, and the library that declares
            // it serves it (ADR-0026 S10). Checking that needs a DocRegistry over the
            // installed libraries, which this module cannot see — relix-console's
            // FunctionPageReachabilityTest makes the same assertion where it can be run.
            if (href.startsWith("functions/")) continue;
            if (docEntry != null && !docEntry.contains('"' + href + '"')) unreachable.add(href);
        }

        assertThat(unlinked)
                .as("pages absent from docs/reference/README.md — the index drives both "
                        + "navigation and PDF inclusion, so an unregistered page is invisible "
                        + "in the rendered manual")
                .isEmpty();
        assertThat(unreachable)
                .as("language pages absent from the reference index (ReferenceIndex, or DocEntry "
                        + "for a front end's page) — they cannot be reached by "
                        + "`:doc <key>` or `relix help`, so the page exists but no user can "
                        + "find it from inside the tool (function pages are checked by "
                        + "relix-console's FunctionPageReachabilityTest)")
                .isEmpty();
    }

    @Test
    @DisplayName("every internal link between reference pages resolves")
    void everyReferenceLinkResolves() {
        List<String> broken = new ArrayList<>();
        for (Path page : referencePages()) {
            Matcher m = MD_LINK.matcher(read(page));
            while (m.find()) {
                String href = m.group(1).split("#")[0].strip();
                if (href.isEmpty() || !href.endsWith(".md") || href.startsWith("http")) continue;
                Path target = page.getParent().resolve(href).normalize();
                if (!Files.isRegularFile(target)) {
                    broken.add(relativeToRoot(page) + " → " + href);
                }
            }
        }
        assertThat(broken)
                .as("dangling cross-links between reference pages — a `See Also` pointing at a "
                        + "page that does not exist is a dead end on the web and an unresolved "
                        + "jump in the PDF")
                .isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * Sections every reference page must carry. Deliberately the minimum the three
     * renderers depend on, not the whole template — {@code Limitations},
     * {@code Alternatives} and {@code Notes} stay genuinely optional.
     */
    private static final List<String> REQUIRED_SECTIONS =
            List.of("Name", "Syntax", "Description", "Examples");

    /**
     * Matches the same thing {@code generate_pdf.py}'s {@code SECTION_RE} matches, so the
     * guard sees a page exactly as the renderer does (fences included — a {@code # Label:}
     * line inside a code block would split the page for both).
     */
    private static final Pattern SECTION = Pattern.compile("(?m)^#\\s+([A-Za-z][^:\\n]*):");

    /**
     * Every top-level heading, section label or not — what {@link #SECTION} would have
     * matched had the author written the colon. Fence-blind for {@code SECTION}'s reason:
     * both renderers split a page on this line wherever it falls, so the guard has to see
     * the page the way they do rather than the way Markdown would.
     */
    private static final Pattern TOP_LEVEL_HEADING = Pattern.compile("(?m)^#\\s+(\\S.*)$");

    private static final Pattern MD_LINK = Pattern.compile("\\[[^\\]]*]\\(([^)]*)\\)");

    private static final Pattern INCLUDE =
            Pattern.compile("(?m)^\\s*include\\s+'([^']+)'");

    /** Every reference page, excluding the index and the dot-prefixed template. */
    private static List<Path> referencePages() {
        Path dir = repoRoot().resolve("docs/reference");
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> pages = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("README.md"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
            assertThat(pages).as("reference pages were found under docs/reference").isNotEmpty();
            return pages;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> sectionLabels(String page) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = SECTION.matcher(page);
        while (m.find()) {
            out.add(m.group(1).strip());
        }
        return out;
    }

    private static String relativeToReference(Path page) {
        return repoRoot().resolve("docs/reference").relativize(page).toString().replace('\\', '/');
    }

    private static String relativeToRoot(Path page) {
        return repoRoot().relativize(page).toString().replace('\\', '/');
    }

    /** Module names from {@code settings.gradle}. */
    private static Set<String> declaredModules() {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = INCLUDE.matcher(read(repoRoot().resolve("settings.gradle")));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** Module names appearing as the first cell of a module-map row in {@code page}. */
    private static Set<String> moduleRowsIn(Path page) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?m)^\\|\\s*`(relix-[a-z-]+)`\\s*\\|").matcher(read(page));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** The module names in {@code ext.publicModules}, read from the root build. */
    private static Set<String> publicModules() {
        String build = read(repoRoot().resolve("build.gradle"));
        int start = build.indexOf("ext.publicModules = [");
        assertThat(start).as("ext.publicModules is declared in the root build").isNotNegative();
        String body = build.substring(start, build.indexOf(']', start));
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("':(relix-[a-z-]+)'").matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/reference"))) {
            p = p.getParent();
        }
        assertThat(p).as("repo root containing docs/reference").isNotNull();
        return p;
    }
}

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the <em>audience</em> boundary of the documentation the project ships.
 *
 * <p>Four families of documentation are read by people outside the core team: the
 * generated Javadoc of every public API, {@code docs/reference}, {@code docs/guide},
 * and the website's own pages under {@code relix-site/content}. Two things must never appear
 * in any of them.
 *
 * <p><b>A pointer to a document the reader cannot open.</b> ADRs, design notes and
 * the scratch files under {@code .claude} are internal working material. A
 * {@code (ADR-0020)} in a published class comment is a dangling citation for the
 * one audience that cannot follow it, and a GitHub issue number is worse — it
 * names mutable tracker state as though it were a specification.
 *
 * <p><b>A promise about the future.</b> "Not yet supported" reads as a commitment
 * that support is coming; "deferred to a later slice" leaks the roadmap and dates
 * the page the moment priorities move. Published documentation states what the
 * software does today. A limitation is fine — {@code "MongoDB sources evaluate
 * windows in-engine"} — as long as it is a fact rather than an IOU.
 *
 * <p>Neither rule applies to <em>internal</em> comments: an implementation note
 * inside a method body, or the Javadoc of a package-private class, is core-team
 * correspondence and may cite an ADR freely. The boundary is therefore drawn where
 * the Javadoc tool draws it — {@link #publishedBlocks()} reproduces the
 * {@code -protected} visibility rule so this test flags exactly what the generated
 * HTML would show, and nothing else.
 *
 * <p>Lives beside the other doc guards in {@code relix-lang}: the lowest module
 * that can see the whole repository from a test.
 */
@DisplayName("Published documentation keeps its audience")
final class DocAudienceGuardTest {

    /** An ADR citation: internal decision records, not user-facing material. */
    private static final Pattern ADR = Pattern.compile("ADR-\\d{4}");

    /** A path into the internal design/working notes. */
    private static final Pattern INTERNAL_DOC = Pattern.compile(
            "docs/design/|\\.claude/|COMPLETED_TASKS|CLAUDE\\.md|BACKLOG\\.md");

    /**
     * A GitHub issue reference. In Java the bare form is safe to match: a
     * {@code #} preceded by a word character is provenance syntax
     * ({@code Orders#1}) and one preceded by {@code &} is an HTML entity
     * ({@code &#123;}), so both are excluded and nothing else in the sources
     * legitimately spells a bare {@code #} + digits.
     */
    private static final Pattern ISSUE_IN_JAVA = Pattern.compile("(?<![\\w&])#\\d{1,4}\\b");

    /**
     * The same, narrowed for Markdown, where prose can legitimately say "order
     * #4". Only the shapes an actual citation takes are rejected.
     */
    private static final Pattern ISSUE_IN_MARKDOWN = Pattern.compile(
            "(?i)(?:issue|epic|PR|see)\\s+#\\d{1,4}\\b|\\(#\\d{1,4}\\)|,\\s#\\d{1,4}\\b");

    /**
     * Phrasings that commit the project to work it may never do. {@code deferred
     * to runtime} and {@code deferred to the semantic layer} are deliberately not
     * here: they describe where responsibility sits today, not a plan.
     */
    private static final Pattern FUTURE_PROMISE = Pattern.compile(
            "(?i)not yet (?:implemented|supported|wired|available|executable|a )"
                    + "|will be (?:added|supported|implemented|provided)"
                    + "|(?:is|are) deferred(?!\\s+to\\s+(?:runtime|the))"
                    + "|(?:a|the) (?:future|later) slice"
                    + "|in a (?:future|later) (?:release|version|slice)"
                    + "|coming soon");

    @Nested
    @DisplayName("generated Javadoc")
    final class PublishedJavadoc {

        @Test
        @DisplayName("cites no ADR, issue, or internal design document")
        void citesNothingTheReaderCannotOpen() {
            assertNoMatch(publishedBlocks(), ADR,
                    "an ADR citation in published Javadoc — the reader has no access to "
                            + "ADRs; state the reasoning instead, or move the note to an "
                            + "internal comment");
            assertNoMatch(publishedBlocks(), INTERNAL_DOC,
                    "a path into internal design notes in published Javadoc");
            assertNoMatch(publishedBlocks(), ISSUE_IN_JAVA,
                    "a GitHub issue reference in published Javadoc — git history is the "
                            + "durable record, not a tracker number");
        }

        @Test
        @DisplayName("promises no future capability")
        void promisesNothing() {
            assertNoMatch(publishedBlocks(), FUTURE_PROMISE,
                    "a forward-looking promise in published Javadoc — say what the code "
                            + "does today; \"not yet supported\" is a commitment the "
                            + "project may never keep");
        }
    }

    @Nested
    @DisplayName("docs/reference")
    final class ReferenceDocs {

        @Test
        @DisplayName("cites no ADR, issue, or internal design document")
        void citesNothingTheReaderCannotOpen() {
            List<Excerpt> pages = referencePages();
            assertNoMatch(pages, ADR, "an ADR citation in the language reference");
            assertNoMatch(pages, INTERNAL_DOC,
                    "a path into internal design notes in the language reference");
            assertNoMatch(pages, ISSUE_IN_MARKDOWN,
                    "a GitHub issue reference in the language reference");
        }

        @Test
        @DisplayName("promises no future capability")
        void promisesNothing() {
            assertNoMatch(referencePages(), FUTURE_PROMISE,
                    "a forward-looking promise in the language reference — a limitation "
                            + "should read as a fact, not an IOU");
        }
    }

    @Nested
    @DisplayName("docs/guide")
    final class ProgrammingGuide {

        @Test
        @DisplayName("cites no ADR, issue, or internal design document")
        void citesNothingTheReaderCannotOpen() {
            List<Excerpt> pages = guidePages();
            assertNoMatch(pages, ADR, "an ADR citation in the programming guide");
            assertNoMatch(pages, INTERNAL_DOC,
                    "a path into internal design notes in the programming guide");
            assertNoMatch(pages, ISSUE_IN_MARKDOWN,
                    "a GitHub issue reference in the programming guide");
        }

        @Test
        @DisplayName("promises no future capability")
        void promisesNothing() {
            assertNoMatch(guidePages(), FUTURE_PROMISE,
                    "a forward-looking promise in the programming guide — a limitation "
                            + "should read as a fact, not an IOU");
        }
    }

    /**
     * The website's own pages. Skipped where {@code relix-site/content} is absent: the
     * generator is this repository's rather than the engine's, and the public tree does
     * not carry it. A guard that fails for material it was deliberately not given is a
     * guard that gets deleted.
     */
    @Nested
    @DisplayName("relix-site/content")
    final class SitePages {

        @BeforeEach
        void siteIsPresent() {
            assumeTrue(Files.isDirectory(repoRoot().resolve("relix-site/content")),
                    "no relix-site/content in this tree");
        }

        @Test
        @DisplayName("cites no ADR, issue, or internal design document")
        void citesNothingTheReaderCannotOpen() {
            List<Excerpt> pages = sitePages();
            assertNoMatch(pages, ADR, "an ADR citation on the website");
            assertNoMatch(pages, INTERNAL_DOC, "a path into internal design notes on the website");
            assertNoMatch(pages, ISSUE_IN_MARKDOWN, "a GitHub issue reference on the website");
        }

        @Test
        @DisplayName("promises no future capability")
        void promisesNothing() {
            assertNoMatch(sitePages(), FUTURE_PROMISE,
                    "a forward-looking promise on the website — the landing page is where "
                            + "a roadmap reads most like a commitment");
        }
    }

    // ── the published surface ────────────────────────────────────────────────

    /** One documentation excerpt: where it lives and what it says. */
    private record Excerpt(String location, String text) {}

    private static void assertNoMatch(List<Excerpt> excerpts, Pattern forbidden, String why) {
        List<String> offenders = new ArrayList<>();
        for (Excerpt e : excerpts) {
            Matcher m = forbidden.matcher(e.text());
            if (m.find()) {
                offenders.add(e.location() + " — \"" + m.group() + "\"");
            }
        }
        assertThat(offenders).as(why).isEmpty();
    }

    /**
     * Every Javadoc comment that reaches the generated HTML.
     *
     * <p>Mirrors the {@code -protected} default the {@code javadoc} task runs
     * under: {@code package-info}/{@code module-info} always publish; otherwise
     * the file's top-level type must be public, and the block must document a
     * {@code public}/{@code protected} member, an implicitly-public interface
     * member, or an enum constant. Everything else — a package-private class, a
     * private field — is internal and exempt.
     */
    private static List<Excerpt> publishedBlocks() {
        List<Excerpt> out = new ArrayList<>();
        for (Path file : mainSources()) {
            String name = file.getFileName().toString();
            boolean info = name.equals("package-info.java") || name.equals("module-info.java");
            List<String> lines = List.of(read(file).split("\n", -1));
            TopLevel top = topLevel(lines);
            if (!info && !top.isPublic()) {
                continue;
            }
            collectBlocks(file, lines, info, top, out);
        }
        assertThat(out).as("published Javadoc blocks were found").isNotEmpty();
        return out;
    }

    private static void collectBlocks(Path file, List<String> lines, boolean info,
                                      TopLevel top, List<Excerpt> out) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).stripLeading().startsWith("/**")) {
                continue;
            }
            int start = i;
            while (i < lines.size() && !lines.get(i).contains("*/")) {
                i++;
            }
            int end = Math.min(i, lines.size() - 1);
            if (info || publishes(top, declarationAfter(lines, end))) {
                out.add(new Excerpt(relativeToRoot(file) + ":" + (start + 1),
                        String.join("\n", lines.subList(start, end + 1))));
            }
        }
    }

    /** The declaration a Javadoc block documents: the next real line after it. */
    private static String declarationAfter(List<String> lines, int blockEnd) {
        for (int j = blockEnd + 1; j < lines.size(); j++) {
            String s = lines.get(j).strip();
            if (s.isEmpty() || s.startsWith("@") || s.startsWith("//")) {
                continue;
            }
            return s;
        }
        return "";
    }

    private static final Pattern ENUM_CONSTANT = Pattern.compile("^[A-Z][A-Z0-9_]*\\s*[(,;]");

    private static boolean publishes(TopLevel top, String declaration) {
        if (declaration.isEmpty() || declaration.startsWith("private")) {
            return false;
        }
        if (declaration.startsWith("public") || declaration.startsWith("protected")) {
            return true;
        }
        // Interface members are implicitly public; enum constants carry no modifier.
        return top.isInterface() || (top.isEnum() && ENUM_CONSTANT.matcher(declaration).find());
    }

    private record TopLevel(boolean isPublic, String kind) {
        boolean isInterface() {
            return "interface".equals(kind) || "@interface".equals(kind);
        }

        boolean isEnum() {
            return "enum".equals(kind);
        }
    }

    private static final Pattern TYPE_DECL =
            Pattern.compile("\\b(class|interface|enum|record|@interface)\\s+\\w+");

    private static TopLevel topLevel(List<String> lines) {
        for (String line : lines) {
            String s = line.stripLeading();
            if (s.startsWith("*") || s.startsWith("//") || s.startsWith("/*")
                    || s.startsWith("import") || s.startsWith("package")) {
                continue;
            }
            Matcher m = TYPE_DECL.matcher(line);
            if (m.find()) {
                return new TopLevel(line.strip().startsWith("public"), m.group(1));
            }
        }
        return new TopLevel(false, "");
    }

    // ── file walking ─────────────────────────────────────────────────────────

    private static List<Path> mainSources() {
        try (Stream<Path> walk = Files.walk(repoRoot())) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> relativeToRoot(p).matches("relix-[a-z-]+/src/main/.*"))
                    // An .internal package is never exported by the published jar, so a
                    // public class there is the engine's own and its Javadoc is not read
                    // by a user (#1141).
                    .filter(p -> !relativeToRoot(p).contains("/internal/"))
                    .sorted()
                    .toList();
            assertThat(files).as("main sources were found").isNotEmpty();
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Excerpt> guidePages() {
        return markdownUnder("docs/guide", "programming guide pages");
    }

    private static List<Excerpt> referencePages() {
        return markdownUnder("docs/reference", "reference pages");
    }

    /** The hand-written website pages — the landing page and the pages around it. */
    private static List<Excerpt> sitePages() {
        return markdownUnder("relix-site/content", "website pages");
    }

    private static List<Excerpt> markdownUnder(String directory, String what) {
        Path dir = repoRoot().resolve(directory);
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Excerpt> pages = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .map(p -> new Excerpt(relativeToRoot(p), read(p)))
                    .toList();
            assertThat(pages).as(what + " were found").isNotEmpty();
            return pages;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relativeToRoot(Path p) {
        return repoRoot().relativize(p).toString().replace('\\', '/');
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

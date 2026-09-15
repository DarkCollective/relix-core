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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A test builds a node through the published authoring surface — {@code AstBuilders},
 * {@code Expr}, {@code ScriptBuilders} — rather than re-declaring a factory of its own.
 *
 * <p>This is the construction half of what {@link AssertionSurfaceGuardTest} does for
 * assertions, and it is a guard for the same reason: the convention decayed before it was
 * written down. {@code AstBuilders} and {@code ScriptBuilders} were promoted out of
 * {@code testFixtures} into {@code main} precisely so that building a tree without the
 * grammar is a supported thing to do, but nothing swept the test sources behind that
 * promotion — 106 private factories across 47 files still declared
 * {@code private static NumberOperand num(String v)} and the 23 that had drifted to a
 * name of their own ({@code leftOuter}, {@code eqCols}, {@code scriptOf}) were no longer
 * findable as copies at all. Each is one line, which is exactly why they accumulate.
 *
 * <p><b>What is forbidden.</b> A static method in a test source whose whole body is a
 * single {@code return new <Kind>(…)}, whose parameters match a published factory that
 * builds that same record. Matching is on <em>signature</em> rather than on name, since
 * the copies that matter most are the renamed ones — a guard keyed on {@code num} would
 * pass {@code n} unchanged.
 *
 * <p><b>What is deliberately allowed.</b>
 *
 * <ol>
 *   <li><b>Calling a record's constructor directly.</b> {@code new SelectionNode(p, r)} at
 *       a call site is not a copy of {@code select(p, r)} — it is the record's own public
 *       constructor, there are some 8,400 such call sites, and a rule against them would
 *       be a style sweep rather than a de-duplication. What this forbids is declaring a
 *       <em>second factory</em> beside the published one.</li>
 *   <li><b>A named local helper that delegates.</b> {@code numOperand(v)} returning
 *       {@code AstBuilders.num(v)} is an alias, not a re-implementation, and in
 *       {@code relix-processor} it is a necessity: {@code ProcessorTestSupport.num} builds
 *       a row <em>value</em> of the same spelling, an inherited member wins over a static
 *       import, and the two vocabularies have to be spelled apart.</li>
 *   <li><b>A helper that passes a {@link com.darkcollective.relix.ast.SourceLocation}.</b>
 *       Every factory defaults the location to {@code UNKNOWN} and none takes one, so this
 *       is the one shape the published surface cannot express — {@code AstLocations} and
 *       the parser's position tests need it and are not doing anything wrong.</li>
 * </ol>
 *
 * <p>It lives here for the reason {@link AssertionSurfaceGuardTest} does: the guards that
 * read the repository tree rather than their own module are collected in one place, and
 * this module's {@code test} task declares that tree as an input, without which a change
 * in another module's tests leaves this UP-TO-DATE — passing because it never ran.
 */
@DisplayName("tests build nodes through the published builders, not around them")
final class BuilderSurfaceGuardTest {

    /** The three published authoring surfaces, as {@code <module>/…/<Class>.java}. */
    private static final Map<String, String> BUILDERS = Map.of(
            "AstBuilders", "relix-ast/src/main/java/com/darkcollective/relix/ast/AstBuilders.java",
            "Expr", "relix-ast/src/main/java/com/darkcollective/relix/ast/Expr.java",
            "ScriptBuilders",
            "relix-lang-ast/src/main/java/com/darkcollective/relix/lang/ast/ScriptBuilders.java");

    /**
     * {@code AstLocations} rebuilds every record component-by-component with a stripped
     * location — that <em>is</em> the fixture, and it is excluded as the mechanism rather
     * than admitted as a use of it, the distinction {@code AssertionSurfaceGuardTest}
     * draws for the message projection it forbids.
     */
    private static final Set<String> REBUILDERS = Set.of("AstLocations.java");

    /** A local factory: a static method whose whole body is one <code>return new Kind(…);</code>. */
    private static final Pattern LOCAL_FACTORY = Pattern.compile(
            "(?:private|public|protected)?\\s*static\\s+([\\w.]+(?:<[^>]*>)?)\\s+(\\w+)"
                    + "\\s*\\(([^)]*)\\)\\s*\\{\\s*return\\s+new\\s+(\\w+)\\s*\\(([^;]*)\\);\\s*}",
            Pattern.DOTALL);

    /** A published factory: a <code>public static</code> method on one of the three builders. */
    private static final Pattern PUBLISHED_FACTORY = Pattern.compile(
            "public\\s+static\\s+([\\w.]+(?:<[^>]*>)?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{");

    @Test
    @DisplayName("no test re-declares a factory the published builders already publish")
    void noLocalCopyOfAPublishedFactory() {
        Map<String, Set<String>> published = publishedFactories();
        assertThat(published).as("published factories, keyed by return type and parameters")
                .hasSizeGreaterThan(100);

        List<String> offenders = new ArrayList<>();
        for (Path source : testSources()) {
            if (REBUILDERS.contains(source.getFileName().toString())) {
                continue;
            }
            String text = String.join("\n", codeLines(source));
            Matcher local = LOCAL_FACTORY.matcher(text);
            while (local.find()) {
                String returnType = local.group(1);
                String name = local.group(2);
                String kind = local.group(4);
                if (local.group(0).contains("SourceLocation")) {
                    continue;           // the one thing no factory can express
                }
                Set<String> already = published.get(key(kind, local.group(3)));
                if (already == null) {
                    already = published.get(key(returnType, local.group(3)));
                }
                if (already != null) {
                    offenders.add("%s:%d  %s(…) → new %s  — already published as %s"
                            .formatted(relative(source), lineOf(text, local.start()), name, kind,
                                    already));
                }
            }
        }

        assertThat(offenders)
                .as("""
                        A test declared its own factory for a node the published authoring \
                        surface already builds. Delete it and static-import the published one \
                        (AstBuilders/Expr for a RelNode, Predicate or Operand; ScriptBuilders \
                        for a Statement or Script), or — where the name is taken, as num/str \
                        are in relix-processor — keep the local name and delegate to it.""")
                .isEmpty();
    }

    // =========================================================================
    // Reading the published surface
    // =========================================================================

    /** {@code returnType(paramTypes)} → the factory names publishing that signature. */
    private static Map<String, Set<String>> publishedFactories() {
        Map<String, Set<String>> published = new HashMap<>();
        BUILDERS.forEach((owner, path) -> {
            Matcher factory = PUBLISHED_FACTORY.matcher(read(repoRoot().resolve(path)));
            while (factory.find()) {
                published.computeIfAbsent(key(factory.group(1), factory.group(3)),
                        k -> new TreeSet<>()).add(owner + "." + factory.group(2));
            }
        });
        return published;
    }

    /**
     * A signature as {@code Type(ParamType, ParamType)}.
     *
     * <p>Generic arguments are kept: {@code project(List<ProjectedAttribute>, RelNode)} and
     * a test's {@code project(List<String>, RelNode)} build the same record from different
     * inputs, and erasing them would report the second as a copy of the first.
     */
    private static String key(String returnType, String params) {
        List<String> types = new ArrayList<>();
        for (String param : splitParams(params)) {
            String cleaned = param.replace("final ", "").strip();
            int space = cleaned.lastIndexOf(' ');
            types.add(space < 0 ? cleaned : cleaned.substring(0, space).strip());
        }
        return normalise(returnType) + "(" + String.join(",", types.stream().map(
                BuilderSurfaceGuardTest::normalise).toList()) + ")";
    }

    private static String normalise(String type) {
        return type.replaceAll("\\s+", "");
    }

    /** Splits a parameter list on top-level commas only — a {@code Map<K, V>} has one. */
    private static List<String> splitParams(String params) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : params.toCharArray()) {
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString());
        }
        return out;
    }

    // =========================================================================
    // Repository access — the same shape AssertionSurfaceGuardTest uses
    // =========================================================================

    private static List<Path> testSources() {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                for (String set : List.of("src/test/java", "src/testFixtures/java")) {
                    Path dir = module.resolve(set);
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".java"))
                                .forEach(sources::add);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(sources).as("test sources to scan").hasSizeGreaterThan(100);
        return sources;
    }

    /** {@code source} with comment lines blanked, so javadoc showing a shape is not one. */
    private static List<String> codeLines(Path source) {
        List<String> lines = new ArrayList<>();
        boolean inBlockComment = false;
        for (String line : read(source).lines().toList()) {
            String trimmed = line.strip();
            boolean commented = inBlockComment
                    || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*");
            if (trimmed.startsWith("/*") && !trimmed.contains("*/")) {
                inBlockComment = true;
            }
            if (inBlockComment && trimmed.endsWith("*/")) {
                inBlockComment = false;
            }
            lines.add(commented ? "" : line);
        }
        return lines;
    }

    private static int lineOf(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String relative(Path source) {
        return repoRoot().relativize(source).toString();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("relix-ast/src/main/java"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new AssertionError("could not locate the repository root from "
                    + Paths.get("").toAbsolutePath());
        }
        return path;
    }
}

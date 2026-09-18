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

import com.darkcollective.relix.function.FunctionCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0026 — how an engine module is allowed to reach a function: it is handed one.
 *
 * <p>This is the grep-style counterpart to {@link EngineBoundaryTest}. That test reads a
 * compiled {@code module-info} and so can only see which modules exist; the claim here is
 * about <em>access pattern</em> within them, which nothing in the module graph records.
 *
 * <p>Three things it forbids in the {@code main} source of an engine module:
 *
 * <ol>
 *   <li><b>A static function registry.</b> {@code BuiltinFunctionRegistry.get()} was the
 *       original one — deleted with this test's arrival — and its shape is what matters:
 *       a phase reaching around its caller for a second opinion on what a function is.</li>
 *   <li><b>Discovering the installed libraries.</b> {@link FunctionCatalog#discover()}
 *       looks like injection and is not: two independently-discovered catalogues can
 *       disagree about what a name means, and the disagreement surfaces as analysis
 *       accepting a call that evaluation cannot make. Discovery belongs at the small set
 *       of <em>entry points</em> in {@link #DISCOVERY_ENTRY_POINTS}, each of which
 *       establishes the one catalogue for a run when its caller supplied none, and each
 *       of which then passes it on. Everywhere else, take it as a parameter.</li>
 *   <li><b>Dispatching on a built-in's name.</b> A {@code case "sin"} or an
 *       {@code equals("DATE_TRUNC")} in the engine is the table ADR-0026 removed growing
 *       back: the engine is supposed to hold a function's <em>form</em>, and a name it
 *       recognises specially is knowledge of a particular implementation. The names are
 *       read from the installed libraries rather than listed here, so a library's own
 *       function is protected by the same rule.</li>
 * </ol>
 */
@DisplayName("ADR-0026 — engine modules are handed their functions, never fetch them")
final class FunctionAccessGuardTest {

    /** The modules that make up the engine: those an embedder gets without a front end. */
    private static final List<String> ENGINE_MODULES = List.of(
            "relix-ast", "relix-symbol", "relix-value", "relix-function", "relix-lang-ast",
            "relix-semantic", "relix-cost", "relix-plan", "relix-optimizer", "relix-events",
            "relix-json", "relix-provenance", "relix-processor");

    /**
     * Where discovery is the right thing, each for the same reason: it is the point at
     * which a run acquires its one catalogue, on behalf of a caller that named none.
     *
     * <ul>
     *   <li>{@code SemanticAnalyzer} — the convenience constructors, for an embedder that
     *       has no opinion about which libraries are installed.</li>
     *   <li>{@code SemanticModel} — the convenience constructor, for a model assembled
     *       without going through the analyser.</li>
     *   <li>{@code ExecutionContext} — the documented fallback for a context built
     *       without a model, discovering once in a holder. The production path passes
     *       {@code model.functions()} instead.</li>
     * </ul>
     *
     * <p>Adding a name here is a decision to be argued, not a way to make this test pass:
     * every entry is one more place two catalogues could come from.
     */
    private static final Set<String> DISCOVERY_ENTRY_POINTS =
            Set.of("SemanticAnalyzer.java", "SemanticModel.java", "ExecutionContext.java");

    /**
     * The file that <em>declares</em> discovery, and shows it in its own documentation.
     * Excluded rather than listed as an entry point: it is the mechanism, not a use of it.
     */
    private static final String DISCOVERY_DECLARATION = "FunctionCatalog.java";

    private static final Pattern STATIC_REGISTRY = Pattern.compile("BuiltinFunctionRegistry");

    private static final Pattern DISCOVERY = Pattern.compile("FunctionCatalog\\s*\\.\\s*discover\\s*\\(");

    /** A {@code case "…"} label or an {@code equals("…")} against a literal name. */
    private static final Pattern NAME_TEST =
            Pattern.compile("(?:case\\s+|equals(?:IgnoreCase)?\\(\\s*)\"([A-Za-z_]\\w*)\"");

    @Test
    @DisplayName("no engine module reaches a static function registry")
    void noStaticRegistry() {
        assertThat(offenders(STATIC_REGISTRY, unused -> true))
                .as("""
                        engine sources naming a static function registry. What a function \
                        is comes from the FunctionCatalog the caller supplied — analysis \
                        threads it through the SemanticModel, execution through the \
                        ExecutionContext. A static second directory can disagree with it""")
                .isEmpty();
    }

    @Test
    @DisplayName("only the declared entry points discover the installed libraries")
    void discoveryIsConfinedToEntryPoints() {
        assertThat(offenders(DISCOVERY, file -> {
                    String name = file.getFileName().toString();
                    return !DISCOVERY_ENTRY_POINTS.contains(name)
                            && !DISCOVERY_DECLARATION.equals(name);
                }))
                .as("""
                        engine sources calling FunctionCatalog.discover() outside \
                        FunctionAccessGuardTest.DISCOVERY_ENTRY_POINTS. Discovery is for \
                        the start of a run, not for a phase that was handed a tree: take \
                        the catalogue as a parameter and let one run have one catalogue""")
                .isEmpty();
    }

    @Test
    @DisplayName("no engine module singles out an installed function by name")
    void noEngineModuleDispatchesOnAFunctionName() {
        Set<String> installed = FunctionCatalog.discover().names().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertThat(installed)
                .as("the installed libraries, without which this test asserts nothing")
                .isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Path source : engineSources()) {
            List<String> lines = read(source).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = NAME_TEST.matcher(lines.get(i));
                while (matcher.find()) {
                    if (installed.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
                        offenders.add(source + ":" + (i + 1) + " — '" + matcher.group(1) + "'");
                    }
                }
            }
        }
        assertThat(offenders)
                .as("""
                        engine sources testing a name against an installed function. The \
                        engine keeps a function's form; what the name means is the \
                        library's, reached through the catalogue — ask the resolved \
                        function, do not recognise its name""")
                .isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Every line of engine {@code main} source matching {@code pattern} in a file the filter admits. */
    private static List<String> offenders(Pattern pattern,
                                          java.util.function.Predicate<Path> included) {
        List<String> found = new ArrayList<>();
        for (Path source : engineSources()) {
            if (!included.test(source)) {
                continue;
            }
            List<String> lines = read(source).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    found.add(source + ":" + (i + 1) + " — " + lines.get(i).strip());
                }
            }
        }
        return found;
    }

    /** Every {@code .java} file in the {@code main} source of an engine module. */
    private static List<Path> engineSources() {
        Path root = repoRoot();
        List<Path> sources = new ArrayList<>();
        for (String module : ENGINE_MODULES) {
            Path main = root.resolve(module).resolve("src/main/java");
            assertThat(main).as("engine module source directory").exists();
            try (Stream<Path> walk = Files.walk(main)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .forEach(sources::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(sources).as("engine sources to scan").isNotEmpty();
        return sources;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Walks up from the working directory to the directory holding the modules. */
    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("relix-semantic/src/main/java"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new AssertionError("could not locate the repository root from "
                    + Paths.get("").toAbsolutePath());
        }
        return path;
    }
}

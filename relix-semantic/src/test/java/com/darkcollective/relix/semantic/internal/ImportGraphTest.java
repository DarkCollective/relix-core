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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.semantic.ScriptLoader;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.Severity;
import com.darkcollective.relix.lang.ast.ImportKind;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ImportGraph — loading, cycle detection, topological order, path resolution")
final class ImportGraphTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    /** A script with no statements. */
    private static Script empty() {
        return script();
    }

    /** A script that bulk-imports the given paths. */
    private static Script importing(String... paths) {
        var stmts = new ArrayList<Statement>();
        for (String path : paths) {
            stmts.add(importAll(path));
        }
        return script(stmts.toArray(Statement[]::new));
    }

    /** A script that imports the given path with a specific kind and names. */
    private static Script importing(ImportKind kind, List<String> names, String path) {
        return script(importNames(kind, names, path));
    }

    private static ImportGraph build(String root, Map<String, Script> scripts) {
        return ImportGraph.build(root, null, new InMemoryScriptLoader(scripts));
    }

    private static ImportGraph buildWithPreloaded(String root, Script preloaded,
                                                   Map<String, Script> others) {
        return ImportGraph.build(root, preloaded, new InMemoryScriptLoader(others));
    }

    // =========================================================================
    // Single file
    // =========================================================================

    @Nested
    @DisplayName("Single file")
    class SingleFile {

        @Test
        @DisplayName("Single file with no imports — processingOrder contains only root")
        void singleFile() {
            var graph = build("root.relix", Map.of("root.relix", empty()));
            assertThat(graph.processingOrder()).containsExactly("root.relix");
            assertThat(graph.hasLoadErrors()).isFalse();
        }

        @Test
        @DisplayName("script() returns the loaded script")
        void scriptAccessor() {
            Script s = empty();
            var graph = build("root.relix", Map.of("root.relix", s));
            assertThat(graph.script("root.relix")).contains(s);
        }

        @Test
        @DisplayName("paths() contains only the root")
        void pathsContainsOnlyRoot() {
            var graph = build("root.relix", Map.of("root.relix", empty()));
            assertThat(graph.paths()).containsExactly("root.relix");
        }

        @Test
        @DisplayName("rootPath() returns the root path")
        void rootPath() {
            var graph = build("root.relix", Map.of("root.relix", empty()));
            assertThat(graph.rootPath()).isEqualTo("root.relix");
        }
    }

    // =========================================================================
    // Linear chains
    // =========================================================================

    @Nested
    @DisplayName("Linear import chains")
    class LinearChains {

        @Test
        @DisplayName("A imports B → processingOrder is [B, A]")
        void twoFiles() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("b.relix"),
                    "b.relix", empty()));
            assertThat(graph.processingOrder()).containsExactly("b.relix", "a.relix");
            assertThat(graph.hasLoadErrors()).isFalse();
        }

        @Test
        @DisplayName("A imports B imports C → processingOrder is [C, B, A]")
        void threeFileChain() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("b.relix"),
                    "b.relix", importing("c.relix"),
                    "c.relix", empty()));
            assertThat(graph.processingOrder()).containsExactly("c.relix", "b.relix", "a.relix");
        }

        @Test
        @DisplayName("Four-file chain → dependencies always precede dependents")
        void fourFileChain() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("b.relix"),
                    "b.relix", importing("c.relix"),
                    "c.relix", importing("d.relix"),
                    "d.relix", empty()));
            var order = graph.processingOrder();
            assertThat(order).hasSize(4);
            assertThat(order.indexOf("d.relix")).isLessThan(order.indexOf("c.relix"));
            assertThat(order.indexOf("c.relix")).isLessThan(order.indexOf("b.relix"));
            assertThat(order.indexOf("b.relix")).isLessThan(order.indexOf("a.relix"));
        }
    }

    // =========================================================================
    // Diamond / shared dependency
    // =========================================================================

    @Nested
    @DisplayName("Diamond and shared dependencies")
    class Diamond {

        @Test
        @DisplayName("Diamond (A→B,C; B→D; C→D) — D first, A last, no duplicates")
        void diamond() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("b.relix", "c.relix"),
                    "b.relix", importing("d.relix"),
                    "c.relix", importing("d.relix"),
                    "d.relix", empty()));
            var order = graph.processingOrder();
            assertThat(order).hasSize(4);
            assertThat(order.get(0)).isEqualTo("d.relix");     // D must be first
            assertThat(order.get(order.size() - 1)).isEqualTo("a.relix"); // A must be last
            assertThat(order.indexOf("b.relix")).isGreaterThan(order.indexOf("d.relix"));
            assertThat(order.indexOf("c.relix")).isGreaterThan(order.indexOf("d.relix"));
        }

        @Test
        @DisplayName("Shared dependency loaded only once (not duplicated in graph)")
        void sharedDepLoadedOnce() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("b.relix", "c.relix"),
                    "b.relix", importing("common.relix"),
                    "c.relix", importing("common.relix"),
                    "common.relix", empty()));
            // All 4 files loaded exactly once
            assertThat(graph.paths()).hasSize(4);
            // common.relix appears exactly once in the processing order
            assertThat(graph.processingOrder().stream()
                    .filter(p -> p.equals("common.relix")).count()).isEqualTo(1);
        }
    }

    // =========================================================================
    // Cycle detection
    // =========================================================================

    @Nested
    @DisplayName("Cycle detection")
    class Cycles {

        @Test
        @DisplayName("Direct two-file cycle A→B→A reports an error")
        void directCycle() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("b.relix"),
                    "b.relix", importing("a.relix")));
            assertThat(graph.hasLoadErrors()).isTrue();
            assertThat(graph.loadErrors().get(0).message())
                    .contains("cycle")
                    .contains("a.relix")
                    .contains("b.relix");
        }

        @Test
        @DisplayName("Direct two-file cycle error contains the full cycle path")
        void directCyclePath() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("b.relix"),
                    "b.relix", importing("a.relix")));
            String msg = graph.loadErrors().get(0).message();
            // The cycle path should close: a → b → a
            assertThat(msg).matches(".*a\\.relix.*b\\.relix.*a\\.relix.*");
        }

        @Test
        @DisplayName("Self-import A→A reports a cycle error")
        void selfImport() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("a.relix")));
            assertThat(graph.hasLoadErrors()).isTrue();
            assertThat(graph.loadErrors().get(0).message()).contains("cycle");
        }

        @Test
        @DisplayName("Three-file cycle A→B→C→A reports an error with full path")
        void threeFileCycle() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("b.relix"),
                    "b.relix", importing("c.relix"),
                    "c.relix", importing("a.relix")));
            assertThat(graph.hasLoadErrors()).isTrue();
            String msg = graph.loadErrors().get(0).message();
            assertThat(msg).contains("a.relix").contains("b.relix").contains("c.relix");
        }

        @Test
        @DisplayName("Cycle error uses line=0, col=0 (no source position)")
        void cycleErrorPosition() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("a.relix")));
            var error = graph.loadErrors().get(0);
            assertThat(error.line()).isEqualTo(0);
            assertThat(error.column()).isEqualTo(0);
            assertThat(error.severity()).isEqualTo(Severity.ERROR);
        }
    }

    // =========================================================================
    // Missing files
    // =========================================================================

    @Nested
    @DisplayName("Missing file errors")
    class MissingFiles {

        @Test
        @DisplayName("Importing a missing file records a SemanticError")
        void missingImport() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("missing.relix")));
            assertThat(graph.hasLoadErrors()).isTrue();
            assertThat(graph.loadErrors().get(0).message())
                    .contains("missing.relix");
        }

        @Test
        @DisplayName("Missing root file records a SemanticError")
        void missingRoot() {
            var graph = build("nonexistent.relix", Map.of());
            assertThat(graph.hasLoadErrors()).isTrue();
            assertThat(graph.loadErrors().get(0).message())
                    .contains("nonexistent.relix");
        }

        @Test
        @DisplayName("Successfully loaded files are still accessible after a partial failure")
        void partialLoadStillAccessible() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("good.relix", "missing.relix"),
                    "good.relix", empty()));
            assertThat(graph.script("a.relix")).isPresent();
            assertThat(graph.script("good.relix")).isPresent();
            assertThat(graph.script("missing.relix")).isEmpty();
            assertThat(graph.hasLoadErrors()).isTrue();
        }

        @Test
        @DisplayName("Missing file does not appear in processingOrder()")
        void missingExcludedFromOrder() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("missing.relix")));
            assertThat(graph.processingOrder()).doesNotContain("missing.relix");
        }
    }

    // =========================================================================
    // Preloaded root (stdin scenario)
    // =========================================================================

    @Nested
    @DisplayName("Preloaded root (stdin)")
    class PreloadedRoot {

        @Test
        @DisplayName("Preloaded root script with no imports — graph has one node")
        void preloadedRootNoImports() {
            var graph = buildWithPreloaded(SemanticAnalyzer.STDIN_PATH, empty(), Map.of());
            assertThat(graph.processingOrder())
                    .containsExactly(SemanticAnalyzer.STDIN_PATH);
            assertThat(graph.hasLoadErrors()).isFalse();
        }

        @Test
        @DisplayName("Preloaded root with imports — imports loaded via ScriptLoader")
        void preloadedRootWithImports() {
            var graph = buildWithPreloaded(
                    SemanticAnalyzer.STDIN_PATH,
                    importing("helpers.relix"),
                    Map.of("helpers.relix", empty()));
            assertThat(graph.paths()).contains(SemanticAnalyzer.STDIN_PATH, "helpers.relix");
            var order = graph.processingOrder();
            assertThat(order.indexOf("helpers.relix"))
                    .isLessThan(order.indexOf(SemanticAnalyzer.STDIN_PATH));
        }

        @Test
        @DisplayName("rootPath() returns STDIN_PATH when built from InputStream")
        void rootPathIsStdin() {
            var graph = buildWithPreloaded(SemanticAnalyzer.STDIN_PATH, empty(), Map.of());
            assertThat(graph.rootPath()).isEqualTo(SemanticAnalyzer.STDIN_PATH);
        }
    }

    // =========================================================================
    // Import kinds
    // =========================================================================

    @Nested
    @DisplayName("All import kinds trigger file loading")
    class ImportKinds {

        @Test
        @DisplayName("BULK import loads the target file")
        void bulkImport() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing(ImportKind.BULK, List.of(), "lib.relix"),
                    "lib.relix", empty()));
            assertThat(graph.paths()).contains("lib.relix");
        }

        @Test
        @DisplayName("UNQUALIFIED import loads the target file")
        void unqualifiedImport() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing(ImportKind.UNQUALIFIED, List.of("Users"), "db.relix"),
                    "db.relix", empty()));
            assertThat(graph.paths()).contains("db.relix");
        }

        @Test
        @DisplayName("SOURCE import loads the target file")
        void sourceImport() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing(ImportKind.SOURCE, List.of("weather"), "api.relix"),
                    "api.relix", empty()));
            assertThat(graph.paths()).contains("api.relix");
        }

        @Test
        @DisplayName("RELATION import loads the target file")
        void relationImport() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing(ImportKind.RELATION, List.of("Cities"), "geo.relix"),
                    "geo.relix", empty()));
            assertThat(graph.paths()).contains("geo.relix");
        }

        @Test
        @DisplayName("FUNCTION import loads the target file")
        void functionImport() {
            var graph = build("a.relix", Map.of(
                    "a.relix", importing(ImportKind.FUNCTION, List.of("tax"), "calc.relix"),
                    "calc.relix", empty()));
            assertThat(graph.paths()).contains("calc.relix");
        }
    }

    // =========================================================================
    // Path resolution
    // =========================================================================

    @Nested
    @DisplayName("resolvePath() — normalization and relative resolution")
    class PathResolution {

        @Test
        @DisplayName("Bare filename + relative import → normalized filename")
        void bareBaseWithRelative() {
            assertThat(ImportGraph.resolvePath("a.relix", "./b.relix"))
                    .isEqualTo("b.relix");
        }

        @Test
        @DisplayName("Directory base + relative import → resolved path")
        void directoryBaseWithRelative() {
            assertThat(ImportGraph.resolvePath("scripts/a.relix", "./b.relix"))
                    .isEqualTo("scripts/b.relix");
        }

        @Test
        @DisplayName("Directory base + parent traversal → resolved path")
        void parentTraversal() {
            assertThat(ImportGraph.resolvePath("scripts/a.relix", "../common/b.relix"))
                    .isEqualTo("common/b.relix");
        }

        @Test
        @DisplayName("Absolute import path is used as-is")
        void absoluteImportPath() {
            assertThat(ImportGraph.resolvePath("scripts/a.relix", "/abs/b.relix"))
                    .isEqualTo("/abs/b.relix");
        }

        @Test
        @DisplayName("stdin base + relative import → normalized filename")
        void stdinBase() {
            assertThat(ImportGraph.resolvePath(SemanticAnalyzer.STDIN_PATH, "./b.relix"))
                    .isEqualTo("b.relix");
        }

        @Test
        @DisplayName("Import path without leading ./ → normalized as-is")
        void bareImportPath() {
            assertThat(ImportGraph.resolvePath("a.relix", "b.relix"))
                    .isEqualTo("b.relix");
        }

        @Test
        @DisplayName("Nested directory base + sibling import")
        void nestedDirectory() {
            assertThat(ImportGraph.resolvePath("a/b/c.relix", "./d.relix"))
                    .isEqualTo("a/b/d.relix");
        }
    }

    // =========================================================================
    // Error accessors
    // =========================================================================

    @Nested
    @DisplayName("Error collection")
    class ErrorCollection {

        @Test
        @DisplayName("No errors on successful load")
        void noErrorsOnSuccess() {
            var graph = build("a.relix", Map.of("a.relix", empty()));
            assertThat(graph.loadErrors()).isEmpty();
            assertThat(graph.hasLoadErrors()).isFalse();
        }

        @Test
        @DisplayName("loadErrors() is unmodifiable")
        void errorsListIsUnmodifiable() {
            var graph = build("a.relix", Map.of("a.relix", importing("x.relix")));
            assertThat(graph.loadErrors()).isUnmodifiable();
        }

        @Test
        @DisplayName("Multiple errors are all recorded (one per problem)")
        void multipleErrors() {
            // a.relix imports two missing files
            var graph = build("a.relix", Map.of(
                    "a.relix", importing("miss1.relix", "miss2.relix")));
            assertThat(graph.loadErrors()).hasSize(2);
        }
    }
}

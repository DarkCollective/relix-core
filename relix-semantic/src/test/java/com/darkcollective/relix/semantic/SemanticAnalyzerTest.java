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

import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.symbol.Symbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SemanticAnalyzer — construction, null guards, AST and loader entry points")
final class SemanticAnalyzerTest {

    private static ScriptLoader stubLoader() {
        return path -> script();
    }

    /** Every symbol the analysis registered, by name — comparable across runs. */
    private static List<String> symbolNames(SemanticResult result) {
        return result.model().orElseThrow().symbolTable().allSymbols().stream()
                .map(s -> s.namespace() + "." + s.declaredName())
                .toList();
    }

    // =========================================================================
    // Construction
    // =========================================================================

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Single-arg constructor accepts a valid loader")
        void singleArgConstructor() {
            assertThat(new SemanticAnalyzer(stubLoader())).isNotNull();
        }

        @Test
        @DisplayName("Two-arg constructor accepts loader and builtin provider")
        void twoArgConstructor() {
            assertThat(new SemanticAnalyzer(stubLoader(), BuiltinProvider.none())).isNotNull();
        }

        /**
         * The overload an embedder reaches for when the schemas live in a database rather
         * than in the script — public API, and until now constructed by nothing.
         */
        @Test
        @DisplayName("Two-arg constructor accepts loader and catalog provider")
        void loaderAndCatalogConstructor() {
            assertThat(new SemanticAnalyzer(stubLoader(), CatalogProvider.NONE)).isNotNull();
        }

        @Test
        @DisplayName("Null loader throws NullPointerException (single-arg)")
        void nullLoaderSingleArg() {
            assertThatThrownBy(() -> new SemanticAnalyzer(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("loader");
        }

        @Test
        @DisplayName("Null loader throws NullPointerException (two-arg)")
        void nullLoaderTwoArg() {
            assertThatThrownBy(() -> new SemanticAnalyzer(null, BuiltinProvider.none()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("loader");
        }

        @Test
        @DisplayName("Null builtin provider throws NullPointerException")
        void nullBuiltinProvider() {
            assertThatThrownBy(() -> new SemanticAnalyzer(stubLoader(), (BuiltinProvider) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("builtins");
        }
    }

    // =========================================================================
    // analyze(String) — null guard
    // =========================================================================

    @Nested
    @DisplayName("analyze(String)")
    class AnalyzeString {

        @Test
        @DisplayName("Null rootPath throws NullPointerException")
        void nullRootPath() {
            var analyzer = new SemanticAnalyzer(stubLoader());
            assertThatThrownBy(() -> analyzer.analyze((String) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("rootPath");
        }
    }

    // =========================================================================
    // analyze(Script) / analyze(Script, String) — the AST-first front door
    // =========================================================================

    @Nested
    @DisplayName("analyze(Script) — the AST-first entry point")
    class AnalyzeScript {

        @Test
        @DisplayName("Null script throws NullPointerException")
        void nullScript() {
            var analyzer = new SemanticAnalyzer(stubLoader());
            assertThatThrownBy(() -> analyzer.analyze((Script) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("script");
        }

        @Test
        @DisplayName("Null rootPath throws NullPointerException")
        void nullRootPath() {
            var analyzer = new SemanticAnalyzer(stubLoader());
            Script script = ScriptParser.parse("query { Users };");
            assertThatThrownBy(() -> analyzer.analyze(script, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("rootPath");
        }

        @Test
        @DisplayName("A hand-built Script — no parser involved — analyses to a model")
        void handBuiltScriptAnalyses() {
            // The contract external consumers use: an AST straight in, no text.
            var query = query("Users");
            var script = script("built",query);

            SemanticResult result = new SemanticAnalyzer(stubLoader()).analyze(script);

            assertThat(result.model()).isPresent();
            assertThat(result.model().orElseThrow().namespace()).isEqualTo("built");
        }

        @Test
        @DisplayName("Analysing a Script directly matches loading the same script by path")
        void matchesTheLoaderEntryPoint() {
            String src = """
                    namespace shop;
                    source Orders from csv("o.csv") {
                        schema: { order_id: NUMBER, amount: NUMBER } };
                    Totals := { γ order_id, SUM(amount) → total (Orders) };
                    query Totals;
                    """;
            Script script = ScriptParser.parse(src);
            var loader = new InMemoryScriptLoader(Map.of("main.relix", script));

            SemanticResult viaAst = new SemanticAnalyzer(loader).analyze(script);
            SemanticResult viaLoader = new SemanticAnalyzer(loader).analyze("main.relix");

            assertThat(viaAst).isFullyValid();
            assertThat(viaLoader).isFullyValid();
            assertThat(symbolNames(viaAst)).containsExactlyInAnyOrderElementsOf(
                    symbolNames(viaLoader));
        }

        @Test
        @DisplayName("Imports in an AST-supplied root still resolve through the loader")
        void importsStillResolveThroughTheLoader() {
            var loader = new InMemoryScriptLoader(Map.of("helpers.relix", helpers()));

            SemanticResult result = new SemanticAnalyzer(loader).analyze(importingRoot());

            assertThat(result.errors()).isEmpty();
            assertThat(result.model().orElseThrow().symbolTable().lookupRelation("Users"))
                    .isPresent();
        }

        @Test
        @DisplayName("Without a path, the root's relative imports resolve against the root of the tree")
        void relativeImportsResolveAgainstNothing() {
            // No path means no directory: './helpers.relix' normalizes to 'helpers.relix'.
            SemanticResult result = new SemanticAnalyzer(new InMemoryScriptLoader(Map.of()))
                    .analyze(importingRoot());

            assertThat(result.errors()).anySatisfy(
                    e -> assertThat(e.filePath()).isEqualTo("helpers.relix"));
        }

        @Test
        @DisplayName("A supplied rootPath is the base the root's relative imports resolve against")
        void suppliedRootPathIsTheImportBase() {
            var loader = new InMemoryScriptLoader(Map.of("shop/helpers.relix", helpers()));

            SemanticResult found =
                    new SemanticAnalyzer(loader).analyze(importingRoot(), "shop/main.relix");
            SemanticResult notFound =
                    new SemanticAnalyzer(loader).analyze(importingRoot(), "other/main.relix");

            assertThat(found.errors()).isEmpty();
            assertThat(notFound.errors()).anySatisfy(
                    e -> assertThat(e.filePath()).isEqualTo("other/helpers.relix"));
        }

        private Script helpers() {
            return ScriptParser.parse("""
                    source Users from csv("u.csv") {
                        schema: { id: NUMBER, name: STRING } };
                    """);
        }

        private Script importingRoot() {
            return ScriptParser.parse("""
                    import source Users from "./helpers.relix";
                    query { π name (Users) };
                    """);
        }
    }

    // =========================================================================
    // Synthetic path constants
    // =========================================================================

    @Test
    @DisplayName("STDIN_PATH constant is '<stdin>'")
    void stdinPathConstant() {
        assertThat(SemanticAnalyzer.STDIN_PATH).isEqualTo("<stdin>");
    }

    @Test
    @DisplayName("AST_PATH constant is '<ast>'")
    void astPathConstant() {
        assertThat(SemanticAnalyzer.AST_PATH).isEqualTo("<ast>");
    }
}

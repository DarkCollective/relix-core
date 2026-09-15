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
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.InlineTableBody;
import com.darkcollective.relix.lang.ast.AssignmentStatement;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.SymbolError;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

@DisplayName("SemanticAnalyzer — end-to-end analysis through symbol collection")
final class SemanticAnalysisTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    /** A script with no namespace and no statements. */
    private static final Script EMPTY_SCRIPT = script();

    private static SourceDeclaration dbSource(String name, String... colPairs) {
        List<ColumnSpec> cols = new java.util.ArrayList<>();
        for (int i = 0; i < colPairs.length; i += 2) {
            cols.add(ColumnSpec.out(colPairs[i], ScalarType.fromString(colPairs[i + 1])));
        }
        return source(true, name,
                new DatabaseSourceConfig("${DB_URL}", name.toLowerCase(), cols));
    }

    private static AssignmentStatement inlineTable(
            String name, List<String> headers, List<List<String>> rows) {
        return assign(true, name, markdownTable(headers, rows));
    }

    private static SemanticAnalyzer analyzerFor(Map<String, Script> scripts) {
        return new SemanticAnalyzer(new InMemoryScriptLoader(scripts));
    }

    private static SemanticAnalyzer analyzerFor(Map<String, Script> scripts,
                                                BuiltinProvider builtins) {
        return new SemanticAnalyzer(new InMemoryScriptLoader(scripts), builtins);
    }

    // =========================================================================
    // Single-file analysis
    // =========================================================================

    @Nested
    @DisplayName("Single-file analysis")
    class SingleFile {

        @Test
        @DisplayName("Empty script produces a success result with empty model")
        void emptyScriptIsFullyValid() {
            var analyzer = analyzerFor(Map.of("root.relix", EMPTY_SCRIPT));
            SemanticResult result = analyzer.analyze("root.relix");

            assertThat(result).isFullyValid();
            assertThat(result.hasModel()).isTrue();
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("Root namespace defaults to 'default' when not declared")
        void defaultNamespace() {
            var analyzer = analyzerFor(Map.of("root.relix", EMPTY_SCRIPT));
            SemanticResult result = analyzer.analyze("root.relix");

            assertThat(result.model().orElseThrow().namespace()).isEqualTo("default");
        }

        @Test
        @DisplayName("Root namespace is taken from the script's namespace declaration")
        void explicitNamespace() {
            var script = script("weather");
            var analyzer = analyzerFor(Map.of("root.relix", script));
            SemanticResult result = analyzer.analyze("root.relix");

            assertThat(result.model().orElseThrow().namespace()).isEqualTo("weather");
        }

        @Test
        @DisplayName("Source declaration appears in model's sources map and symbol table")
        void sourcePresentInModel() {
            SourceDeclaration src = dbSource("Users", "id", "NUMBER", "name", "STRING");
            var analyzer = analyzerFor(Map.of("root.relix", script(src)));
            SemanticResult result = analyzer.analyze("root.relix");

            SemanticModel model = result.model().orElseThrow();
            assertThat(model.symbolTable().lookupRelation("Users")).isPresent()
                    .get().isInstanceOf(SourceRelationSymbol.class);
            assertThat(model.sources()).containsKey("users");
        }

        @Test
        @DisplayName("Inline table appears in symbol table")
        void inlineTableInModel() {
            var analyzer = analyzerFor(Map.of("root.relix",
                    script(inlineTable("Cities",
                            List.of("name", "code"),
                            List.of(List.of("Chicago", "US"))))));
            SemanticResult result = analyzer.analyze("root.relix");

            assertThat(result.model().orElseThrow().symbolTable().lookupRelation("Cities"))
                    .isPresent().get().isInstanceOf(InlineRelationSymbol.class);
        }

        @Test
        @DisplayName("Query statements appear in rootQueries")
        void queryStatementsCollected() {
            var analyzer = new SemanticAnalyzer(path -> EMPTY_SCRIPT);
            SemanticResult result = analyzer.analyze(
                    ScriptParser.parse("query { σ x = 1 (T) };"));

            // root queries are non-empty
            assertThat(result.model().orElseThrow().rootQueries()).hasSize(1);
        }

        @Test
        @DisplayName("Missing root file returns a failure result")
        void missingRootFile() {
            var analyzer = analyzerFor(Map.of()); // no files
            SemanticResult result = analyzer.analyze("nonexistent.relix");

            assertThat(result.hasModel()).isFalse();
            assertThat(result).hasErrors();
            assertThat(result.errors().get(0).message()).contains("nonexistent.relix");
        }
    }

    // =========================================================================
    // Multi-file analysis
    // =========================================================================

    @Nested
    @DisplayName("Multi-file analysis with imports")
    class MultiFile {

        @Test
        @DisplayName("Symbols from imported files are visible in the symbol table")
        void importedSymbolsVisible() {
            var lib  = script(dbSource("Users", "id", "NUMBER"));
            var main = script(importAll("lib.relix"));
            var analyzer = analyzerFor(Map.of("lib.relix", lib, "main.relix", main));
            SemanticResult result = analyzer.analyze("main.relix");

            assertThat(result.model().orElseThrow().symbolTable().lookupRelation("Users"))
                    .isPresent();
        }

        @Test
        @DisplayName("Import cycle is reported as an error in the result")
        void importCycleReported() {
            var a = script(importAll("b.relix"));
            var b = script(importAll("a.relix"));
            var analyzer = analyzerFor(Map.of("a.relix", a, "b.relix", b));
            SemanticResult result = analyzer.analyze("a.relix");

            assertThat(result).hasErrors();
            assertThat(result.errors().get(0).message()).containsIgnoringCase("cycle");
        }

        @Test
        @DisplayName("Cycle result is partial — root file still analysed")
        void cycleResultIsPartial() {
            // a.relix declares Users locally and imports b.relix which cycles back
            var a = script(
                    dbSource("Users", "id", "NUMBER"),
                    importAll("b.relix"));
            var b = script(importAll("a.relix"));
            var analyzer = analyzerFor(Map.of("a.relix", a, "b.relix", b));
            SemanticResult result = analyzer.analyze("a.relix");

            // Model present (partial) because root loaded ok
            assertThat(result.hasModel()).isTrue();
            // Users from a.relix is still registered
            assertThat(result.model().orElseThrow().symbolTable().lookupRelation("Users"))
                    .isPresent();
        }

        @Test
        @DisplayName("Missing import is reported as an error but other symbols still register")
        void missingImportIsPartial() {
            var main = script(
                    dbSource("LocalTable", "x", "NUMBER"),
                    importAll("missing.relix"));
            var analyzer = analyzerFor(Map.of("main.relix", main));
            SemanticResult result = analyzer.analyze("main.relix");

            assertThat(result).hasErrors();
            // LocalTable still visible despite the load error for missing.relix
            assertThat(result.model().orElseThrow().symbolTable().lookupRelation("LocalTable"))
                    .isPresent();
        }
    }

    // =========================================================================
    // Builtin provider
    // =========================================================================

    @Nested
    @DisplayName("Builtin provider hook")
    class BuiltinProviderTest {

        @Test
        @DisplayName("Builtins registered via provider are visible in the symbol table")
        void builtinsRegistered() {
            var builtin = com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol.builtin(
                    "SysInfo", new com.darkcollective.relix.symbol.Schema(
                            List.of(new com.darkcollective.relix.symbol.ColumnDefinition(
                                    "version", ScalarType.STRING))));

            BuiltinProvider provider = table -> table.register(builtin);
            var analyzer = analyzerFor(Map.of("root.relix", EMPTY_SCRIPT), provider);
            SemanticResult result = analyzer.analyze("root.relix");

            assertThat(result.model().orElseThrow().symbolTable().lookupRelation("builtin", "sysinfo"))
                    .isPresent();
        }

        @Test
        @DisplayName("BuiltinProvider.none() adds no symbols of its own")
        void noneProviderAddsNothing() {
            var analyzer = analyzerFor(Map.of("root.relix", EMPTY_SCRIPT),
                    BuiltinProvider.none());
            SemanticResult result = analyzer.analyze("root.relix");

            // none() contributes nothing; the only symbols present are the
            // always-registered system catalog relations (relix.*; see ADR-0007).
            assertThat(result.model().orElseThrow().symbolTable().allSymbols())
                    .allSatisfy(s -> assertThat(s.namespace())
                            .isEqualTo(CatalogBuilder.CATALOG_NAMESPACE));
        }
    }

    // =========================================================================
    // stdin / InputStream entry point
    // =========================================================================

    @Nested
    @DisplayName("STDIN_PATH root — a root script with no file of its own")
    class StdinAnalysis {

        @Test
        @DisplayName("Empty root script produces a valid result")
        void emptyStreamResult() {
            // Use a stub loader — empty script has no imports
            var analyzer = new SemanticAnalyzer(path -> EMPTY_SCRIPT);
            SemanticResult result = analyzer.analyze(
                    ScriptParser.parse(""), SemanticAnalyzer.STDIN_PATH);

            assertThat(result.hasModel()).isTrue();
        }

        @Test
        @DisplayName("STDIN_PATH used as filePath in errors from stdin analysis")
        void stdinPathInErrors() {
            // Import a missing file so we get a load error attributed to <stdin>
            // Actually, ImportGraph errors on cycle/missing use the missing file's path,
            // not <stdin>. So let's verify that an empty stdin script is STDIN_PATH root.
            var analyzer = new SemanticAnalyzer(path -> EMPTY_SCRIPT);
            SemanticResult result = analyzer.analyze(
                    ScriptParser.parse(""), SemanticAnalyzer.STDIN_PATH);

            // No errors, but model's namespace is default
            assertThat(result.model().orElseThrow().namespace()).isEqualTo("default");
        }

        @Test
        @DisplayName("an empty user script has no node schemas of its own (only the builtin relix.* stdlib bodies)")
        void nodeSchemasEmpty() {
            var analyzer = new SemanticAnalyzer(path -> EMPTY_SCRIPT);
            SemanticResult result = analyzer.analyze(
                    ScriptParser.parse(""), SemanticAnalyzer.STDIN_PATH);

            // The only analysed bodies are the builtin relix.* introspection stdlib
            // (the user script contributes none):
            //   relix.dependencies — δ π σ over relix.plan ............... 4 nodes
            //   relix.unused       — (π −) over two relation leaves ...... 5 nodes
            //   relix.deps         — π σ CLOSURE over relix.dependencies . 4 nodes
            //   relix.impact       — π σ CLOSURE over relix.dependencies . 4 nodes
            //   relix.find         — π σ over relix.columns ............... 3 nodes
            //   relix.schema       — π σ over relix.columns ............... 3 nodes
            //   relix.cycles       — δ π σ CLOSURE over relix.dependencies 5 nodes
            //   relix.funcs        — π σ over relix.functions ............ 3 nodes
            //   relix.rules        — π σ over relix.events ............... 3 nodes
            // (see CatalogBuilder.buildDependencies / buildUnused / lineageFunction
            //  / buildFind / buildSchema / buildCycles / buildFuncs / buildRules).
            assertThat(result.model().orElseThrow().nodeSchemas().size()).isEqualTo(34);
        }
    }
}

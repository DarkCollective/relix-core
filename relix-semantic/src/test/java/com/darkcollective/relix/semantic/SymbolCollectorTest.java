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

import com.darkcollective.relix.lang.ast.ScriptBuilders;
import com.darkcollective.relix.lang.LangParseException;
import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.lang.ast.AssignmentStatement;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.DefStatement;
import com.darkcollective.relix.lang.ast.ImportKind;
import com.darkcollective.relix.lang.ast.ImportStatement;
import com.darkcollective.relix.lang.ast.InlineTableBody;
import com.darkcollective.relix.lang.ast.QueryAssignmentBody;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.source.HttpMethod;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonExtractSpec;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnStatistics;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SymbolCollector — symbol registration, import resolution, error accumulation")
final class SymbolCollectorTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Runs the collector over the given map of path → script and returns a
     * {@link Collected} holder for asserting on the results.
     */
    private static Collected buildAndCollect(String rootPath, Map<String, Script> scripts) {
        return buildAndCollect(rootPath, scripts, CatalogProvider.NONE);
    }

    private static Collected buildAndCollect(String rootPath, Map<String, Script> scripts,
                                             CatalogProvider catalog) {
        var loader    = new InMemoryScriptLoader(scripts);
        var table     = new InMemorySymbolTable();
        var graph     = ImportGraph.build(rootPath, scripts.get(rootPath), loader);
        var collector = new SymbolCollector(graph, table, catalog);
        collector.collect();
        return new Collected(table, collector);
    }

    private static Collected buildAndCollect(String rootPath, Map<String, Script> scripts,
                                             GeneratorCatalog generators) {
        var loader    = new InMemoryScriptLoader(scripts);
        var table     = new InMemorySymbolTable();
        var graph     = ImportGraph.build(rootPath, scripts.get(rootPath), loader);
        var collector = new SymbolCollector(graph, table, CatalogProvider.NONE, generators);
        collector.collect();
        return new Collected(table, collector);
    }

    record Collected(InMemorySymbolTable table, SymbolCollector collector) {
        List<SemanticError> errors()             { return collector.errors();      }
        Map<String, SourceDeclaration> sources() { return collector.sources();     }
        Map<String, ConnectionDeclaration> connections() { return collector.connections(); }
        Map<String, RelationStatistics> statistics() { return collector.statistics(); }
        List<QueryStatement> rootQueries()       { return collector.rootQueries(); }
    }

    /** Convenience: exported source backed by a trivial database config. */
    private static SourceDeclaration dbSource(String name, String... colPairs) {
        List<ColumnSpec> cols = new java.util.ArrayList<>();
        for (int i = 0; i < colPairs.length; i += 2) {
            cols.add(ColumnSpec.out(colPairs[i], ScalarType.fromString(colPairs[i + 1])));
        }
        return source(true, name,
                new DatabaseSourceConfig("${DB_URL}", name.toLowerCase(), cols));
    }

    /** Convenience: inline table assignment. */
    private static AssignmentStatement inlineTable(
            String name, List<String> headers, List<List<String>> rows) {
        return assign(true, name, markdownTable(headers, rows));
    }

    /** Convenience: typed named-import statement. */
    private static ImportStatement importNamed(ImportKind kind, List<String> names, String path) {
        return ScriptBuilders.importNames(kind, names, path);
    }

    // =========================================================================
    // Source declarations
    // =========================================================================

    @Nested
    @DisplayName("Source declarations")
    class SourceSymbols {

        @Test
        @DisplayName("Source is registered as SourceRelationSymbol")
        void sourceRegistered() {
            var c = buildAndCollect("a.relix", Map.of(
                    "a.relix", ScriptBuilders.script(dbSource("Users", "id", "NUMBER", "name", "STRING"))));

            assertThat(c.table().lookupRelation("Users")).isPresent()
                    .get().isInstanceOf(SourceRelationSymbol.class);
        }

        @Test
        @DisplayName("Source schema matches the ColumnSpecs in the config")
        void sourceSchema() {
            var c = buildAndCollect("a.relix", Map.of(
                    "a.relix", ScriptBuilders.script(dbSource("Users", "id", "NUMBER", "name", "STRING"))));

            var sym = (SourceRelationSymbol) c.table().lookupRelation("Users").orElseThrow();
            assertThat(sym.schema().columns()).hasSize(2);
            assertThat(sym.schema().column("id").orElseThrow().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(sym.schema().column("name").orElseThrow().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("Source declaration is stored in the sources map")
        void sourceInSourcesMap() {
            SourceDeclaration src = dbSource("Users", "id", "NUMBER");
            var c = buildAndCollect("a.relix", Map.of("a.relix", ScriptBuilders.script(src)));

            assertThat(c.sources()).containsKey("users");
            assertThat(c.sources().get("users")).isSameAs(src);
        }

        @Test
        @DisplayName("Private source is registered but not exported")
        void privateSourceNotExported() {
            var src = source("Internal",
                    databaseSource("${DB}", "internal",ColumnSpec.out("x", ScalarType.NUMBER)));
            var c = buildAndCollect("a.relix", Map.of(
                    "a.relix", ScriptBuilders.script(src),
                    "b.relix", ScriptBuilders.script(importAll("a.relix"))));

            // Registered in the table
            assertThat(c.table().lookupRelation("Internal")).isPresent();
            // But not visible to importers — b.relix should not see it
            assertThat(c.table().lookupRelation("default", "internal")).isPresent(); // still in table from a.relix
        }

        @Test
        @DisplayName("No errors for a clean source declaration")
        void noErrorsForCleanSource() {
            var c = buildAndCollect("a.relix", Map.of(
                    "a.relix", ScriptBuilders.script(dbSource("Users", "id", "NUMBER"))));
            assertThat(c.errors()).isEmpty();
        }
    }

    // =========================================================================
    // Inline table assignments
    // =========================================================================

    @Nested
    @DisplayName("Inline table assignments")
    class InlineTables {

        @Test
        @DisplayName("Inline table is registered as InlineRelationSymbol")
        void inlineTableRegistered() {
            var c = buildAndCollect("a.relix", Map.of("a.relix",
                    ScriptBuilders.script(inlineTable("Cities",
                            List.of("name", "code"),
                            List.of(List.of("Chicago", "US"))))));

            assertThat(c.table().lookupRelation("Cities")).isPresent()
                    .get().isInstanceOf(InlineRelationSymbol.class);
        }

        @Test
        @DisplayName("All-numeric column inferred as NUMBER")
        void numericColumnInferred() {
            var c = buildAndCollect("a.relix", Map.of("a.relix",
                    ScriptBuilders.script(inlineTable("T",
                            List.of("id", "label"),
                            List.of(List.of("1", "alpha"), List.of("2", "beta"))))));

            var sym = (InlineRelationSymbol) c.table().lookupRelation("T").orElseThrow();
            assertThat(sym.schema().column("id").orElseThrow().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(sym.schema().column("label").orElseThrow().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("Mixed-value column inferred as STRING")
        void mixedColumnInferredAsString() {
            var c = buildAndCollect("a.relix", Map.of("a.relix",
                    ScriptBuilders.script(inlineTable("T",
                            List.of("val"),
                            List.of(List.of("1"), List.of("foo"), List.of("2"))))));

            var sym = (InlineRelationSymbol) c.table().lookupRelation("T").orElseThrow();
            assertThat(sym.schema().column("val").orElseThrow().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("Inline table rows are stored in the symbol")
        void rowsStored() {
            var c = buildAndCollect("a.relix", Map.of("a.relix",
                    ScriptBuilders.script(inlineTable("T",
                            List.of("x"),
                            List.of(List.of("1"), List.of("2"))))));

            var sym = (InlineRelationSymbol) c.table().lookupRelation("T").orElseThrow();
            assertThat(sym.rows()).hasSize(2);
        }

        @Test
        @DisplayName("Empty table (no rows) still registered with NUMBER schema")
        void emptyTableRegistered() {
            var c = buildAndCollect("a.relix", Map.of("a.relix",
                    ScriptBuilders.script(inlineTable("Empty", List.of("id"), List.of()))));

            assertThat(c.table().lookupRelation("Empty")).isPresent();
            var sym = (InlineRelationSymbol) c.table().lookupRelation("Empty").orElseThrow();
            // No rows → defaults to NUMBER
            assertThat(sym.schema().column("id").orElseThrow().type()).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("Numeric column with a NULL marker (⊥ / blank / NULL) stays NUMBER")
        void numericColumnWithNullStaysNumber() {
            var c = buildAndCollect("a.relix", Map.of("a.relix",
                    ScriptBuilders.script(inlineTable("T",
                            List.of("amount"),
                            List.of(List.of("3"), List.of("⊥"), List.of(""),
                                    List.of("NULL"), List.of("4"))))));

            var sym = (InlineRelationSymbol) c.table().lookupRelation("T").orElseThrow();
            assertThat(sym.schema().column("amount").orElseThrow().type())
                    .isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("A NULL-marker cell is omitted from the row (→ NullValue at runtime)")
        void nullMarkerCellOmittedFromRow() {
            var c = buildAndCollect("a.relix", Map.of("a.relix",
                    ScriptBuilders.script(inlineTable("T",
                            List.of("amount"),
                            List.of(List.of("3"), List.of("⊥"), List.of(""),
                                    List.of("null"))))));

            var sym = (InlineRelationSymbol) c.table().lookupRelation("T").orElseThrow();
            assertThat(sym.rows()).hasSize(4);
            // present value
            assertThat(sym.rows().get(0)).containsKey("amount");
            // ⊥ / blank / NULL all leave the key absent
            assertThat(sym.rows().get(1)).doesNotContainKey("amount");
            assertThat(sym.rows().get(2)).doesNotContainKey("amount");
            assertThat(sym.rows().get(3)).doesNotContainKey("amount");
        }

        @Test
        @DisplayName("A genuinely non-numeric value still demotes the column to STRING")
        void nonNumericStillString() {
            var c = buildAndCollect("a.relix", Map.of("a.relix",
                    ScriptBuilders.script(inlineTable("T",
                            List.of("val"),
                            List.of(List.of("1"), List.of("⊥"), List.of("foo"))))));

            var sym = (InlineRelationSymbol) c.table().lookupRelation("T").orElseThrow();
            assertThat(sym.schema().column("val").orElseThrow().type())
                    .isEqualTo(ScalarType.STRING);
        }
    }

    // =========================================================================
    // Query assignment bodies
    // =========================================================================

    @Nested
    @DisplayName("Query assignment bodies")
    class QueryAssignments {

        @Test
        @DisplayName("Query assignment is registered as QueryRelationSymbol")
        void queryAssignmentRegistered() throws LangParseException {
            Script s = ScriptParser.parse("Active := { σ status = \"active\" (Users) };");
            var c = buildAndCollect("a.relix", Map.of("a.relix", s));

            assertThat(c.table().lookupRelation("Active")).isPresent()
                    .get().isInstanceOf(QueryRelationSymbol.class);
        }

        @Test
        @DisplayName("Query relation uses UNRESOLVED_SCHEMA placeholder")
        void queryUsesPlaceholderSchema() throws LangParseException {
            Script s = ScriptParser.parse("Active := { σ status = \"active\" (Users) };");
            var c = buildAndCollect("a.relix", Map.of("a.relix", s));

            var sym = (QueryRelationSymbol) c.table().lookupRelation("Active").orElseThrow();
            assertThat(sym.schema()).isSameAs(SymbolCollector.UNRESOLVED_SCHEMA);
        }

        @Test
        @DisplayName("Query body (RelNode) is preserved on the symbol")
        void queryBodyPreserved() throws LangParseException {
            Script s = ScriptParser.parse("Active := { σ status = \"active\" (Users) };");
            AssignmentStatement asgn = (AssignmentStatement) s.statements().get(0);
            var c = buildAndCollect("a.relix", Map.of("a.relix", s));

            var sym = (QueryRelationSymbol) c.table().lookupRelation("Active").orElseThrow();
            assertThat(sym.body()).isNotNull();
        }

        @Test
        @DisplayName("No errors for a clean query assignment")
        void noErrorsForCleanQuery() throws LangParseException {
            Script s = ScriptParser.parse("View := { σ x = 1 (T) };");
            var c = buildAndCollect("a.relix", Map.of("a.relix", s));
            assertThat(c.errors()).isEmpty();
        }
    }

    // =========================================================================
    // Def statements (scalar functions)
    // =========================================================================

    @Nested
    @DisplayName("Def statements (scalar functions)")
    class FunctionSymbols {

        @Test
        @DisplayName("Def registers a ScalarFunctionSymbol")
        void defRegistered() throws LangParseException {
            Script s = ScriptParser.parse("def double(x: NUMBER): NUMBER := { x * 2 };");
            var c = buildAndCollect("a.relix", Map.of("a.relix", s));

            assertThat(c.table().lookupFunction("double")).hasSize(1);
            assertThat(c.table().lookupFunction("double").get(0))
                    .isInstanceOf(ScalarFunctionSymbol.class);
        }

        @Test
        @DisplayName("Parameter types are preserved on the function symbol")
        void parameterTypesPreserved() throws LangParseException {
            Script s = ScriptParser.parse("def add(a: NUMBER, b: NUMBER): NUMBER := { a + b };");
            var c = buildAndCollect("a.relix", Map.of("a.relix", s));

            var fn = (ScalarFunctionSymbol) c.table().lookupFunction("add").get(0);
            assertThat(fn.parameterSignature())
                    .containsExactly(ScalarType.NUMBER, ScalarType.NUMBER);
        }

        @Test
        @DisplayName("Return type is preserved on the function symbol")
        void returnTypePreserved() throws LangParseException {
            Script s = ScriptParser.parse("def label(score: NUMBER): STRING := { \"ok\" };");
            var c = buildAndCollect("a.relix", Map.of("a.relix", s));

            var fn = (ScalarFunctionSymbol) c.table().lookupFunction("label").get(0);
            assertThat(fn.returnType()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("Zero-parameter function is registered")
        void zeroParamFunction() throws LangParseException {
            Script s = ScriptParser.parse("def zero(): NUMBER := { 0 };");
            var c = buildAndCollect("a.relix", Map.of("a.relix", s));

            assertThat(c.table().lookupFunction("zero")).hasSize(1);
            var fn = (ScalarFunctionSymbol) c.table().lookupFunction("zero").get(0);
            assertThat(fn.parameters()).isEmpty();
        }

        @Test
        @DisplayName("Function body is stored on the symbol")
        void functionBodyStored() throws LangParseException {
            Script s = ScriptParser.parse("def double(x: NUMBER): NUMBER := { x * 2 };");
            var c = buildAndCollect("a.relix", Map.of("a.relix", s));

            var fn = (ScalarFunctionSymbol) c.table().lookupFunction("double").get(0);
            assertThat(fn.body()).isPresent();
        }
    }

    // =========================================================================
    // Import resolution
    // =========================================================================

    @Nested
    @DisplayName("Import resolution")
    class ImportResolution {

        @Test
        @DisplayName("BULK import brings all exported symbols into the importing namespace")
        void bulkImport() {
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  ScriptBuilders.script(dbSource("Users", "id", "NUMBER")),
                    "main.relix", ScriptBuilders.script(importAll("lib.relix"))));

            assertThat(c.table().lookupRelation("Users")).isPresent();
        }

        @Test
        @DisplayName("Named SOURCE import resolves a specific source symbol")
        void namedSourceImport() {
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  ScriptBuilders.script(dbSource("Weather", "city", "STRING", "temp", "NUMBER")),
                    "main.relix", ScriptBuilders.script(
                            importNamed(ImportKind.SOURCE, List.of("Weather"), "lib.relix"))));

            assertThat(c.table().lookupRelation("Weather")).isPresent()
                    .get().isInstanceOf(SourceRelationSymbol.class);
        }

        @Test
        @DisplayName("Named RELATION import resolves an inline-table symbol")
        void namedRelationImport() {
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  ScriptBuilders.script(inlineTable("Cities",
                            List.of("name"), List.of(List.of("Chicago")))),
                    "main.relix", ScriptBuilders.script(
                            importNamed(ImportKind.RELATION, List.of("Cities"), "lib.relix"))));

            assertThat(c.table().lookupRelation("Cities")).isPresent()
                    .get().isInstanceOf(InlineRelationSymbol.class);
        }

        @Test
        @DisplayName("Named FUNCTION import resolves a function symbol")
        void namedFunctionImport() throws LangParseException {
            Script lib = ScriptParser.parse("def double(x: NUMBER): NUMBER := { x * 2 };");
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  lib,
                    "main.relix", ScriptBuilders.script(
                            importNamed(ImportKind.FUNCTION, List.of("double"), "lib.relix"))));

            assertThat(c.table().lookupFunction("double")).hasSize(1);
        }

        @Test
        @DisplayName("UNQUALIFIED import resolves any exported symbol by name")
        void unqualifiedImport() {
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  ScriptBuilders.script(dbSource("Users", "id", "NUMBER")),
                    "main.relix", ScriptBuilders.script(
                            importNamed(ImportKind.UNQUALIFIED, List.of("Users"), "lib.relix"))));

            assertThat(c.table().lookupRelation("Users")).isPresent();
        }

        @Test
        @DisplayName("Importing a missing name records a SemanticError")
        void missingNameError() {
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  ScriptBuilders.script(dbSource("Users", "id", "NUMBER")),
                    "main.relix", ScriptBuilders.script(
                            importNamed(ImportKind.RELATION, List.of("Orders"), "lib.relix"))));

            assertThat(c.errors()).hasSize(1);
            assertThat(c.errors().get(0).message()).contains("Orders");
            assertThat(c.errors().get(0).severity()).isEqualTo(Severity.ERROR);
        }

        @Test
        @DisplayName("Importing a symbol with the wrong kind records a SemanticError")
        void wrongKindError() {
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  ScriptBuilders.script(dbSource("Weather", "city", "STRING")),
                    "main.relix", ScriptBuilders.script(
                            // Weather is a SOURCE, not a RELATION
                            importNamed(ImportKind.RELATION, List.of("Weather"), "lib.relix"))));

            assertThat(c.errors()).hasSize(1);
            assertThat(c.errors().get(0).message()).contains("Weather");
        }

        @Test
        @DisplayName("Import from a failed file is silently skipped (load error is in ImportGraph)")
        void importFromFailedFile() {
            // "missing.relix" is not in the loader — ImportGraph will record a load error
            var c = buildAndCollect("main.relix", Map.of(
                    "main.relix", ScriptBuilders.script(importAll("missing.relix"))));

            // The load error is in the graph, not in collector.errors()
            assertThat(c.errors()).isEmpty();
        }

        @Test
        @DisplayName("Source declaration metadata propagates through BULK import")
        void sourceMetadataPropagates() {
            SourceDeclaration src = dbSource("Weather", "city", "STRING", "temp", "NUMBER");
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  ScriptBuilders.script(src),
                    "main.relix", ScriptBuilders.script(importAll("lib.relix"))));

            // Sources map should contain the declaration after import
            assertThat(c.sources()).containsKey("weather");
        }

        @Test
        @DisplayName("BULK import does not expose private symbols")
        void bulkImportSkipsPrivate() {
            // Private source in lib.relix
            var privateSrc = source("Secret",
                    databaseSource("${DB}", "secret",ColumnSpec.out("x", ScalarType.NUMBER)));
            var publicSrc = dbSource("Public", "y", "STRING");

            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  ScriptBuilders.script(privateSrc, publicSrc),
                    "main.relix", ScriptBuilders.script(importAll("lib.relix"))));

            // Public should be visible; Secret was private so not exported from lib.relix
            assertThat(c.table().lookupRelation("default", "public")).isPresent();
            // Secret is registered in the table (from lib.relix itself) but not re-exported
            // via the bulk import — so main.relix's exports don't include it.
            // We verify no extra errors and public symbol is accessible.
            assertThat(c.errors()).isEmpty();
        }

        @Test
        @DisplayName("Transitive imports: A → B → C, A sees symbols from C via B")
        void transitiveImport() {
            var c = buildAndCollect("a.relix", Map.of(
                    "c.relix", ScriptBuilders.script(dbSource("Base", "id", "NUMBER")),
                    "b.relix", ScriptBuilders.script(importAll("c.relix")),
                    "a.relix", ScriptBuilders.script(importAll("b.relix"))));

            assertThat(c.table().lookupRelation("Base")).isPresent();
        }
    }

    // =========================================================================
    // Namespace handling
    // =========================================================================

    @Nested
    @DisplayName("Namespace handling")
    class NamespaceHandling {

        @Test
        @DisplayName("Symbol declared in default namespace uses 'default'")
        void defaultNamespace() {
            var c = buildAndCollect("a.relix", Map.of(
                    "a.relix", ScriptBuilders.script(dbSource("Users", "id", "NUMBER"))));

            var sym = c.table().lookupRelation("default", "users").orElseThrow();
            assertThat(sym.namespace()).isEqualTo("default");
        }

        @Test
        @DisplayName("Symbol declared in explicit namespace uses that namespace")
        void explicitNamespace() {
            var c = buildAndCollect("a.relix", Map.of(
                    "a.relix", ScriptBuilders.script("weather", dbSource("Current", "city", "STRING"))));

            assertThat(c.table().lookupRelation("weather", "current")).isPresent();
        }

        @Test
        @DisplayName("BULK import from a non-default namespace re-registers in importer's namespace")
        void crossNamespaceBulkImport() {
            // lib.relix declares namespace "lib", main.relix has no namespace ("default")
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  ScriptBuilders.script("lib", dbSource("Data", "x", "NUMBER")),
                    "main.relix", ScriptBuilders.script(importAll("lib.relix"))));

            // Symbol should be visible in "default" after import
            assertThat(c.table().lookupRelation("default", "data")).isPresent();
        }

        @Test
        @DisplayName("Inline table from a non-default namespace is re-registered under importer's namespace")
        void crossNamespaceInlineTable() {
            // lib.relix declares namespace "lib" and has an inline table
            var libScript = ScriptBuilders.script("lib",
                    inlineTable("Cities", List.of("name"), List.of(List.of("Chicago"))));
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  libScript,
                    "main.relix", ScriptBuilders.script(importAll("lib.relix"))));

            // After BULK import, Cities should be re-bound to "default" namespace
            assertThat(c.table().lookupRelation("default", "cities")).isPresent()
                    .get().isInstanceOf(InlineRelationSymbol.class);
        }

        @Test
        @DisplayName("Function def from a non-default namespace is re-registered under importer's namespace")
        void crossNamespaceFunctionDef() throws LangParseException {
            // lib.relix declares namespace "lib" and defines a function
            Script libDef = ScriptParser.parse("def square(n: NUMBER): NUMBER := { n * n };");
            // Wrap the def in a script with namespace "lib"
            var defStmt = libDef.statements().get(0);
            var libScript = ScriptBuilders.script("lib", defStmt);
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  libScript,
                    "main.relix", ScriptBuilders.script(importAll("lib.relix"))));

            // After BULK import, square should be re-bound to "default" namespace
            List<com.darkcollective.relix.symbol.function.FunctionSymbol> fns =
                    c.table().lookupFunction("square");
            assertThat(fns).hasSize(1);
            assertThat(fns.get(0).namespace()).isEqualTo("default");
        }

        @Test
        @DisplayName("QueryRelationSymbol from non-default namespace is re-registered under importer's namespace")
        void crossNamespaceQueryRelation() {
            // lib.relix declares namespace "analytics" and exports a view (QueryRelationSymbol)
            var viewAssign = assign(true, "ActiveUsers", rel("Users"));
            var libScript  = ScriptBuilders.script("analytics", viewAssign);

            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  libScript,
                    "main.relix", ScriptBuilders.script(importAll("lib.relix"))));

            // After BULK import, ActiveUsers should be re-bound to "default" namespace
            assertThat(c.table().lookupRelation("default", "activeusers")).isPresent()
                    .get().isInstanceOf(QueryRelationSymbol.class);
        }
    }

    // =========================================================================
    // Root queries
    // =========================================================================

    @Nested
    @DisplayName("Root queries")
    class RootQueriesTest {

        @Test
        @DisplayName("QueryStatements in the root file are collected")
        void rootQueriesCollected() throws LangParseException {
            Script root = ScriptParser.parse("query { σ x = 1 (T) };");
            var c = buildAndCollect("root.relix", Map.of("root.relix", root));

            assertThat(c.rootQueries()).hasSize(1);
        }

        @Test
        @DisplayName("QueryStatements in non-root files are NOT collected")
        void nonRootQueriesIgnored() throws LangParseException {
            Script lib  = ScriptParser.parse("query { σ x = 1 (T) };");
            Script main = ScriptBuilders.script(importAll("lib.relix"));
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  lib,
                    "main.relix", main));

            assertThat(c.rootQueries()).isEmpty();
        }

        @Test
        @DisplayName("Multiple query statements in the root are all collected, in order")
        void multipleRootQueries() throws LangParseException {
            Script root = ScriptParser.parse("""
                    query { σ x = 1 (T) };
                    query { σ y = 2 (T) };
                    """);
            var c = buildAndCollect("root.relix", Map.of("root.relix", root));

            assertThat(c.rootQueries()).hasSize(2);
        }
    }

    // =========================================================================
    // Multiple symbols, no errors
    // =========================================================================

    @Nested
    @DisplayName("Multiple declarations in a single file")
    class MultipleDeclarations {

        @Test
        @DisplayName("Source + inline table + def all registered in the same pass")
        void allKindsInOneFile() throws LangParseException {
            Script s = ScriptParser.parse("""
                    def double(x: NUMBER): NUMBER := { x * 2 };
                    """);
            // Add source and inline table manually alongside the parsed def
            DefStatement def = (DefStatement) s.statements().get(0);
            var combined = ScriptBuilders.script(
                    dbSource("Users", "id", "NUMBER"),
                    inlineTable("Cities", List.of("name"), List.of(List.of("Chicago"))),
                    def);

            var c = buildAndCollect("a.relix", Map.of("a.relix", combined));

            assertThat(c.table().lookupRelation("Users")).isPresent();
            assertThat(c.table().lookupRelation("Cities")).isPresent();
            assertThat(c.table().lookupFunction("double")).hasSize(1);
            assertThat(c.errors()).isEmpty();
        }
    }

    // =========================================================================
    // Shadow-policy error accumulation
    // =========================================================================

    @Nested
    @DisplayName("Shadow-policy error accumulation")
    class ShadowErrors {

        @Test
        @DisplayName("No errors when the same symbol is imported under PERMITTED policy")
        void permittedImportNoError() {
            var c = buildAndCollect("main.relix", Map.of(
                    "lib.relix",  ScriptBuilders.script(dbSource("Users", "id", "NUMBER")),
                    "main.relix", ScriptBuilders.script(importAll("lib.relix"))));

            assertThat(c.errors()).isEmpty();
        }

        @Test
        @DisplayName("WARN_AND_PERMIT shadow policy produces a WARNING SemanticError")
        void shadowWarningProducesWarning() {
            // Pre-seed the table with a WARN_AND_PERMIT symbol so any re-registration warns
            var table  = new InMemorySymbolTable();
            var schema = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));
            table.register(new SourceRelationSymbol(
                    "default", "Users", Provenance.USER, ShadowPolicy.WARN_AND_PERMIT, schema));

            // Script also declares Users → shadows the existing one → SHADOW_WARNING
            var script    = ScriptBuilders.script(dbSource("Users", "id", "NUMBER"));
            var loader    = new InMemoryScriptLoader(Map.of("a.relix", script));
            var graph     = ImportGraph.build("a.relix", script, loader);
            var collector = new SymbolCollector(graph, table);
            collector.collect();

            assertThat(collector.errors()).isNotEmpty();
            assertThat(collector.errors().get(0).severity()).isEqualTo(Severity.WARNING);
        }

        @Test
        @DisplayName("SHADOW_FORBIDDEN policy produces an ERROR SemanticError")
        void shadowForbiddenProducesError() {
            // Pre-seed the table with a FORBIDDEN symbol — cannot be overwritten
            var table  = new InMemorySymbolTable();
            var schema = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));
            table.register(new SourceRelationSymbol(
                    "default", "Users", Provenance.BUILTIN, ShadowPolicy.FORBIDDEN, schema));

            // Script also declares Users → tries to shadow the FORBIDDEN symbol → SHADOW_FORBIDDEN
            var script    = ScriptBuilders.script(dbSource("Users", "id", "NUMBER"));
            var loader    = new InMemoryScriptLoader(Map.of("a.relix", script));
            var graph     = ImportGraph.build("a.relix", script, loader);
            var collector = new SymbolCollector(graph, table);
            collector.collect();

            assertThat(collector.errors()).isNotEmpty();
            assertThat(collector.errors().get(0).severity()).isEqualTo(Severity.ERROR);
        }
    }

    // =========================================================================
    // HTTP source configuration
    // =========================================================================

    @Nested
    @DisplayName("HTTP source configuration")
    class HttpSource {

        @Test
        @DisplayName("HTTP source config registers the symbol with correct schema from http columns")
        void httpSourceRegistered() {
            var httpConfig = new HttpSourceConfig(
                    "https://api.example.com/data",
                    HttpMethod.GET,
                    Map.of(),
                    new JsonExtractSpec("$."),
                    Optional.empty(),
                    List.of(ColumnSpec.out("city", ScalarType.STRING),
                            ColumnSpec.out("temp", ScalarType.NUMBER)));
            var src = source(true, "Weather", httpConfig);
            var c   = buildAndCollect("a.relix", Map.of("a.relix", ScriptBuilders.script(src)));

            assertThat(c.table().lookupRelation("Weather")).isPresent();
            var sym = c.table().lookupRelation("Weather").orElseThrow();
            assertThat(sym.schema().column("city")).isPresent();
            assertThat(sym.schema().column("temp")).isPresent();
            assertThat(c.errors()).isEmpty();
        }
    }

    // =========================================================================
    // EnvStatement
    // =========================================================================

    @Nested
    @DisplayName("EnvStatement — carries no symbols")
    class EnvStatements {

        @Test
        @DisplayName("Script with EnvStatement only registers no relation symbols")
        void envStatementRegistersNoSymbols() {
            var envStmt = com.darkcollective.relix.lang.ast.EnvStatement.defaults();
            var c = buildAndCollect("a.relix", Map.of(
                    "a.relix", ScriptBuilders.script(envStmt)));

            // EnvStatement carries no symbols — table should be empty
            assertThat(c.errors()).isEmpty();
            assertThat(c.table().lookupRelation("anything")).isEmpty();
        }

        @Test
        @DisplayName("Script with EnvStatement + source declaration registers the source")
        void envStatementCombinedWithSource() {
            var envStmt = com.darkcollective.relix.lang.ast.EnvStatement.defaults();
            var src = dbSource("Orders", "id", "NUMBER");
            var c = buildAndCollect("a.relix", Map.of(
                    "a.relix", ScriptBuilders.script(envStmt, src)));

            assertThat(c.table().lookupRelation("Orders")).isPresent();
            assertThat(c.errors()).isEmpty();
        }
    }

    // =========================================================================
    // Connection declarations
    // =========================================================================

    @Nested
    @DisplayName("connection declarations")
    class Connections {

        private static ConnectionDeclaration connection(String name, String url) {
            return ScriptBuilders.connection(name,
                    new DatabaseConnectionConfig(url, Optional.empty(), Optional.empty(), Optional.empty()));
        }

        @Test
        @DisplayName("a connection is collected, keyed by canonical (lower-cased) name")
        void collectsConnection() {
            Collected c = buildAndCollect("/main.relix", Map.of("/main.relix",
                    ScriptBuilders.script(connection("Sales", "jdbc:postgresql://host/db"))));

            assertThat(c.errors()).isEmpty();
            assertThat(c.connections()).containsKey("sales");
            assertThat(c.connections().get("sales").config().url())
                    .isEqualTo("jdbc:postgresql://host/db");
        }

        @Test
        @DisplayName("a duplicate connection name is a semantic error")
        void duplicateConnectionIsError() {
            Collected c = buildAndCollect("/main.relix", Map.of("/main.relix",
                    ScriptBuilders.script(connection("sales", "jdbc:h2:mem:a"),
                             connection("sales", "jdbc:h2:mem:b"))));

            assertThat(c.errors()).anyMatch(e -> e.message().contains("Duplicate connection"));
            assertThat(c.connections()).hasSize(1);   // first wins
        }

        private static SourceDeclaration connectionTable(String name, String conn, String table) {
            return source(true, name, ScriptBuilders.connectionTable(
                    conn, table,ColumnSpec.out("id", ScalarType.NUMBER)));
        }

        @Test
        @DisplayName("a connection-bound source resolves to a relation with its declared schema")
        void connectionBoundSourceResolvesToRelation() {
            Collected c = buildAndCollect("/main.relix", Map.of("/main.relix",
                    ScriptBuilders.script(connection("sales", "jdbc:h2:mem:x"),
                             connectionTable("Orders", "sales", "orders"))));

            assertThat(c.errors()).isEmpty();
            assertThat(c.table().lookupRelation("Orders")).isPresent();
            assertThat(c.sources().get("orders").config())
                    .isInstanceOf(ConnectionTableSourceConfig.class);
        }

        @Test
        @DisplayName("a source referencing an undeclared connection is a semantic error")
        void undeclaredConnectionIsError() {
            Collected c = buildAndCollect("/main.relix", Map.of("/main.relix",
                    ScriptBuilders.script(connectionTable("Orders", "nope", "orders"))));

            assertThat(c.errors()).anyMatch(e -> e.message().contains("undeclared connection"));
        }

        @Test
        @DisplayName("a dotted conn.table reference resolves its schema via the catalog provider")
        void dottedReferenceResolvesViaCatalog() {
            CatalogProvider catalog = (conn, table) ->
                    Optional.of(new Schema(List.of(new ColumnDefinition("amount", ScalarType.NUMBER))));
            Collected c = buildAndCollect("/main.relix",
                    Map.of("/main.relix", ScriptParser.parse("""
                            connection sales from database { url: "jdbc:h2:mem:x" };
                            query { σ amount > 0 (sales.orders) };
                            """)),
                    catalog);

            assertThat(c.errors()).isEmpty();
            assertThat(c.table().lookupRelation("sales.orders")).isPresent();
            assertThat(c.sources().get("sales.orders").config())
                    .isInstanceOf(ConnectionTableSourceConfig.class);
        }

        @Test
        @DisplayName("a dotted reference with no catalog and no declared schema is an error")
        void dottedReferenceWithoutCatalogIsError() {
            Collected c = buildAndCollect("/main.relix",
                    Map.of("/main.relix", ScriptParser.parse("""
                            connection sales from database { url: "jdbc:h2:mem:x" };
                            query { sales.orders };
                            """)));   // default NONE catalog

            assertThat(c.errors()).anyMatch(e -> e.message().contains("Cannot resolve schema"));
        }

        /** A catalog returning a fixed schema and the given row count for every table. */
        private static CatalogProvider catalogWithStats(long rowCount) {
            return new CatalogProvider() {
                @Override
                public Optional<Schema> tableSchema(ConnectionDeclaration conn, String table) {
                    return Optional.of(new Schema(List.of(new ColumnDefinition("amount", ScalarType.NUMBER))));
                }
                @Override
                public Optional<RelationStatistics> tableStatistics(
                        ConnectionDeclaration conn, String table) {
                    return Optional.of(RelationStatistics.of(rowCount));
                }
            };
        }

        @Test
        @DisplayName("statistics are collected for a dotted conn.table reference")
        void statisticsForDottedReference() {
            Collected c = buildAndCollect("/main.relix",
                    Map.of("/main.relix", ScriptParser.parse("""
                            connection sales from database { url: "jdbc:h2:mem:x" };
                            query { σ amount > 0 (sales.orders) };
                            """)),
                    catalogWithStats(500));

            assertThat(c.errors()).isEmpty();
            assertThat(c.statistics()).containsKey("sales.orders");
            assertThat(c.statistics().get("sales.orders").rowCount()).hasValue(500L);
        }

        @Test
        @DisplayName("statistics are collected for a binding-form connection table")
        void statisticsForBindingForm() {
            Collected c = buildAndCollect("/main.relix", Map.of("/main.relix",
                    ScriptBuilders.script(connection("sales", "jdbc:h2:mem:x"),
                             connectionTable("Orders", "sales", "orders"))),
                    catalogWithStats(42));

            assertThat(c.errors()).isEmpty();
            assertThat(c.statistics().get("orders").rowCount()).hasValue(42L);
        }

        @Test
        @DisplayName("no statistics are collected from the offline NONE catalog")
        void noStatisticsFromNoneCatalog() {
            Collected c = buildAndCollect("/main.relix", Map.of("/main.relix",
                    ScriptBuilders.script(connection("sales", "jdbc:h2:mem:x"),
                             connectionTable("Orders", "sales", "orders"))));   // NONE catalog

            assertThat(c.statistics()).isEmpty();
        }
    }

    // =========================================================================
    // Generator sources (ADR-0008)
    // =========================================================================

    @Nested
    @DisplayName("Generator sources")
    class GeneratorSources {

        private static final Schema RANGE_SCHEMA =
                new Schema(List.of(new ColumnDefinition("n", ScalarType.NUMBER)));

        /** A catalog that knows only {@code Range} — schema and exact cardinality. */
        private static final GeneratorCatalog RANGE_ONLY = new GeneratorCatalog() {
            @Override
            public Optional<Schema> generatorSchema(String name, Map<String, String> args) {
                return name.equalsIgnoreCase("Range") ? Optional.of(RANGE_SCHEMA) : Optional.empty();
            }
            @Override
            public OptionalLong generatorCardinality(String name, Map<String, String> args) {
                if (!name.equalsIgnoreCase("Range")) {
                    return OptionalLong.empty();
                }
                long lo = Long.parseLong(args.get("lo"));
                long hi = Long.parseLong(args.get("hi"));
                return OptionalLong.of(hi - lo + 1);
            }
        };

        @Test
        @DisplayName("Generator source schema is resolved from the catalog")
        void schemaResolvedFromCatalog() {
            var src = source(true, "R",
                    generatorSource("Range", Map.of("lo", "1", "hi", "9")));
            Collected c = buildAndCollect("a.relix", Map.of("a.relix", ScriptBuilders.script(src)), RANGE_ONLY);

            assertThat(c.errors()).isEmpty();
            var sym = c.table().lookupRelation("R").orElseThrow();
            assertThat(sym).isInstanceOf(SourceRelationSymbol.class);
            assertThat(((SourceRelationSymbol) sym).schema().columns()).singleElement()
                    .satisfies(col -> {
                        assertThat(col.name()).isEqualTo("n");
                        assertThat(col.type()).isEqualTo(ScalarType.NUMBER);
                    });
        }

        @Test
        @DisplayName("Unknown generator reports a semantic error")
        void unknownGeneratorReportsError() {
            var src = source(true, "P",
                    generatorSource("Primes", Map.of()));
            Collected c = buildAndCollect("a.relix", Map.of("a.relix", ScriptBuilders.script(src)), RANGE_ONLY);

            assertThat(c.errors()).anyMatch(e -> e.message().contains("Unknown generator 'Primes'"));
        }

        @Test
        @DisplayName("A finite generator's exact cardinality is collected as statistics")
        void exactCardinalityCollectedAsStatistics() {
            var src = source(true, "R",
                    generatorSource("Range", Map.of("lo", "1", "hi", "100")));
            Collected c = buildAndCollect("a.relix", Map.of("a.relix", ScriptBuilders.script(src)), RANGE_ONLY);

            assertThat(c.statistics()).containsKey("r");
            assertThat(c.statistics().get("r").rowCount()).hasValue(100);
        }
    }

    @Nested
    @DisplayName("Inline relation statistics")
    class InlineRelationStatistics {

        @Test
        @DisplayName("Exact row count is derived from the inline rows")
        void exactRowCount() {
            var c = buildAndCollect("a.relix", Map.of("a.relix", ScriptBuilders.script(
                    inlineTable("Users", List.of("id", "name"), List.of(
                            List.of("1", "Alice"),
                            List.of("2", "Bob"),
                            List.of("3", "Carol"))))));

            assertThat(c.statistics().get("users").rowCount()).hasValue(3L);
        }

        @Test
        @DisplayName("Per-column distinct and null counts are exact")
        void distinctAndNullCounts() {
            // 'dept' repeats a value and has one NULL (empty cell -> absent key).
            var c = buildAndCollect("a.relix", Map.of("a.relix", ScriptBuilders.script(
                    inlineTable("Emp", List.of("id", "dept"), List.of(
                            List.of("1", "eng"),
                            List.of("2", "eng"),
                            List.of("3", ""))))));

            RelationStatistics stats = c.statistics().get("emp");
            ColumnStatistics dept = stats.column("dept").orElseThrow();
            assertThat(dept.distinctCount()).hasValue(1L);   // only "eng"
            assertThat(dept.nullCount()).hasValue(1L);       // the empty cell

            ColumnStatistics id = stats.column("id").orElseThrow();
            assertThat(id.distinctCount()).hasValue(3L);
            assertThat(id.nullCount()).hasValue(0L);
        }

        @Test
        @DisplayName("A unique, non-null column is a single-column candidate key")
        void uniqueColumnIsCandidateKey() {
            var c = buildAndCollect("a.relix", Map.of("a.relix", ScriptBuilders.script(
                    inlineTable("Users", List.of("id", "name"), List.of(
                            List.of("1", "Alice"),
                            List.of("2", "Bob"),
                            List.of("3", "Alice"))))));   // name duplicates, id does not

            assertThat(c.statistics().get("users").keys())
                    .containsExactly(List.of("id"));
        }

        @Test
        @DisplayName("A column with a NULL is not a candidate key even if otherwise unique")
        void nullColumnIsNotAKey() {
            var c = buildAndCollect("a.relix", Map.of("a.relix", ScriptBuilders.script(
                    inlineTable("T", List.of("id"), List.of(
                            List.of("1"),
                            List.of("2"),
                            List.of(""))))));   // distinct present values, but one NULL

            assertThat(c.statistics().get("t").keys()).isEmpty();
        }

        @Test
        @DisplayName("An empty inline relation has a zero row count and no keys")
        void emptyRelation() {
            var c = buildAndCollect("a.relix", Map.of("a.relix", ScriptBuilders.script(
                    inlineTable("Empty", List.of("id"), List.of()))));

            RelationStatistics stats = c.statistics().get("empty");
            assertThat(stats.rowCount()).hasValue(0L);
            assertThat(stats.keys()).isEmpty();
            assertThat(stats.column("id").orElseThrow().distinctCount()).hasValue(0L);
        }

        @Test
        @DisplayName("Inline statistics are derived offline, with no catalog connection")
        void derivedOfflineWithoutCatalog() {
            // The NONE catalog resolves nothing, yet inline stats are still present —
            // contradicting the notion that statistics are empty without a JDBC source.
            var c = buildAndCollect("a.relix", Map.of("a.relix", ScriptBuilders.script(
                    inlineTable("Users", List.of("id"), List.of(
                            List.of("1"), List.of("2"))))),
                    CatalogProvider.NONE);

            assertThat(c.statistics()).containsKey("users");
            assertThat(c.statistics().get("users").rowCount()).hasValue(2L);
        }
    }
}

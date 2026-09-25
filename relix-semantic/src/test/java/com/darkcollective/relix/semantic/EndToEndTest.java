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

import com.darkcollective.relix.semantic.internal.BuiltinProvider;
import com.darkcollective.relix.semantic.internal.InMemoryScriptLoader;
import com.darkcollective.relix.semantic.internal.SemanticAnalyzer;
import com.darkcollective.relix.semantic.internal.SemanticResult;
import com.darkcollective.relix.semantic.internal.SymbolCollector;
import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * End-to-end integration tests for the full five-phase semantic analysis pipeline.
 *
 * <p>Every test drives real {@code .relix} source text through
 * {@link ScriptParser} → {@link SemanticAnalyzer} and then asserts on the
 * resulting {@link SemanticResult} / {@link SemanticModel}.  No AST nodes are
 * constructed by hand; all input is plain-text relix source.
 */
@DisplayName("End-to-end semantic analysis — full pipeline integration")
final class EndToEndTest {

    // =========================================================================
    // Test helpers
    // =========================================================================

    // =========================================================================
    // 1. Empty / namespace scripts
    // =========================================================================

    @Nested
    @DisplayName("Empty and namespace scripts")
    class EmptyAndNamespace {

        @Test
        @DisplayName("Empty script is fully valid, namespace defaults to 'default'")
        void emptyScript() {
            SemanticResult r = analyze("");
            assertThat(r).isFullyValid();
            assertThat(r.model().orElseThrow().namespace()).isEqualTo("default");
            assertThat(r.model().orElseThrow().rootQueries()).isEmpty();
            assertThat(r.model().orElseThrow().sources()).isEmpty();
        }

        @Test
        @DisplayName("Namespace declaration is reflected in the model")
        void namespaceDeclaration() {
            SemanticResult r = analyze("namespace weather;");
            assertThat(r.model().orElseThrow().namespace()).isEqualTo("weather");
        }

        @Test
        @DisplayName("Comments-only script is fully valid")
        void commentsOnly() {
            SemanticResult r = analyze("-- just a comment\n/* block */");
            assertThat(r).isFullyValid();
        }
    }

    // =========================================================================
    // 2. Source declarations
    // =========================================================================

    @Nested
    @DisplayName("Source declarations — symbol registration and sources map")
    class SourceDeclarations {

        private static final String DB_USERS = """
                source Users from database {
                    url:   "${DB_URL}",
                    table: "users",
                    schema: {
                        id:   NUMBER,
                        name: STRING
                    }
                };
                """;

        @Test
        @DisplayName("Database source is registered as SourceRelationSymbol")
        void databaseSourceInSymbolTable() {
            SemanticResult r = analyze(DB_USERS);

            assertThat(r).isFullyValid();
            assertThat(r.model().orElseThrow().symbolTable().lookupRelation("Users"))
                    .isPresent()
                    .get().isInstanceOf(SourceRelationSymbol.class);
        }

        @Test
        @DisplayName("Database source appears in the model's sources map")
        void databaseSourceInSourcesMap() {
            SemanticResult r = analyze(DB_USERS);
            assertThat(r.model().orElseThrow().sources()).containsKey("users");
        }

        @Test
        @DisplayName("Database source schema has correct column types")
        void databaseSourceSchema() {
            SemanticResult r = analyze(DB_USERS);
            var sym = r.model().orElseThrow().symbolTable()
                    .lookupRelation("Users").orElseThrow();

            assertThat(sym.schema().column("id").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(sym.schema().column("name").get().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("CSV file source is registered as SourceRelationSymbol")
        void csvFileSource() {
            String src = """
                    source Products from csv("./products.csv") {
                        schema: { id: NUMBER, name: STRING, price: NUMBER }
                    };
                    """;
            SemanticResult r = analyze(src);

            assertThat(r).isFullyValid();
            assertThat(r.model().orElseThrow().symbolTable().lookupRelation("Products"))
                    .isPresent()
                    .get().isInstanceOf(SourceRelationSymbol.class);
            assertThat(r.model().orElseThrow().sources()).containsKey("products");
        }

        @Test
        @DisplayName("Multiple source declarations are all registered")
        void multipleSources() {
            String src = """
                    source Users from database { url: "${DB}", table: "u", schema: { id: NUMBER } };
                    source Orders from database { url: "${DB}", table: "o", schema: { id: NUMBER } };
                    """;
            SemanticResult r = analyze(src);

            assertThat(r.model().orElseThrow().symbolTable().lookupRelation("Users")).isPresent();
            assertThat(r.model().orElseThrow().symbolTable().lookupRelation("Orders")).isPresent();
        }
    }

    // =========================================================================
    // 3. Inline tables
    // =========================================================================

    @Nested
    @DisplayName("Inline table assignments — schema inference from cell values")
    class InlineTables {

        @Test
        @DisplayName("Markdown inline table is registered as InlineRelationSymbol")
        void markdownTableRegistered() {
            String src = """
                    Cities := [
                    | name    | code |
                    |---------|------|
                    | Chicago | US   |
                    | London  | UK   |
                    ];
                    """;
            SemanticResult r = analyze(src);

            assertThat(r).isFullyValid();
            assertThat(r.model().orElseThrow().symbolTable().lookupRelation("Cities"))
                    .isPresent()
                    .get().isInstanceOf(InlineRelationSymbol.class);
        }

        @Test
        @DisplayName("All-string inline table columns get STRING type")
        void stringColumnsInferred() {
            String src = """
                    Cities := [
                    | name    | code |
                    |---------|------|
                    | Chicago | US   |
                    ];
                    """;
            SemanticResult r = analyze(src);
            var sym = r.model().orElseThrow().symbolTable().lookupRelation("Cities").orElseThrow();

            assertThat(sym.schema().column("name").get().type()).isEqualTo(ScalarType.STRING);
            assertThat(sym.schema().column("code").get().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("Numeric inline table columns get NUMBER type")
        void numericColumnsInferred() {
            String src = """
                    Prices := [
                    | item | price |
                    |------|-------|
                    | Pen  | 1.99  |
                    | Book | 12.50 |
                    ];
                    """;
            SemanticResult r = analyze(src);
            var sym = r.model().orElseThrow().symbolTable().lookupRelation("Prices").orElseThrow();

            assertThat(sym.schema().column("item").get().type()).isEqualTo(ScalarType.STRING);
            assertThat(sym.schema().column("price").get().type()).isEqualTo(ScalarType.NUMBER);
        }
    }

    // =========================================================================
    // 4. Named views (query assignments)
    // =========================================================================

    @Nested
    @DisplayName("Named views — schema inference end-to-end")
    class NamedViews {

        private static final String USERS_SOURCE = """
                source Users from database {
                    url: "${DB_URL}", table: "users",
                    schema: { id: NUMBER, name: STRING, dept_id: NUMBER }
                };
                """;

        @Test
        @DisplayName("Selection view has same schema as source")
        void selectionView() {
            String src = USERS_SOURCE + "ActiveUsers := { σ id > 0 (Users) };";
            SemanticResult r = analyze(src);

            assertThat(r).isFullyValid();
            var sym = r.model().orElseThrow().symbolTable()
                    .lookupRelation("ActiveUsers").orElseThrow();
            assertThat(sym).isInstanceOf(QueryRelationSymbol.class);
            assertThat(sym.schema().column("id")).isPresent();
            assertThat(sym.schema().column("name")).isPresent();
            assertThat(sym.schema().column("dept_id")).isPresent();
        }

        @Test
        @DisplayName("Projection view has only the projected columns")
        void projectionView() {
            String src = USERS_SOURCE + "UserNames := { π name (Users) };";
            SemanticResult r = analyze(src);

            assertThat(r).isFullyValid();
            var sym = r.model().orElseThrow().symbolTable()
                    .lookupRelation("UserNames").orElseThrow();
            assertThat(sym.schema().columns()).hasSize(1);
            assertThat(sym.schema().column("name").get().type()).isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("Rename view renames columns positionally")
        void renameView() {
            String src = USERS_SOURCE + "E := { ρ E(eid, ename, did) (Users) };";
            SemanticResult r = analyze(src);

            var sym = r.model().orElseThrow().symbolTable().lookupRelation("E").orElseThrow();
            assertThat(sym.schema().column("eid")).isPresent();
            assertThat(sym.schema().column("ename")).isPresent();
            assertThat(sym.schema().column("id")).isEmpty(); // old name gone
        }

        @Test
        @DisplayName("Aggregation view has groupby + aggregate result columns")
        void aggregationView() {
            String src = """
                    source Orders from database {
                        url: "${DB}", table: "orders",
                        schema: { customer_id: NUMBER, amount: NUMBER }
                    };
                    Summary := { γ customer_id, SUM(amount) → total (Orders) };
                    """;
            SemanticResult r = analyze(src);

            var sym = r.model().orElseThrow().symbolTable()
                    .lookupRelation("Summary").orElseThrow();
            assertThat(sym.schema().column("customer_id").get().type())
                    .isEqualTo(ScalarType.NUMBER);
            assertThat(sym.schema().column("total").get().type())
                    .isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("Chained views: second view resolves schema from the first")
        void chainedViews() {
            String src = USERS_SOURCE
                    + "ActiveUsers := { σ id > 0 (Users) };\n"
                    + "Names := { π name (ActiveUsers) };";
            SemanticResult r = analyze(src);

            assertThat(r).isFullyValid();
            var names = r.model().orElseThrow().symbolTable()
                    .lookupRelation("Names").orElseThrow();
            assertThat(names.schema().columns()).hasSize(1);
            assertThat(names.schema().column("name").get().type())
                    .isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("View body nodes are all annotated in nodeSchemas")
        void viewBodyAnnotated() {
            String src = USERS_SOURCE + "V := { π id, name (Users) };";
            SemanticResult r = analyze(src);

            // The projection and its RelationNode input should both be annotated
            assertThat(r.model().orElseThrow().nodeSchemas().size()).isGreaterThanOrEqualTo(2);
        }
    }

    // =========================================================================
    // 5. Root query statements
    // =========================================================================

    @Nested
    @DisplayName("Root query statements — rootQueries list and model population")
    class RootQueries {

        private static final String WITH_USERS = """
                source Users from database {
                    url: "${DB}", table: "users", schema: { id: NUMBER, name: STRING }
                };
                """;

        @Test
        @DisplayName("Named query target appears in rootQueries")
        void namedQueryTarget() {
            SemanticResult r = analyze(WITH_USERS + "query Users;");

            assertThat(r).isFullyValid();
            assertThat(r.model().orElseThrow().rootQueries()).hasSize(1);
        }

        @Test
        @DisplayName("Multiple query statements all appear in rootQueries")
        void multipleQueryStatements() {
            SemanticResult r = analyze(WITH_USERS + "query Users;\nquery Users;");

            assertThat(r.model().orElseThrow().rootQueries()).hasSize(2);
        }

        @Test
        @DisplayName("Inline expression query target appears in rootQueries")
        void inlineExpressionQuery() {
            SemanticResult r = analyze(WITH_USERS + "query { π name (Users) };");

            assertThat(r.model().orElseThrow().rootQueries()).hasSize(1);
        }

        @Test
        @DisplayName("Inline query expression nodes are annotated in nodeSchemas")
        void inlineQueryAnnotated() {
            SemanticResult r = analyze(WITH_USERS + "query { π name (Users) };");

            assertThat(r.model().orElseThrow().nodeSchemas().size()).isGreaterThanOrEqualTo(2);
        }
    }

    // =========================================================================
    // 6. Multi-file imports
    // =========================================================================

    @Nested
    @DisplayName("Multi-file import scenarios")
    class MultiFileImports {

        @Test
        @DisplayName("Bulk import makes all exported symbols visible in importing file")
        void bulkImportVisible() {
            String lib = """
                    source Users from database {
                        url: "${DB}", table: "users", schema: { id: NUMBER }
                    };
                    """;
            String main = """
                    import "lib.relix";
                    query Users;
                    """;
            SemanticResult r = analyze(main, Map.of("lib.relix", lib));

            assertThat(r).isFullyValid();
            assertThat(r.model().orElseThrow().symbolTable().lookupRelation("Users"))
                    .isPresent();
        }

        @Test
        @DisplayName("Named import of a specific symbol makes it visible")
        void namedImport() {
            String lib = """
                    source Users from database {
                        url: "${DB}", table: "users", schema: { id: NUMBER }
                    };
                    source Orders from database {
                        url: "${DB}", table: "orders", schema: { id: NUMBER }
                    };
                    """;
            String main = "import source Users from \"lib.relix\";\nquery Users;";
            SemanticResult r = analyze(main, Map.of("lib.relix", lib));

            assertThat(r).isFullyValid();
            assertThat(r.model().orElseThrow().symbolTable().lookupRelation("Users"))
                    .isPresent();
            // Note: Orders is also in the global symbol table because SymbolCollector
            // processes lib.relix in full (dependency-first) before applying the named
            // import filter.  The named import only controls what gets re-exported from
            // the importing file; it does not un-register symbols from the global table.
        }

        @Test
        @DisplayName("Import cycle is reported as an error")
        void importCycle() {
            String a = "import \"b.relix\";";
            String b = "import \"a.relix\";";
            // Analyze a.relix as root
            Map<String, com.darkcollective.relix.lang.ast.Script> scripts = new LinkedHashMap<>();
            scripts.put("a.relix", ScriptParser.parse(a));
            scripts.put("b.relix", ScriptParser.parse(b));
            SemanticResult r;
            try {
                r = new SemanticAnalyzer(new InMemoryScriptLoader(scripts))
                        .analyze("a.relix");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message()).containsIgnoringCase("cycle"));
        }

        @Test
        @DisplayName("Missing import file is an error; local symbols still registered")
        void missingImport() {
            String main = """
                    source LocalTable from database {
                        url: "${DB}", table: "t", schema: { id: NUMBER }
                    };
                    import "missing.relix";
                    """;
            SemanticResult r = analyze(main, Map.of());

            assertThat(r).hasErrors();
            // LocalTable was still collected
            assertThat(r.model().orElseThrow().symbolTable().lookupRelation("LocalTable"))
                    .isPresent();
        }

        @Test
        @DisplayName("Transitive import: A imports B which imports C; C's symbols visible in A")
        void transitiveImport() {
            String c = """
                    source Users from database {
                        url: "${DB}", table: "users", schema: { id: NUMBER }
                    };
                    """;
            String b = "import \"c.relix\";";
            String a = "import \"b.relix\";\nquery Users;";
            SemanticResult r = analyze(a, Map.of("b.relix", b, "c.relix", c));

            assertThat(r).isFullyValid();
            assertThat(r.model().orElseThrow().symbolTable().lookupRelation("Users"))
                    .isPresent();
        }
    }

    // =========================================================================
    // 7. Validation errors surfaced end-to-end
    // =========================================================================

    @Nested
    @DisplayName("Validation errors — end-to-end via full pipeline")
    class ValidationErrors {

        @Test
        @DisplayName("Referencing an undefined relation produces an error")
        void undefinedRelation() {
            SemanticResult r = analyze("Bad := { σ x = 1 (GhostTable) };");

            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message()).contains("GhostTable"));
        }

        @Test
        @DisplayName("Named query target for undefined symbol produces an error")
        void undefinedNamedTarget() {
            SemanticResult r = analyze("query NoSuchRelation;");

            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message()).contains("NoSuchRelation"));
        }

        @Test
        @DisplayName("Set operation with width-mismatched schemas produces an error")
        void setOpWidthMismatch() {
            String src = """
                    source A from database { url: "${DB}", table: "a", schema: { x: NUMBER } };
                    source B from database { url: "${DB}", table: "b", schema: { x: NUMBER, y: STRING } };
                    Bad := { A ∪ B };
                    """;
            SemanticResult r = analyze(src);

            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message()).containsIgnoringCase("width"));
        }

        @Test
        @DisplayName("Set operation with type-mismatched schemas produces an error")
        void setOpTypeMismatch() {
            String src = """
                    source A from database { url: "${DB}", table: "a", schema: { x: NUMBER } };
                    source B from database { url: "${DB}", table: "b", schema: { x: STRING } };
                    Bad := { A ∪ B };
                    """;
            SemanticResult r = analyze(src);

            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message()).containsIgnoringCase("mismatch"));
        }

        @Test
        @DisplayName("Projection of a non-existent column produces an error")
        void projectionMissingColumn() {
            String src = """
                    source Users from database { url: "${DB}", table: "u", schema: { id: NUMBER } };
                    Bad := { π ghost_col (Users) };
                    """;
            SemanticResult r = analyze(src);

            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message()).contains("ghost_col"));
        }

        @Test
        @DisplayName("Sort by a non-existent column produces an error")
        void sortMissingColumn() {
            String src = """
                    source Users from database { url: "${DB}", table: "u", schema: { id: NUMBER } };
                    Bad := { τ ghost_col (Users) };
                    """;
            SemanticResult r = analyze(src);

            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message()).contains("ghost_col"));
        }

        @Test
        @DisplayName("Aggregation on a non-existent column produces an error")
        void aggregationMissingColumn() {
            String src = """
                    source Users from database { url: "${DB}", table: "u", schema: { id: NUMBER } };
                    Bad := { γ ghost_col, COUNT(id) (Users) };
                    """;
            SemanticResult r = analyze(src);

            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message()).contains("ghost_col"));
        }

        @Test
        @DisplayName("Result has a model even when errors are present (partial result)")
        void partialResultOnError() {
            SemanticResult r = analyze("query NotDefined;");

            assertThat(r).hasErrors();
            assertThat(r.hasModel()).isTrue();
        }

        @Test
        @DisplayName("Selection predicate referencing a non-existent column produces an error")
        void selectionPredicateMissingColumn() {
            String src = """
                    source Users from database { url: "${DB}", table: "u",
                        schema: { id: NUMBER, name: STRING } };
                    Bad := { σ nonexistent_col = 5 (Users) };
                    """;
            SemanticResult r = analyze(src);

            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message())
                            .containsIgnoringCase("selection")
                            .contains("nonexistent_col"));
        }

        @Test
        @DisplayName("Rename with wrong attribute count produces an error")
        void renameArityMismatch() {
            // Users has 3 columns (id, name, dept_id); supply only 2 rename aliases
            String src = """
                    source Users from database { url: "${DB}", table: "u",
                        schema: { id: NUMBER, name: STRING, dept_id: NUMBER } };
                    E := { ρ E(eid, ename) (Users) };
                    """;
            SemanticResult r = analyze(src);

            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message())
                            .containsIgnoringCase("rename")
                            .contains("2")
                            .contains("3"));
        }
    }

    // =========================================================================
    // 8. Builtin provider
    // =========================================================================

    @Nested
    @DisplayName("Builtin provider — symbols visible without imports")
    class BuiltinProviderIntegration {

        @Test
        @DisplayName("Builtin relation registered via provider is accessible")
        void builtinRelationVisible() {
            Schema sysSchema = new Schema(List.of(
                    new ColumnDefinition("version", ScalarType.STRING)));
            DatabaseRelationSymbol sysInfo = DatabaseRelationSymbol.builtin("SysInfo", sysSchema);

            BuiltinProvider provider = table -> table.register(sysInfo);
            var analyzer = new SemanticAnalyzer(
                    new InMemoryScriptLoader(Map.of()), provider);
            SemanticResult r = analyzer.analyze(ScriptParser.parse("query SysInfo;"));

            assertThat(r).isFullyValid();
            assertThat(r.model().orElseThrow().symbolTable()
                    .lookupRelation("builtin", "sysinfo")).isPresent();
        }
    }

    // =========================================================================
    // 9. Complex full-model integration
    // =========================================================================

    @Nested
    @DisplayName("Full model integration — complex multi-statement script")
    class FullModelIntegration {

        /**
         * A realistic multi-statement script that exercises every major feature:
         * namespace, source, inline table, named views (chained), and a root query.
         */
        private static final String FULL_SCRIPT = """
                namespace analytics;

                source Orders from database {
                    url:   "${DB_URL}",
                    table: "orders",
                    schema: {
                        order_id:    NUMBER,
                        customer_id: NUMBER,
                        amount:      NUMBER,
                        status:      STRING
                    }
                };

                Regions := [
                | region | code |
                |--------|------|
                | North  | N    |
                | South  | S    |
                ];

                PaidOrders := { σ status = "paid" (Orders) };
                OrderAmounts := { π customer_id, amount (PaidOrders) };
                CustomerTotals := { γ customer_id, SUM(amount) → total (OrderAmounts) };

                query CustomerTotals;
                """;

        @Test
        @DisplayName("Namespace is correctly set from declaration")
        void namespace() {
            SemanticResult r = analyze(FULL_SCRIPT);
            assertThat(r.model().orElseThrow().namespace()).isEqualTo("analytics");
        }

        @Test
        @DisplayName("Source is in the symbol table and sources map")
        void sourcePresent() {
            SemanticResult r = analyze(FULL_SCRIPT);
            SemanticModel m = r.model().orElseThrow();
            assertThat(m.symbolTable().lookupRelation("Orders"))
                    .isPresent().get().isInstanceOf(SourceRelationSymbol.class);
            assertThat(m.sources()).containsKey("orders");
        }

        @Test
        @DisplayName("Inline table is in the symbol table")
        void inlineTablePresent() {
            SemanticResult r = analyze(FULL_SCRIPT);
            assertThat(r.model().orElseThrow().symbolTable().lookupRelation("Regions"))
                    .isPresent().get().isInstanceOf(InlineRelationSymbol.class);
        }

        @Test
        @DisplayName("All intermediate named views are in the symbol table")
        void namedViewsPresent() {
            SemanticResult r = analyze(FULL_SCRIPT);
            var table = r.model().orElseThrow().symbolTable();
            assertThat(table.lookupRelation("PaidOrders")).isPresent();
            assertThat(table.lookupRelation("OrderAmounts")).isPresent();
            assertThat(table.lookupRelation("CustomerTotals")).isPresent();
        }

        @Test
        @DisplayName("CustomerTotals schema has correct columns from aggregation")
        void customerTotalsSchema() {
            SemanticResult r = analyze(FULL_SCRIPT);
            var sym = r.model().orElseThrow().symbolTable()
                    .lookupRelation("CustomerTotals").orElseThrow();

            assertThat(sym.schema().column("customer_id").get().type())
                    .isEqualTo(ScalarType.NUMBER);
            assertThat(sym.schema().column("total").get().type())
                    .isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("Root queries list has exactly one entry")
        void rootQueriesCount() {
            SemanticResult r = analyze(FULL_SCRIPT);
            assertThat(r.model().orElseThrow().rootQueries()).hasSize(1);
        }

        @Test
        @DisplayName("Schema annotations are populated for all RA expression bodies")
        void nodeSchemaAnnotations() {
            SemanticResult r = analyze(FULL_SCRIPT);
            // 3 named views (PaidOrders, OrderAmounts, CustomerTotals) × at least 2
            // nodes each (root + input leaf) → at least 6 annotations
            assertThat(r.model().orElseThrow().nodeSchemas().size()).isGreaterThanOrEqualTo(6);
        }

        @Test
        @DisplayName("Full script is fully valid — no errors")
        void fullyValid() {
            SemanticResult r = analyze(FULL_SCRIPT);
            assertThat(r).isFullyValid();
        }
    }

    // =========================================================================
    // 10. General recursion (FIX) — the full parse → infer → validate pipeline
    // =========================================================================

    @Nested
    @DisplayName("General recursion (FIX)")
    class GeneralRecursion {

        @Test
        @DisplayName("a linear, monotone recursive query is fully valid; schema = base")
        void validRecursiveQuery() {
            String src = """
                    Edges := [| src | dst |
                               | 1   | 2   |
                               | 2   | 3   |];
                    Reach := { FIX R (Edges, Edges ∪ R) };
                    query Reach;
                    """;
            SemanticResult r = analyze(src);
            assertThat(r).isFullyValid();
            var sym = r.model().orElseThrow().symbolTable()
                    .lookupRelation("Reach").orElseThrow();
            assertThat(sym.schema().column("src")).isPresent();
            assertThat(sym.schema().column("dst")).isPresent();
        }

        @Test
        @DisplayName("a non-monotone step (recursive ref right of −) is rejected")
        void nonMonotoneRejected() {
            String src = """
                    Edges := [| src | dst |
                               | 1   | 2   |];
                    Bad := { FIX R (Edges, Edges − R) };
                    query Bad;
                    """;
            SemanticResult r = analyze(src);
            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message()).contains("non-monotone position"));
        }

        @Test
        @DisplayName("a non-recursive FIX (no reference in the step) is rejected")
        void nonRecursiveRejected() {
            String src = """
                    Edges := [| src | dst |
                               | 1   | 2   |];
                    Bad := { FIX R (Edges, σ src > 0 (Edges)) };
                    query Bad;
                    """;
            SemanticResult r = analyze(src);
            assertThat(r).hasErrors();
            assertThat(r.errors()).anySatisfy(e ->
                    assertThat(e.message()).contains("is not recursive"));
        }
    }
}

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

import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.cost.Boundedness;
import com.darkcollective.relix.events.EventMetrics;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.relation.SystemRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyzeObserving;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * Spike tests for {@code relix.relations}, the first system catalog relation
 * (ADR-0007, Strategy B — inline relation in the reserved {@code relix} namespace).
 */
@DisplayName("System catalog relations (relix.*) — ADR-0007 spike")
final class CatalogBuilderTest {

    private static final String SCRIPT = """
            Users := [| id | name  |
                       | 1  | Alice |];
            Adults := { σ id > 0 (Users) };
            ByName := { τ name (Users) };
            """;

    // ─── helpers ──────────────────────────────────────────────────────────────

    /** The registered {@code relix.relations} system catalog symbol from a script's model. */
    private static SystemRelationSymbol catalog(String src) {
        SymbolTable table = analyze(src).model().orElseThrow().symbolTable();
        RelationSymbol sym = table.lookupRelation("relix", "relations").orElseThrow();
        return (SystemRelationSymbol) sym;
    }

    /** Catalog rows keyed by the relation name, each row a column→string map. */
    private static Map<String, Map<String, String>> rowsByName(SystemRelationSymbol catalog) {
        return catalog.rows().stream().collect(Collectors.toMap(
                r -> str(r.get("name")),
                r -> r.entrySet().stream().collect(
                        Collectors.toMap(Map.Entry::getKey, e -> str(e.getValue())))));
    }

    private static String str(Operand op) {
        return switch (op) {
            case NumberOperand n -> n.value();
            case StringOperand t -> t.value();
            case DurationOperand d -> d.value().toString();
            default -> throw new AssertionError(
                    "a catalog row holds an operand this helper cannot read: " + op);
        };
    }

    /**
     * The symbol table of {@code src} analysed against {@code provider}, for the
     * cases {@link SemanticFixtures#analyze} cannot reach: statistics a live
     * catalog would supply, which no inline relation can produce.
     */
    private static SymbolTable analyzeWithCatalog(String src, CatalogProvider provider) {
        return new SemanticAnalyzer(
                        new InMemoryScriptLoader(Map.of(SemanticAnalyzer.STDIN_PATH,
                                com.darkcollective.relix.lang.ScriptParser.parse(
                                        src, SemanticAnalyzer.STDIN_PATH))),
                        BuiltinProvider.none(), provider)
                .analyze(SemanticAnalyzer.STDIN_PATH)
                .model().orElseThrow().symbolTable();
    }

    /**
     * The symbol table of {@code src} analysed against {@code generators} — the seam that
     * answers whether a generator is infinite, and so the only way to reach an
     * {@code unbounded} or {@code unknown} row of {@code relix.relations}.
     */
    private static SymbolTable analyzeWithGenerators(String src, GeneratorCatalog generators) {
        return new SemanticAnalyzer(
                        new InMemoryScriptLoader(Map.of(SemanticAnalyzer.STDIN_PATH,
                                com.darkcollective.relix.lang.ScriptParser.parse(
                                        src, SemanticAnalyzer.STDIN_PATH))),
                        BuiltinProvider.none(), CatalogProvider.NONE, generators)
                .analyze(SemanticAnalyzer.STDIN_PATH)
                .model().orElseThrow().symbolTable();
    }

    /** {@code relix.relations} rows keyed by relation name, from a generator-aware run. */
    private static Map<String, Map<String, String>> rowsWithGenerators(
            String src, GeneratorCatalog generators) {
        return rowsByName((SystemRelationSymbol) analyzeWithGenerators(src, generators)
                .lookupRelation("relix", "relations").orElseThrow());
    }

    /**
     * A catalog of one-column generators: {@code Naturals} is infinite, {@code Mystery} is
     * one this session cannot place, everything else is finite.
     */
    private static final GeneratorCatalog GENERATORS = new GeneratorCatalog() {
        @Override
        public java.util.Optional<Schema> generatorSchema(String name, Map<String, String> args) {
            return java.util.Optional.of(new Schema(List.of(
                    new ColumnDefinition("n", ScalarType.NUMBER))));
        }

        @Override
        public Boundedness generatorBoundedness(String name, Map<String, String> args) {
            if (name.equalsIgnoreCase("Naturals")) {
                return Boundedness.UNBOUNDED;
            }
            return name.equalsIgnoreCase("Mystery") ? Boundedness.UNKNOWN : Boundedness.BOUNDED;
        }
    };

    /** A catalog answering one fixed schema and the given candidate keys for every table. */
    private static CatalogProvider keyedCatalog(List<List<String>> keys) {
        return new CatalogProvider() {
            @Override
            public java.util.Optional<Schema> tableSchema(
                    ConnectionDeclaration conn, String table) {
                return java.util.Optional.of(new Schema(List.of(
                        new ColumnDefinition("oid", ScalarType.NUMBER),
                        new ColumnDefinition("region", ScalarType.STRING),
                        new ColumnDefinition("amount", ScalarType.NUMBER))));
            }

            @Override
            public java.util.Optional<RelationStatistics> tableStatistics(
                    ConnectionDeclaration conn, String table) {
                return java.util.Optional.of(new RelationStatistics(
                        java.util.OptionalLong.of(10), Map.of(), keys));
            }
        };
    }

    /** Rows of {@code relix.functions} as column→string maps. */
    private static List<Map<String, String>> functionRows(String src) {
        SymbolTable table = analyze(src).model().orElseThrow().symbolTable();
        SystemRelationSymbol fns =
                (SystemRelationSymbol) table.lookupRelation("relix", "functions").orElseThrow();
        return fns.rows().stream()
                .map(r -> r.entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, e -> str(e.getValue()))))
                .collect(Collectors.toList());
    }

    /** {@code relation.column:type} entries of {@code relix.columns}. */
    private static List<String> columnEntries(String src) {
        SymbolTable table = analyze(src).model().orElseThrow().symbolTable();
        SystemRelationSymbol cols =
                (SystemRelationSymbol) table.lookupRelation("relix", "columns").orElseThrow();
        return cols.rows().stream()
                .map(r -> str(r.get("relation")) + "." + str(r.get("column")) + ":" + str(r.get("type")))
                .collect(Collectors.toList());
    }

    /** The registered {@code relix.dependencies} view symbol from a script's model. */
    private static QueryRelationSymbol dependenciesView(String src) {
        SymbolTable table = analyze(src).model().orElseThrow().symbolTable();
        return (QueryRelationSymbol) table.lookupRelation("relix", "dependencies").orElseThrow();
    }

    /** The registered {@code relix.version} system catalog symbol from a script's model. */
    private static SystemRelationSymbol versionCatalog(String src) {
        SymbolTable table = analyze(src).model().orElseThrow().symbolTable();
        return (SystemRelationSymbol) table.lookupRelation("relix", "version").orElseThrow();
    }

    /** The registered {@code relix.connections} system catalog symbol from a script's model. */
    private static SystemRelationSymbol connectionsCatalog(String src) {
        SymbolTable table = analyze(src).model().orElseThrow().symbolTable();
        return (SystemRelationSymbol) table.lookupRelation("relix", "connections").orElseThrow();
    }

    /**
     * The registered {@code relix.events} symbol, analysed with {@code events} as
     * the previous run's feed — supplied on the analyzer, not in the source.
     */
    private static SystemRelationSymbol eventsCatalog(String src, List<QueryEvent> events) {
        SymbolTable table = analyzeObserving(src, events)
                .model().orElseThrow().symbolTable();
        return (SystemRelationSymbol) table.lookupRelation("relix", "events").orElseThrow();
    }

    /** Rows of {@code relix.events} as column→string maps, in extent order. */
    private static List<Map<String, String>> eventRows(String src, List<QueryEvent> events) {
        return eventsCatalog(src, events).rows().stream()
                .map(r -> r.entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, e -> str(e.getValue()))))
                .collect(Collectors.toList());
    }

    /** The registered {@code relix.plan} system catalog symbol from a script's model. */
    private static SystemRelationSymbol planCatalog(String src) {
        SymbolTable table = analyze(src).model().orElseThrow().symbolTable();
        return (SystemRelationSymbol) table.lookupRelation("relix", "plan").orElseThrow();
    }

    /**
     * Rows of {@code relix.plan} as column→string maps.  A column omitted from a
     * row (e.g. {@code parent_id} on the root) is simply absent from the map.
     */
    private static List<Map<String, String>> planRows(String src) {
        return planCatalog(src).rows().stream()
                .map(r -> r.entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, e -> str(e.getValue()))))
                .collect(Collectors.toList());
    }

    /** {@code relix.plan} rows for one owning view, in node-id order. */
    private static List<Map<String, String>> planRowsFor(String src, String query) {
        return planRows(src).stream()
                .filter(r -> query.equals(r.get("query")))
                .sorted(java.util.Comparator.comparingInt(r -> Integer.parseInt(r.get("node_id"))))
                .collect(Collectors.toList());
    }

    // ─── resolution & schema ────────────────────────────────────────────────

    @Nested
    @DisplayName("Resolution and schema")
    class Resolution {

        @Test
        @DisplayName("relix.relations resolves and a query over it is fully valid")
        void resolvesAndValidates() {
            assertThat(analyze(SCRIPT + "query { relix.relations };").isFullyValid())
                    .isTrue();
        }

        @Test
        @DisplayName("catalog schema is (name, kind, namespace, materialization, row_count, boundedness)")
        void schemaShape() {
            assertThat(catalog(SCRIPT).schema().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("name:S", "kind:S", "namespace:S",
                            "materialization:S", "row_count:N", "boundedness:S");
        }

        @Test
        @DisplayName("registered as a reserved builtin (BUILTIN / FORBIDDEN) in the relix namespace")
        void reservedBuiltin() {
            SystemRelationSymbol c = catalog(SCRIPT);
            assertThat(c.namespace()).isEqualTo("relix");
            assertThat(c.provenance()).isEqualTo(Provenance.BUILTIN);
            assertThat(c.shadowPolicy()).isEqualTo(ShadowPolicy.FORBIDDEN);
        }

        @Test
        @DisplayName("catalog relation is a SystemRelationSymbol, not InlineRelationSymbol")
        void isSystemRelationSymbol() {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            RelationSymbol sym = table.lookupRelation("relix", "relations").orElseThrow();
            assertThat(sym).isInstanceOf(SystemRelationSymbol.class)
                           .isNotInstanceOf(InlineRelationSymbol.class);
        }
    }

    // ─── extent ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Extent")
    class Extent {

        @Test
        @DisplayName("one row per user relation, with kind and materialization")
        void describesUserRelations() {
            Map<String, Map<String, String>> rows = rowsByName(catalog(SCRIPT));

            assertThat(rows.get("Users"))
                    .containsEntry("kind", "INL")
                    .containsEntry("namespace", "default")
                    .containsEntry("materialization", "stream");
            assertThat(rows.get("Adults"))
                    .containsEntry("kind", "QR")
                    .containsEntry("materialization", "stream");
            // τ (sort) buffers → SORTED materialisation surfaces as "sort".
            assertThat(rows.get("ByName")).containsEntry("materialization", "sort");
        }

        @Test
        @DisplayName("row_count is the exact count for an inline relation")
        void rowCountForInlineRelation() {
            // Users is a one-row inline relation; its statistics are exact.
            assertThat(rowsByName(catalog(SCRIPT)).get("Users"))
                    .containsEntry("row_count", "1");
        }

        @Test
        @DisplayName("row_count is NULL (absent) for a view — its cardinality is an estimate")
        void rowCountAbsentForView() {
            assertThat(catalog(SCRIPT).rows().stream()
                    .filter(r -> "Adults".equals(str(r.get("name"))))
                    .findFirst().orElseThrow())
                    .doesNotContainKey("row_count");
        }

        @Test
        @DisplayName("the catalog excludes itself (snapshot taken before registration)")
        void excludesItself() {
            assertThat(rowsByName(catalog(SCRIPT))).doesNotContainKey("relations");
        }

        @Test
        @DisplayName("an empty script yields an empty catalog (no builtin relations)")
        void emptyScript() {
            assertThat(catalog("").rows()).isEmpty();
        }
    }

    // ─── boundedness ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Boundedness")
    class Bounded {

        private static final String GENERATED = """
                source N from generator { name: "Naturals" };
                source R from generator { name: "Range", lo: "1", hi: "9" };
                source M from generator { name: "Mystery" };
                Small    := { λ 3 (N) };
                Filtered := { σ n > 2 (N) };
                Deeper   := { π n (Filtered) };
                """;

        @Test
        @DisplayName("every relation carries one — unknown is a value, not a NULL")
        void neverNull() {
            assertThat(catalog(SCRIPT).rows())
                    .allSatisfy(row -> assertThat(row).containsKey("boundedness"));
        }

        @Test
        @DisplayName("an inline relation and a view over it are bounded")
        void inlineAndItsViewsAreBounded() {
            Map<String, Map<String, String>> rows = rowsByName(catalog(SCRIPT));
            assertThat(rows.get("Users")).containsEntry("boundedness", "bounded");
            assertThat(rows.get("Adults")).containsEntry("boundedness", "bounded");
        }

        @Test
        @DisplayName("a generator source is whatever the generator catalog says it is")
        void generatorSourcesTakeTheirLeafAnswer() {
            Map<String, Map<String, String>> rows = rowsWithGenerators(GENERATED, GENERATORS);
            assertThat(rows.get("N")).containsEntry("boundedness", "unbounded");
            assertThat(rows.get("R")).containsEntry("boundedness", "bounded");
            assertThat(rows.get("M")).containsEntry("boundedness", "unknown");
        }

        /**
         * The reason a view is derived rather than left NULL: reading an infinite relation
         * does not make a view infinite, and the operator that rescues it is the one the
         * property framework already knows about.
         */
        @Test
        @DisplayName("a view is as bounded as what it reads — λ over an infinite source is not")
        void viewsAreDerivedFromTheirBodies() {
            Map<String, Map<String, String>> rows = rowsWithGenerators(GENERATED, GENERATORS);
            assertThat(rows.get("Filtered")).containsEntry("boundedness", "unbounded");
            assertThat(rows.get("Small")).containsEntry("boundedness", "bounded");
        }

        @Test
        @DisplayName("through a view of a view — the walk follows names, not just children")
        void derivationIsTransitiveThroughViews() {
            assertThat(rowsWithGenerators(GENERATED, GENERATORS).get("Deeper"))
                    .containsEntry("boundedness", "unbounded");
        }

        /**
         * Mutually recursive definitions terminate, and answer {@code unknown} rather than
         * the lattice's identity: nothing here shows either relation to be finite.
         */
        @Test
        @DisplayName("a definitional cycle is unknown, not bounded")
        void cyclesAreUnknown() {
            Map<String, Map<String, String>> rows = rowsByName(catalog("""
                    A := { σ id > 0 (B) };
                    B := { σ id > 0 (A) };
                    """));
            assertThat(rows.get("A")).containsEntry("boundedness", "unknown");
            assertThat(rows.get("B")).containsEntry("boundedness", "unknown");
        }

        /**
         * A session with no generator registry wired in cannot place a generator at all,
         * and says so. This is the default {@link GeneratorCatalog#NONE} answer reaching a
         * catalog row rather than being quietly rounded to "finite".
         */
        @Test
        @DisplayName("with no generator catalog, a generator source is unknown")
        void withoutARegistryAGeneratorIsUnknown() {
            GeneratorCatalog schemaOnly = (name, args) -> java.util.Optional.of(
                    new Schema(List.of(new ColumnDefinition("n", ScalarType.NUMBER))));
            assertThat(rowsWithGenerators(
                    "source N from generator { name: \"Naturals\" };", schemaOnly).get("N"))
                    .containsEntry("boundedness", "unknown");
        }
    }

    // ─── lineage (relix.dependencies) ─────────────────────────────────────────

    @Nested
    @DisplayName("Lineage — relix.dependencies")
    class Dependencies {

        @Test
        @DisplayName("is a reserved builtin view (QR) in the relix namespace, (dependent, depends_on)")
        void reservedView() {
            QueryRelationSymbol deps = dependenciesView(SCRIPT);
            assertThat(deps.namespace()).isEqualTo("relix");
            assertThat(deps.provenance()).isEqualTo(Provenance.BUILTIN);
            assertThat(deps.shadowPolicy()).isEqualTo(ShadowPolicy.FORBIDDEN);
            assertThat(deps.schema().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("dependent:S", "depends_on:S");
        }

        @Test
        @DisplayName("is derived from relix.plan, not hand-walked (#303)")
        void derivedFromPlan() {
            // The view body reads exactly one relation — the relix.plan primitive —
            // proving the lineage is a query over the plan, not a Java AST walk.
            assertThat(IrReport.referencedRelations(dependenciesView(SCRIPT).body()))
                    .containsExactly("relix.plan");
        }

        @Test
        @DisplayName("resolves and a query over it is fully valid")
        void resolvesAndValidates() {
            assertThat(analyze(SCRIPT + "query { relix.dependencies };").isFullyValid())
                    .isTrue();
        }

        @Test
        @DisplayName("transitive lineage via CLOSURE over relix.dependencies is fully valid")
        void closureOverLineageValidates() {
            String src = SCRIPT
                    + "Lineage := { CLOSURE dependent, depends_on (relix.dependencies) };\n"
                    + "query { σ dependent = \"ByName\" (Lineage) };";
            assertThat(analyze(src)).isFullyValid();
        }
    }

    // ─── introspection stdlib (relix.unused / deps / impact) — #301 ────────────

    @Nested
    @DisplayName("Introspection stdlib — relix.unused / deps / impact")
    class Stdlib {

        private RelationFunctionSymbol tvf(String name) {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            return table.resolveFunction("relix." + name).stream()
                    .filter(RelationFunctionSymbol.class::isInstance)
                    .map(RelationFunctionSymbol.class::cast)
                    .findFirst().orElseThrow();
        }

        @Test
        @DisplayName("relix.unused is a reserved builtin view (QR) over the catalog")
        void unusedReservedView() {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            QueryRelationSymbol unused =
                    (QueryRelationSymbol) table.lookupRelation("relix", "unused").orElseThrow();
            assertThat(unused.namespace()).isEqualTo("relix");
            assertThat(unused.provenance()).isEqualTo(Provenance.BUILTIN);
            assertThat(unused.shadowPolicy()).isEqualTo(ShadowPolicy.FORBIDDEN);
            assertThat(unused.schema().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("name:S");
            // Layered over the catalog relations, not hand-walked.
            assertThat(IrReport.referencedRelations(unused.body()))
                    .containsExactlyInAnyOrder("relix.relations", "relix.dependencies");
        }

        @Test
        @DisplayName("relix.deps / relix.impact are reserved builtin TVFs taking one STRING param")
        void lineageFunctionsRegistered() {
            for (String name : List.of("deps", "impact")) {
                RelationFunctionSymbol fn = tvf(name);
                assertThat(fn.namespace()).isEqualTo("relix");
                assertThat(fn.provenance()).isEqualTo(Provenance.BUILTIN);
                assertThat(fn.parameters()).hasSize(1);
                assertThat(fn.parameters().get(0).type().code()).isEqualTo("S");
            }
        }

        @Test
        @DisplayName("relix.unused / deps / impact are self-excluded from the catalog relations")
        void selfExcluded() {
            // The stdlib lives in the relix namespace, so it never describes itself.
            assertThat(rowsByName(catalog(SCRIPT)).keySet())
                    .doesNotContain("unused", "deps", "impact");
        }

        @Test
        @DisplayName("a query calling relix.impact(\"X\") resolves and is fully valid")
        void impactCallValidates() {
            assertThat(analyze(SCRIPT + "query { relix.impact(\"Users\") };").isFullyValid())
                    .isTrue();
        }

        @Test
        @DisplayName("a query calling relix.deps(\"X\") resolves and is fully valid")
        void depsCallValidates() {
            assertThat(analyze(SCRIPT + "query { relix.deps(\"ByName\") };").isFullyValid())
                    .isTrue();
        }

        @Test
        @DisplayName("a query over relix.unused resolves and is fully valid")
        void unusedQueryValidates() {
            assertThat(analyze(SCRIPT + "query { relix.unused };").isFullyValid())
                    .isTrue();
        }

        @Test
        @DisplayName("relix.find / schema / funcs are reserved builtin TVFs taking one STRING param")
        void explorationFunctionsRegistered() {
            for (String name : List.of("find", "schema", "funcs")) {
                RelationFunctionSymbol fn = tvf(name);
                assertThat(fn.namespace()).isEqualTo("relix");
                assertThat(fn.provenance()).isEqualTo(Provenance.BUILTIN);
                assertThat(fn.shadowPolicy()).isEqualTo(ShadowPolicy.FORBIDDEN);
                assertThat(fn.parameters()).hasSize(1);
                assertThat(fn.parameters().get(0).type().code()).isEqualTo("S");
            }
        }

        @Test
        @DisplayName("relix.find is layered over relix.columns")
        void findLayeredOverColumns() {
            RelationFunctionSymbol find = tvf("find");
            assertThat(find.returnSchema().orElseThrow().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("relation:S", "type:S");
            assertThat(IrReport.referencedRelations(find.body()))
                    .containsExactly("relix.columns");
        }

        @Test
        @DisplayName("relix.schema is layered over relix.columns, the by-relation mirror of find")
        void schemaLayeredOverColumns() {
            RelationFunctionSymbol schema = tvf("schema");
            assertThat(schema.returnSchema().orElseThrow().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("column:S", "type:S", "ordinal:N");
            assertThat(IrReport.referencedRelations(schema.body()))
                    .containsExactly("relix.columns");
        }

        @Test
        @DisplayName("relix.funcs is layered over relix.functions")
        void funcsLayeredOverFunctions() {
            RelationFunctionSymbol funcs = tvf("funcs");
            assertThat(funcs.returnSchema().orElseThrow().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("name:S", "arity:N", "return_type:S");
            assertThat(IrReport.referencedRelations(funcs.body()))
                    .containsExactly("relix.functions");
        }

        @Test
        @DisplayName("relix.cycles is a reserved builtin view (QR) closing over relix.dependencies")
        void cyclesReservedView() {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            QueryRelationSymbol cycles =
                    (QueryRelationSymbol) table.lookupRelation("relix", "cycles").orElseThrow();
            assertThat(cycles.namespace()).isEqualTo("relix");
            assertThat(cycles.provenance()).isEqualTo(Provenance.BUILTIN);
            assertThat(cycles.shadowPolicy()).isEqualTo(ShadowPolicy.FORBIDDEN);
            assertThat(cycles.schema().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("name:S");
            assertThat(IrReport.referencedRelations(cycles.body()))
                    .containsExactly("relix.dependencies");
        }

        @Test
        @DisplayName("relix.find / schema / cycles / funcs are self-excluded from the catalog relations")
        void explorationSelfExcluded() {
            assertThat(rowsByName(catalog(SCRIPT)).keySet())
                    .doesNotContain("find", "schema", "cycles", "funcs");
        }

        @Test
        @DisplayName("queries calling relix.find / schema / cycles / funcs resolve and are fully valid")
        void explorationCallsValidate() {
            assertThat(analyze(SCRIPT + "query { relix.find(\"name\") };").isFullyValid())
                    .isTrue();
            assertThat(analyze(SCRIPT + "query { relix.schema(\"Users\") };").isFullyValid())
                    .isTrue();
            assertThat(analyze(SCRIPT + "query { relix.funcs(\"Math\") };").isFullyValid())
                    .isTrue();
            assertThat(analyze(SCRIPT + "query { relix.cycles };").isFullyValid())
                    .isTrue();
        }
    }

    // ─── schema (relix.columns) ───────────────────────────────────────────────

    @Nested
    @DisplayName("Schema — relix.columns")
    class Columns {

        @Test
        @DisplayName("one typed row per column of every base relation")
        void baseRelationColumns() {
            assertThat(columnEntries(SCRIPT))
                    .contains("Users.id:N", "Users.name:S");
        }

        @Test
        @DisplayName("a view reflects its post-inference (inferred) output schema")
        void viewReflectsInferredSchema() {
            // π name drops id — relix.columns must show only the projected column,
            // proving the extent is filled after inference resolves view schemas.
            String src = SCRIPT + "NameOnly := { π name (Users) };";
            assertThat(columnEntries(src)).contains("NameOnly.name:S")
                    .doesNotContain("NameOnly.id:N");
        }

        @Test
        @DisplayName("ordinals are sequential per relation")
        void ordinalsAreSequential() {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            SystemRelationSymbol cols =
                    (SystemRelationSymbol) table.lookupRelation("relix", "columns").orElseThrow();
            List<String> usersOrdinals = cols.rows().stream()
                    .filter(r -> str(r.get("relation")).equals("Users"))
                    .map(r -> ((com.darkcollective.relix.ast.NumberOperand) r.get("ordinal")).value())
                    .collect(Collectors.toList());
            assertThat(usersOrdinals).containsExactly("0", "1");
        }

        @Test
        @DisplayName("the catalog does not describe its own relix.* relations")
        void excludesCatalogRelations() {
            assertThat(columnEntries(SCRIPT))
                    .noneMatch(e -> e.startsWith("relations.")
                            || e.startsWith("columns.")
                            || e.startsWith("dependencies."));
        }

        @Test
        @DisplayName("schema carries distinct_count and null_count columns")
        void schemaShape() {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            SystemRelationSymbol cols =
                    (SystemRelationSymbol) table.lookupRelation("relix", "columns").orElseThrow();
            assertThat(cols.schema().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("relation:S", "column:S", "type:S", "ordinal:N",
                            "distinct_count:N", "null_count:N");
        }

        @Test
        @DisplayName("distinct_count / null_count are exact for an inline relation's columns")
        void exactColumnStatsForInline() {
            Map<String, String> idRow = columnRow(SCRIPT, "Users", "id");
            assertThat(idRow)
                    .containsEntry("distinct_count", "1")
                    .containsEntry("null_count", "0");
        }

        @Test
        @DisplayName("two spellings of one number are one distinct value")
        void distinctCountIsByValueNotBySpelling() {
            // Counted on the literals, this was a claim about spelling: a column holding
            // 5 and 5.0 read as two distinct values and no NULLs, so it was published as
            // a candidate key, the relation was believed duplicate-free, and DIST-001
            // removed a δ that was doing real work.
            String src = """
                    Scales := [
                    | x |
                    |---|
                    | 5 |
                    | 5.0 |
                    ];
                    """;
            assertThat(columnRow(src, "Scales", "x"))
                    .containsEntry("distinct_count", "1")
                    .containsEntry("null_count", "0");
        }

        @Test
        @DisplayName("a view's columns carry no distinct_count / null_count (absent)")
        void noColumnStatsForView() {
            String src = SCRIPT + "NameOnly := { π name (Users) };";
            assertThat(columnRow(src, "NameOnly", "name"))
                    .doesNotContainKey("distinct_count")
                    .doesNotContainKey("null_count");
        }

        /** The {@code relix.columns} row for one relation.column, as a column→string map. */
        private static Map<String, String> columnRow(String src, String relation, String column) {
            SymbolTable table = analyze(src).model().orElseThrow().symbolTable();
            SystemRelationSymbol cols =
                    (SystemRelationSymbol) table.lookupRelation("relix", "columns").orElseThrow();
            return cols.rows().stream()
                    .filter(r -> relation.equals(str(r.get("relation")))
                            && column.equals(str(r.get("column"))))
                    .map(r -> r.entrySet().stream().collect(Collectors.toMap(
                            Map.Entry::getKey, e -> str(e.getValue()))))
                    .findFirst().orElseThrow();
        }
    }

    // ─── functions (relix.functions) ──────────────────────────────────────────

    @Nested
    @DisplayName("Functions — relix.functions")
    class Functions {

        private Map<String, String> oneRow(String src, java.util.function.Predicate<Map<String, String>> p) {
            return functionRows(src).stream().filter(p).findFirst().orElseThrow();
        }

        @Test
        @DisplayName("every built-in is listed regardless of use, with category + flags")
        void listsAllBuiltins() {
            // Empty script uses no functions, yet the full library is present.
            Map<String, String> abs = oneRow("", r -> r.get("name").equals("Abs"));
            assertThat(abs)
                    .containsEntry("category", "math")
                    .containsEntry("arity", "1")
                    .containsEntry("max_arity", "1")
                    .containsEntry("return_type", "N")
                    .containsEntry("pure", "true")
                    .containsEntry("idempotent", "true")
                    .containsEntry("is_builtin", "true");
        }

        @Test
        @DisplayName("non-deterministic built-ins (Rand) are flagged as such")
        void nonDeterministicBuiltin() {
            Map<String, String> rand = oneRow("", r -> r.get("name").equals("Rand"));
            assertThat(rand)
                    .containsEntry("pure", "false")
                    .containsEntry("deterministic", "false");
        }

        @Test
        @DisplayName("a function with an optional argument is one row spanning both forms")
        void arityRangeIsOneRow() {
            List<Map<String, String>> mid = functionRows("").stream()
                    .filter(r -> r.get("name").equals("Mid"))
                    .collect(Collectors.toList());

            // Mid(s, start) and Mid(s, start, length) are two forms of one function,
            // so the catalog reports the range rather than inventing a row per form.
            assertThat(mid).hasSize(1);
            assertThat(mid.get(0))
                    .containsEntry("arity", "2")
                    .containsEntry("max_arity", "3");
        }

        @Test
        @DisplayName("a function taking any number of arguments reports max_arity -1")
        void unboundedArity() {
            assertThat(oneRow("", r -> r.get("name").equals("Coalesce")))
                    .containsEntry("arity", "1")
                    .containsEntry("max_arity", "-1");
        }

        @Test
        @DisplayName("user defs are listed with category 'user' and is_builtin false")
        void userDefsListed() {
            String src = "def lineTotal(price: NUMBER, qty: NUMBER): NUMBER := { price * qty };";
            Map<String, String> fn = oneRow(src, r -> r.get("name").equals("lineTotal"));
            assertThat(fn)
                    .containsEntry("category", "user")
                    .containsEntry("arity", "2")
                    .containsEntry("max_arity", "2")
                    .containsEntry("return_type", "N")
                    .containsEntry("is_builtin", "false");
        }
    }

    // ─── relix.connections (redacted) ───────────────────────────────────────────

    @Nested
    @DisplayName("Connections — relix.connections (redacted)")
    class Connections {

        private static final String CONN =
                "connection sales from database { url: \"jdbc:h2:mem:s\", dialect: postgres };\n";

        @Test
        @DisplayName("schema is (name, dialect), all STRING — url/user/password are redacted away")
        void schemaShapeRedacted() {
            assertThat(connectionsCatalog(CONN).schema().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("name:S", "dialect:S");
        }

        @Test
        @DisplayName("one row per declared connection, with its declared dialect")
        void oneRowPerConnection() {
            var rows = connectionsCatalog(CONN).rows();
            assertThat(rows).hasSize(1);
            assertThat(str(rows.get(0).get("name"))).isEqualTo("sales");
            assertThat(str(rows.get(0).get("dialect"))).isEqualTo("postgres");
        }

        @Test
        @DisplayName("dialect defaults to 'generic' when none is declared")
        void dialectDefaultsToGeneric() {
            var rows = connectionsCatalog(
                    "connection plain from database { url: \"jdbc:h2:mem:p\" };\n").rows();
            assertThat(str(rows.get(0).get("dialect"))).isEqualTo("generic");
        }

        @Test
        @DisplayName("empty extent when no connections are declared")
        void emptyWhenNoConnections() {
            assertThat(connectionsCatalog("").rows()).isEmpty();
        }

        @Test
        @DisplayName("registered as a reserved builtin (BUILTIN / FORBIDDEN) in the relix namespace")
        void reservedBuiltin() {
            SystemRelationSymbol c = connectionsCatalog(CONN);
            assertThat(c.namespace()).isEqualTo("relix");
            assertThat(c.provenance()).isEqualTo(Provenance.BUILTIN);
            assertThat(c.shadowPolicy()).isEqualTo(ShadowPolicy.FORBIDDEN);
        }

        @Test
        @DisplayName("resolves and a query over it is fully valid")
        void resolvesAndValidates() {
            assertThat(analyze(CONN + "query { relix.connections };").isFullyValid()).isTrue();
        }
    }

    // ─── expression tree (relix.plan) — #302 ──────────────────────────────────

    @Nested
    @DisplayName("Expression tree — relix.plan")
    class Plan {

        @Test
        @DisplayName("schema is (query, node_id, parent_id, ordinal, op, label, relation, schema_code, materialization, depth)")
        void schemaShape() {
            assertThat(planCatalog(SCRIPT).schema().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("query:S", "node_id:N", "parent_id:N", "ordinal:N",
                            "op:S", "label:S", "relation:S", "schema_code:S",
                            "materialization:S", "depth:N");
        }

        @Test
        @DisplayName("registered as a reserved builtin (BUILTIN / PERMITTED) in the relix namespace")
        void reservedBuiltin() {
            SystemRelationSymbol c = planCatalog(SCRIPT);
            assertThat(c.namespace()).isEqualTo("relix");
            assertThat(c.provenance()).isEqualTo(Provenance.BUILTIN);
            // PERMITTED so the post-inference fill can overwrite the placeholder.
            assertThat(c.shadowPolicy()).isEqualTo(ShadowPolicy.PERMITTED);
        }

        @Test
        @DisplayName("resolves and a query over it is fully valid")
        void resolvesAndValidates() {
            assertThat(analyze(SCRIPT + "query { relix.plan };").isFullyValid()).isTrue();
        }

        @Test
        @DisplayName("one row per IR node of each view body, with op + label + depth")
        void oneRowPerNode() {
            // Adults := σ id > 0 (Users) → two nodes: the σ over the Users leaf.
            List<Map<String, String>> adults = planRowsFor(SCRIPT, "Adults");
            assertThat(adults).hasSize(2);

            Map<String, String> root = adults.get(0);
            assertThat(root).containsEntry("op", "Selection")
                    .containsEntry("label", "σ id > 0")
                    .containsEntry("depth", "0")
                    .containsEntry("ordinal", "0")
                    .doesNotContainKey("parent_id")    // root → NULL parent
                    .doesNotContainKey("relation");    // non-leaf → NULL relation

            Map<String, String> leaf = adults.get(1);
            assertThat(leaf).containsEntry("op", "Relation")
                    .containsEntry("label", "Users [INL]")
                    .containsEntry("depth", "1")
                    .containsEntry("parent_id", root.get("node_id"));
        }

        @Test
        @DisplayName("relation names the referenced relation on Relation leaves only (the #303 join key)")
        void relationColumnOnLeaves() {
            // Adults := σ id > 0 (Users) → the σ root carries no relation, the leaf names Users.
            List<Map<String, String>> adults = planRowsFor(SCRIPT, "Adults");
            assertThat(adults.get(0)).doesNotContainKey("relation");        // σ → NULL
            assertThat(adults.get(1)).containsEntry("op", "Relation")
                    .containsEntry("relation", "Users");                    // bare name, no kind tag
            // The decorated label keeps its kind tag — relation is the clean join key.
            assertThat(adults.get(1).get("label")).isEqualTo("Users [INL]");
        }

        @Test
        @DisplayName("node_ids are a dense pre-order numbering per view (root = 0)")
        void preOrderIds() {
            List<Map<String, String>> byName = planRowsFor(SCRIPT, "ByName");
            assertThat(byName).extracting(r -> r.get("node_id")).containsExactly("0", "1");
            assertThat(byName.get(0)).containsEntry("op", "Sort");   // τ
        }

        @Test
        @DisplayName("schema_code is the node's inferred output schema as a Type.code() string")
        void schemaCodeReflectsInference() {
            // π name drops id → the projection node's output schema is just {name:S}.
            String src = SCRIPT + "NameOnly := { π name (Users) };";
            Map<String, String> proj = planRowsFor(src, "NameOnly").get(0);
            assertThat(proj).containsEntry("op", "Projection")
                    .containsEntry("schema_code", "{name:S}");
        }

        @Test
        @DisplayName("materialization tags the buffering operators (τ → sort)")
        void materializationTag() {
            assertThat(planRowsFor(SCRIPT, "ByName").get(0))
                    .containsEntry("materialization", "sort");
            assertThat(planRowsFor(SCRIPT, "Adults").get(0))
                    .containsEntry("materialization", "stream");
        }

        @Test
        @DisplayName("only views contribute rows — base relations have no body")
        void onlyViews() {
            assertThat(planRows(SCRIPT)).extracting(r -> r.get("query"))
                    .containsOnly("Adults", "ByName")
                    .doesNotContain("Users");
        }

        @Test
        @DisplayName("the catalog does not describe its own relix.* relations")
        void excludesCatalogRelations() {
            assertThat(planRows(SCRIPT)).extracting(r -> r.get("query"))
                    .noneMatch(q -> q.equals("plan") || q.equals("columns")
                            || q.equals("relations") || q.equals("dependencies"));
        }

        @Test
        @DisplayName("an empty script yields an empty plan extent")
        void emptyScript() {
            assertThat(planCatalog("").rows()).isEmpty();
        }

        @Test
        @DisplayName("subtree queries via CLOSURE over the parent edge are fully valid (dogfood)")
        void closureOverParentEdgeValidates() {
            String src = SCRIPT
                    + "Subtree := { CLOSURE parent_id, node_id "
                    + "(π parent_id, node_id (σ query = \"Adults\" (relix.plan))) };\n"
                    + "query { Subtree };";
            assertThat(analyze(src)).isFullyValid();
        }
    }

    // ─── observability feed (relix.events) — #35 ──────────────────────────────

    @Nested
    @DisplayName("Observability feed — relix.events")
    class Events {

        private static final List<QueryEvent> FEED = List.of(
                QueryEvent.of(QueryEvent.Stage.OPTIMIZE, "SEL-001",
                        "selection pushed below join", "Adults"),
                QueryEvent.of(QueryEvent.Stage.PLAN, "JOIN",
                        "hash join, build side left", "Adults"),
                // No target: the emitter did not know the owning query.
                QueryEvent.of(QueryEvent.Stage.EXECUTE, "OPTIMIZE",
                        "group skipped as infeasible"));

        @Test
        @DisplayName("schema is (seq, stage, code, description, target, rows, elapsed)")
        void schemaShape() {
            assertThat(eventsCatalog(SCRIPT, List.of()).schema().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("seq:N", "stage:S", "code:S", "description:S", "target:S",
                            "rows:N", "elapsed:DUR");
        }

        @Test
        @DisplayName("a measured event carries its quantities; an unmeasured one leaves both NULL")
        void measurementsReachTheFeed() {
            // The Java feed and this relation are the same feed, so a quantity readable
            // through metrics() and not here would be two surfaces disagreeing about what
            // one run observed.
            List<Map<String, String>> rows = eventRows(SCRIPT, List.of(
                    QueryEvent.of(QueryEvent.Stage.EXECUTE, "ROWS", "query delivered 2 rows", "Q")
                            .withMetrics(EventMetrics.of(2, Duration.ofMillis(4))),
                    QueryEvent.of(QueryEvent.Stage.OPTIMIZE, "SEL-001", "pushed", "Q")));

            assertThat(rows.get(0)).containsEntry("rows", "2").containsEntry("elapsed", "PT0.004S");
            assertThat(rows.get(1)).doesNotContainKey("rows").doesNotContainKey("elapsed");
        }

        @Test
        @DisplayName("one row per supplied event, in arrival order, numbered from 1")
        void oneRowPerEventInOrder() {
            List<Map<String, String>> rows = eventRows(SCRIPT, FEED);

            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(r -> r.get("seq")).containsExactly("1", "2", "3");
            assertThat(rows).extracting(r -> r.get("code"))
                    .containsExactly("SEL-001", "JOIN", "OPTIMIZE");
            assertThat(rows).extracting(r -> r.get("stage"))
                    .containsExactly("OPTIMIZE", "PLAN", "EXECUTE");
            assertThat(rows.get(0).get("description")).isEqualTo("selection pushed below join");
        }

        @Test
        @DisplayName("an event with no target yields a NULL target — the key is omitted")
        void absentTargetIsNull() {
            List<Map<String, String>> rows = eventRows(SCRIPT, FEED);
            assertThat(rows.get(0)).containsEntry("target", "Adults");
            assertThat(rows.get(2)).doesNotContainKey("target");
        }

        @Test
        @DisplayName("empty extent when the host observed nothing (the batch-run case)")
        void emptyWithoutAFeed() {
            assertThat(eventsCatalog(SCRIPT, List.of()).rows()).isEmpty();
        }

        @Test
        @DisplayName("registered as a reserved builtin (BUILTIN / FORBIDDEN) in the relix namespace")
        void reservedBuiltin() {
            SystemRelationSymbol events = eventsCatalog(SCRIPT, FEED);
            assertThat(events.namespace()).isEqualTo("relix");
            assertThat(events.provenance()).isEqualTo(Provenance.BUILTIN);
            assertThat(events.shadowPolicy()).isEqualTo(ShadowPolicy.FORBIDDEN);
        }

        @Test
        @DisplayName("resolves and a query over it is fully valid")
        void resolvesAndValidates() {
            assertThat(analyze(SCRIPT + "query { relix.events };").isFullyValid()).isTrue();
        }

        @Test
        @DisplayName("a γ over the feed — the motivating query — is fully valid")
        void aggregationOverFeedValidates() {
            String src = SCRIPT
                    + "RuleCounts := { γ code, COUNT(*) → fired "
                    + "(σ stage = \"OPTIMIZE\" (relix.events)) };\n"
                    + "query { RuleCounts };";
            assertThat(analyze(src)).isFullyValid();
        }

        @Test
        @DisplayName("the feed does not describe the catalog's own relations")
        void selfExclusionUnaffected() {
            // relix.events has no structural extent to exclude, but adding it must
            // not leak a relix.* row into the structural catalogs either.
            assertThat(rowsByName(catalog(SCRIPT)).keySet())
                    .doesNotContain("events", "rules");
        }
    }

    // ─── derived view over the feed (relix.rules) ─────────────────────────────

    @Nested
    @DisplayName("Optimizer firings — relix.rules")
    class Rules {

        @Test
        @DisplayName("is a derived view, not a Java-computed extent")
        void isAView() {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            RelationSymbol sym = table.lookupRelation("relix", "rules").orElseThrow();
            assertThat(sym).isInstanceOf(QueryRelationSymbol.class);
        }

        @Test
        @DisplayName("body is π seq, code, description, target (σ stage = OPTIMIZE (relix.events))")
        void derivedFromEvents() {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            QueryRelationSymbol rules =
                    (QueryRelationSymbol) table.lookupRelation("relix", "rules").orElseThrow();
            String body = rules.body().prettyPrint();
            assertThat(body).contains("relix.events");
            assertThat(body).contains("OPTIMIZE");
        }

        @Test
        @DisplayName("schema drops the constant stage column and keeps the ordering key")
        void schemaShape() {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            QueryRelationSymbol rules =
                    (QueryRelationSymbol) table.lookupRelation("relix", "rules").orElseThrow();
            assertThat(rules.schema().columns())
                    .extracting(c -> c.name() + ":" + c.type().code())
                    .containsExactly("seq:N", "code:S", "description:S", "target:S");
        }

        @Test
        @DisplayName("resolves and a query over it is fully valid")
        void resolvesAndValidates() {
            assertThat(analyze(SCRIPT + "query { relix.rules };").isFullyValid()).isTrue();
        }
    }

    // ─── relix.version (the component inventory) ────────────────────────────────

    @Nested
    @DisplayName("Version — relix.version")
    class Version {

        private static final String SRC = """
                R := [| id | name  |
                       | 1  | Alice |];
                query R;
                """;

        @Test
        @DisplayName("the engine reports itself, with the version the build stamped")
        void reportsTheEngine() {
            assertThat(versionCatalog(SRC).rows())
                    .anySatisfy(row -> {
                        assertThat(str(row.get("component"))).isEqualTo("relix-engine");
                        assertThat(str(row.get("kind"))).isEqualTo("engine");
                        assertThat(str(row.get("version"))).isNotBlank()
                                .isNotEqualTo("unknown");
                    });
        }

        @Test
        @DisplayName("its heading is (component, kind, version)")
        void heading() {
            assertThat(versionCatalog(SRC).schema().columns())
                    .extracting("name")
                    .containsExactly("component", "kind", "version");
        }

        /**
         * The engine knows the libraries because it holds them — which is the half of
         * the inventory that needs no host to supply it.
         */
        @Test
        @DisplayName("each function library the catalogue was built over is a row")
        void reportsFunctionLibraries() {
            assertThat(versionCatalog(SRC).rows())
                    .filteredOn(row -> str(row.get("kind")).equals("function-library"))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("a host that supplies nothing still gets the engine's own rows")
        void hostMaySupplyNothing() {
            assertThat(versionCatalog(SRC).rows())
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(str(row.get("kind")))
                            .isIn("engine", "function-library"));
        }

        @Test
        @DisplayName("what the host supplies is reported beside it")
        void hostComponentsAreReported() {
            SemanticAnalyzer analyzer = new SemanticAnalyzer(new InMemoryScriptLoader(Map.of()))
                    .withComponents(() -> List.of(
                            new ComponentInventory.Component("acme-mongo", "connector", "3.1"),
                            new ComponentInventory.Component("org.h2.Driver", "driver", "2.2")));
            SymbolTable table = analyzer
                    .analyze(com.darkcollective.relix.lang.ScriptParser.parse(
                            SRC, SemanticAnalyzer.STDIN_PATH), SemanticAnalyzer.STDIN_PATH)
                    .model().orElseThrow().symbolTable();
            SystemRelationSymbol version =
                    (SystemRelationSymbol) table.lookupRelation("relix", "version").orElseThrow();

            assertThat(version.rows())
                    .filteredOn(row -> str(row.get("kind")).equals("connector"))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(str(row.get("component"))).isEqualTo("acme-mongo");
                        assertThat(str(row.get("version"))).isEqualTo("3.1");
                    });
            assertThat(version.rows())
                    .anySatisfy(row -> assertThat(str(row.get("kind"))).isEqualTo("driver"));
        }

        @Test
        @DisplayName("it is queryable like any other relation")
        void isQueryable() {
            assertThat(analyze(SRC + "query { σ kind = \"engine\" (relix.version) };")
                    .isFullyValid()).isTrue();
        }
    }

    // ─── candidate keys (relix.keys) ──────────────────────────────────────────

    @Nested
    @DisplayName("Candidate keys — relix.keys")
    class Keys {

        /** {@code relation/key/ordinal/column} entries of {@code relix.keys}. */
        private static List<String> keyEntries(String src) {
            SymbolTable table = analyze(src).model().orElseThrow().symbolTable();
            SystemRelationSymbol keys =
                    (SystemRelationSymbol) table.lookupRelation("relix", "keys").orElseThrow();
            return keys.rows().stream()
                    .map(r -> str(r.get("relation")) + "/" + str(r.get("key"))
                            + "/" + str(r.get("ordinal")) + "/" + str(r.get("column")))
                    .collect(Collectors.toList());
        }

        @Test
        @DisplayName("is a reserved builtin system relation, (relation, key, ordinal, column)")
        void schemaAndReservation() {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            RelationSymbol keys = table.lookupRelation("relix", "keys").orElseThrow();

            assertThat(keys).isInstanceOf(SystemRelationSymbol.class);
            assertThat(keys.namespace()).isEqualTo("relix");
            assertThat(keys.provenance()).isEqualTo(Provenance.BUILTIN);
            assertThat(keys.shadowPolicy()).isEqualTo(ShadowPolicy.FORBIDDEN);
            assertThat(keys.schema()).hasColumnNames(
                    "relation", "key", "ordinal", "column");
        }

        @Test
        @DisplayName("an inline relation's distinct non-null column is a candidate key")
        void inlineColumnIsAKey() {
            // Users has one row, so id and name are each trivially distinct.
            assertThat(keyEntries(SCRIPT))
                    .contains("Users/0/0/id", "Users/1/0/name");
        }

        @Test
        @DisplayName("a column with a duplicate value is not a key")
        void duplicatedColumnIsNotAKey() {
            String src = """
                    People := [| id | dept |
                               | 1  | eng  |
                               | 2  | eng  |];
                    """;
            assertThat(keyEntries(src))
                    .contains("People/0/0/id")
                    .noneMatch(entry -> entry.endsWith("/dept"));
        }

        @Test
        @DisplayName("a view contributes no rows — its statistics are never collected")
        void viewHasNoKeys() {
            // Not the same claim as having no key: nothing was measured.
            assertThat(keyEntries(SCRIPT)).noneMatch(entry -> entry.startsWith("Adults/"));
        }

        @Test
        @DisplayName("a composite key keeps its column order in `ordinal`")
        void compositeKeyOrder() {
            // Column order within a key is significant — it is the order an index
            // on it would be built in — so it is data, not incidental row order.
            SymbolTable table = analyzeWithCatalog("""
                    connection sales from database { url: "jdbc:h2:mem:x" };
                    query { σ amount > 0 (sales.orders) };
                    """, keyedCatalog(List.of(List.of("region", "oid"))));
            SystemRelationSymbol keys =
                    (SystemRelationSymbol) table.lookupRelation("relix", "keys").orElseThrow();

            assertThat(keys.rows()).hasSize(2);
            assertThat(keys.rows()).allSatisfy(row ->
                    assertThat(str(row.get("key"))).isEqualTo("0"));
            assertThat(keys.rows().stream().map(r -> str(r.get("ordinal"))
                    + ":" + str(r.get("column"))).toList())
                    .containsExactly("0:region", "1:oid");
        }

        @Test
        @DisplayName("several keys of one relation are numbered apart")
        void severalKeysAreNumbered() {
            SymbolTable table = analyzeWithCatalog("""
                    connection sales from database { url: "jdbc:h2:mem:x" };
                    query { σ amount > 0 (sales.orders) };
                    """, keyedCatalog(List.of(List.of("oid"), List.of("region", "amount"))));
            SystemRelationSymbol keys =
                    (SystemRelationSymbol) table.lookupRelation("relix", "keys").orElseThrow();

            assertThat(keys.rows().stream().map(r -> str(r.get("key"))
                    + "/" + str(r.get("ordinal")) + "/" + str(r.get("column"))).toList())
                    .containsExactly("0/0/oid", "1/0/region", "1/1/amount");
        }

        @Test
        @DisplayName("it is queryable like any other relation")
        void isQueryable() {
            assertThat(analyze(SCRIPT
                    + "query { σ relation = \"Users\" (relix.keys) };").isFullyValid())
                    .isTrue();
        }

        @Test
        @DisplayName("it describes only user relations — the catalog excludes itself")
        void selfExcluded() {
            assertThat(keyEntries(SCRIPT)).noneMatch(entry -> entry.startsWith("relix"));
        }
    }

    // ─── the reserved namespace (relix.catalog) ───────────────────────────────

    @Nested
    @DisplayName("Reserved namespace — relix.catalog")
    class Catalog {

        /** {@code relix.catalog} rows keyed by the entry name. */
        private static Map<String, Map<String, String>> entries(String src) {
            SymbolTable table = analyze(src).model().orElseThrow().symbolTable();
            SystemRelationSymbol cat =
                    (SystemRelationSymbol) table.lookupRelation("relix", "catalog").orElseThrow();
            return cat.rows().stream().collect(Collectors.toMap(
                    r -> str(r.get("name")),
                    r -> r.entrySet().stream().collect(
                            Collectors.toMap(Map.Entry::getKey, e -> str(e.getValue())))));
        }

        @Test
        @DisplayName("is a reserved builtin system relation, (name, kind, arity, schema_code)")
        void schemaAndReservation() {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            RelationSymbol cat = table.lookupRelation("relix", "catalog").orElseThrow();

            assertThat(cat).isInstanceOf(SystemRelationSymbol.class);
            assertThat(cat.namespace()).isEqualTo("relix");
            assertThat(cat.provenance()).isEqualTo(Provenance.BUILTIN);
            assertThat(cat.schema()).hasColumnNames("name", "kind", "arity", "schema_code");
        }

        @Test
        @DisplayName("lists the engine-computed catalogs, the stdlib views, and the TVFs")
        void listsEveryKind() {
            Map<String, Map<String, String>> rows = entries(SCRIPT);
            assertThat(rows).containsKeys(
                    "relix.relations", "relix.columns", "relix.plan",     // SYS
                    "relix.dependencies", "relix.unused", "relix.cycles", // QR
                    "relix.deps", "relix.impact", "relix.funcs");         // TVF
            assertThat(rows.get("relix.relations")).containsEntry("kind", "SYS");
            assertThat(rows.get("relix.unused")).containsEntry("kind", "QR");
            assertThat(rows.get("relix.impact")).containsEntry("kind", "TVF");
        }

        @Test
        @DisplayName("lists itself — a listing with a hole where the entry point is, is not one")
        void listsItself() {
            assertThat(entries(SCRIPT)).containsKey("relix.catalog");
            assertThat(entries(SCRIPT).get("relix.catalog"))
                    .containsEntry("kind", "SYS")
                    .containsEntry("schema_code", "{name:S,kind:S,arity:N,schema_code:S}");
        }

        @Test
        @DisplayName("every registered relix.* symbol has a row, and nothing else does")
        void listsExactlyTheReservedNamespace() {
            SymbolTable table = analyze(SCRIPT).model().orElseThrow().symbolTable();
            List<String> registered = table.allSymbols().stream()
                    .filter(s -> "relix".equals(s.namespace()))
                    .map(s -> "relix." + s.declaredName())
                    .sorted().toList();
            assertThat(entries(SCRIPT).keySet()).containsExactlyInAnyOrderElementsOf(registered);
        }

        @Test
        @DisplayName("arity is the parameter count for a TVF and NULL for a relation")
        void arityDistinguishesCallables() {
            Map<String, Map<String, String>> rows = entries(SCRIPT);
            assertThat(rows.get("relix.impact")).containsEntry("arity", "1");
            assertThat(rows.get("relix.relations")).doesNotContainKey("arity");
        }

        @Test
        @DisplayName("schema_code is the heading, for a relation and for a TVF's result alike")
        void schemaCodeIsTheHeading() {
            Map<String, Map<String, String>> rows = entries(SCRIPT);
            assertThat(rows.get("relix.dependencies"))
                    .containsEntry("schema_code", "{dependent:S,depends_on:S}");
            assertThat(rows.get("relix.impact"))
                    .containsEntry("schema_code", "{dependent:S}");
        }

        @Test
        @DisplayName("it changes no other extent — the rest still exclude the reserved namespace")
        void doesNotLeakIntoTheOtherCatalogs() {
            assertThat(rowsByName(catalog(SCRIPT)).keySet())
                    .noneMatch(name -> name.startsWith("relix"));
            assertThat(columnEntries(SCRIPT)).noneMatch(entry -> entry.startsWith("relix"));
        }

        @Test
        @DisplayName("it is non-empty for an empty script — the namespace does not depend on one")
        void populatedWithoutAScript() {
            assertThat(entries("query { relix.catalog };")).containsKey("relix.relations");
        }

        @Test
        @DisplayName("it is queryable like any other relation")
        void isQueryable() {
            assertThat(analyze(SCRIPT + "query { σ kind = \"TVF\" (relix.catalog) };")
                    .isFullyValid()).isTrue();
        }
    }
}

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
/**
 * Semantic analysis for {@code .relix} scripting language source files.
 *
 * <p>This module takes a parsed {@link com.darkcollective.relix.lang.ast.Script}
 * (produced by {@code relix-lang}) and performs:
 * <ol>
 *   <li><strong>Import resolution</strong> — loads all transitively imported files,
 *       detects cycles, and determines a safe processing order.</li>
 *   <li><strong>Symbol collection</strong> — registers every source declaration,
 *       assignment, and {@code def} statement as a typed symbol in the symbol table.</li>
 *   <li><strong>Schema inference</strong> — walks each relational algebra expression
 *       body and infers the output schema at every node.</li>
 *   <li><strong>Validation</strong> — checks name resolution, function arities,
 *       schema compatibility for set operations, and source config completeness.</li>
 * </ol>
 *
 * <p>The entry point is {@link com.darkcollective.relix.semantic.SemanticAnalyzer},
 * whose contract is {@code Script → SemanticResult} — the engine
 * analyses an AST, and the concrete {@code .relix} text syntax is one frontend
 * that produces one.  Where a {@code Script} comes from is pluggable behind
 * {@link com.darkcollective.relix.semantic.ScriptLoader}; the grammar itself is
 * <em>not</em> on this module's {@code main} classpath, so reading text —
 * {@code FileSystemScriptLoader}, {@code ScriptText} — lives in
 * {@code relix-console} (Decision 2).
 * Built-in functions and relations are registered via a
 * {@link com.darkcollective.relix.semantic.BuiltinProvider} hook passed at
 * construction time.
 *
 * <p>Analysis always returns a
 * {@link com.darkcollective.relix.semantic.SemanticResult} that carries both a
 * (possibly partial) {@link com.darkcollective.relix.semantic.SemanticModel} and
 * any {@link com.darkcollective.relix.semantic.SemanticError}s collected during
 * analysis.
 */
module com.darkcollective.relix.semantic {
    // NOTE (ADR-0025 D2): there is deliberately no `requires
    // com.darkcollective.relix.lang` here. The engine analyses an AST; the
    // .relix grammar is a frontend that produces one, and lives in
    // relix-console (runtime.script). Its absence from this module graph is the
    // decision's compiler-enforced claim — core code cannot reach the concrete
    // syntax. The grammar stays a *test* dependency (D3), where whitebox tests
    // run on the classpath and so need no `requires` at all.

    // Script, Statement, SourceConfig, and related AST types in the public API.
    // Transitively provides relix-symbol and relix-ast (via requires transitive chain).
    requires transitive com.darkcollective.relix.lang.ast;

    // SymbolTable, Schema, ScalarType — all in the public API.
    // Already transitively visible via relix-lang-ast, but declared explicitly
    // because relix-semantic directly depends on this module's types.
    requires transitive com.darkcollective.relix.symbol;

    // JsonWriter appears in LogicalPlanJson.write(JsonWriter, …); transitive so
    // bundle assemblers (the CLI) can drive the same writer across modules.
    requires transitive com.darkcollective.relix.json;

    // QueryEvent appears in SemanticAnalyzer.withSessionEvents(List<QueryEvent>),
    // which carries the previous run's feed in as the extent of relix.events
    // (ADR-0017 Decision 3 — the push→pull bridge). relix-events is a leaf with no
    // relix dependencies, so requiring it here adds no cycle; transitive because a
    // session host passing a feed in also holds the listener that produced it.
    requires transitive com.darkcollective.relix.events;

    // The function SPI. FunctionCatalog is a SemanticModel component and a
    // SemanticAnalyzer constructor parameter, so it is transitive; the default
    // library is deliberately absent — a provider is discovered, never required.
    requires transitive com.darkcollective.relix.function;

    // The relation-property framework (ADR-0009). Boundedness appears on the
    // GeneratorCatalog seam and is the source of relix.relations.boundedness,
    // and PropertyDeriver is what turns a view's body into one — a second
    // derivation of the same lattice here is the copy this repository refuses.
    // relix-cost requires only relix-ast and relix-symbol, so this edge inverts
    // nothing: it is a sibling library over the same two inputs, not a later
    // phase reached backwards.
    requires transitive com.darkcollective.relix.cost;

    exports com.darkcollective.relix.semantic;
    exports com.darkcollective.relix.semantic.graph;
}

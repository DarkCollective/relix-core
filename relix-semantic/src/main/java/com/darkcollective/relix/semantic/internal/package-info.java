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
 * The engine-side machinery behind {@code com.darkcollective.relix.semantic}: not exported by the published
 * artifact. What follows describes the package as a whole, as it stood before the split.
 *
 * Five-phase semantic analysis pipeline for Relix scripts.
 *
 * <h2>Overview</h2>
 *
 * <p>This package transforms a parsed {@link com.darkcollective.relix.lang.ast.Script}
 * AST into a validated {@link com.darkcollective.relix.semantic.SemanticModel} that
 * downstream tools (execution engines, optimisers, IDEs) can query.  Analysis is
 * orchestrated by {@link com.darkcollective.relix.semantic.internal.SemanticAnalyzer} and
 * proceeds in five sequential phases:
 *
 * <ol>
 *   <li><b>Import Graph</b> ({@link com.darkcollective.relix.semantic.internal.ImportGraph}) —
 *       resolves {@code import} statements depth-first and detects cycles.</li>
 *   <li><b>Symbol Collection</b> ({@link com.darkcollective.relix.semantic.internal.SymbolCollector}) —
 *       registers every relation and function declared in all reachable scripts
 *       into an {@link com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable}.</li>
 *   <li><b>Schema Inference</b> ({@link com.darkcollective.relix.semantic.internal.SchemaInferenceEngine}) —
 *       walks each query body and annotates every
 *       {@link com.darkcollective.relix.ast.RelNode} with its inferred output
 *       {@link com.darkcollective.relix.symbol.Schema}.</li>
 *   <li><b>Validation</b> ({@link com.darkcollective.relix.semantic.internal.SemanticValidator}) —
 *       checks predicate attribute references, rename arity, join conditions, and
 *       function existence/arity.</li>
 *   <li><b>Model Assembly</b> — produces a {@link com.darkcollective.relix.semantic.SemanticModel}
 *       containing the namespace, symbol table, source declarations, schema
 *       annotations, and root query statements.</li>
 * </ol>
 *
 * <h2>Key classes</h2>
 * <dl>
 *   <dt>{@link com.darkcollective.relix.semantic.internal.SemanticAnalyzer}</dt>
 *   <dd>Entry point; call {@code analyze(Script)} to run the full pipeline.</dd>
 *   <dt>{@link com.darkcollective.relix.semantic.internal.SemanticResult}</dt>
 *   <dd>Returned by {@code SemanticAnalyzer}: carries errors and, on success, the
 *       {@link com.darkcollective.relix.semantic.SemanticModel}.</dd>
 *   <dt>{@link com.darkcollective.relix.semantic.SchemaAnnotations}</dt>
 *   <dd>Identity-keyed side-map from {@link com.darkcollective.relix.ast.RelNode}
 *       to inferred {@link com.darkcollective.relix.symbol.Schema}.</dd>
 *   <dt>{@link com.darkcollective.relix.function.FunctionCatalog}</dt>
 *   <dd>The functions a script may call, discovered from the installed libraries.
 *       A function enters the symbol table on first reference, so an analysed
 *       script carries the functions it calls rather than the whole library.</dd>
 *   <dt>{@link com.darkcollective.relix.semantic.internal.IrReport}</dt>
 *   <dd>Generates a concise ≤80 char/line IR report from a
 *       {@link com.darkcollective.relix.semantic.SemanticModel}.</dd>
 * </dl>
 *
 * <h2>Error reporting</h2>
 *
 * <p>{@link com.darkcollective.relix.semantic.SemanticError} carries a file path,
 * line, column, message, and {@link com.darkcollective.relix.semantic.Severity}.
 * When AST nodes have been parsed with explicit source coordinates (via
 * {@link com.darkcollective.relix.ast.SourceLocation}), errors are reported at
 * the precise source position rather than the file level.
 *
 * @see com.darkcollective.relix.semantic.internal.SemanticAnalyzer
 * @see com.darkcollective.relix.semantic.internal.SemanticResult
 * @see com.darkcollective.relix.semantic.SemanticModel
 */
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.semantic.internal.SemanticAnalyzer;

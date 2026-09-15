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
 * Language-level parser for {@code .relix} script files.
 *
 * <h2>Overview</h2>
 *
 * <p>This package provides the {@link com.darkcollective.relix.lang.ScriptParser}
 * that reads a complete {@code .relix} source file and produces a
 * {@link com.darkcollective.relix.lang.ast.Script} AST.  The script AST is then
 * consumed by the semantic analysis pipeline in the {@code relix-semantic} module.
 *
 * <h2>Entry points</h2>
 * <dl>
 *   <dt>{@link com.darkcollective.relix.lang.ScriptParser}</dt>
 *   <dd>Recursive-descent parser for full {@code .relix} scripts.  Handles
 *       {@code namespace}, {@code env}, {@code import}, {@code source},
 *       assignment ({@code :=}), {@code def}, and {@code query} statements.
 *       Embedded relational algebra bodies are delegated to
 *       {@link com.darkcollective.relix.parser.RelAlgebraParser} with offset
 *       coordinates so that source positions are accurate end-to-end.</dd>
 *   <dt>{@link com.darkcollective.relix.lang.LangLexer}</dt>
 *   <dd>Tokenizer used internally by {@code ScriptParser}.</dd>
 *   <dt>{@link com.darkcollective.relix.lang.LangParseException}</dt>
 *   <dd>Thrown on syntax errors in the script layer.</dd>
 * </dl>
 *
 * <h2>Source positions</h2>
 *
 * <p>Every {@link com.darkcollective.relix.lang.ast.Statement} produced by
 * {@code ScriptParser} carries a {@link com.darkcollective.relix.ast.SourceLocation}
 * identifying the file, line, and column where the statement began.  When a file
 * path is supplied to the parser, that path is embedded in every location,
 * enabling the semantic analyser to emit diagnostics of the form
 * {@code script.relix:12:5: error: ...}.
 *
 * @see com.darkcollective.relix.lang.ScriptParser
 * @see com.darkcollective.relix.lang.LangParseException
 * @see com.darkcollective.relix.lang.ast
 */
package com.darkcollective.relix.lang;

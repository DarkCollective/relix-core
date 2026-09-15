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
 * Low-level relational algebra expression parser.
 *
 * <h2>Overview</h2>
 *
 * <p>This package converts a relational algebra expression string into an
 * immutable AST ({@link com.darkcollective.relix.ast.RelNode}) defined in the
 * {@code relix-ast} module.  It supports the full operator vocabulary including
 * Unicode symbols (π σ ρ γ τ λ δ ⋈ ⨝ ⟕ ⟖ ⟗ ⋉ ▷ ∪ ⊎ − ∩ ÷ ×) and their
 * ASCII keyword equivalents (PROJECT, SELECT, RENAME, …).
 *
 * <h2>Entry points</h2>
 * <dl>
 *   <dt>{@link com.darkcollective.relix.parser.RelAlgebraParser}</dt>
 *   <dd>Recursive-descent parser. Use the static {@code parse} or
 *       {@code parseOperand} factory methods. Overloads that accept a
 *       {@code filePath}, {@code startLine}, and {@code startColumn} embed
 *       precise {@link com.darkcollective.relix.ast.SourceLocation} information
 *       into every produced AST node.</dd>
 *   <dt>{@link com.darkcollective.relix.parser.Lexer}</dt>
 *   <dd>Tokenizer used internally by the parser.</dd>
 *   <dt>{@link com.darkcollective.relix.parser.ParseException}</dt>
 *   <dd>Thrown on syntax errors; carries line, column, and offending lexeme.</dd>
 * </dl>
 *
 * <h2>Source positions</h2>
 *
 * <p>When the parser is constructed with an explicit file path and start
 * position (as done by the language-level parser for embedded RA bodies),
 * every produced {@link com.darkcollective.relix.ast.RelNode},
 * {@link com.darkcollective.relix.ast.Predicate}, and
 * {@link com.darkcollective.relix.ast.Operand} carries a
 * {@link com.darkcollective.relix.ast.SourceLocation} that maps back to the
 * original source file, enabling precise error diagnostics from the semantic
 * analysis phase.
 *
 * @see com.darkcollective.relix.parser.RelAlgebraParser
 * @see com.darkcollective.relix.parser.ParseException
 * @see com.darkcollective.relix.ast.SourceLocation
 */
package com.darkcollective.relix.parser;

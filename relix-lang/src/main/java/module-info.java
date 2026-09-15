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
 * Scripting language lexer and parser for {@code .relix} files.
 *
 * <p>The primary entry point is {@link com.darkcollective.relix.lang.ScriptParser},
 * whose static {@code parse} methods accept a {@link String} or
 * {@link java.io.InputStream} and return a fully-parsed
 * {@link com.darkcollective.relix.lang.ast.Script} AST.
 *
 * <p>Relational-algebra expression blocks ({@code { ... }} bodies inside
 * assignment statements, query statements, and {@code def} bodies) are
 * delegated eagerly to
 * {@link com.darkcollective.relix.parser.RelAlgebraParser} and stored as
 * {@link com.darkcollective.relix.ast.RelNode} / {@link com.darkcollective.relix.ast.Operand}
 * values in the AST — there are no raw string blobs in the parse result.
 *
 * <p>{@link com.darkcollective.relix.lang.LangParseException} is the single
 * exception type thrown on syntax errors; it extends the core-level
 * {@link com.darkcollective.relix.lang.ast.ScriptParseException}, which is what
 * the engine's {@code ScriptLoader} seam declares.
 */
module com.darkcollective.relix.lang {
    requires com.darkcollective.relix.lang.ast;
    requires com.darkcollective.relix.parser;

    exports com.darkcollective.relix.lang;
}

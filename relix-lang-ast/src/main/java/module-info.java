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
 * AST node types for the relix scripting language.
 *
 * <p>This module contains the pure data structures that represent a parsed
 * {@code .relix} script.  It has no dependency on any parser and may be used
 * independently by semantic analysis, tooling, or pretty-printing layers.
 *
 * <p>Key packages:
 * <ul>
 *   <li>{@code com.darkcollective.relix.lang.ast} — root script and statement
 *       types ({@code Script}, {@code Statement} hierarchy), plus
 *       {@code ScriptParseException}, the core-level failure signal every
 *       frontend that produces a {@code Script} throws a subtype of
 *.</li>
 *   <li>{@code com.darkcollective.relix.lang.ast.source} — source-declaration
 *       AST ({@code SourceConfig} hierarchy, {@code ColumnSpec}, bindings,
 *       extract specs, pagination).</li>
 *   <li>{@code com.darkcollective.relix.lang.ast.table} — inline table
 *       representations ({@code MarkdownInlineTable}, {@code CsvInlineTable}).</li>
 * </ul>
 */
module com.darkcollective.relix.lang.ast {
    requires transitive com.darkcollective.relix.symbol;
    // com.darkcollective.relix.ast is visible transitively via relix-symbol → relix-ast chain

    exports com.darkcollective.relix.lang.ast;
    exports com.darkcollective.relix.lang.ast.source;
    exports com.darkcollective.relix.lang.ast.table;
}

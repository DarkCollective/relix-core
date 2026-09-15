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
 * Lexer, parser, and related types for the relix relational algebra language.
 *
 * <p>The primary entry point is {@link com.darkcollective.relix.parser.RelAlgebraParser},
 * which parses a relational algebra expression string into an
 * {@link com.darkcollective.relix.ast.RelNode} AST.
 *
 * <p>The {@link com.darkcollective.relix.parser.ParseException} is the single
 * exception type thrown on syntax errors; it carries line, column, and a
 * source-pointer diagnostic string.
 */
module com.darkcollective.relix.parser {
    requires transitive com.darkcollective.relix.ast;

    exports com.darkcollective.relix.parser;
}

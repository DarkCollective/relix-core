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
 * Relational algebra AST — immutable node types, predicates, operands, and
 * visitor infrastructure for traversal and transformation.
 *
 * <p>This module carries no runtime dependencies beyond the JDK, making it safe
 * to embed in any context that needs a structured, in-memory representation of
 * relational algebra expressions.
 *
 * <p>The public API is organised around three sealed hierarchies, each traversed
 * via a dedicated visitor interface:
 * <ul>
 *   <li>{@link com.darkcollective.relix.ast.RelNode} — relational operations
 *       (selection, projection, join, aggregation, …)</li>
 *   <li>{@link com.darkcollective.relix.ast.Predicate} — boolean filter
 *       conditions used in selections and join conditions</li>
 *   <li>{@link com.darkcollective.relix.ast.Operand} — scalar value expressions
 *       (attributes, literals, arithmetic, function calls, …)</li>
 * </ul>
 *
 * <p>A concrete {@link com.darkcollective.relix.ast.visitor.PrettyPrinter}
 * implementation is bundled in the {@code visitor} subpackage and is the
 * reference example for writing new visitors.
 *
 * @see com.darkcollective.relix.ast.RelNode
 * @see com.darkcollective.relix.ast.visitor.PrettyPrinter
 */
module com.darkcollective.relix.ast {
    exports com.darkcollective.relix.ast;
    exports com.darkcollective.relix.ast.visitor;
}

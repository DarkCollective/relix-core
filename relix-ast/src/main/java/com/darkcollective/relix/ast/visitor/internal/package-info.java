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
 * The engine-side machinery behind {@code com.darkcollective.relix.ast.visitor}: not exported by the published
 * artifact. What follows describes the package as a whole, as it stood before the split.
 *
 * Visitor interfaces and bundled implementations for traversing the relational
 * algebra AST.
 *
 * <h2>Visitor interfaces</h2>
 *
 * <p>Three generic visitor interfaces mirror the three sealed hierarchies in the
 * parent package:
 *
 * <ul>
 *   <li>{@link com.darkcollective.relix.ast.visitor.RelNodeVisitor RelNodeVisitor&lt;R&gt;}
 *       — one {@code visit} method per {@link com.darkcollective.relix.ast.RelNode}
 *       implementation.</li>
 *   <li>{@link com.darkcollective.relix.ast.visitor.OperandVisitor OperandVisitor&lt;R&gt;}
 *       — one {@code visit} method per {@link com.darkcollective.relix.ast.Operand}
 *       implementation.</li>
 *   <li>{@link com.darkcollective.relix.ast.visitor.PredicateVisitor PredicateVisitor&lt;R&gt;}
 *       — one {@code visit} method per {@link com.darkcollective.relix.ast.Predicate}
 *       implementation.</li>
 * </ul>
 *
 * <p>Because the root sealed interfaces are exhaustive, implementing a visitor
 * interface guarantees that every node type is handled — the compiler will flag
 * any missing {@code visit} overload.
 *
 * <h2>Bundled implementations</h2>
 *
 * <ul>
 *   <li>{@link com.darkcollective.relix.ast.visitor.internal.PrettyPrinter} — converts an
 *       entire {@link com.darkcollective.relix.ast.RelNode} tree to a Unicode
 *       relational algebra string. Use it directly via
 *       {@link com.darkcollective.relix.ast.RelNode#prettyPrint()}.</li>
 *   <li>{@link com.darkcollective.relix.ast.visitor.internal.OperandPrettyPrinter} — formats
 *       operand expressions, handling operator precedence and string escaping.</li>
 *   <li>{@link com.darkcollective.relix.ast.visitor.internal.PredicatePrettyPrinter} — formats
 *       predicate conditions using Unicode logical and comparison symbols.</li>
 * </ul>
 *
 * @see com.darkcollective.relix.ast.visitor.internal.PrettyPrinter
 */
package com.darkcollective.relix.ast.visitor.internal;

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
 * Immutable AST node types for relational algebra expressions.
 *
 * <h2>Overview</h2>
 *
 * <p>The package models the full vocabulary of relational algebra using three sealed
 * interface hierarchies:
 *
 * <ul>
 *   <li>{@link com.darkcollective.relix.ast.RelNode} — relational operations that
 *       consume or produce relations (tables). Implementations cover the classic
 *       unary operators ({@link com.darkcollective.relix.ast.SelectionNode σ},
 *       {@link com.darkcollective.relix.ast.ProjectionNode π},
 *       {@link com.darkcollective.relix.ast.RenameNode ρ},
 *       {@link com.darkcollective.relix.ast.DistinctNode δ}), binary set and join
 *       operators (∪ ⊎ − ∩ ÷ ×, ⋈ ⨝ ⟕ ⟖ ⟗ ⋉ ▷), and extended operators
 *       ({@link com.darkcollective.relix.ast.AggregationNode γ},
 *       {@link com.darkcollective.relix.ast.SortNode τ},
 *       {@link com.darkcollective.relix.ast.LimitNode λ}).</li>
 *
 *   <li>{@link com.darkcollective.relix.ast.Predicate} — boolean conditions used
 *       in selections and join conditions. Includes comparison, logical connectives
 *       (∧ ∨ ¬), null testing (⊥), and set membership (∈ ∉).</li>
 *
 *   <li>{@link com.darkcollective.relix.ast.Operand} — scalar value expressions
 *       appearing in projections and predicates. Covers attribute references,
 *       literals (string, number, boolean), arithmetic expressions, function calls,
 *       set literals, and unary negation.</li>
 * </ul>
 *
 * <h2>Design</h2>
 *
 * <p>All node types are Java {@code record}s — they are value-based, thread-safe,
 * and structurally equal (two nodes with the same fields compare as equal). Lists
 * inside records are defensively copied to unmodifiable views. Constructor
 * validation throws {@link java.lang.NullPointerException} for null required fields
 * and {@link java.lang.IllegalArgumentException} for semantically invalid values
 * (e.g., blank names, empty projection attribute lists).
 *
 * <p>The {@link com.darkcollective.relix.ast.RelNode#prettyPrint()} default method
 * produces a Unicode relational algebra string that can be round-tripped through
 * the parser.
 *
 * <h2>Visitor pattern</h2>
 *
 * <p>Every node type exposes an {@code accept} method typed to the corresponding
 * visitor interface in {@link com.darkcollective.relix.ast.visitor}. Implement one
 * of the three visitor interfaces to add new traversals without modifying the node
 * classes.
 *
 * @see com.darkcollective.relix.ast.RelNode
 * @see com.darkcollective.relix.ast.Predicate
 * @see com.darkcollective.relix.ast.Operand
 * @see com.darkcollective.relix.ast.visitor
 */
package com.darkcollective.relix.ast;

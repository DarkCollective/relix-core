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
 * Cost estimation for relix relational algebra trees.
 *
 * <p>A small, tier-based cost model shared by the query optimizer and the
 * execution engine, so that neither has to depend on the other.
 *
 * <h2>Core types</h2>
 * <ul>
 *   <li>{@link com.darkcollective.relix.cost.CostTier} — an ordinal cost class
 *       ({@code INLINE < VIEW < FILE < REMOTE}) describing how expensive a
 *       relation is to read.</li>
 *   <li>{@link com.darkcollective.relix.cost.CostEstimator} — walks a
 *       {@link com.darkcollective.relix.ast.RelNode} tree, resolving leaf
 *       relations against a {@link com.darkcollective.relix.symbol.table.SymbolTable},
 *       and returns the dominating {@code CostTier}.</li>
 * </ul>
 *
 * <h2>Consumers</h2>
 * <ul>
 *   <li>The optimizer uses it to order join inputs (cheaper side first).</li>
 *   <li>The execution engine uses it to pick the build side of a hash join.</li>
 * </ul>
 */
package com.darkcollective.relix.cost;

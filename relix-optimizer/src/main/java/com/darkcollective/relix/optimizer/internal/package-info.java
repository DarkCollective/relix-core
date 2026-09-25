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
 * The engine-side machinery behind {@code com.darkcollective.relix.optimizer}: not exported by the published
 * artifact. What follows describes the package as a whole, as it stood before the split.
 *
 * Query optimizer for Relix relational algebra expressions.
 *
 * <p>The optimizer takes a fully-analysed
 * {@link com.darkcollective.relix.semantic.SemanticModel} and rewrites each
 * query's {@link com.darkcollective.relix.ast.RelNode} tree to a semantically
 * equivalent but more efficient form.
 *
 * <h2>Core types</h2>
 * <ul>
 *   <li>{@link com.darkcollective.relix.optimizer.internal.QueryOptimizer} — entry
 *       point; applies all rule phases and returns per-query
 *       {@link com.darkcollective.relix.optimizer.internal.OptimizationResult}s.</li>
 *   <li>{@link com.darkcollective.relix.optimizer.internal.OptimizationRule} — interface
 *       implemented by every individual transformation rule.</li>
 *   <li>{@link com.darkcollective.relix.optimizer.OptimizationCode} — stable
 *       enumeration of every known rule, each with a unique code string and
 *       human-readable description.</li>
 *   <li>{@link com.darkcollective.relix.optimizer.TransformationRecord} —
 *       immutable record of a single rule firing: code, relation name, detail,
 *       and source location.</li>
 *   <li>{@link com.darkcollective.relix.optimizer.internal.OptimizationContext} —
 *       mutable collector that accumulates records during an optimization run.</li>
 *   <li>{@link com.darkcollective.relix.optimizer.internal.OptimizationResult} —
 *       immutable result for one query: original tree, optimized tree, and
 *       the list of transformations applied.</li>
 *   <li>the console's optimization report —
 *       renders an 80-column audit report in the same style as
 *       {@link com.darkcollective.relix.semantic.internal.IrReport}.</li>
 * </ul>
 */
package com.darkcollective.relix.optimizer.internal;

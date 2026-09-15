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
 * Execution operators for the relix processor.
 *
 * <p>This package contains the recursive {@link
 * com.darkcollective.relix.processor.exec.RelNodeExecutor}
 * that walks a {@link com.darkcollective.relix.ast.RelNode} tree and produces a lazy
 * {@code Stream<}{@link com.darkcollective.relix.processor.Row}{@code >} pipeline.
 * All 21 node types of the sealed {@link com.darkcollective.relix.ast.RelNode} hierarchy
 * are implemented.
 *
 * <h2>Streaming vs. materialising operators</h2>
 *
 * <p>Unary <em>streaming</em> operators ({@code Selection}, {@code Projection},
 * {@code Rename}, {@code Limit}, {@code Distinct}, {@code UnionAll}) chain directly
 * onto the input stream without intermediate materialisation.
 *
 * <p>Operators that require full materialisation collect the input into an appropriate
 * {@link com.darkcollective.relix.processor.exec.MaterializedRelation} collection type
 * before returning a fresh stream:
 * <ul>
 *   <li>{@link com.darkcollective.relix.processor.exec.BagRelation} — ordered, duplicates allowed
 *       (Sort, Aggregation, UnionAll output, outer-join unmatched rows)</li>
 *   <li>{@link com.darkcollective.relix.processor.exec.SetRelation} — insertion-order,
 *       deduplicated (Union, Intersection, Difference)</li>
 *   <li>{@link com.darkcollective.relix.processor.exec.SortedBagRelation} — sorted, duplicates
 *       allowed (Sort output)</li>
 *   <li>{@link com.darkcollective.relix.processor.exec.IndexedRelation} — grouped by key
 *       (Aggregation intermediate)</li>
 * </ul>
 *
 * <h2>Binary join algorithms</h2>
 * <p>All joins use nested-loop.  The right-hand side is always fully materialised;
 * the left-hand side is streamed where possible ({@code Product}, {@code ThetaJoin},
 * {@code LeftOuterJoin}, {@code SemiJoin}, {@code AntiJoin}) and materialised where
 * the algorithm requires random access ({@code NaturalJoin}, {@code RightOuterJoin},
 * {@code FullOuterJoin}).
 *
 * <h2>Set operations</h2>
 * <p>{@code Union} deduplicates via {@link com.darkcollective.relix.processor.exec.SetRelation}.
 * {@code Intersection} and {@code Difference} materialise the right side as a set for
 * O(1) membership tests while streaming the left side.  {@code Division} uses the
 * nested-loop "for-all" check with a hash-set lookup of full left-row tuples.
 */
package com.darkcollective.relix.processor.exec;

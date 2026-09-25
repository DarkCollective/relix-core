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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Optimization pass that eliminates a redundant outer aggregation stacked
 * directly on top of another aggregation ({@code AGG-001}).
 *
 * <h2>Rule</h2>
 * <pre>{@code
 *   γ cols [] (γ keys, aggs (R))  →  γ keys, aggs (R)
 * }</pre>
 * <p>The rule fires when the <em>outer</em> {@link AggregationNode}
 * <ul>
 *   <li>has <strong>no aggregate functions</strong> of its own, and</li>
 *   <li>groups by a key set that is exactly the inner aggregation's
 *       <em>output columns</em> (its grouping keys plus the output name of each
 *       inner aggregate).</li>
 * </ul>
 *
 * <p>An aggregation emits exactly one row per distinct grouping-key
 * combination, so its output is already distinct on its own output columns.
 * Re-grouping that output by the full set of those columns, without computing
 * any new aggregate, therefore reproduces the inner result row-for-row and
 * column-for-column — the outer grouping is pure redundant work and is removed.
 *
 * <p>The output-column names are derived exactly as schema inference derives
 * them: a grouping attribute keeps its name, and an aggregate uses its
 * {@code alias} when present, otherwise the synthetic
 * {@code operator_attribute} name (e.g. {@code sum_amount}).  Because the rule
 * needs only the node itself, it requires no {@link SchemaAnnotations}.
 *
 * <p>The comparison is <em>set</em>-based, so the outer keys may list the inner
 * columns in any order; the surviving inner node keeps its own canonical column
 * order.  The pass deliberately does <em>not</em> fire when the outer
 * aggregation has its own aggregates, or when its key set differs from the
 * inner output columns (e.g. a strict subset), because those genuinely change
 * the result — they are not redundant.
 *
 * <p>The pass is <em>bottom-up</em>: each input is rewritten before the rule is
 * attempted at the current node, so a stack of three or more aggregations
 * collapses from the inside out within a <em>single</em> application — the node
 * above sees the already-collapsed input and tests the rule against that.  It
 * therefore needs no re-sweep of its {@linkplain OptimizationPipeline phase}
 * (pinned by {@code PipelineConvergenceTest}).
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
public final class RedundantGroupingPass {

    private RedundantGroupingPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies redundant-grouping elimination (AGG-001) to the entire tree
     * rooted at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations; unused by this pass but accepted for
     *                  API consistency with the other passes
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, schemas, ctx);
    }

    // =========================================================================
    // RelNode traversal (bottom-up)
    // =========================================================================

    private static RelNode rewriteNode(RelNode node, String queryName,
                                        SchemaAnnotations schemas,
                                        OptimizationContext ctx) {
        return switch (node) {

            // ── γ: recurse into input, then try AGG-001 ───────────────────────
            case AggregationNode a -> {
                RelNode newInput = rewriteNode(a.input(), queryName, schemas, ctx);
                AggregationNode current = (newInput != a.input())
                        ? new AggregationNode(a.groupingKeys(), a.aggregates(),
                                              newInput, a.location())
                        : a;

                // AGG-001: γ cols [] (γ keys, aggs (R)) → γ keys, aggs (R)
                if (current.aggregates().isEmpty()
                        && current.input() instanceof AggregationNode inner
                        && current.groupingKeys().stream()
                                .map(GroupingKey::outputName)
                                .collect(java.util.stream.Collectors.toSet())
                                .equals(outputColumns(inner))) {
                    ctx.record(OptimizationCode.AGG_001, queryName,
                            "redundant outer aggregation removed; "
                            + "inner aggregation already emits distinct grouping keys",
                            current.location());
                    yield inner;
                }
                yield current;
            }

            default -> node.mapChildren(child -> rewriteNode(child, queryName, schemas, ctx));
        };
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Returns the set of output column names produced by {@code agg}, using the
     * same naming rule as schema inference: a grouping attribute keeps its name;
     * an aggregate uses its alias if present, else {@code operator_attribute}.
     */
    private static Set<String> outputColumns(AggregationNode agg) {
        List<String> names = new ArrayList<>();
        for (GroupingKey key : agg.groupingKeys()) {
            names.add(key.outputName());
        }
        for (AggregateFunction fn : agg.aggregates()) {
            names.add(fn.outputName());
        }
        return new HashSet<>(names);
    }
}

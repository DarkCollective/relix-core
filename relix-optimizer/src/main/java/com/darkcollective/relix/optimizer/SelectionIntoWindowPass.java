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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

/**
 * Partition-pruning pass (ADR-0020 — selection pushdown into expensive
 * operators) for the partitioned window operators {@link WindowNode} and
 * {@link TopKNode} ({@code WINDOW-001} / {@code TOPK-001}).
 *
 * <p>Both operators compute their result independently per partition: a
 * window frame never crosses a {@code PARTITION BY} boundary, and a top-k is
 * ranked within each {@code PER} group. So an equality on a partition key sitting
 * above the operator selects exactly one (or, for repeated keys, a fixed set of)
 * partition(s), and pushing it <em>below</em> the operator yields an identical
 * result while computing only the matching partition:
 *
 * <pre>
 *   σ region = "WEST" (WINDOW … PARTITION BY region … (Sales))
 *       →  WINDOW … PARTITION BY region … (σ region = "WEST" (Sales))
 *
 *   σ region = "WEST" (TOP 3 amount DESC PER region (Orders))
 *       →  TOP 3 amount DESC PER region (σ region = "WEST" (Orders))
 * </pre>
 *
 * <p>The pushed equality is removed from above the operator — after the operator
 * runs over only the matching partition every output row already satisfies it, so
 * the residual {@code σ} would be a no-op.
 *
 * <p>What counts as pushable — a top-level {@code =} conjunct against a constant on
 * a bare partition column, everything else left as a residual σ — and the traversal
 * that applies it belong to {@link PartitionPruning}, shared with the three sibling
 * rules. This class supplies only the two node shapes it owns. A window with an
 * empty partition list has no partition dimension and is left alone.
 *
 * <p>A predicate on the <em>computed</em> window column (e.g. {@code rk ≤ 3}) is not
 * a partition equality and stays above.
 *
 * <p>{@code IN {…}} multi-partition pruning and top-k <em>limit</em> pushdown (a
 * predicate on the computed rank column) are noted as follow-ons in ADR-0020 and
 * are out of scope for this slice.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class SelectionIntoWindowPass {

    private SelectionIntoWindowPass() {}

    /**
     * Applies partition pruning (WINDOW-001 / TOPK-001) to the entire tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations (not used by this pass — partition keys are
     *                  read directly off the operator)
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return PartitionPruning.apply(node, queryName, ctx, SelectionIntoWindowPass::target);
    }

    /** The two partitioned window operators, as {@link PartitionPruning} describes them. */
    private static PartitionPruning.Target target(RelNode node) {
        return switch (node) {
            case WindowNode w -> PartitionPruning.Target.of(
                    OptimizationCode.WINDOW_001, w.partitionKeys(), w.input(),
                    input -> new WindowNode(w.function(), w.partitionKeys(), w.sortSpecs(),
                            w.frame(), w.outputColumn(), input, w.location()),
                    "partition", "WINDOW", "other partitions not computed");
            case TopKNode t -> PartitionPruning.Target.of(
                    OptimizationCode.TOPK_001, t.groupingAttributes(), t.input(),
                    input -> new TopKNode(t.groupingAttributes(), t.sortSpecs(), t.offset(),
                            t.count(), input, t.location()),
                    "partition", "TOP", "other partitions not computed");
            default -> null;
        };
    }
}

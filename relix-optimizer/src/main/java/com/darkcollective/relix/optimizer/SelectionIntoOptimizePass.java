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

import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

/**
 * Group-pruning pass (ADR-0020 — selection pushdown into expensive operators)
 * for the declarative-solver operator {@link OptimizeNode} ({@code OPTIMIZE-001}).
 *
 * <p>{@code OPTIMIZE} solves an independent optimisation problem (a MIP subset
 * choice, or an LP allocation) <em>per group</em> — the groups named by its
 * {@code PER} keys never interact. So an equality on a grouping key sitting above
 * the operator selects exactly one (or, for repeated keys, a fixed set of)
 * group(s), and pushing it <em>below</em> the operator yields an identical result
 * while solving only the matching group's problem:
 *
 * <pre>
 *   σ region = "WEST" (OPTIMIZE MAXIMIZE SUM(value)
 *                        SUBJECT TO SUM(weight) &lt;= 100 PER region (Candidates))
 *       →  OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) &lt;= 100 PER region
 *            (σ region = "WEST" (Candidates))
 * </pre>
 *
 * <p>Because the solver's search is the dominant cost, skipping every other
 * group's MIP/LP is the high-value win of this slice — and the pushed equality is
 * removed from above the operator (after solving only the matching group every
 * output row already satisfies it, so the residual {@code σ} would be a no-op).
 *
 * <p>What counts as pushable — a top-level {@code =} conjunct against a constant on a
 * bare {@code PER} key, everything else left as a residual σ — and the traversal that
 * applies it belong to {@link PartitionPruning}, shared with the sibling rules. This is
 * the <em>partitioning-dimension</em> category of ADR-0020 Decision 2: the grouping key
 * indexes independent sub-problems, so filtering the output by it equals solving only
 * that group.
 *
 * <p>A predicate on a <em>non-grouping</em> column is deliberately <strong>not</strong>
 * pushed. Under {@code OPTIMIZE}'s emit-the-optimum semantics, removing a candidate row
 * from the input changes the optimum — a row that would have been chosen and then
 * discarded by the post-filter is not the same as a row the solver never saw. Only the
 * group dimension partitions the computation.
 *
 * <p>An {@code OPTIMIZE} with no {@code PER} keys (the whole-relation single group) has
 * no grouping dimension and is left alone. The sibling
 * {@link com.darkcollective.relix.ast.SolveNode} ({@code SOLVE}) is a per-row equation
 * goal-seek with no search space or partition dimension, so it is out of scope entirely.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class SelectionIntoOptimizePass {

    private SelectionIntoOptimizePass() {}

    /**
     * Applies group pruning (OPTIMIZE-001) to the entire tree rooted at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations (not used by this pass — grouping keys are
     *                  read directly off the operator)
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return PartitionPruning.apply(node, queryName, ctx, SelectionIntoOptimizePass::target);
    }

    /** The solver operator, as {@link PartitionPruning} describes it. */
    private static PartitionPruning.Target target(RelNode node) {
        if (!(node instanceof OptimizeNode o)) {
            return null;
        }
        return PartitionPruning.Target.of(
                OptimizationCode.OPTIMIZE_001, o.groupingKeys(), o.input(),
                input -> new OptimizeNode(o.sense(), o.objective(), o.constraints(),
                        o.groupingKeys(), o.allocation(), input, o.location()),
                "group", "OPTIMIZE", "other groups not solved");
    }
}

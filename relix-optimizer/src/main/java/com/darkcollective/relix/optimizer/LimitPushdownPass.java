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

import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

import java.util.List;
import java.util.Optional;

/**
 * Optimization pass for the limit operator ({@code λ}) — {@code LIM-001..004}.
 *
 * <h2>Rules</h2>
 * <pre>{@code
 *   LIM-001   λ(off, n)(π A (R))    →  π A (λ(off, n)(R))
 *   LIM-002   λ(off, n)(ρ … (R))    →  ρ … (λ(off, n)(R))
 *   LIM-003   λ(off, n)(τ k (R))    →  TOP n OFFSET off ORDER BY k (R)
 *   LIM-004   λ(off, n)(A ⊎ B)      →  λ(off, n)((λ off+n A) ⊎ (λ off+n B))
 * }</pre>
 *
 * <p>{@code LIM-001} and {@code LIM-002} push past operators that are
 * <em>row-count and order neutral</em> — projection narrows the column set and
 * rename touches names only, so neither can filter or reorder rows.  Discarding
 * the excess first means only the rows actually wanted are projected or renamed.
 *
 * <p>{@code LIM-003} is the classic <strong>top-N</strong> rewrite.  A limit over a
 * sort is exactly what {@link TopKNode} means, so the pair is fused into one
 * operator: instead of materializing and sorting the whole input to then throw all
 * but {@code n} rows away, the executor keeps a bounded heap.  The produced
 * {@code TOP} has <em>no</em> grouping attributes — one global group — which is the
 * form {@code λ}/{@code τ} expresses and which has no surface syntax of its own
 * ({@code TOP … PER …} always names a key).  The λ's offset carries across
 * unchanged, since {@code TopKNode} skips before it takes.
 *
 * <p>{@code LIM-004} bounds each branch of a union-all: the union cannot use more
 * than {@code offset + count} rows from either side, so producing more is wasted
 * work — and over a pushdown-capable source the branch limit reaches the backend.
 * The outer λ must remain, because a branch may supply fewer rows than the bound
 * and because the offset applies to the union, not to either branch.
 *
 * <p>The rule does <em>not</em> apply to other operators that sit between a limit
 * and its source:
 * <ul>
 *   <li>Selections ({@code σ}) are row-reducing; pushing a limit below a
 *       selection would cause fewer than {@code n} rows to survive the
 *       selection, producing a wrong answer.</li>
 *   <li>Aggregation, distinct, and the set operations all affect cardinality in
 *       non-trivial ways.</li>
 * </ul>
 *
 * <p>The pass is <em>bottom-up</em>: the input tree is recursed before the limit
 * rules are attempted, so a stacked {@code λ(π(π(R)))} structure will first have
 * the inner projection merged by {@link ProjectionPass} (in the preceding cleanup
 * phase) and then have the limit pushed below the merged result.  A successful
 * push retries from the new position, so a {@code λ(π(ρ(τ(R))))} chain descends
 * all the way to the top-N fusion in one visit.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class LimitPushdownPass {

    private LimitPushdownPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies limit pushdown ({@code LIM-001..004}) to the entire tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations; unused by this pass but accepted
     *                  for API consistency with other passes
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

            // ── λ: recurse into the input, then try to push/fuse ──────────────
            case LimitNode l -> {
                RelNode newInput = rewriteNode(l.input(), queryName, schemas, ctx);
                LimitNode current = (newInput != l.input())
                        ? new LimitNode(l.offset(), l.count(), newInput, l.location()) : l;
                yield pushDown(current, queryName, ctx);
            }

            // ── Bottom-up traversal for all other nodes (leaves recurse over
            //    zero children and are returned unchanged) ──────────────────────
            default -> node.mapChildren(child -> rewriteNode(child, queryName, schemas, ctx));
        };
    }

    // =========================================================================
    // The λ rules
    // =========================================================================

    /**
     * Applies whichever λ rule matches {@code l}'s input, returning {@code l}
     * unchanged when none does.  A push recurses from the limit's new position, so
     * a chain of row-neutral operators is descended in one visit; each step moves
     * the λ strictly deeper, so the recursion terminates.
     */
    private static RelNode pushDown(LimitNode l, String queryName, OptimizationContext ctx) {
        switch (l.input()) {

            // ── LIM-001: λ(off, n)(π A (R)) → π A (λ(off, n)(R)) ──────────────
            case ProjectionNode p -> {
                ctx.record(OptimizationCode.LIM_001, queryName,
                        "limit pushed below projection to reduce projected row count",
                        l.location());
                return new ProjectionNode(p.attributes(), below(l, p.input(), queryName, ctx),
                        p.location());
            }

            // ── LIM-002: λ(off, n)(ρ … (R)) → ρ … (λ(off, n)(R)) ──────────────
            case RenameNode r -> {
                ctx.record(OptimizationCode.LIM_002, queryName,
                        "limit pushed below rename (rename is row-count neutral)",
                        l.location());
                return r.withInput(below(l, r.input(), queryName, ctx));
            }

            // ── LIM-003: λ(off, n)(τ k (R)) → TOP n OFFSET off ORDER BY k (R) ─
            case SortNode t -> {
                ctx.record(OptimizationCode.LIM_003, queryName,
                        "limit over sort fused into TOP " + l.count()
                                + " (bounded heap instead of a full sort)",
                        l.location());
                return new TopKNode(List.of(), t.sortSpecs(), l.offset(),
                        l.count(), t.input(), l.location());
            }

            // ── LIM-004: λ(off, n)(A ⊎ B) → λ(off, n)((λ b A) ⊎ (λ b B)) ──────
            case UnionAllNode u when !alreadyBounded(u, branchBound(l)) -> {
                long bound = branchBound(l);
                ctx.record(OptimizationCode.LIM_004, queryName,
                        "limit replicated into both branches of union-all "
                                + "(neither branch can contribute more than " + bound + " rows)",
                        l.location());
                RelNode left  = boundBranch(u.left(), bound, l, queryName, ctx);
                RelNode right = boundBranch(u.right(), bound, l, queryName, ctx);
                return new LimitNode(l.offset(), l.count(),
                        new UnionAllNode(left, right, u.location()), l.location());
            }

            default -> {
                return l;
            }
        }
    }

    /** The limit relocated onto {@code input}, then pushed further from there. */
    private static RelNode below(LimitNode l, RelNode input, String queryName,
                                 OptimizationContext ctx) {
        return pushDown(new LimitNode(l.offset(), l.count(), input, l.location()), queryName, ctx);
    }

    /**
     * The most rows a {@code ⊎} branch can contribute to {@code λ(offset, count)}:
     * the outer limit skips {@code offset} rows of the <em>union</em>, and in the
     * worst case every skipped row came from this branch, so the branch bound is
     * {@code offset + count} (saturating).
     */
    private static long branchBound(LimitNode l) {
        long offset = l.offset().orElse(0L);
        long sum = l.count() + offset;
        return (sum < 0) ? Long.MAX_VALUE : sum;   // overflow → no useful bound, but stay positive
    }

    /** A branch capped at {@code bound}, with the cap pushed onwards from there. */
    private static RelNode boundBranch(RelNode branch, long bound, LimitNode l,
                                       String queryName, OptimizationContext ctx) {
        return pushDown(new LimitNode(Optional.empty(), bound, branch, l.location()),
                queryName, ctx);
    }

    /**
     * Whether both branches of {@code u} are already capped at {@code bound} or
     * tighter.  Without this guard the rewrite is not idempotent: re-running the
     * optimizer over its own output would stack another pair of limits every time.
     * The walk peels the row-neutral wrappers {@code LIM-001}/{@code LIM-002} may
     * have left above the branch limit, since that is exactly the shape this rule
     * produces.
     */
    private static boolean alreadyBounded(UnionAllNode u, long bound) {
        return boundedAtMost(u.left(), bound) && boundedAtMost(u.right(), bound);
    }

    private static boolean boundedAtMost(RelNode branch, long bound) {
        return switch (branch) {
            case LimitNode l    -> l.offset().orElse(0L) + l.count() <= bound;
            case ProjectionNode p -> boundedAtMost(p.input(), bound);
            case RenameNode r   -> boundedAtMost(r.input(), bound);
            default             -> false;
        };
    }
}

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

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AstEquivalence;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.cost.PropertyDeriver;
import com.darkcollective.relix.semantic.SchemaAnnotations;

/**
 * Cleanup pass that puts selections back together — the two rules that turn several
 * filters into one.
 *
 * <h2>{@code SEL-002} — adjacent selections</h2>
 * <p>{@code σ(A)(σ(B)(R)) → σ(A ∧ B)(R)}.  Multiple adjacent selections are collapsed
 * in a single pass: {@code σ(A)(σ(B)(σ(C)(R))) → σ(A ∧ B ∧ C)(R)} (two firings,
 * bottom-up).  This is the inverse of {@link SelectionSplitPass}.
 *
 * <h2>{@code SEL-010} — a set operation over two selections of one input</h2>
 * <pre>{@code
 *   σ i (A)  ∪  σ q (A)   →   δ σ (i ∨ q) (A)
 *   σ i (A)  ∩  σ q (A)   →   δ σ (i ∧ q) (A)
 *   σ i (A)  −  σ q (A)   →   δ σ (i ∧ (¬q ∨ q IS UNKNOWN)) (A)
 * }</pre>
 * <p>When both branches filter the <em>same</em> input, the operation is doing by
 * row-matching what one predicate does by evaluation.  The rewrite deletes a blocking
 * operator outright — {@code ∪} and {@code ∩} each buffer a hash set of one side — and
 * leaves one filtered read where there were two.  It also restores pushdown: the SQL
 * renderer folds σ but not {@code UNION}, so the set operation is a boundary the fold
 * cannot cross, and one {@code WHERE} crosses it.
 *
 * <p>The two inputs are compared with {@link AstEquivalence}, which is location-free, so
 * the same sub-expression written out twice matches where record equality never could.
 *
 * <h3>Why the δ, and when it is dropped</h3>
 * <p>{@code ∪} and {@code ∩} declare {@link com.darkcollective.relix.ast.MaterializationMode#SET}
 * and {@code PropertyDeriver} reports their output duplicate-free on the strength of
 * that declaration — which {@code DIST-001} then reads.  A merged σ over a bag input does
 * <em>not</em> de-duplicate, so handing one back bare would make a claim the input need
 * not honour, and a δ removed above it would not come back.  The δ is therefore emitted
 * unless the input is provably duplicate-free, which is the same question
 * {@code DIST-001} asks; either way the set operation is gone.
 *
 * <h3>Gates</h3>
 * <ul>
 *   <li><b>Reproducibility.</b>  The rewrite evaluates the input once where the query
 *       evaluated it twice, and evaluates each predicate once per row rather than once
 *       per branch, so both branches must be deterministic
 *       ({@link DeterminismSource} on the context).</li>
 *   <li><b>{@code −} is not {@code i ∧ ¬q}.</b>  A row whose {@code q} is UNKNOWN is
 *       absent from {@code σq(A)} and so <em>kept</em> by the difference, while
 *       {@code ¬UNKNOWN} is UNKNOWN and a bare {@code ¬q} drops it.
 *       {@link SelectionComplement} writes the correction.</li>
 *   <li><b>{@code ⊎} is excluded</b> — bag union, so the multiplicities are the answer —
 *       and so is {@code ∆}, which would need an XOR the predicate language does not
 *       have.</li>
 * </ul>
 *
 * <h3>Phase placement</h3>
 * <p>{@code SEL-010} is the exact inverse of {@code SEL-009}, which distributes a σ over
 * the set operations.  They are in different phases for the reason
 * {@code SEL-001}/{@code SEL-002} are: put both in one phase and the sweep trades the two
 * shapes forever.  {@code SEL-009} runs in {@code pushdown} and this pass in
 * {@code cleanup}, which is also the useful order — a σ that was distributed and could
 * then be carried no further is merged back into one.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class SelectionMergePass {

    private SelectionMergePass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies adjacent selection merging (SEL-002) to the entire tree rooted
     * at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations (not used by this pass)
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, ctx);
    }

    // =========================================================================
    // RelNode traversal
    // =========================================================================

    private static RelNode rewriteNode(RelNode node, String queryName, OptimizationContext ctx) {
        return switch (node) {

            // ── σ: after recursing into input, try to merge with inner σ ─────
            case SelectionNode s -> {
                RelNode newInput = rewriteNode(s.input(), queryName, ctx);
                SelectionNode current = (newInput != s.input())
                        ? new SelectionNode(s.predicate(), newInput, s.location()) : s;
                yield mergeAdjacent(current, queryName, ctx);
            }

            // ── ∪ / ∩: recurse into both branches, then try SEL-010 ──────────
            // Recursing first is what lets SEL-002 settle each branch into a single σ
            // before the branches are compared, so `σa(σb(A)) ∪ σc(A)` is reached.
            case UnionNode u -> {
                RelNode left  = rewriteNode(u.left(), queryName, ctx);
                RelNode right = rewriteNode(u.right(), queryName, ctx);
                RelNode merged = mergeBranches(u, left, right, DISJUNCTION, "union",
                        queryName, ctx);
                yield merged != null ? merged : rebuilt(u, left, right);
            }
            case IntersectionNode i -> {
                RelNode left  = rewriteNode(i.left(), queryName, ctx);
                RelNode right = rewriteNode(i.right(), queryName, ctx);
                RelNode merged = mergeBranches(i, left, right, CONJUNCTION, "intersection",
                        queryName, ctx);
                yield merged != null ? merged : rebuilt(i, left, right);
            }
            case DifferenceNode d -> {
                RelNode left  = rewriteNode(d.left(), queryName, ctx);
                RelNode right = rewriteNode(d.right(), queryName, ctx);
                RelNode merged = mergeBranches(d, left, right, EXCEPT, "difference",
                        queryName, ctx);
                yield merged != null ? merged : rebuilt(d, left, right);
            }

            // ── All other nodes: recurse into children, no rule fires ────────
            default -> node.mapChildren(child -> rewriteNode(child, queryName, ctx));
        };
    }

    /** Rebuilds a set operation over rewritten branches, returning it unchanged when
     *  neither branch moved — the reference-inequality signal the phase driver reads. */
    private static RelNode rebuilt(RelNode setOp, RelNode left, RelNode right) {
        if (setOp instanceof UnionNode u) {
            return (left != u.left() || right != u.right())
                    ? new UnionNode(left, right, u.location()) : u;
        }
        if (setOp instanceof IntersectionNode i) {
            return (left != i.left() || right != i.right())
                    ? new IntersectionNode(left, right, i.location()) : i;
        }
        DifferenceNode d = (DifferenceNode) setOp;
        return (left != d.left() || right != d.right())
                ? new DifferenceNode(left, right, d.location()) : d;
    }

    // =========================================================================
    // Merge helper
    // =========================================================================

    /**
     * Merges {@code s} with its inner selection (if any), producing a single
     * selection with {@code AND}-ed predicates.  Repeats until no adjacent
     * pair remains.  Records {@link OptimizationCode#SEL_002} once per merge.
     */
    private static RelNode mergeAdjacent(SelectionNode s,
                                          String queryName,
                                          OptimizationContext ctx) {
        if (s.input() instanceof SelectionNode inner) {
            var merged = new SelectionNode(
                    new AndPredicate(s.predicate(), inner.predicate(), s.location()),
                    inner.input(),
                    s.location());
            ctx.record(OptimizationCode.SEL_002, queryName,
                    "two adjacent selections merged into one conjunctive predicate",
                    s.location());
            // Recurse: maybe the merged selection's input is another selection
            return mergeAdjacent(merged, queryName, ctx);
        }
        return s;
    }

    // =========================================================================
    // SEL-010 — a set operation over two selections of one input
    // =========================================================================

    /**
     * Merges {@code left} and {@code right} into one selection when both filter the same
     * input, or returns {@code null} when any gate declines.
     *
     * @param setOp       the set operation being replaced — its location is carried onto
     *                    the nodes that replace it
     * @param disjunction {@code true} for {@code ∪} (the predicates are OR-ed),
     *                    {@code false} for {@code ∩} (AND-ed)
     */
    private static RelNode mergeBranches(RelNode setOp, RelNode left, RelNode right,
                                         Combiner combine, String operation,
                                         String queryName, OptimizationContext ctx) {
        if (!(left instanceof SelectionNode ls) || !(right instanceof SelectionNode rs)) {
            return null;
        }
        if (!AstEquivalence.equivalent(ls.input(), rs.input())) {
            return null;
        }
        // One expression asked of the determinism source rather than two: the walk covers
        // the predicate as well as the input, which is the other thing that changes how
        // often it is evaluated.
        if (!ctx.determinism().isDeterministic(ls) || !ctx.determinism().isDeterministic(rs)) {
            return null;
        }

        Predicate combined =
                combine.combine(ls.predicate(), rs.predicate(), setOp.location());
        RelNode filtered = new SelectionNode(combined, ls.input(), setOp.location());

        boolean distinct =
                PropertyDeriver.derive(filtered, ctx.distinctness()).isDuplicateFree();
        ctx.record(OptimizationCode.SEL_010, queryName,
                operation + " of two selections of one input merged into "
                        + (distinct ? "a single selection"
                                    : "a single selection under a DISTINCT"),
                setOp.location());
        return distinct ? filtered : new DistinctNode(filtered, setOp.location());
    }

    /** How a set operation's two branch predicates become one. */
    @FunctionalInterface
    private interface Combiner {
        Predicate combine(Predicate left, Predicate right, SourceLocation at);
    }

    /** {@code ∪} — a row survives if either branch kept it. */
    private static final Combiner DISJUNCTION =
            (i, q, at) -> new OrPredicate(i, q, at);

    /** {@code ∩} — a row survives only if both branches kept it. */
    private static final Combiner CONJUNCTION =
            (i, q, at) -> new AndPredicate(i, q, at);

    /**
     * {@code −} — a row survives if the left branch kept it and the right did not.
     *
     * <p>Not {@code i ∧ ¬q}: the subtrahend also fails to hold a row whose {@code q}
     * is UNKNOWN, and such a row is therefore <em>kept</em> by the difference while
     * {@code ¬q} drops it. {@link SelectionComplement} is the correction.
     */
    private static final Combiner EXCEPT =
            (i, q, at) -> new AndPredicate(i, SelectionComplement.of(q, at), at);
}

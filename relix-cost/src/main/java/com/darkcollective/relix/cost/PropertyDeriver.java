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
package com.darkcollective.relix.cost;

import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ClusterNode;
import com.darkcollective.relix.ast.PathNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.ast.CoverNode;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.CompositionNode;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.SessionizeNode;
import com.darkcollective.relix.ast.TreeNode;
import com.darkcollective.relix.ast.WhyNode;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.DivisionNode;
import com.darkcollective.relix.ast.FullOuterJoinNode;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.OuterUnionNode;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.ReservoirSampleNode;
import com.darkcollective.relix.ast.RightOuterJoinNode;
import com.darkcollective.relix.ast.SampleNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;
import com.darkcollective.relix.ast.SolveNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.ast.UnpivotNode;
import com.darkcollective.relix.ast.PivotNode;

import java.util.List;

/**
 * Derives {@link RelationProperties} for a {@link RelNode} tree bottom-up,
 * purely structurally — from each operator's semantics and its children's
 * properties — with no schema or symbol-table lookup.
 *
 * <p>Because the derivation reads only node structure, it is robust against the
 * optimizer rewriting nodes (a rewritten subtree carries no schema annotation):
 * properties are recomputed from whatever tree it is handed.
 *
 * <h2>Distinctness rules</h2>
 * <ul>
 *   <li><strong>Establish whole-row distinctness</strong> (output is a set
 *       regardless of input): {@code δ}, {@code ∪}, {@code ∩}, {@code −},
 *       {@code ∆}, {@code ÷}, transitive closure.</li>
 *   <li><strong>Establish a candidate key</strong> from structurally known unique
 *       columns: grouping aggregation ({@code γ}) and group-wise universal
 *       quantification ({@code ∀}) are unique on their grouping keys (an empty
 *       grouping-key list ⇒ at most one row).</li>
 *   <li><strong>Preserve</strong> the input's distinctness (row-subset / reorder /
 *       relation-only rename): {@code σ}, {@code τ}, {@code λ}, sampling,
 *       {@code TOP}, {@code OPTIMIZE}, and the left input of {@code ⋉}/{@code ▷}.</li>
 *   <li><strong>Conservatively break</strong> distinctness (may introduce or
 *       require duplicates, or change values): base relations (no key info in this
 *       phase), all joins/products/composition, {@code ⊎}, {@code π} (may drop key
 *       columns), {@code μ} (UNNEST), and {@code SOLVE}.</li>
 * </ul>
 *
 * <p>Every rule is conservative: it never reports {@code isDuplicateFree()} for a
 * relation that could contain duplicates. It derives distinctness structurally
 * only: base-relation primary keys, candidate-key survival through {@code π} and
 * key propagation through joins are not part of the derivation.
 */
public final class PropertyDeriver {

    /** Maximum recursion depth, mirroring {@link CostEstimator#MAX_DEPTH}. */
    static final int MAX_DEPTH = 64;

    private PropertyDeriver() {}

    /**
     * Derives the logical properties of the relation produced by {@code node},
     * assuming every leaf is {@linkplain Boundedness#BOUNDED bounded}
     * (equivalent to {@code derive(node, BoundednessSource.ALL_BOUNDED)}).
     *
     * @param node the tree to analyse; must not be null
     * @return the derived properties; never null (conservatively
     *         {@link RelationProperties#none()} when nothing can be proven)
     */
    public static RelationProperties derive(RelNode node) {
        return derive(node, BoundednessSource.ALL_BOUNDED, DistinctnessSource.NONE);
    }

    /**
     * Derives properties taking leaf <em>distinctness</em> from {@code distinctnessSource}
     * (and all-bounded leaves). A leaf the source reports duplicate-free yields whole-row
     * distinctness; everything else is structural as usual.
     *
     * @param node               the tree to analyse; must not be null
     * @param distinctnessSource the per-leaf duplicate-free lookup; must not be null
     * @return the derived properties; never null
     */
    public static RelationProperties derive(RelNode node, DistinctnessSource distinctnessSource) {
        return derive(node, BoundednessSource.ALL_BOUNDED, distinctnessSource);
    }

    /**
     * Derives the logical properties of {@code node}, taking leaf boundedness from
     * {@code boundednessSource}. Distinctness is derived purely structurally; the
     * boundedness is computed bottom-up — contagious upward from leaves, with
     * {@code λ} (LIMIT) bounding its input — and overlaid onto the result.
     *
     * @param node             the tree to analyse; must not be null
     * @param boundednessSource the per-leaf boundedness lookup; must not be null
     * @return the derived properties; never null
     */
    public static RelationProperties derive(RelNode node, BoundednessSource boundednessSource) {
        return derive(node, boundednessSource, DistinctnessSource.NONE);
    }

    /**
     * Derives the logical properties of {@code node}, taking leaf boundedness from
     * {@code boundednessSource} and leaf distinctness from {@code distinctnessSource}.
     *
     * @param node               the tree to analyse; must not be null
     * @param boundednessSource  the per-leaf boundedness lookup; must not be null
     * @param distinctnessSource the per-leaf duplicate-free lookup; must not be null
     * @return the derived properties; never null
     */
    public static RelationProperties derive(RelNode node, BoundednessSource boundednessSource,
                                            DistinctnessSource distinctnessSource) {
        return deriveAt(node, 0, distinctnessSource)
                .withBoundedness(deriveBoundedness(node, boundednessSource, 0));
    }

    /**
     * Derives only the boundedness of {@code node} (without computing
     * distinctness), taking leaf boundedness from {@code boundednessSource}.
     *
     * @param node             the tree to analyse; must not be null
     * @param boundednessSource the per-leaf boundedness lookup; must not be null
     * @return the relation's boundedness; never null
     */
    public static Boundedness boundedness(RelNode node, BoundednessSource boundednessSource) {
        return deriveBoundedness(node, boundednessSource, 0);
    }

    /**
     * Derives boundedness bottom-up: a leaf takes its boundedness from the source,
     * {@code λ} (LIMIT) is always {@link Boundedness#BOUNDED} (it bounds its input),
     * and every other operator is the least upper bound of its children's
     * boundedness — an unbounded input makes the result unbounded.
     *
     * <p><strong>Contagious-only is a choice, not a theorem</strong> (#874). Unboundedness
     * travels up from a leaf that declares it and no operator here invents any, which is
     * sound for every operator that draws its output values from its input — and a
     * {@code FIX} whose step <em>computes</em> a value does not. {@code FIX N (Zero,
     * π n + 1 → m (N))} has a one-row base, a leaf recursive reference, and the extent of
     * ℕ; this method types it {@code BOUNDED}, exactly as it types the finite recursion
     * beside it. So an infinite relation written this way is invisible to every check
     * keyed on boundedness, {@link BoundednessChecker} included, where the same extent
     * from the {@code Naturals} generator is caught.
     *
     * <p>That is left as it is, deliberately. Whether such a fixpoint is finite is
     * undecidable, so no bottom-up lattice can decide it; and typing it {@code UNBOUNDED}
     * would make {@code BoundednessChecker} refuse the {@code FIX} itself — it is a
     * blocking (SET) operator — which rejects every value-inventing recursion outright and
     * costs the operator its reason to exist. What covers the case is the runtime row cap
     * on the fixpoint's accumulator, not this analysis. The same premise fails in the same
     * place in {@code docs/design/general-recursion-plan.md}'s termination argument, which
     * states it and says why.
     */
    private static Boundedness deriveBoundedness(RelNode node, BoundednessSource source, int depth) {
        // A pathologically deep tree is never a real unbounded query; favour not
        // falsely rejecting it over catching a generator buried 64 levels down.
        if (depth > MAX_DEPTH) return Boundedness.BOUNDED;
        if (node instanceof RelationNode r) {
            // A generator leaf with a pushed production stop (GEN-001) is finite,
            // even when the underlying generator is unbounded — the bound is the rescue
            // (like λ), so a downstream blocking operator over it becomes legal.
            if (r.produceBound().isPresent()) {
                return Boundedness.BOUNDED;
            }
            return source.boundednessOf(r.name());
        }
        if (node instanceof LimitNode) {
            return Boundedness.BOUNDED;
        }
        // A bounded-frame window buffers at most n rows per partition (ADR-0015 §D5),
        // so it bounds its input the same way λ does — regardless of input boundedness.
        if (node instanceof WindowNode w && w.frame() instanceof WindowFrame.BoundedFrame) {
            return Boundedness.BOUNDED;
        }
        Boundedness b = Boundedness.BOUNDED;
        for (RelNode child : node.children()) {
            b = b.lub(deriveBoundedness(child, source, depth + 1));
        }
        return b;
    }

    private static RelationProperties deriveAt(RelNode node, int depth, DistinctnessSource src) {
        if (depth > MAX_DEPTH) return RelationProperties.none();
        return switch (node) {

            // ── Establish whole-row distinctness (set-producing operators) ──────
            // FIX materialises a set (no duplicate row ever re-added).
            // COVER is whole-row duplicate-free: the greedy algorithm never selects
            // a row that covers the same new t-tuples as one already chosen.
            // CLUSTER emits one row per distinct node (the node column is a candidate
            // key), so the whole output row is duplicate-free.
            // TRACE emits one row per distinct (from, to) pair — the pair is a candidate key.
            // PATH emits one row per distinct (from, to) pair at its shortest depth, so the
            // pair is a candidate key and the whole output row is duplicate-free.
            // A truth-relation literal holds at most one (empty) tuple, so it is
            // whole-row distinct without any source needing to say so; ∅ holds none
            // at all, which is duplicate-free for the same trivial reason.
            case DistinctNode _, UnionNode _, OuterUnionNode _, IntersectionNode _,
                 DifferenceNode _, SymmetricDifferenceNode _, DivisionNode _,
                 ClosureNode _, ClusterNode _, PathNode _, TraceNode _, FixpointNode _,
                 CoverNode _, TruthRelationNode _, EmptyRelationNode _ -> RelationProperties.wholeRow();

            // ── Establish a candidate key from grouping columns ─────────────────
            case AggregationNode a -> RelationProperties.key(
                    a.groupingKeys().stream().map(GroupingKey::outputName).toList());
            case UniversalNode u   -> RelationProperties.key(u.groupingAttributes());

            // ── Preserve the input's distinctness ───────────────────────────────
            // σ / τ / λ / sampling / TOP / OPTIMIZE output a row-subset of the input.
            // ⋉ / ▷ / pairwise-∀ are left-filter operators (OperatorSemantics.isLeftFilterOperator):
            // they emit a row-subset of their left input so left's distinctness holds.
            // AS-OF emits exactly one (nearest) right row per left probe, so the
            // left's candidate keys still uniquely identify output rows.
            case SelectionNode _, SortNode _, LimitNode _, SampleNode _,
                 ReservoirSampleNode _, TopKNode _, OptimizeNode _,
                 SemiJoinNode _, AntiJoinNode _, PairwiseUniversalNode _,
                 AsOfJoinNode _ ->
                    deriveAt(node.children().get(0), depth + 1, src);
            case RenameNode r -> renameProperties(r, depth, src);

            // ── Leaf: duplicate-free iff the source says so (a generator that
            //    declares it, a PK base relation) — otherwise conservatively none.
            case RelationNode r -> src.duplicateFreeLeaf(r.name())
                    ? RelationProperties.wholeRow() : RelationProperties.none();

            // ── Conservatively break distinctness ───────────────────────────────
            // A table-valued function's distinctness depends on its (untracked) body.
            // The recursive reference is an opaque leaf (its content is the current delta).
            // DOWNSAMPLE is a grouping operator — output rows may not be distinct across buckets.
            // LATERAL joins multiply rows (one TVF invocation per outer row), so distinctness breaks.
            // A window appends a computed column, so output rows may not be distinct.
            // TREE appends a nested children column and emits roots only — derive
            // distinctness conservatively rather than tracking the key candidate key.
            // WHY appends a provenance column and is a hard barrier (ADR-0018); derive
            // distinctness conservatively rather than threading the input's keys across it.
            case RelationFunctionCall _, ProjectionNode _, UnnestNode _, SolveNode _,
                 DownsampleNode _, LateralJoinNode _, WindowNode _, SessionizeNode _,
                 UnpivotNode _, PivotNode _, TreeNode _, WhyNode _,
                 NaturalJoinNode _, ThetaJoinNode _, LeftOuterJoinNode _, RightOuterJoinNode _,
                 FullOuterJoinNode _, ProductNode _, CompositionNode _, UnionAllNode _,
                 IntervalJoinNode _,
                 RecursiveRefNode _ -> RelationProperties.none();
        };
    }

    /**
     * Rename preserves row identity, so distinctness survives. A relation-only
     * rename (empty attribute list) leaves column names unchanged and passes the
     * input's properties through verbatim; a column-renaming rename keeps the
     * distinctness but not the now-renamed candidate-key names, so it collapses to
     * whole-row distinctness.
     */
    private static RelationProperties renameProperties(RenameNode r, int depth, DistinctnessSource src) {
        RelationProperties in = deriveAt(r.input(), depth + 1, src);
        if (!r.renamesColumns()) {
            return in;                                 // columns unchanged
        }
        return in.isDuplicateFree() ? RelationProperties.wholeRow() : RelationProperties.none();
    }
}

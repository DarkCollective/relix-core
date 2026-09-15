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
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ClusterNode;
import com.darkcollective.relix.ast.PathNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.ast.CoverNode;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.DivisionNode;
import com.darkcollective.relix.ast.FullOuterJoinNode;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.MaterializationMode;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.OuterUnionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.ReservoirSampleNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.ast.PivotNode;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.SessionizeNode;
import com.darkcollective.relix.ast.TreeNode;
import com.darkcollective.relix.ast.WhyNode;
import com.darkcollective.relix.ast.WindowNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Materialisation-safety check: a <em>blocking</em> operator —
 * one that must buffer its whole input before emitting any row — over a provably
 * {@linkplain Boundedness#UNBOUNDED unbounded} input is a plan-time error.
 *
 * <p>The blocking/streaming split is already a first-class property:
 * {@link RelNode#materializationMode()} is non-{@link MaterializationMode#STREAM}
 * (i.e. {@code BAG}/{@code SET}/{@code SORTED}) for exactly the operators that
 * accumulate their input — {@code γ}, {@code τ}, {@code ∀}, {@code OPTIMIZE},
 * {@code TOP}, reservoir {@code SAMPLE}, {@code ⊎}, {@code ÷}, {@code ⟗}, and the
 * set operations {@code ∪}/{@code ∩}/{@code −}/{@code ∆} and transitive closure.
 * Streaming operators ({@code σ}, {@code π}, {@code λ}, {@code δ}, most joins, …)
 * may consume an unbounded input forever and are never flagged.
 *
 * <p>Boundedness is derived by {@link PropertyDeriver#boundedness}; because no
 * operator ever <em>produces</em> {@code UNBOUNDED} (it originates only at a leaf
 * and {@code λ} bounds it), a tree whose leaves are all
 * {@link Boundedness#BOUNDED} never trips this check — only a query reading an
 * unbounded generator ({@code Naturals}, {@code Primes}) or a streaming source
 * can trigger it.
 *
 * <p>{@link Boundedness#UNKNOWN} inputs are <em>not</em> flagged — only what is
 * provably unbounded.
 */
public final class BoundednessChecker {

    private BoundednessChecker() {}

    /**
     * Checks the tree rooted at {@code root} for blocking operators over unbounded
     * inputs.
     *
     * @param root   the tree to check; must not be null
     * @param source the per-leaf boundedness lookup; must not be null
     * @return one diagnostic per offending (blocking operator, unbounded input)
     *         pair, in pre-order; empty when the tree is materialisation-safe
     */
    public static List<String> check(RelNode root, BoundednessSource source) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(source, "source");
        List<String> errors = new ArrayList<>();
        checkNode(root, source, errors);
        return errors;
    }

    private static void checkNode(RelNode node, BoundednessSource source, List<String> errors) {
        if (node.materializationMode() != MaterializationMode.STREAM
                && !streamsOverOrder(node) && !isBoundedWindow(node)) {
            for (RelNode child : node.children()) {
                if (PropertyDeriver.boundedness(child, source) == Boundedness.UNBOUNDED) {
                    errors.add("cannot materialise unbounded relation for blocking operator "
                            + label(node) + "; add a bound (e.g. λ n) below it");
                }
            }
        }
        for (RelNode child : node.children()) {
            checkNode(child, source, errors);
        }
    }

    /**
     * Whether {@code node} is a nominally-blocking operator that nonetheless streams
     * because its input already delivers a compatible order (ADR-0009, Phase C3) — a
     * {@code γ} (GROUP) whose input is ordered by its grouping keys aggregates in a
     * single linear pass, so it does not buffer its (possibly unbounded) input.
     * ({@code δ} is already {@link MaterializationMode#STREAM} and never reaches the
     * blocking branch.)
     */
    private static boolean streamsOverOrder(RelNode node) {
        // Only a grouping entirely over plain columns can be matched against the
        // input's delivered ordering; a derived key (e.g. YEAR(ts)) blocks.
        return node instanceof AggregationNode agg
                && GroupingKey.plainColumns(agg.groupingKeys())
                        .map(cols -> OrderDeriver.derive(agg.input()).groupsBy(cols))
                        .orElse(false);
    }

    /**
     * Whether {@code node} is a bounded-frame window ({@code ROLLING … OVER n ROWS}).
     * Such a window buffers at most {@code n} rows per partition (ADR-0015 §D5), so —
     * like {@code λ}'s rescue — it streams an unbounded input safely and is exempt
     * from the blocking check.  Cumulative / ranking / offset windows remain blocking.
     */
    private static boolean isBoundedWindow(RelNode node) {
        return node instanceof WindowNode w && w.frame() instanceof WindowFrame.BoundedFrame;
    }

    /**
     * A short, user-facing label for a blocking operator.
     *
     * <p>The switch is over all 52 node kinds while only the blocking ones need a label, so
     * it needs a {@code default} — but that arm is not a shrug: it would put a Java type
     * name into a diagnostic a user is meant to act on. {@code NodeKindDerivationTest}
     * checks every kind whose {@code materializationMode()} is not {@code STREAM} against
     * this switch, so a blocking operator arriving without an arm fails the build rather
     * than falling through to its class name.
     */
    private static String label(RelNode node) {
        return switch (node) {
            case SortNode _                -> "τ (SORT)";
            case AggregationNode _         -> "γ (GROUP)";
            case UniversalNode _           -> "∀ (FORALL)";
            case OptimizeNode _            -> "OPTIMIZE";
            case TopKNode _                -> "TOP";
            case ReservoirSampleNode _     -> "SAMPLE … ROWS";
            case CoverNode _               -> "COVER";
            case DownsampleNode _          -> "DOWNSAMPLE";
            case UnionAllNode _            -> "⊎ (UALL)";
            case DivisionNode _            -> "÷ (DIV)";
            case FullOuterJoinNode _       -> "⟗ (full outer join)";
            case UnionNode _               -> "∪ (UNION)";
            case OuterUnionNode _          -> "⊔ (OUNION)";
            case IntersectionNode _        -> "∩ (INTER)";
            case DifferenceNode _          -> "− (DIFF)";
            case SymmetricDifferenceNode _ -> "∆ (SYMDIFF)";
            case ClosureNode _             -> "CLOSURE";
            case ClusterNode _             -> "CLUSTER";
            case PathNode _                -> "PATH";
            case TraceNode _               -> "TRACE";
            case FixpointNode _            -> "FIX (general recursion)";
            case WindowNode _              -> "WINDOW (cumulative / ranking / offset)";
            case SessionizeNode _          -> "SESSIONIZE";
            case PivotNode _               -> "PIVOT";
            case TreeNode _                -> "TREE";
            case WhyNode _                 -> "ω (WHY)";
            default                              -> node.getClass().getSimpleName();
        };
    }
}

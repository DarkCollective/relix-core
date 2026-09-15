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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ClusterNode;
import com.darkcollective.relix.ast.PathNode;
import com.darkcollective.relix.ast.SessionizeNode;
import com.darkcollective.relix.ast.TreeNode;
import com.darkcollective.relix.ast.WhyNode;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.ast.CompositionNode;
import com.darkcollective.relix.ast.CoverNode;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.DivisionNode;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.FullOuterJoinNode;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.OuterUnionNode;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.RelNode;
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
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.ast.UnpivotNode;
import com.darkcollective.relix.ast.PivotNode;

import java.util.List;
import java.util.Objects;

/**
 * Monotonicity + linearity check for the step of a {@link FixpointNode} (general
 * recursion, {@code FIX name (base, step)}).
 *
 * <p>Extracted from {@code RelAlgebraValidator} to keep the recursive-reference
 * analysis — a self-contained structural (schema-free) walk — out of the
 * already-large validator.
 *
 * <p>{@link #countRecursiveRefs(RelNode, String)} walks the step counting the
 * references to the recursive binder that are not shadowed by a nested
 * {@code FIX} of the same name, and — for each such reference reached through a
 * non-monotone position — appends a positioned error to the {@code errors} list.
 * The caller turns the returned count into the linearity check (exactly one
 * reference is required in v1).
 */
final class RecursiveRefChecker {

    private final List<SemanticError> errors;

    RecursiveRefChecker(List<SemanticError> errors) {
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /**
     * Counts the non-shadowed references to {@code name} in {@code step},
     * reporting an error for each one reached through a non-monotone position.
     */
    int countRecursiveRefs(RelNode step, String name) {
        return countRecursiveRefs(step, name, null);
    }

    /**
     * {@code forbidden} carries the reason of the first non-monotone edge on the
     * path from the step root (null while the path is still monotone); once set it
     * is preserved (the outermost edge is the error site).
     */
    private int countRecursiveRefs(RelNode node, String name, String forbidden) {
        return switch (node) {
            case RecursiveRefNode ref -> {
                if (!ref.name().equals(name)) yield 0;
                if (forbidden != null) {
                    error(ref.location(), "Recursive relation '" + name
                            + "' used in a non-monotone position (" + forbidden + ")");
                }
                yield 1;
            }

            // ── Monotone — recursive ref allowed (reason carried unchanged) ──────
            case SelectionNode n  -> countRecursiveRefs(n.input(), name, forbidden);
            case ProjectionNode n -> countRecursiveRefs(n.input(), name, forbidden);
            case RenameNode n     -> countRecursiveRefs(n.input(), name, forbidden);
            case DistinctNode n   -> countRecursiveRefs(n.input(), name, forbidden);
            case UnnestNode n     -> countRecursiveRefs(n.input(), name, forbidden);
            case NaturalJoinNode n -> countRecursiveRefs(n.left(), name, forbidden)
                    + countRecursiveRefs(n.right(), name, forbidden);
            case ThetaJoinNode n -> countRecursiveRefs(n.left(), name, forbidden)
                    + countRecursiveRefs(n.right(), name, forbidden);
            case ProductNode n -> countRecursiveRefs(n.left(), name, forbidden)
                    + countRecursiveRefs(n.right(), name, forbidden);
            // Semi-join (⋉): both inputs are monotone.
            // Left — adding rows to the growing fixpoint can only add output rows.
            // Right — adding rows to the filter source lets MORE left rows satisfy the ∃
            //         condition; it can never remove an already-matching left row.
            // Design decision: both sides of ⋉ are monotone (contrast with ▷ anti-join,
            // where the right side is non-monotone — adding right rows removes output rows).
            case SemiJoinNode n -> countRecursiveRefs(n.left(), name, forbidden)
                    + countRecursiveRefs(n.right(), name, forbidden);
            case UnionNode n -> countRecursiveRefs(n.left(), name, forbidden)
                    + countRecursiveRefs(n.right(), name, forbidden);
            case UnionAllNode n -> countRecursiveRefs(n.left(), name, forbidden)
                    + countRecursiveRefs(n.right(), name, forbidden);
            case OuterUnionNode n -> countRecursiveRefs(n.left(), name, forbidden)
                    + countRecursiveRefs(n.right(), name, forbidden);
            case IntersectionNode n -> countRecursiveRefs(n.left(), name, forbidden)
                    + countRecursiveRefs(n.right(), name, forbidden);

            // Nested FIX is monotone; its base always sees our name, but its step
            // shadows our name iff the inner binder reuses it (then we skip the step).
            case FixpointNode n -> {
                int count = countRecursiveRefs(n.base(), name, forbidden);
                if (!n.name().equals(name)) {
                    count += countRecursiveRefs(n.step(), name, forbidden);
                }
                yield count;
            }

            // ── Non-monotone — recursive ref forbidden (record the first reason) ─
            case DifferenceNode n -> countRecursiveRefs(n.left(), name, forbidden)
                    + countRecursiveRefs(n.right(), name, firstReason(forbidden, "right side of −"));
            case AntiJoinNode n -> countRecursiveRefs(n.left(), name, forbidden)
                    + countRecursiveRefs(n.right(), name, firstReason(forbidden, "right side of ▷"));
            case DivisionNode n -> {
                String why = firstReason(forbidden, "under ÷");
                yield countRecursiveRefs(n.left(), name, why)
                        + countRecursiveRefs(n.right(), name, why);
            }
            case SymmetricDifferenceNode n -> {
                String why = firstReason(forbidden, "under ∆");
                yield countRecursiveRefs(n.left(), name, why)
                        + countRecursiveRefs(n.right(), name, why);
            }
            case CompositionNode n -> {
                String why = firstReason(forbidden, "under ∘");
                yield countRecursiveRefs(n.left(), name, why)
                        + countRecursiveRefs(n.right(), name, why);
            }
            case LeftOuterJoinNode n -> {
                String why = firstReason(forbidden, "under an outer join");
                yield countRecursiveRefs(n.left(), name, why)
                        + countRecursiveRefs(n.right(), name, why);
            }
            case RightOuterJoinNode n -> {
                String why = firstReason(forbidden, "under an outer join");
                yield countRecursiveRefs(n.left(), name, why)
                        + countRecursiveRefs(n.right(), name, why);
            }
            case FullOuterJoinNode n -> {
                String why = firstReason(forbidden, "under an outer join");
                yield countRecursiveRefs(n.left(), name, why)
                        + countRecursiveRefs(n.right(), name, why);
            }
            case PairwiseUniversalNode n -> {
                String why = firstReason(forbidden, "under ∀ (pairwise)");
                yield countRecursiveRefs(n.left(), name, why)
                        + countRecursiveRefs(n.right(), name, why);
            }
            case AsOfJoinNode n -> {
                String why = firstReason(forbidden, "under an AS-OF join");
                yield countRecursiveRefs(n.left(), name, why)
                        + countRecursiveRefs(n.right(), name, why);
            }
            case IntervalJoinNode n -> {
                String why = firstReason(forbidden, "under an interval join");
                yield countRecursiveRefs(n.left(), name, why)
                        + countRecursiveRefs(n.right(), name, why);
            }
            case AggregationNode n  -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under γ"));
            case DownsampleNode n   -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under DOWNSAMPLE"));
            case UniversalNode n    -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under ∀"));
            case OptimizeNode n    -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under OPTIMIZE"));
            case TopKNode n        -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under TOP"));
            case SolveNode n       -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under SOLVE"));
            case SampleNode n      -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under SAMPLE"));
            case ReservoirSampleNode n -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under SAMPLE"));
            case LimitNode n       -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under λ"));
            case SortNode n        -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under τ"));
            case ClosureNode n     -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under CLOSURE"));
            case ClusterNode n     -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under CLUSTER"));
            case PathNode n        -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under PATH"));
            case TraceNode n       -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under TRACE"));
            case CoverNode n       -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under COVER"));
            case WindowNode n      -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under WINDOW"));
            case SessionizeNode n  -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under SESSIONIZE"));
            case UnpivotNode n     -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under UNPIVOT"));
            case PivotNode n       -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under PIVOT"));
            case TreeNode n        -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under TREE"));
            // WHY is a blocking reification barrier (ADR-0018) — conservatively
            // treat a recursive reference beneath it as non-monotone.
            case WhyNode n         -> countRecursiveRefs(n.input(), name, firstReason(forbidden, "under ω (WHY)"));

            // LATERAL is monotone: adding rows to its left side only adds more TVF
            // invocations, never removes rows.  The TVF arguments are scalar Operands
            // and cannot contain a RecursiveRefNode.
            case LateralJoinNode n -> countRecursiveRefs(n.left(), name, forbidden);

            // ── Leaves that can hold no recursive reference ──────────────────────
            case RelationNode _         -> 0;
            case RelationFunctionCall _ -> 0;
            case TruthRelationNode _    -> 0;
            case EmptyRelationNode _    -> 0;
        };
    }

    /** Keeps the first (outermost) non-monotone reason on a root-to-ref path. */
    private static String firstReason(String existing, String candidate) {
        return existing != null ? existing : candidate;
    }

    private void error(SourceLocation loc, String message) {
        errors.add(SemanticError.error(loc.filePath(), loc.line(), loc.column(), message));
    }
}

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
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.internal.Predicates;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.ast.visitor.internal.PredicatePrettyPrinter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The shared machinery behind every ADR-0020 <em>partition pruning</em> rule: push an
 * equality on a partition/grouping key below an operator that computes independently per
 * partition, so only the matching partition is computed.
 *
 * <pre>
 *   σ k = c (OP … PER k (R))   ≡   OP … PER k (σ k = c (R))
 * </pre>
 *
 * <p>Five rules share this shape — {@code WINDOW-001}, {@code TOPK-001},
 * {@code OPTIMIZE-001}, {@code SESSION-001} and {@code DOWNSAMPLE-001}.  The traversal,
 * the conjunct split, the residual σ and the pushability descriptor were previously
 * copied per pass, drifting apart one {@code isConstant} at a time; a rule's own pass now
 * supplies only a {@link TargetResolver} — "if this node is mine, here are its partition
 * keys, its input, and how to rebuild it".
 *
 * <h2>What is pushable</h2>
 * <p>A top-level conjunct is pushable <strong>iff</strong> it is an {@code =} comparison
 * with a bare partition-key column on one side and a constant (no attribute reference) on
 * the other.  Everything else — inequalities, predicates on a computed output column,
 * equalities on non-partition columns, cross-column correlations, disjunctions — stays as
 * a <em>residual</em> {@code σ} above the operator.  This is ADR-0020 Decision 5: an
 * over-conservative descriptor is a no-op, never a wrong answer.
 *
 * <p>The pushed conjunct is <em>removed</em> from above the operator: once the operator
 * has run over only the matching partition, every output row satisfies it already.  That
 * relies on the operator emitting the partition key it was given, which is a property of
 * every member of this family.
 *
 * <p>An operator with an empty key list has no partition dimension, so nothing is
 * pushable and the rule is a no-op there.
 */
final class PartitionPruning {

    private PartitionPruning() {}

    // =========================================================================
    // The seam
    // =========================================================================

    /**
     * One prunable operator, as its owning pass describes it.
     *
     * @param code    the rule code to record
     * @param keys    the partition/grouping key columns, lowercased and unqualified
     * @param input   the operator's input, which the pushed σ goes on top of
     * @param rebuild rebuilds the operator over a new input, preserving everything else
     * @param noun    what a partition is called for this operator — {@code "partition"}
     *                or {@code "group"}
     * @param opLabel the operator's name in the transformation record, e.g. {@code "TOP"}
     * @param saving  what the push avoids, e.g. {@code "other groups not solved"}
     */
    record Target(OptimizationCode code, Set<String> keys, RelNode input,
                  UnaryOperator<RelNode> rebuild, String noun, String opLabel, String saving) {

        /** Builds a target from an operator's raw (possibly qualified) key list. */
        static Target of(OptimizationCode code, List<String> keys, RelNode input,
                         UnaryOperator<RelNode> rebuild, String noun, String opLabel,
                         String saving) {
            return new Target(code, keySet(keys), input, rebuild, noun, opLabel, saving);
        }
    }

    /** Recognises the node types one rule owns; returns {@code null} for anything else. */
    @FunctionalInterface
    interface TargetResolver {
        Target resolve(RelNode node);
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Prunes every operator {@code resolver} recognises, throughout the tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param ctx       transformation record accumulator
     * @param resolver  the owning pass's node recogniser
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName, OptimizationContext ctx,
                         TargetResolver resolver) {
        return rewrite(node, queryName, ctx, resolver);
    }

    // =========================================================================
    // Traversal (top-down for σ, bottom-up for everything else)
    // =========================================================================

    private static RelNode rewrite(RelNode node, String queryName, OptimizationContext ctx,
                                   TargetResolver resolver) {
        return switch (node) {

            // ── σ: try to prune first (top-down), then recurse on the result ──
            case SelectionNode s -> {
                RelNode pruned = tryPrune(s, queryName, ctx, resolver);
                if (pruned != null) {
                    yield pruned;
                }
                RelNode newInput = rewrite(s.input(), queryName, ctx, resolver);
                yield (newInput != s.input())
                        ? new SelectionNode(s.predicate(), newInput, s.location()) : s;
            }

            // ── Bottom-up traversal for all other nodes ───────────────────────
            default -> node.mapChildren(child -> rewrite(child, queryName, ctx, resolver));
        };
    }

    /**
     * Splits the conjuncts of the σ-<em>chain</em> starting at {@code s} into pushable
     * partition-equalities and residual conjuncts; on a non-empty pushable set, rebuilds
     * the operator with the pushed selection on its input and the residual (if any) left
     * above.  Returns {@code null} when nothing is pushable, so the caller recurses
     * normally.
     *
     * <p>It has to be the whole chain, not just this σ: {@code SEL-001} runs first in
     * this phase and turns {@code σ k = c ∧ other} into two stacked selections, so
     * whether a partition equality ends up adjacent to the operator is an accident of
     * which side of the {@code ∧} the user wrote it on.
     */
    private static RelNode tryPrune(SelectionNode s, String queryName,
                                    OptimizationContext ctx, TargetResolver resolver) {
        var conjuncts = new ArrayList<Predicate>();
        RelNode below = s;
        while (below instanceof SelectionNode chained) {
            conjuncts.addAll(Predicates.conjuncts(chained.predicate()));
            below = chained.input();
        }
        Target target = resolver.resolve(below);
        if (target == null || target.keys().isEmpty()) {
            return null;
        }

        var pushable = new ArrayList<Predicate>();
        var residual = new ArrayList<Predicate>();
        for (Predicate c : conjuncts) {
            if (isPartitionEquality(c, target.keys())) {
                pushable.add(c);
            } else {
                residual.add(c);
            }
        }
        if (pushable.isEmpty()) {
            return null;
        }

        Predicate pushed = Predicates.conjoin(pushable);
        // Push the equality below the operator and recurse, so deeper structure (or a
        // nested same-key operator) is handled too.
        RelNode newInner = rewrite(new SelectionNode(pushed, target.input(), s.location()),
                queryName, ctx, resolver);
        RelNode newOperator = target.rebuild().apply(newInner);

        ctx.record(target.code(), queryName,
                target.noun() + " pruned — σ " + render(pushed) + " pushed below "
                        + target.opLabel() + "; " + target.saving(),
                s.location());

        if (residual.isEmpty()) {
            return newOperator;
        }
        return new SelectionNode(Predicates.conjoin(residual), newOperator, s.location());
    }

    // =========================================================================
    // Pushability descriptor: partition-key equality with a constant
    // =========================================================================

    /**
     * Returns {@code true} when {@code p} is an equality fixing a partition key to a
     * constant — {@code k = const} or {@code const = k} where {@code k}'s unqualified
     * column name is in {@code keys} and the other side references no attribute.
     */
    private static boolean isPartitionEquality(Predicate p, Set<String> keys) {
        if (!(p instanceof ComparisonPredicate c) || c.operator() != ComparisonOperator.EQUAL) {
            return false;
        }
        return matchesKeyAndConstant(c.left(), c.right(), keys)
                || matchesKeyAndConstant(c.right(), c.left(), keys);
    }

    private static boolean matchesKeyAndConstant(Operand maybeKey, Operand maybeConst,
                                                 Set<String> keys) {
        return maybeKey instanceof AttributeOperand a
                && keys.contains(unqualifiedKey(a.name()))
                && isConstant(maybeConst);
    }

    /** An operand is constant when it references no attribute (column). */
    private static boolean isConstant(Operand op) {
        return switch (op) {
            case AttributeOperand ignored -> false;
            case BinaryArithmeticExpression b -> isConstant(b.left()) && isConstant(b.right());
            case UnaryOperand u -> isConstant(u.operand());
            case FunctionCall f -> f.arguments().stream().allMatch(PartitionPruning::isConstant);
            case SetLiteralOperand s -> s.elements().stream().allMatch(PartitionPruning::isConstant);
            case StructConstruction s -> s.fields().stream().allMatch(fl -> isConstant(fl.value()));
            case ArrayConstruction a -> a.elements().stream().allMatch(PartitionPruning::isConstant);
            default -> true;   // literals: number / string / boolean / temporal
        };
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Lower-cased, unqualified key set, for case-insensitive matching. */
    private static Set<String> keySet(List<String> keys) {
        return keys.stream().map(PartitionPruning::unqualifiedKey).collect(Collectors.toSet());
    }

    private static String unqualifiedKey(String name) {
        return AttributeNames.stripQualifier(name).toLowerCase(Locale.ROOT);
    }

    private static String render(Predicate p) {
        return p.accept(new PredicatePrettyPrinter());
    }
}

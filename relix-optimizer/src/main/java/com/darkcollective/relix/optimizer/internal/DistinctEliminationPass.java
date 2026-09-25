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
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.internal.OperandWalker;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.cost.PropertyDeriver;
import com.darkcollective.relix.function.AggregateProperty;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.FunctionProperty;

/**
 * Optimization pass for the two {@code δ} (DISTINCT) elimination rules: one looks
 * <em>down</em> from the {@code δ} ({@code DIST-001}), the other looks <em>up</em>
 * from it ({@code DIST-002}).
 *
 * <h2>Rules</h2>
 * <pre>{@code
 *   δ(R)                 →  R                    when R is already duplicate-free
 *   γ keys, aggs (δ R)   →  γ keys, aggs (R)     when every aggregate ignores multiplicity
 * }</pre>
 *
 * <p>"Duplicate-free" is decided by
 * {@link PropertyDeriver}, which derives distinctness bottom-up from operator
 * semantics: the output of {@code γ}, a set operation ({@code ∪}/{@code ∩}/
 * {@code −}/{@code ∆}), {@code ÷}, transitive closure, {@code ∀}, or another
 * {@code δ} is a set, and distinctness is carried through row-subset operators
 * ({@code σ}/{@code τ}/{@code λ}/sampling/{@code TOP}/{@code OPTIMIZE}, the left of
 * {@code ⋉}/{@code ▷}) and renames. So {@code δ(γ …)}, {@code δ(A ∪ B)},
 * {@code δ(δ …)} and the like collapse to their input.
 *
 * <p>The derivation is purely structural, so it is unaffected by earlier passes
 * having rewritten the input subtree (a rewritten node carries no schema
 * annotation); this pass therefore needs no {@link SchemaAnnotations}.
 *
 * <h2>{@code DIST-002} — the δ its consumer makes irrelevant</h2>
 * <p>An aggregation groups its input, so a {@code δ} beneath it is wasted work
 * <em>provided</em> no aggregate counts duplicates.  {@code MIN} and {@code MAX} reduce a
 * multiset the same way they reduce the set beneath it, and a γ with no aggregates at all
 * is pure grouping; {@code SUM}/{@code COUNT}/{@code AVG}/{@code COLLECT} genuinely read
 * multiplicity, and {@code ARGMAX}/{@code ARGMIN} are excluded as well (deliberately
 * conservative: their yield expression is a second reduction the rule would have to reason
 * about).  Every aggregate argument must also be <em>deterministic</em> — the rule changes
 * how many rows the argument is evaluated over, so {@code MIN(Rand())} is not invariant
 * under it.
 *
 * <p>The saving is real rather than notional: {@code δ} is
 * {@link com.darkcollective.relix.ast.MaterializationMode#SET} — it buffers a whole hash
 * set — and it sits directly below an operator that is itself blocking.
 *
 * <p>The rule looks <em>through</em> a chain of operators that map each input row to at
 * most one output row and so cannot turn "duplicates present" into a different answer
 * above: {@code σ}, {@code π}, {@code ρ} and {@code τ}.  A {@code λ}, a sample or a
 * {@code TOP} is not transparent this way — how many rows they keep depends on how many
 * arrive, which is exactly what the {@code δ} changes.
 *
 * <p>The pass is <em>bottom-up</em>: each input is rewritten before the rule is
 * attempted at the current node, so {@code δ(δ(R))} collapses fully and a
 * {@code δ} exposed as redundant by an inner rewrite is also removed.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
public final class DistinctEliminationPass {

    private DistinctEliminationPass() {}

    /**
     * Applies redundant-DISTINCT elimination (DIST-001, DIST-002) to the entire tree
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

            // ── δ: recurse into input, then try DIST-001 ──────────────────────
            case DistinctNode d -> {
                RelNode ni = rewriteNode(d.input(), queryName, schemas, ctx);
                if (PropertyDeriver.derive(ni, ctx.distinctness()).isDuplicateFree()) {
                    ctx.record(OptimizationCode.DIST_001, queryName,
                            "redundant DISTINCT removed; input is already duplicate-free",
                            d.location());
                    yield ni;
                }
                yield ni != d.input() ? new DistinctNode(ni, d.location()) : d;
            }

            // ── γ: recurse into input, then try DIST-002 ──────────────────────
            case AggregationNode a -> {
                RelNode ni = rewriteNode(a.input(), queryName, schemas, ctx);
                if (duplicateInsensitive(a, ctx.functions())) {
                    RelNode without = withoutDistinct(ni);
                    if (without != null) {
                        ctx.record(OptimizationCode.DIST_002, queryName,
                                "DISTINCT below a duplicate-insensitive aggregation removed",
                                a.location());
                        ni = without;
                    }
                }
                yield ni != a.input()
                        ? new AggregationNode(a.groupingKeys(), a.aggregates(), ni, a.location())
                        : a;
            }

            default -> node.mapChildren(child -> rewriteNode(child, queryName, schemas, ctx));
        };
    }

    // =========================================================================
    // DIST-002 helpers
    // =========================================================================

    /**
     * Whether {@code node}'s result is the same over a bag as over the set beneath it:
     * it has no aggregates at all (pure grouping), or every aggregate
     * {@linkplain AggregateProperty#DUPLICATE_INSENSITIVE declares} that multiplicity
     * does not reach its result, over a deterministic argument.
     *
     * <p>Read off the declaration rather than tested against a list of names, so an
     * aggregate that does not exist yet is handled correctly the day it is installed —
     * and one that never declares the property is excluded, which is the safe direction.
     *
     * <p>Grouping keys need no separate check — a group is identified by its key values,
     * so duplicate rows land in the group they already belong to — beyond the same
     * determinism requirement the aggregates get.
     */
    private static boolean duplicateInsensitive(AggregationNode node, FunctionCatalog functions) {
        for (AggregateFunction aggregate : node.aggregates()) {
            boolean declared = functions.aggregate(aggregate.operator().name())
                    .map(reduction -> reduction.signature()
                            .has(AggregateProperty.DUPLICATE_INSENSITIVE))
                    .orElse(false);
            if (!declared) {
                return false;
            }
            if (!deterministic(aggregate.argument(), functions)) {
                return false;
            }
        }
        // `groupingKeys()` is null for an ungrouped aggregation — the whole relation as
        // one group, where the law holds just the same.
        if (node.groupingKeys() != null) {
            for (GroupingKey key : node.groupingKeys()) {
                if (!deterministic(key.expression(), functions)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether every function call in {@code operand} is an installed function tagged
     * {@link FunctionProperty#DETERMINISTIC}.  A user-defined function is in no library
     * and so counts as non-deterministic — conservative, and the same treatment
     * {@code NOW()}/{@code Rand()} get.
     */
    private static boolean deterministic(Operand operand, FunctionCatalog functions) {
        boolean[] pure = {true};
        OperandWalker.walk(operand, attr -> { }, (FunctionCall call) -> {
            boolean declared = functions.scalar(call.functionName())
                    .map(fn -> fn.signature().has(FunctionProperty.DETERMINISTIC))
                    .orElse(false);
            if (!declared) {
                pure[0] = false;
            }
        });
        return pure[0];
    }

    /**
     * Returns {@code node} with the nearest {@code δ} below it removed, looking through
     * the operators that map each input row to at most one output row; {@code null} when
     * there is no such {@code δ}.
     */
    private static RelNode withoutDistinct(RelNode node) {
        if (node instanceof DistinctNode d) {
            return d.input();
        }
        if (!transparent(node)) {
            return null;
        }
        RelNode child   = node.children().getFirst();
        RelNode rewrite = withoutDistinct(child);
        return rewrite == null ? null : node.mapChildren(c -> c == child ? rewrite : c);
    }

    /**
     * Whether an operator between a γ and a {@code δ} leaves the rule sound: it must emit
     * at most one row per input row, so removing the {@code δ} below it can only re-admit
     * duplicates — which the γ above ignores — and never change which rows arrive.
     */
    private static boolean transparent(RelNode node) {
        return node instanceof SelectionNode
                || node instanceof ProjectionNode
                || node instanceof RenameNode
                || node instanceof SortNode;
    }
}

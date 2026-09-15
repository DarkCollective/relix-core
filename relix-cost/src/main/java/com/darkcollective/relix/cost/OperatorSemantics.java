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

import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;

/**
 * Centralised operator-property classification consulted by
 * {@link PropertyDeriver}, {@link OrderDeriver}, {@link BoundednessChecker},
 * and {@link CostEstimator}.
 *
 * <p>Each method encodes a single shared operator category — a property that
 * more than one deriver must classify consistently.  Centralising here means
 * that adding a new {@link RelNode} type produces a compile error in each
 * deriver's exhaustive switch, and the author need only update this class to
 * declare which shared categories the new type belongs to, then update the
 * derivers that need non-default behaviour.
 *
 * <p>{@link BoundednessChecker} is already covered by
 * {@link RelNode#materializationMode()} on the node itself and does not
 * consult this class.
 *
 * <h2>Coverage invariant</h2>
 * <p>Every {@link RelNode} type must appear in at least one method below (or
 * be absent from all, meaning it falls into the conservative default for that
 * property — order-clearing, breaks distinctness, etc.).  The exhaustive
 * switches in {@link PropertyDeriver} and {@link CostEstimator} catch any
 * newly added type that is accidentally omitted here.
 */
public final class OperatorSemantics {

    private OperatorSemantics() {}

    /**
     * Whether {@code node} is a <em>left-filter operator</em>: its output is a
     * row-subset of its left input — the left rows that pass (or fail) an
     * existence test against the right input — without adding columns or forcing
     * deduplication.
     *
     * <p>True for:
     * <ul>
     *   <li>{@code ⋉} (semi-join) — keeps left rows for which a matching right
     *       row exists; both inputs are monotone positions (see below).</li>
     *   <li>{@code ▷} (anti-join) — keeps left rows for which no matching right
     *       row exists; only the <em>left</em> input is a monotone position.</li>
     *   <li>pairwise-{@code ∀} — keeps left rows for which every right row
     *       satisfies the condition.</li>
     * </ul>
     *
     * <p>Note: {@code −} (difference) and {@code ÷} (division) also output a
     * subset of left-input rows but they enforce deduplication, so
     * {@link PropertyDeriver} categorises them as <em>set-producing</em>
     * operators rather than left-filter operators.  {@link CostEstimator} groups
     * all five together for row-count estimation (output ≤ left-input rows in
     * every case) and handles the additional two explicitly.
     *
     * <p><b>Monotonicity note</b> ({@code FIX} recursion): a recursive
     * reference is valid in both inputs of {@code ⋉} — adding rows to either side
     * can only grow the output — but only in the <em>left</em> input of {@code ▷},
     * because adding rows to the right side of an anti-join removes output rows.
     * The {@code ⋉} right-side monotonicity is deliberately explicit in
     * {@code RecursiveRefChecker} (relix-semantic, which depends on this module
     * rather than the other way round, hence no link) with a design-decision
     * comment.
     *
     * @param node the operator to classify; must not be null
     * @return {@code true} iff {@code node} is a left-filter operator
     */
    public static boolean isLeftFilterOperator(RelNode node) {
        return node instanceof SemiJoinNode
                || node instanceof AntiJoinNode
                || node instanceof PairwiseUniversalNode;
    }

    /**
     * Whether {@code node} <em>preserves the ordering</em> delivered by its
     * first (left) input — i.e., the operator is order-transparent.
     *
     * <p>True for:
     * <ul>
     *   <li>{@code σ} (selection) — filters rows but keeps their relative order.</li>
     *   <li>{@code λ} (limit/offset) — takes an ordered prefix or sub-sequence.</li>
     *   <li>{@code ASOF} (AS-OF join) — streams probe rows in their left-input
     *       order, appending the matched right columns; the left columns (which
     *       carry any delivered ordering) are unchanged.</li>
     *   <li>A relation-only {@link RenameNode} — renames the relation label
     *       without touching column names or row order; a <em>column-renaming</em>
     *       rename invalidates the ordering's column names and therefore does not
     *       preserve order.</li>
     * </ul>
     *
     * <p>Order-establishing operators ({@code τ}) are excluded — they impose a
     * new order rather than preserving the input's.  All other operators
     * conservatively clear the delivered ordering.
     *
     * @param node the operator to classify; must not be null
     * @return {@code true} iff {@code node} passes its input's ordering through
     *         unchanged
     * @see OrderDeriver
     */
    public static boolean preservesInputOrder(RelNode node) {
        return node instanceof SelectionNode
                || node instanceof LimitNode
                || node instanceof AsOfJoinNode
                || (node instanceof RenameNode r && !r.renamesColumns());
    }
}

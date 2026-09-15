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
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.WhyNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements the {@code WHY} <strong>hard optimizer barrier</strong> (ADR-0018):
 * no rule pass rewrites <em>inside</em> or <em>across</em> a {@code WHY}.
 *
 * <p>{@code WHY} reifies its subtree's <em>exact</em> row-set and multiplicities as
 * a lineage column, so any rewrite within the subtree — a column-dropping {@code π}
 * that merges two tuples' provenance into {@code x₁ ⊕ x₂}, a {@code δ}-elimination,
 * a pushdown that changes what the subtree emits — would silently change the
 * reified result. The conservative v1 decision is therefore to freeze the subtree
 * entirely (relaxing to provably-safe cases is a documented follow-on).
 *
 * <p>The barrier is implemented by <em>substitution</em>: before the passes run,
 * {@link #shield(RelNode)} replaces each {@code WhyNode} (and everything below it,
 * including any nested {@code WHY}) with an opaque placeholder leaf; after the
 * passes finish, {@link #restore(RelNode, List)} swaps the original, therefore
 * un-rewritten, {@code WHY} subtrees back in verbatim. Because the placeholder is a
 * leaf, no pass recurses into the frozen subtree and the allowlist-based pushdown
 * passes never pull an operator across it.
 *
 * <p>When the tree contains no {@code WHY}, {@code shield} returns the input
 * reference-unchanged and {@code frozen} is empty, so the common case pays nothing
 * and keeps every existing schema annotation (no ancestor is rebuilt).
 */
final class WhyBarrier {

    /**
     * Placeholder relation-name prefix. The leading {@code NUL} cannot occur in a
     * lexed relation name, so a placeholder can never collide with a real relation.
     */
    private static final String PLACEHOLDER_PREFIX = "\u0000why-barrier:";

    private WhyBarrier() {}

    /**
     * Replaces every {@code WhyNode} subtree in {@code node} with an opaque
     * placeholder leaf, returning the shielded tree paired with the frozen
     * subtrees (indexed by placeholder ordinal).
     *
     * @param node the tree to shield; must not be null
     * @return the shielded tree and the list of frozen {@code WhyNode}s
     */
    static Shielded shield(RelNode node) {
        List<WhyNode> frozen = new ArrayList<>();
        RelNode shielded = shieldInto(node, frozen);
        return new Shielded(shielded, frozen);
    }

    private static RelNode shieldInto(RelNode node, List<WhyNode> frozen) {
        if (node instanceof WhyNode why) {
            int index = frozen.size();
            frozen.add(why);   // freeze the whole subtree — do not descend into it
            return new RelationNode(PLACEHOLDER_PREFIX + index, why.location());
        }
        return node.mapChildren(child -> shieldInto(child, frozen));
    }

    /**
     * Substitutes each placeholder leaf in {@code node} back to its original frozen
     * {@code WhyNode}. The inverse of {@link #shield(RelNode)}.
     *
     * @param node   the (optimized) shielded tree; must not be null
     * @param frozen the frozen subtrees from {@code shield}; must not be null
     * @return the tree with WHY subtrees restored verbatim
     */
    static RelNode restore(RelNode node, List<WhyNode> frozen) {
        if (node instanceof RelationNode r && r.name().startsWith(PLACEHOLDER_PREFIX)) {
            int index = Integer.parseInt(r.name().substring(PLACEHOLDER_PREFIX.length()));
            return frozen.get(index);
        }
        return node.mapChildren(child -> restore(child, frozen));
    }

    /** A shielded tree paired with the WHY subtrees frozen out of it. */
    record Shielded(RelNode tree, List<WhyNode> frozen) {}
}

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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.ast.internal.AstEquivalence;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.RelNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Finds the sub-expressions a query tree reads in more than one place.
 *
 * <p>Two occurrences of one sub-expression are recognised by
 * {@link AstEquivalence#digest(RelNode)}, which is location-free — every AST node
 * carries its {@code SourceLocation} as a record component, so two identical
 * fragments written at different positions are never {@code equals} and could not be
 * matched by equality alone.
 *
 * <h2>Counting sites, not occurrences</h2>
 * <p>The count that matters is <b>how many times the sub-expression would be
 * evaluated</b>, which is not the same as how many times it appears. In
 * {@code (γ (σ R)) ∪ (γ (σ R))} the σ appears twice, but sharing the γ above it
 * already reduces the σ to one evaluation — buffering the σ as well would hold the
 * same rows a second time for a single reader.
 *
 * <p>So the walk descends into a sub-expression only the <b>first</b> time it
 * reaches it: a second encounter is counted and then stopped at, because everything
 * below it is reached through the sharing that second encounter just established.
 * The γ above therefore counts 2 and the σ below counts 1. Write the σ a third time
 * <em>outside</em> both γs and it counts 2 in its own right, and both are shared —
 * the γ's spool then reads the σ's, which is what a plan DAG is.
 *
 * <p>A sub-expression that cannot be shared at all (see the caller's predicate) is
 * walked <em>through</em> rather than counted, so that a shareable sub-expression
 * underneath an unshareable one is still found: two identical aggregations over a
 * random draw cannot be shared, but the selection feeding both of them can.
 *
 * <h2>A fixpoint step is a site per round</h2>
 * <p>Written once is not read once inside a {@link FixpointNode}'s step: semi-naïve
 * evaluation re-executes the step on every iteration, so a sub-expression there is
 * evaluated once per round, and a sub-expression that does not read the recursive
 * relation computes the same rows every time. One occurrence is therefore
 * {@code rounds} evaluations, and the walk reports it as shared on its own — which
 * is why {@link Sharing} says <em>why</em> rather than only how many, the round
 * count being a runtime quantity the planner cannot see.
 *
 * <p>Invariance needs no test of its own: a sub-expression that <em>does</em> read
 * the enclosing recursion is not shareable in the first place (the caller's
 * predicate refuses one naming a recursive relation bound outside itself), so it is
 * walked through and the invariant sub-expressions beneath it are what this finds.
 * The step's own root is the usual case of exactly that — a linear recursion reads
 * the recursive relation somewhere, so the step is walked through and the invariant
 * side of the join below it is what gets spooled. The base is <b>not</b> per-round:
 * it is the seed, evaluated once.
 */
final class SharedSubexpressions {

    private SharedSubexpressions() {
    }

    /**
     * Why a sub-expression is worth sharing, and how widely.
     *
     * @param sites    how many places in the tree read it — at least one
     * @param perRound whether one of those places sits inside a fixpoint step, which
     *                 evaluates it once per iteration however few times it is written
     */
    record Sharing(int sites, boolean perRound) {

        /** A sub-expression read from {@code sites} places, none of them recurring. */
        static Sharing sites(int sites) {
            return new Sharing(sites, false);
        }

        /** Whether one evaluation would stand in for more than one. */
        boolean worthSharing() {
            return perRound || sites > 1;
        }
    }

    /**
     * Returns the digest of every sub-expression of {@code root} that would be
     * evaluated more than once, mapped to why.
     *
     * @param root      the tree to analyse; must not be null
     * @param shareable whether a given sub-expression may be shared at all; consulted
     *                  once per distinct digest
     * @return digest → the sharing it is worth; never null, iteration order stable
     */
    static Map<String, Sharing> detect(RelNode root, Predicate<RelNode> shareable) {
        Map<String, Sharing> sites = new LinkedHashMap<>();
        new Walk(shareable).visit(root, false, sites);
        sites.values().removeIf(sharing -> !sharing.worthSharing());
        return sites;
    }

    /** One traversal, memoizing the shareable test by digest so each distinct tree is tested once. */
    private static final class Walk {
        private final Predicate<RelNode> shareable;
        private final Map<String, Boolean> shareableByDigest = new HashMap<>();

        Walk(Predicate<RelNode> shareable) {
            this.shareable = shareable;
        }

        /**
         * @param perRound whether this node sits inside a fixpoint step, so that
         *                 reaching it once means evaluating it once per iteration
         */
        void visit(RelNode node, boolean perRound, Map<String, Sharing> sites) {
            String digest = AstEquivalence.digest(node);
            if (!shareableByDigest.computeIfAbsent(digest, unused -> shareable.test(node))) {
                visitChildren(node, perRound, sites);
                return;
            }
            Sharing seen = sites.get(digest);
            sites.put(digest, seen == null
                    ? new Sharing(1, perRound)
                    : new Sharing(seen.sites() + 1, seen.perRound() || perRound));
            if (seen != null || perRound) {
                return;   // already walked, or reached once per round; below is reached through it
            }
            visitChildren(node, perRound, sites);
        }

        /**
         * A fixpoint's two children are reached differently: the base seeds the
         * iteration once, the step runs on every round.
         */
        private void visitChildren(RelNode node, boolean perRound, Map<String, Sharing> sites) {
            if (node instanceof FixpointNode fix) {
                visit(fix.base(), perRound, sites);
                visit(fix.step(), true, sites);
                return;
            }
            node.children().forEach(child -> visit(child, perRound, sites));
        }
    }
}

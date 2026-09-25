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
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.PathNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.internal.Predicates;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.visitor.internal.OperandPrettyPrinter;
import com.darkcollective.relix.semantic.SchemaAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Selection-into-{@code PATH} pushdown ({@code PATH-001}, ADR-0020) — the third member of
 * the magic-sets family, beside {@code CLOSURE-001} and {@code TRACE-001}.
 *
 * <p>{@code PATH from, to HOPS m TO n AS depth (E)} performs an <em>all-pairs</em> bounded
 * breadth-first traversal. A selection above it that fixes an endpoint to a constant
 * discards every other pair:
 * <pre>{@code
 *   σ from = "X" (PATH from, to HOPS 1 TO 3 AS depth (E))
 * }</pre>
 * That is the form the operator's own reference page recommends — the start node is
 * deliberately not baked into the syntax, because scoping a traversal is a selection like
 * any other — so the unbounded traversal is what a reader following the manual gets. Folding
 * the equality into the operator as a {@link PathNode#boundSource()} /
 * {@link PathNode#boundTarget()} bound turns it into a single-source (or single-target, or
 * single-pair) search.
 *
 * <h2>Why the distance survives</h2>
 * <p>{@code depth} is the <em>shortest</em> path length, which is what makes
 * {@code (from, to)} a candidate key of the result. Seeding the traversal at one node
 * cannot change it: the shortest path from {@code X} does not depend on which other nodes
 * were also searched from. So the bounded result is exactly the slice of the unbounded one
 * whose source is the bound — the same claim {@code CLOSURE-001} rests on, with a distance
 * column riding along.
 *
 * <p>A target bound is the same traversal over the reversed adjacency, and the distance is
 * again unchanged: the shortest path from a node <em>to</em> {@code T} is the shortest path
 * length either way round.
 *
 * <h2>What is pushable</h2>
 * <p>A top-level conjunct in the selection chain directly above the path is pushed iff it
 * is an <strong>equality</strong> ({@code =}) between the {@code from} or {@code to} column
 * and a <strong>literal</strong> constant, and that endpoint is not already bound. A
 * predicate on {@code depth}, an inequality, a disjunction, a cross-column correlation —
 * each stays as a {@code σ} above the now-bounded path, so an unrecognised conjunct is
 * never dropped and a path with nothing pushable is left untouched.
 *
 * <p>The pass flattens an entire contiguous chain of {@link SelectionNode}s above the path,
 * so it handles the merged ({@code σ a ∧ b}) and split ({@code σ a (σ b …)}) shapes alike.
 * It fires only on a path that is not already bounded.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a static
 * method.
 */
final class SelectionIntoPathPass {

    private static final OperandPrettyPrinter OPND = new OperandPrettyPrinter();

    private SelectionIntoPathPass() {}

    /**
     * Applies PATH-001 to the entire tree rooted at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations; unused (the rule is purely structural) but
     *                  accepted for API consistency with the other passes
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, ctx);
    }

    private static RelNode rewriteNode(RelNode node, String queryName, OptimizationContext ctx) {
        if (node instanceof SelectionNode s) {
            // Peel the contiguous σ-chain directly above any node.
            List<SelectionNode> chain = new ArrayList<>();
            RelNode cur = s;
            while (cur instanceof SelectionNode sn) {
                chain.add(sn);
                cur = sn.input();
            }
            if (cur instanceof PathNode path
                    && path.boundSource().isEmpty()
                    && path.boundTarget().isEmpty()) {
                return pushIntoPath(chain, path, queryName, ctx);
            }
            RelNode ni = rewriteNode(s.input(), queryName, ctx);
            return ni == s.input() ? s : new SelectionNode(s.predicate(), ni, s.location());
        }
        return node.mapChildren(child -> rewriteNode(child, queryName, ctx));
    }

    /**
     * Folds every pushable endpoint equality from {@code chain} into {@code path} and
     * rebuilds the residual selections above it. {@code chain} is ordered outermost-first
     * and bottoms out at {@code path}.
     */
    private static RelNode pushIntoPath(List<SelectionNode> chain, PathNode path,
                                        String queryName, OptimizationContext ctx) {
        List<Predicate> conjuncts = new ArrayList<>();
        for (SelectionNode sn : chain) {
            conjuncts.addAll(Predicates.conjuncts(sn.predicate()));
        }

        Optional<Operand> boundSource = Optional.empty();
        Optional<Operand> boundTarget = Optional.empty();
        List<Predicate> residual = new ArrayList<>();

        for (Predicate p : conjuncts) {
            Optional<Operand> srcLit = boundSource.isEmpty()
                    ? Predicates.equalityLiteralFor(p, path.fromColumn()) : Optional.empty();
            if (srcLit.isPresent()) {
                boundSource = srcLit;
                continue;
            }
            Optional<Operand> tgtLit = boundTarget.isEmpty()
                    ? Predicates.equalityLiteralFor(p, path.toColumn()) : Optional.empty();
            if (tgtLit.isPresent()) {
                boundTarget = tgtLit;
                continue;
            }
            residual.add(p);
        }

        RelNode newInput = rewriteNode(path.input(), queryName, ctx);

        if (boundSource.isEmpty() && boundTarget.isEmpty()) {
            // Nothing pushable. Preserve reference identity (the no-op convention) when the
            // recursed input is unchanged; otherwise rebuild the chain over it.
            if (newInput == path.input()) {
                return chain.get(0);
            }
            return rebuildChain(chain, withInput(path, newInput));
        }

        PathNode bounded = withInput(path, newInput).withBounds(boundSource, boundTarget);

        ctx.record(OptimizationCode.PATH_001, queryName,
                describe(path, boundSource, boundTarget), path.location());

        if (residual.isEmpty()) {
            return bounded;
        }
        return new SelectionNode(Predicates.conjoin(residual), bounded, chain.get(0).location());
    }

    private static PathNode withInput(PathNode path, RelNode input) {
        return new PathNode(input, path.fromColumn(), path.toColumn(), path.undirected(),
                path.minHops(), path.maxHops(), path.depthColumn(),
                path.boundSource(), path.boundTarget(), path.location());
    }

    /** Rebuilds the original selection chain (outermost-first) over {@code base}. */
    private static RelNode rebuildChain(List<SelectionNode> chain, RelNode base) {
        RelNode out = base;
        for (int i = chain.size() - 1; i >= 0; i--) {
            SelectionNode sn = chain.get(i);
            out = new SelectionNode(sn.predicate(), out, sn.location());
        }
        return out;
    }

    private static String describe(PathNode path,
                                   Optional<Operand> boundSource,
                                   Optional<Operand> boundTarget) {
        if (boundSource.isPresent() && boundTarget.isPresent()) {
            return "σ folded into PATH — single-pair "
                    + path.fromColumn() + "=" + boundSource.get().accept(OPND)
                    + " ⇝ " + path.toColumn() + "=" + boundTarget.get().accept(OPND)
                    + " (all-pairs traversal avoided)";
        }
        if (boundSource.isPresent()) {
            return "σ folded into PATH — single-source from "
                    + path.fromColumn() + "=" + boundSource.get().accept(OPND)
                    + " (all-pairs traversal avoided)";
        }
        return "σ folded into PATH — single-target into "
                + path.toColumn() + "=" + boundTarget.get().accept(OPND)
                + " on reversed adjacency (all-pairs traversal avoided)";
    }
}

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
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.Operand;
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
 * Selection-into-{@code CLOSURE} pushdown — the canonical magic-sets /
 * sideways-information-passing rewrite ({@code CLOSURE-001}, ADR-0020).
 *
 * <p>{@code CLOSURE from, to (E)} computes the <em>all-pairs</em> transitive
 * closure (reachability between every pair). A selection above it that fixes the
 * source and/or target endpoint to a constant discards every other pair:
 * <pre>{@code
 *   σ from = "X" (CLOSURE from, to (E))
 * }</pre>
 * Because {@code from} (resp. {@code to}) is a <em>partitioning dimension</em> —
 * the all-pairs result filtered to {@code from = X} is identical to seeding the
 * traversal only from {@code X} — the equality can be folded into the operator as
 * a {@link ClosureNode#boundSource()} / {@link ClosureNode#boundTarget()} bound,
 * collapsing all-pairs to single-source / single-target / single-pair
 * reachability (an asymptotic, semantically exact win).
 *
 * <h2>What is pushable</h2>
 * <p>A top-level conjunct in the selection chain directly above the closure is
 * pushed iff it is an <strong>equality</strong> ({@code =}) between the
 * {@code from} or {@code to} column and a <strong>literal</strong> constant
 * (string / number / boolean / temporal), and that endpoint is not already bound.
 * Everything else — disjunctions, inequalities, predicates on other columns, a
 * second equality on an already-bound endpoint, cross-column correlations like
 * {@code from = to} — stays as a {@code σ} above the (now bounded) closure. The
 * rewrite is therefore <em>correctness-preserving by construction</em>: an
 * unrecognised conjunct is never dropped, and a closure with no pushable conjunct
 * is left untouched (a no-op).
 *
 * <p>The pass flattens an entire contiguous chain of {@link SelectionNode}s above
 * the closure and re-derives the residual selections, so it handles both the
 * merged ({@code σ a ∧ b}) and the split ({@code σ a (σ b …)}) shapes regardless
 * of which selection passes ran before it. It fires only on a closure that is not
 * already bounded.
 *
 * <h2>Observability</h2>
 * <p>Each firing records {@link OptimizationCode#CLOSURE_001} (a
 * {@link com.darkcollective.relix.events.QueryEvent.Stage#OPTIMIZE} event via the
 * {@link OptimizationContext} listener), stating which endpoint(s) were bound and
 * to which constant(s). No event is emitted for an unconstrained closure.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class SelectionIntoClosurePass {

    private static final OperandPrettyPrinter OPND = new OperandPrettyPrinter();

    private SelectionIntoClosurePass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies CLOSURE-001 to the entire tree rooted at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations; unused (the rule is purely structural)
     *                  but accepted for API consistency with the other passes
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, ctx);
    }

    // =========================================================================
    // RelNode traversal (top-down: a whole selection chain is consumed at once)
    // =========================================================================

    private static RelNode rewriteNode(RelNode node, String queryName, OptimizationContext ctx) {
        if (node instanceof SelectionNode s) {
            // Peel the contiguous σ-chain directly above any node.
            List<SelectionNode> chain = new ArrayList<>();
            RelNode cur = s;
            while (cur instanceof SelectionNode sn) {
                chain.add(sn);
                cur = sn.input();
            }
            if (cur instanceof ClosureNode closure
                    && closure.boundSource().isEmpty()
                    && closure.boundTarget().isEmpty()) {
                return pushIntoClosure(chain, closure, queryName, ctx);
            }
            // Not a closure chain — recurse into the input only (the chain above is
            // left intact; selection structure is owned by the other passes).
            RelNode ni = rewriteNode(s.input(), queryName, ctx);
            return ni == s.input() ? s : new SelectionNode(s.predicate(), ni, s.location());
        }
        return node.mapChildren(child -> rewriteNode(child, queryName, ctx));
    }

    // =========================================================================
    // Rewrite
    // =========================================================================

    /**
     * Folds every pushable endpoint equality from {@code chain} into {@code closure}
     * and rebuilds the residual selections above it. {@code chain} is ordered
     * outermost-first and bottoms out at {@code closure}.
     */
    private static RelNode pushIntoClosure(List<SelectionNode> chain, ClosureNode closure,
                                           String queryName, OptimizationContext ctx) {
        // Flatten all top-level conjuncts, outer selection first.
        List<Predicate> conjuncts = new ArrayList<>();
        for (SelectionNode sn : chain) {
            conjuncts.addAll(Predicates.conjuncts(sn.predicate()));
        }

        Optional<Operand> boundSource = Optional.empty();
        Optional<Operand> boundTarget = Optional.empty();
        List<Predicate> residual = new ArrayList<>();

        for (Predicate p : conjuncts) {
            Optional<Operand> srcLit = boundSource.isEmpty()
                    ? Predicates.equalityLiteralFor(p, closure.fromColumn()) : Optional.empty();
            if (srcLit.isPresent()) {
                boundSource = srcLit;
                continue;
            }
            Optional<Operand> tgtLit = boundTarget.isEmpty()
                    ? Predicates.equalityLiteralFor(p, closure.toColumn()) : Optional.empty();
            if (tgtLit.isPresent()) {
                boundTarget = tgtLit;
                continue;
            }
            residual.add(p);
        }

        RelNode newInput = rewriteNode(closure.input(), queryName, ctx);

        if (boundSource.isEmpty() && boundTarget.isEmpty()) {
            // Nothing pushable. Preserve reference identity (the no-op convention) when
            // the recursed input is unchanged; otherwise rebuild the chain over it.
            if (newInput == closure.input()) {
                return chain.get(0);
            }
            ClosureNode rebuilt = new ClosureNode(newInput, closure.fromColumn(),
                    closure.toColumn(), closure.undirected(), closure.reflexive(),
                    closure.boundSource(), closure.boundTarget(), closure.location());
            return rebuildChain(chain, rebuilt);
        }

        // Undirected is carried through: the adjacency a bound seeds a traversal over is
        // symmetric or not by the operator's own reading, which pushing a bound does not
        // change.
        ClosureNode bounded = new ClosureNode(newInput, closure.fromColumn(), closure.toColumn(),
                closure.undirected(), closure.reflexive(), boundSource, boundTarget,
                closure.location());

        ctx.record(OptimizationCode.CLOSURE_001, queryName,
                describe(closure, boundSource, boundTarget), closure.location());

        // Re-wrap any residual conjuncts as a single selection above the bounded closure.
        if (residual.isEmpty()) {
            return bounded;
        }
        Predicate combined = Predicates.conjoin(residual);
        return new SelectionNode(combined, bounded, chain.get(0).location());
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

    // =========================================================================
    // Event description
    // =========================================================================

    private static String describe(ClosureNode closure,
                                   Optional<Operand> boundSource,
                                   Optional<Operand> boundTarget) {
        String op = closure.reflexive() ? "RCLOSURE" : "CLOSURE";
        if (boundSource.isPresent() && boundTarget.isPresent()) {
            return "σ folded into " + op + " — single-pair "
                    + closure.fromColumn() + "=" + boundSource.get().accept(OPND)
                    + " ⇝ " + closure.toColumn() + "=" + boundTarget.get().accept(OPND)
                    + " (all-pairs reachability avoided)";
        }
        if (boundSource.isPresent()) {
            return "σ folded into " + op + " — single-source from "
                    + closure.fromColumn() + "=" + boundSource.get().accept(OPND)
                    + " (all-pairs reachability avoided)";
        }
        return "σ folded into " + op + " — single-target into "
                + closure.toColumn() + "=" + boundTarget.get().accept(OPND)
                + " on reversed adjacency (all-pairs reachability avoided)";
    }
}

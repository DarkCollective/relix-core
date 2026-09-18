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

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.Predicates;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.ast.visitor.OperandPrettyPrinter;
import com.darkcollective.relix.semantic.SchemaAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Selection-into-{@code TRACE} pushdown — the magic-sets / sideways-information-passing
 * rewrite specialised to optimal-path search ({@code TRACE-001}, ADR-0020).
 *
 * <p>{@code TRACE from, to VIA w MINIMIZE AS path (E)} computes the <em>all-pairs</em>
 * optimal paths (a Bellman-Ford–style relaxation seeded from every edge). A selection
 * above it that fixes the origin and/or destination to a constant discards every other
 * pair:
 * <pre>{@code
 *   σ from = "JFK" ∧ to = "LAX" (TRACE from, to VIA cost MINIMIZE AS route (Flights))
 * }</pre>
 * Because {@code from}/{@code to} index independent sub-computations, filtering the
 * output by an endpoint equality equals seeding the search from (resp. into) that
 * endpoint alone. The equality is folded into the operator as a
 * {@link TraceNode#boundSource()} / {@link TraceNode#boundTarget()} bound, collapsing
 * all-pairs to single-source / single-target / single-pair search.
 *
 * <h2>What is pushable</h2>
 * <p>A top-level conjunct directly above the trace is pushed iff it is an
 * <strong>equality</strong> ({@code =}) between the {@code from} or {@code to} column
 * and a <strong>literal</strong> constant (string / number / boolean / temporal), and
 * that endpoint is not already bound. Everything else — disjunctions, inequalities (e.g.
 * a {@code cost < 300} residual), predicates on other columns, {@code from = to}
 * correlations — stays as a {@code σ} above the (now bounded) trace. The rewrite is
 * therefore <em>correctness-preserving by construction</em>: an unrecognised conjunct is
 * never dropped, and a trace with no pushable conjunct is left untouched (a no-op).
 *
 * <h2>Scope</h2>
 * <p>This pass adds only the <em>logical</em> endpoint boundaries (a
 * {@link com.darkcollective.relix.events.QueryEvent.Stage#OPTIMIZE} event). The
 * consequent physical algorithm switch — single-pair Dijkstra for the non-negative
 * MINIMIZE case — belongs to the planner as a
 * {@link com.darkcollective.relix.events.QueryEvent.Stage#PLAN} decision
 * ({@code Planner.traceAlgorithm}), which reads the bounds this pass folded in.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a static
 * method.
 */
final class SelectionIntoTracePass {

    private static final OperandPrettyPrinter OPND = new OperandPrettyPrinter();

    private SelectionIntoTracePass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies TRACE-001 to the entire tree rooted at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations; unused (the rule is purely structural)
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
            List<SelectionNode> chain = new ArrayList<>();
            RelNode cur = s;
            while (cur instanceof SelectionNode sn) {
                chain.add(sn);
                cur = sn.input();
            }
            if (cur instanceof TraceNode trace
                    && trace.boundSource().isEmpty()
                    && trace.boundTarget().isEmpty()) {
                return pushIntoTrace(chain, trace, queryName, ctx);
            }
            RelNode ni = rewriteNode(s.input(), queryName, ctx);
            return ni == s.input() ? s : new SelectionNode(s.predicate(), ni, s.location());
        }
        return node.mapChildren(child -> rewriteNode(child, queryName, ctx));
    }

    // =========================================================================
    // Rewrite
    // =========================================================================

    private static RelNode pushIntoTrace(List<SelectionNode> chain, TraceNode trace,
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
                    ? Predicates.equalityLiteralFor(p, trace.fromColumn()) : Optional.empty();
            if (srcLit.isPresent()) {
                boundSource = srcLit;
                continue;
            }
            Optional<Operand> tgtLit = boundTarget.isEmpty()
                    ? Predicates.equalityLiteralFor(p, trace.toColumn()) : Optional.empty();
            if (tgtLit.isPresent()) {
                boundTarget = tgtLit;
                continue;
            }
            residual.add(p);
        }

        RelNode newInput = rewriteNode(trace.input(), queryName, ctx);

        if (boundSource.isEmpty() && boundTarget.isEmpty()) {
            if (newInput == trace.input()) {
                return chain.get(0);
            }
            return rebuildChain(chain, withInput(trace, newInput));
        }

        TraceNode bounded = new TraceNode(newInput, trace.fromColumn(), trace.toColumn(),
                trace.undirected(), trace.weightColumn(), trace.sense(), trace.pathColumn(),
                boundSource, boundTarget, trace.location());

        ctx.record(OptimizationCode.TRACE_001, queryName,
                describe(trace, boundSource, boundTarget), trace.location());

        if (residual.isEmpty()) {
            return bounded;
        }
        return new SelectionNode(Predicates.conjoin(residual), bounded, chain.get(0).location());
    }

    private static TraceNode withInput(TraceNode trace, RelNode input) {
        return new TraceNode(input, trace.fromColumn(), trace.toColumn(),
                trace.undirected(), trace.weightColumn(), trace.sense(), trace.pathColumn(),
                trace.boundSource(), trace.boundTarget(), trace.location());
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

    private static String describe(TraceNode trace,
                                   Optional<Operand> boundSource,
                                   Optional<Operand> boundTarget) {
        if (boundSource.isPresent() && boundTarget.isPresent()) {
            return "σ folded into TRACE — single-pair "
                    + trace.fromColumn() + "=" + boundSource.get().accept(OPND)
                    + " ⇝ " + trace.toColumn() + "=" + boundTarget.get().accept(OPND)
                    + " (all-pairs path search avoided)";
        }
        if (boundSource.isPresent()) {
            return "σ folded into TRACE — single-source from "
                    + trace.fromColumn() + "=" + boundSource.get().accept(OPND)
                    + " (all-pairs path search avoided)";
        }
        return "σ folded into TRACE — single-target into "
                + trace.toColumn() + "=" + boundTarget.get().accept(OPND)
                + " on the reversed graph (all-pairs path search avoided)";
    }
}

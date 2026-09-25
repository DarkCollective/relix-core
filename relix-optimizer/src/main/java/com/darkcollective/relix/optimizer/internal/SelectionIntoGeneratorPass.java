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
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.internal.Predicates;
import com.darkcollective.relix.ast.ProduceBound;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.visitor.internal.OperandPrettyPrinter;
import com.darkcollective.relix.semantic.SchemaAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Generator bound pushdown — the producer member of the selection-into-expensive-operator
 * family ({@code GEN-001}, ADR-0020), and the one case where the pushdown is about
 * <em>termination</em>, not merely efficiency.
 *
 * <p>An unbounded ascending generator ({@code Naturals}, {@code Primes}) produces values
 * forever. A {@code σ} above it that only filters never stops the producer, so
 * {@code σ n < 100 (Naturals)} does not terminate. When the selection carries a top-level
 * <em>upper bound</em> on the generator's ascending value column, this pass folds that bound
 * into the leaf as a {@link ProduceBound} production stop, so the executor stops producing
 * once the threshold is passed:
 * <pre>{@code
 *   σ n < 100 (Naturals)  →  σ n < 100 (Naturals ⟨produce while n < 100⟩)
 * }</pre>
 *
 * <p>The {@code σ} is <strong>kept</strong> above the leaf as a residual filter — the bound
 * only limits how much is produced, never the result, so the rewrite is correctness-preserving
 * by construction. The bounded leaf also becomes boundedness-{@code BOUNDED}
 * ({@link com.darkcollective.relix.cost.PropertyDeriver}), so a downstream blocking operator
 * ({@code γ}/{@code τ}/…) over it — previously rejected by the
 * {@link com.darkcollective.relix.cost.BoundednessChecker} — becomes legal.
 *
 * <h2>What is pushable</h2>
 * <p>Only when the leaf is a monotone-ascending unbounded generator (consulted via
 * {@link OptimizationContext#monotoneGenerators()}) and a top-level conjunct is an
 * <strong>upper-bound</strong> comparison ({@code <}, {@code <=}, {@code =}) between the
 * generator's ascending column and a <strong>literal</strong>. Lower bounds ({@code n > k}),
 * non-monotone predicates (parity), disjunctions, and predicates on other columns do not
 * bound termination and stay as the residual {@code σ}. The first pushable upper bound wins;
 * a generator already carrying a bound is left untouched.
 *
 * <p>Package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a static method.
 */
final class SelectionIntoGeneratorPass {

    private static final OperandPrettyPrinter OPND = new OperandPrettyPrinter();

    private SelectionIntoGeneratorPass() {}

    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, ctx);
    }

    private static RelNode rewriteNode(RelNode node, String queryName, OptimizationContext ctx) {
        if (node instanceof SelectionNode s) {
            List<SelectionNode> chain = new ArrayList<>();
            RelNode cur = s;
            while (cur instanceof SelectionNode sn) {
                chain.add(sn);
                cur = sn.input();
            }
            if (cur instanceof RelationNode leaf && leaf.produceBound().isEmpty()) {
                Optional<String> col = ctx.monotoneGenerators().ascendingColumn(leaf.name());
                if (col.isPresent()) {
                    return pushIntoGenerator(chain, leaf, col.get(), queryName, ctx);
                }
            }
            RelNode ni = rewriteNode(s.input(), queryName, ctx);
            return ni == s.input() ? s : new SelectionNode(s.predicate(), ni, s.location());
        }
        return node.mapChildren(child -> rewriteNode(child, queryName, ctx));
    }

    /**
     * Folds the first top-level upper-bound conjunct on {@code column} into {@code leaf} as a
     * {@link ProduceBound}, rebuilding the (unchanged) selection chain above it. The σ-chain is
     * never removed — only the leaf gains the production stop.
     */
    private static RelNode pushIntoGenerator(List<SelectionNode> chain, RelationNode leaf,
                                             String column, String queryName,
                                             OptimizationContext ctx) {
        List<Predicate> conjuncts = new ArrayList<>();
        for (SelectionNode sn : chain) {
            conjuncts.addAll(Predicates.conjuncts(sn.predicate()));
        }
        Optional<ProduceBound> bound = Optional.empty();
        for (Predicate p : conjuncts) {
            bound = upperBoundFor(p, column);
            if (bound.isPresent()) {
                break;
            }
        }
        if (bound.isEmpty()) {
            return chain.get(0);   // no pushable upper bound — leave the chain untouched
        }
        RelationNode bounded = leaf.withProduceBound(bound.get());
        ctx.record(OptimizationCode.GEN_001, queryName,
                "bound pushed into generator " + leaf.name() + " — produce while "
                        + column + " " + bound.get().operator().symbol() + " "
                        + bound.get().limit().accept(OPND)
                        + " (unbounded scan avoided)",
                leaf.location());
        // Rebuild the σ-chain verbatim over the now-bounded leaf (σ stays as residual filter).
        RelNode out = bounded;
        for (int i = chain.size() - 1; i >= 0; i--) {
            SelectionNode sn = chain.get(i);
            out = new SelectionNode(sn.predicate(), out, sn.location());
        }
        return out;
    }


    /**
     * Returns a {@link ProduceBound} if {@code p} is a top-level upper-bound comparison
     * ({@code col < lit}, {@code col <= lit}, {@code col = lit}, or the mirrored
     * {@code lit > col} / {@code lit >= col}) between {@code column} and a literal.
     */
    private static Optional<ProduceBound> upperBoundFor(Predicate p, String column) {
        if (!(p instanceof ComparisonPredicate c)) {
            return Optional.empty();
        }
        // column OP literal
        if (Predicates.isColumn(c.left(), column) && Predicates.isLiteral(c.right())) {
            return forward(c.operator()).map(op -> new ProduceBound(column, op, c.right()));
        }
        // literal OP column  →  mirror the operator
        if (Predicates.isColumn(c.right(), column) && Predicates.isLiteral(c.left())) {
            return mirrored(c.operator()).map(op -> new ProduceBound(column, op, c.left()));
        }
        return Optional.empty();
    }

    /** Keeps only the operators that are upper bounds with the column on the left. */
    private static Optional<ComparisonOperator> forward(ComparisonOperator op) {
        return switch (op) {
            case LESS, LESS_EQUAL, EQUAL -> Optional.of(op);
            default -> Optional.empty();
        };
    }

    /** Maps {@code lit OP col} to the equivalent column-on-left upper bound. */
    private static Optional<ComparisonOperator> mirrored(ComparisonOperator op) {
        return switch (op) {
            case GREATER -> Optional.of(ComparisonOperator.LESS);          // k > n  ≡  n < k
            case GREATER_EQUAL -> Optional.of(ComparisonOperator.LESS_EQUAL); // k >= n ≡ n <= k
            case EQUAL -> Optional.of(ComparisonOperator.EQUAL);
            default -> Optional.empty();
        };
    }


}

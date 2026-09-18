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
import com.darkcollective.relix.semantic.SchemaAnnotations;

import java.util.List;
import java.util.Objects;

/**
 * The optimizer's rule registry and its bounded fixpoint driver.
 *
 * <p>A pipeline is an ordered list of {@link Phase}s; a phase is an ordered list of
 * {@link OptimizationRule}s plus the number of times that group may be re-run.
 * {@link QueryOptimizer} builds the {@linkplain #defaultPipeline() default pipeline}
 * once per query and calls {@link #run}.
 *
 * <h2>Why phases, and not one global loop</h2>
 * <p>Some pairs of rules are mutual inverses and would oscillate forever if iterated
 * together:
 * <ul>
 *   <li>{@code SEL-003} (σ below π) and {@code PROJ-003} (π below σ);</li>
 *   <li>{@code SEL-001} (split a conjunction) and {@code SEL-002} (merge adjacent
 *       selections).</li>
 * </ul>
 * <p>Each such pair is split across two phases, and phases run once each in order —
 * only the rules <em>within</em> a phase are iterated.  That is why the pushdown
 * phase deliberately excludes {@code ProjectionPass} and {@code SelectionMergePass},
 * which live in the cleanup phase that follows it.  <strong>Any new rule must be
 * placed in a phase whose other members it cannot undo.</strong>
 *
 * <h2>What iteration buys</h2>
 * <p>Within a phase, one rule's rewrite can expose the pattern a rule <em>earlier</em>
 * in the same phase matches on, and that earlier rule has already had its turn.  The
 * demonstrable case is {@code DIST-001} removing a δ from between two selections: the
 * σ pair is now adjacent, which is exactly what {@code SEL-002} merges — and
 * {@code SEL-002} runs before {@code DIST-001} in the cleanup phase, so without a
 * second sweep the merge never happens ({@code PipelineConvergenceTest} pins this at
 * cap 1 versus cap 8).
 *
 * <p>What iteration does <em>not</em> buy is a cascade <em>within</em> one rule: every
 * pass here recurses bottom-up, so it already reaches its own fixpoint in a single
 * application.  {@code AGG-001} is the case to be clear about — a three-deep γ stack
 * collapses <em>fully</em> inside one {@code apply}, because each input is rewritten
 * before the rule is tested at the node above it.  With today's rule set the default
 * pipeline settles in one sweep per phase on every shape tested; the loop's value is
 * the guarantee it gives the <em>next</em> rule, at a cost of one confirming sweep per
 * phase that fired.
 *
 * <h2>Termination</h2>
 * <p>A sweep counts as progress only when the phase's rules <em>both</em> recorded at
 * least one firing in the {@link OptimizationContext} <em>and</em> returned a
 * different tree — reference inequality, relying on {@link RelNode#mapChildren}'s
 * documented contract that it returns {@code this} when no child changed.  The first
 * sweep that makes no progress ends the phase.  Iteration is additionally capped at
 * {@link Phase#maxIterations()} sweeps, so a rule pair that oscillates despite the
 * phase split degrades to a bounded amount of wasted work rather than a hang.
 *
 * <p>Instances are immutable and safe to share.
 */
public final class OptimizationPipeline {

    /**
     * Sweeps allowed for an iterated phase.  Chosen to comfortably exceed the deepest
     * cascade the current rule set can produce — which is one confirming sweep beyond
     * the first, since every pass is internally recursive — while keeping the worst
     * case bounded for a rule that genuinely needs several.
     */
    public static final int DEFAULT_MAX_ITERATIONS = 8;

    /** A phase run exactly once, for a rule that must not be re-applied. */
    public static final int SINGLE_PASS = 1;

    private final List<Phase> phases;

    private OptimizationPipeline(List<Phase> phases) {
        this.phases = List.copyOf(phases);
    }

    // =========================================================================
    // Phase
    // =========================================================================

    /**
     * One group of rules, applied in order and re-applied while they keep making
     * progress.
     *
     * @param name          short stable identifier, e.g. {@code "pushdown"}
     * @param rules         the rules, in application order; must not be empty
     * @param maxIterations how many sweeps this phase may run;
     *                      {@link #SINGLE_PASS} for a phase that must run once
     */
    public record Phase(String name, List<OptimizationRule> rules, int maxIterations) {

        /** Validates and defensively copies the rule list. */
        public Phase {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(rules, "rules");
            if (name.isBlank()) {
                throw new IllegalArgumentException("phase name must not be blank");
            }
            if (rules.isEmpty()) {
                throw new IllegalArgumentException("phase '" + name + "' has no rules");
            }
            if (maxIterations < 1) {
                throw new IllegalArgumentException(
                        "phase '" + name + "' maxIterations must be >= 1, was " + maxIterations);
            }
            rules = List.copyOf(rules);
        }

        /**
         * Returns whether this phase may run more than one sweep.
         *
         * @return {@code true} when {@link #maxIterations()} &gt; 1
         */
        public boolean iterated() {
            return maxIterations > 1;
        }
    }

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Creates a pipeline from an explicit phase list — the seam for a caller that
     * wants a subset of the rules — a test pinning one phase, for instance.
     *
     * @param phases the phases, in application order; must not be null or empty
     * @return the pipeline; never null
     */
    public static OptimizationPipeline of(List<Phase> phases) {
        Objects.requireNonNull(phases, "phases");
        if (phases.isEmpty()) {
            throw new IllegalArgumentException("a pipeline needs at least one phase");
        }
        return new OptimizationPipeline(phases);
    }

    /**
     * The default pipeline: every rule the optimizer ships, in phase order.
     *
     * <p>Every rule here is a pure syntactic rewrite, so the pipeline needs no
     * {@link com.darkcollective.relix.symbol.table.SymbolTable} and no cost model.  It
     * did until {@code JOIN-003} was removed — that rule swapped a commutative
     * join's inputs on a cost estimate, and was the sole reason this factory took a
     * symbol table.  A cost-driven rewrite belongs in the planner, where the decision
     * can be made without permuting the logical schema; see {@code Planner.buildSide}.
     *
     * @return the pipeline; never null
     */
    public static OptimizationPipeline defaultPipeline() {
        return new OptimizationPipeline(List.of(
                simplifyPhase(),
                pushdownPhase(),
                cleanupPhase(),
                sipPhase(),
                limitPhase(),
                prunePhase()));
    }

    /**
     * Phase 1 — expression and predicate simplification.  Runs first so every later
     * rule matches against folded constants and normalised comparisons.
     */
    private static Phase simplifyPhase() {
        return new Phase("simplify", List.of(
                // Also declares the PRED codes: it is the only pass that walks *operands*,
                // so it is the only one that can reach a predicate written in operand
                // position — the condition of an IIf — and it simplifies that condition
                // fully rather than half.
                PassRule.of("expression-simplification",
                        ExpressionSimplificationPass::apply,
                        OptimizationCode.EXPR_001, OptimizationCode.EXPR_002,
                        OptimizationCode.EXPR_003, OptimizationCode.EXPR_004,
                        OptimizationCode.EXPR_005, OptimizationCode.EXPR_006,
                        OptimizationCode.EXPR_007, OptimizationCode.EXPR_008,
                        OptimizationCode.PRED_001, OptimizationCode.PRED_002,
                        OptimizationCode.PRED_003, OptimizationCode.PRED_004,
                        OptimizationCode.PRED_005, OptimizationCode.PRED_006),
                // PRED after EXPR: predicate folding matches on the literals that
                // expression folding produces.
                PassRule.of("predicate-simplification",
                        PredicateSimplificationPass::apply,
                        OptimizationCode.PRED_001, OptimizationCode.PRED_002,
                        OptimizationCode.PRED_003, OptimizationCode.PRED_004,
                        OptimizationCode.PRED_005, OptimizationCode.PRED_006)),
                DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Phase 2 — move filters and limits toward the data.  {@code SEL-001} splits
     * first so each conjunct travels independently; the inverse {@code SEL-002} merge
     * is deliberately in the cleanup phase, as is {@code PROJ-003}, the inverse of
     * {@code SEL-003}.
     *
     * <p>{@code LATERAL-001} is <em>not</em> here, though its output feeds these rules: it
     * has to classify a table-valued function's body as deterministic, so it needs a
     * {@link com.darkcollective.relix.symbol.table.SymbolTable} and runs in
     * {@link QueryOptimizer}'s preamble instead — see {@link #defaultPipeline()}.
     */
    private static Phase pushdownPhase() {
        return new Phase("pushdown", List.of(
                PassRule.of("selection-split", SelectionSplitPass::apply,
                        OptimizationCode.SEL_001),
                // Before EQ-001, which reads facts out of inner-join conditions only: a
                // join demoted here becomes eligible for equality propagation in the
                // same sweep.
                PassRule.of("outer-join-demotion", OuterJoinDemotionPass::apply,
                        OptimizationCode.JOIN_004),
                // Before the pushdown rules, so the predicates it derives are carried
                // down by the existing machinery rather than needing pushdown logic of
                // their own.
                PassRule.of("transitive-equality", TransitiveEqualityPass::apply,
                        OptimizationCode.EQ_001),
                PassRule.of("nest-unnest", NestUnnestPass::apply,
                        OptimizationCode.NEST_001, OptimizationCode.NEST_002,
                        OptimizationCode.NEST_003),
                PassRule.of("selection-pushdown", SelectionPushdownPass::apply,
                        OptimizationCode.SEL_003, OptimizationCode.SEL_004,
                        OptimizationCode.SEL_005, OptimizationCode.SEL_006,
                        OptimizationCode.SEL_007, OptimizationCode.SEL_008,
                        OptimizationCode.SEL_009),
                PassRule.of("selection-into-window", SelectionIntoWindowPass::apply,
                        OptimizationCode.WINDOW_001, OptimizationCode.TOPK_001),
                PassRule.of("selection-into-optimize", SelectionIntoOptimizePass::apply,
                        OptimizationCode.OPTIMIZE_001),
                PassRule.of("selection-into-time-series", SelectionIntoTimeSeriesPass::apply,
                        OptimizationCode.SESSION_001, OptimizationCode.DOWNSAMPLE_001),
                PassRule.of("join-rules", JoinRulesPass::apply,
                        OptimizationCode.JOIN_001, OptimizationCode.JOIN_002)),
                DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Phase 3 — cleanup: put the conjunctions back together, tidy the projections,
     * and drop operators a rewrite made redundant.  Iterated, which is what lets
     * {@code SEL-002} merge the σ pair that {@code DIST-001}, further down this same
     * phase, made adjacent.
     *
     * <p>{@code PROD-001} belongs here rather than in {@code pushdown}: it only ever
     * <em>removes</em> a node, and no rule in this phase reintroduces a {@code ×}, so
     * it cannot oscillate against a phase-mate.
     */
    private static Phase cleanupPhase() {
        return new Phase("cleanup", List.of(
                PassRule.of("selection-merge", SelectionMergePass::apply,
                        OptimizationCode.SEL_002),
                PassRule.of("projection", ProjectionPass::apply,
                        OptimizationCode.PROJ_001, OptimizationCode.PROJ_002,
                        OptimizationCode.PROJ_003),
                PassRule.of("redundant-grouping", RedundantGroupingPass::apply,
                        OptimizationCode.AGG_001),
                PassRule.of("distinct-elimination", DistinctEliminationPass::apply,
                        OptimizationCode.DIST_001, OptimizationCode.DIST_002),
                PassRule.of("product-identity", ProductIdentityPass::apply,
                        OptimizationCode.PROD_001),
                // Alongside PROD-001, and for the same reason: it only ever *removes*
                // work, and nothing in this phase reintroduces a satisfiable filter or
                // a non-empty input, so it cannot oscillate against a phase-mate. It
                // must run after `simplify`, which is where PRED-004 produces the
                // constant-false predicate EMPTY-001 keys on.
                PassRule.of("empty-relation-propagation", EmptyRelationPropagationPass::apply,
                        OptimizationCode.EMPTY_001, OptimizationCode.EMPTY_002,
                        OptimizationCode.EMPTY_003),
                PassRule.of("sort-elimination",
                        (node, queryName, schemas, ctx) ->
                                SortEliminationPass.apply(node, queryName, ctx),
                        OptimizationCode.SORT_001)),
                DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Phase 4 — sideways information passing (ADR-0020/0021): fold a constraint above
     * an expensive operator into the operator itself.  After cleanup, so each rule
     * sees a settled σ-chain rather than whatever shape {@code SEL-001} left behind.
     */
    private static Phase sipPhase() {
        return new Phase("sip", List.of(
                PassRule.of("selection-into-closure", SelectionIntoClosurePass::apply,
                        OptimizationCode.CLOSURE_001),
                PassRule.of("selection-into-trace", SelectionIntoTracePass::apply,
                        OptimizationCode.TRACE_001),
                PassRule.of("selection-into-path", SelectionIntoPathPass::apply,
                        OptimizationCode.PATH_001),
                PassRule.of("selection-into-fixpoint", SelectionIntoFixpointPass::apply,
                        OptimizationCode.FIX_001),
                PassRule.of("selection-into-generator", SelectionIntoGeneratorPass::apply,
                        OptimizationCode.GEN_001)),
                DEFAULT_MAX_ITERATIONS);
    }

    /** Phase 5 — limit pushdown, including the {@code λ∘τ → TOP} fusion. */
    private static Phase limitPhase() {
        return new Phase("limit", List.of(
                PassRule.of("limit-pushdown", LimitPushdownPass::apply,
                        OptimizationCode.LIM_001, OptimizationCode.LIM_002,
                        OptimizationCode.LIM_003, OptimizationCode.LIM_004)),
                DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Phase 6 — column pruning, last and once.
     *
     * <p>Last because it inserts a π directly above a base relation, and every
     * pattern-matching phase above matches on shapes such a π would hide
     * ({@code GEN-001} most directly); once because it is a single top-down walk that
     * already computes the narrowest requirement each leaf can be given, so a second
     * sweep can only re-walk the tree it just settled.
     */
    private static Phase prunePhase() {
        return new Phase("prune", List.of(
                PassRule.of("column-pruning", ColumnPruningPass::apply,
                        OptimizationCode.PROJ_004)),
                SINGLE_PASS);
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns the phases, in application order.
     *
     * @return unmodifiable list; never null or empty
     */
    public List<Phase> phases() {
        return phases;
    }

    /**
     * Returns every rule in the pipeline, flattened in application order.
     *
     * @return unmodifiable list; never null or empty
     */
    public List<OptimizationRule> rules() {
        return phases.stream().flatMap(p -> p.rules().stream()).toList();
    }

    // =========================================================================
    // Driver
    // =========================================================================

    /**
     * Runs every phase over {@code node}, each iterated to a fixpoint within its
     * cap.
     *
     * @param node      the root of the tree to optimize; must not be null
     * @param queryName display name used in transformation records; must not be blank
     * @param schemas   schema annotations from semantic analysis; must not be null
     * @param ctx       context that accumulates transformation records; must not be null
     * @return the optimized tree; the original {@code node} when no rule fired
     */
    public RelNode run(RelNode node, String queryName, SchemaAnnotations schemas,
                       OptimizationContext ctx) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(queryName, "queryName");
        Objects.requireNonNull(schemas, "schemas");
        Objects.requireNonNull(ctx, "ctx");

        RelNode current = node;
        for (Phase phase : phases) {
            current = runPhase(phase, current, queryName, schemas, ctx);
        }
        return current;
    }

    /** Sweeps one phase's rules until they stop making progress, or the cap is hit. */
    private static RelNode runPhase(Phase phase, RelNode node, String queryName,
                                    SchemaAnnotations schemas, OptimizationContext ctx) {
        RelNode current = node;
        for (int sweep = 0; sweep < phase.maxIterations(); sweep++) {
            RelNode before = current;
            int recordsBefore = ctx.size();
            for (OptimizationRule rule : phase.rules()) {
                current = rule.apply(current, queryName, schemas, ctx);
            }
            boolean fired   = ctx.size() != recordsBefore;
            boolean changed = current != before;
            if (!fired || !changed) {
                break;      // fixpoint for this phase
            }
        }
        return current;
    }
}

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
import com.darkcollective.relix.cost.DistinctnessSource;
import com.darkcollective.relix.cost.MonotoneGeneratorSource;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.semantic.RelationDeterminism;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SchemaInference;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.table.SymbolTable;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Entry point for the Relix query optimizer.
 *
 * <p>The optimizer takes a {@link SemanticModel} produced by semantic analysis
 * and rewrites each query's {@link RelNode} tree to a semantically equivalent
 * but more efficient form.  Every transformation is logged in an
 * {@link OptimizationContext} and returned as an {@link OptimizationResult}
 * so the audit trail can be rendered by {@link OptimizationReport}.
 *
 * <h2>Optimization phases</h2>
 * <p>The rules, their order, and which groups are iterated are declared by
 * {@link OptimizationPipeline}, which this class builds once per query and runs.
 * The six phases run once each, in order, and the rules <em>within</em> a phase are
 * swept repeatedly until they stop making progress (capped at
 * {@link OptimizationPipeline#DEFAULT_MAX_ITERATIONS} sweeps):
 * <ol>
 *   <li><b>simplify</b> — expression then predicate simplification.</li>
 *   <li><b>pushdown</b> — selection splitting, the nest/unnest laws, selection
 *       pushdown, partition/group pruning, and the join rules.</li>
 *   <li><b>cleanup</b> — selection merging, the projection rules, and the
 *       redundant-γ/δ/τ eliminations.</li>
 *   <li><b>sip</b> — folding a constraint into {@code CLOSURE}/{@code TRACE}/
 *       {@code FIX}/a generator.</li>
 *   <li><b>limit</b> — limit pushdown and the top-N fusion.</li>
 *   <li><b>prune</b> — column pruning, once.</li>
 * </ol>
 *
 * <p>The split into phases is load-bearing, not cosmetic: {@code SEL-003}/{@code
 * PROJ-003} and {@code SEL-001}/{@code SEL-002} are mutual inverses, so iterating all
 * the rules together would never terminate.  See {@link OptimizationPipeline} for the
 * placement rule a new pass has to satisfy.
 *
 * <p>Three rewrites run <em>outside</em> the pipeline, in {@link #optimize(SemanticModel)}'s
 * per-query preamble, because each needs the model's {@link SymbolTable} and every rule
 * <em>in</em> the pipeline is a pure syntactic rewrite: {@link ViewInliner}
 * ({@code INLINE-001}) expands view references, {@link RenameEliminationPass}
 * ({@code RENAME-001}..{@code RENAME-004}) removes the alias wrappers inlining leaves
 * behind and tidies the column renames, and {@link LateralDecorrelationPass} ({@code LATERAL-001}) turns an uncorrelated
 * {@code LATERAL} into a {@code ×} — which needs the table to resolve the function and
 * classify its body as deterministic. All three run before schemas are re-inferred for the
 * expanded tree.
 *
 * <p>Rule by rule, in the order they run:
 * <ol>
 *   <li>Expression simplification — bottom-up constant folding, arithmetic
 *       identity elimination, constant accumulation, and idempotent function
 *       call elimination ({@code EXPR-001..008}).</li>
 *   <li>Predicate simplification — constant-branch folding and double-NOT
 *       elimination ({@code PRED-001..002}).</li>
 *   <li>Selection splitting — conjunctive predicates decomposed into stacked
 *       selections ({@code SEL-001}) to expose independent pushdown
 *       opportunities ({@link SelectionSplitPass}).</li>
 *   <li>Nest/unnest round-trip laws — collapse a {@code μ} over a {@code COLLECT}
 *       to a projection and push σ/π below {@code μ} ({@code NEST-001..003})
 *       ({@link NestUnnestPass}).</li>
 *   <li>Selection pushdown ({@code SEL-003..009})
 *       ({@link SelectionPushdownPass}).  The projection rules, including the
 *       inverse {@code PROJ-003}, are in the cleanup phase below.</li>
 *   <li>Partition pruning into {@code WINDOW}/{@code TopK} — push a
 *       partition-key equality below the operator so only the matching partition
 *       is computed ({@code WINDOW-001}, {@code TOPK-001})
 *       ({@link SelectionIntoWindowPass}).</li>
 *   <li>Group pruning into {@code OPTIMIZE} — push a {@code PER} grouping-key
 *       equality below the operator so the solver runs for only the matching
 *       group ({@code OPTIMIZE-001}) ({@link SelectionIntoOptimizePass}).</li>
 *   <li>Cross-product to theta-join conversion ({@code JOIN-001}).</li>
 *   <li>Selection into join inputs ({@code JOIN-002}, {@code SEL-005}).</li>
 *   <li>Selection and projection merging — cleanup pass
 *       ({@code SEL-002}, {@code PROJ-001..002}).</li>
 *   <li>Redundant-grouping elimination — collapse a γ stacked redundantly on
 *       another γ ({@code AGG-001}) ({@link RedundantGroupingPass}).</li>
 *   <li>Redundant-DISTINCT elimination — remove a δ whose input is already
 *       duplicate-free ({@code DIST-001}) ({@link DistinctEliminationPass}).</li>
 *   <li>Redundant-SORT elimination — remove a τ whose input already delivers a
 *       satisfying order ({@code SORT-001}) ({@link SortEliminationPass}).</li>
 *   <li>Selection-into-CLOSURE pushdown — fold a constant endpoint equality above
 *       a {@code CLOSURE} into a source/target bound, collapsing all-pairs
 *       reachability to single-source/single-pair ({@code CLOSURE-001})
 *       ({@link SelectionIntoClosurePass}).</li>
 *   <li>Selection-into-TRACE pushdown — fold a constant endpoint equality above a
 *       {@code TRACE} into a source/target bound, collapsing all-pairs optimal-path
 *       search to single-source/single-pair ({@code TRACE-001})
 *       ({@link SelectionIntoTracePass}).</li>
 *   <li>Magic-sets into {@code FIX} — push a selection over <em>frozen</em> columns
 *       into a {@code FIX} least-fixpoint so the recursion is seeded and restricted by
 *       the bound rather than computed in full ({@code FIX-001}) — the
 *       general case of which CLOSURE/TRACE pushdown are fixed-shape instances
 *       ({@link SelectionIntoFixpointPass}).</li>
 *   <li>Bound pushdown into a monotone generator — fold an upper bound above an
 *       unbounded ascending generator into a production stop, so the scan
 *       terminates ({@code GEN-001})
 *       ({@link SelectionIntoGeneratorPass}).</li>
 *   <li>Limit pushdown ({@code LIM-001..004}).</li>
 *   <li>Column pruning — a top-down "required columns" walk that narrows every
 *       base relation to the columns the query actually reads ({@code PROJ-004})
 *       ({@link ColumnPruningPass}).  Runs last so it cannot hide the shapes the
 *       pattern-matching phases above match on.</li>
 * </ol>
 *
 * <p>This class is stateless and safe to reuse across multiple optimization
 * runs.
 */
public final class QueryOptimizer {

    /**
     * Constructs a {@code QueryOptimizer} with the default full rule set.
     */
    public QueryOptimizer() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Optimizes a single {@link RelNode} tree, recording all transformations
     * in the supplied context.
     *
     * <p>Every rule the optimizer ships is a pure syntactic rewrite, so this needs
     * neither a symbol table nor a cost model.  Cost-driven join decisions are the
     * planner's, where they can be made without permuting the logical schema.
     *
     * <p>The rules that ask what a <em>function</em> may do — whether a call folds, or
     * an aggregate ignores multiplicity — read the
     * {@link com.darkcollective.relix.function.FunctionCatalog} off {@code ctx}.
     * {@link #optimize(SemanticModel)} puts the model's own catalogue there; a caller
     * entering here directly supplies one when it wants those rules to fire, and gets a
     * slower plan rather than a wrong one when it does not.
     *
     * @param node      the root of the query tree to optimize; must not be null
     * @param queryName display name used in transformation records; must not
     *                  be blank
     * @param schemas   schema annotations from semantic analysis; must not be null
     * @param ctx       context that accumulates transformation records;
     *                  must not be null
     * @return the optimized tree; the original {@code node} when no rules fired
     */
    public RelNode optimize(RelNode             node,
                            String              queryName,
                            SchemaAnnotations   schemas,
                            OptimizationContext ctx) {
        Objects.requireNonNull(node,      "node");
        Objects.requireNonNull(queryName, "queryName");
        Objects.requireNonNull(schemas,   "schemas");
        Objects.requireNonNull(ctx,       "ctx");
        // ── WHY hard barrier (ADR-0018) ──────────────────────────────────────
        // Freeze each WHY subtree behind an opaque placeholder so no pass rewrites
        // inside or across it, run the rule passes, then restore the subtrees
        // verbatim. A WHY-free tree is shielded reference-unchanged (zero cost).
        WhyBarrier.Shielded shielded = WhyBarrier.shield(node);
        RelNode optimized = OptimizationPipeline.defaultPipeline()
                .run(shielded.tree(), queryName, schemas, ctx);
        return WhyBarrier.restore(optimized, shielded.frozen());
    }

    /**
     * Optimizes all root queries in the given {@link SemanticModel}.
     *
     * <p>Each root query statement is optimized in the order it appears in
     * {@link SemanticModel#rootQueries()}.  For a {@link NamedQueryTarget} the
     * symbol is looked up in the model's symbol table:
     * <ul>
     *   <li>If the symbol is a {@link QueryRelationSymbol} (a named view), its
     *       {@link QueryRelationSymbol#body()} is optimized using all phases and
     *       the result is captured in an {@link OptimizationResult}.</li>
     *   <li>If the symbol is not found or is not a {@code QueryRelationSymbol}
     *       (e.g. a source table), a trivial pass-through result is returned
     *       with a {@link RelationNode} leaf as both original and optimized.</li>
     * </ul>
     * <p>For an {@link ExpressionQueryTarget} (an inline {@code query { … }} statement)
     * the expression is optimized directly and assigned the generated name
     * {@code "query[n]"} where {@code n} is the 1-based ordinal of inline queries
     * encountered so far.
     *
     * <p>Schema annotations carried in {@link SemanticModel#nodeSchemas()} are
     * forwarded to each optimization pass so that schema-dependent rules (e.g.
     * PROJ-001) can operate correctly.
     *
     * @param model the semantic model to optimize; must not be null
     * @return unmodifiable list of per-query results, in root-query order;
     *         never null; empty when the model has no root queries
     */
    public List<OptimizationResult> optimize(SemanticModel model) {
        return optimize(model, QueryEventListener.NONE);
    }

    /**
     * Optimizes all root queries in {@code model}, emitting a
     * {@link com.darkcollective.relix.events.QueryEvent} to {@code listener} for
     * every rule that fires (in addition to recording it
     * in the returned results).
     *
     * @param model    the semantic model to optimize; must not be null
     * @param listener notified on each rule firing; must not be null
     *                 (use {@link QueryEventListener#NONE} for no observation)
     * @return unmodifiable list of per-query results, in root-query order
     */
    public List<OptimizationResult> optimize(SemanticModel model, QueryEventListener listener) {
        return optimize(model, listener, DistinctnessSource.NONE);
    }

    /**
     * As {@link #optimize(SemanticModel, QueryEventListener)}, plus a per-leaf
     * {@link DistinctnessSource} so {@code DIST-001} removes {@code δ} over
     * an inherently-distinct leaf (e.g. a duplicate-free generator). The CLI supplies a
     * generator-backed source; {@link DistinctnessSource#NONE} disables the leaf rule.
     *
     * @param model        the semantic model to optimize; must not be null
     * @param listener     notified on each rule firing; must not be null
     * @param distinctness the per-leaf duplicate-free lookup; must not be null
     * @return unmodifiable list of per-query results, in root-query order
     */
    public List<OptimizationResult> optimize(SemanticModel model, QueryEventListener listener,
                                             DistinctnessSource distinctness) {
        return optimize(model, listener, distinctness, MonotoneGeneratorSource.NONE);
    }

    /**
     * As {@link #optimize(SemanticModel, QueryEventListener, DistinctnessSource)}, plus a
     * per-leaf {@link MonotoneGeneratorSource} so {@code GEN-001} folds an
     * upper-bound {@code σ} over a monotone unbounded generator into a production stop. The
     * runtime supplies a generator-backed source; {@link MonotoneGeneratorSource#NONE}
     * disables the rule.
     *
     * @param model              the semantic model to optimize; must not be null
     * @param listener           notified on each rule firing; must not be null
     * @param distinctness       the per-leaf duplicate-free lookup; must not be null
     * @param monotoneGenerators the per-leaf ascending-generator lookup; must not be null
     * @return unmodifiable list of per-query results, in root-query order
     */
    public List<OptimizationResult> optimize(SemanticModel model, QueryEventListener listener,
                                             DistinctnessSource distinctness,
                                             MonotoneGeneratorSource monotoneGenerators) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(distinctness, "distinctness");
        Objects.requireNonNull(monotoneGenerators, "monotoneGenerators");

        List<QueryStatement> rootQueries = model.rootQueries();
        if (rootQueries.isEmpty()) {
            return List.of();
        }

        SymbolTable symTable = model.symbolTable();
        var results = new ArrayList<OptimizationResult>(rootQueries.size());
        int inlineCount = 0;

        for (QueryStatement stmt : rootQueries) {
            switch (stmt.target()) {

                case NamedQueryTarget nqt -> {
                    String name = nqt.name();
                    var sym = symTable.lookupRelation(name);
                    if (sym.isPresent() && sym.get() instanceof QueryRelationSymbol qr) {
                        var ctx = new OptimizationContext(listener, distinctness, monotoneGenerators,
                                model.functions(), determinism(model));
                        RelNode original  = qr.body();
                        RelNode optimized = inlineThenOptimize(original, name, ctx, model);
                        results.add(new OptimizationResult(name, original, optimized,
                                ctx.records()));
                    } else {
                        // Source table, database table, or unknown symbol — no RA body to
                        // optimise; return a trivial leaf result.
                        var leaf = new RelationNode(name);
                        results.add(new OptimizationResult(name, leaf, leaf, List.of()));
                    }
                }

                case ExpressionQueryTarget eqt -> {
                    inlineCount++;
                    String name = "query[" + inlineCount + "]";
                    var ctx = new OptimizationContext(listener, distinctness, monotoneGenerators,
                                model.functions(), determinism(model));
                    RelNode original  = eqt.expression();
                    RelNode optimized = inlineThenOptimize(original, name, ctx, model);
                    results.add(new OptimizationResult(name, original, optimized,
                            ctx.records()));
                }
            }
        }

        return Collections.unmodifiableList(results);
    }

    /**
     * The reproducibility lookup the pipeline's rules consult, bound to this model's
     * symbol table.
     *
     * <p>{@code SEL-010} and the {@code SET} family collapse two evaluations of a
     * sub-expression into one, which is answer-preserving exactly when that expression is
     * reproducible. The question is {@link RelationDeterminism}'s and needs the symbol
     * table to see through a view or a table-valued function — which is why it reaches a
     * rule as a lookup on the context rather than as a parameter: no rule in
     * {@link OptimizationPipeline} takes a {@link SymbolTable}, and this keeps it that
     * way.
     */
    private static DeterminismSource determinism(SemanticModel model) {
        return expression -> RelationDeterminism.isDeterministic(
                expression, model.symbolTable(), model.functions());
    }

    /**
     * Inlines view references in {@code original} (recording each as
     * {@link OptimizationCode#INLINE_001}), removes the alias wrappers that inlining
     * leaves behind ({@code RENAME-001}/{@code RENAME-002}), re-infers schemas for the
     * expanded tree, then runs the rule passes — so rules can optimise across the
     * former view boundaries.
     *
     * <p>Rename elimination runs here rather than as a
     * {@link OptimizationPipeline} rule, and specifically <em>before</em> the schema
     * re-inference: {@link SchemaAnnotations} is identity-keyed, so removing a node
     * drops the annotation of every node above it.  Cleaning up first means the rule
     * passes still see a fully annotated tree.
     */
    private RelNode inlineThenOptimize(RelNode original, String name,
                                       OptimizationContext ctx, SemanticModel model) {
        SymbolTable symTable = model.symbolTable();
        // ── WHY hard barrier (ADR-0018) ──────────────────────────────────────
        // The preamble passes rewrite the tree exactly as the pipeline rules do, so
        // they are shielded for the same reason. ViewInliner is the one that bites:
        // replacing a view reference under a WHY with ρ(body) costs the provenance
        // evaluator the view branch that threads lineage into the body, so a join's
        // two contributing tuples collapse to one opaque ρ variable (#855).
        // The subtrees are restored *before* schemas are re-inferred, so annotation
        // still sees the real tree and the WHY keeps the schema the planner reads.
        WhyBarrier.Shielded shielded = WhyBarrier.shield(original);
        RelNode inlined = ViewInliner.inline(shielded.tree(), symTable, ctx, name);
        RelNode cleaned = RenameEliminationPass.apply(inlined, name, ctx);
        RelNode flattened = LateralDecorrelationPass.apply(cleaned, symTable, ctx, name);
        RelNode restored = WhyBarrier.restore(flattened, shielded.frozen());
        // Re-infer schemas for the expanded tree, seeded from the model's existing
        // annotations so any node carried over unchanged keeps its known schema.
        SchemaAnnotations inlinedSchemas =
                SchemaInference.annotate(symTable, restored, model.nodeSchemas(), model.functions());
        return optimize(restored, name, inlinedSchemas, ctx);
    }
}

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

import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.Predicates;
import com.darkcollective.relix.ast.Qualifiers;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.RightOuterJoinNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Optimization pass that moves selections ({@code σ}) closer to their source
 * relations, reducing the number of tuples flowing through intermediate
 * operators ({@code SEL-003..009}).
 *
 * <p>Rules applied (top-down per selection node):
 * <ul>
 *   <li><b>SEL-003</b> — Push selection below projection when every column
 *       referenced by the predicate is present in the projection's input and
 *       not introduced as a computed alias by the projection.</li>
 *   <li><b>SEL-004</b> — Push selection below rename, rewriting attribute
 *       references from the renamed column names to the original column names.
 *       Only applies when the rename provides a complete column-name mapping
 *       or no column renaming at all.</li>
 *   <li><b>SEL-005</b> — Push a selection whose predicate references only one
 *       side of an inner join into that join's input.  Applies to
 *       {@link NaturalJoinNode} and {@link ThetaJoinNode} (either side);
 *       {@link LeftOuterJoinNode} and {@link SemiJoinNode} and
 *       {@link AntiJoinNode} (left side only);
 *       {@link RightOuterJoinNode} (right side only).
 *       Schema annotations for both join inputs are required.</li>
 *   <li><b>SEL-006</b> — Replicate a selection into both branches of a
 *       {@link UnionAllNode} so rows are filtered as early as possible.</li>
 *   <li><b>SEL-007</b> — Demote a {@code HAVING}-position selection to a
 *       {@code WHERE}-position one: push the conjuncts that reference only
 *       bare-column grouping keys of an {@link AggregationNode} below it, leaving
 *       anything that touches an aggregate output as a residual σ above.</li>
 *   <li><b>SEL-008</b> — Push a selection below a {@link DistinctNode} or a
 *       {@link SortNode}.  Unconditional: both are row- and column-preserving with
 *       respect to a filter, and both are <em>blocking</em>, so a σ left above one
 *       deduplicates or sorts rows that are about to be discarded.</li>
 *   <li><b>SEL-009</b> — Distribute a selection over a set operation: replicated
 *       into both branches of {@link UnionNode}, {@link IntersectionNode},
 *       {@link SymmetricDifferenceNode} and {@link DifferenceNode} — the subtrahend
 *       included, because the same predicate goes into the minuend, so a row the
 *       filtered subtrahend stops removing is one the filtered minuend no longer
 *       offers.  {@link com.darkcollective.relix.ast.OuterUnionNode}
 *       is deliberately excluded — its branches have different schemas, so a
 *       predicate valid against one may reference a column absent from the other —
 *       as are {@code ÷} and {@code ∘}, neither of which is a row filter with
 *       respect to σ.</li>
 * </ul>
 *
 * <p>The traversal is <em>top-down</em> for selection nodes (try to push first,
 * then recurse into the result) and <em>bottom-up</em> for all other nodes.
 * This allows a single pass to push a selection through multiple levels in one
 * invocation, while still processing nested trees.
 *
 * <p>A σ whose input is another σ <em>looks through</em> the chain: it retries
 * against the chain's input and restores the skipped selection above the result.
 * Without that, a pushable conjunct would be stranded whenever
 * {@link SelectionSplitPass} happened to place a non-pushable one beneath it —
 * {@code σ dept = "HR" (σ SUM(pay) &gt; 10 (γ dept, SUM(pay) (Staff)))} would never
 * reach the γ. Conjunction commutes, so swapping the two is sound.
 *
 * <p>This pass should run <em>after</em> {@link SelectionSplitPass} so that
 * individual conjuncts are moveable independently.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class SelectionPushdownPass {

    private SelectionPushdownPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies selection pushdown (SEL-003..009) to the entire tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations used to determine which columns
     *                  belong to each join input; may be empty (pushdowns
     *                  that require schema information are skipped when
     *                  the relevant annotation is absent)
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, schemas, ctx);
    }

    // =========================================================================
    // RelNode traversal  (top-down for σ, bottom-up for everything else)
    // =========================================================================

    private static RelNode rewriteNode(RelNode node, String queryName,
                                        SchemaAnnotations schemas,
                                        OptimizationContext ctx) {
        return switch (node) {

            // ── σ: try to push first (top-down), then recurse on result ──────
            case SelectionNode s -> {
                RelNode pushed = tryPush(s, queryName, schemas, ctx);
                if (pushed != null) {
                    // Successful push — the pushed structure was already recursed
                    // inside tryPush, so return it directly.
                    yield pushed;
                }
                // No push — recurse normally into the input
                RelNode newInput = rewriteNode(s.input(), queryName, schemas, ctx);
                yield (newInput != s.input())
                        ? new SelectionNode(s.predicate(), newInput, s.location()) : s;
            }

            // ── Bottom-up traversal for all other nodes ──────────────────────
            default -> node.mapChildren(child -> rewriteNode(child, queryName, schemas, ctx));
        };
    }

    // =========================================================================
    // Push dispatcher
    // =========================================================================

    /**
     * Attempts to push {@code s} one level deeper based on the type of its
     * input.  If a push is possible, the new tree is returned (with the newly
     * created inner selection recursed via {@link #rewriteNode}).  Returns
     * {@code null} when no applicable rule fires.
     */
    private static RelNode tryPush(SelectionNode s, String queryName,
                                    SchemaAnnotations schemas,
                                    OptimizationContext ctx) {
        Predicate pred   = s.predicate();
        Set<String> attrs = PredicateAttributeCollector.collectNames(pred);

        return switch (s.input()) {

            // ── SEL-003: push below projection ───────────────────────────────
            case ProjectionNode p when canPushBelowProjection(attrs, p, schemas) -> {
                ctx.record(OptimizationCode.SEL_003, queryName,
                        "selection pushed below projection",
                        s.location());
                RelNode newInner = rewriteNode(
                        new SelectionNode(pred, p.input(), s.location()),
                        queryName, schemas, ctx);
                yield new ProjectionNode(p.attributes(), newInner, p.location());
            }

            // ── SEL-004: push below rename ────────────────────────────────────
            case RenameNode r -> {
                Optional<Predicate> rewritten = tryRewriteForRename(pred, attrs, r, schemas);
                if (rewritten.isEmpty()) yield null;
                ctx.record(OptimizationCode.SEL_004, queryName,
                        "selection pushed below rename (attribute references rewritten)",
                        s.location());
                RelNode newInner = rewriteNode(
                        new SelectionNode(rewritten.get(), r.input(), s.location()),
                        queryName, schemas, ctx);
                yield r.withInput(newInner);
            }

            // ── SEL-005: push into natural join (either side) ─────────────────
            case NaturalJoinNode j -> {
                Optional<Schema> ls = schemas.get(j.left());
                Optional<Schema> rs = schemas.get(j.right());
                if (ls.isEmpty() || rs.isEmpty()) yield null;
                JoinSide side = determineJoinSide(attrs, j.left(), ls.get(), j.right(), rs.get());
                yield switch (side) {
                    case LEFT -> {
                        ctx.record(OptimizationCode.SEL_005, queryName,
                                "selection pushed into left input of natural join", s.location());
                        RelNode newLeft = rewriteNode(
                                new SelectionNode(pred, j.left(), s.location()),
                                queryName, schemas, ctx);
                        RelNode newRight = rewriteNode(j.right(), queryName, schemas, ctx);
                        yield new NaturalJoinNode(newLeft, newRight, j.location());
                    }
                    case RIGHT -> {
                        ctx.record(OptimizationCode.SEL_005, queryName,
                                "selection pushed into right input of natural join", s.location());
                        RelNode newLeft = rewriteNode(j.left(), queryName, schemas, ctx);
                        RelNode newRight = rewriteNode(
                                new SelectionNode(pred, j.right(), s.location()),
                                queryName, schemas, ctx);
                        yield new NaturalJoinNode(newLeft, newRight, j.location());
                    }
                    case NEITHER -> null;
                };
            }

            // ── SEL-005: push into theta join (either side) ──────────────────
            case ThetaJoinNode j -> {
                Optional<Schema> ls = schemas.get(j.left());
                Optional<Schema> rs = schemas.get(j.right());
                if (ls.isEmpty() || rs.isEmpty()) yield null;
                JoinSide side = determineJoinSide(attrs, j.left(), ls.get(), j.right(), rs.get());
                yield switch (side) {
                    case LEFT -> {
                        ctx.record(OptimizationCode.SEL_005, queryName,
                                "selection pushed into left input of theta join", s.location());
                        RelNode newLeft = rewriteNode(
                                new SelectionNode(pred, j.left(), s.location()),
                                queryName, schemas, ctx);
                        RelNode newRight = rewriteNode(j.right(), queryName, schemas, ctx);
                        yield new ThetaJoinNode(newLeft, newRight, j.condition(), j.location());
                    }
                    case RIGHT -> {
                        ctx.record(OptimizationCode.SEL_005, queryName,
                                "selection pushed into right input of theta join", s.location());
                        RelNode newLeft = rewriteNode(j.left(), queryName, schemas, ctx);
                        RelNode newRight = rewriteNode(
                                new SelectionNode(pred, j.right(), s.location()),
                                queryName, schemas, ctx);
                        yield new ThetaJoinNode(newLeft, newRight, j.condition(), j.location());
                    }
                    case NEITHER -> null;
                };
            }

            // ── SEL-005: push into left outer join — left side only ───────────
            case LeftOuterJoinNode j -> {
                Optional<Schema> ls = schemas.get(j.left());
                if (ls.isEmpty()) yield null;
                boolean leftOnly = attrs.stream().allMatch(a -> hasColumn(ls.get(), a));
                if (!leftOnly) yield null;
                ctx.record(OptimizationCode.SEL_005, queryName,
                        "selection pushed into left input of left outer join", s.location());
                RelNode newLeft = rewriteNode(
                        new SelectionNode(pred, j.left(), s.location()),
                        queryName, schemas, ctx);
                RelNode newRight = rewriteNode(j.right(), queryName, schemas, ctx);
                yield new LeftOuterJoinNode(newLeft, newRight, j.condition(), j.location());
            }

            // ── SEL-005: push into right outer join — right side only ─────────
            case RightOuterJoinNode j -> {
                Optional<Schema> rs = schemas.get(j.right());
                if (rs.isEmpty()) yield null;
                boolean rightOnly = attrs.stream().allMatch(a -> hasColumn(rs.get(), a));
                if (!rightOnly) yield null;
                ctx.record(OptimizationCode.SEL_005, queryName,
                        "selection pushed into right input of right outer join", s.location());
                RelNode newLeft = rewriteNode(j.left(), queryName, schemas, ctx);
                RelNode newRight = rewriteNode(
                        new SelectionNode(pred, j.right(), s.location()),
                        queryName, schemas, ctx);
                yield new RightOuterJoinNode(newLeft, newRight, j.condition(), j.location());
            }

            // ── SEL-005: push into semi-join — left side only ─────────────────
            case SemiJoinNode j -> {
                Optional<Schema> ls = schemas.get(j.left());
                if (ls.isEmpty()) yield null;
                boolean leftOnly = attrs.stream().allMatch(a -> hasColumn(ls.get(), a));
                if (!leftOnly) yield null;
                ctx.record(OptimizationCode.SEL_005, queryName,
                        "selection pushed into left input of semi-join", s.location());
                RelNode newLeft = rewriteNode(
                        new SelectionNode(pred, j.left(), s.location()),
                        queryName, schemas, ctx);
                RelNode newRight = rewriteNode(j.right(), queryName, schemas, ctx);
                yield new SemiJoinNode(newLeft, newRight, j.condition(), j.location());
            }

            // ── SEL-005: push into anti-join — left side only ─────────────────
            case AntiJoinNode j -> {
                Optional<Schema> ls = schemas.get(j.left());
                if (ls.isEmpty()) yield null;
                boolean leftOnly = attrs.stream().allMatch(a -> hasColumn(ls.get(), a));
                if (!leftOnly) yield null;
                ctx.record(OptimizationCode.SEL_005, queryName,
                        "selection pushed into left input of anti-join", s.location());
                RelNode newLeft = rewriteNode(
                        new SelectionNode(pred, j.left(), s.location()),
                        queryName, schemas, ctx);
                RelNode newRight = rewriteNode(j.right(), queryName, schemas, ctx);
                yield new AntiJoinNode(newLeft, newRight, j.condition(), j.location());
            }

            // ── SEL-005: push into universal semi-join — left side only ──────
            case PairwiseUniversalNode j -> {
                Optional<Schema> ls = schemas.get(j.left());
                if (ls.isEmpty()) yield null;
                boolean leftOnly = attrs.stream().allMatch(a -> hasColumn(ls.get(), a));
                if (!leftOnly) yield null;
                ctx.record(OptimizationCode.SEL_005, queryName,
                        "selection pushed into left input of universal semi-join", s.location());
                RelNode newLeft = rewriteNode(
                        new SelectionNode(pred, j.left(), s.location()),
                        queryName, schemas, ctx);
                RelNode newRight = rewriteNode(j.right(), queryName, schemas, ctx);
                yield new PairwiseUniversalNode(newLeft, newRight, j.condition(), j.location());
            }

            // ── SEL-006: push into union-all (both branches) ──────────────────
            case UnionAllNode u -> {
                ctx.record(OptimizationCode.SEL_006, queryName,
                        "selection replicated into both branches of union-all",
                        s.location());
                RelNode newLeft = rewriteNode(
                        new SelectionNode(pred, u.left(), s.location()),
                        queryName, schemas, ctx);
                RelNode newRight = rewriteNode(
                        new SelectionNode(pred, u.right(), s.location()),
                        queryName, schemas, ctx);
                yield new UnionAllNode(newLeft, newRight, u.location());
            }

            // ── SEL-007: push below aggregation (HAVING → WHERE) ──────────────
            case AggregationNode a -> tryPushBelowAggregation(s, a, queryName, schemas, ctx);

            // ── SEL-008: push below δ / τ ─────────────────────────────────────
            // Both are row- and column-preserving with respect to a filter, so the
            // push is unconditional — and worth doing, since both are blocking.
            case DistinctNode d -> {
                ctx.record(OptimizationCode.SEL_008, queryName,
                        "selection pushed below distinct", s.location());
                yield new DistinctNode(pushInto(pred, d.input(), s, queryName, schemas, ctx),
                        d.location());
            }
            case SortNode t -> {
                ctx.record(OptimizationCode.SEL_008, queryName,
                        "selection pushed below sort", s.location());
                yield new SortNode(t.sortSpecs(),
                        pushInto(pred, t.input(), s, queryName, schemas, ctx), t.location());
            }

            // ── SEL-009: distribute over the set operations ───────────────────
            // σ distributes into both branches of ∪ / ∩ / ∆ / −.  The subtrahend looks
            // like the exception and is not: the SAME predicate goes into the minuend,
            // so a row the filtered subtrahend stops removing is a row the filtered
            // minuend no longer offers.
            //
            //   σp(A − B)  = { r : r ∈ A, r ∉ B, p(r) }
            //   σpA − σpB  = { r : r ∈ A, p(r), ¬(r ∈ B ∧ p(r)) }
            //              = { r : r ∈ A, p(r), r ∉ B }        -- p(r) holds
            //
            // Three-valued logic needs no separate argument: where p(r) is UNKNOWN the
            // row is dropped by the σ on one form and absent from σpA on the other.
            // Pushing into the subtrahend *alone* is the unsound thing, and that is not
            // what distribution does.
            case UnionNode u -> {
                ctx.record(OptimizationCode.SEL_009, queryName,
                        "selection replicated into both branches of union", s.location());
                yield new UnionNode(pushInto(pred, u.left(), s, queryName, schemas, ctx),
                        pushInto(pred, u.right(), s, queryName, schemas, ctx), u.location());
            }
            case IntersectionNode i -> {
                ctx.record(OptimizationCode.SEL_009, queryName,
                        "selection replicated into both branches of intersection", s.location());
                yield new IntersectionNode(pushInto(pred, i.left(), s, queryName, schemas, ctx),
                        pushInto(pred, i.right(), s, queryName, schemas, ctx), i.location());
            }
            case SymmetricDifferenceNode d -> {
                ctx.record(OptimizationCode.SEL_009, queryName,
                        "selection replicated into both branches of symmetric difference",
                        s.location());
                yield new SymmetricDifferenceNode(
                        pushInto(pred, d.left(), s, queryName, schemas, ctx),
                        pushInto(pred, d.right(), s, queryName, schemas, ctx), d.location());
            }
            case DifferenceNode d -> {
                ctx.record(OptimizationCode.SEL_009, queryName,
                        "selection replicated into both branches of difference", s.location());
                yield new DifferenceNode(pushInto(pred, d.left(), s, queryName, schemas, ctx),
                        pushInto(pred, d.right(), s, queryName, schemas, ctx), d.location());
            }

            // ── Look through a σ-chain ────────────────────────────────────────
            // SelectionSplitPass turns `σ a ∧ b (X)` into `σ a (σ b (X))`, so only the
            // innermost σ ever sees X directly and a pushable conjunct can be stranded
            // above a non-pushable one.  Conjunction commutes, so try this selection
            // against the chain's input and put the skipped one back on top.
            case SelectionNode inner -> {
                RelNode pushed = tryPush(
                        new SelectionNode(pred, inner.input(), s.location()),
                        queryName, schemas, ctx);
                yield (pushed == null) ? null : rewriteNode(
                        new SelectionNode(inner.predicate(), pushed, inner.location()),
                        queryName, schemas, ctx);
            }

            // ── No applicable rule ────────────────────────────────────────────
            default -> null;
        };
    }

    /**
     * Wraps {@code input} in a copy of the selection and recurses, so the freshly
     * placed σ keeps travelling downwards in the same pass.  Used by every
     * unconditional single- and two-branch push (SEL-008 / SEL-009).
     */
    private static RelNode pushInto(Predicate pred, RelNode input, SelectionNode s,
                                    String queryName, SchemaAnnotations schemas,
                                    OptimizationContext ctx) {
        return rewriteNode(new SelectionNode(pred, input, s.location()), queryName, schemas, ctx);
    }

    // =========================================================================
    // SEL-007 — HAVING → WHERE
    // =========================================================================

    /**
     * Splits {@code s}'s predicate into the conjuncts that can run <em>before</em>
     * {@code a} and those that cannot, and on a non-empty pushable set rebuilds the
     * aggregation with the pushed selection on its input:
     *
     * <pre>{@code
     *   σ p (γ keys, aggs (R))  →  γ keys, aggs (σ p (R))     when attrs(p) ⊆ keys
     * }</pre>
     *
     * <p>A conjunct is pushable only when every attribute it references is a
     * <em>bare, unaliased</em> grouping key ({@link GroupingKey#isPlainColumn()}), so
     * the column keeps the same name below the γ and needs no rewriting. That
     * deliberately excludes:
     * <ul>
     *   <li>a conjunct on an aggregate output ({@code SUM(amount) &gt; 100}) — it is
     *       not computable before the grouping and stays above as a residual σ;</li>
     *   <li>a derived key ({@code YEAR(order_date) → yr}) and an aliased key
     *       ({@code customer_id → cust}) — the output name has no matching input
     *       column, so there is nothing to push against;</li>
     *   <li>a constant conjunct (no attributes at all) — nothing to gain, and the
     *       vacuous {@code allMatch} would otherwise let it through.</li>
     * </ul>
     *
     * <p>An <em>ungrouped</em> aggregation ({@code groupingKeys()} is null, or the
     * empty scalar grouping) has no key to filter on, so the rule is a no-op there —
     * and must be, since a scalar γ emits one row even over an empty input.
     *
     * @return the rewritten tree, or {@code null} when no conjunct is pushable
     */
    private static RelNode tryPushBelowAggregation(SelectionNode s, AggregationNode a,
                                                   String queryName, SchemaAnnotations schemas,
                                                   OptimizationContext ctx) {
        List<GroupingKey> keys = a.groupingKeys();
        // AggregationNode is the one node whose list field may be null (ungrouped).
        if (keys == null || keys.isEmpty()) return null;

        Set<String> pushableColumns = keys.stream()
                .filter(GroupingKey::isPlainColumn)
                .map(k -> k.outputName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (pushableColumns.isEmpty()) return null;

        List<Predicate> pushable = new ArrayList<>();
        List<Predicate> residual = new ArrayList<>();
        for (Predicate c : Predicates.conjuncts(s.predicate())) {
            Set<String> attrs = PredicateAttributeCollector.collectNames(c);
            boolean overKeysOnly = !attrs.isEmpty() && attrs.stream()
                    .allMatch(n -> pushableColumns.contains(
                            PredicateAttributeCollector.columnPart(n).toLowerCase(Locale.ROOT)));
            (overKeysOnly ? pushable : residual).add(c);
        }
        if (pushable.isEmpty()) return null;

        ctx.record(OptimizationCode.SEL_007, queryName,
                "selection on grouping key(s) pushed below aggregation (HAVING → WHERE)",
                s.location());

        RelNode newInner = rewriteNode(
                new SelectionNode(Predicates.conjoin(pushable), a.input(), s.location()),
                queryName, schemas, ctx);
        RelNode newAgg = new AggregationNode(a.groupingKeys(), a.aggregates(), newInner, a.location());
        return residual.isEmpty()
                ? newAgg
                : new SelectionNode(Predicates.conjoin(residual), newAgg, s.location());
    }

    // =========================================================================
    // Condition checks
    // =========================================================================

    /**
     * Returns {@code true} when {@code σ(pred)(π(attrs)(R))} can be safely
     * rewritten to {@code π(attrs)(σ(pred)(R))}.
     *
     * <p>Conditions:
     * <ol>
     *   <li>The schema of {@code p.input()} is available in {@code schemas}.</li>
     *   <li>Every attribute in {@code predAttrs} appears as a column in
     *       {@code p}'s input schema.</li>
     *   <li>No projection attribute uses the same name as a predicate attribute
     *       via an alias that hides a differently-named expression (e.g.
     *       {@code price * 1.1 → price} would make the alias "price" refer to a
     *       computed value, not the stored column).</li>
     * </ol>
     */
    private static boolean canPushBelowProjection(Set<String> predAttrs,
                                                   ProjectionNode p,
                                                   SchemaAnnotations schemas) {
        Optional<Schema> inputSchemaOpt = schemas.get(p.input());
        if (inputSchemaOpt.isEmpty()) return false;
        Schema inputSchema = inputSchemaOpt.get();

        // All predicate columns must exist in the projection's input
        if (!predAttrs.stream().allMatch(a -> hasColumn(inputSchema, a))) return false;

        // No alias should shadow a predicate column with a computed expression
        for (ProjectedAttribute pa : p.attributes()) {
            if (pa.alias().isPresent()) {
                String alias = pa.alias().get();
                boolean predRefsAlias = predAttrs.stream()
                        .anyMatch(a -> alias.equalsIgnoreCase(
                                PredicateAttributeCollector.columnPart(a)));
                if (predRefsAlias) {
                    // The alias appears in the predicate — safe only if the
                    // projection expression is a direct passthrough of the same name
                    boolean passthrough = (pa.expression() instanceof AttributeOperand ao)
                            && ao.name().equalsIgnoreCase(alias);
                    if (!passthrough) return false;
                }
            }
        }
        return true;
    }

    /**
     * Attempts to produce a rewritten predicate suitable for pushing below
     * {@code r}.
     *
     * <p>When {@code r.attributes()} is empty (relation-only rename), the
     * predicate is returned unchanged, provided all predicate columns exist in
     * {@code r}'s input schema.
     *
     * <p>When {@code r.attributes()} is non-empty (full column rename), a
     * positional mapping from new name → old name is built from
     * {@code r.attributes()} and the input schema, and every
     * {@link AttributeOperand} in the predicate is rewritten accordingly.
     *
     * @return the rewritten predicate, or {@link Optional#empty()} if the push
     *         is not safe (e.g. the input schema is unavailable, or a predicate
     *         attribute does not appear in the mapping)
     */
    private static Optional<Predicate> tryRewriteForRename(Predicate pred,
                                                             Set<String> predAttrs,
                                                             RenameNode r,
                                                             SchemaAnnotations schemas) {
        Optional<Schema> inputSchemaOpt = schemas.get(r.input());
        if (inputSchemaOpt.isEmpty()) return Optional.empty();
        Schema inputSchema = inputSchemaOpt.get();

        if (!r.renamesColumns()) {
            // Relation-only rename: column names unchanged, just verify they exist. The
            // rename's own qualifier is dropped on the way down — beneath `ρ J` nothing
            // answers to `J`. A declared row forgave the stale qualifier by falling back
            // to the bare name; a schema-on-read row reads it as a path and finds
            // nothing, so a σ over a view of an open join matched no rows (#971).
            if (!predAttrs.stream().allMatch(a -> hasColumn(inputSchema, a)))
                return Optional.empty();
            String relation = r.relationName().orElseThrow();
            return Optional.of(rewriteAttrNames(pred, name -> withoutQualifier(name, relation)));
        }

        // Column rename: build output(new) → input(old) name mapping.
        Map<String, String> mapping = renameNameMapping(r, inputSchema);
        if (mapping == null) return Optional.empty(); // positional arity mismatch

        // All predicate columns (column part, lowercased) must be in the mapping
        boolean allMapped = predAttrs.stream().allMatch(a -> {
            String col = PredicateAttributeCollector.columnPart(a).toLowerCase(Locale.ROOT);
            return mapping.containsKey(col);
        });
        if (!allMapped) return Optional.empty();

        return Optional.of(rewriteAttrNames(pred, name -> {
            String mapped = mapping.get(
                    PredicateAttributeCollector.columnPart(name).toLowerCase(Locale.ROOT));
            return mapped != null ? mapped : name;
        }));
    }

    /**
     * {@code name} as it reads beneath {@code ρ relation}: a leading {@code relation.}
     * is dropped, since the rename's own name is in scope only above it and a
     * relation-only rename keeps every column's name. Anything else is unchanged.
     */
    private static String withoutQualifier(String name, String relation) {
        int length = relation.length();
        return name.length() > length + 1
                && name.charAt(length) == '.'
                && name.regionMatches(true, 0, relation, 0, length)
                ? name.substring(length + 1) : name;
    }

    /**
     * Builds the output(new) → input(old) column-name mapping for a column-renaming
     * ρ, keyed by lowercased new name. For the positional form every column is
     * renamed by index (returns {@code null} on an arity mismatch); for the pair
     * form each {@code old → new} pair maps that column and unlisted columns map to
     * themselves.
     */
    private static Map<String, String> renameNameMapping(RenameNode r, Schema inputSchema) {
        Map<String, String> mapping = new HashMap<>();
        if (!r.pairs().isEmpty()) {
            Map<String, String> fromToTo = new HashMap<>();
            for (RenameNode.RenamePair p : r.pairs()) {
                fromToTo.put(p.from().toLowerCase(Locale.ROOT), p.to());
            }
            for (var col : inputSchema.columns()) {
                String out = fromToTo.getOrDefault(col.name().toLowerCase(Locale.ROOT), col.name());
                mapping.put(out.toLowerCase(Locale.ROOT), col.name());
            }
        } else {
            List<String> newNames = r.attributes();
            if (newNames.size() != inputSchema.width()) return null;
            for (int i = 0; i < newNames.size(); i++) {
                mapping.put(newNames.get(i).toLowerCase(Locale.ROOT),
                        inputSchema.columns().get(i).name());
            }
        }
        return mapping;
    }

    /**
     * Determines which side of an inner join a predicate with {@code predAttrs}
     * can be pushed into.  Returns {@link JoinSide#LEFT} when all referenced
     * columns are exclusively in the left schema, {@link JoinSide#RIGHT} when
     * exclusively in the right schema, and {@link JoinSide#NEITHER} otherwise
     * (predicate spans both sides, or column is absent from both).
     */
    private static JoinSide determineJoinSide(Set<String> predAttrs,
                                               RelNode left, Schema leftSchema,
                                               RelNode right, Schema rightSchema) {
        Set<String> leftQualifiers  = Qualifiers.inScope(left);
        Set<String> rightQualifiers = Qualifiers.inScope(right);
        boolean allLeft  = true;
        boolean allRight = true;
        for (String attr : predAttrs) {
            JoinSides.Side side = JoinSides.sideOf(
                    attr, leftSchema, rightSchema, leftQualifiers, rightQualifiers);
            allLeft  &= side == JoinSides.Side.LEFT;
            allRight &= side == JoinSides.Side.RIGHT;
        }
        if (allLeft)  return JoinSide.LEFT;
        if (allRight) return JoinSide.RIGHT;
        return JoinSide.NEITHER;
    }

    // =========================================================================
    // Predicate attribute rewriter (for SEL-004)
    // =========================================================================

    /**
     * Recursively rewrites every {@link AttributeOperand} in {@code pred} whose
     * unqualified column name (lowercased) matches a key in {@code mapping},
     * substituting the mapped old name.  Qualified names are stripped to their
     * column part before lookup, and the rewritten operand is unqualified.
     */
    private static Predicate rewriteAttrNames(Predicate pred,
                                               UnaryOperator<String> mapping) {
        return switch (pred) {
            case ComparisonPredicate c -> {
                Operand newL = rewriteAttrNames(c.left(), mapping);
                Operand newR = rewriteAttrNames(c.right(), mapping);
                yield (newL != c.left() || newR != c.right())
                        ? new ComparisonPredicate(newL, c.operator(), newR, c.location()) : c;
            }
            case AndPredicate a -> {
                Predicate newL = rewriteAttrNames(a.left(), mapping);
                Predicate newR = rewriteAttrNames(a.right(), mapping);
                yield (newL != a.left() || newR != a.right())
                        ? new AndPredicate(newL, newR, a.location()) : a;
            }
            case OrPredicate o -> {
                Predicate newL = rewriteAttrNames(o.left(), mapping);
                Predicate newR = rewriteAttrNames(o.right(), mapping);
                yield (newL != o.left() || newR != o.right())
                        ? new OrPredicate(newL, newR, o.location()) : o;
            }
            case NotPredicate n -> {
                Predicate inner = rewriteAttrNames(n.predicate(), mapping);
                yield inner != n.predicate() ? new NotPredicate(inner, n.location()) : n;
            }
            case NullPredicate n -> {
                Operand newOp = rewriteAttrNames(n.operand(), mapping);
                yield newOp != n.operand() ? new NullPredicate(newOp, n.isNull(), n.location()) : n;
            }
            case ElementOfPredicate e -> {
                Operand newElem = rewriteAttrNames(e.element(), mapping);
                Operand newSet  = rewriteAttrNames(e.setExpression(), mapping);
                yield (newElem != e.element() || newSet != e.setExpression())
                        ? new ElementOfPredicate(newElem, newSet, e.isNegated(), e.location()) : e;
            }
            case PatternPredicate p -> {
                Operand newOperand = rewriteAttrNames(p.operand(), mapping);
                Operand newPattern = rewriteAttrNames(p.pattern(), mapping);
                yield (newOperand != p.operand() || newPattern != p.pattern())
                        ? new PatternPredicate(newOperand, newPattern, p.negated(), p.location()) : p;
            }
        };
    }

    private static Operand rewriteAttrNames(Operand op, UnaryOperator<String> mapping) {
        return switch (op) {
            case AttributeOperand a -> {
                String mapped = mapping.apply(a.name());
                yield mapped.equals(a.name()) ? a : new AttributeOperand(mapped, a.location());
            }
            case BinaryArithmeticExpression b -> {
                Operand newL = rewriteAttrNames(b.left(), mapping);
                Operand newR = rewriteAttrNames(b.right(), mapping);
                yield (newL != b.left() || newR != b.right())
                        ? new BinaryArithmeticExpression(newL, b.operator(), newR, b.location()) : b;
            }
            case FunctionCall f -> {
                List<Operand> newArgs = f.arguments().stream()
                        .map(a -> rewriteAttrNames(a, mapping))
                        .toList();
                boolean changed = IntStream.range(0, newArgs.size())
                        .anyMatch(i -> newArgs.get(i) != f.arguments().get(i));
                yield changed ? new FunctionCall(f.functionName(), newArgs, f.location()) : f;
            }
            case UnaryOperand u -> {
                Operand inner = rewriteAttrNames(u.operand(), mapping);
                yield inner != u.operand() ? new UnaryOperand(inner, u.location()) : u;
            }
            case SetLiteralOperand s -> {
                List<Operand> newElems = s.elements().stream()
                        .map(e -> rewriteAttrNames(e, mapping))
                        .toList();
                boolean changed = IntStream.range(0, newElems.size())
                        .anyMatch(i -> newElems.get(i) != s.elements().get(i));
                yield changed ? new SetLiteralOperand(newElems, s.location()) : s;
            }
            case StructConstruction struct -> {
                List<StructConstruction.Field> newFields = struct.fields().stream()
                        .map(f -> new StructConstruction.Field(f.name(), rewriteAttrNames(f.value(), mapping)))
                        .toList();
                boolean changed = IntStream.range(0, newFields.size())
                        .anyMatch(i -> newFields.get(i).value() != struct.fields().get(i).value());
                yield changed ? new StructConstruction(newFields, struct.location()) : struct;
            }
            case ArrayConstruction array -> {
                List<Operand> newElems = array.elements().stream()
                        .map(e -> rewriteAttrNames(e, mapping))
                        .toList();
                boolean changed = IntStream.range(0, newElems.size())
                        .anyMatch(i -> newElems.get(i) != array.elements().get(i));
                yield changed ? new ArrayConstruction(newElems, array.location()) : array;
            }
            default -> op; // literals — no attrs to rewrite
        };
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns {@code true} when {@code schema} has a column matching the
     *  unqualified column part of {@code attrName} (case-insensitive). */
    /**
     * Whether {@code schema} can answer the reference {@code attrName}, under either
     * reading a dotted name has: as a path into a nested column ({@code location.city},
     * whose column is the head) or as a relation-qualified reference ({@code Orders.amount},
     * whose column is the tail).
     *
     * <p>Asking only the second is what let a σ on a left-hand nested column be pushed
     * into the right input of a join: {@code location.city} stripped to {@code city},
     * which the right input happened to carry. Where the question is <em>which</em> of
     * two inputs owns a reference, {@link JoinSides#sideOf} answers it in precedence
     * order instead — a union of both readings cannot, since each side may answer under
     * a different one.
     */
    private static boolean hasColumn(Schema schema, String attrName) {
        return schema.resolvePath(attrName).isPresent()
                || schema.column(PredicateAttributeCollector.columnPart(attrName)).isPresent();
    }

    // ─── Join side ────────────────────────────────────────────────────────────

    private enum JoinSide { LEFT, RIGHT, NEITHER }
}

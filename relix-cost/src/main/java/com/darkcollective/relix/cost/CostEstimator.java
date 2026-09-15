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

import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.EmptyRelationNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicates;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.IntervalJoinNode;
import com.darkcollective.relix.ast.AttributeNames;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.CompositionNode;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.DivisionNode;
import com.darkcollective.relix.ast.FullOuterJoinNode;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.LimitNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.RightOuterJoinNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;
import com.darkcollective.relix.ast.OptimizeNode;
import com.darkcollective.relix.ast.OuterUnionNode;
import com.darkcollective.relix.ast.ReservoirSampleNode;
import com.darkcollective.relix.ast.SampleNode;
import com.darkcollective.relix.ast.SolveNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.TopKNode;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ClusterNode;
import com.darkcollective.relix.ast.PathNode;
import com.darkcollective.relix.ast.TraceNode;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.ast.CoverNode;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.ast.PivotNode;
import com.darkcollective.relix.ast.SessionizeNode;
import com.darkcollective.relix.ast.TreeNode;
import com.darkcollective.relix.ast.WhyNode;
import com.darkcollective.relix.ast.UnpivotNode;
import com.darkcollective.relix.ast.WindowNode;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnStatistics;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;
import com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.relation.SystemRelationSymbol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Estimates the relative I/O cost of a {@link RelNode} expression by
 * inspecting the types of its leaf relation symbols.
 *
 * <p>Cost is represented as a {@link CostTier} ordinal — higher ordinal means
 * more expensive.  The cost of a composite expression is the maximum (worst-case)
 * cost of any leaf in its sub-tree, on the principle that the most expensive
 * source dominates overall I/O cost.
 *
 * <h2>Leaf cost mapping</h2>
 * <ul>
 *   <li>{@link InlineRelationSymbol}   → {@link CostTier#INLINE}  (in-memory)</li>
 *   <li>{@link QueryRelationSymbol}    → recursive estimate of its body</li>
 *   <li>{@link SourceRelationSymbol}   → {@link CostTier#FILE}    (local file I/O)</li>
 *   <li>{@link DatabaseRelationSymbol} → {@link CostTier#REMOTE}  (JDBC round-trip)</li>
 *   <li>Unknown name                   → {@link CostTier#FILE}    (conservative)</li>
 * </ul>
 *
 * <p>A {@code null} symbol table is permitted; in that case every
 * {@link RelationNode} leaf resolves to {@link CostTier#FILE} (conservative
 * fallback).
 *
 * <p>Recursion into {@link QueryRelationSymbol} bodies is bounded by an
 * internal depth limit ({@value #MAX_DEPTH}) to guard against unexpectedly deep
 * view chains; beyond that limit {@link CostTier#FILE} is returned.
 *
 * <h2>Cardinality estimation</h2>
 * <p>In addition to the coarse tier, {@link #estimateRows(RelNode)} produces a
 * numeric row-count estimate when the leaf relations carry
 * {@link RelationStatistics} (supplied via a {@link StatisticsSource}).  Row
 * counts are propagated through operators with simple, documented heuristics
 * (selection selectivity, join cardinality, etc.).  When per-column distinct
 * counts are available, three estimates are sharpened for the common case where the
 * relevant input is a base relation: a grouped aggregation uses the product of its
 * grouping columns' distinct counts (capped at the input size), an inner
 * equi-join uses {@code |L|·|R| / max(distinct(L.k), distinct(R.k))} (with a
 * composite key's distinct count taken as the product of its columns'), and a
 * selection reads its own predicate rather than assuming
 * {@link #DEFAULT_SELECTIVITY} — {@code 1/d} for an equality, {@code n/d} for an
 * {@code IN} list, {@code nullCount/rowCount} for {@code IS NULL}, combined through
 * the connectives under the independence assumption.  All three fall
 * back to their coarse heuristic when the columns or statistics are unknown.
 * The estimate is <em>honest</em>: it is {@link OptionalLong#empty()} whenever a
 * contributing leaf has no known row count, so callers can fall back to the tier
 * model rather than act on a fabricated number.
 *
 * <p>This class is stateless beyond its injected {@code SymbolTable} and
 * {@link StatisticsSource}; a single instance may be reused across multiple
 * {@link #estimate(RelNode)} / {@link #estimateRows(RelNode)} calls.
 */
public final class CostEstimator {

    /** Maximum recursion depth when resolving {@link QueryRelationSymbol} bodies. */
    static final int MAX_DEPTH = 32;

    /** Default fraction of rows assumed to survive a selection of unknown selectivity. */
    static final double DEFAULT_SELECTIVITY = 0.33;

    private final SymbolTable symbolTable; // nullable
    private final StatisticsSource statistics; // never null
    private ObservedCardinalities observed = ObservedCardinalities.NONE;

    /**
     * Creates a {@code CostEstimator} backed by the given symbol table with no
     * statistics (tier-based estimation only).
     *
     * @param symbolTable the symbol table used to look up leaf relation names;
     *                    may be {@code null}, in which case all leaf names
     *                    resolve to {@link CostTier#FILE}
     */
    public CostEstimator(SymbolTable symbolTable) {
        this(symbolTable, StatisticsSource.NONE);
    }

    /**
     * Creates a {@code CostEstimator} backed by the given symbol table and
     * statistics source.
     *
     * @param symbolTable the symbol table used to look up leaf relation names;
     *                    may be {@code null} (all leaves then resolve to
     *                    {@link CostTier#FILE} and carry no row count)
     * @param statistics  the source of per-relation statistics; must not be null
     *                    (use {@link StatisticsSource#NONE} for none)
     */
    public CostEstimator(SymbolTable symbolTable, StatisticsSource statistics) {
        this.symbolTable = symbolTable; // intentionally nullable
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    /**
     * Lets this estimator prefer a row count a previous run actually produced over the
     * one it would compute.
     *
     * <p>A measured count beats an estimate wherever there is one, which is why it is
     * consulted before the walk rather than blended into it: a selectivity guess applied
     * on top of a real number would make the answer worse than either input.
     *
     * @param observed the recorded counts; must not be null
     *                 ({@link ObservedCardinalities#NONE} for none)
     * @return this estimator, for chaining
     */
    public CostEstimator withObserved(ObservedCardinalities observed) {
        this.observed = Objects.requireNonNull(observed, "observed");
        return this;
    }

    /**
     * Returns the cost tier for the given expression tree.
     *
     * @param node the expression to estimate; must not be null
     * @return the cost tier; never null
     */
    public CostTier estimate(RelNode node) {
        Objects.requireNonNull(node, "node");
        return estimateAt(node, 0);
    }

    /**
     * Estimates the number of rows the given expression produces.
     *
     * <p>Returns {@link OptionalLong#empty()} when the estimate cannot be made
     * because some contributing leaf relation has no known row count.  A present
     * value is a heuristic upper-bound-flavoured estimate, suitable for comparing
     * the relative size of two sub-trees (e.g. to pick a hash-join build side).
     *
     * @param node the expression to estimate; must not be null
     * @return the estimated row count, or empty if unknown
     */
    public OptionalLong estimateRows(RelNode node) {
        Objects.requireNonNull(node, "node");
        // A count this exact expression really produced, if one was recorded. Checked
        // first and returned whole: it is not an input to the estimate, it is a better
        // answer than the estimate, and the sub-tree beneath it needs no walk at all.
        OptionalLong measured = observed.forExpression(node);
        if (measured.isPresent()) {
            return measured;
        }
        return rowsAt(node, 0);
    }

    // =========================================================================
    // Private — recursive tree walk
    // =========================================================================

    private CostTier estimateAt(RelNode node, int depth) {
        if (depth > MAX_DEPTH) return CostTier.FILE; // depth guard

        // Leaf relations carry the cost; every other operator is the worst (max)
        // tier of its children — unary nodes simply pass their single input through.
        if (node instanceof RelationNode relation) {
            return leafCost(relation.name(), depth);
        }
        // A truth-relation literal is materialised from nothing at all — the
        // cheapest leaf there is.
        if (node instanceof TruthRelationNode) {
            return CostTier.INLINE;
        }
        // A table-valued function call is a leaf (its arguments are operands, not
        // relational children); cost it as its (inlined) body.
        if (node instanceof RelationFunctionCall call) {
            return symbolTable == null ? CostTier.FILE
                    : symbolTable.lookupFunction(call.functionName()).stream()
                            .filter(RelationFunctionSymbol.class::isInstance)
                            .map(RelationFunctionSymbol.class::cast)
                            .findFirst()
                            .map(fn -> estimateAt(fn.body(), depth + 1))
                            .orElse(CostTier.FILE);
        }
        CostTier worst = CostTier.INLINE;   // identity for "max"
        for (RelNode child : node.children()) {
            CostTier childCost = estimateAt(child, depth + 1);
            if (childCost.ordinal() > worst.ordinal()) {
                worst = childCost;
            }
        }
        return worst;
    }

    private CostTier leafCost(String name, int depth) {
        if (symbolTable == null) return CostTier.FILE;
        return symbolTable.resolveRelation(name)
                .map(sym -> tierFor(sym, depth))
                .orElse(CostTier.FILE);
    }

    private CostTier tierFor(RelationSymbol sym, int depth) {
        return switch (sym) {
            case InlineRelationSymbol   _ -> CostTier.INLINE;
            case SystemRelationSymbol   _ -> CostTier.INLINE;
            case QueryRelationSymbol   qr -> estimateAt(qr.body(), depth + 1);
            case SourceRelationSymbol   _ -> CostTier.FILE;
            case DatabaseRelationSymbol _ -> CostTier.REMOTE;
        };
    }

    // =========================================================================
    // Private — recursive cardinality walk
    // =========================================================================

    private OptionalLong rowsAt(RelNode node, int depth) {
        if (depth > MAX_DEPTH) return OptionalLong.empty();
        return switch (node) {
            case RelationNode r   -> leafRows(r.name(), depth);

            // A truth-relation literal has an exact, statistics-free cardinality:
            // UNIT is the one empty tuple, EMPTY is no tuple at all.
            case TruthRelationNode t -> OptionalLong.of(t.cardinality());

            // ∅ is exactly zero rows — the one place a zero estimate is a fact rather
            // than a fabrication. Its carried heading is inert and is not costed:
            // nothing in it is ever executed.
            case EmptyRelationNode _ -> OptionalLong.of(0L);

            // A table-valued function is bound by substitution, so its cardinality is
            // that of its (un-substituted) body — substituting constants only narrows it.
            case RelationFunctionCall f -> relationFunctionRows(f, depth);

            // Pass-through operators do not change the row count (upper bound).
            // Unnest multiplies rows by the (unknown) array length; input rows are a rough estimate.
            // Cluster emits one row per distinct node — at most ~2× the edge count, so the
            // input row count is a serviceable upper-bound proxy.
            // Goal-seek fills NULLs in place; optimisation and cover emit a subset of the input.
            // Window adds a column to every input row — one output row per input row.
            // TREE emits one row per root — at most the input row count (an
            // adjacency relation with no edges is all-roots), a serviceable upper bound.
            // WHY emits every input row unchanged plus a provenance column — one
            // output row per input row.
            case ProjectionNode _, RenameNode _, SortNode _, DistinctNode _,
                 UnnestNode _, ClusterNode _, PathNode _, SolveNode _, OptimizeNode _, CoverNode _,
                 WindowNode _, SessionizeNode _, PivotNode _, TreeNode _, WhyNode _ ->
                    rowsAt(node.children().get(0), depth + 1);

            // A bounded closure (ADR-0020 endpoint pushdown) computes far fewer pairs
            // than the all-pairs form: both endpoints fixed is a single-pair check
            // (≤ 1 row); one endpoint fixed is single-source/single-target reachability,
            // bounded by the node count (the input edge count is a serviceable proxy).
            case ClosureNode c -> {
                if (c.boundSource().isPresent() && c.boundTarget().isPresent()) {
                    yield OptionalLong.of(1L);
                }
                yield rowsAt(c.input(), depth + 1);
            }

            // A bounded TRACE (ADR-0020 endpoint pushdown) likewise computes a single
            // slice of the all-pairs optimal-path table: both endpoints fixed is a
            // single-pair search (≤ 1 row); one endpoint fixed is single-source/target.
            case TraceNode t -> {
                if (t.boundSource().isPresent() && t.boundTarget().isPresent()) {
                    yield OptionalLong.of(1L);
                }
                yield rowsAt(t.input(), depth + 1);
            }

            // UNPIVOT emits one output row per column per input row
            case UnpivotNode uv -> {
                OptionalLong inputRows = rowsAt(uv.input(), depth + 1);
                yield inputRows.isPresent()
                        ? OptionalLong.of(inputRows.getAsLong() * uv.columns().size())
                        : inputRows;
            }

            case DownsampleNode d -> downsampleRows(d, depth);

            case SelectionNode s  -> selectionRows(s, depth);
            // Bernoulli sampling keeps ~probability × input rows.
            case SampleNode s     -> scale(rowsAt(s.input(), depth + 1), s.probability());
            // Reservoir sampling keeps exactly `count` rows (or all when fewer).
            case ReservoirSampleNode s -> reservoirRows(s, depth);
            case LimitNode l      -> capLimit(rowsAt(l.input(), depth + 1), l.offset().orElse(0L), l.count());
            case AggregationNode a -> aggregateRows(a, depth);
            // ∀ keeps at most one row per group; estimate as the group count
            // (capped at input rows), like a grouped aggregation.
            case UniversalNode u -> groupedRows(u.groupingAttributes(), u.input(), depth);
            // Top-k keeps at most `count` rows per group, capped at the input size.
            case TopKNode t -> topKRows(t, depth);

            // Inner equi/natural joins refine with distinct counts when possible;
            // outer joins keep the ~max(sides) heuristic.
            case NaturalJoinNode j -> equiJoinRows(j.left(), j.right(), null, depth);
            case ThetaJoinNode j   -> equiJoinRows(j.left(), j.right(), j.condition(), depth);
            case LeftOuterJoinNode _, RightOuterJoinNode _, FullOuterJoinNode _ ->
                    max(rowsAt(node.children().get(0), depth + 1),
                        rowsAt(node.children().get(1), depth + 1));

            // Output ≤ left-input rows: ⋉/▷/pairwise-∀ are left-filter operators
            // (OperatorSemantics.isLeftFilterOperator); − and ÷ also output a left-input
            // subset but are set-producing (force deduplication) so PropertyDeriver
            // categorises them separately.
            case SemiJoinNode _, AntiJoinNode _, PairwiseUniversalNode _,
                 DifferenceNode _, DivisionNode _ ->
                    rowsAt(node.children().get(0), depth + 1);

            // AS-OF join: left-outer = exactly |left| rows; inner = at most |left| rows.
            case AsOfJoinNode a -> rowsAt(a.left(), depth + 1);

            // Interval join is an inner join: at most |left| × |right| but conservatively |left|.
            case IntervalJoinNode j -> rowsAt(j.left(), depth + 1);

            case ProductNode p      -> product(rowsAt(p.left(), depth + 1), rowsAt(p.right(), depth + 1));
            case UnionNode u        -> sum(rowsAt(u.left(), depth + 1), rowsAt(u.right(), depth + 1));
            case UnionAllNode u     -> sum(rowsAt(u.left(), depth + 1), rowsAt(u.right(), depth + 1));
            case OuterUnionNode u   -> sum(rowsAt(u.left(), depth + 1), rowsAt(u.right(), depth + 1));
            case IntersectionNode i -> min(rowsAt(i.left(), depth + 1), rowsAt(i.right(), depth + 1));

            // Symmetric difference ≡ (L−R) ∪ (R−L): at most |L| + |R| rows.
            case SymmetricDifferenceNode s ->
                    sum(rowsAt(s.left(), depth + 1), rowsAt(s.right(), depth + 1));
            // Composition ≡ π(L ⋈ R): the projection does not change the row count,
            // so it estimates as the underlying natural (equi-)join.
            case CompositionNode c -> equiJoinRows(c.left(), c.right(), null, depth);

            // General recursion (FIX) — a conservative placeholder. Recursion
            // cardinality is generally unknown (O(n²) for closure-like shapes, worse
            // for richer bodies), so the honest estimate is empty; the recursive
            // reference's extent (the accumulator) is likewise unknown here.
            case FixpointNode _, RecursiveRefNode _ -> OptionalLong.empty();

            // Lateral TVF join: conservatively |left| × |TVF_body| rows.
            // (Each left row drives one TVF invocation; the TVF body may return many rows.)
            case LateralJoinNode n -> {
                OptionalLong leftRows = rowsAt(n.left(), depth + 1);
                OptionalLong tvfRows = symbolTable == null ? OptionalLong.empty()
                        : symbolTable.lookupFunction(n.functionName()).stream()
                                .filter(RelationFunctionSymbol.class::isInstance)
                                .map(RelationFunctionSymbol.class::cast)
                                .findFirst()
                                .map(fn -> rowsAt(fn.body(), depth + 1))
                                .orElse(OptionalLong.empty());
                yield product(leftRows, tvfRows);
            }
        };
    }

    private OptionalLong relationFunctionRows(RelationFunctionCall call, int depth) {
        if (symbolTable == null) return OptionalLong.empty();
        return symbolTable.lookupFunction(call.functionName()).stream()
                .filter(RelationFunctionSymbol.class::isInstance)
                .map(RelationFunctionSymbol.class::cast)
                .findFirst()
                .map(fn -> rowsAt(fn.body(), depth + 1))
                .orElse(OptionalLong.empty());
    }

    private OptionalLong leafRows(String name, int depth) {
        if (symbolTable != null) {
            Optional<RelationSymbol> sym = symbolTable.resolveRelation(name);
            if (sym.isPresent()) {
                RelationSymbol symbol = sym.get();
                if (symbol instanceof QueryRelationSymbol qr) {
                    return rowsAt(qr.body(), depth + 1);   // inline the view's cardinality
                }
                if (symbol instanceof InlineRelationSymbol inline) {
                    return OptionalLong.of(inline.rows().size());
                }
                if (symbol instanceof SystemRelationSymbol sys) {
                    // System catalog extents are held in memory — cardinality is exact.
                    return OptionalLong.of(sys.rows().size());
                }
            }
        }
        return statistics.forRelation(name)
                .map(RelationStatistics::rowCount)
                .orElse(OptionalLong.empty());
    }

    // ── cardinality refinements using column statistics ─────────────────────

    /**
     * Estimates a selection's row count as {@code |input| × selectivity(predicate)}.
     *
     * <p>The selectivity comes from {@link #selectivity} when the predicate's shape
     * and the available statistics allow it, and from {@link #DEFAULT_SELECTIVITY}
     * otherwise — so a predicate the estimator cannot read costs exactly what it
     * always did.
     */
    private OptionalLong selectionRows(SelectionNode node, int depth) {
        OptionalLong inputRows = rowsAt(node.input(), depth + 1);
        // Statistics are only resolvable against a base relation, matching the
        // convention groupedRows and equiJoinRows already use: over any other input
        // the column names have been renamed, derived or joined and no longer
        // correspond to a catalogued column.
        String relation = node.input() instanceof RelationNode r ? r.name() : null;
        double factor = selectivity(node.predicate(), relation)
                .orElse(DEFAULT_SELECTIVITY);
        return scale(inputRows, factor);
    }

    /**
     * Estimates the fraction of rows {@code p} admits, or empty when its shape or
     * the available statistics do not support an estimate.
     *
     * <p>The leaves that can be estimated are the ones a distinct or null count
     * answers directly:
     * <ul>
     *   <li>{@code c = k} over a column with {@code d} distinct values → {@code 1/d},
     *       and {@code c ≠ k} → {@code 1 − 1/d}. This is the <em>uniformity
     *       assumption</em>: every value is assumed equally frequent, which is what a
     *       distinct count alone can support.</li>
     *   <li>{@code c ∈ {k₁ … kₙ}} → {@code n/d}, capped at one — the same assumption
     *       applied {@code n} times; negated, its complement.</li>
     *   <li>{@code c IS NULL} → {@code nullCount / rowCount}, the one exact
     *       frequency the statistics carry, and {@code IS NOT NULL} its complement.</li>
     * </ul>
     *
     * <p>Range comparisons ({@code <}, {@code >}, …) are deliberately absent:
     * {@link ColumnStatistics} has no min/max and no histogram, so {@code x > 5} is
     * genuinely unestimable and falls back rather than guessing.
     *
     * <p>The connectives combine under the <em>independence assumption</em> —
     * {@code s(a ∧ b) = s(a)·s(b)}, {@code s(a ∨ b) = 1 − (1−s(a))(1−s(b))},
     * {@code s(¬a) = 1 − s(a)} — correlated columns are therefore under-estimated by
     * a conjunction and over-estimated by a disjunction. An unreadable side of a
     * connective contributes {@link #DEFAULT_SELECTIVITY} so the readable side still
     * counts, but a connective with <em>no</em> readable leaf stays empty rather
     * than manufacturing a number from the default alone: {@code ¬p} for an opaque
     * {@code p} would otherwise claim {@code 0.67}, which is a stronger statement
     * than "unknown".
     */
    private Optional<Double> selectivity(Predicate p, String relation) {
        return switch (p) {
            case AndPredicate a -> combine(selectivity(a.left(), relation),
                    selectivity(a.right(), relation),
                    (l, r) -> l * r);
            case OrPredicate o -> combine(selectivity(o.left(), relation),
                    selectivity(o.right(), relation),
                    (l, r) -> 1.0 - (1.0 - l) * (1.0 - r));
            case NotPredicate n -> selectivity(n.predicate(), relation).map(s -> 1.0 - s);
            case ComparisonPredicate c -> comparisonSelectivity(c, relation);
            case ElementOfPredicate e -> elementOfSelectivity(e, relation);
            case NullPredicate n -> nullSelectivity(n, relation);
            // LIKE has no statistic that speaks to it — a distinct count says nothing
            // about how many values match a pattern.
            case PatternPredicate _ -> Optional.empty();
        };
    }

    /**
     * Combines two branch selectivities, substituting {@link #DEFAULT_SELECTIVITY}
     * for an unreadable branch — but only when the other branch is readable, so a
     * connective over two opaque predicates stays empty.
     */
    private static Optional<Double> combine(Optional<Double> left, Optional<Double> right,
                                            java.util.function.DoubleBinaryOperator op) {
        if (left.isEmpty() && right.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(clamp(op.applyAsDouble(
                left.orElse(DEFAULT_SELECTIVITY), right.orElse(DEFAULT_SELECTIVITY))));
    }

    /** Selectivity of {@code column = literal} / {@code column ≠ literal} as {@code 1/d}. */
    private Optional<Double> comparisonSelectivity(ComparisonPredicate c, String relation) {
        if (relation == null) {
            return Optional.empty();
        }
        boolean equal = c.operator() == ComparisonOperator.EQUAL;
        if (!equal && c.operator() != ComparisonOperator.NOT_EQUAL) {
            return Optional.empty();   // no min/max, so a range predicate is unestimable
        }
        return columnAgainstLiteral(c.left(), c.right())
                .flatMap(column -> distinctFraction(relation, column))
                .map(fraction -> equal ? fraction : 1.0 - fraction);
    }

    /** Selectivity of {@code column ∈ {k₁ … kₙ}} as {@code n/d}, capped at one. */
    private Optional<Double> elementOfSelectivity(ElementOfPredicate e, String relation) {
        if (relation == null
                || !(e.element() instanceof AttributeOperand a)
                || !(e.setExpression() instanceof SetLiteralOperand set)
                || set.elements().isEmpty()
                || !set.elements().stream().allMatch(Predicates::isLiteral)) {
            return Optional.empty();
        }
        return distinctFraction(relation, a.name())
                .map(fraction -> clamp(fraction * set.elements().size()))
                .map(fraction -> e.isNegated() ? 1.0 - fraction : fraction);
    }

    /**
     * Selectivity of {@code column IS NULL} as {@code nullCount / rowCount} — the one
     * frequency the statistics record exactly rather than under the uniformity
     * assumption.  Needs both counts, and a non-zero row count to divide by.
     */
    private Optional<Double> nullSelectivity(NullPredicate n, String relation) {
        if (relation == null || !(n.operand() instanceof AttributeOperand a)) {
            return Optional.empty();
        }
        Optional<RelationStatistics> stats = statistics.forRelation(relation);
        if (stats.isEmpty() || stats.get().rowCount().isEmpty()) {
            return Optional.empty();
        }
        long rows = stats.get().rowCount().getAsLong();
        if (rows <= 0) {
            return Optional.empty();
        }
        return columnStatistics(relation, a.name())
                .flatMap(cs -> cs.nullCount().isPresent()
                        ? Optional.of(clamp((double) cs.nullCount().getAsLong() / rows))
                        : Optional.empty())
                .map(fraction -> n.isNull() ? fraction : 1.0 - fraction);
    }

    /** The column name of a {@code column ⊙ literal} comparison, in either operand order. */
    private static Optional<String> columnAgainstLiteral(Operand left, Operand right) {
        if (left instanceof AttributeOperand a && Predicates.isLiteral(right)) {
            return Optional.of(a.name());
        }
        if (right instanceof AttributeOperand a && Predicates.isLiteral(left)) {
            return Optional.of(a.name());
        }
        return Optional.empty();
    }

    /** {@code 1/d} for a column with a known, positive distinct count. */
    private Optional<Double> distinctFraction(String relation, String column) {
        OptionalLong distinct = distinctCount(relation, column);
        return distinct.isPresent() && distinct.getAsLong() > 0
                ? Optional.of(1.0 / distinct.getAsLong())
                : Optional.empty();
    }

    /** Statistics for {@code column} of base relation {@code relation}, matched case-insensitively. */
    private Optional<ColumnStatistics> columnStatistics(String relation, String column) {
        Optional<RelationStatistics> stats = statistics.forRelation(relation);
        if (stats.isEmpty()) {
            return Optional.empty();
        }
        String target = AttributeNames.stripQualifier(column);
        return stats.get().columnStatistics().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(target))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** Confines a selectivity to {@code [0, 1]} — an assumption stacked n times can leave it. */
    private static double clamp(double selectivity) {
        return Math.min(1.0, Math.max(0.0, selectivity));
    }

    /**
     * Estimates a grouped aggregation's row count.  When the input is a base
     * relation and the grouping columns' distinct counts are known, the result is
     * the product of those distinct counts, capped at the input row count; a scalar
     * aggregate is one row; otherwise it falls back to "≤ input rows".
     */
    private OptionalLong aggregateRows(AggregationNode node, int depth) {
        // For statistics, a bare column uses its own name; a derived key has no
        // base-relation stats, so its output name resolves to no distinct count
        // and the estimate degrades gracefully to "≤ input rows".
        List<String> statNames = node.groupingKeys().stream()
                .map(k -> k.columnName().orElseGet(k::outputName))
                .toList();
        return groupedRows(statNames, node.input(), depth);
    }

    /**
     * Cardinality of a reservoir (fixed-count) sample: exactly {@code count} rows,
     * or the whole input when it has fewer.  When the input size is known the
     * estimate is {@code min(count, inputRows)}; otherwise the result never exceeds
     * {@code count}, which is itself a tight upper bound.
     */
    private OptionalLong reservoirRows(ReservoirSampleNode node, int depth) {
        OptionalLong inputRows = rowsAt(node.input(), depth + 1);
        return inputRows.isPresent()
                ? OptionalLong.of(Math.min(node.count(), inputRows.getAsLong()))
                : OptionalLong.of(node.count());
    }

    /**
     * Cardinality of a top-k-per-group: at most {@code count} rows per group,
     * never more than the input.
     *
     * <p>The estimate depends on whether the group count can be derived from
     * statistics, because {@code count} is a <em>per-group</em> cap rather than a
     * global one:
     * <ul>
     *   <li><b>Group count known</b> (the grouping columns of a base relation have
     *       distinct-count statistics): the tight {@code groupCount × count}, capped
     *       at the input row count when that too is known.</li>
     *   <li><b>Group count unknown but input size known</b>: the group count cannot
     *       be derived, so a top-k is treated like a coarse selection — its input
     *       reduced by {@link #DEFAULT_SELECTIVITY} — but never below one group's
     *       worth ({@code count}) and never above the input.  This keeps {@code count}
     *       meaningful instead of collapsing to the whole input.</li>
     *   <li><b>Both unknown</b>: a per-group cap over an unknown number of groups is
     *       genuinely unbounded, so the estimate is {@link OptionalLong#empty()}.</li>
     * </ul>
     */
    private OptionalLong topKRows(TopKNode node, int depth) {
        OptionalLong inputRows = rowsAt(node.input(), depth + 1);
        OptionalLong groups = node.input() instanceof RelationNode rel
                ? groupCount(rel.name(), node.groupingAttributes())
                : OptionalLong.empty();

        if (groups.isPresent()) {
            long perGroupTotal = saturatingMultiply(groups.getAsLong(), node.count());
            return inputRows.isPresent()
                    ? OptionalLong.of(Math.min(perGroupTotal, inputRows.getAsLong()))
                    : OptionalLong.of(perGroupTotal);
        }
        if (inputRows.isEmpty()) {
            return OptionalLong.empty();
        }
        long n = inputRows.getAsLong();
        long reduced = Math.max(node.count(), Math.round(n * DEFAULT_SELECTIVITY));
        return OptionalLong.of(Math.min(n, reduced));
    }

    /**
     * Cardinality of a {@code DOWNSAMPLE}: one row per (time bucket × grouping-key
     * combination) that has data, capped by an explicit {@code FOR n ROWS}.
     *
     * <p>The bucket count is {@code time span ÷ interval}, and the time span needs a
     * min/max on the timestamp column, which {@link ColumnStatistics} does not carry.
     * So the bucket count is <em>not derivable</em>, and neither is the output size.
     *
     * <p>This deliberately does <strong>not</strong> delegate to
     * {@link #groupedRows}, which is what it used to do. That treats the operator as
     * an ordinary grouped aggregation and ignores the bucketing entirely, so the
     * common shape — a downsample with no extra grouping keys — estimated exactly
     * <em>one</em> row for what is usually thousands of buckets. The grouping-key
     * product is a <em>lower</em> bound on this operator's output (every group is
     * multiplied by the unknown bucket count), so using it as the estimate errs in
     * the one direction that matters: {@code DownsampleNode} is
     * {@link com.darkcollective.relix.ast.MaterializationMode#BAG}, and
     * under-estimating a blocking operator is what mis-picks a hash-join build side.
     *
     * <p>What survives is a true upper bound — a consolidation emits at most one row
     * per input row — so the estimate falls back to the input row count, the same
     * fallback {@link #groupedRows} uses when a group count cannot be derived, and
     * tightened to {@code n} when {@code FOR n ROWS} caps the output globally.
     */
    private OptionalLong downsampleRows(DownsampleNode node, int depth) {
        OptionalLong inputRows = rowsAt(node.input(), depth + 1);
        if (node.maxRows().isEmpty()) {
            return inputRows;
        }
        long cap = node.maxRows().getAsLong();
        return OptionalLong.of(inputRows.isPresent()
                ? Math.min(cap, inputRows.getAsLong())
                : cap);
    }

    /**
     * Cardinality of a grouping operator (γ or ∀): the number of distinct
     * grouping-key combinations, capped at the input row count.  A scalar
     * (no-key) group collapses to a single row; over a base relation with column
     * statistics the distinct-count product sharpens the estimate.
     */
    private OptionalLong groupedRows(List<String> groupingAttributes, RelNode input, int depth) {
        if (groupingAttributes.isEmpty()) {
            return OptionalLong.of(1L);
        }
        OptionalLong inputRows = rowsAt(input, depth + 1);
        if (input instanceof RelationNode rel) {
            OptionalLong groups = groupCount(rel.name(), groupingAttributes);
            if (groups.isPresent()) {
                return inputRows.isPresent()
                        ? OptionalLong.of(Math.min(groups.getAsLong(), inputRows.getAsLong()))
                        : groups;
            }
        }
        return inputRows;
    }

    /** Product of the distinct counts of {@code grouping} columns in a base relation, or empty. */
    private OptionalLong groupCount(String relation, List<String> grouping) {
        long product = 1L;
        for (String attribute : grouping) {
            OptionalLong distinct = distinctCount(relation, attribute);
            if (distinct.isEmpty()) {
                return OptionalLong.empty();
            }
            product = saturatingMultiply(product, Math.max(1L, distinct.getAsLong()));
        }
        return OptionalLong.of(product);
    }

    /**
     * Estimates an inner equi-join's row count.  When both inputs are base
     * relations and the join key columns' distinct counts are known, uses the
     * textbook {@code |L|·|R| / max(distinct(L.k), distinct(R.k))}; otherwise falls
     * back to {@code max(|L|, |R|)}.
     *
     * <p>A <strong>composite</strong> key — several columns shared by a natural join,
     * or several equalities conjoined in a theta join's condition — is handled by
     * taking the product of each side's per-column distinct counts as that side's
     * effective distinct count, under the independence assumption.  This is the case
     * where the refinement matters most: a composite key is more selective than any
     * of its columns, so the coarse {@code max(|L|, |R|)} fallback over-estimates it
     * by the widest margin.  The estimator used to bail on exactly this shape.
     */
    private OptionalLong equiJoinRows(RelNode left, RelNode right, Predicate condition, int depth) {
        OptionalLong leftRows = rowsAt(left, depth + 1);
        OptionalLong rightRows = rowsAt(right, depth + 1);
        if (left instanceof RelationNode l && right instanceof RelationNode r
                && leftRows.isPresent() && rightRows.isPresent()) {
            List<String[]> keys = (condition == null)
                    ? commonColumns(l.name(), r.name())
                    : equiColumns(condition, l.name(), r.name());
            if (!keys.isEmpty()) {
                OptionalLong dl = groupCount(l.name(), keys.stream().map(k -> k[0]).toList());
                OptionalLong dr = groupCount(r.name(), keys.stream().map(k -> k[1]).toList());
                if (dl.isPresent() && dr.isPresent()) {
                    long denom = Math.max(1L, Math.max(dl.getAsLong(), dr.getAsLong()));
                    double estimate = (double) leftRows.getAsLong() / denom * rightRows.getAsLong();
                    long rows = estimate >= Long.MAX_VALUE ? Long.MAX_VALUE
                            : Math.max(1L, (long) estimate);
                    return OptionalLong.of(rows);
                }
            }
        }
        return max(leftRows, rightRows);
    }

    /**
     * Every column two base relations share by name — the implicit join key of a
     * natural join — as {@code [leftColumn, rightColumn]} pairs; empty when they
     * share none or either schema is unresolvable.
     */
    private List<String[]> commonColumns(String leftRelation, String rightRelation) {
        Optional<Schema> left = relationSchema(leftRelation);
        Optional<Schema> right = relationSchema(rightRelation);
        if (left.isEmpty() || right.isEmpty()) {
            return List.of();
        }
        List<String[]> shared = new ArrayList<>();
        for (ColumnDefinition col : left.get().columns()) {
            if (right.get().indexOf(col.name()) >= 0) {
                shared.add(new String[]{col.name(), col.name()});
            }
        }
        return shared;
    }

    /**
     * The {@code [leftColumn, rightColumn]} pairs of the {@code a = b} equalities in
     * a join condition — one pair per top-level conjunct that compares a column of
     * one side to a column of the other.
     *
     * <p>Returns empty when <em>any</em> conjunct is not such an equality: a
     * condition of the form {@code a = b ∧ x > y} filters further than the equality
     * alone, so estimating from the equality would over-estimate the result, and the
     * coarse fallback is the safer answer.
     */
    private List<String[]> equiColumns(Predicate condition, String leftRelation, String rightRelation) {
        List<String[]> pairs = new ArrayList<>();
        for (Predicate conjunct : Predicates.conjuncts(condition)) {
            String[] pair = equiColumn(conjunct, leftRelation, rightRelation);
            if (pair == null) {
                return List.of();
            }
            pairs.add(pair);
        }
        return pairs;
    }

    /** The {@code [leftColumn, rightColumn]} of a single {@code a = b} equality, or null. */
    private String[] equiColumn(Predicate condition, String leftRelation, String rightRelation) {
        if (!(condition instanceof ComparisonPredicate cmp)
                || cmp.operator() != ComparisonOperator.EQUAL
                || !(cmp.left() instanceof AttributeOperand a)
                || !(cmp.right() instanceof AttributeOperand b)) {
            return null;
        }
        String aCol = a.unqualifiedName();
        String bCol = b.unqualifiedName();
        if (belongsTo(a.name(), leftRelation) && belongsTo(b.name(), rightRelation)) {
            return new String[]{aCol, bCol};
        }
        if (belongsTo(a.name(), rightRelation) && belongsTo(b.name(), leftRelation)) {
            return new String[]{bCol, aCol};
        }
        return null;
    }

    /** Whether {@code attribute} refers to {@code relation} — by qualifier, else by schema membership. */
    private boolean belongsTo(String attribute, String relation) {
        int dot = attribute.lastIndexOf('.');
        if (dot >= 0) {
            return attribute.substring(0, dot).equalsIgnoreCase(relation);
        }
        return relationSchema(relation).map(s -> s.indexOf(attribute) >= 0).orElse(false);
    }

    /** Distinct count of {@code column} in base relation {@code relation} (case-insensitive), or empty. */
    private OptionalLong distinctCount(String relation, String column) {
        return columnStatistics(relation, column)
                .map(ColumnStatistics::distinctCount)
                .orElse(OptionalLong.empty());
    }

    private Optional<Schema> relationSchema(String name) {
        return symbolTable == null
                ? Optional.empty()
                : symbolTable.lookupRelation(name).map(RelationSymbol::schema);
    }


    private static long saturatingMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Multiplies a row count by a selectivity factor, flooring at one row. */
    private static OptionalLong scale(OptionalLong rows, double factor) {
        if (rows.isEmpty()) return OptionalLong.empty();
        return OptionalLong.of(Math.max(1L, Math.round(rows.getAsLong() * factor)));
    }

    /**
     * Caps a row count by a {@code LIMIT offset, count}.  {@code count} is a hard
     * upper bound even when the input size is unknown, so this always yields a value.
     */
    private static OptionalLong capLimit(OptionalLong rows, long offset, long count) {
        if (rows.isEmpty()) return OptionalLong.of(count);
        long available = Math.max(0L, rows.getAsLong() - offset);
        return OptionalLong.of(Math.min(available, count));
    }

    private static OptionalLong max(OptionalLong a, OptionalLong b) {
        if (a.isEmpty() || b.isEmpty()) return OptionalLong.empty();
        return OptionalLong.of(Math.max(a.getAsLong(), b.getAsLong()));
    }

    private static OptionalLong min(OptionalLong a, OptionalLong b) {
        if (a.isEmpty() || b.isEmpty()) return OptionalLong.empty();
        return OptionalLong.of(Math.min(a.getAsLong(), b.getAsLong()));
    }

    private static OptionalLong sum(OptionalLong a, OptionalLong b) {
        if (a.isEmpty() || b.isEmpty()) return OptionalLong.empty();
        long result = a.getAsLong() + b.getAsLong();
        return OptionalLong.of(result < 0 ? Long.MAX_VALUE : result);   // overflow guard
    }

    private static OptionalLong product(OptionalLong a, OptionalLong b) {
        if (a.isEmpty() || b.isEmpty()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Math.multiplyExact(a.getAsLong(), b.getAsLong()));
        } catch (ArithmeticException overflow) {
            return OptionalLong.of(Long.MAX_VALUE);
        }
    }
}

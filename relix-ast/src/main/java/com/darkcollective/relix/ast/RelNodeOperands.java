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
package com.darkcollective.relix.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The operand-level counterpart of {@link RelNode#children()}: a single, exhaustive
 * registration point for the {@link Operand} and {@link Predicate} expressions each
 * {@link RelNode} carries <em>directly</em>.
 *
 * <p>{@code children()} answers "what relations feed this node"; this answers "what
 * expressions does this node evaluate".  The second question had no single answer before
 * — every analysis that needed it either wrote its own partial {@code switch} or, more
 * often, missed the expressions entirely.  Two node kinds are the classic blind spot,
 * because they carry expressions that are <em>not</em> relational children and therefore
 * do not show up in any structural traversal at all:
 * {@link RelationFunctionCall#arguments()} (a leaf, so {@code children()} is empty) and
 * {@link LateralJoinNode#arguments()} ({@code children()} reports only the left input).
 *
 * <p>Like {@code children()}, the implementation is an exhaustive {@code switch} over the
 * sealed hierarchy with no {@code default} arm, so a new {@code RelNode} does not
 * compile until its expressions are registered here.  That is the point: an analysis
 * built on this walker cannot silently go stale when the AST grows.
 *
 * <p>The walk comes in two forms — {@link #forEach} reads the expressions, and
 * {@link #map} rewrites them, returning the rebuilt node.  Both are exhaustive, so the
 * "which expressions does this node carry" and "how is this node rebuilt around new
 * expressions" knowledge sits in one place rather than being re-derived per consumer.
 *
 * <h2>What it does and does not recurse</h2>
 * <p>This is a <em>shallow</em> walk: it reports the expressions of {@code node} itself
 * and does not descend into its children.  Compose it with {@link RelNode#children()} to
 * walk a whole tree, and with {@link OperandWalker} to descend <em>within</em> a reported
 * operand or predicate:
 *
 * <pre>{@code
 * static void walkTree(RelNode node, Consumer<FunctionCall> onCall) {
 *     RelNodeOperands.forEach(node,
 *             operand   -> OperandWalker.walk(operand,   attr -> { }, onCall),
 *             predicate -> OperandWalker.walk(predicate, attr -> { }, onCall));
 *     node.children().forEach(child -> walkTree(child, onCall));
 * }
 * }</pre>
 *
 * <p>{@link EmptyRelationNode#heading()} is deliberately not reported.  It is an inert
 * carrier for a replaced sub-expression rather than something the node evaluates — the
 * same reason {@code children()} omits it.
 *
 * <p>This module carries no dependencies beyond the JDK, so the walker is safe to reuse
 * from any module that depends on the AST.
 */
public final class RelNodeOperands {

    private RelNodeOperands() {
    }

    /**
     * Reports every {@link Operand} and {@link Predicate} that {@code node} carries
     * directly, in no particular order.
     *
     * @param node        the node to inspect; must not be null
     * @param onOperand   invoked for each operand expression the node evaluates
     * @param onPredicate invoked for each predicate the node evaluates
     */
    public static void forEach(RelNode node,
                               Consumer<Operand> onOperand,
                               Consumer<Predicate> onPredicate) {
        switch (node) {
            // ── leaves and the purely structural operators ────────────────────────
            // No expressions of their own: a name, a position, or a set operation over
            // whole tuples.
            case TruthRelationNode ignored -> { }
            case EmptyRelationNode ignored -> { }
            case RecursiveRefNode ignored -> { }
            case DistinctNode ignored -> { }
            case WhyNode ignored -> { }
            case NaturalJoinNode ignored -> { }
            case ProductNode ignored -> { }
            case UnionNode ignored -> { }
            case UnionAllNode ignored -> { }
            case OuterUnionNode ignored -> { }
            case DifferenceNode ignored -> { }
            case IntersectionNode ignored -> { }
            case DivisionNode ignored -> { }
            case SymmetricDifferenceNode ignored -> { }
            case CompositionNode ignored -> { }
            case FixpointNode ignored -> { }

            // ── column-name-only operators ────────────────────────────────────────
            // Every field is a column name or a literal constant of the operator, not an
            // expression evaluated per row.
            case RenameNode ignored -> { }
            case UnnestNode ignored -> { }
            case ClusterNode ignored -> { }
            case PathNode ignored -> { }
            case LimitNode ignored -> { }
            case IntervalJoinNode ignored -> { }
            case CoverNode ignored -> { }
            case DownsampleNode ignored -> { }
            case UnpivotNode ignored -> { }
            case PivotNode ignored -> { }

            // ── operators carrying expressions ────────────────────────────────────
            case RelationNode n -> n.produceBound().ifPresent(b -> onOperand.accept(b.limit()));
            case RelationFunctionCall n -> n.arguments().forEach(onOperand);
            case LateralJoinNode n      -> n.arguments().forEach(onOperand);
            case SelectionNode n        -> onPredicate.accept(n.predicate());
            case UniversalNode n        -> onPredicate.accept(n.predicate());
            case ProjectionNode n ->
                    n.attributes().forEach(a -> onOperand.accept(a.expression()));
            case AggregationNode n -> {
                if (n.groupingKeys() != null) {
                    n.groupingKeys().forEach(k -> onOperand.accept(k.expression()));
                }
                for (AggregateFunction aggregate : n.aggregates()) {
                    onOperand.accept(aggregate.argument());
                    aggregate.yieldExpr().ifPresent(onOperand);
                }
            }
            case SortNode n -> n.sortSpecs().forEach(s -> onOperand.accept(s.expression()));
            case TopKNode n -> n.sortSpecs().forEach(s -> onOperand.accept(s.expression()));
            // TREE's fold is over column names, but its sibling ordering is a sort key
            // like any other — and a sort key is a full expression.
            case TreeNode n -> n.orderSpecs().forEach(s -> onOperand.accept(s.expression()));
            case ConditionalJoinNode n -> onPredicate.accept(n.condition());
            case AsOfJoinNode n -> {
                onPredicate.accept(n.condition());
                n.tolerance().ifPresent(onOperand);
            }
            case ClosureNode n -> {
                n.boundSource().ifPresent(onOperand);
                n.boundTarget().ifPresent(onOperand);
            }
            case TraceNode n -> {
                n.boundSource().ifPresent(onOperand);
                n.boundTarget().ifPresent(onOperand);
            }
            case SolveNode n -> {
                onOperand.accept(n.left());
                onOperand.accept(n.right());
            }
            case OptimizeNode n -> {
                onOperand.accept(n.objective());
                n.constraints().forEach(c -> onOperand.accept(c.expr()));
            }
            // A window carries expressions in two places: the function itself, and the
            // within-partition ordering.
            case WindowNode n -> {
                forEachWindowOperand(n.function(), onOperand);
                n.sortSpecs().forEach(s -> onOperand.accept(s.expression()));
            }
            // SESSIONIZE's gap threshold is an expression, unlike the rest of its fields.
            case SessionizeNode n -> onOperand.accept(n.threshold());

            // ── sampling ──────────────────────────────────────────────────────────
            // The probability / reservoir size are plain numbers on the node, not
            // expressions; the seed is a long. Nothing to report — but see
            // `usesSystemState`, which is where a sampling node's own volatility lives.
            case SampleNode ignored -> { }
            case ReservoirSampleNode ignored -> { }
        }
    }

    /**
     * Whether {@code node} produces a result that depends on state outside the query —
     * the clock, a random source — <em>ignoring</em> the expressions
     * {@link #forEach} reports and its children.
     *
     * <p>An unseeded {@code SAMPLE} / {@code RESERVOIR} draws from a fresh random source
     * on each execution, so running it twice over the same input can give two different
     * relations. A seeded one is reproducible and therefore not system-dependent.
     *
     * <p>Exhaustive over the sealed hierarchy for the same reason {@link #forEach} is: a
     * new node kind that reads system state must be classified here rather than
     * defaulting, silently, to "reproducible".
     *
     * @param node the node to classify; must not be null
     * @return {@code true} when the node itself reads system state
     */
    public static boolean usesSystemState(RelNode node) {
        return switch (node) {
            case SampleNode n          -> n.seed().isEmpty();
            case ReservoirSampleNode n -> n.seed().isEmpty();

            case RelationNode ignored -> false;
            case RelationFunctionCall ignored -> false;
            case TruthRelationNode ignored -> false;
            case EmptyRelationNode ignored -> false;
            case RecursiveRefNode ignored -> false;
            case ProjectionNode ignored -> false;
            case SelectionNode ignored -> false;
            case RenameNode ignored -> false;
            case AggregationNode ignored -> false;
            case SortNode ignored -> false;
            case LimitNode ignored -> false;
            case DistinctNode ignored -> false;
            case UnnestNode ignored -> false;
            case ClosureNode ignored -> false;
            case ClusterNode ignored -> false;
            case PathNode ignored -> false;
            case TraceNode ignored -> false;
            case UniversalNode ignored -> false;
            case SolveNode ignored -> false;
            case OptimizeNode ignored -> false;
            case TopKNode ignored -> false;
            case CoverNode ignored -> false;
            case DownsampleNode ignored -> false;
            case WindowNode ignored -> false;
            case SessionizeNode ignored -> false;
            case UnpivotNode ignored -> false;
            case PivotNode ignored -> false;
            case TreeNode ignored -> false;
            case WhyNode ignored -> false;
            case LateralJoinNode ignored -> false;
            case NaturalJoinNode ignored -> false;
            case ConditionalJoinNode ignored -> false;
            case AsOfJoinNode ignored -> false;
            case IntervalJoinNode ignored -> false;
            case ProductNode ignored -> false;
            case UnionNode ignored -> false;
            case UnionAllNode ignored -> false;
            case OuterUnionNode ignored -> false;
            case DifferenceNode ignored -> false;
            case IntersectionNode ignored -> false;
            case DivisionNode ignored -> false;
            case SymmetricDifferenceNode ignored -> false;
            case CompositionNode ignored -> false;
            case FixpointNode ignored -> false;
        };
    }

    // =========================================================================
    // Mapping form
    // =========================================================================

    /**
     * The rewriting counterpart of {@link #forEach}: replaces every {@link Operand} and
     * {@link Predicate} that {@code node} carries directly with the result of applying
     * {@code onOperand} / {@code onPredicate}, and returns the rebuilt node.
     *
     * <p>Like {@code forEach} this is <em>shallow</em> — the node's children are left
     * untouched.  Compose it with {@link RelNode#mapChildren} to rewrite a whole tree:
     *
     * <pre>{@code
     * static RelNode rewrite(RelNode node) {
     *     RelNode withChildren = node.mapChildren(RelNodeOperands::rewrite);
     *     return RelNodeOperands.map(withChildren, Simplifier::operand, Simplifier::predicate);
     * }
     * }</pre>
     *
     * <p>Following the same convention as {@code mapChildren}, {@code node} itself is
     * returned when every mapped expression comes back <em>identical by reference</em>,
     * so a caller can use reference inequality as a "something changed" signal.  A
     * mapping function that rebuilds an unchanged expression therefore defeats that
     * signal — return the argument unchanged when no rule fires.
     *
     * <p>Exhaustive over the sealed hierarchy with no {@code default} arm, for the same
     * reason {@code forEach} is.
     *
     * @param node        the node to rewrite; must not be null
     * @param onOperand   applied to each operand expression the node evaluates
     * @param onPredicate applied to each predicate the node evaluates
     * @return the rebuilt node, or {@code node} when no expression changed
     */
    public static RelNode map(RelNode node,
                              UnaryOperator<Operand> onOperand,
                              UnaryOperator<Predicate> onPredicate) {
        return switch (node) {
            // ── the nodes carrying no expressions: nothing to rewrite ─────────────
            case TruthRelationNode ignored -> node;
            case EmptyRelationNode ignored -> node;
            case RecursiveRefNode ignored -> node;
            case DistinctNode ignored -> node;
            case WhyNode ignored -> node;
            case NaturalJoinNode ignored -> node;
            case ProductNode ignored -> node;
            case UnionNode ignored -> node;
            case UnionAllNode ignored -> node;
            case OuterUnionNode ignored -> node;
            case DifferenceNode ignored -> node;
            case IntersectionNode ignored -> node;
            case DivisionNode ignored -> node;
            case SymmetricDifferenceNode ignored -> node;
            case CompositionNode ignored -> node;
            case FixpointNode ignored -> node;
            case RenameNode ignored -> node;
            case UnnestNode ignored -> node;
            case ClusterNode ignored -> node;
            case PathNode ignored -> node;
            case LimitNode ignored -> node;
            case IntervalJoinNode ignored -> node;
            case CoverNode ignored -> node;
            case DownsampleNode ignored -> node;
            case UnpivotNode ignored -> node;
            case PivotNode ignored -> node;
            case SampleNode ignored -> node;
            case ReservoirSampleNode ignored -> node;

            // ── operators carrying expressions ────────────────────────────────────
            case RelationNode n -> {
                if (n.produceBound().isEmpty()) {
                    yield n;
                }
                ProduceBound bound = n.produceBound().get();
                Operand limit = onOperand.apply(bound.limit());
                yield limit == bound.limit() ? n
                        : new RelationNode(n.name(), Optional.of(new ProduceBound(
                                bound.column(), bound.operator(), limit)), n.location());
            }
            case RelationFunctionCall n -> {
                List<Operand> args = mapList(n.arguments(), onOperand);
                yield args == n.arguments() ? n
                        : new RelationFunctionCall(n.functionName(), args, n.location());
            }
            case LateralJoinNode n -> {
                List<Operand> args = mapList(n.arguments(), onOperand);
                yield args == n.arguments() ? n
                        : new LateralJoinNode(n.left(), n.functionName(), args, n.location());
            }
            case SelectionNode n -> {
                Predicate p = onPredicate.apply(n.predicate());
                yield p == n.predicate() ? n : new SelectionNode(p, n.input(), n.location());
            }
            case UniversalNode n -> {
                Predicate p = onPredicate.apply(n.predicate());
                yield p == n.predicate() ? n
                        : new UniversalNode(n.groupingAttributes(), p, n.input(), n.location());
            }
            case ProjectionNode n -> {
                List<ProjectedAttribute> attrs = mapList(n.attributes(), a -> {
                    Operand e = onOperand.apply(a.expression());
                    return e == a.expression() ? a : new ProjectedAttribute(e, a.alias());
                });
                yield attrs == n.attributes() ? n
                        : new ProjectionNode(attrs, n.input(), n.location());
            }
            case AggregationNode n -> {
                List<GroupingKey> keys = n.groupingKeys() == null ? null
                        : mapList(n.groupingKeys(), k -> {
                            Operand e = onOperand.apply(k.expression());
                            return e == k.expression() ? k : new GroupingKey(e, k.alias());
                        });
                List<AggregateFunction> aggs = mapList(n.aggregates(), a -> {
                    Operand arg   = onOperand.apply(a.argument());
                    Optional<Operand> yieldExpr = mapOptional(a.yieldExpr(), onOperand);
                    return arg == a.argument() && yieldExpr == a.yieldExpr() ? a
                            : new AggregateFunction(a.operator(), arg, yieldExpr, a.alias());
                });
                yield keys == n.groupingKeys() && aggs == n.aggregates() ? n
                        : new AggregationNode(keys, aggs, n.input(), n.location());
            }
            case SortNode n -> {
                List<SortSpecification> specs = mapSortSpecs(n.sortSpecs(), onOperand);
                yield specs == n.sortSpecs() ? n
                        : new SortNode(specs, n.input(), n.location());
            }
            case TopKNode n -> {
                List<SortSpecification> specs = mapSortSpecs(n.sortSpecs(), onOperand);
                yield specs == n.sortSpecs() ? n
                        : new TopKNode(n.groupingAttributes(), specs, n.offset(), n.count(),
                                n.input(), n.location());
            }
            case TreeNode n -> {
                List<SortSpecification> specs = mapSortSpecs(n.orderSpecs(), onOperand);
                yield specs == n.orderSpecs() ? n
                        : new TreeNode(n.input(), n.keyColumn(), n.parentColumn(), specs,
                                n.childrenColumn(), n.location());
            }
            case ConditionalJoinNode n -> {
                Predicate c = onPredicate.apply(n.condition());
                yield c == n.condition() ? n : n.rebuild(n.left(), n.right(), c);
            }
            case AsOfJoinNode n -> {
                Predicate c = onPredicate.apply(n.condition());
                Optional<Operand> tolerance = mapOptional(n.tolerance(), onOperand);
                yield c == n.condition() && tolerance == n.tolerance() ? n
                        : new AsOfJoinNode(n.left(), n.right(), c, tolerance, n.inner(),
                                n.tieBreak(), n.location());
            }
            case ClosureNode n -> {
                Optional<Operand> source = mapOptional(n.boundSource(), onOperand);
                Optional<Operand> target = mapOptional(n.boundTarget(), onOperand);
                yield source == n.boundSource() && target == n.boundTarget() ? n
                        : new ClosureNode(n.input(), n.fromColumn(), n.toColumn(),
                                n.undirected(), n.reflexive(), source, target, n.location());
            }
            case TraceNode n -> {
                Optional<Operand> source = mapOptional(n.boundSource(), onOperand);
                Optional<Operand> target = mapOptional(n.boundTarget(), onOperand);
                yield source == n.boundSource() && target == n.boundTarget() ? n
                        : new TraceNode(n.input(), n.fromColumn(), n.toColumn(),
                                n.undirected(), n.weightColumn(), n.sense(), n.pathColumn(),
                                source, target, n.location());
            }
            case SolveNode n -> {
                Operand left  = onOperand.apply(n.left());
                Operand right = onOperand.apply(n.right());
                yield left == n.left() && right == n.right() ? n
                        : new SolveNode(left, right, n.input(), n.location());
            }
            case OptimizeNode n -> {
                Operand objective = onOperand.apply(n.objective());
                List<OptimizeConstraint> constraints = mapList(n.constraints(), c -> {
                    Operand e = onOperand.apply(c.expr());
                    return e == c.expr() ? c
                            : new OptimizeConstraint(e, c.op(), c.bound());
                });
                yield objective == n.objective() && constraints == n.constraints() ? n
                        : new OptimizeNode(n.sense(), objective, constraints,
                                n.groupingKeys(), n.allocation(), n.input(), n.location());
            }
            case WindowNode n -> {
                WindowFunction function = mapWindowFunction(n.function(), onOperand);
                List<SortSpecification> specs = mapSortSpecs(n.sortSpecs(), onOperand);
                yield function == n.function() && specs == n.sortSpecs() ? n
                        : new WindowNode(function, n.partitionKeys(), specs, n.frame(),
                                n.outputColumn(), n.input(), n.location());
            }
            case SessionizeNode n -> {
                Operand threshold = onOperand.apply(n.threshold());
                yield threshold == n.threshold() ? n
                        : new SessionizeNode(n.input(), n.orderColumn(), threshold,
                                n.partitionKeys(), n.sessionColumn(), n.location());
            }
        };
    }

    /** Maps a list element-wise, returning the original list when nothing changed. */
    private static <T> List<T> mapList(List<T> items, UnaryOperator<T> f) {
        List<T> mapped = null; // lazily allocated on first change
        for (int i = 0; i < items.size(); i++) {
            T item    = items.get(i);
            T rewrite = f.apply(item);
            if (rewrite != item) {
                if (mapped == null) {
                    mapped = new ArrayList<>(items);
                }
                mapped.set(i, rewrite);
            }
        }
        return mapped == null ? items : List.copyOf(mapped);
    }

    /** Maps an optional operand, returning the original {@code Optional} when unchanged. */
    private static Optional<Operand> mapOptional(Optional<Operand> operand,
                                                 UnaryOperator<Operand> f) {
        if (operand.isEmpty()) {
            return operand;
        }
        Operand mapped = f.apply(operand.get());
        return mapped == operand.get() ? operand : Optional.of(mapped);
    }

    /** Maps the expression of each sort key, sharing {@link #mapList}'s convention. */
    private static List<SortSpecification> mapSortSpecs(List<SortSpecification> specs,
                                                        UnaryOperator<Operand> f) {
        return mapList(specs, s -> {
            Operand e = f.apply(s.expression());
            return e == s.expression() ? s : new SortSpecification(e, s.direction());
        });
    }

    /** The rewriting counterpart of {@link #forEachWindowOperand}. */
    private static WindowFunction mapWindowFunction(WindowFunction function,
                                                    UnaryOperator<Operand> f) {
        return switch (function) {
            case WindowFunction.AggregateWindow w -> {
                Operand argument = f.apply(w.argument());
                yield argument == w.argument() ? w
                        : new WindowFunction.AggregateWindow(w.operator(), argument);
            }
            case WindowFunction.RankingWindow w -> {
                Optional<Operand> count = mapOptional(w.ntileCount(), f);
                yield count == w.ntileCount() ? w
                        : new WindowFunction.RankingWindow(w.function(), count);
            }
            case WindowFunction.OffsetWindow w -> {
                Operand expression = f.apply(w.expression());
                Optional<Operand> offset  = mapOptional(w.offset(), f);
                Optional<Operand> fallback = mapOptional(w.defaultValue(), f);
                yield expression == w.expression() && offset == w.offset()
                        && fallback == w.defaultValue() ? w
                        : new WindowFunction.OffsetWindow(w.function(), expression,
                                offset, fallback);
            }
        };
    }

    /** The operand-bearing sub-cases of a window function. */
    private static void forEachWindowOperand(WindowFunction function,
                                             Consumer<Operand> onOperand) {
        switch (function) {
            case WindowFunction.AggregateWindow w -> onOperand.accept(w.argument());
            case WindowFunction.RankingWindow w   -> w.ntileCount().ifPresent(onOperand);
            case WindowFunction.OffsetWindow w -> {
                onOperand.accept(w.expression());
                w.offset().ifPresent(onOperand);
                w.defaultValue().ifPresent(onOperand);
            }
        }
    }
}

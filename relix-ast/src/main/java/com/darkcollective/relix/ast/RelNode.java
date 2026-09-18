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

import com.darkcollective.relix.ast.visitor.PrettyPrinter;
import com.darkcollective.relix.ast.visitor.RelNodeVisitor;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Root sealed interface for all relational algebra operation nodes.
 *
 * <p>Every node in the AST implements this interface and participates in the
 * visitor pattern via {@link #accept}. The sealed hierarchy is exhaustive —
 * all permitted implementations are listed in the {@code permits} clause —
 * so pattern matching and visitor dispatch are guaranteed to be complete at
 * compile time.
 *
 * <p>Use {@link #prettyPrint()} to serialise any node to a Unicode relational
 * algebra string.
 *
 * @see com.darkcollective.relix.ast.visitor.RelNodeVisitor
 */
public sealed interface RelNode permits
        RelationNode,
        RelationFunctionCall,
        TruthRelationNode,
        EmptyRelationNode,
        ProjectionNode,
        SelectionNode,
        RenameNode,
        NaturalJoinNode,
        ConditionalJoinNode,
        AsOfJoinNode,
        IntervalJoinNode,
        ProductNode,
        UnionNode,
        UnionAllNode,
        OuterUnionNode,
        DifferenceNode,
        IntersectionNode,
        DivisionNode,
        SymmetricDifferenceNode,
        CompositionNode,
        AggregationNode,
        SortNode,
        LimitNode,
        DistinctNode,
        UnnestNode,
        ClosureNode,
        ClusterNode,
        PathNode,
        UniversalNode,
        SampleNode,
        ReservoirSampleNode,
        SolveNode,
        OptimizeNode,
        TopKNode,
        FixpointNode,
        RecursiveRefNode,
        CoverNode,
        DownsampleNode,
        LateralJoinNode,
        WindowNode,
        SessionizeNode,
        TraceNode,
        UnpivotNode,
        PivotNode,
        TreeNode,
        WhyNode {
    <R> R accept(RelNodeVisitor<R> visitor);

    /** Returns the source location of the first token of this node. */
    SourceLocation location();

    /**
     * Returns the output materialisation mode of this node.
     *
     * <p>The default implementation derives the mode from the concrete node
     * type via an exhaustive pattern switch over the sealed hierarchy:
     *
     * <ul>
     *   <li>{@link MaterializationMode#SORTED} — {@link SortNode}</li>
     *   <li>{@link MaterializationMode#BAG} — {@link AggregationNode},
     *       {@link UniversalNode}, {@link UnionAllNode}, {@link DivisionNode},
     *       {@link FullOuterJoinNode}</li>
     *   <li>{@link MaterializationMode#SET} — {@link UnionNode},
     *       {@link OuterUnionNode}, {@link IntersectionNode},
     *       {@link DifferenceNode}, {@link SymmetricDifferenceNode}</li>
     *   <li>{@link MaterializationMode#STREAM} — all other nodes</li>
     * </ul>
     *
     * @return the materialisation mode; never null
     */
    default MaterializationMode materializationMode() {
        return switch (this) {
            case SortNode          ignored -> MaterializationMode.SORTED;
            case AggregationNode   ignored -> MaterializationMode.BAG;
            case UniversalNode     ignored -> MaterializationMode.BAG;
            case OptimizeNode      ignored -> MaterializationMode.BAG;
            case TopKNode          ignored -> MaterializationMode.BAG;
            case ReservoirSampleNode ignored -> MaterializationMode.BAG;
            case UnionAllNode      ignored -> MaterializationMode.BAG;
            case DivisionNode      ignored -> MaterializationMode.BAG;
            case FullOuterJoinNode ignored -> MaterializationMode.BAG;
            case UnionNode         ignored -> MaterializationMode.SET;
            case OuterUnionNode    ignored -> MaterializationMode.SET;
            case IntersectionNode  ignored -> MaterializationMode.SET;
            case DifferenceNode    ignored -> MaterializationMode.SET;
            case SymmetricDifferenceNode ignored -> MaterializationMode.SET;
            case ClosureNode       ignored -> MaterializationMode.SET;
            case ClusterNode       ignored -> MaterializationMode.SET;
            case PathNode          ignored -> MaterializationMode.SET;
            case TraceNode         ignored -> MaterializationMode.BAG;
            case FixpointNode      ignored -> MaterializationMode.SET;
            case CoverNode         ignored -> MaterializationMode.BAG;
            case DownsampleNode    ignored -> MaterializationMode.BAG;
            case WindowNode        ignored -> MaterializationMode.BAG;
            case SessionizeNode    ignored -> MaterializationMode.BAG;
            case PivotNode         ignored -> MaterializationMode.BAG;
            case TreeNode          ignored -> MaterializationMode.BAG;
            case WhyNode           ignored -> MaterializationMode.BAG;
            default                        -> MaterializationMode.STREAM;
        };
    }

    default String prettyPrint() {
        return accept(new PrettyPrinter());
    }

    /**
     * Returns this node's direct child sub-expressions, in left-to-right order.
     *
     * <p>Leaf nodes ({@link RelationNode}) return an empty list; unary operators
     * return their single input; binary operators return {@code [left, right]}.
     * This is the canonical structural-traversal accessor — prefer it over a
     * hand-written {@code switch} when walking a tree read-only.
     *
     * @return an immutable list of direct children; never null, possibly empty
     */
    default List<RelNode> children() {
        return switch (this) {
            case RelationNode ignored                -> List.of();
            case RelationFunctionCall ignored        -> List.of();  // args are operands, not relations
            case TruthRelationNode ignored           -> List.of();  // leaf — a nullary literal relation
            case EmptyRelationNode ignored           -> List.of();  // leaf — its heading is inert, not a child
            case RecursiveRefNode ignored            -> List.of();  // leaf — resolves to the enclosing FIX accumulator
            case ProjectionNode n      -> List.of(n.input());
            case SelectionNode n       -> List.of(n.input());
            case RenameNode n          -> List.of(n.input());
            case AggregationNode n     -> List.of(n.input());
            case SortNode n            -> List.of(n.input());
            case LimitNode n           -> List.of(n.input());
            case DistinctNode n        -> List.of(n.input());
            case UnnestNode n          -> List.of(n.input());
            case ClosureNode n         -> List.of(n.input());
            case ClusterNode n         -> List.of(n.input());
            case PathNode n            -> List.of(n.input());
            case TraceNode n           -> List.of(n.input());
            case UniversalNode n       -> List.of(n.input());
            case SampleNode n          -> List.of(n.input());
            case ReservoirSampleNode n -> List.of(n.input());
            case SolveNode n           -> List.of(n.input());
            case OptimizeNode n        -> List.of(n.input());
            case TopKNode n            -> List.of(n.input());
            case CoverNode n           -> List.of(n.input());
            case DownsampleNode n      -> List.of(n.input());
            case WindowNode n          -> List.of(n.input());
            case SessionizeNode n      -> List.of(n.input());
            case UnpivotNode n         -> List.of(n.input());
            case PivotNode n           -> List.of(n.input());
            case TreeNode n            -> List.of(n.input());
            case WhyNode n             -> List.of(n.input());
            case LateralJoinNode n     -> List.of(n.left()); // TVF call is not a relational child
            case NaturalJoinNode n     -> List.of(n.left(), n.right());
            case ConditionalJoinNode n -> List.of(n.left(), n.right());
            case AsOfJoinNode n            -> List.of(n.left(), n.right());
            case IntervalJoinNode n        -> List.of(n.left(), n.right());
            case ProductNode n         -> List.of(n.left(), n.right());
            case UnionNode n           -> List.of(n.left(), n.right());
            case UnionAllNode n        -> List.of(n.left(), n.right());
            case OuterUnionNode n      -> List.of(n.left(), n.right());
            case DifferenceNode n      -> List.of(n.left(), n.right());
            case IntersectionNode n    -> List.of(n.left(), n.right());
            case DivisionNode n        -> List.of(n.left(), n.right());
            case SymmetricDifferenceNode n -> List.of(n.left(), n.right());
            case CompositionNode n     -> List.of(n.left(), n.right());
            case FixpointNode n        -> List.of(n.base(), n.step());
        };
    }

    /**
     * Returns a copy of this node with each direct child replaced by the result
     * of applying {@code f} to it, preserving all other fields (predicates,
     * attributes, join conditions, source location, …).
     *
     * <p>As an optimization and a convenient no-op signal, this node is returned
     * <em>unchanged</em> (reference-identical) when {@code f} returns the same
     * reference for every child — so a tree rewrite can detect "nothing changed"
     * with a {@code ==} check.  This is the canonical structural-rewrite helper;
     * prefer it over a hand-written reconstruction {@code switch}.
     *
     * @param f the transformation to apply to each direct child; must not be null
     * @return the rewritten node, or {@code this} if no child changed
     */
    default RelNode mapChildren(UnaryOperator<RelNode> f) {
        return switch (this) {
            case RelationNode n -> n;   // leaf — no children
            case RelationFunctionCall n -> n;   // leaf — args are operands, no relation children
            case TruthRelationNode n -> n;  // leaf — a nullary literal relation
            case EmptyRelationNode n -> n;  // leaf — its heading is inert, not a child
            case RecursiveRefNode n -> n;   // leaf — resolves to the enclosing FIX accumulator

            case ProjectionNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n : new ProjectionNode(n.attributes(), in, n.location());
            }
            case SelectionNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n : new SelectionNode(n.predicate(), in, n.location());
            }
            case RenameNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n : n.withInput(in);
            }
            case AggregationNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new AggregationNode(n.groupingKeys(), n.aggregates(), in, n.location());
            }
            case SortNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n : new SortNode(n.sortSpecs(), in, n.location());
            }
            case LimitNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n : new LimitNode(n.offset(), n.count(), in, n.location());
            }
            case DistinctNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n : new DistinctNode(in, n.location());
            }
            case UnnestNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new UnnestNode(n.column(), n.outer(), n.ordinalityColumn(), in, n.location());
            }

            case ClosureNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new ClosureNode(in, n.fromColumn(), n.toColumn(),
                                          n.undirected(), n.reflexive(),
                                          n.boundSource(), n.boundTarget(),
                                          n.location());
            }
            case ClusterNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new ClusterNode(in, n.fromColumn(), n.toColumn(),
                                          n.labelColumn(), n.location());
            }
            case PathNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new PathNode(in, n.fromColumn(), n.toColumn(), n.undirected(),
                                       n.minHops(), n.maxHops(), n.depthColumn(), n.location());
            }
            case TraceNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new TraceNode(in, n.fromColumn(), n.toColumn(), n.undirected(),
                                        n.weightColumn(), n.sense(),
                                        n.pathColumn(), n.boundSource(), n.boundTarget(),
                                        n.location());
            }
            case UniversalNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new UniversalNode(n.groupingAttributes(), n.predicate(),
                                            in, n.location());
            }
            case SampleNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new SampleNode(n.probability(), n.seed(), in, n.location());
            }
            case ReservoirSampleNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new ReservoirSampleNode(n.count(), n.seed(), in, n.location());
            }
            case SolveNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new SolveNode(n.left(), n.right(), in, n.location());
            }
            case OptimizeNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new OptimizeNode(n.sense(), n.objective(), n.constraints(),
                                           n.groupingKeys(), n.allocation(), in, n.location());
            }
            case TopKNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new TopKNode(n.groupingAttributes(), n.sortSpecs(),
                                       n.offset(), n.count(), in, n.location());
            }
            case CoverNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n : new CoverNode(n.strength(), n.exact(), in, n.location());
            }
            case DownsampleNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new DownsampleNode(n.timestampColumn(), n.interval(), n.function(),
                                             n.groupingKeys(), n.maxRows(), in, n.location());
            }
            case WindowNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new WindowNode(n.function(), n.partitionKeys(), n.sortSpecs(),
                                         n.frame(), n.outputColumn(), in, n.location());
            }
            case SessionizeNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new SessionizeNode(in, n.orderColumn(), n.threshold(),
                                             n.partitionKeys(), n.sessionColumn(), n.location());
            }
            case UnpivotNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new UnpivotNode(n.columns(), n.nameColumn(), n.valueColumn(),
                                          in, n.location());
            }
            case PivotNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new PivotNode(n.valueColumn(), n.keyColumn(), n.groupKeys(),
                                        in, n.location());
            }
            case TreeNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n
                        : new TreeNode(in, n.keyColumn(), n.parentColumn(),
                                       n.orderSpecs(), n.childrenColumn(), n.location());
            }
            case WhyNode n -> {
                RelNode in = f.apply(n.input());
                yield in == n.input() ? n : new WhyNode(in, n.location());
            }
            case LateralJoinNode n -> {
                RelNode l = f.apply(n.left());
                yield l == n.left() ? n
                        : new LateralJoinNode(l, n.functionName(), n.arguments(), n.location());
            }

            case NaturalJoinNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n : new NaturalJoinNode(l, r, n.location());
            }
            case ConditionalJoinNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n : n.rebuild(l, r, n.condition());
            }
            case AsOfJoinNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n
                        : new AsOfJoinNode(l, r, n.condition(), n.tolerance(), n.inner(), n.tieBreak(), n.location());
            }
            case IntervalJoinNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n
                        : new IntervalJoinNode(l, r, n.relation(), n.leftStart(), n.leftEnd(),
                                               n.rightStart(), n.rightEnd(), n.location());
            }
            case ProductNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n : new ProductNode(l, r, n.location());
            }
            case UnionNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n : new UnionNode(l, r, n.location());
            }
            case UnionAllNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n : new UnionAllNode(l, r, n.location());
            }
            case OuterUnionNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n : new OuterUnionNode(l, r, n.location());
            }
            case DifferenceNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n : new DifferenceNode(l, r, n.location());
            }
            case IntersectionNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n : new IntersectionNode(l, r, n.location());
            }
            case DivisionNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n : new DivisionNode(l, r, n.location());
            }
            case SymmetricDifferenceNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n
                        : new SymmetricDifferenceNode(l, r, n.location());
            }
            case CompositionNode n -> {
                RelNode l = f.apply(n.left()), r = f.apply(n.right());
                yield (l == n.left() && r == n.right()) ? n
                        : new CompositionNode(l, r, n.location());
            }
            case FixpointNode n -> {
                RelNode base = f.apply(n.base()), step = f.apply(n.step());
                yield (base == n.base() && step == n.step()) ? n
                        : new FixpointNode(n.name(), base, step, n.location());
            }
        };
    }
}

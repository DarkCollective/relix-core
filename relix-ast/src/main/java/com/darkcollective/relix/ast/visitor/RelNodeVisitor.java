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
package com.darkcollective.relix.ast.visitor;

import com.darkcollective.relix.ast.*;

/**
 * Visitor over the {@link com.darkcollective.relix.ast.RelNode} sealed hierarchy.
 *
 * <p>Implement this interface to add a new traversal or transformation over
 * relational operation nodes without modifying the node classes themselves.
 * Because {@link com.darkcollective.relix.ast.RelNode} is sealed and exhaustive,
 * every permitted node type has a dedicated {@code visit} overload — the compiler
 * will flag any missing implementation.
 *
 * @param <R> the return type produced by each visit method
 * @see com.darkcollective.relix.ast.RelNode#accept(RelNodeVisitor)
 * @see PrettyPrinter
 */
public interface RelNodeVisitor<R> {
    /** Visits a base relation reference. */
    R visit(RelationNode node);
    /** Visits a table-valued (relation-returning) function call. */
    R visit(RelationFunctionCall node);
    /** Visits a nullary truth-relation literal (UNIT/DEE or EMPTY/DUM). */
    R visit(TruthRelationNode node);
    /** Visits an empty relation carrying another expression's heading (∅). */
    R visit(EmptyRelationNode node);
    /** Visits a projection (π) node. */
    R visit(ProjectionNode node);
    /** Visits a selection (σ) node. */
    R visit(SelectionNode node);
    /** Visits a rename (ρ) node. */
    R visit(RenameNode node);
    /** Visits a natural join (⋈) node. */
    R visit(NaturalJoinNode node);
    /** Visits a theta join (⨝) node. */
    R visit(ThetaJoinNode node);
    /** Visits a left outer join (⟕) node. */
    R visit(LeftOuterJoinNode node);
    /** Visits a right outer join (⟖) node. */
    R visit(RightOuterJoinNode node);
    /** Visits a full outer join (⟗) node. */
    R visit(FullOuterJoinNode node);
    /** Visits a semi-join (⋉) node. */
    R visit(SemiJoinNode node);
    /** Visits an anti-join (▷) node. */
    R visit(AntiJoinNode node);
    /** Visits a pairwise-universal semi-join (USEMI) node. */
    R visit(PairwiseUniversalNode node);
    /** Visits an AS-OF temporal join (ASOF) node. */
    R visit(AsOfJoinNode node);
    /** Visits an interval join (IJOIN) node. */
    R visit(IntervalJoinNode node);
    /** Visits a Cartesian product (×) node. */
    R visit(ProductNode node);
    /** Visits a set union (∪) node. */
    R visit(UnionNode node);
    /** Visits a multiset union (⊎) node. */
    R visit(UnionAllNode node);
    /** Visits an outer-union (⊔) node. */
    R visit(OuterUnionNode node);
    /** Visits a set difference (−) node. */
    R visit(DifferenceNode node);
    /** Visits a set intersection (∩) node. */
    R visit(IntersectionNode node);
    /** Visits a relational division (÷) node. */
    R visit(DivisionNode node);
    /** Visits a symmetric-difference (∆) node. */
    R visit(SymmetricDifferenceNode node);
    /** Visits a relational composition (∘) node. */
    R visit(CompositionNode node);
    /** Visits an aggregation (γ) node. */
    R visit(AggregationNode node);
    /** Visits a sort (τ) node. */
    R visit(SortNode node);
    /** Visits a limit (λ) node. */
    R visit(LimitNode node);
    /** Visits a distinct (δ) node. */
    R visit(DistinctNode node);
    /** Visits an unnest (μ) node. */
    R visit(UnnestNode node);
    /** Visits a transitive-closure (CLOSURE/RCLOSURE) node. */
    R visit(ClosureNode node);
    /** Visits a connected-components (CLUSTER) node. */
    R visit(ClusterNode node);
    /** Visits a bounded variable-length path (PATH) node. */
    R visit(PathNode node);
    /** Visits an optimal-path extraction (TRACE) node. */
    R visit(TraceNode node);
    /** Visits a universal-quantification (∀) node. */
    R visit(UniversalNode node);
    /** Visits a Bernoulli-sampling (SAMPLE) node. */
    R visit(SampleNode node);
    /** Visits a reservoir (fixed-count) sampling (SAMPLE … ROWS) node. */
    R visit(ReservoirSampleNode node);
    /** Visits a goal-seek (SOLVE) node. */
    R visit(SolveNode node);
    /** Visits a declarative-optimisation (OPTIMIZE) node. */
    R visit(OptimizeNode node);
    /** Visits a top-k-per-group (TOP) node. */
    R visit(TopKNode node);

    /** Visits a time-series downsampling (DOWNSAMPLE) node. */
    R visit(DownsampleNode node);

    /** Visits a window (ROLLING / WINDOW) node. */
    R visit(WindowNode node);

    /** Visits a gap-and-island / sessionization (SESSIONIZE) node. */
    R visit(SessionizeNode node);

    /** Visits a column-folding (UNPIVOT) node. */
    R visit(UnpivotNode node);

    /** Visits a row-pivoting (PIVOT) node. */
    R visit(PivotNode node);

    /** Visits an adjacency-to-forest nesting (TREE) node. */
    R visit(TreeNode node);

    /**
     * Visits a covering-reduction ({@code COVER}) node.
     *
     * <p>A {@code default} that throws rather than an abstract method: a visitor
     * with no meaningful answer for a covering reduction inherits the failure
     * instead of being forced to carry a stub. Visitors that do handle it —
     * schema inference, validation, the executor — override this method.
     */
    default R visit(CoverNode node) {
        throw new UnsupportedOperationException(
                "CoverNode (COVER) handling is not implemented in this visitor");
    }

    /**
     * Visits a general-recursion fixpoint ({@code FIX}) node.
     *
     * <p>A {@code default} that throws rather than an abstract method: a visitor
     * with no meaningful answer for a least fixpoint inherits the failure instead
     * of being forced to carry a stub. Visitors that do handle it — schema
     * inference, validation, the semi-naïve executor — override this method.
     */
    default R visit(FixpointNode node) {
        throw new UnsupportedOperationException(
                "FixpointNode (FIX) handling is not implemented in this visitor");
    }

    /**
     * Visits a recursive-reference ({@code FIX}-bound name) node.
     *
     * <p>Provided as a {@code default} that throws — see {@link #visit(FixpointNode)}.
     */
    default R visit(RecursiveRefNode node) {
        throw new UnsupportedOperationException(
                "RecursiveRefNode handling is not implemented in this visitor");
    }

    /**
     * Visits a lateral (correlated) table-valued function join node.
     *
     * <p>A {@code default} that throws rather than an abstract method: a visitor
     * with no meaningful answer for a correlated TVF join inherits the failure
     * instead of being forced to carry a stub. Visitors that do handle it —
     * schema inference, validation, the executor — override this method.
     */
    default R visit(LateralJoinNode node) {
        throw new UnsupportedOperationException(
                "LateralJoinNode (LATERAL) handling is not implemented in this visitor");
    }

    /**
     * Visits a lineage-reification ({@code WHY}) node.
     *
     * <p>A {@code default} that throws rather than an abstract method: a visitor
     * with no meaningful answer for a lineage reification inherits the failure
     * instead of being forced to carry a stub. Visitors that do handle it —
     * schema inference, validation, cost, planning, execution — override this
     * method.
     */
    default R visit(WhyNode node) {
        throw new UnsupportedOperationException(
                "WhyNode (WHY) handling is not implemented in this visitor");
    }
}

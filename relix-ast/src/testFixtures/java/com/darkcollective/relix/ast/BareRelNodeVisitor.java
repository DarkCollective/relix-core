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

import com.darkcollective.relix.ast.visitor.RelNodeVisitor;

/**
 * A {@link RelNodeVisitor} that implements every <em>abstract</em> arm and overrides
 * none of the {@code default} ones.
 *
 * <p>It exists to make the throwing defaults testable in isolation. {@code RelNodeVisitor}
 * declares five arms as {@code default}s that throw — {@code COVER}, {@code FIX}, a
 * {@code FIX}-bound reference, {@code LATERAL} and {@code WHY} — so a visitor with no
 * meaningful answer for one of them inherits the failure rather than carrying a stub.
 * Reaching that inherited failure from a test otherwise means writing out all
 * 47 abstract arms by hand, which is why it was only ever done once.
 *
 * <p>Every implemented arm throws too, with the node kind in the message: a test that
 * lands on the wrong arm should say so rather than return a quiet {@code null}.
 *
 * @param <R> the visitor's result type
 */
public abstract class BareRelNodeVisitor<R> implements RelNodeVisitor<R> {

    /** Signals that a test reached an arm it did not mean to. */
    private R unhandled(String kind) {
        throw new AssertionError("unexpected visit(" + kind + ")");
    }

    @Override public R visit(RelationNode node) { return unhandled("RelationNode"); }
    @Override public R visit(RelationFunctionCall node) { return unhandled("RelationFunctionCall"); }
    @Override public R visit(TruthRelationNode node) { return unhandled("TruthRelationNode"); }
    @Override public R visit(EmptyRelationNode node) { return unhandled("EmptyRelationNode"); }
    @Override public R visit(ProjectionNode node) { return unhandled("ProjectionNode"); }
    @Override public R visit(SelectionNode node) { return unhandled("SelectionNode"); }
    @Override public R visit(RenameNode node) { return unhandled("RenameNode"); }
    @Override public R visit(NaturalJoinNode node) { return unhandled("NaturalJoinNode"); }
    @Override public R visit(ThetaJoinNode node) { return unhandled("ThetaJoinNode"); }
    @Override public R visit(LeftOuterJoinNode node) { return unhandled("LeftOuterJoinNode"); }
    @Override public R visit(RightOuterJoinNode node) { return unhandled("RightOuterJoinNode"); }
    @Override public R visit(FullOuterJoinNode node) { return unhandled("FullOuterJoinNode"); }
    @Override public R visit(SemiJoinNode node) { return unhandled("SemiJoinNode"); }
    @Override public R visit(AntiJoinNode node) { return unhandled("AntiJoinNode"); }
    @Override public R visit(PairwiseUniversalNode node) { return unhandled("PairwiseUniversalNode"); }
    @Override public R visit(AsOfJoinNode node) { return unhandled("AsOfJoinNode"); }
    @Override public R visit(IntervalJoinNode node) { return unhandled("IntervalJoinNode"); }
    @Override public R visit(ProductNode node) { return unhandled("ProductNode"); }
    @Override public R visit(UnionNode node) { return unhandled("UnionNode"); }
    @Override public R visit(UnionAllNode node) { return unhandled("UnionAllNode"); }
    @Override public R visit(OuterUnionNode node) { return unhandled("OuterUnionNode"); }
    @Override public R visit(DifferenceNode node) { return unhandled("DifferenceNode"); }
    @Override public R visit(IntersectionNode node) { return unhandled("IntersectionNode"); }
    @Override public R visit(DivisionNode node) { return unhandled("DivisionNode"); }
    @Override public R visit(SymmetricDifferenceNode node) { return unhandled("SymmetricDifferenceNode"); }
    @Override public R visit(CompositionNode node) { return unhandled("CompositionNode"); }
    @Override public R visit(AggregationNode node) { return unhandled("AggregationNode"); }
    @Override public R visit(SortNode node) { return unhandled("SortNode"); }
    @Override public R visit(LimitNode node) { return unhandled("LimitNode"); }
    @Override public R visit(DistinctNode node) { return unhandled("DistinctNode"); }
    @Override public R visit(UnnestNode node) { return unhandled("UnnestNode"); }
    @Override public R visit(ClosureNode node) { return unhandled("ClosureNode"); }
    @Override public R visit(ClusterNode node) { return unhandled("ClusterNode"); }
    @Override public R visit(PathNode node) { return unhandled("PathNode"); }
    @Override public R visit(TraceNode node) { return unhandled("TraceNode"); }
    @Override public R visit(UniversalNode node) { return unhandled("UniversalNode"); }
    @Override public R visit(SampleNode node) { return unhandled("SampleNode"); }
    @Override public R visit(ReservoirSampleNode node) { return unhandled("ReservoirSampleNode"); }
    @Override public R visit(SolveNode node) { return unhandled("SolveNode"); }
    @Override public R visit(OptimizeNode node) { return unhandled("OptimizeNode"); }
    @Override public R visit(TopKNode node) { return unhandled("TopKNode"); }
    @Override public R visit(DownsampleNode node) { return unhandled("DownsampleNode"); }
    @Override public R visit(WindowNode node) { return unhandled("WindowNode"); }
    @Override public R visit(SessionizeNode node) { return unhandled("SessionizeNode"); }
    @Override public R visit(UnpivotNode node) { return unhandled("UnpivotNode"); }
    @Override public R visit(PivotNode node) { return unhandled("PivotNode"); }
    @Override public R visit(TreeNode node) { return unhandled("TreeNode"); }
}

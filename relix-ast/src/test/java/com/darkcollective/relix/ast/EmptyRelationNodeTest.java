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

import com.darkcollective.relix.ast.internal.AstEquivalence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link EmptyRelationNode}.
 */
@DisplayName("EmptyRelationNode")
final class EmptyRelationNodeTest {

    private static final SourceLocation LOC = new SourceLocation("q.relix", 4, 9);

    @Test
    @DisplayName("of() adopts the heading's source location")
    void ofAdoptsLocation() {
        RelNode heading = new RelationNode("R", LOC);
        EmptyRelationNode node = EmptyRelationNode.of(heading);
        assertThat(node.heading()).isSameAs(heading);
        assertThat(node.location()).isEqualTo(LOC);
    }

    @Test
    @DisplayName("both components are required")
    void componentsRequired() {
        assertThatThrownBy(() -> new EmptyRelationNode(null, LOC))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EmptyRelationNode(new RelationNode("R"), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EmptyRelationNode.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("it is a LEAF — the heading is a component, not a child")
    void headingIsNotAChild() {
        RelNode heading = new SelectionNode(
                new ComparisonPredicate(new AttributeOperand("x"),
                        ComparisonOperator.GREATER, new NumberOperand("5")),
                new RelationNode("R"));
        EmptyRelationNode node = EmptyRelationNode.of(heading);

        assertThat(node.children()).isEmpty();
        // mapChildren must not reach the heading: a rewrite that changed the carried
        // expression's schema would silently change this node's heading.
        assertThat(node.mapChildren(unused -> new RelationNode("Other"))).isSameAs(node);
    }

    @Test
    @DisplayName("it streams — there is nothing to buffer")
    void streams() {
        assertThat(EmptyRelationNode.of(new RelationNode("R")).materializationMode())
                .isEqualTo(MaterializationMode.STREAM);
    }

    @Test
    @DisplayName("it pretty-prints as ∅⟨heading⟩, injectively in the heading")
    void prettyPrints() {
        assertThat(EmptyRelationNode.of(new RelationNode("R")).prettyPrint())
                .isEqualTo("∅⟨R⟩");
        assertThat(EmptyRelationNode.of(new RelationNode("S")).prettyPrint())
                .isNotEqualTo(EmptyRelationNode.of(new RelationNode("R")).prettyPrint());
    }

    @Test
    @DisplayName("accept() dispatches to the visitor's own arm")
    void acceptDispatches() {
        var visitor = new RelNodeVisitorStub();
        assertThat(EmptyRelationNode.of(new RelationNode("R")).accept(visitor)).isEqualTo("empty");
    }

    @Test
    @DisplayName("two empty relations over the same heading are structurally equivalent")
    void structurallyEquivalent() {
        RelNode a = EmptyRelationNode.of(new RelationNode("R", LOC));
        RelNode b = EmptyRelationNode.of(new RelationNode("R", new SourceLocation("q.relix", 9, 1)));
        assertThat(a).isNotEqualTo(b);
        assertThat(AstEquivalence.equivalent(a, b)).isTrue();
    }

    /** A visitor that answers only the arm under test; the rest are unreachable here. */
    private static final class RelNodeVisitorStub
            implements com.darkcollective.relix.ast.visitor.RelNodeVisitor<String> {

        @Override public String visit(EmptyRelationNode node) { return "empty"; }

        // ── every other arm ────────────────────────────────────────────────
        @Override public String visit(RelationNode n) { return other(); }
        @Override public String visit(RelationFunctionCall n) { return other(); }
        @Override public String visit(TruthRelationNode n) { return other(); }
        @Override public String visit(ProjectionNode n) { return other(); }
        @Override public String visit(SelectionNode n) { return other(); }
        @Override public String visit(RenameNode n) { return other(); }
        @Override public String visit(NaturalJoinNode n) { return other(); }
        @Override public String visit(ThetaJoinNode n) { return other(); }
        @Override public String visit(LeftOuterJoinNode n) { return other(); }
        @Override public String visit(RightOuterJoinNode n) { return other(); }
        @Override public String visit(FullOuterJoinNode n) { return other(); }
        @Override public String visit(SemiJoinNode n) { return other(); }
        @Override public String visit(AntiJoinNode n) { return other(); }
        @Override public String visit(PairwiseUniversalNode n) { return other(); }
        @Override public String visit(AsOfJoinNode n) { return other(); }
        @Override public String visit(IntervalJoinNode n) { return other(); }
        @Override public String visit(LateralJoinNode n) { return other(); }
        @Override public String visit(ProductNode n) { return other(); }
        @Override public String visit(UnionNode n) { return other(); }
        @Override public String visit(UnionAllNode n) { return other(); }
        @Override public String visit(OuterUnionNode n) { return other(); }
        @Override public String visit(DifferenceNode n) { return other(); }
        @Override public String visit(IntersectionNode n) { return other(); }
        @Override public String visit(DivisionNode n) { return other(); }
        @Override public String visit(SymmetricDifferenceNode n) { return other(); }
        @Override public String visit(CompositionNode n) { return other(); }
        @Override public String visit(AggregationNode n) { return other(); }
        @Override public String visit(SortNode n) { return other(); }
        @Override public String visit(LimitNode n) { return other(); }
        @Override public String visit(DistinctNode n) { return other(); }
        @Override public String visit(UnnestNode n) { return other(); }
        @Override public String visit(ClosureNode n) { return other(); }
        @Override public String visit(ClusterNode n) { return other(); }
        @Override public String visit(PathNode n) { return other(); }
        @Override public String visit(TraceNode n) { return other(); }
        @Override public String visit(UniversalNode n) { return other(); }
        @Override public String visit(SampleNode n) { return other(); }
        @Override public String visit(ReservoirSampleNode n) { return other(); }
        @Override public String visit(SolveNode n) { return other(); }
        @Override public String visit(OptimizeNode n) { return other(); }
        @Override public String visit(TopKNode n) { return other(); }
        @Override public String visit(FixpointNode n) { return other(); }
        @Override public String visit(RecursiveRefNode n) { return other(); }
        @Override public String visit(CoverNode n) { return other(); }
        @Override public String visit(DownsampleNode n) { return other(); }
        @Override public String visit(WindowNode n) { return other(); }
        @Override public String visit(SessionizeNode n) { return other(); }
        @Override public String visit(UnpivotNode n) { return other(); }
        @Override public String visit(PivotNode n) { return other(); }
        @Override public String visit(TreeNode n) { return other(); }
        @Override public String visit(WhyNode n) { return other(); }

        private static String other() {
            throw new AssertionError("unexpected visit arm");
        }
    }
}

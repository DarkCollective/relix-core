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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.ast.AstAssertions.assertThat;

/**
 * Tests for the structural-traversal helpers on {@link RelNode}:
 * {@link RelNode#children()} and {@link RelNode#mapChildren}.
 */
@DisplayName("RelNode — children() and mapChildren()")
final class RelNodeTraversalTest extends AstTestSupport {

    private static final RelationNode A = rel("A");
    private static final RelationNode B = rel("B");
    private static final RelationNode C = rel("C");
    private static final Predicate PRED = cmp(attr("x"), ComparisonOperator.EQUAL, attr("y"));

    @Nested
    @DisplayName("children()")
    class Children {

        @Test
        @DisplayName("a leaf relation has no children")
        void leafHasNoChildren() {
            assertThat(A.children()).isEmpty();
        }

        @Test
        @DisplayName("a unary operator exposes its single input")
        void unaryExposesInput() {
            assertThat(select(PRED, A).children()).containsExactly(A);
            assertThat(project(List.of(projected(attr("x"))), A).children())
                    .containsExactly(A);
        }

        @Test
        @DisplayName("a binary operator exposes left then right")
        void binaryExposesBothSides() {
            assertThat(join(A, B, PRED).children()).containsExactly(A, B);
            assertThat(product(A, B).children()).containsExactly(A, B);
        }

        @Test
        @DisplayName("symmetric difference and composition expose left then right")
        void newBinaryNodesExposeBothSides() {
            assertThat(symmetricDifference(A, B).children()).containsExactly(A, B);
            assertThat(composition(A, B).children()).containsExactly(A, B);
        }

        @Test
        @DisplayName("universal quantification exposes its single input")
        void universalExposesInput() {
            assertThat(universal(List.of("k"), PRED, A).children()).containsExactly(A);
        }

        @Test
        @DisplayName("sample exposes its single input")
        void sampleExposesInput() {
            assertThat(sample(0.1, A).children()).containsExactly(A);
        }

        @Test
        @DisplayName("reservoir sample exposes its single input")
        void reservoirSampleExposesInput() {
            assertThat(reservoirSample(10, A).children()).containsExactly(A);
        }

        @Test
        @DisplayName("top-k exposes its single input")
        void topKExposesInput() {
            var node = topK(List.of("k"),
                    List.of(desc("v")), 3, A);
            assertThat(node.children()).containsExactly(A);
        }
    }

    @Nested
    @DisplayName("mapChildren()")
    class MapChildren {

        @Test
        @DisplayName("a leaf is returned unchanged")
        void leafUnchanged() {
            assertThat(A.mapChildren(UnaryOperator.identity())).isSameAs(A);
        }

        @Test
        @DisplayName("the identity transform returns the same instance (no-op signal)")
        void identityReturnsSameInstance() {
            List<RelNode> samples = List.of(
                    A,
                    select(PRED, A),
                    project(List.of(projected(attr("x"))), A),
                    join(A, B, PRED),
                    product(A, B));
            for (RelNode node : samples) {
                assertThat(node.mapChildren(UnaryOperator.identity()))
                        .as("identity over %s", node.getClass().getSimpleName())
                        .isSameAs(node);
            }
        }

        @Test
        @DisplayName("a unary node is rebuilt with the replaced input, preserving other fields")
        void unaryRebuilt() {
            SelectionNode sel = select(PRED, A);
            RelNode result = sel.mapChildren(child -> child == A ? B : child);

            SelectionNode rebuilt = assertThat(result).asNode(SelectionNode.class);
            assertThat(rebuilt.input()).isSameAs(B);
            assertThat(rebuilt.predicate()).isEqualTo(PRED);   // field preserved
            assertThat(sel.input()).isSameAs(A);               // original unchanged
        }

        @Test
        @DisplayName("a binary node replaces only the changed side and preserves the condition")
        void binaryReplacesOneSide() {
            ThetaJoinNode join = join(A, B, PRED);
            RelNode result = join.mapChildren(child -> child == A ? C : child);

            ThetaJoinNode rebuilt = assertThat(result).asNode(ThetaJoinNode.class);
            assertThat(rebuilt.left()).isSameAs(C);
            assertThat(rebuilt.right()).isSameAs(B);           // unchanged side kept
            assertThat(rebuilt.condition()).isEqualTo(PRED);   // condition preserved
        }

        @Test
        @DisplayName("symmetric difference and composition rebuild with the replaced side")
        void newBinaryNodesRebuild() {
            RelNode sd = symmetricDifference(A, B).mapChildren(c -> c == A ? C : c);
            assertThat(sd).isNode(SymmetricDifferenceNode.class);
            assertThat(((SymmetricDifferenceNode) sd).left()).isSameAs(C);
            assertThat(((SymmetricDifferenceNode) sd).right()).isSameAs(B);
            SymmetricDifferenceNode sdNode = symmetricDifference(A, B);
            assertThat(sdNode.mapChildren(UnaryOperator.identity())).isSameAs(sdNode);

            RelNode comp = composition(A, B).mapChildren(c -> c == B ? C : c);
            assertThat(comp).isNode(CompositionNode.class);
            assertThat(((CompositionNode) comp).left()).isSameAs(A);
            assertThat(((CompositionNode) comp).right()).isSameAs(C);
            CompositionNode compNode = composition(A, B);
            assertThat(compNode.mapChildren(UnaryOperator.identity())).isSameAs(compNode);
        }

        @Test
        @DisplayName("universal quantification rebuilds with the replaced input, preserving keys + predicate")
        void universalRebuilds() {
            UniversalNode node = universal(List.of("k"), PRED, A);
            RelNode result = node.mapChildren(child -> child == A ? B : child);

            UniversalNode rebuilt = assertThat(result).asNode(UniversalNode.class);
            assertThat(rebuilt.input()).isSameAs(B);
            assertThat(rebuilt.groupingAttributes()).containsExactly("k");
            assertThat(rebuilt.predicate()).isEqualTo(PRED);
            assertThat(node.mapChildren(UnaryOperator.identity())).isSameAs(node);
        }

        @Test
        @DisplayName("sample rebuilds with the replaced input, preserving the probability")
        void sampleRebuilds() {
            SampleNode node = sample(0.3, A);
            RelNode result = node.mapChildren(child -> child == A ? B : child);

            assertThat(result).isNode(SampleNode.class);
            assertThat(((SampleNode) result).input()).isSameAs(B);
            assertThat(((SampleNode) result).probability()).isEqualTo(0.3);
            assertThat(node.mapChildren(UnaryOperator.identity())).isSameAs(node);
        }

        @Test
        @DisplayName("reservoir sample rebuilds with the replaced input, preserving the count")
        void reservoirSampleRebuilds() {
            ReservoirSampleNode node = reservoirSample(7, A);
            RelNode result = node.mapChildren(child -> child == A ? B : child);

            assertThat(result).isNode(ReservoirSampleNode.class);
            assertThat(((ReservoirSampleNode) result).input()).isSameAs(B);
            assertThat(((ReservoirSampleNode) result).count()).isEqualTo(7L);
            assertThat(node.mapChildren(UnaryOperator.identity())).isSameAs(node);
        }

        @Test
        @DisplayName("top-k rebuilds with the replaced input, preserving keys/sort/count")
        void topKRebuilds() {
            var node = topK(List.of("k"),
                    List.of(desc("v")), 3, A);
            RelNode result = node.mapChildren(child -> child == A ? B : child);

            TopKNode rebuilt = assertThat(result).asNode(TopKNode.class);
            assertThat(rebuilt.input()).isSameAs(B);
            assertThat(rebuilt.groupingAttributes()).containsExactly("k");
            assertThat(rebuilt.count()).isEqualTo(3L);
            assertThat(rebuilt.sortSpecs()).hasSize(1);
            assertThat(node.mapChildren(UnaryOperator.identity())).isSameAs(node);
        }
    }

    @Nested
    @DisplayName("materializationMode()")
    class MaterializationModes {

        @Test
        @DisplayName("symmetric difference materialises a SET; composition streams")
        void newBinaryNodeModes() {
            assertThat(symmetricDifference(A, B).materializationMode())
                    .isEqualTo(MaterializationMode.SET);
            assertThat(composition(A, B).materializationMode())
                    .isEqualTo(MaterializationMode.STREAM);
        }

        @Test
        @DisplayName("universal quantification materialises a BAG (it groups)")
        void universalMode() {
            assertThat(universal(List.of("k"), PRED, A).materializationMode())
                    .isEqualTo(MaterializationMode.BAG);
        }

        @Test
        @DisplayName("top-k materialises a BAG (it groups and sorts)")
        void topKMode() {
            var node = topK(List.of("k"),
                    List.of(desc("v")), 3, A);
            assertThat(node.materializationMode()).isEqualTo(MaterializationMode.BAG);
        }

        @Test
        @DisplayName("reservoir sample materialises a BAG (it buffers a reservoir)")
        void reservoirSampleMode() {
            assertThat(reservoirSample(10, A).materializationMode())
                    .isEqualTo(MaterializationMode.BAG);
        }
    }
}

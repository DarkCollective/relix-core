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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constructor validation tests for relation and join AST nodes:
 * {@link RelationNode}, {@link NaturalJoinNode}, {@link ThetaJoinNode},
 * {@link LeftOuterJoinNode}, {@link RightOuterJoinNode}, {@link FullOuterJoinNode},
 * {@link SemiJoinNode}, {@link AntiJoinNode}, and set-operation nodes.
 */
@DisplayName("AST — relation, join, and set-operation node validation")
final class AstNodeRelationTest extends AstTestSupport {

    // =========================================================================
    // RelationNode
    // =========================================================================

    @Nested
    @DisplayName("RelationNode Validations")
    class RelationNodeValidation {

        @Test
        @DisplayName("Rejects null relation name")
        void rejectsNullRelationName() {
            assertThatThrownBy(() -> new RelationNode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Rejects blank relation name")
        void rejectsBlankRelationName() {
            assertThatThrownBy(() -> new RelationNode(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects whitespace-only relation name")
        void rejectsWhitespaceOnlyRelationName() {
            assertThatThrownBy(() -> new RelationNode("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Accepts valid relation name")
        void acceptsValidRelationName() {
            RelationNode node = new RelationNode("Users");
            assertThat(node.name()).isEqualTo("Users");
        }
    }

    // =========================================================================
    // Join nodes
    // =========================================================================

    @Nested
    @DisplayName("Join Node Validations")
    class JoinNodeValidation {

        @Test
        @DisplayName("NaturalJoin rejects null left relation")
        void naturalJoinRejectsNullLeft() {
            assertThatThrownBy(() -> new NaturalJoinNode(null, new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("NaturalJoin rejects null right relation")
        void naturalJoinRejectsNullRight() {
            assertThatThrownBy(() -> new NaturalJoinNode(new RelationNode("L"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ThetaJoin rejects null left relation")
        void thetaJoinRejectsNullLeft() {
            assertThatThrownBy(() -> new ThetaJoinNode(null, new RelationNode("R"),
                    cmp(attr("a"), ComparisonOperator.EQUAL, attr("a"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ThetaJoin rejects null right relation")
        void thetaJoinRejectsNullRight() {
            assertThatThrownBy(() -> new ThetaJoinNode(new RelationNode("L"), null,
                    cmp(attr("a"), ComparisonOperator.EQUAL, attr("a"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ThetaJoin rejects null condition")
        void thetaJoinRejectsNullCondition() {
            assertThatThrownBy(() -> new ThetaJoinNode(
                    new RelationNode("L"), new RelationNode("R"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("LeftOuterJoin rejects null left relation")
        void leftOuterJoinRejectsNullLeft() {
            assertThatThrownBy(() -> new LeftOuterJoinNode(null, new RelationNode("R"),
                    cmp(attr("a"), ComparisonOperator.EQUAL, attr("a"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("LeftOuterJoin rejects null right relation")
        void leftOuterJoinRejectsNullRight() {
            assertThatThrownBy(() -> new LeftOuterJoinNode(new RelationNode("L"), null,
                    cmp(attr("a"), ComparisonOperator.EQUAL, attr("a"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("LeftOuterJoin rejects null condition")
        void leftOuterJoinRejectsNullCondition() {
            assertThatThrownBy(() -> new LeftOuterJoinNode(
                    new RelationNode("L"), new RelationNode("R"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("RightOuterJoin rejects null left relation")
        void rightOuterJoinRejectsNullLeft() {
            assertThatThrownBy(() -> new RightOuterJoinNode(null, new RelationNode("R"),
                    cmp(attr("a"), ComparisonOperator.EQUAL, attr("a"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("FullOuterJoin rejects null right relation")
        void fullOuterJoinRejectsNullRight() {
            assertThatThrownBy(() -> new FullOuterJoinNode(new RelationNode("L"), null,
                    cmp(attr("a"), ComparisonOperator.EQUAL, attr("a"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("SemiJoin rejects null left relation")
        void semiJoinRejectsNullLeft() {
            assertThatThrownBy(() -> new SemiJoinNode(null, new RelationNode("R"),
                    cmp(attr("a"), ComparisonOperator.EQUAL, attr("a"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("AntiJoin rejects null right relation")
        void antiJoinRejectsNullRight() {
            assertThatThrownBy(() -> new AntiJoinNode(new RelationNode("L"), null,
                    cmp(attr("a"), ComparisonOperator.EQUAL, attr("a"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Accepts valid natural join")
        void acceptsValidNaturalJoin() {
            NaturalJoinNode node = new NaturalJoinNode(new RelationNode("L"), new RelationNode("R"));
            assertThat(node.left()).isEqualTo(new RelationNode("L"));
            assertThat(node.right()).isEqualTo(new RelationNode("R"));
        }
    }

    // =========================================================================
    // Set operations
    // =========================================================================

    @Nested
    @DisplayName("Set Operation Validations")
    class SetOperationValidation {

        @Test
        @DisplayName("Union rejects null left relation")
        void unionRejectsNullLeft() {
            assertThatThrownBy(() -> new UnionNode(null, new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Union rejects null right relation")
        void unionRejectsNullRight() {
            assertThatThrownBy(() -> new UnionNode(new RelationNode("L"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("UnionAll rejects null left relation")
        void unionAllRejectsNullLeft() {
            assertThatThrownBy(() -> new UnionAllNode(null, new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("UnionAll rejects null right relation")
        void unionAllRejectsNullRight() {
            assertThatThrownBy(() -> new UnionAllNode(new RelationNode("L"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Difference rejects null left relation")
        void differenceRejectsNullLeft() {
            assertThatThrownBy(() -> new DifferenceNode(null, new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Difference rejects null right relation")
        void differenceRejectsNullRight() {
            assertThatThrownBy(() -> new DifferenceNode(new RelationNode("L"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Intersection rejects null left relation")
        void intersectionRejectsNullLeft() {
            assertThatThrownBy(() -> new IntersectionNode(null, new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Division rejects null right relation")
        void divisionRejectsNullRight() {
            assertThatThrownBy(() -> new DivisionNode(new RelationNode("L"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Product rejects null left relation")
        void productRejectsNullLeft() {
            assertThatThrownBy(() -> new ProductNode(null, new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Accepts valid union")
        void acceptsValidUnion() {
            UnionNode node = new UnionNode(new RelationNode("L"), new RelationNode("R"));
            assertThat(node.left()).isEqualTo(new RelationNode("L"));
            assertThat(node.right()).isEqualTo(new RelationNode("R"));
        }
    }
}

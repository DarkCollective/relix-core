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

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.attrs;
import static com.darkcollective.relix.ast.AstBuilders.cmp;
import static com.darkcollective.relix.ast.AstBuilders.distinct;
import static com.darkcollective.relix.ast.AstBuilders.groupByKeys;
import static com.darkcollective.relix.ast.AstBuilders.num;
import static com.darkcollective.relix.ast.AstBuilders.product;
import static com.darkcollective.relix.ast.AstBuilders.project;
import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.ast.AstBuilders.select;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RelNodeAssert} exists for its failure messages, so that is what is tested:
 * every negative case here asserts that the message carries the <em>tree</em>, not
 * only the leaf that mismatched.  A wrapper that passes the claim through but drops
 * the subject would satisfy the positive tests and be worthless.
 */
@DisplayName("RelNodeAssert — tree assertions that print the tree")
final class RelNodeAssertTest {

    /** π name (σ id = 1 (Users)) — one node of each kind the navigation methods walk. */
    private static final RelNode TREE =
            project(attrs("name"), select(cmp(attr("id"), ComparisonOperator.EQUAL, num("1")), rel("Users")));

    @Nested
    @DisplayName("kind and navigation")
    class Shape {

        @Test
        @DisplayName("navigates by position rather than by cast")
        void navigates() {
            assertThat(TREE)
                    .isNode(ProjectionNode.class)
                    .input().isNode(SelectionNode.class)
                    .input().isRelation("Users");
        }

        @Test
        @DisplayName("a relation name matches case-insensitively, as the symbol table resolves it")
        void relationNameIsCaseInsensitive() {
            assertThat(rel("Users")).isRelation("users");
        }

        @Test
        @DisplayName("a wrong kind reports the tree, not just the node type")
        void wrongKindPrintsTheTree() {
            assertThatThrownBy(() -> assertThat(TREE).isNode(SelectionNode.class))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected a SelectionNode but was a ProjectionNode")
                    .hasMessageContaining(TREE.prettyPrint());
        }

        @Test
        @DisplayName("a wrong relation name reports both names and the tree")
        void wrongRelationPrintsTheTree() {
            assertThatThrownBy(() -> assertThat(TREE).input().input().isRelation("Orders"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected the relation Orders but was Users")
                    .hasMessageContaining("Users");
        }

        @Test
        @DisplayName("input() on a binary operator fails on the arity rather than silently taking the left")
        void inputRejectsBinary() {
            assertThatThrownBy(() -> assertThat(product(rel("A"), rel("B"))).input())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("to have 1 child expression(s) but it has 2");
        }

        @Test
        @DisplayName("left() and right() walk a binary operator")
        void binaryNavigation() {
            RelNode cross = product(rel("A"), rel("B"));
            assertThat(cross).left().isRelation("A");
            assertThat(cross).right().isRelation("B");
        }

        @Test
        @DisplayName("a missing child names the position that was asked for")
        void missingChild() {
            assertThatThrownBy(() -> assertThat(rel("Users")).child(0))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("to have a child at index 0 but it has 0");
        }

        @Test
        @DisplayName("isNodeSatisfying narrows to the record's own accessors")
        void narrows() {
            assertThat(TREE).isNodeSatisfying(ProjectionNode.class,
                    p -> org.assertj.core.api.Assertions.assertThat(p.attributes()).hasSize(1));
        }
    }

    @Nested
    @DisplayName("structural equivalence")
    class Equivalence {

        @Test
        @DisplayName("two trees written at different positions are equivalent")
        void ignoresSourceLocation() {
            RelNode other = project(attrs("name"),
                    new SelectionNode(cmp(attr("id"), ComparisonOperator.EQUAL, num("1")),
                            new RelationNode("Users", java.util.Optional.empty(),
                                    new SourceLocation("other.relix", 9, 9)),
                            new SourceLocation("other.relix", 4, 2)));
            assertThat(TREE).isEquivalentTo(other);
        }

        @Test
        @DisplayName("a mismatch prints both trees — the only readable form of the difference")
        void printsBothTrees() {
            RelNode other = project(attrs("name"), rel("Users"));
            assertThatThrownBy(() -> assertThat(TREE).isEquivalentTo(other))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining(TREE.prettyPrint())
                    .hasMessageContaining(other.prettyPrint());
        }

        @Test
        @DisplayName("isNotEquivalentTo is the claim a rewrite test makes")
        void notEquivalent() {
            assertThat(TREE).isNotEquivalentTo(rel("Users"));
            assertThatThrownBy(() -> assertThat(TREE).isNotEquivalentTo(TREE))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected a different expression");
        }
    }

    @Nested
    @DisplayName("rendering and whole-tree counts")
    class Rendering {

        @Test
        @DisplayName("prettyPrintsTo and prettyPrintContains state a rendering claim")
        void rendering() {
            assertThat(rel("Users")).prettyPrintsTo(rel("Users").prettyPrint());
            assertThat(TREE).prettyPrintContains("Users");
        }

        @Test
        @DisplayName("containsNoNode is a claim about the whole tree, not the root")
        void containsNoNode() {
            assertThat(TREE).containsNoNode(DistinctNode.class);
            RelNode withDistinct = project(attrs("name"), distinct(rel("Users")));
            assertThatThrownBy(() -> assertThat(withDistinct).containsNoNode(DistinctNode.class))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected no DistinctNode anywhere in the expression, but found 1");
        }

        @Test
        @DisplayName("containsNodes counts every occurrence")
        void containsNodes() {
            assertThat(product(distinct(rel("A")), distinct(rel("B"))))
                    .containsNodes(DistinctNode.class, 2)
                    .containsNodes(RelationNode.class, 2);
        }
    }

    @Test
    @DisplayName("an unrenderable tree still gets its assertion — the description never throws")
    void unrenderableTree() {
        // A γ built with a null grouping list: SelectionPushdownPassTest builds one on
        // purpose, to ask what the pass does with it, and PrettyPrinter throws on it.
        // Rendering it is a courtesy; the assertion is the point, so the courtesy gives way.
        RelNode broken = groupByKeys(null, List.of(), rel("R"));
        assertThat(broken).isNode(AggregationNode.class);
        assertThatThrownBy(() -> assertThat(broken).isNode(SelectionNode.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected a SelectionNode but was a AggregationNode")
                .hasMessageContaining("unrenderable AggregationNode");
    }

    @Test
    @DisplayName("a null tree is reported as null rather than throwing")
    void nullTree() {
        assertThatThrownBy(() -> assertThat((RelNode) null).isNode(RelationNode.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expecting actual not to be null");
    }
}

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

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class TreeNodeTest {

    private static final RelNode INPUT = new RelationNode("Nodes");

    @Test
    void exposesItsFields() {
        var spec = asc("ordinal");
        var t = new TreeNode(INPUT, "node_id", "parent_id", List.of(spec), "children");
        assertThat(t.input()).isSameAs(INPUT);
        assertThat(t.keyColumn()).isEqualTo("node_id");
        assertThat(t.parentColumn()).isEqualTo("parent_id");
        assertThat(t.orderSpecs()).containsExactly(spec);
        assertThat(t.childrenColumn()).isEqualTo("children");
    }

    @Test
    void rejectsBlankKeyColumn() {
        assertThatThrownBy(() -> new TreeNode(INPUT, " ", "parent_id", List.of(), "children"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyColumn");
    }

    @Test
    void rejectsBlankParentColumn() {
        assertThatThrownBy(() -> new TreeNode(INPUT, "node_id", " ", List.of(), "children"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parentColumn");
    }

    @Test
    void rejectsBlankChildrenColumn() {
        assertThatThrownBy(() -> new TreeNode(INPUT, "node_id", "parent_id", List.of(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("childrenColumn");
    }

    @Test
    void rejectsNullInput() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TreeNode(null, "node_id", "parent_id", List.of(), "children"));
    }

    @Test
    void rejectsNullOrderSpecs() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TreeNode(INPUT, "node_id", "parent_id", null, "children"));
    }

    @Test
    void orderSpecsAreDefensivelyCopied() {
        var specs = new java.util.ArrayList<>(List.of(asc("a")));
        var t = new TreeNode(INPUT, "node_id", "parent_id", specs, "children");
        specs.add(desc("b"));
        assertThat(t.orderSpecs()).hasSize(1);
    }

    @Test
    void materializesAsBag() {
        assertThat(new TreeNode(INPUT, "node_id", "parent_id", List.of(), "children")
                .materializationMode())
                .isEqualTo(MaterializationMode.BAG);
    }

    @Test
    void exposesInputAsSoleChild() {
        var t = new TreeNode(INPUT, "node_id", "parent_id", List.of(), "children");
        assertThat(t.children()).containsExactly(INPUT);
    }

    @Test
    void mapChildrenRebuildsWithReplacedInput() {
        var t = new TreeNode(INPUT, "node_id", "parent_id", List.of(), "children");
        RelNode replacement = new RelationNode("Other");
        RelNode mapped = t.mapChildren(child -> replacement);
        assertThat(mapped).isNode(TreeNode.class);
        assertThat(((TreeNode) mapped).input()).isSameAs(replacement);
        assertThat(((TreeNode) mapped).childrenColumn()).isEqualTo("children");
    }

    @Test
    void mapChildrenReturnsSameInstanceWhenUnchanged() {
        var t = new TreeNode(INPUT, "node_id", "parent_id", List.of(), "children");
        assertThat(t.mapChildren(child -> child)).isSameAs(t);
    }

    @Test
    void prettyPrintsWithOrder() {
        var t = new TreeNode(INPUT, "node_id", "parent_id",
                List.of(asc("ordinal")), "children");
        assertThat(t.prettyPrint())
                .isEqualTo("TREE node_id BY parent_id ORDER ordinal AS children (Nodes)");
    }

    @Test
    void prettyPrintsWithoutOrder() {
        var t = new TreeNode(INPUT, "id", "manager_id", List.of(), "reports");
        assertThat(t.prettyPrint())
                .isEqualTo("TREE id BY manager_id AS reports (Nodes)");
    }

    @Test
    void prettyPrintsDescendingOrder() {
        var t = new TreeNode(INPUT, "node_id", "parent_id",
                List.of(desc("ordinal")), "children");
        assertThat(t.prettyPrint())
                .isEqualTo("TREE node_id BY parent_id ORDER ordinal DESC AS children (Nodes)");
    }
}

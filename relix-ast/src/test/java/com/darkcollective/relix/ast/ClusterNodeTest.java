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

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class ClusterNodeTest {

    private static final RelNode INPUT = new RelationNode("Edges");

    @Test
    void exposesItsFields() {
        var c = new ClusterNode(INPUT, "src", "dst", "cid");
        assertThat(c.input()).isSameAs(INPUT);
        assertThat(c.fromColumn()).isEqualTo("src");
        assertThat(c.toColumn()).isEqualTo("dst");
        assertThat(c.labelColumn()).isEqualTo("cid");
    }

    @Test
    void rejectsBlankFromColumn() {
        assertThatThrownBy(() -> new ClusterNode(INPUT, " ", "dst", "cid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromColumn");
    }

    @Test
    void rejectsBlankToColumn() {
        assertThatThrownBy(() -> new ClusterNode(INPUT, "src", " ", "cid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toColumn");
    }

    @Test
    void rejectsBlankLabelColumn() {
        assertThatThrownBy(() -> new ClusterNode(INPUT, "src", "dst", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("labelColumn");
    }

    @Test
    void rejectsNullInput() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ClusterNode(null, "src", "dst", "cid"));
    }

    @Test
    void materializesAsSet() {
        assertThat(new ClusterNode(INPUT, "a", "b", "c").materializationMode())
                .isEqualTo(MaterializationMode.SET);
    }

    @Test
    void exposesInputAsSoleChild() {
        var c = new ClusterNode(INPUT, "a", "b", "c");
        assertThat(c.children()).containsExactly(INPUT);
    }

    @Test
    void mapChildrenRebuildsWithReplacedInput() {
        var c = new ClusterNode(INPUT, "a", "b", "c");
        RelNode replacement = new RelationNode("Other");
        RelNode mapped = c.mapChildren(child -> replacement);
        assertThat(mapped).isNode(ClusterNode.class);
        assertThat(((ClusterNode) mapped).input()).isSameAs(replacement);
        assertThat(((ClusterNode) mapped).labelColumn()).isEqualTo("c");
    }

    @Test
    void mapChildrenReturnsSameInstanceWhenUnchanged() {
        var c = new ClusterNode(INPUT, "a", "b", "c");
        assertThat(c.mapChildren(child -> child)).isSameAs(c);
    }

    @Test
    void prettyPrintsCanonically() {
        var c = new ClusterNode(INPUT, "src", "dst", "cid");
        assertThat(c.prettyPrint()).isEqualTo("CLUSTER src, dst AS cid (Edges)");
    }

    @Test
    void childrenListIsTheSingletonInput() {
        var c = new ClusterNode(INPUT, "a", "b", "c");
        assertThat(c.children()).isEqualTo(List.of(INPUT));
    }
}

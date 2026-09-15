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

final class PathNodeTest {

    private static final RelNode INPUT = new RelationNode("Edges");

    @Test
    void exposesItsFields() {
        var p = new PathNode(INPUT, "src", "dst", 1, 3, "depth");
        assertThat(p.input()).isSameAs(INPUT);
        assertThat(p.fromColumn()).isEqualTo("src");
        assertThat(p.toColumn()).isEqualTo("dst");
        assertThat(p.minHops()).isEqualTo(1);
        assertThat(p.maxHops()).isEqualTo(3);
        assertThat(p.depthColumn()).isEqualTo("depth");
    }

    @Test
    void rejectsBlankFromColumn() {
        assertThatThrownBy(() -> new PathNode(INPUT, " ", "dst", 1, 3, "depth"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromColumn");
    }

    @Test
    void rejectsBlankToColumn() {
        assertThatThrownBy(() -> new PathNode(INPUT, "src", " ", 1, 3, "depth"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toColumn");
    }

    @Test
    void rejectsBlankDepthColumn() {
        assertThatThrownBy(() -> new PathNode(INPUT, "src", "dst", 1, 3, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depthColumn");
    }

    @Test
    void rejectsMinHopsBelowOne() {
        assertThatThrownBy(() -> new PathNode(INPUT, "src", "dst", 0, 3, "depth"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minHops");
    }

    @Test
    void rejectsMaxLessThanMin() {
        assertThatThrownBy(() -> new PathNode(INPUT, "src", "dst", 5, 2, "depth"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHops");
    }

    @Test
    void allowsEqualMinAndMax() {
        var p = new PathNode(INPUT, "src", "dst", 2, 2, "depth");
        assertThat(p.minHops()).isEqualTo(2);
        assertThat(p.maxHops()).isEqualTo(2);
    }

    @Test
    void rejectsNullInput() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PathNode(null, "src", "dst", 1, 3, "depth"));
    }

    @Test
    void materializesAsSet() {
        assertThat(new PathNode(INPUT, "a", "b", 1, 2, "d").materializationMode())
                .isEqualTo(MaterializationMode.SET);
    }

    @Test
    void exposesInputAsSoleChild() {
        var p = new PathNode(INPUT, "a", "b", 1, 2, "d");
        assertThat(p.children()).containsExactly(INPUT);
        assertThat(p.children()).isEqualTo(List.of(INPUT));
    }

    @Test
    void mapChildrenRebuildsWithReplacedInput() {
        var p = new PathNode(INPUT, "a", "b", 1, 4, "d");
        RelNode replacement = new RelationNode("Other");
        RelNode mapped = p.mapChildren(child -> replacement);
        assertThat(mapped).isNode(PathNode.class);
        assertThat(((PathNode) mapped).input()).isSameAs(replacement);
        assertThat(((PathNode) mapped).minHops()).isEqualTo(1);
        assertThat(((PathNode) mapped).maxHops()).isEqualTo(4);
        assertThat(((PathNode) mapped).depthColumn()).isEqualTo("d");
    }

    @Test
    void mapChildrenReturnsSameInstanceWhenUnchanged() {
        var p = new PathNode(INPUT, "a", "b", 1, 2, "d");
        assertThat(p.mapChildren(child -> child)).isSameAs(p);
    }

    @Test
    void prettyPrintsCanonically() {
        var p = new PathNode(INPUT, "src", "dst", 1, 3, "depth");
        assertThat(p.prettyPrint()).isEqualTo("PATH src, dst HOPS 1 TO 3 AS depth (Edges)");
    }
}

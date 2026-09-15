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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class OuterUnionNodeTest {

    private static final RelNode LEFT = new RelationNode("A");
    private static final RelNode RIGHT = new RelationNode("B");

    @Test
    void exposesItsFields() {
        var u = new OuterUnionNode(LEFT, RIGHT);
        assertThat(u.left()).isSameAs(LEFT);
        assertThat(u.right()).isSameAs(RIGHT);
        assertThat(u.location()).isSameAs(SourceLocation.UNKNOWN);
    }

    @Test
    void rejectsNullLeft() {
        assertThatNullPointerException().isThrownBy(() -> new OuterUnionNode(null, RIGHT));
    }

    @Test
    void rejectsNullRight() {
        assertThatNullPointerException().isThrownBy(() -> new OuterUnionNode(LEFT, null));
    }

    @Test
    void rejectsNullLocation() {
        assertThatNullPointerException().isThrownBy(() -> new OuterUnionNode(LEFT, RIGHT, null));
    }

    @Test
    void materialisesAsSet() {
        assertThat(new OuterUnionNode(LEFT, RIGHT).materializationMode())
                .isEqualTo(MaterializationMode.SET);
    }

    @Test
    void exposesBothChildren() {
        assertThat(new OuterUnionNode(LEFT, RIGHT).children())
                .isEqualTo(List.of(LEFT, RIGHT));
    }

    @Test
    void mapChildrenRebuildsWithReplacedChildren() {
        var u = new OuterUnionNode(LEFT, RIGHT);
        RelNode l2 = new RelationNode("X");
        RelNode r2 = new RelationNode("Y");
        RelNode mapped = u.mapChildren(child -> child == LEFT ? l2 : r2);
        assertThat(mapped).isNode(OuterUnionNode.class);
        assertThat(((OuterUnionNode) mapped).left()).isSameAs(l2);
        assertThat(((OuterUnionNode) mapped).right()).isSameAs(r2);
    }

    @Test
    void mapChildrenReturnsSameInstanceWhenUnchanged() {
        var u = new OuterUnionNode(LEFT, RIGHT);
        assertThat(u.mapChildren(child -> child)).isSameAs(u);
    }

    @Test
    void prettyPrintsWithGlyph() {
        assertThat(new OuterUnionNode(LEFT, RIGHT).prettyPrint()).isEqualTo("(A) ⊔ (B)");
    }
}

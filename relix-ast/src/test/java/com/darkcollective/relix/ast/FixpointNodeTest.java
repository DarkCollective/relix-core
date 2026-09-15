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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link FixpointNode} and {@link RecursiveRefNode} (epic #46,
 * issue #113 — the general-recursion AST nodes).
 */
final class FixpointNodeTest {

    private static final RelNode BASE = new RelationNode("Base");
    private static final RelNode STEP = new RecursiveRefNode("R");
    private static final RelNode OTHER = new RelationNode("Other");

    @Nested
    class Fixpoint {

        @Test
        void exposesItsFields() {
            var f = new FixpointNode("R", BASE, STEP);
            assertThat(f.name()).isEqualTo("R");
            assertThat(f.base()).isSameAs(BASE);
            assertThat(f.step()).isSameAs(STEP);
            assertThat(f.location()).isEqualTo(SourceLocation.UNKNOWN);
        }

        @Test
        void rejectsBlankName() {
            assertThatThrownBy(() -> new FixpointNode(" ", BASE, STEP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        void rejectsNullName() {
            assertThatNullPointerException().isThrownBy(() -> new FixpointNode(null, BASE, STEP));
        }

        @Test
        void rejectsNullBase() {
            assertThatNullPointerException().isThrownBy(() -> new FixpointNode("R", null, STEP));
        }

        @Test
        void rejectsNullStep() {
            assertThatNullPointerException().isThrownBy(() -> new FixpointNode("R", BASE, null));
        }

        @Test
        void rejectsNullLocation() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new FixpointNode("R", BASE, STEP, null));
        }

        @Test
        void materializesAsSet() {
            assertThat(new FixpointNode("R", BASE, STEP).materializationMode())
                    .isEqualTo(MaterializationMode.SET);
        }

        @Test
        void childrenAreBaseThenStep() {
            assertThat(new FixpointNode("R", BASE, STEP).children()).containsExactly(BASE, STEP);
        }

        @Test
        void mapChildrenRebuildsBothBranches() {
            var f = new FixpointNode("R", BASE, STEP);
            RelNode rewritten = f.mapChildren(c -> c == BASE ? OTHER : c);
            FixpointNode result = assertThat(rewritten).asNode(FixpointNode.class);
            assertThat(result.name()).isEqualTo("R");
            assertThat(result.base()).isSameAs(OTHER);
            assertThat(result.step()).isSameAs(STEP);
        }

        @Test
        void mapChildrenReturnsSameInstanceWhenUnchanged() {
            var f = new FixpointNode("R", BASE, STEP);
            assertThat(f.mapChildren(UnaryOperator.identity())).isSameAs(f);
        }

        @Test
        void prettyPrints() {
            assertThat(new FixpointNode("R", new RelationNode("Edges"), new RecursiveRefNode("R"))
                    .prettyPrint()).isEqualTo("FIX R (Edges, R)");
        }
    }

    @Nested
    class RecursiveRef {

        @Test
        void exposesItsName() {
            var r = new RecursiveRefNode("R");
            assertThat(r.name()).isEqualTo("R");
            assertThat(r.location()).isEqualTo(SourceLocation.UNKNOWN);
        }

        @Test
        void rejectsBlankName() {
            assertThatThrownBy(() -> new RecursiveRefNode(" "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        void rejectsNullName() {
            assertThatNullPointerException().isThrownBy(() -> new RecursiveRefNode(null));
        }

        @Test
        void rejectsNullLocation() {
            assertThatNullPointerException().isThrownBy(() -> new RecursiveRefNode("R", null));
        }

        @Test
        void isALeaf() {
            var r = new RecursiveRefNode("R");
            assertThat(r.children()).isEmpty();
            assertThat(r.mapChildren(c -> OTHER)).isSameAs(r);
        }

        @Test
        void materializesAsStream() {
            assertThat(new RecursiveRefNode("R").materializationMode())
                    .isEqualTo(MaterializationMode.STREAM);
        }

        @Test
        void prettyPrintsAsBareName() {
            assertThat(new RecursiveRefNode("R").prettyPrint()).isEqualTo("R");
        }
    }
}

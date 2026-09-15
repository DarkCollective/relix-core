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
 * Unit tests for {@link CoverNode} — the PICT-style pairwise/t-way covering
 * reduction operator (ADR-0012, epic #126, issue #127 — AST/parser slice).
 */
final class CoverNodeTest {

    private static final RelNode INPUT = new RelationNode("Params");
    private static final RelNode OTHER = new RelationNode("Other");

    @Nested
    class RecordGuards {

        @Test
        void exposesItsFields() {
            var c = new CoverNode(2, INPUT);
            assertThat(c.strength()).isEqualTo(2);
            assertThat(c.exact()).isFalse();
            assertThat(c.input()).isSameAs(INPUT);
            assertThat(c.location()).isEqualTo(SourceLocation.UNKNOWN);
        }

        @Test
        void exactFlagDefaultsFalseViaConvenienceCtors() {
            var c2 = new CoverNode(2, INPUT);
            var c3 = new CoverNode(2, INPUT, SourceLocation.UNKNOWN);
            assertThat(c2.exact()).isFalse();
            assertThat(c3.exact()).isFalse();
        }

        @Test
        void exactFlagSetViaCanonicalCtor() {
            var exact = new CoverNode(2, true, INPUT, SourceLocation.UNKNOWN);
            assertThat(exact.exact()).isTrue();
            assertThat(exact.strength()).isEqualTo(2);
        }

        @Test
        void exposesLocationWhenProvided() {
            var loc = new SourceLocation("test.relix", 3, 5);
            var c = new CoverNode(3, INPUT, loc);
            assertThat(c.location()).isEqualTo(loc);
        }

        @Test
        void rejectsStrengthZero() {
            assertThatThrownBy(() -> new CoverNode(0, INPUT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("≥ 1");
        }

        @Test
        void rejectsNegativeStrength() {
            assertThatThrownBy(() -> new CoverNode(-1, INPUT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("≥ 1");
        }

        @Test
        void acceptsStrengthOne() {
            var c = new CoverNode(1, INPUT);
            assertThat(c.strength()).isEqualTo(1);
        }

        @Test
        void acceptsLargeStrength() {
            var c = new CoverNode(5, INPUT);
            assertThat(c.strength()).isEqualTo(5);
        }

        @Test
        void rejectsNullInput() {
            assertThatNullPointerException().isThrownBy(() -> new CoverNode(2, null));
        }

        @Test
        void rejectsNullLocation() {
            assertThatNullPointerException().isThrownBy(() -> new CoverNode(2, INPUT, null));
        }
    }

    @Nested
    class Materialization {

        @Test
        void materializesAsBag() {
            assertThat(new CoverNode(2, INPUT).materializationMode())
                    .isEqualTo(MaterializationMode.BAG);
        }
    }

    @Nested
    class Traversal {

        @Test
        void hasOneChild() {
            assertThat(new CoverNode(2, INPUT).children()).containsExactly(INPUT);
        }

        @Test
        void mapChildrenRebuildWithNewInput() {
            var c = new CoverNode(2, INPUT);
            RelNode result = c.mapChildren(child -> OTHER);
            CoverNode rewritten = assertThat(result).asNode(CoverNode.class);
            assertThat(rewritten.strength()).isEqualTo(2);
            assertThat(rewritten.exact()).isFalse();
            assertThat(rewritten.input()).isSameAs(OTHER);
        }

        @Test
        void mapChildrenPreservesExactFlag() {
            var c = new CoverNode(2, true, INPUT, SourceLocation.UNKNOWN);
            RelNode result = c.mapChildren(child -> OTHER);
            CoverNode rewritten = (CoverNode) result;
            assertThat(rewritten.exact()).isTrue();
        }

        @Test
        void mapChildrenReturnsSameInstanceWhenUnchanged() {
            var c = new CoverNode(2, INPUT);
            assertThat(c.mapChildren(UnaryOperator.identity())).isSameAs(c);
        }
    }

    @Nested
    class PrettyPrint {

        @Test
        void prettyPrintsWithStrengthAndInput() {
            assertThat(new CoverNode(2, new RelationNode("Params")).prettyPrint())
                    .isEqualTo("COVER 2 (Params)");
        }

        @Test
        void prettyPrintsStrengthOne() {
            assertThat(new CoverNode(1, new RelationNode("R")).prettyPrint())
                    .isEqualTo("COVER 1 (R)");
        }

        @Test
        void prettyPrintsStrengthThree() {
            assertThat(new CoverNode(3, new RelationNode("R")).prettyPrint())
                    .isEqualTo("COVER 3 (R)");
        }

        @Test
        void prettyPrintsExactMode() {
            assertThat(new CoverNode(2, true, new RelationNode("Params"), SourceLocation.UNKNOWN).prettyPrint())
                    .isEqualTo("COVER EXACT 2 (Params)");
        }
    }
}

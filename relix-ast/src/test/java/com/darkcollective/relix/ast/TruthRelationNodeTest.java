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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * AST-contract tests for the nullary truth-relation literal
 * {@link TruthRelationNode} (issue #51).
 */
@DisplayName("TruthRelationNode — UNIT / EMPTY (DEE / DUM)")
final class TruthRelationNodeTest {

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void unitHoldsTheEmptyTuple() {
            assertThat(TruthRelationNode.unit(SourceLocation.UNKNOWN).holdsTuple()).isTrue();
        }

        @Test
        void emptyHoldsNoTuple() {
            assertThat(TruthRelationNode.empty(SourceLocation.UNKNOWN).holdsTuple()).isFalse();
        }

        @Test
        void rejectsNullLocation() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TruthRelationNode(true, null));
        }

        @Test
        void preservesItsLocation() {
            var loc = new SourceLocation("q.relix", 3, 11);
            assertThat(TruthRelationNode.unit(loc).location()).isEqualTo(loc);
        }

        @Test
        @DisplayName("the two literals are never equal to each other")
        void unitAndEmptyDiffer() {
            assertThat(TruthRelationNode.unit(SourceLocation.UNKNOWN))
                    .isNotEqualTo(TruthRelationNode.empty(SourceLocation.UNKNOWN));
        }
    }

    @Nested
    @DisplayName("cardinality and keyword")
    class Facts {

        @Test
        void unitHasExactlyOneRow() {
            assertThat(TruthRelationNode.unit(SourceLocation.UNKNOWN).cardinality()).isEqualTo(1L);
        }

        @Test
        void emptyHasNoRows() {
            assertThat(TruthRelationNode.empty(SourceLocation.UNKNOWN).cardinality()).isZero();
        }

        @Test
        void keywordIsTheCanonicalSpelling() {
            assertThat(TruthRelationNode.unit(SourceLocation.UNKNOWN).keyword()).isEqualTo("UNIT");
            assertThat(TruthRelationNode.empty(SourceLocation.UNKNOWN).keyword()).isEqualTo("EMPTY");
        }
    }

    @Nested
    @DisplayName("RelNode contract")
    class NodeContract {

        @Test
        @DisplayName("it is a leaf — no children")
        void hasNoChildren() {
            assertThat(TruthRelationNode.unit(SourceLocation.UNKNOWN).children()).isEmpty();
        }

        @Test
        @DisplayName("mapChildren is the identity (nothing to rewrite)")
        void mapChildrenReturnsSameInstance() {
            var unit = TruthRelationNode.unit(SourceLocation.UNKNOWN);
            assertThat(unit.mapChildren(child -> new RelationNode("Other"))).isSameAs(unit);
        }

        @Test
        @DisplayName("a 0-or-1 row literal never buffers — it streams")
        void streams() {
            assertThat(TruthRelationNode.unit(SourceLocation.UNKNOWN).materializationMode())
                    .isEqualTo(MaterializationMode.STREAM);
        }

        @Test
        void prettyPrintsAsItsKeyword() {
            assertThat(TruthRelationNode.unit(SourceLocation.UNKNOWN).prettyPrint()).isEqualTo("UNIT");
            assertThat(TruthRelationNode.empty(SourceLocation.UNKNOWN).prettyPrint()).isEqualTo("EMPTY");
        }

        @Test
        @DisplayName("accept dispatches to the visitor's TruthRelationNode arm")
        void acceptDispatchesToVisit() {
            assertThat(TruthRelationNode.empty(SourceLocation.UNKNOWN)
                    .accept(new com.darkcollective.relix.ast.visitor.internal.PrettyPrinter()))
                    .isEqualTo("EMPTY");
        }

        @Test
        @DisplayName("it prints unchanged inside a larger expression")
        void printsInsideAProduct() {
            var product = new ProductNode(new RelationNode("Trades"),
                    TruthRelationNode.unit(SourceLocation.UNKNOWN));
            assertThat(product.prettyPrint()).isEqualTo("(Trades) × (UNIT)");
        }
    }
}

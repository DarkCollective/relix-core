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
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Constructor validation tests for unary and aggregation AST nodes:
 * {@link ProjectionNode}, {@link SelectionNode}, {@link RenameNode},
 * {@link DistinctNode}, {@link SortNode}, {@link LimitNode},
 * and {@link AggregationNode}.
 */
@DisplayName("AST — unary and aggregation node validation")
final class AstNodeUnaryTest extends AstTestSupport {

    // =========================================================================
    // ProjectionNode
    // =========================================================================

    @Nested
    @DisplayName("ProjectionNode Validations")
    class ProjectionNodeValidation {

        @Test
        @DisplayName("Rejects null attributes list")
        void rejectsNullAttributesList() {
            assertThatThrownBy(() -> new ProjectionNode(null, new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Rejects empty attributes list")
        void rejectsEmptyAttributesList() {
            assertThatThrownBy(() -> new ProjectionNode(List.of(), new RelationNode("R")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects null input relation")
        void rejectsNullInput() {
            assertThatThrownBy(() -> new ProjectionNode(List.of(projected(attr("a"))), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Rejects null element in attributes list")
        void rejectsNullInAttributesList() {
            assertThatThrownBy(() -> new ProjectionNode(List.of(null), new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Accepts valid projection")
        void acceptsValidProjection() {
            ProjectionNode node = new ProjectionNode(
                    List.of(projected(attr("name"))), new RelationNode("Users"));
            assertThat(node.attributes()).hasSize(1);
        }
    }

    // =========================================================================
    // SelectionNode
    // =========================================================================

    @Nested
    @DisplayName("SelectionNode Validations")
    class SelectionNodeValidation {

        @Test
        @DisplayName("Rejects null predicate")
        void rejectsNullPredicate() {
            assertThatThrownBy(() -> new SelectionNode(null, new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Rejects null input relation")
        void rejectsNullInput() {
            assertThatThrownBy(() -> new SelectionNode(
                    cmp(attr("a"), ComparisonOperator.EQUAL, num("1")), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Accepts valid selection")
        void acceptsValidSelection() {
            SelectionNode node = new SelectionNode(
                    cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                    new RelationNode("Users"));
            assertThat(node.predicate())
                    .isEqualTo(cmp(attr("age"), ComparisonOperator.GREATER, num("18")));
            assertThat(node.input()).isEqualTo(new RelationNode("Users"));
        }
    }

    // =========================================================================
    // RenameNode
    // =========================================================================

    @Nested
    @DisplayName("RenameNode Validations")
    class RenameNodeValidation {

        @Test
        @DisplayName("Rejects null relation name")
        void rejectsNullRelationName() {
            assertThatThrownBy(() -> new RenameNode(null, List.of(), new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Rejects blank relation name")
        void rejectsBlankRelationName() {
            assertThatThrownBy(() -> new RenameNode("", List.of(), new RelationNode("R")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects null attributes list")
        void rejectsNullAttributesList() {
            assertThatThrownBy(() -> new RenameNode("R2", null, new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Rejects null input relation")
        void rejectsNullInput() {
            assertThatThrownBy(() -> new RenameNode("R2", List.of(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Accepts rename with no attribute renaming")
        void acceptsValidRenameWithNoAttributes() {
            RenameNode node = new RenameNode("R2", List.of(), new RelationNode("R"));
            assertThat(node.relationName()).contains("R2");
        }

        @Test
        @DisplayName("Accepts rename with attribute list")
        void acceptsValidRenameWithAttributes() {
            RenameNode node = new RenameNode("R2", List.of("a", "b"), new RelationNode("R"));
            assertThat(node.attributes()).hasSize(2);
        }

        @Test
        @DisplayName("Accepts pair-form rename and reports it as renaming columns")
        void acceptsPairForm() {
            RenameNode node = new RenameNode(java.util.Optional.of("R2"), List.of(),
                    List.of(new RenameNode.RenamePair("a", "x")),
                    new RelationNode("R"), SourceLocation.UNKNOWN);
            assertThat(node.pairs()).hasSize(1);
            assertThat(node.renamesColumns()).isTrue();
            assertThat(node.attributes()).isEmpty();
        }

        @Test
        @DisplayName("Accepts pair-form rename with no relation name")
        void acceptsPairFormNoRelationName() {
            RenameNode node = new RenameNode(java.util.Optional.empty(), List.of(),
                    List.of(new RenameNode.RenamePair("a", "x")),
                    new RelationNode("R"), SourceLocation.UNKNOWN);
            assertThat(node.relationName()).isEmpty();
            assertThat(node.renamesColumns()).isTrue();
        }

        @Test
        @DisplayName("Relation-only rename reports renamesColumns() == false")
        void relationOnlyDoesNotRenameColumns() {
            RenameNode node = new RenameNode("R2", List.of(), new RelationNode("R"));
            assertThat(node.renamesColumns()).isFalse();
        }

        @Test
        @DisplayName("Rejects mixing positional attributes and pairs")
        void rejectsMixedForms() {
            assertThatThrownBy(() -> new RenameNode(java.util.Optional.of("R2"),
                    List.of("a"), List.of(new RenameNode.RenamePair("b", "c")),
                    new RelationNode("R"), SourceLocation.UNKNOWN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mix");
        }

        @Test
        @DisplayName("Rejects a no-op rename: no relation name and no column changes")
        void rejectsEmptyRename() {
            assertThatThrownBy(() -> new RenameNode(java.util.Optional.empty(),
                    List.of(), List.of(), new RelationNode("R"), SourceLocation.UNKNOWN))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects a blank column name inside a rename pair")
        void rejectsBlankPairName() {
            assertThatThrownBy(() -> new RenameNode.RenamePair("a", " "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("withInput preserves rename metadata over a new input")
        void withInputPreservesMetadata() {
            RenameNode node = new RenameNode(java.util.Optional.of("R2"), List.of(),
                    List.of(new RenameNode.RenamePair("a", "x")),
                    new RelationNode("R"), SourceLocation.UNKNOWN);
            RenameNode rebuilt = node.withInput(new RelationNode("S"));
            assertThat(rebuilt.relationName()).contains("R2");
            assertThat(rebuilt.pairs()).isEqualTo(node.pairs());
            assertThat(rebuilt.input()).isEqualTo(new RelationNode("S"));
        }
    }

    // =========================================================================
    // Unary operations (Distinct, Sort, Limit)
    // =========================================================================

    @Nested
    @DisplayName("Unary Operation Validations")
    class UnaryOperationValidation {

        @Test
        @DisplayName("Distinct rejects null input")
        void distinctRejectsNullInput() {
            assertThatThrownBy(() -> new DistinctNode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Accepts valid distinct node")
        void acceptsValidDistinct() {
            DistinctNode node = new DistinctNode(new RelationNode("R"));
            assertThat(node.input()).isEqualTo(new RelationNode("R"));
        }

        @Test
        @DisplayName("Sort rejects null sort specifications")
        void sortRejectsNullSort() {
            assertThatThrownBy(() -> new SortNode(null, new RelationNode("R")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Sort rejects empty sort specifications")
        void sortRejectsEmptySort() {
            assertThatThrownBy(() -> new SortNode(List.of(), new RelationNode("R")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Sort rejects null input relation")
        void sortRejectsNullInput() {
            assertThatThrownBy(() -> new SortNode(
                    List.of(asc("a")), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Limit accepts empty offset (Optional.empty)")
        void limitAcceptsEmptyOffset() {
            LimitNode node = new LimitNode(Optional.empty(), 10L, new RelationNode("R"));
            assertThat(node.offset()).isEmpty();
            assertThat(node.count()).isEqualTo(10L);
        }

        @Test
        @DisplayName("Limit rejects negative offset")
        void limitRejectsNegativeOffset() {
            assertThatThrownBy(() -> new LimitNode(Optional.of(-1L), 10L, new RelationNode("R")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Limit rejects negative count")
        void limitRejectsNegativeCount() {
            assertThatThrownBy(() -> new LimitNode(Optional.empty(), -1L, new RelationNode("R")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Limit accepts zero count")
        void limitAcceptsZeroCount() {
            LimitNode node = new LimitNode(Optional.empty(), 0L, new RelationNode("R"));
            assertThat(node.count()).isZero();
        }

        @Test
        @DisplayName("Limit rejects null input relation")
        void limitRejectsNullInput() {
            assertThatThrownBy(() -> new LimitNode(Optional.empty(), 10L, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // TopKNode
    // =========================================================================

    @Nested
    @DisplayName("Top-k Validations")
    class TopKValidation {

        private static SortSpecification spec() {
            return desc("v");
        }

        @Test
        @DisplayName("rejects negative count")
        void rejectsNegativeCount() {
            assertThatThrownBy(() -> new TopKNode(List.of("k"), List.of(spec()),
                    Optional.empty(), -1L, new RelationNode("R"), SourceLocation.UNKNOWN))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects negative offset")
        void rejectsNegativeOffset() {
            assertThatThrownBy(() -> new TopKNode(List.of("k"), List.of(spec()),
                    Optional.of(-1L), 3L, new RelationNode("R"), SourceLocation.UNKNOWN))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects null input relation")
        void rejectsNullInput() {
            assertThatThrownBy(() -> new TopKNode(List.of("k"), List.of(spec()), 3L, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("accepts zero count and copies its lists defensively")
        void acceptsZeroCount() {
            TopKNode node = new TopKNode(List.of("k"), List.of(spec()), 0L, new RelationNode("R"));
            assertThat(node.count()).isZero();
            assertThat(node.offset()).isEmpty();
        }
    }

    // =========================================================================
    // AggregationNode
    // =========================================================================

    @Nested
    @DisplayName("Aggregation Validations")
    class AggregationValidation {

        @Test
        @DisplayName("Accepts null grouping attributes (ungrouped aggregation)")
        void acceptsNullGrouping() {
            AggregationNode node = new AggregationNode(null,
                    List.of(AggregateFunction.simple(AggregateOperator.COUNT, "id")),
                    new RelationNode("R"));
            // The convenience constructor passes a null grouping list straight through
            // rather than normalising it, which is what "ungrouped" means here.
            assertThat(node.groupingKeys()).isNull();
        }

        @Test
        @DisplayName("Accepts null aggregates list")
        void acceptsNullAggregates() {
            AggregationNode node = new AggregationNode(List.of(), null, new RelationNode("R"));
            assertThat(node.aggregates()).isNull();
        }

        @Test
        @DisplayName("Accepts empty aggregates list")
        void acceptsEmptyAggregates() {
            AggregationNode node = new AggregationNode(List.of(), List.of(), new RelationNode("R"));
            assertThat(node.aggregates()).isEmpty();
        }

        @Test
        @DisplayName("Accepts null input relation")
        void acceptsNullInput() {
            AggregationNode node = new AggregationNode(
                    List.of(),
                    List.of(AggregateFunction.simple(AggregateOperator.COUNT, "id")),
                    null);
            assertThat(node.input()).isNull();
        }

        @Test
        @DisplayName("Accepts aggregation with grouping attributes")
        void acceptsValidAggregationWithGrouping() {
            AggregationNode node = new AggregationNode(
                    List.of("dept"),
                    List.of(AggregateFunction.simple(AggregateOperator.SUM, "salary")),
                    new RelationNode("Employees"));
            assertThat(node.groupingKeys()).hasSize(1);
            assertThat(node.aggregates()).hasSize(1);
        }

        @Test
        @DisplayName("Accepts aggregation without grouping attributes")
        void acceptsValidAggregationWithoutGrouping() {
            AggregationNode node = new AggregationNode(
                    List.of(),
                    List.of(AggregateFunction.aliased(AggregateOperator.COUNT, "id", "total_count")),
                    new RelationNode("Orders"));
            assertThat(node.groupingKeys()).isEmpty();
            assertThat(node.aggregates()).hasSize(1);
        }
    }

    // =========================================================================
    // SortSpecification.nullPlacement (Phase C2 — ADR-0009)
    // =========================================================================

    @Nested
    @DisplayName("SortSpecification.nullPlacement()")
    class SortSpecificationNullPlacement {

        @Test
        @DisplayName("ASC direction maps to NULLS_LAST (SQL standard default)")
        void ascMapsToNullsLast() {
            SortSpecification spec = asc("col");
            assertThat(spec.nullPlacement()).isEqualTo(NullPlacement.NULLS_LAST);
        }

        @Test
        @DisplayName("DESC direction maps to NULLS_FIRST (SQL standard default)")
        void descMapsToNullsFirst() {
            SortSpecification spec = desc("col");
            assertThat(spec.nullPlacement()).isEqualTo(NullPlacement.NULLS_FIRST);
        }

        @Test
        @DisplayName("nullPlacement is consistent for any attribute name")
        void consistentForAnyAttribute() {
            assertThat(asc("x").nullPlacement())
                    .isEqualTo(NullPlacement.NULLS_LAST);
            assertThat(desc("y").nullPlacement())
                    .isEqualTo(NullPlacement.NULLS_FIRST);
        }
    }
}

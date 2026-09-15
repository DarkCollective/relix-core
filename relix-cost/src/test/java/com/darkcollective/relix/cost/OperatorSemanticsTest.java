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
package com.darkcollective.relix.cost;

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OperatorSemantics — shared operator classification")
final class OperatorSemanticsTest {

    private static Predicate pred() {
        return cmp(attr("x"),
                ComparisonOperator.GREATER, num("0"));
    }

    // =========================================================================
    // isLeftFilterOperator
    // =========================================================================

    @Nested
    @DisplayName("isLeftFilterOperator")
    class IsLeftFilterOperator {

        @Test
        @DisplayName("⋉ (semi-join) is a left-filter operator")
        void semiJoin() {
            assertThat(OperatorSemantics.isLeftFilterOperator(
                    AstBuilders.semiJoin(rel("L"), rel("R"), pred()))).isTrue();
        }

        @Test
        @DisplayName("▷ (anti-join) is a left-filter operator")
        void antiJoin() {
            assertThat(OperatorSemantics.isLeftFilterOperator(
                    AstBuilders.antiJoin(rel("L"), rel("R"), pred()))).isTrue();
        }

        @Test
        @DisplayName("pairwise-∀ is a left-filter operator")
        void pairwiseUniversal() {
            assertThat(OperatorSemantics.isLeftFilterOperator(
                    AstBuilders.pairwiseUniversal(rel("L"), rel("R"), pred()))).isTrue();
        }

        @Test
        @DisplayName("− and ÷ are NOT left-filter operators (they are set-producing)")
        void differenceAndDivisionAreNotLeftFilter() {
            assertThat(OperatorSemantics.isLeftFilterOperator(
                    difference(rel("L"), rel("R")))).isFalse();
            assertThat(OperatorSemantics.isLeftFilterOperator(
                    division(rel("L"), rel("R")))).isFalse();
        }

        @Test
        @DisplayName("inner joins, product, union, selection, projection are NOT left-filter operators")
        void otherOperatorsAreNotLeftFilter() {
            assertThat(OperatorSemantics.isLeftFilterOperator(
                    naturalJoin(rel("L"), rel("R")))).isFalse();
            assertThat(OperatorSemantics.isLeftFilterOperator(
                    join(rel("L"), rel("R"), pred()))).isFalse();
            assertThat(OperatorSemantics.isLeftFilterOperator(
                    product(rel("L"), rel("R")))).isFalse();
            assertThat(OperatorSemantics.isLeftFilterOperator(
                    union(rel("L"), rel("R")))).isFalse();
            assertThat(OperatorSemantics.isLeftFilterOperator(
                    select(pred(), rel("R")))).isFalse();
            assertThat(OperatorSemantics.isLeftFilterOperator(
                    distinct(rel("R")))).isFalse();
            assertThat(OperatorSemantics.isLeftFilterOperator(rel("R"))).isFalse();
        }
    }

    // =========================================================================
    // preservesInputOrder
    // =========================================================================

    @Nested
    @DisplayName("preservesInputOrder")
    class PreservesInputOrder {

        @Test
        @DisplayName("σ (selection) preserves order")
        void selection() {
            assertThat(OperatorSemantics.preservesInputOrder(
                    select(pred(), rel("R")))).isTrue();
        }

        @Test
        @DisplayName("λ (limit) preserves order")
        void limit() {
            assertThat(OperatorSemantics.preservesInputOrder(
                    AstBuilders.limit(10L, rel("R")))).isTrue();
        }

        @Test
        @DisplayName("relation-only ρ (rename) preserves order")
        void relationOnlyRename() {
            assertThat(OperatorSemantics.preservesInputOrder(
                    rename("NewName", List.of(), rel("R")))).isTrue();
        }

        @Test
        @DisplayName("column-renaming ρ does NOT preserve order (column names are invalidated)")
        void columnRenamingRenameDoesNotPreserveOrder() {
            assertThat(OperatorSemantics.preservesInputOrder(
                    rename("NewName", List.of("a", "b"), rel("R")))).isFalse();
        }

        @Test
        @DisplayName("AS-OF preserves order — it emits one output row per left row, in order")
        void asOfJoin() {
            assertThat(OperatorSemantics.preservesInputOrder(
                    AstBuilders.asOfJoin(rel("Trades"), rel("Quotes"), pred()))).isTrue();
        }

        @Test
        @DisplayName("τ (sort) does NOT preserve order — it establishes a new order")
        void sortDoesNotPreserveOrder() {
            assertThat(OperatorSemantics.preservesInputOrder(
                    sort(List.of(asc("x")),
                            rel("R")))).isFalse();
        }

        @Test
        @DisplayName("π, δ, γ, ⋉, ▷, ⋈, ∪, ⊎ do NOT preserve order")
        void otherOperatorsDoNotPreserveOrder() {
            assertThat(OperatorSemantics.preservesInputOrder(
                    project(List.of(projected(
                            attr("x"))), rel("R")))).isFalse();
            assertThat(OperatorSemantics.preservesInputOrder(
                    distinct(rel("R")))).isFalse();
            assertThat(OperatorSemantics.preservesInputOrder(
                    groupBy(List.of("x"),
                            List.of(AggregateFunction.simple(AggregateOperator.COUNT, "y")),
                            rel("R")))).isFalse();
            assertThat(OperatorSemantics.preservesInputOrder(
                    semiJoin(rel("L"), rel("R"), pred()))).isFalse();
            assertThat(OperatorSemantics.preservesInputOrder(
                    antiJoin(rel("L"), rel("R"), pred()))).isFalse();
            assertThat(OperatorSemantics.preservesInputOrder(
                    naturalJoin(rel("L"), rel("R")))).isFalse();
            assertThat(OperatorSemantics.preservesInputOrder(
                    union(rel("L"), rel("R")))).isFalse();
            assertThat(OperatorSemantics.preservesInputOrder(
                    unionAll(rel("L"), rel("R")))).isFalse();
            assertThat(OperatorSemantics.preservesInputOrder(rel("R"))).isFalse();
        }
    }
}

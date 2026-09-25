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

import com.darkcollective.relix.ast.Ordering;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SortSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import com.darkcollective.relix.ast.AstBuilders;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OrderDeriver — delivered ordering, bottom-up")
final class OrderDeriverTest {

    private static SortNode sortBy(RelNode in, String... cols) {
        return sort(Arrays.stream(cols).map(AstBuilders::asc).toList(), in);
    }
    private static Predicate pred() {
        return cmp(attr("x"),
                ComparisonOperator.GREATER, num("0"));
    }

    @Test
    @DisplayName("τ delivers its sort keys")
    void sortEstablishes() {
        assertThat(OrderDeriver.derive(sortBy(rel("R"), "x", "y")))
                .isEqualTo(Ordering.of(List.of(asc("x"), asc("y"))));
    }

    @Test
    @DisplayName("a base relation delivers no order")
    void baseRelationNone() {
        assertThat(OrderDeriver.derive(rel("R"))).isEqualTo(Ordering.none());
    }

    @Nested
    @DisplayName("order-preserving operators carry the input's order")
    class Preservers {

        @Test
        @DisplayName("σ preserves order")
        void selection() {
            var node = new com.darkcollective.relix.ast.SelectionNode(pred(), sortBy(rel("R"), "x"));
            assertThat(OrderDeriver.derive(node)).isEqualTo(Ordering.of(List.of(asc("x"))));
        }

        @Test
        @DisplayName("λ preserves order")
        void limit() {
            var node = AstBuilders.limit(10L, sortBy(rel("R"), "x"));
            assertThat(OrderDeriver.derive(node)).isEqualTo(Ordering.of(List.of(asc("x"))));
        }

        @Test
        @DisplayName("a relation-only rename preserves order")
        void relationOnlyRename() {
            var node = rename("X", List.of(), sortBy(rel("R"), "x"));
            assertThat(OrderDeriver.derive(node)).isEqualTo(Ordering.of(List.of(asc("x"))));
        }

        @Test
        @DisplayName("σ over λ over τ preserves order through the chain")
        void chain() {
            var node = new com.darkcollective.relix.ast.SelectionNode(pred(),
                    AstBuilders.limit(5L, sortBy(rel("R"), "x", "y")));
            assertThat(OrderDeriver.derive(node)).isEqualTo(Ordering.of(List.of(asc("x"), asc("y"))));
        }
    }

    @Nested
    @DisplayName("order-clearing operators deliver no order")
    class Clears {

        @Test
        @DisplayName("a column-renaming rename clears order (key names would be stale)")
        void columnRename() {
            var node = rename("X", List.of("a"), sortBy(rel("R"), "x"));
            assertThat(OrderDeriver.derive(node)).isEqualTo(Ordering.none());
        }

        @Test
        @DisplayName("π clears order conservatively")
        void projection() {
            var node = project(
                    List.of(projected(attr("x"))),
                    sortBy(rel("R"), "x"));
            assertThat(OrderDeriver.derive(node)).isEqualTo(Ordering.none());
        }

        @Test
        @DisplayName("δ clears order conservatively")
        void distinct() {
            assertThat(OrderDeriver.derive(AstBuilders.distinct(sortBy(rel("R"), "x"))))
                    .isEqualTo(Ordering.none());
        }

        @Test
        @DisplayName("ω (WHY) clears order — blocking reification barrier")
        void why() {
            assertThat(OrderDeriver.derive(
                    new com.darkcollective.relix.ast.WhyNode(sortBy(rel("R"), "x"))))
                    .isEqualTo(Ordering.none());
        }
    }
}

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
package com.darkcollective.relix.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.visitor.PrettyPrinter;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;


/**
 * Visitor Round-Trip Testing
 * Tests that parsing → pretty-printing → parsing produces equivalent AST nodes.
 * This verifies both the visitors and the parser handle all node types correctly.
 */
@DisplayName("Visitor Round-Trip Tests")
final class VisitorRoundTripTest extends ParserTestSupport {

    private final PrettyPrinter printer = new PrettyPrinter();

    @Nested
    @DisplayName("RelationNode Round-Trip")
    class RelationNodeRoundTrip {
        @Test
        public void relationNodeRoundTrips() {
            RelationNode original = rel("Users");
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            assertThat(stripLocations(reparsed)).isEqualTo(stripLocations(original));
        }
    }

    @Nested
    @DisplayName("ProjectionNode Round-Trip")
    class ProjectionNodeRoundTrip {
        @Test
        public void simpleProjectionRoundTrips() {
            ProjectionNode original = project(
                    List.of(projected(attr("id")), projected(attr("name"))),
                    rel("Users"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            // Verify idempotency
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void projectionWithAliasRoundTrips() {
            ProjectionNode original = project(
                    List.of(projected(attr("id"), "user_id")),
                    rel("Users"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void projectionWithArithmeticRoundTrips() {
            ProjectionNode original = project(
                    List.of(projected(arith(attr("price"), ArithmeticOperator.MULTIPLY, num("1.1")))),
                    rel("Products"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void projectionWithFunctionRoundTrips() {
            ProjectionNode original = project(
                    List.of(projected(func("UPPER",attr("name")))),
                    rel("Users"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }
    }

    @Nested
    @DisplayName("SelectionNode Round-Trip")
    class SelectionNodeRoundTrip {
        @Test
        public void simpleSelectionRoundTrips() {
            SelectionNode original = select(
                    cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                    rel("Users"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void selectionWithAndRoundTrips() {
            SelectionNode original = select(
                    and(
                            cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                            cmp(attr("status"), ComparisonOperator.EQUAL, str("active"))),
                    rel("Users"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void selectionWithNotRoundTrips() {
            SelectionNode original = select(
                    not(cmp(attr("archived"), ComparisonOperator.EQUAL, bool(true))),
                    rel("Posts"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void selectionWithOrRoundTrips() {
            SelectionNode original = select(
                    or(
                            cmp(attr("age"), ComparisonOperator.GREATER, num("65")),
                            cmp(attr("status"), ComparisonOperator.EQUAL, str("vip"))),
                    rel("Users"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }
    }

    @Nested
    @DisplayName("Join Node Round-Trip")
    class JoinNodeRoundTrip {
        @Test
        public void naturalJoinRoundTrips() {
            NaturalJoinNode original = naturalJoin(
                    rel("Users"),
                    rel("Orders"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void thetaJoinRoundTrips() {
            ThetaJoinNode original = join(
                    rel("Users"),
                    rel("Orders"),
                    cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void leftOuterJoinRoundTrips() {
            LeftOuterJoinNode original = leftJoin(
                    rel("Users"),
                    rel("Orders"),
                    cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void rightOuterJoinRoundTrips() {
            RightOuterJoinNode original = rightJoin(
                    rel("Users"),
                    rel("Orders"),
                    cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void fullOuterJoinRoundTrips() {
            FullOuterJoinNode original = fullJoin(
                    rel("Users"),
                    rel("Orders"),
                    cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void semiJoinRoundTrips() {
            SemiJoinNode original = semiJoin(
                    rel("Users"),
                    rel("Orders"),
                    cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void antiJoinRoundTrips() {
            AntiJoinNode original = antiJoin(
                    rel("Users"),
                    rel("Orders"),
                    cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }
    }

    @Nested
    @DisplayName("Set Operation Round-Trip")
    class SetOperationRoundTrip {
        @Test
        public void unionRoundTrips() {
            UnionNode original = union(
                    rel("A"),
                    rel("B"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void unionAllRoundTrips() {
            UnionAllNode original = unionAll(
                    rel("A"),
                    rel("B"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void differenceRoundTrips() {
            DifferenceNode original = difference(
                    rel("A"),
                    rel("B"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void intersectionRoundTrips() {
            IntersectionNode original = intersection(
                    rel("A"),
                    rel("B"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void divisionRoundTrips() {
            DivisionNode original = division(
                    rel("A"),
                    rel("B"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void productRoundTrips() {
            ProductNode original = product(
                    rel("A"),
                    rel("B"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }
    }

    @Nested
    @DisplayName("Unary Operation Round-Trip")
    class UnaryOperationRoundTrip {
        @Test
        public void renameRoundTrips() {
            RenameNode original = rename("R2", List.of("a", "b"), rel("R"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void distinctRoundTrips() {
            DistinctNode original = distinct(rel("Users"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void sortRoundTrips() {
            SortNode original = sort(
                    List.of(asc("name")),
                    rel("Users"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void sortMultipleAttributesRoundTrips() {
            SortNode original = sort(
                    List.of(
                            asc("dept"),
                            desc("salary")
                    ),
                    rel("Employees"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void limitRoundTrips() {
            LimitNode original = limit(Optional.empty(), 10L, rel("Orders"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void limitWithOffsetRoundTrips() {
            LimitNode original = limit(Optional.of(5L), 10L, rel("Orders"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }
    }

    @Nested
    @DisplayName("Aggregation Round-Trip")
    class AggregationRoundTrip {
        @Test
        public void aggregationWithGroupingRoundTrips() {
            AggregationNode original = groupBy(
                    List.of("dept"),
                    List.of(AggregateFunction.aliased(AggregateOperator.SUM, "salary", "total")),
                    rel("Employees"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void aggregationWithoutGroupingRoundTrips() {
            AggregationNode original = groupBy(
                    List.of(),
                    List.of(AggregateFunction.aliased(AggregateOperator.COUNT, "id", "total_count")),
                    rel("Orders"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void aggregationWithMultipleFunctionsRoundTrips() {
            AggregationNode original = groupBy(
                    List.of("category"),
                    List.of(
                            AggregateFunction.aliased(AggregateOperator.SUM, "amount", "total"),
                            AggregateFunction.aliased(AggregateOperator.AVG, "price", "avg_price"),
                            AggregateFunction.simple(AggregateOperator.COUNT, "id")
                    ),
                    rel("Sales"));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }
    }

    @Nested
    @DisplayName("Complex Nested Round-Trip")
    class ComplexNestedRoundTrip {
        @Test
        public void projectionSelectionRoundTrips() {
            ProjectionNode original = project(
                    List.of(projected(attr("id")), projected(attr("name"))),
                    select(
                            cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                            rel("Users")));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void sortProjectionRoundTrips() {
            SortNode original = sort(
                    List.of(desc("salary")),
                    project(
                            List.of(projected(attr("id")), projected(attr("salary"))),
                            rel("Employees")));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void limitSortProjectionSelectionRoundTrips() {
            // π id, name (σ active = true (τ name (λ 100 (Users))))
            LimitNode original = limit(
                    Optional.empty(),
                    100L,
                    sort(
                            List.of(asc("name")),
                            select(
                                    cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                    rel("Users"))));
            String printed = original.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void aggregationSortRoundTrips() {
            AggregationNode original = groupBy(
                    List.of("category"),
                    List.of(AggregateFunction.aliased(AggregateOperator.SUM, "amount", "total")),
                    rel("Sales"));
            SortNode sorted = sort(
                    List.of(desc("total")),
                    original);
            String printed = sorted.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }

        @Test
        public void joinWithProjectionRoundTrips() {
            ThetaJoinNode original = join(
                    rel("Users"),
                    rel("Orders"),
                    cmp(attr("Users.id"), ComparisonOperator.EQUAL, attr("Orders.user_id")));
            ProjectionNode projected = project(
                    List.of(projected(attr("Users.id")), projected(attr("name")), projected(attr("Orders.id"))),
                    original);
            String printed = projected.accept(printer);
            RelNode reparsed = RelAlgebraParser.parse(printed);
            String reprinted = reparsed.accept(printer);
            assertThat(reprinted).isEqualTo(printed);
        }
    }

}


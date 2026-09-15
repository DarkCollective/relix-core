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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import com.darkcollective.relix.symbol.table.SymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RelationDeterminism} — "would evaluating this expression twice
 * necessarily give the same relation?", the precondition of every rewrite that changes
 * how many times an expression is evaluated.
 */
@DisplayName("RelationDeterminism")
final class RelationDeterminismTest {

    private static final RelationNode ORDERS = rel("Orders");

    private static InMemorySymbolTable table() {
        var table = new InMemorySymbolTable();
        table.register(SourceRelationSymbol.of("Orders", Schema.empty()));
        return table;
    }

    private static boolean deterministic(RelNode node) {
        return deterministic(node, table());
    }

    private static boolean deterministic(RelNode node, SymbolTable symbols) {
        return RelationDeterminism.isDeterministic(node, symbols, SemanticFixtures.FUNCTIONS);
    }

    private static ProjectionNode projectOf(Operand expression, RelNode input) {
        return project(
                List.of(projected(expression,"out")), input);
    }

    @Nested
    @DisplayName("reproducible")
    class Reproducible {

        @Test
        @DisplayName("a bare base relation")
        void baseRelation() {
            assertThat(deterministic(ORDERS)).isTrue();
        }

        @Test
        @DisplayName("a filter and projection over ordinary columns")
        void plainExpression() {
            RelNode node = projectOf(attr("amount"),
                    select(cmp(attr("cid"),
                            ComparisonOperator.EQUAL, num("2")), ORDERS));

            assertThat(deterministic(node)).isTrue();
        }

        @Test
        @DisplayName("a deterministic builtin")
        void deterministicBuiltin() {
            assertThat(deterministic(projectOf(
                    func("Abs",attr("delta")), ORDERS)))
                    .isTrue();
        }

        @Test
        @DisplayName("a seeded SAMPLE — reproducible by construction")
        void seededSample() {
            assertThat(deterministic(sample(0.5, Optional.of(42L), ORDERS))).isTrue();
        }

        @Test
        @DisplayName("a view whose body reads nothing volatile")
        void plainView() {
            var symbols = table();
            symbols.register(QueryRelationSymbol.of("Recent", Schema.empty(),
                    projectOf(attr("amount"), ORDERS)));

            assertThat(deterministic(rel("Recent"), symbols)).isTrue();
        }
    }

    @Nested
    @DisplayName("irreproducible — a function that reads system state")
    class VolatileFunctions {

        @Test
        @DisplayName("Rand() in a projection")
        void randInProjection() {
            assertThat(deterministic(projectOf(func("Rand"), ORDERS)))
                    .isFalse();
        }

        @Test
        @DisplayName("NOW() inside a predicate")
        void nowInPredicate() {
            RelNode node = select(cmp(
                    attr("at"), ComparisonOperator.LESS,
                    func("NOW")), ORDERS);

            assertThat(deterministic(node)).isFalse();
        }

        @Test
        @DisplayName("a user-defined scalar function, which the registry does not know")
        void unknownScalarFunction() {
            assertThat(deterministic(projectOf(
                    func("myUdf",num("1")), ORDERS)))
                    .isFalse();
        }

        @Test
        @DisplayName("volatility below a join is found — the walk covers every child")
        void volatilityInAJoinBranch() {
            RelNode node = join(ORDERS,
                    projectOf(func("Rand"), ORDERS),
                    cmp(attr("x"),
                            ComparisonOperator.EQUAL, attr("y")));

            assertThat(deterministic(node)).isFalse();
        }

        @Test
        @DisplayName("volatility in a TVF call's arguments, which children() hides")
        void volatilityInTvfArguments() {
            var symbols = table();
            symbols.register(RelationFunctionSymbol.builder("ordersFor")
                    .parameter("cid", ScalarType.NUMBER).body(ORDERS).build());
            RelNode node = tvf("ordersFor",func("Rand"));

            assertThat(deterministic(node, symbols))
                    .as("the argument is not a structural child of the call")
                    .isFalse();
        }

        @Test
        @DisplayName("volatility in a LATERAL join's arguments, which children() also hides")
        void volatilityInLateralArguments() {
            var symbols = table();
            symbols.register(RelationFunctionSymbol.builder("ordersFor")
                    .parameter("cid", ScalarType.NUMBER).body(ORDERS).build());
            RelNode node = lateral(ORDERS, "ordersFor",func("Rand"));

            assertThat(deterministic(node, symbols)).isFalse();
        }
    }

    @Nested
    @DisplayName("irreproducible — an operator that reads system state")
    class VolatileOperators {

        @Test
        @DisplayName("an unseeded SAMPLE")
        void unseededSample() {
            assertThat(deterministic(sample(0.5, ORDERS))).isFalse();
        }

        @Test
        @DisplayName("an unseeded SAMPLE buried below other operators")
        void unseededSampleBelow() {
            assertThat(deterministic(projectOf(attr("amount"),
                    sample(0.5, ORDERS)))).isFalse();
        }
    }

    @Nested
    @DisplayName("through a name")
    class ThroughNames {

        @Test
        @DisplayName("a view whose body is volatile — the reference alone looks innocent")
        void volatileView() {
            var symbols = table();
            symbols.register(QueryRelationSymbol.of("Jittered", Schema.empty(),
                    projectOf(func("Rand"), ORDERS)));

            assertThat(deterministic(rel("Jittered"), symbols)).isFalse();
        }

        @Test
        @DisplayName("a nested TVF whose body is volatile")
        void volatileNestedTvf() {
            var symbols = table();
            symbols.register(RelationFunctionSymbol.builder("jitter")
                    .parameter("cid", ScalarType.NUMBER)
                    .body(projectOf(func("Rand"), ORDERS)).build());

            assertThat(deterministic(
                    tvf("jitter",num("1")), symbols))
                    .isFalse();
        }

        @Test
        @DisplayName("two levels of view still resolve to the volatility at the bottom")
        void volatilityTwoLevelsDown() {
            var symbols = table();
            symbols.register(QueryRelationSymbol.of("Inner", Schema.empty(),
                    projectOf(func("Rand"), ORDERS)));
            symbols.register(QueryRelationSymbol.of("Outer", Schema.empty(),
                    rel("Inner")));

            assertThat(deterministic(rel("Outer"), symbols)).isFalse();
        }

        @Test
        @DisplayName("an unresolvable relation is not vouched for")
        void unresolvableRelation() {
            assertThat(deterministic(rel("Nowhere"), new InMemorySymbolTable()))
                    .as("a name the analysis cannot see through is one it cannot vouch for")
                    .isFalse();
        }

        @Test
        @DisplayName("an unresolvable table-valued function is not vouched for")
        void unresolvableFunction() {
            assertThat(deterministic(tvf("nowhere")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("termination")
    class Termination {

        @Test
        @DisplayName("a self-referential view does not spin")
        void selfReferentialView() {
            var symbols = table();
            symbols.register(QueryRelationSymbol.of("Loop", Schema.empty(),
                    rel("Loop")));

            assertThat(deterministic(rel("Loop"), symbols))
                    .as("the name is already on the walk stack, so it adds nothing new")
                    .isTrue();
        }

        @Test
        @DisplayName("mutually recursive views do not spin, and volatility in one is still found")
        void mutuallyRecursiveViews() {
            var symbols = table();
            symbols.register(QueryRelationSymbol.of("Ping", Schema.empty(),
                    rel("Pong")));
            symbols.register(QueryRelationSymbol.of("Pong", Schema.empty(),
                    projectOf(func("Rand"), rel("Ping"))));

            assertThat(deterministic(rel("Ping"), symbols)).isFalse();
        }

        @Test
        @DisplayName("a TVF that calls itself does not spin")
        void selfReferentialTvf() {
            var symbols = table();
            symbols.register(RelationFunctionSymbol.builder("loop")
                    .parameter("n", ScalarType.NUMBER)
                    .body(tvf("loop",num("1")))
                    .build());

            assertThat(deterministic(
                    tvf("loop",num("1")), symbols))
                    .isTrue();
        }
    }
}

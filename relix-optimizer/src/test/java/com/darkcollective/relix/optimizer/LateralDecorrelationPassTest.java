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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.LateralJoinNode;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import com.darkcollective.relix.symbol.table.SymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Lateral decorrelation — LATERAL-001")
final class LateralDecorrelationPassTest {

    private OptimizationContext ctx;
    private SymbolTable symbols;

    @BeforeEach
    void setUp() {
        ctx = OptimizerFixtures.context();
        symbols = tableWith(deterministicBody());
    }

    /**
     * A table carrying the two functions the fixtures call — {@code ordersFor(cid)} and
     * {@code topOrders(cid, lim)} — over the supplied body, plus the {@code Orders}
     * relation the body reads.
     */
    private static SymbolTable tableWith(RelNode body) {
        var table = baseTable();
        table.register(RelationFunctionSymbol.builder("ordersFor")
                .parameter("cid", ScalarType.NUMBER).body(body).build());
        table.register(RelationFunctionSymbol.builder("topOrders")
                .parameter("cid", ScalarType.NUMBER)
                .parameter("lim", ScalarType.NUMBER).body(body).build());
        table.register(RelationFunctionSymbol.builder("allOrders").body(body).build());
        return table;
    }

    /**
     * A table holding just the {@code Orders} base relation.  Registering it matters:
     * a name the analysis cannot resolve is one it cannot vouch for, so an unregistered
     * relation reads as non-deterministic and blocks the rule.
     */
    private static InMemorySymbolTable baseTable() {
        var table = new InMemorySymbolTable();
        table.register(SourceRelationSymbol.of("Orders", Schema.empty()));
        return table;
    }

    /** {@code σ customer_id = cid (Orders)} — reads nothing volatile. */
    private static RelNode deterministicBody() {
        return select(cmp(attr("customer_id"),
                ComparisonOperator.EQUAL, attr("cid")), rel("Orders"));
    }

    private RelNode apply(RelNode node) {
        return LateralDecorrelationPass.apply(node, symbols, ctx, "Q");
    }

    private int firings() {
        return ctx.recordsFor(OptimizationCode.LATERAL_001).size();
    }

    private static LateralJoinNode lateral(RelNode left, Operand... args) {
        return AstBuilders.lateral(left, "ordersFor",args);
    }

    @Nested
    @DisplayName("LATERAL-001 — fires")
    class Fires {

        @Test
        @DisplayName("a constant argument decorrelates to L × f(c)")
        void constantArgument() {
            RelNode result = apply(lateral(rel("Customers"), num("2")));

            assertThat(result).isEqualTo(product(rel("Customers"),
                    tvf("ordersFor",num("2"))));
            assertThat(firings()).isEqualTo(1);
        }

        @Test
        @DisplayName("the TVF call keeps the function name and every argument, in order")
        void argumentsPreserved() {
            RelNode result = apply(AstBuilders.lateral(rel("Customers"), "topOrders",num("2"), str("open")));

            var call = (RelationFunctionCall) ((ProductNode) result).right();
            assertThat(call.functionName()).isEqualTo("topOrders");
            assertThat(call.arguments())
                    .containsExactly(num("2"), str("open"));
        }

        @Test
        @DisplayName("a zero-parameter TVF has nothing to correlate on")
        void zeroArgumentFunction() {
            RelNode node = AstBuilders.lateral(rel("Customers"), "allOrders");

            assertThat(apply(node)).isNode(ProductNode.class);
            assertThat(firings()).isEqualTo(1);
        }

        @Test
        @DisplayName("an argument built only from literals is uncorrelated")
        void constantArithmetic() {
            RelNode result = apply(lateral(rel("Customers"),
                    arith(num("1"),
                            ArithmeticOperator.PLUS, num("1"))));

            assertThat(result).isNode(ProductNode.class);
            assertThat(firings()).isEqualTo(1);
        }

        @Test
        @DisplayName("a deterministic builtin over literals is uncorrelated")
        void deterministicBuiltin() {
            RelNode result = apply(lateral(rel("Customers"),
                    func("Abs",num("-2"))));

            assertThat(result).isNode(ProductNode.class);
            assertThat(firings()).isEqualTo(1);
        }

        @Test
        @DisplayName("a lateral nested below another operator is reached")
        void nestedBelowSelection() {
            var predicate = cmp(attr("amount"),
                    ComparisonOperator.GREATER, num("60"));
            RelNode result = apply(select(predicate,
                    lateral(rel("Customers"), num("2"))));

            assertThat(result).isNode(SelectionNode.class);
            assertThat(((SelectionNode) result).input()).isNode(ProductNode.class);
            assertThat(firings()).isEqualTo(1);
        }

        @Test
        @DisplayName("stacked uncorrelated laterals both decorrelate in one traversal")
        void stackedLaterals() {
            RelNode result = apply(lateral(
                    lateral(rel("Customers"), num("2")),
                    num("3")));

            assertThat(result).isNode(ProductNode.class);
            assertThat(((ProductNode) result).left()).isNode(ProductNode.class);
            assertThat(firings()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("LATERAL-001 — does not fire")
    class DoesNotFire {

        @Test
        @DisplayName("a bare column argument is the correlated case")
        void columnArgument() {
            RelNode node = lateral(rel("Customers"), attr("customer_id"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a column buried inside an arithmetic argument still correlates")
        void columnInsideExpression() {
            RelNode node = lateral(rel("Customers"), arith(
                    attr("customer_id"), ArithmeticOperator.PLUS,
                    num("1")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a column in the second of two arguments still correlates")
        void columnInLaterArgument() {
            RelNode node = AstBuilders.lateral(rel("Customers"), "topOrders",num("2"), attr("min_amount"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a column inside a function-call argument still correlates")
        void columnInsideFunctionCall() {
            RelNode node = lateral(rel("Customers"),
                    func("Abs",attr("delta")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a non-deterministic argument is left per-row (Rand() is drawn per left row)")
        void nonDeterministicArgument() {
            RelNode node = lateral(rel("Customers"), func("Rand"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a clock-reading argument is left per-row")
        void clockReadingArgument() {
            RelNode node = lateral(rel("Customers"), func("NOW"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a user-defined scalar function is not known deterministic")
        void unknownFunction() {
            RelNode node = lateral(rel("Customers"),
                    func("myUdf",num("1")));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a body reading Rand() keeps its per-row evaluation")
        void volatileBody() {
            symbols = tableWith(project(
                    List.of(projected(func("Rand"),"r")),
                    rel("Orders")));
            RelNode node = lateral(rel("Customers"), num("2"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a body drawing an unseeded SAMPLE keeps its per-row evaluation")
        void unseededSampleBody() {
            symbols = tableWith(sample(0.5, rel("Orders")));
            RelNode node = lateral(rel("Customers"), num("2"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a SEED makes the same SAMPLE reproducible, so the rule fires")
        void seededSampleBody() {
            symbols = tableWith(sample(0.5, Optional.of(42L), rel("Orders")));

            assertThat(apply(lateral(rel("Customers"), num("2"))))
                    .isNode(ProductNode.class);
            assertThat(firings()).isEqualTo(1);
        }

        @Test
        @DisplayName("volatility reached only through a view the body reads still blocks it")
        void volatileThroughAView() {
            var table = baseTable();
            table.register(QueryRelationSymbol.of("Jittered", Schema.empty(),
                    project(
                            List.of(projected(func("Rand"),"r")),
                            rel("Orders"))));
            table.register(RelationFunctionSymbol.builder("ordersFor")
                    .parameter("cid", ScalarType.NUMBER)
                    .body(rel("Jittered")).build());
            symbols = table;
            RelNode node = lateral(rel("Customers"), num("2"));

            assertThat(apply(node))
                    .as("the body itself is a bare reference — the volatility is one level down")
                    .isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("an unresolvable function is not vouched for")
        void unknownFunction2() {
            symbols = new InMemorySymbolTable();
            RelNode node = lateral(rel("Customers"), num("2"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("an arity mismatch is not vouched for either")
        void arityMismatch() {
            RelNode node = AstBuilders.lateral(rel("Customers"), "ordersFor",num("2"), num("3"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a tree with no lateral join is returned unchanged")
        void noLateralAtAll() {
            RelNode node = project(
                    List.of(projected(attr("name"))),
                    rel("Customers"));

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }
    }

    @Nested
    @DisplayName("visibility")
    class Visibility {

        @Test
        @DisplayName("the TVF call becomes a structural child, which under the lateral it was not")
        void bodyBecomesVisibleToTraversal() {
            RelNode lateral = lateral(rel("Customers"), num("2"));

            assertThat(lateral.children())
                    .as("the lateral hides the TVF from traversal — only the left input shows")
                    .containsExactly(rel("Customers"));

            assertThat(apply(lateral).children())
                    .as("as a × the call is a child every pass walks")
                    .containsExactly(rel("Customers"),
                            tvf("ordersFor",num("2")));
        }

        @Test
        @DisplayName("re-applying the rule to its own output is a no-op")
        void isIdempotent() {
            RelNode once = apply(lateral(rel("Customers"), num("2")));

            assertThat(apply(once))
                    .as("nothing here reintroduces a LATERAL, so a second pass changes nothing")
                    .isSameAs(once);
            assertThat(firings()).isEqualTo(1);
        }
    }
}

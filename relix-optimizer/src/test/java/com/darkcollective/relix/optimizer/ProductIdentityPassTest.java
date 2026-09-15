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

import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.TruthRelationNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Product-identity elimination — PROD-001")
final class ProductIdentityPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    private RelNode apply(RelNode node) {
        return ProductIdentityPass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private int firings() {
        return ctx.recordsFor(OptimizationCode.PROD_001).size();
    }

    private static TruthRelationNode unit()  { return TruthRelationNode.unit(SourceLocation.UNKNOWN); }

    private static TruthRelationNode empty() { return TruthRelationNode.empty(SourceLocation.UNKNOWN); }

    @Nested
    @DisplayName("PROD-001 — fires")
    class Fires {

        @Test
        @DisplayName("R × UNIT → R")
        void unitOnTheRight() {
            var r = rel("Trades");
            assertThat(apply(product(r, unit()))).isSameAs(r);
            assertThat(firings()).isEqualTo(1);
        }

        @Test
        @DisplayName("UNIT × R → R")
        void unitOnTheLeft() {
            var r = rel("Trades");
            assertThat(apply(product(unit(), r))).isSameAs(r);
            assertThat(firings()).isEqualTo(1);
        }

        @Test
        @DisplayName("UNIT × UNIT → UNIT")
        void bothSidesUnit() {
            RelNode result = apply(product(unit(), unit()));
            assertThat(result).isNode(TruthRelationNode.class);
            assertThat(((TruthRelationNode) result).holdsTuple()).isTrue();
            assertThat(firings()).isEqualTo(1);
        }

        @Test
        @DisplayName("a chain collapses fully in one bottom-up traversal")
        void chainCollapses() {
            var r = rel("Trades");
            RelNode chained = product(product(r, unit()), unit());
            assertThat(apply(chained)).isSameAs(r);
            assertThat(firings()).isEqualTo(2);
        }

        @Test
        @DisplayName("a product buried under another operator is still rewritten")
        void firesBelowAnotherOperator() {
            var r = rel("Trades");
            var pred = cmp(attr("amount"),
                    ComparisonOperator.GREATER, num("0"));
            RelNode result = apply(select(pred, product(r, unit())));
            assertThat(result).isNode(SelectionNode.class);
            assertThat(((SelectionNode) result).input()).isSameAs(r);
            assertThat(firings()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("PROD-001 — does not fire")
    class NoOp {

        @Test
        @DisplayName("R × EMPTY is kept — the result keeps R's heading, so it is not EMPTY")
        void emptyIsNotAnIdentityOrAnAnnihilator() {
            RelNode node = product(rel("Trades"), empty());
            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("EMPTY × R is likewise kept")
        void emptyOnTheLeftIsKept() {
            RelNode node = product(empty(), rel("Trades"));
            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("an ordinary product between two relations is untouched")
        void ordinaryProduct() {
            RelNode node = product(rel("A"), rel("B"));
            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a bare UNIT with no product above it is left alone")
        void bareUnit() {
            RelNode node = unit();
            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }

        @Test
        @DisplayName("a tree with no product is returned unchanged")
        void noProductAtAll() {
            RelNode node = rel("Trades");
            assertThat(apply(node)).isSameAs(node);
            assertThat(firings()).isZero();
        }
    }

    @Nested
    @DisplayName("through the registered pipeline")
    class ThroughThePipeline {

        @Test
        @DisplayName("the cleanup phase runs PROD-001 on a real optimize() call")
        void firesInTheCleanupPhase() {
            var r = rel("Trades");
            var ctx2 = new OptimizationContext();

            RelNode result = new QueryOptimizer().optimize(
                    product(r, unit()), "Q", SchemaAnnotations.empty(), ctx2);

            assertThat(result).isEqualTo(r);
            assertThat(ctx2.recordsFor(OptimizationCode.PROD_001)).hasSize(1);
        }

        @Test
        @DisplayName("no phase-mate reintroduces the product it removed (the pipeline converges)")
        void doesNotOscillate() {
            var ctx2 = new OptimizationContext();
            var pred = cmp(attr("amount"),
                    ComparisonOperator.GREATER, num("0"));

            RelNode result = new QueryOptimizer().optimize(
                    select(pred, product(rel("Trades"), unit())),
                    "Q", SchemaAnnotations.empty(), ctx2);

            assertThat(result).isNode(SelectionNode.class);
            assertThat(((SelectionNode) result).input()).isEqualTo(rel("Trades"));
            assertThat(ctx2.recordsFor(OptimizationCode.PROD_001))
                    .as("fires exactly once — a re-introduced × would fire it again")
                    .hasSize(1);
        }
    }
}

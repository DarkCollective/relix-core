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
package com.darkcollective.relix.embed.equivalence;

import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.internal.OptimizationResult;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertEquivalent;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.fired;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.optimizeFirst;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.run;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * Execution-level <em>equivalence</em> tests for {@code LATERAL-001} (#539): an
 * uncorrelated {@code LATERAL} join must produce identical results as the {@code ×}
 * over a plain TVF call it is rewritten to.
 *
 * <p>The pass unit test ({@code LateralDecorrelationPassTest}) proves the rewrite's
 * shape and its guards; these prove it is answer-preserving over real rows — including
 * the two properties the rewrite silently depends on, the ordered output heading and
 * the left-outer/right-inner row order.
 *
 * <p>Harness: {@link OptimizerEquivalence}.
 */
@DisplayName("LATERAL-001 decorrelation — execution equivalence (optimized == unoptimized)")
final class LateralDecorrelationEquivalenceTest {

    private static final String FIXTURES =
            "def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };\n"
          + "Orders := [\n"
          + "| order_id | customer_id | amount |\n"
          + "|----------|-------------|--------|\n"
          + "| 1        | 2           | 100    |\n"
          + "| 2        | 3           | 50     |\n"
          + "| 3        | 2           | 75     |\n"
          + "];\n"
          + "Customers := [\n"
          + "| customer_id | name  |\n"
          + "|-------------|-------|\n"
          + "| 2           | Alice |\n"
          + "| 3           | Bob   |\n"
          + "];\n";

    @Nested
    @DisplayName("fires — and the answer is unchanged")
    class Fires {

        @Test
        @DisplayName("a constant argument: one call paired with every customer")
        void constantArgument() {
            assertEquivalent(FIXTURES + "query { Customers LATERAL ordersFor(2) };\n",
                    OptimizationCode.LATERAL_001);
        }

        @Test
        @DisplayName("a selection above the decorrelated join keeps the same rows")
        void selectionAbove() {
            assertEquivalent(
                    FIXTURES + "query { σ amount > 80 (Customers LATERAL ordersFor(2)) };\n",
                    OptimizationCode.LATERAL_001);
        }

        @Test
        @DisplayName("a projection above the decorrelated join keeps the same rows")
        void projectionAbove() {
            assertEquivalent(
                    FIXTURES + "query { π name, amount (Customers LATERAL ordersFor(2)) };\n",
                    OptimizationCode.LATERAL_001);
        }

        @Test
        @DisplayName("an arithmetic argument over literals only is still uncorrelated")
        void arithmeticArgument() {
            assertEquivalent(FIXTURES + "query { Customers LATERAL ordersFor(1 + 1) };\n",
                    OptimizationCode.LATERAL_001);
        }

        @Test
        @DisplayName("a TVF returning no rows drops every left row, product and lateral alike")
        void emptyBody() {
            assertEquivalent(FIXTURES + "query { Customers LATERAL ordersFor(99) };\n",
                    OptimizationCode.LATERAL_001);
        }

        @Test
        @DisplayName("the rewritten tree really is a × over the TVF call")
        void rewritesToProduct() {
            SemanticModel m = model(FIXTURES + "query { Customers LATERAL ordersFor(2) };\n");
            OptimizationResult r = optimizeFirst(m);

            assertThat(fired(r, OptimizationCode.LATERAL_001)).isTrue();
            assertThat(r.optimized()).isNode(ProductNode.class);
        }

        @Test
        @DisplayName("the output heading and row order are preserved exactly, not just as a multiset")
        void headingAndOrderPreserved() {
            SemanticModel m = model(FIXTURES + "query { Customers LATERAL ordersFor(2) };\n");
            OptimizationResult r = optimizeFirst(m);

            assertThat(run(r.optimized(), m))
                    .as("× iterates left-outer/right-inner, exactly as the lateral emits")
                    .containsExactlyElementsOf(run(r.original(), m));
        }
    }

    @Nested
    @DisplayName("does not fire — the per-row cases are left alone")
    class DoesNotFire {

        @Test
        @DisplayName("a TVF body reading Rand() is left per-row, even with a constant argument")
        void volatileBody() {
            SemanticModel m = model(
                    "def jitter(cid: NUMBER): RELATION := "
                  + "{ π order_id, Rand() → r (σ customer_id = cid (Orders)) };\n"
                  + FIXTURES
                  + "query { Customers LATERAL jitter(2) };\n");
            OptimizationResult r = optimizeFirst(m);

            assertThat(fired(r, OptimizationCode.LATERAL_001))
                    .as("one body per left row is what the query asked for")
                    .isFalse();
        }

        @Test
        @DisplayName("a TVF body drawing an unseeded SAMPLE is left per-row")
        void unseededSampleBody() {
            SemanticModel m = model(
                    "def someOf(cid: NUMBER): RELATION := "
                  + "{ SAMPLE 0.5 (σ customer_id = cid (Orders)) };\n"
                  + FIXTURES
                  + "query { Customers LATERAL someOf(2) };\n");

            assertThat(fired(optimizeFirst(m), OptimizationCode.LATERAL_001)).isFalse();
        }

        @Test
        @DisplayName("a SEED makes the same body reproducible, so the rule fires")
        void seededSampleBody() {
            assertEquivalent(
                    "def someOf(cid: NUMBER): RELATION := "
                  + "{ SAMPLE 1.0 SEED 42 (σ customer_id = cid (Orders)) };\n"
                  + FIXTURES
                  + "query { Customers LATERAL someOf(2) };\n",
                    OptimizationCode.LATERAL_001);
        }

        @Test
        @DisplayName("a column argument stays a LATERAL and still answers correctly")
        void correlatedArgument() {
            SemanticModel m = model(
                    FIXTURES + "query { Customers LATERAL ordersFor(customer_id) };\n");
            OptimizationResult r = optimizeFirst(m);

            assertThat(fired(r, OptimizationCode.LATERAL_001))
                    .as("a per-row argument must not be decorrelated")
                    .isFalse();
            assertThat(run(r.optimized(), m)).containsExactlyInAnyOrderElementsOf(
                    run(r.original(), m));
        }
    }
}

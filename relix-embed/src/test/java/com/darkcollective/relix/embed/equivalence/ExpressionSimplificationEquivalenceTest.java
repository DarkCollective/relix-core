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

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.internal.OptimizationResult;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.internal.SchemaInference;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.symbol.ColumnDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertEquivalent;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.assertEquivalentInOrder;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.fired;
import static com.darkcollective.relix.embed.equivalence.OptimizerEquivalence.optimizeFirst;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-level <em>equivalence</em> tests for expression simplification reaching the
 * operand carriers it used to skip (#564): folding a redundant {@code * 1} out of a γ
 * aggregate argument, a grouping key, a τ sort key or a {@code LATERAL} argument must
 * not change the answer.
 *
 * <p>The pass unit test ({@code ExpressionSimplificationPassTest.OtherCarriers}) proves
 * each rewrite's shape; these prove it is answer-preserving over real rows — which
 * matters more here than for the σ/π arms, because these positions feed operators that
 * <em>group</em>, <em>order</em> and <em>parameterise a call</em> rather than merely
 * filtering.
 *
 * <p>Harness: {@link OptimizerEquivalence}.
 */
@DisplayName("EXPR-004 at every operand carrier — execution equivalence (#564)")
final class ExpressionSimplificationEquivalenceTest {

    private static final String ORDERS =
            "Orders := [\n"
          + "| cust | region | amount |\n"
          + "|------|--------|--------|\n"
          + "| A    | west   | 10     |\n"
          + "| A    | west   | 30     |\n"
          + "| B    | east   | 5      |\n"
          + "| C    | west   | 70     |\n"
          + "];\n";

    private static final String LATERAL_FIXTURES =
            "def ordersFor(n: NUMBER): RELATION := { σ amount > n (Orders) };\n" + ORDERS
          + "Customers := [\n"
          + "| cust | name  |\n"
          + "|------|-------|\n"
          + "| A    | Alice |\n"
          + "| B    | Bob   |\n"
          + "];\n";

    @Nested
    @DisplayName("γ — the carriers that group")
    class Aggregation {

        @Test
        @DisplayName("an aggregate argument: SUM(amount * 1) sums the same rows as SUM(amount)")
        void aggregateArgument() {
            assertEquivalent(
                    ORDERS
                  + "query { γ cust, SUM(amount * 1) → total (Orders) };\n",
                    OptimizationCode.EXPR_004);
        }

        @Test
        @DisplayName("a grouping key: folding the key does not merge or split groups")
        void groupingKey() {
            assertEquivalent(
                    ORDERS
                  + "query { γ amount * 1 → k, COUNT(cust) → n (Orders) };\n",
                    OptimizationCode.EXPR_004);
        }

        @Test
        @DisplayName("both at once, over two grouping keys")
        void keysAndArguments() {
            assertEquivalent(
                    ORDERS
                  + "query { γ cust, amount * 1 → a, SUM(amount * 1) → total (Orders) };\n",
                    OptimizationCode.EXPR_004);
        }
    }

    @Nested
    @DisplayName("τ — the carrier that orders")
    class Sorting {

        @Test
        @DisplayName("a sort key: the folded key delivers the same order")
        void sortKey() {
            assertEquivalentInOrder(
                    ORDERS
                  + "query { τ amount * 1 DESC (Orders) };\n",
                    OptimizationCode.EXPR_004);
        }
    }

    @Nested
    @DisplayName("LATERAL — the carrier no structural traversal reaches")
    class Lateral {

        @Test
        @DisplayName("a folded argument calls the TVF with the same value")
        void lateralArgument() {
            // A lateral argument may not read a left column, so the fold available here
            // is the constant one (EXPR-001) rather than EXPR-004's identity strip.
            assertEquivalent(
                    LATERAL_FIXTURES
                  + "query { Customers LATERAL ordersFor(10 + 10) };\n",
                    OptimizationCode.EXPR_001);
        }

        @Test
        @DisplayName("a bare TVF call's arguments are folded too — children() sees a leaf")
        void tvfCallArgument() {
            assertEquivalent(
                    LATERAL_FIXTURES + "query { ordersFor(10 + 10) };\n",
                    OptimizationCode.EXPR_001);
        }
    }

    // =========================================================================
    // Output-column naming
    // =========================================================================

    /**
     * An unaliased aggregate's output column name is <em>derived from its argument</em>
     * ({@code sum_expr} for a compound expression, {@code sum_amount} for a bare
     * column), so folding the argument renames the column.  This is the behaviour π has
     * had since expression simplification shipped — {@code π (amount * 1)} is named
     * {@code _col0} unoptimized and {@code amount} optimized — and γ now matches it
     * rather than diverging.  Pinned here because it is a real, if narrow, consequence
     * of #564 and should change only deliberately.
     */
    @Nested
    @DisplayName("a folded argument renames an unaliased output column, as π already did")
    class DerivedNaming {

        private List<String> namesOf(SemanticModel m, RelNode node) {
            SchemaAnnotations annotations =
                    SchemaInference.annotate(m.symbolTable(), node, m.nodeSchemas(), m.functions());
            return annotations.require(node, "— test fixture").columns().stream()
                    .map(ColumnDefinition::name).toList();
        }

        private void assertRenames(String query, String before, String after) {
            SemanticModel m = model(ORDERS + query);
            OptimizationResult r = optimizeFirst(m);
            assertThat(fired(r, OptimizationCode.EXPR_004)).isTrue();

            assertThat(namesOf(m, r.original())).contains(before);
            assertThat(namesOf(m, r.optimized())).contains(after);
        }

        @Test
        @DisplayName("γ: sum_expr → sum_amount once the argument folds to a bare column")
        void unaliasedAggregate() {
            assertRenames("query { γ cust, SUM(amount * 1) (Orders) };\n",
                    "sum_expr", "sum_amount");
        }

        @Test
        @DisplayName("π: _col0 → amount — the same rule, already shipped")
        void unaliasedProjection() {
            assertRenames("query { π amount * 1 (Orders) };\n", "_col0", "amount");
        }

        @Test
        @DisplayName("an alias pins the name, so folding cannot move it")
        void aliasIsStable() {
            SemanticModel m = model(ORDERS
                    + "query { γ cust, SUM(amount * 1) → total (Orders) };\n");
            OptimizationResult r = optimizeFirst(m);

            assertThat(fired(r, OptimizationCode.EXPR_004)).isTrue();
            assertThat(namesOf(m, r.optimized()))
                    .isEqualTo(namesOf(m, r.original()));
        }
    }
}

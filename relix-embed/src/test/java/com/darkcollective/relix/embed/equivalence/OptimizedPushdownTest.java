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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.optimizer.OptimizationResult;
import com.darkcollective.relix.cost.BoundednessSource;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.plan.Planner;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SchemaInference;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the payoff of the logical σ arms that is invisible at the optimizer level:
 * each one widens what the <em>pushdown renderers</em> can absorb. `SqlPushdownPlanner`
 * folds a σ into a `WHERE` only while no projection / sort / limit has been folded yet,
 * so a σ sitting <em>above</em> a τ or a γ cannot become a `WHERE` — it has to run in
 * the engine over everything the database sent. Move it below first and the whole
 * query goes to the database.
 *
 * <p>These assertions run the real pipeline — optimizer, then {@link Planner} with
 * pushdown enabled — and compare the emitted SQL with and without the rewrite, so a
 * regression in either half shows up here.
 */
@DisplayName("Optimized σ arms reach the SQL pushdown renderer")
final class OptimizedPushdownTest {

    private static final String ORDERS =
            "connection db from database { url: \"jdbc:h2:mem:x\" };\n"
          + "source Orders from db { table: \"orders\", "
          + "schema: { id: NUMBER, cust: STRING, amount: NUMBER } };\n";

    /** The logical tree of the model's first (expression) root query, un-optimized. */
    private static RelNode logicalOf(SemanticModel m) {
        return ((ExpressionQueryTarget) m.rootQueries().get(0).target()).expression();
    }

    /** Plans {@code node} with SQL pushdown enabled and returns its native query, if pushed. */
    private static Optional<String> pushedSql(RelNode node, SemanticModel m) {
        SchemaAnnotations schemas = SchemaInference.annotate(m.symbolTable(), node, m.nodeSchemas(), m.functions());
        // The model's own catalogue: an aggregate's SQL spelling comes from the
        // aggregate, so a planner without one pushes no GROUP BY (ADR-0026 S6/S7).
        PhysicalNode plan = new Planner(m.symbolTable(), schemas, Map.of(),
                m.sources(), m.connections(), QueryEventListener.NONE,
                BoundednessSource.ALL_BOUNDED, m.functions()).plan(node);
        return (plan instanceof PhysicalNode.PushedScan s)
                ? Optional.of(s.nativeQuery()) : Optional.empty();
    }

    /** The SQL the optimized form of {@code src} pushes down (empty when it stays in-engine). */
    private static Optional<String> optimizedSql(String src) {
        SemanticModel m = model(src);
        OptimizationResult r = OptimizerEquivalence.optimizeFirst(m);
        return pushedSql(r.optimized(), m);
    }

    /** The SQL the un-optimized form of {@code src} pushes down. */
    private static Optional<String> unoptimizedSql(String src) {
        SemanticModel m = model(src);
        return pushedSql(logicalOf(m), m);
    }

    @Nested
    @DisplayName("SEL-007 — a σ over γ becomes a WHERE instead of an in-engine filter")
    class Sel007 {

        private static final String QUERY =
                ORDERS + "query { σ cust = \"acme\" (γ cust, SUM(amount) → total (Orders)) };\n";

        @Test
        @DisplayName("un-optimized: the σ folds too, but as a HAVING — after every group is built")
        void withoutRewrite() {
            // The σ is not what blocks the fold any more (#807), so what SEL-007 buys is
            // no longer pushdown-or-nothing: it is the difference between filtering rows
            // before they are grouped and discarding groups after they are built. Both
            // return the same answer; only one of them reads one customer's rows.
            assertThat(unoptimizedSql(QUERY)).contains(
                    "SELECT cust, SUM(amount) FROM orders GROUP BY cust HAVING (cust = 'acme')");
        }

        @Test
        @DisplayName("optimized: the whole query is one WHERE … GROUP BY")
        void withRewrite() {
            assertThat(optimizedSql(QUERY)).contains(
                    "SELECT cust, SUM(amount) FROM orders WHERE (cust = 'acme') GROUP BY cust");
        }
    }

    @Nested
    @DisplayName("SEL-008 — a σ over τ becomes a WHERE alongside the ORDER BY")
    class Sel008 {

        private static final String QUERY =
                ORDERS + "query { σ amount > 100 (τ amount DESC (Orders)) };\n";

        @Test
        @DisplayName("un-optimized: ORDER BY is folded first, so the σ can no longer become a WHERE")
        void withoutRewrite() {
            assertThat(unoptimizedSql(QUERY)).isEmpty();
        }

        @Test
        @DisplayName("optimized: WHERE and ORDER BY are pushed together")
        void withRewrite() {
            assertThat(optimizedSql(QUERY)).contains(
                    "SELECT id, cust, amount FROM orders WHERE (amount > 100) ORDER BY (amount IS NULL) ASC, amount DESC");
        }
    }

    @Nested
    @DisplayName("LIM-003 — fusing λ∘τ into TOP keeps the ORDER BY … LIMIT pushdown")
    class Lim003 {

        private static final String QUERY =
                ORDERS + "query { λ 3 (τ amount DESC (Orders)) };\n";

        @Test
        @DisplayName("the fused TOP pushes down as the same SQL the λ/τ pair did")
        void topNStillPushes() {
            String expected =
                    "SELECT id, cust, amount FROM orders ORDER BY (amount IS NULL) ASC, amount DESC LIMIT 3";
            // The regression this guards: TopKNode had no arm in SqlPushdownPlanner, so
            // fusing λ∘τ would have *cost* the pushdown — a full table scan plus an
            // in-engine heap where the database could have done the whole thing.
            assertThat(unoptimizedSql(QUERY)).contains(expected);
            assertThat(optimizedSql(QUERY)).contains(expected);
        }

        @Test
        @DisplayName("a partitioned TOP … PER k is not pushed — SQL needs a window function")
        void partitionedTopDoesNotPush() {
            assertThat(unoptimizedSql(
                    ORDERS + "query { TOP 2 amount DESC PER cust (Orders) };\n")).isEmpty();
        }
    }

    @Nested
    @DisplayName("PROJ-004 — column pruning narrows the SELECT list over the wire")
    class Proj004 {

        @Test
        @DisplayName("a σ over one column ships one column, not the whole row")
        void selectionNarrowsSelectList() {
            String query = ORDERS + "query { π amount (σ amount > 100 (Orders)) };\n";
            assertThat(unoptimizedSql(query))
                    .contains("SELECT amount FROM orders WHERE (amount > 100)");
            assertThat(optimizedSql(query))
                    .contains("SELECT amount FROM orders WHERE (amount > 100)");
        }

        @Test
        @DisplayName("a same-connection join still folds — each side is a narrower scan")
        void joinStillFoldsOverNarrowedSides() {
            // The other half of the "a rewrite can cost a pushdown" hazard: the join fold
            // only takes *bare table scans*, so a pruning π above each leaf must not stop
            // the side from being one — it just makes the scan narrower.
            String query = ORDERS
                    + "source Customers from db { table: \"customers\", "
                    + "schema: { cust: STRING, city: STRING, tier: NUMBER } };\n"
                    + "query { π amount, city (Orders ⨝ Orders.cust = Customers.cust Customers) };\n";
            assertThat(optimizedSql(query)).contains(
                    "SELECT Orders.amount, Customers.city "
                    + "FROM orders Orders JOIN customers Customers "
                    + "ON (Orders.cust = Customers.cust)");
        }

        @Test
        @DisplayName("the pruning π does not cost the GROUP BY pushdown it sits under")
        void aggregationStillPushesWhole() {
            // The regression this guards: a pruning π counted as "the select list is
            // fixed" would make the γ above it un-foldable, trading a narrower SELECT
            // for no pushdown at all. See SqlPushdownPlanner.projection.
            assertThat(optimizedSql(
                    ORDERS + "query { γ cust, SUM(amount) → total (Orders) };\n"))
                    .contains("SELECT cust, SUM(amount) FROM orders GROUP BY cust");
        }
    }

    @Nested
    @DisplayName("δ — SELECT DISTINCT, and the rewrites that reach it (#540)")
    class Distinct {

        @Test
        @DisplayName("the database deduplicates instead of the engine buffering a hash set")
        void distinctPushesDown() {
            assertThat(optimizedSql(ORDERS + "query { δ (π cust (Orders)) };\n"))
                    .contains("SELECT DISTINCT cust FROM orders");
        }

        @Test
        @DisplayName("SEL-008 puts the σ below the δ, and both reach the same query")
        void selectionBelowDistinctStillOnePush() {
            // σ over δ: the σ arm pushes it below, and the renderer folds both — either
            // order gives one `SELECT DISTINCT … WHERE …`, since filtering commutes with
            // deduplication.
            assertThat(optimizedSql(ORDERS + "query { σ amount > 100 (δ (Orders)) };\n"))
                    .contains("SELECT DISTINCT id, cust, amount FROM orders WHERE (amount > 100)");
        }

        @Test
        @DisplayName("DIST-001 removes the δ over a γ, so the GROUP BY still pushes whole")
        void distinctOverAggregationStillPushes() {
            // The renderer refuses to emit DISTINCT alongside GROUP BY; the optimizer has
            // already dropped the redundant δ, so the query is pushed rather than split.
            assertThat(optimizedSql(
                    ORDERS + "query { δ (γ cust, SUM(amount) → total (Orders)) };\n"))
                    .contains("SELECT cust, SUM(amount) FROM orders GROUP BY cust");
        }
    }

    @Nested
    @DisplayName("SEL-010 / SET — a set operation the optimizer removes becomes one SELECT")
    class SetOperations {

        /*
         * Why this section is not covered by the agreement suites.
         *
         * PushdownAgreement plans "the resolved root tree with no optimizer pass", so
         * every corpus case measures the query AS WRITTEN. A shape that only exists
         * AFTER a rewrite is invisible to it — on H2 and in the container suites alike.
         * These rules produce exactly such shapes, so this file is the only place the
         * claim can be made at all.
         */

        private static final String UNION =
                ORDERS + "query { σ cust = \"acme\" (Orders) ∪ σ amount > 100 (Orders) };\n";

        @Test
        @DisplayName("un-optimized: a UNION is a boundary the renderer cannot cross")
        void withoutRewrite() {
            // The renderer folds σ but has no spelling for ∪, so each branch would be its
            // own scan with the de-duplication done in the engine. The root is not a
            // pushed scan, which is what "nothing folded" means here.
            assertThat(unoptimizedSql(UNION)).isEmpty();
        }

        @Test
        @DisplayName("optimized: two filtered reads become one WHERE with an OR")
        void unionOfTwoSelections() {
            assertThat(optimizedSql(UNION)).contains(
                    "SELECT DISTINCT id, cust, amount FROM orders "
                            + "WHERE ((cust = 'acme') OR (amount > 100))");
        }

        @Test
        @DisplayName("the ∩ arm is the same query with an AND")
        void intersectionOfTwoSelections() {
            assertThat(optimizedSql(
                    ORDERS + "query { σ cust = \"acme\" (Orders) ∩ σ amount > 100 (Orders) };\n"))
                    .contains("SELECT DISTINCT id, cust, amount FROM orders "
                            + "WHERE ((cust = 'acme') AND (amount > 100))");
        }

        @Test
        @DisplayName("SET-001 — R ∪ R is one SELECT DISTINCT, not two scans and a hash set")
        void idempotentUnion() {
            assertThat(optimizedSql(ORDERS + "query { Orders ∪ Orders };\n"))
                    .contains("SELECT DISTINCT id, cust, amount FROM orders");
        }

        @Test
        @DisplayName("the − arms stay in-engine: no dialect spells a condition's truth value")
        void complementDoesNotPush() {
            // `R − σk(R)` rewrites to σ (¬k ∨ k IS UNKNOWN) (R), whose second disjunct is
            // a null test over a ConditionOperand — and SqlExpressions.operand has no arm
            // for one, so it declines and the σ runs here. A slower plan, never a wrong
            // one, and the rows are covered by SetOperationEquivalenceTest.
            //
            // Asserted rather than left unsaid because the alternative is a reader
            // assuming the whole family folds: two of these arms do and two do not.
            assertThat(optimizedSql(ORDERS + "query { Orders − σ amount > 100 (Orders) };\n"))
                    .isEmpty();
            assertThat(optimizedSql(ORDERS
                    + "query { σ cust = \"acme\" (Orders) − σ amount > 100 (Orders) };\n"))
                    .isEmpty();
        }
    }
}

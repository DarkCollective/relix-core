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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The child-stream lifecycle invariant (issue #392): <b>every row stream an
 * operator opens is closed, including the ones a blocking operator drains and
 * abandons mid-tree.</b>
 *
 * <p>This matters because a connector's stream may hold a resource — the JDBC
 * connector's is lazy over a live {@code ResultSet} and keeps that
 * {@code ResultSet}, its {@code Statement}, and a pooled {@code Connection} open
 * until closed.  Closing the <em>root</em> stream (what every other execution test
 * does) does not reach a child that some blocking operator already consumed, so
 * that alone would never catch a leak.
 *
 * <p>These tests therefore make the leak visible: a connector hands out streams
 * carrying a close-counter, and each script asserts that once the root stream is
 * closed the counts balance.  The scripts are one per materialisation site the
 * sweep covered, so a new blocking operator that forgets
 * {@link ChildDispatch#materialize} fails here rather than silently leaking a
 * cursor in production.
 */
@DisplayName("Child-stream lifecycle — blocking operators close what they drain")
final class ChildStreamLifecycleTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    /**
     * The source declaration every script below reads from.  A {@code database}
     * source with an inline {@code url} is deliberately <em>not</em> pushdown-eligible
     * (pushdown needs a named {@code connection}), so the plan always contains a
     * {@code Scan} that calls the connector.
     */
    private static final String SOURCE = """
            source Edges from database { url: "jdbc:h2:mem", table: "edges",
                schema: { src: STRING, dst: STRING, grp: STRING,
                          amount: NUMBER, ts: TIMESTAMP } };
            """;

    /** A connector whose streams count their own opens and closes. */
    private static final class CountingConnector implements DataSourceConnector {

        private int opened;
        private int closed;

        @Override
        public Stream<Row> open(String relationName, Schema schema) {
            opened++;
            return rows(schema).stream().onClose(() -> closed++);
        }

        /**
         * Four edge rows shaped to satisfy every operator under test at once:
         * {@code src} is unique (TREE rejects duplicate keys), {@code dst} points at
         * another row's {@code src} to form a single-rooted forest / connected graph,
         * and one NULL {@code dst} marks the root.
         */
        private static List<Row> rows(Schema schema) {
            Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
            return List.of(
                    row(schema, str("n1"), str("n2"), str("x"), num(10),
                            new TimestampValue(t0)),
                    row(schema, str("n2"), str("n3"), str("x"), num(20),
                            new TimestampValue(t0.plusSeconds(3600))),
                    row(schema, str("n3"), NullValue.INSTANCE, str("y"), num(30),
                            new TimestampValue(t0.plusSeconds(7200))),
                    row(schema, str("n4"), str("n3"), str("y"), num(40),
                            new TimestampValue(t0.plusSeconds(10800))));
        }
    }

    /**
     * A connector whose streams fail part-way through, still counting their closes.
     *
     * <p>The lifecycle rule is about ownership, and ownership does not lapse because a
     * read went wrong — the resource a connector stream holds is a pooled JDBC connection,
     * and the case where the read failed is exactly the one where letting it leak is worst.
     */
    private static final class FailingConnector implements DataSourceConnector {

        private final int rowsBeforeFailing;
        private int opened;
        private int closed;
        /** Across every stream this connector hands out, so a later one can be the one to fail. */
        private int handedOut;

        FailingConnector(int rowsBeforeFailing) {
            this.rowsBeforeFailing = rowsBeforeFailing;
        }

        @Override
        public Stream<Row> open(String relationName, Schema schema) {
            opened++;
            return CountingConnector.rows(schema).stream()
                    .map(row -> {
                        if (handedOut++ >= rowsBeforeFailing) {
                            throw new EvaluationException(
                                    "the connector lost its cursor reading '" + relationName + "'");
                        }
                        return row;
                    })
                    .onClose(() -> closed++);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget expr -> expr.expression();
        };
    }

    /**
     * Runs {@code script} to completion, closing only the <b>root</b> stream — the
     * lifecycle every production caller provides — and returns the connector, whose
     * open/close counts are what the assertions are about.
     */
    private static CountingConnector run(String script) {
        SemanticModel model = model(SOURCE + script);
        CountingConnector connector = new CountingConnector();
        ExecutionContext ctx = ExecutionContext.of(model, connector);
        QueryStatement query = model.rootQueries().getFirst();

        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            stream.forEach(unused -> { });
        }
        return connector;
    }

    /** Runs {@code script} and returns its rows. */
    private static List<Row> rows(String script) {
        SemanticModel model = model(SOURCE + script);
        ExecutionContext ctx = ExecutionContext.of(model, new CountingConnector());
        try (Stream<Row> stream = EXECUTOR.execute(
                queryNode(model.rootQueries().getFirst()), ctx)) {
            return stream.toList();
        }
    }

    /**
     * Runs {@code script} to completion, closing only the <b>root</b> stream — the
     * lifecycle every production caller provides — and asserts that every child
     * stream the plan opened was closed too.
     */
    private static void assertNoLeak(String script) {
        CountingConnector connector = run(script);

        assertThat(connector.opened)
                .as("the plan must actually read the connector-backed source")
                .isPositive();
        assertThat(connector.closed)
                .as("every opened child stream must be closed (%d opened)", connector.opened)
                .isEqualTo(connector.opened);
    }

    // ── the sweep ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("blocking operators")
    class BlockingOperators {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '~', textBlock = """
                PIVOT       ~ query { PIVOT amount BY dst PER src (Edges) };
                sort        ~ query { τ amount DESC (Edges) };
                aggregate   ~ query { γ grp, SUM(amount) → total (Edges) };
                scalar γ    ~ query { γ SUM(amount) → total (Edges) };
                universal   ~ query { ∀ grp : amount > 0 (Edges) };
                TOP         ~ query { TOP 1 amount DESC PER grp (Edges) };
                WINDOW      ~ query { WINDOW RANK() SORT amount DESC PER grp AS rnk (Edges) };
                SESSIONIZE  ~ query { SESSIONIZE ts GAP DURATION 'PT30M' PER grp AS session (Edges) };
                DOWNSAMPLE  ~ query { DOWNSAMPLE ts BY '1h' USING AVG PER grp (Edges) };
                TREE        ~ query { TREE src BY dst ORDER amount AS children (Edges) };
                reservoir   ~ query { SAMPLE 2 ROWS SEED 7 (Edges) };
                OPTIMIZE    ~ query { OPTIMIZE MAXIMIZE SUM(amount) SUBJECT TO SUM(amount) <= 50 PER grp (Edges) };
                WHY         ~ query { WHY(σ amount > 0 (Edges)) };
                """)
        void closesItsInput(String operator, String script) {
            assertNoLeak(script);
        }
    }

    @Nested
    @DisplayName("recursion and graph operators")
    class Recursion {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '~', textBlock = """
                CLOSURE ~ query { CLOSURE src, dst (Edges) };
                CLUSTER ~ query { CLUSTER src, dst AS island (Edges) };
                PATH    ~ query { PATH src, dst HOPS 1 TO 2 AS depth (Edges) };
                TRACE   ~ query { TRACE src, dst VIA amount MINIMIZE AS route (Edges) };
                """)
        void closesItsInput(String operator, String script) {
            assertNoLeak(script);
        }
    }

    @Nested
    @DisplayName("binary operators — both sides")
    class BinaryOperators {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '~', textBlock = """
                product     ~ query { (π grp (Edges)) × (π dst (Edges)) };
                theta join  ~ query { Edges ⨝ src = rdst (ρ R2(rsrc, rdst, rgrp, ramount, rts) (Edges)) };
                natural     ~ query { Edges ⋈ Edges };
                full outer  ~ query { Edges |><| src = rdst (ρ R2(rsrc, rdst, rgrp, ramount, rts) (Edges)) };
                union       ~ query { (π grp (Edges)) ∪ (π grp (Edges)) };
                union all   ~ query { (π grp (Edges)) ⊎ (π grp (Edges)) };
                intersect   ~ query { (π grp (Edges)) ∩ (π grp (Edges)) };
                difference  ~ query { (π grp (Edges)) − (π grp (Edges)) };
                division    ~ query { (π src, grp (Edges)) ÷ (π grp (Edges)) };
                COVER       ~ query { COVER 2 ((π grp (Edges)) × (π dst (Edges))) };
                """)
        void closesBothInputs(String operator, String script) {
            assertNoLeak(script);
        }
    }

    // ── sharing: the same sub-plan read twice, evaluated once ─────────────────

    @Nested
    @DisplayName("∆ — each input is read once, not once per branch")
    class SymmetricDifferenceSharing {

        /**
         * {@code A ∆ B} desugars to {@code (A − B) ∪ (B − A)}, so each input appears at
         * two places in the plan.  A plan is a tree everywhere except at a spool, so
         * without one this script opens the source <b>four</b> times — twice for each
         * of the two selections.
         */
        private static final String SCRIPT =
                "query { (σ amount > 15 (Edges)) ∆ (σ amount > 25 (Edges)) };";

        @Test
        @DisplayName("opens the source once, where the un-shared plan opened it four times")
        void opensEachInputOnce() {
            CountingConnector connector = run(SCRIPT);

            // Two layers of sharing land on this script. The desugaring's own
            // duplication is removed by spooling each ∆ input, taking four opens to
            // two; and both of those inputs are selections over the same `Edges`, a
            // sub-expression the detection pass finds and spools in its own right,
            // taking two to one.
            assertThat(connector.opened)
                    .as("the source is read once, however many places read from it")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("still closes everything it opened")
        void closesWhatItOpened() {
            assertNoLeak(SCRIPT);
        }

        @Test
        @DisplayName("sharing does not change the rows")
        void rowsAreUnchanged() {
            // amount > 15 gives {20,30,40}, amount > 25 gives {30,40}; the symmetric
            // difference is the row they disagree on.
            List<Row> rows = rows(SCRIPT);

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("amount")).isEqualTo(num(20));
        }

        @Test
        @DisplayName("a ∆ whose two sides are the same expression is empty either way")
        void selfDifferenceIsEmpty() {
            assertThat(rows("query { (σ amount > 15 (Edges)) ∆ (σ amount > 15 (Edges)) };"))
                    .isEmpty();
        }

        @Test
        @DisplayName("an empty side leaves the other side's rows")
        void emptySideIsPreserved() {
            assertThat(rows("query { (σ amount > 25 (Edges)) ∆ (σ amount > 999 (Edges)) };"))
                    .hasSize(2);
        }
    }

    @Nested
    @DisplayName("a sub-expression two branches read is evaluated once")
    class RepeatedSubExpressionSharing {

        /**
         * Both branches read {@code Edges}. Without sharing the plan is a tree and the
         * source is opened twice; the detection pass finds the repeat and the source is
         * read once, however many branches read from it.
         */
        private static final String SCRIPT =
                "query { (σ amount > 15 (Edges)) ∪ (σ amount > 25 (Edges)) };";

        @Test
        @DisplayName("opens the source once, not once per branch")
        void opensTheSourceOnce() {
            assertThat(run(SCRIPT).opened).isEqualTo(1);
        }

        @Test
        @DisplayName("still closes what it opened")
        void closesWhatItOpened() {
            assertNoLeak(SCRIPT);
        }

        @Test
        @DisplayName("sharing does not change the rows")
        void rowsAreUnchanged() {
            // amount > 15 gives {20,30,40}; amount > 25 gives {30,40}; ∪ is set union.
            List<Row> rows = rows(SCRIPT);

            assertThat(rows).hasSize(3);
            assertThat(rows.stream().map(r -> r.get("amount")).toList())
                    .containsExactlyInAnyOrder(num(20), num(30), num(40));
        }

        @Test
        @DisplayName("a repeat read three ways is still one read")
        void threeReadersOneEvaluation() {
            assertThat(run("query { ((σ amount > 15 (Edges)) ∪ (σ amount > 25 (Edges))) "
                         + "∪ (σ amount > 35 (Edges)) };").opened)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a repeat inside a blocking operator is read once too")
        void blockingConsumerSharesAsWell() {
            // ÷ materialises both of its inputs, and both are derived from one source.
            assertThat(run("query { (π src, grp (Edges)) ÷ (π grp (σ amount > 15 (Edges))) };")
                    .opened)
                    .isEqualTo(1);
        }
    }

    // ── the regression the sweep was looking for ──────────────────────────────

    @Nested
    @DisplayName("PIVOT (the leak issue #392 named)")
    class PivotRegression {

        @Test
        @DisplayName("closes its input even though nothing above it consumes the stream lazily")
        void pivotClosesItsInput() {
            SemanticModel model = model(SOURCE + "query { PIVOT amount BY dst PER src (Edges) };");
            CountingConnector connector = new CountingConnector();
            ExecutionContext ctx = ExecutionContext.of(model, connector);

            // PIVOT is blocking: it drains its input during plan execution, before a
            // single output row is pulled. The close must already have happened here.
            try (Stream<Row> stream = EXECUTOR.execute(
                    queryNode(model.rootQueries().getFirst()), ctx)) {
                assertThat(stream.count()).isPositive();
                assertThat(connector.closed).isEqualTo(connector.opened);
            }
        }
    }

    // ── the exception path ────────────────────────────────────────────────────

    /**
     * The same invariant where the read <em>fails</em>. Every case above is a normal
     * completion, and that is the half of the rule the sweep had: an operator that closes
     * what it drained on the way out says nothing about one whose drain threw. The
     * resource is a pooled connection, so this is the direction where a leak costs most.
     */
    @Nested
    @DisplayName("a read that fails still closes what it opened")
    class FailurePath {

        private static FailingConnector failing(String script, int rowsBeforeFailing) {
            SemanticModel model = model(SOURCE + script);
            FailingConnector connector = new FailingConnector(rowsBeforeFailing);
            ExecutionContext ctx = ExecutionContext.of(model, connector);
            QueryStatement query = model.rootQueries().getFirst();

            assertThatThrownBy(() -> {
                try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
                    stream.forEach(unused -> { });
                }
            }).isInstanceOf(EvaluationException.class);

            assertThat(connector.opened)
                    .as("the plan must actually have read the source")
                    .isPositive();
            return connector;
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '~', textBlock = """
                blocking, mid-drain ~ query { τ amount DESC (Edges) };
                aggregate           ~ query { γ grp, SUM(amount) → total (Edges) };
                streaming           ~ query { π src, amount (σ amount > 0 (Edges)) };
                set operation       ~ query { (π grp (Edges)) ∩ (π dst (Edges)) };
                """)
        void aFailedReadClosesItsStream(String label, String script) {
            FailingConnector connector = failing(script, 2);

            assertThat(connector.closed)
                    .as("%s: %d opened, %d closed", label, connector.opened, connector.closed)
                    .isEqualTo(connector.opened);
        }

        /**
         * A join reads both its inputs, and a failure in one leaves the other mid-flight.
         *
         * <p>Worth recording what this actually opens, because it is not what the shape
         * suggests: <b>one</b> stream, not two. Both sides read the same relation, so the
         * scan beneath them is a shared sub-plan and the planner spools it — which is the
         * spool doing its job, and also the reason the leak this sweep found was the
         * spool's and not the join's.
         */
        @Test
        @DisplayName("a join whose read fails closes it, through the spool both sides share")
        void aJoinClosesItsSharedRead() {
            FailingConnector connector = failing(
                    "query { (π src, grp (Edges)) ⨝ grp = g (π grp → g, amount (Edges)) };", 2);

            assertThat(connector.opened)
                    .as("the scan both sides read is shared, so it is opened once")
                    .isEqualTo(1);
            assertThat(connector.closed).isEqualTo(connector.opened);
        }

        /**
         * The cap throws from <em>inside</em> the consumption of a child stream, which is
         * the engine taking this path deliberately rather than a fault being injected into
         * it. `MaterializationBudgetTest` asserts the throw; nothing asserted the close.
         */
        @Test
        @DisplayName("the materialization cap firing closes the stream it was metering")
        void theCapClosesWhatItWasMetering() {
            SemanticModel model = model(SOURCE + "query { τ amount DESC (Edges) };");
            CountingConnector connector = new CountingConnector();
            ExecutionContext ctx = ExecutionContext.of(model, connector)
                    .withMaxMaterializedRows(1);
            QueryStatement query = model.rootQueries().getFirst();

            assertThatThrownBy(() -> {
                try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
                    stream.forEach(unused -> { });
                }
            }).isInstanceOf(EvaluationException.class)
              .hasMessageContaining("maxMaterializedRows");

            assertThat(connector.opened).isPositive();
            assertThat(connector.closed)
                    .as("%d opened, %d closed", connector.opened, connector.closed)
                    .isEqualTo(connector.opened);
        }
    }
}

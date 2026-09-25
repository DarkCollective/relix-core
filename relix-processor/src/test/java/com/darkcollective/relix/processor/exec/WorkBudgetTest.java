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
import com.darkcollective.relix.processor.internal.DataSourceConnector;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The work budget: <b>a query that works for a long time while producing little is
 * stopped, deterministically by the rows it processed, or by a deadline.</b>
 *
 * <p>The row count is asserted exactly, at the limit and one below it, because a budget
 * that counted a boundary twice, or skipped one, would pass a looser test while
 * refusing or admitting the wrong queries.
 */
@DisplayName("Work budget — rows processed and a deadline stop a runaway execution")
final class WorkBudgetTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    /** Five rows; two have n > 3. */
    private static final String NUMBERS = """
            Numbers := [| n |
                        | 1 |
                        | 2 |
                        | 3 |
                        | 4 |
                        | 5 |];
            """;

    /** A source that never ends: the connector below yields 1, 2, 3, … for ever. */
    private static final String NATURALS = """
            source Naturals from database { url: "jdbc:h2:mem", table: "naturals",
                schema: { n: NUMBER } };
            """;

    private static final DataSourceConnector ENDLESS = (relationName, schema) ->
            Stream.iterate(1, i -> i + 1).map(i -> row(schema, num(i)));

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> com.darkcollective.relix.ast.AstBuilders.rel(named.name());
            case ExpressionQueryTarget expr -> expr.expression();
        };
    }

    /** Runs {@code script} under the tuned context and returns its rows. */
    private static List<Row> run(String script, UnaryOperator<ExecutionContext> tune) {
        SemanticModel model = model(script);
        ExecutionContext ctx = tune.apply(ExecutionContext.of(model, ENDLESS));
        try (Stream<Row> rows = EXECUTOR.execute(queryNode(model.rootQueries().getFirst()), ctx)) {
            return rows.toList();
        }
    }

    private static List<Row> runWithin(String script, long rows) {
        return run(script, ctx -> ctx.withMaxProcessedRows(rows));
    }

    @Nested
    @DisplayName("the row count")
    final class RowCount {

        /** The scan yields 5 rows and the selection above it yields 2: 7 rows processed. */
        private static final String FILTERED = NUMBERS + "query { σ n > 3 (Numbers) };";

        @Test
        @DisplayName("charges every operator's output, exactly")
        void exact() {
            assertThat(runWithin(FILTERED, 7)).hasSize(2);
            assertThatThrownBy(() -> runWithin(FILTERED, 6))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessage("query stopped: it processed more than 6 rows, "
                            + "the limit for one execution");
        }

        @Test
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("stops a selection over an endless input that matches nothing")
        void endlessSelection() {
            assertThatThrownBy(() -> runWithin(NATURALS + "query { σ n < 0 (Naturals) };", 1000))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("processed more than 1000 rows");
        }

        @Test
        @DisplayName("stops an aggregate over a product, although it emits one row")
        void productBehindAnAggregate() {
            String script = """
                    A := [| a |
                          | 1 |
                          | 2 |
                          | 3 |
                          | 4 |];
                    B := [| b |
                          | 1 |
                          | 2 |
                          | 3 |
                          | 4 |];
                    query { γ COUNT(*) → pairs (A × B) };
                    """;
            assertThat(runWithin(script, 1_000)).hasSize(1);
            assertThatThrownBy(() -> runWithin(script, 12))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("processed more than 12 rows");
        }

        @Test
        @DisplayName("charges every round of a recursion to the one budget")
        void recursion() {
            String script = """
                    Edges := [| src | dst |
                               | 1   | 2   |
                               | 2   | 3   |
                               | 3   | 4   |
                               | 4   | 5   |];
                    query { FIX R (Edges,
                      Edges ∪ π src, dst (ρ X(src, via) (R) ⋈ ρ Y(via, dst) (Edges))) };
                    """;
            assertThat(runWithin(script, 10_000)).hasSize(10);
            assertThatThrownBy(() -> runWithin(script, 20))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("processed more than 20 rows");
        }

        @Test
        @DisplayName("is unlimited by default")
        void unlimitedByDefault() {
            assertThat(run(NUMBERS + "query { σ n > 3 (Numbers) };", ctx -> ctx)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("the deadline")
    final class Deadline {

        @Test
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("stops a selection over an endless input that matches nothing")
        void endlessSelection() {
            assertThatThrownBy(() -> run(NATURALS + "query { σ n < 0 (Naturals) };",
                    ctx -> ctx.withTimeout(Duration.ofMillis(50))))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessage("query stopped: it ran longer than 50ms, the limit for one execution");
        }

        @Test
        @DisplayName("names a whole number of seconds as seconds, anything else in milliseconds")
        void describesTheTimeout() {
            assertThat(WorkBudget.describe(Duration.ofSeconds(2))).isEqualTo("2s");
            assertThat(WorkBudget.describe(Duration.ofMillis(1500))).isEqualTo("1500ms");
        }

        @Test
        @DisplayName("leaves a query that finishes in time alone")
        void inTime() {
            assertThat(run(NUMBERS + "query { σ n > 3 (Numbers) };",
                    ctx -> ctx.withTimeout(Duration.ofMinutes(5)))).hasSize(2);
        }
    }

    @Nested
    @DisplayName("cancellation")
    final class Interruption {

        @Test
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("reaches an operator filtering an endless input, with no limit set")
        void interruptReachesTheFilter() {
            assertCancelled(ctx -> ctx);
        }

        @Test
        @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("reaches it with a limit set too")
        void interruptReachesTheFilterUnderALimit() {
            assertCancelled(ctx -> ctx.withTimeout(Duration.ofHours(1)));
        }

        /**
         * Interrupts this thread from another one once the query is under way, as
         * {@code Future.cancel(true)} does, and expects the query to stop.
         */
        private void assertCancelled(UnaryOperator<ExecutionContext> tune) {
            Thread runner = Thread.currentThread();
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    return;
                }
                runner.interrupt();
            });
            canceller.start();
            try {
                assertThatThrownBy(() -> run(NATURALS + "query { σ n < 0 (Naturals) };", tune))
                        .isInstanceOf(EvaluationException.class)
                        .hasMessageContaining("query cancelled");
            } finally {
                canceller.interrupt();
                Thread.interrupted();
            }
        }
    }

    @Nested
    @DisplayName("with no limit")
    final class NoLimit {

        @Test
        @DisplayName("counts nothing, and is the one budget every unlimited execution shares")
        void identity() {
            Stream<Row> rows = Stream.of(row(schema("n"), num(1)), row(schema("n"), num(2)));
            assertThat(WorkBudget.UNLIMITED.meter(rows).toList()).hasSize(2);
            assertThat(WorkBudget.UNLIMITED.charged()).isZero();
            SemanticModel model = model(NUMBERS + "query Numbers;");
            assertThat(WorkBudget.startingNow(ExecutionContext.inlineOnly(model)))
                    .isSameAs(WorkBudget.UNLIMITED);
        }

        @Test
        @DisplayName("closing the metered stream closes the operator's")
        void closePropagates() {
            SemanticModel model = model(NUMBERS + "query Numbers;");
            WorkBudget budget = WorkBudget.startingNow(
                    ExecutionContext.inlineOnly(model).withMaxProcessedRows(10));
            boolean[] closed = {false};
            try (Stream<Row> metered = budget.meter(Stream.<Row>empty().onClose(() -> closed[0] = true))) {
                assertThatCode(metered::toList).doesNotThrowAnyException();
            }
            assertThat(closed[0]).isTrue();
            assertThat(budget.charged()).isZero();
        }
    }
}

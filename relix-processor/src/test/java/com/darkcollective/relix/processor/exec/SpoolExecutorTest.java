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

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NumberValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SpoolExecutor} — the executor half of the plan DAG, where one
 * sub-plan's rows are read by several consumers.
 *
 * <p>Driven directly rather than through a planned query, so that both the executions
 * of the shared sub-plan and the closes of its stream are countable, and so the row
 * budget can be a handful of rows instead of the production one.  The stand-in
 * {@link ChildDispatch} ignores everything on the {@link EvalCtx} except the spool
 * store, which is why the other members can be null.
 */
@DisplayName("SpoolExecutor — one evaluation, many consumers")
final class SpoolExecutorTest {

    private static final Schema SCHEMA = new Schema(
            List.of(new ColumnDefinition("n", ScalarType.NUMBER)));

    private static Row row(int n) {
        return ArrayRow.of(SCHEMA, NumberValue.of(String.valueOf(n)));
    }

    private static PhysicalNode.Spool spool(int id) {
        return new PhysicalNode.Spool(SCHEMA, id, new PhysicalNode.Empty(SCHEMA));
    }

    /**
     * A {@link ChildDispatch} producing {@code rows} rows, counting runs, closes, and
     * the rows actually <em>pulled</em> — the last being how a buffer that produces
     * more than its readers asked for is caught.
     */
    private static final class CountingDispatch implements ChildDispatch {
        private final int rows;
        int executed;
        int closed;
        int pulled;

        CountingDispatch(int rows) { this.rows = rows; }

        @Override
        public Stream<Row> execute(PhysicalNode node, EvalCtx ctx) {
            executed++;
            return IntStream.range(0, rows).mapToObj(SpoolExecutorTest::row)
                    .peek(unused -> pulled++)
                    .onClose(() -> closed++);
        }
    }

    /** Reads {@code n} rows from {@code stream} and closes it, as a λ above a spool would. */
    private static List<Row> take(Stream<Row> stream, int n) {
        try (stream) {
            return stream.limit(n).toList();
        }
    }

    private static EvalCtx ctx(SpoolCache spools) {
        return new EvalCtx(null, null, null, null, Map.of(), spools, 0,
                ExecutionContext.UNLIMITED_MATERIALIZED_ROWS, WorkBudget.UNLIMITED, null);
    }

    private static List<Row> drain(Stream<Row> stream) {
        try (stream) {
            return stream.toList();
        }
    }

    /** The {@code n} column of each row, as the plain integers the dispatch produced. */
    private static List<Integer> values(List<Row> rows) {
        List<Integer> out = new ArrayList<>();
        rows.forEach(r -> out.add(((NumberValue) r.get("n")).value().intValueExact()));
        return out;
    }

    @Nested
    @DisplayName("within budget")
    class WithinBudget {

        @Test
        @DisplayName("the second consumer replays the buffer instead of re-running the sub-plan")
        void secondConsumerReplays() {
            CountingDispatch dispatch = new CountingDispatch(3);
            EvalCtx ctx = ctx(new SpoolCache(100));
            SpoolExecutor executor = new SpoolExecutor(dispatch);
            PhysicalNode.Spool node = spool(1);

            List<Row> first = drain(executor.executeSpool(node, ctx));
            List<Row> second = drain(executor.executeSpool(node, ctx));

            assertThat(dispatch.executed).isEqualTo(1);
            assertThat(values(second)).isEqualTo(values(first)).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("both consumers see the same rows in the same order")
        void bothConsumersSeeTheSameRows() {
            CountingDispatch dispatch = new CountingDispatch(4);
            EvalCtx ctx = ctx(new SpoolCache(100));
            SpoolExecutor executor = new SpoolExecutor(dispatch);
            PhysicalNode.Spool node = spool(1);

            List<Row> first = drain(executor.executeSpool(node, ctx));
            List<Row> second = drain(executor.executeSpool(node, ctx));

            assertThat(values(first)).containsExactly(0, 1, 2, 3);
            assertThat(values(second)).containsExactly(0, 1, 2, 3);
        }

        @Test
        @DisplayName("the fill closes the child stream, and a replay opens nothing to close")
        void fillClosesTheChildOnce() {
            CountingDispatch dispatch = new CountingDispatch(3);
            EvalCtx ctx = ctx(new SpoolCache(100));
            SpoolExecutor executor = new SpoolExecutor(dispatch);

            drain(executor.executeSpool(spool(1), ctx));
            assertThat(dispatch.closed).isEqualTo(1);

            drain(executor.executeSpool(spool(1), ctx));
            assertThat(dispatch.closed)
                    .as("a replay holds no resource, so there is nothing more to close")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an empty sub-plan is buffered like any other")
        void emptySubPlanIsBuffered() {
            CountingDispatch dispatch = new CountingDispatch(0);
            EvalCtx ctx = ctx(new SpoolCache(100));
            SpoolExecutor executor = new SpoolExecutor(dispatch);

            assertThat(drain(executor.executeSpool(spool(1), ctx))).isEmpty();
            assertThat(drain(executor.executeSpool(spool(1), ctx))).isEmpty();
            assertThat(dispatch.executed).isEqualTo(1);
        }

        @Test
        @DisplayName("different ids are buffered independently")
        void distinctIdsAreIndependent() {
            CountingDispatch dispatch = new CountingDispatch(2);
            EvalCtx ctx = ctx(new SpoolCache(100));
            SpoolExecutor executor = new SpoolExecutor(dispatch);

            drain(executor.executeSpool(spool(1), ctx));
            drain(executor.executeSpool(spool(2), ctx));
            drain(executor.executeSpool(spool(1), ctx));
            drain(executor.executeSpool(spool(2), ctx));

            assertThat(dispatch.executed)
                    .as("one fill per id, then replays")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("demand — the buffer grows to what readers ask for, not to the relation")
    class Demand {

        @Test
        @DisplayName("a reader that stops early does not drain the sub-plan")
        void partialReaderPullsOnlyWhatItNeeds() {
            CountingDispatch dispatch = new CountingDispatch(1000);
            EvalCtx ctx = ctx(new SpoolCache(100));
            SpoolExecutor executor = new SpoolExecutor(dispatch);

            assertThat(take(executor.executeSpool(spool(1), ctx), 2)).hasSize(2);

            assertThat(dispatch.pulled)
                    .as("filling ahead of demand is work the un-shared plan would not do")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("two partial readers cost the deeper one's demand, not the sum")
        void twoPartialReadersShareWhatTheyPull() {
            CountingDispatch dispatch = new CountingDispatch(1000);
            EvalCtx ctx = ctx(new SpoolCache(100));
            SpoolExecutor executor = new SpoolExecutor(dispatch);

            take(executor.executeSpool(spool(1), ctx), 2);
            take(executor.executeSpool(spool(1), ctx), 3);

            assertThat(dispatch.pulled)
                    .as("the second reader replays the first's two rows and pulls one more")
                    .isEqualTo(3);
            assertThat(dispatch.executed).isEqualTo(1);
        }

        @Test
        @DisplayName("a reader after a partial one still sees the whole relation")
        void aLaterReaderCanStillGoFurther() {
            CountingDispatch dispatch = new CountingDispatch(5);
            EvalCtx ctx = ctx(new SpoolCache(100));
            SpoolExecutor executor = new SpoolExecutor(dispatch);

            take(executor.executeSpool(spool(1), ctx), 2);
            List<Row> all = drain(executor.executeSpool(spool(1), ctx));

            assertThat(values(all)).containsExactly(0, 1, 2, 3, 4);
            assertThat(dispatch.executed)
                    .as("extending the buffer continues the same read, it does not restart it")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a read nobody exhausted is released when the execution ends")
        void unfinishedReadIsReleasedAtTheEnd() {
            CountingDispatch dispatch = new CountingDispatch(1000);
            SpoolCache spools = new SpoolCache(100);
            SpoolExecutor executor = new SpoolExecutor(dispatch);

            take(executor.executeSpool(spool(1), ctx(spools)), 2);
            assertThat(dispatch.closed)
                    .as("a later reader may still want more, so the read stays open")
                    .isZero();

            spools.close();
            assertThat(dispatch.closed).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("over budget — degrade to re-execution, never to a wrong answer")
    class OverBudget {

        @Test
        @DisplayName("the first consumer still sees every row, from a single execution")
        void firstConsumerSeesEveryRowFromOneExecution() {
            CountingDispatch dispatch = new CountingDispatch(5);
            EvalCtx ctx = ctx(new SpoolCache(2));
            SpoolExecutor executor = new SpoolExecutor(dispatch);

            List<Row> rows = drain(executor.executeSpool(spool(1), ctx));

            assertThat(values(rows))
                    .as("buffered prefix then live remainder — one execution, in order")
                    .containsExactly(0, 1, 2, 3, 4);
            assertThat(dispatch.executed).isEqualTo(1);
        }

        @Test
        @DisplayName("closing the handed-back stream closes the child it is still reading")
        void overBudgetStreamClosesTheChild() {
            CountingDispatch dispatch = new CountingDispatch(5);
            EvalCtx ctx = ctx(new SpoolCache(2));
            SpoolExecutor executor = new SpoolExecutor(dispatch);

            drain(executor.executeSpool(spool(1), ctx));

            assertThat(dispatch.closed).isEqualTo(1);
        }

        @Test
        @DisplayName("a later consumer re-executes the sub-plan and still gets the right rows")
        void laterConsumerReExecutes() {
            CountingDispatch dispatch = new CountingDispatch(5);
            EvalCtx ctx = ctx(new SpoolCache(2));
            SpoolExecutor executor = new SpoolExecutor(dispatch);

            drain(executor.executeSpool(spool(1), ctx));
            List<Row> second = drain(executor.executeSpool(spool(1), ctx));

            assertThat(dispatch.executed)
                    .as("the spool was abandoned, so this is the plain un-shared behaviour")
                    .isEqualTo(2);
            assertThat(values(second)).containsExactly(0, 1, 2, 3, 4);
            assertThat(dispatch.closed).isEqualTo(2);
        }

        @Test
        @DisplayName("a budget already spent by an earlier spool declines the next one")
        void spentBudgetDeclinesTheNextSpool() {
            CountingDispatch dispatch = new CountingDispatch(3);
            EvalCtx ctx = ctx(new SpoolCache(3));
            SpoolExecutor executor = new SpoolExecutor(dispatch);

            drain(executor.executeSpool(spool(1), ctx));   // fills, spending the whole budget
            drain(executor.executeSpool(spool(2), ctx));
            drain(executor.executeSpool(spool(2), ctx));

            assertThat(dispatch.executed)
                    .as("id 1 filled once; id 2 had no budget left and ran per consumer")
                    .isEqualTo(3);
        }
    }
}

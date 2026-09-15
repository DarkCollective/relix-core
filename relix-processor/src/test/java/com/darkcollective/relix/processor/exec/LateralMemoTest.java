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

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.ArrayRow;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LateralMemo} — the per-execution argument-tuple cache that stops
 * a lateral join re-invoking the planner once per outer row (#539).
 *
 * <p>The memo is exercised directly rather than through a planned query so that both the
 * planner invocations and the body executions are countable, and so the two bounds can
 * be driven with small caps instead of the production ones.  The stand-in
 * {@link ChildDispatch} ignores the {@link EvalCtx} it is handed, which is why the tests
 * can pass {@code null} for it.
 */
@DisplayName("LateralMemo — argument-tuple memoization for LATERAL")
final class LateralMemoTest {

    private static final Schema SCHEMA = new Schema(
            List.of(new ColumnDefinition("n", ScalarType.NUMBER)));

    /** A body plan is only ever a cache key/value here, so any distinct node will do. */
    private static PhysicalNode body() {
        return new PhysicalNode.Empty(SCHEMA);
    }

    private static List<Operand> args(String value) {
        return List.of(num(value));
    }

    private static Row row(int n) {
        return ArrayRow.of(SCHEMA, NumberValue.of(String.valueOf(n)));
    }

    /**
     * A {@link ChildDispatch} that records every node it is asked to run and returns
     * {@code rowsPerBody} rows, tracking whether each stream was closed.
     */
    private static final class RecordingDispatch implements ChildDispatch {
        private final int rowsPerBody;
        final List<PhysicalNode> executed = new ArrayList<>();
        int closed;

        RecordingDispatch(int rowsPerBody) { this.rowsPerBody = rowsPerBody; }

        @Override
        public Stream<Row> execute(PhysicalNode node, EvalCtx ctx) {
            executed.add(node);
            return IntStream.range(0, rowsPerBody).mapToObj(LateralMemoTest::row)
                    .onClose(() -> closed++);
        }
    }

    /** A body builder that records the argument tuples it is asked to plan for. */
    private static final class CountingPlanner {
        final List<List<Operand>> planned = new ArrayList<>();

        PhysicalNode plan(List<Operand> args) {
            planned.add(args);
            return body();
        }
    }

    private static List<Row> drain(Stream<Row> stream) {
        try (stream) {
            return stream.toList();
        }
    }

    @Nested
    @DisplayName("reuse")
    class Reuse {

        @Test
        @DisplayName("a repeated argument tuple plans and executes the body exactly once")
        void repeatedTupleIsPlannedOnce() {
            var planner  = new CountingPlanner();
            var dispatch = new RecordingDispatch(2);
            var memo = new LateralMemo(planner::plan, true, dispatch, null, 8, 100);

            List<Row> first  = drain(memo.rowsFor(args("2")));
            List<Row> second = drain(memo.rowsFor(args("2")));
            List<Row> third  = drain(memo.rowsFor(args("2")));

            assertThat(planner.planned).as("one planner invocation for three outer rows")
                    .hasSize(1);
            assertThat(dispatch.executed).as("one body execution for three outer rows")
                    .hasSize(1);
            assertThat(first).hasSize(2);
            assertThat(second).isEqualTo(first);
            assertThat(third).isEqualTo(first);
        }

        @Test
        @DisplayName("distinct argument tuples each get their own plan and rows")
        void distinctTuplesAreSeparate() {
            var planner  = new CountingPlanner();
            var dispatch = new RecordingDispatch(1);
            var memo = new LateralMemo(planner::plan, true, dispatch, null, 8, 100);

            drain(memo.rowsFor(args("2")));
            drain(memo.rowsFor(args("3")));
            drain(memo.rowsFor(args("2")));
            drain(memo.rowsFor(args("3")));

            assertThat(planner.planned).hasSize(2);
            assertThat(dispatch.executed).hasSize(2);
        }

        @Test
        @DisplayName("a retained body's stream is closed once the rows are taken from it")
        void retainedBodyStreamIsClosed() {
            var planner  = new CountingPlanner();
            var dispatch = new RecordingDispatch(2);
            var memo = new LateralMemo(planner::plan, true, dispatch, null, 8, 100);

            drain(memo.rowsFor(args("2")));

            assertThat(dispatch.closed).isEqualTo(1);
        }

        @Test
        @DisplayName("a body that returns no rows is still memoized")
        void emptyBodyIsMemoized() {
            var planner  = new CountingPlanner();
            var dispatch = new RecordingDispatch(0);
            var memo = new LateralMemo(planner::plan, true, dispatch, null, 8, 100);

            assertThat(drain(memo.rowsFor(args("2")))).isEmpty();
            assertThat(drain(memo.rowsFor(args("2")))).isEmpty();

            assertThat(planner.planned).hasSize(1);
            assertThat(dispatch.executed).hasSize(1);
        }
    }

    @Nested
    @DisplayName("a non-deterministic body is never reused")
    class VolatileBody {

        @Test
        @DisplayName("rows are re-executed per outer row, even for an identical argument tuple")
        void rowsAreNotRetained() {
            var planner  = new CountingPlanner();
            var dispatch = new RecordingDispatch(2);
            var memo = new LateralMemo(planner::plan, false, dispatch, null, 8, 100);

            List<Row> first  = drain(memo.rowsFor(args("2")));
            List<Row> second = drain(memo.rowsFor(args("2")));

            assertThat(dispatch.executed)
                    .as("a volatile body must be evaluated once per outer row")
                    .hasSize(2);
            assertThat(first).hasSize(2);
            assertThat(second).hasSize(2);
        }

        @Test
        @DisplayName("the plan is still reused — planning evaluates nothing")
        void planIsStillReused() {
            var planner  = new CountingPlanner();
            var dispatch = new RecordingDispatch(1);
            var memo = new LateralMemo(planner::plan, false, dispatch, null, 8, 100);

            drain(memo.rowsFor(args("2")));
            drain(memo.rowsFor(args("2")));
            drain(memo.rowsFor(args("2")));

            assertThat(planner.planned)
                    .as("the expensive half is saved even when the rows cannot be")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("past the entry cap a repeated tuple is planned again rather than cached")
        void entryCapStopsGrowth() {
            var planner  = new CountingPlanner();
            var dispatch = new RecordingDispatch(1);
            var memo = new LateralMemo(planner::plan, true, dispatch, null, 2, 100);

            // Three distinct tuples, then the same three again.
            for (String value : List.of("1", "2", "3", "1", "2", "3")) {
                drain(memo.rowsFor(args(value)));
            }

            // The first two are retained and hit on the second pass; the third is over
            // the cap, so it is planned and executed once per occurrence.
            assertThat(planner.planned).hasSize(4);
            assertThat(dispatch.executed).hasSize(4);
        }

        @Test
        @DisplayName("past the row budget the body is re-executed rather than retained")
        void rowBudgetStopsRetention() {
            var planner  = new CountingPlanner();
            var dispatch = new RecordingDispatch(3);
            var memo = new LateralMemo(planner::plan, true, dispatch, null, 8, 4);

            drain(memo.rowsFor(args("1")));   // 3 rows retained, budget now 1
            drain(memo.rowsFor(args("2")));   // 3 rows do not fit in 1 — not retained
            drain(memo.rowsFor(args("2")));   // …so this is a miss on rows, hit on the plan

            assertThat(planner.planned).as("the plan is still reused past the row budget")
                    .hasSize(2);
            assertThat(dispatch.executed).as("but the body runs again for the unretained tuple")
                    .hasSize(3);
        }

        @Test
        @DisplayName("a body too large to retain still yields every one of its rows, once")
        void oversizedBodyStreamsThroughIntact() {
            var planner  = new CountingPlanner();
            var dispatch = new RecordingDispatch(5);
            var memo = new LateralMemo(planner::plan, true, dispatch, null, 8, 3);

            List<Row> rows = drain(memo.rowsFor(args("1")));

            assertThat(rows).as("the buffered prefix plus the live remainder, in order")
                    .containsExactly(row(0), row(1), row(2), row(3), row(4));
            assertThat(dispatch.executed).as("one execution, not two")
                    .hasSize(1);
            assertThat(dispatch.closed).as("closing the returned stream closes the source")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an oversized tuple is not buffered again on the next outer row")
        void oversizedTupleIsNotRebuffered() {
            var planner  = new CountingPlanner();
            var dispatch = new RecordingDispatch(5);
            var memo = new LateralMemo(planner::plan, true, dispatch, null, 8, 3);

            drain(memo.rowsFor(args("1")));
            List<Row> second = drain(memo.rowsFor(args("1")));

            assertThat(second).hasSize(5);
            assertThat(planner.planned).as("the plan is reused")
                    .hasSize(1);
            assertThat(dispatch.executed).hasSize(2);
        }

        @Test
        @DisplayName("the production caps are the documented ones")
        void productionCaps() {
            assertThat(LateralMemo.DEFAULT_MAX_ENTRIES).isEqualTo(256);
            assertThat(LateralMemo.DEFAULT_MAX_ROWS).isEqualTo(20_000);
        }
    }
}

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
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.internal.DataSourceConnector;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NumberValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Sharing a sub-expression must never do more work than not sharing it</b> — the
 * invariant issue #633 broke.
 *
 * <p>{@code ChildStreamLifecycleTest} counts how many times a source is <em>opened</em>,
 * which is the right instrument for the sharing win and blind to this: a spool that
 * drains a thousand rows to serve a reader that wanted two opens the source exactly
 * once, and looks perfect. So these tests count the rows actually <b>pulled</b> from
 * the source, and compare the same query planned with sharing against itself planned
 * without.
 */
@DisplayName("Spool demand — a shared sub-plan produces only what its readers ask for")
final class SpoolDemandTest extends ProcessorTestSupport {

    private static final String SOURCE = """
            source Big from database { url: "jdbc:h2:mem", table: "big",
                schema: { id: NUMBER, amount: NUMBER } };
            """;

    /** A source of 1000 rows that counts the ones actually pulled through it. */
    private static final class RowCountingConnector implements DataSourceConnector {
        int pulled;
        int opened;
        int closed;

        @Override
        public Stream<Row> open(String relationName, Schema schema) {
            opened++;
            return IntStream.range(0, 1000)
                    .mapToObj(i -> (Row) ArrayRow.of(schema,
                            NumberValue.of(String.valueOf(i)), NumberValue.of("7")))
                    .peek(unused -> pulled++)
                    .onClose(() -> closed++);
        }
    }

    private static RowCountingConnector run(String query) {
        SemanticModel model = model(SOURCE + query);
        RelNode logical =
                ((ExpressionQueryTarget) model.rootQueries().getFirst().target()).expression();
        RowCountingConnector connector = new RowCountingConnector();
        ExecutionContext ctx = ExecutionContext.of(model, connector);
        try (Stream<Row> stream = new RelNodeExecutor().execute(logical, ctx)) {
            stream.forEach(unused -> { });
        }
        return connector;
    }

    private static List<Row> rows(String query) {
        SemanticModel model = model(SOURCE + query);
        RelNode logical =
                ((ExpressionQueryTarget) model.rootQueries().getFirst().target()).expression();
        ExecutionContext ctx = ExecutionContext.of(model, new RowCountingConnector());
        try (Stream<Row> stream = new RelNodeExecutor().execute(logical, ctx)) {
            return stream.toList();
        }
    }

    @Nested
    @DisplayName("every reader partial (issue #633)")
    class PartialReaders {

        /**
         * Both branches read {@code σ amount > 0 (Big)}, so it is spooled — and both
         * stop after a handful of rows. Filling the spool eagerly drained the whole
         * source for two readers that wanted five rows between them.
         */
        private static final String SCRIPT =
                "query { (λ 2 (σ amount > 0 (Big))) ∩ (λ 3 (σ amount > 0 (Big))) };";

        @Test
        @DisplayName("pulls what the readers ask for, not the whole relation")
        void pullsOnlyWhatIsAsked() {
            RowCountingConnector connector = run(SCRIPT);

            // The deeper reader wants 3, the other 2, and they share those rows: 3
            // pulls serve both. Eagerly filling the spool pulled all 1000.
            assertThat(connector.pulled).isEqualTo(3);
        }

        @Test
        @DisplayName("beats not sharing, rather than merely matching it")
        void beatsNotSharing() {
            // Un-shared, the two branches pull 2 and 3 rows from their own reads of the
            // source — 5 in all. Sharing must not cost more than that, and here it costs
            // less, because the rows one branch pulled are the rows the other reads.
            assertThat(run(SCRIPT).pulled).isLessThanOrEqualTo(5);
        }

        @Test
        @DisplayName("the source is still opened once and closed once")
        void opensOnceAndCloses() {
            RowCountingConnector connector = run(SCRIPT);

            // Nobody exhausted this read and nobody took it over — both readers stopped
            // early — so closing the root is what releases it.
            assertThat(connector.opened).isEqualTo(1);
            assertThat(connector.closed).isEqualTo(1);
        }

        @Test
        @DisplayName("the rows are the ones the un-shared plan returns")
        void rowsAreUnchanged() {
            // λ 2 gives ids {0,1}; λ 3 gives {0,1,2}; the intersection is {0,1}.
            List<Row> rows = rows(SCRIPT);

            assertThat(rows).hasSize(2);
            assertThat(rows.stream().map(r -> r.get("id")).toList())
                    .containsExactlyInAnyOrder(num(0), num(1));
        }
    }

    @Nested
    @DisplayName("a fixpoint step — written once, evaluated once per round")
    class FixpointSteps {

        private static final String EDGES = """
                source Edges from database { url: "jdbc:h2:mem", table: "edges",
                    schema: { src: NUMBER, dst: NUMBER } };
                Seed := [| src | dst |
                         | 0   | 1   |];
                """;

        /**
         * Transitive reachability along a chain, so the fixpoint runs one round per
         * link. The edge side of the join is written <em>once</em> and reads nothing
         * the recursion binds, so it computes the same rows on every round.
         */
        private static final String CHAIN =
                "query { FIX Reach (Seed, "
                + "π src, dst2 → dst (Reach ⋈ (π src → dst, dst → dst2 (Edges)))) };";

        /** A chain 0→1→…→6, counting the rows actually pulled through it. */
        private static final class ChainConnector implements DataSourceConnector {
            int pulled;
            int opened;

            @Override
            public Stream<Row> open(String relationName, Schema schema) {
                opened++;
                return IntStream.range(0, 6)
                        .mapToObj(i -> (Row) ArrayRow.of(schema,
                                NumberValue.of(String.valueOf(i)),
                                NumberValue.of(String.valueOf(i + 1))))
                        .peek(unused -> pulled++);
            }
        }

        private static ChainConnector run(String query) {
            SemanticModel model = model(EDGES + query);
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().getFirst().target()).expression();
            ChainConnector connector = new ChainConnector();
            ExecutionContext ctx = ExecutionContext.of(model, connector);
            try (Stream<Row> stream = new RelNodeExecutor().execute(logical, ctx)) {
                stream.forEach(unused -> { });
            }
            return connector;
        }

        @Test
        @DisplayName("the invariant side is read once, not once per iteration")
        void theInvariantSideIsReadOnce() {
            ChainConnector connector = run(CHAIN);

            // Six links, so the step runs six times. Re-executing the edge side on each
            // of them pulls 36 rows through six opens — which is what this cost before
            // a sub-expression inside a step counted as a site per round.
            assertThat(connector.opened).isEqualTo(1);
            assertThat(connector.pulled).isEqualTo(6);
        }

        @Test
        @DisplayName("the invariant build side is prepared once, not once per round")
        void theBuildSideIsPreparedOnce() {
            // What a spool alone could not buy. The scan happened once from the round
            // the spool landed, but the join still buffered and re-hashed those rows on
            // every iteration, because the join reads the recursive ref and is
            // re-executed by construction. Six links, so six rounds.
            List<com.darkcollective.relix.events.QueryEvent> events = new java.util.ArrayList<>();
            SemanticModel model = model(EDGES + CHAIN);
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().getFirst().target()).expression();
            ExecutionContext ctx = ExecutionContext.of(model, new ChainConnector())
                    .withListener(events::add);
            try (Stream<Row> stream = new RelNodeExecutor().execute(logical, ctx)) {
                stream.forEach(unused -> { });
            }

            assertThat(events)
                    .filteredOn(e -> e.code().equals("MATERIALIZE")
                            && e.metrics().rows().orElse(-1) == 6)
                    .as("the six edges are buffered and hashed once for the whole "
                        + "recursion; this was one per round")
                    .hasSize(1);
            assertThat(events)
                    .filteredOn(e -> e.code().equals("MATERIALIZE")
                            && e.metrics().rows().orElse(-1) == 1)
                    .as("the recursive side is a different relation on every round and "
                        + "must still be buffered on every round")
                    .hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("the rows are the ones the un-shared plan returns")
        void rowsAreUnchanged() {
            SemanticModel model = model(EDGES + CHAIN);
            RelNode logical =
                    ((ExpressionQueryTarget) model.rootQueries().getFirst().target()).expression();
            ExecutionContext ctx = ExecutionContext.of(model, new ChainConnector());
            List<Row> rows;
            try (Stream<Row> stream = new RelNodeExecutor().execute(logical, ctx)) {
                rows = stream.toList();
            }

            // Everything 0 reaches along the chain: 1 (the seed) through 6.
            assertThat(rows.stream().map(r -> r.get("dst")).toList())
                    .containsExactlyInAnyOrder(num(1), num(2), num(3), num(4), num(5), num(6));
        }
    }

    @Nested
    @DisplayName("a draining reader — the sharing win is unchanged")
    class DrainingReaders {

        @Test
        @DisplayName("one read of the source serves both branches in full")
        void oneReadServesBoth() {
            RowCountingConnector connector =
                    run("query { (σ amount > 0 (Big)) ∪ (σ id > 500 (Big)) };");

            assertThat(connector.opened).isEqualTo(1);
            assertThat(connector.pulled)
                    .as("both branches need every row, so the whole relation is read — once")
                    .isEqualTo(1000);
        }

        @Test
        @DisplayName("a partial reader beside a draining one costs the draining one nothing")
        void mixedReadersStillDrainOnce() {
            RowCountingConnector connector =
                    run("query { (λ 2 (σ amount > 0 (Big))) ∪ (σ amount > 0 (Big)) };");

            assertThat(connector.opened).isEqualTo(1);
            assertThat(connector.pulled).isEqualTo(1000);
        }
    }
}

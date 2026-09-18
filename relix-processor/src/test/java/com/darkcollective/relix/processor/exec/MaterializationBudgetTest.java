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
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.TimestampValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The blocking-operator row budget (issue #787): <b>an operator that holds its whole input
 * in memory says how much it held, and stops rather than exhausting the heap.</b>
 *
 * <p>The guard next door — {@code BoundednessChecker}, and {@code toList()}'s refusal —
 * is about boundedness rather than size, so a bounded relation far larger than the heap
 * plans happily and dies with an {@code OutOfMemoryError} attributable to no operator in
 * particular.  These tests are the sweep that makes each blocking operator answerable:
 * under a cap of one row it must refuse and name itself, and with a listener attached it
 * must report what it buffered as a number.
 *
 * <p>The script list is deliberately the same corpus
 * {@link ChildStreamLifecycleTest} sweeps, and for the same reason: a new blocking
 * operator that reaches its input through {@code dispatch.execute} rather than through
 * {@link ChildDispatch#materialize}, {@link ChildDispatch#buffering} or
 * {@link ChildDispatch#holding} fails here instead of silently opting out of the budget.
 */
@DisplayName("Materialization budget — a blocking operator is capped and reports what it held")
final class MaterializationBudgetTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    /** A non-pushdown-eligible source, so the plan always contains an in-engine buffer. */
    private static final String SOURCE = """
            source Edges from database { url: "jdbc:h2:mem", table: "edges",
                schema: { src: STRING, dst: STRING, grp: STRING,
                          amount: NUMBER, ts: TIMESTAMP } };
            """;

    /** Four rows, shaped as {@link ChildStreamLifecycleTest}'s are, so every operator runs. */
    private static final DataSourceConnector CONNECTOR = (relationName, schema) ->
            edges(schema).stream();

    private static List<Row> edges(Schema schema) {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        return List.of(
                row(schema, str("n1"), str("n2"), str("x"), num(10), new TimestampValue(t0)),
                row(schema, str("n2"), str("n3"), str("x"), num(20),
                        new TimestampValue(t0.plusSeconds(3600))),
                row(schema, str("n3"), NullValue.INSTANCE, str("y"), num(30),
                        new TimestampValue(t0.plusSeconds(7200))),
                row(schema, str("n4"), str("n3"), str("y"), num(40),
                        new TimestampValue(t0.plusSeconds(10800))));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named      -> com.darkcollective.relix.ast.AstBuilders.rel(named.name());
            case ExpressionQueryTarget expr  -> expr.expression();
        };
    }

    /** Runs {@code script} under {@code ctx}, draining and closing the root stream. */
    private static void drain(String script, java.util.function.UnaryOperator<ExecutionContext> tune) {
        SemanticModel model = model(SOURCE + script);
        ExecutionContext ctx = tune.apply(ExecutionContext.of(model, CONNECTOR));
        try (Stream<Row> stream = EXECUTOR.execute(
                queryNode(model.rootQueries().getFirst()), ctx)) {
            stream.forEach(unused -> { });
        }
    }

    /** Runs {@code script} with a cap of {@code rows} on any one blocking operator. */
    private static void runCapped(String script, int rows) {
        drain(script, ctx -> ctx.withMaxMaterializedRows(rows));
    }

    /** Runs {@code script} and returns the {@code MATERIALIZE} events it emitted. */
    private static List<QueryEvent> materializations(String script) {
        List<QueryEvent> observed = new ArrayList<>();
        drain(script, ctx -> ctx.withListener(observed::add));
        return observed.stream().filter(e -> e.code().equals("MATERIALIZE")).toList();
    }

    /**
     * Every blocking operator whose buffer <em>is</em> its input, one script each.  The
     * second column is the {@code PhysicalNode} the error and the event must name — the
     * plan's own {@code op} spelling, so a message matches the plan a user reads beside
     * it.
     *
     * <p>{@code TOP} is not among them and is swept by {@link BoundedByItsOwnShape}
     * instead: it reads its whole input like any of these and holds a fixed number of
     * rows per group rather than the input, so the number it must answer with is a
     * different one.
     */
    private static final String BLOCKING = """
            PIVOT       ~ Pivot       ~ query { PIVOT amount BY dst PER src (Edges) };
            sort        ~ Sort        ~ query { τ amount DESC (Edges) };
            aggregate   ~ Aggregate   ~ query { γ grp, SUM(amount) → total (Edges) };
            scalar γ    ~ Aggregate   ~ query { γ SUM(amount) → total (Edges) };
            universal   ~ Universal   ~ query { ∀ grp : amount > 0 (Edges) };
            WINDOW      ~ Window      ~ query { WINDOW RANK() SORT amount DESC PER grp AS rnk (Edges) };
            SESSIONIZE  ~ Sessionize  ~ query { SESSIONIZE ts GAP DURATION 'PT30M' PER grp AS session (Edges) };
            DOWNSAMPLE  ~ Downsample  ~ query { DOWNSAMPLE ts BY '1h' USING AVG PER grp (Edges) };
            TREE        ~ Tree        ~ query { TREE src BY dst ORDER amount AS children (Edges) };
            OPTIMIZE    ~ Optimize    ~ query { OPTIMIZE MAXIMIZE SUM(amount) SUBJECT TO SUM(amount) <= 50 PER grp (Edges) };
            CLOSURE     ~ Closure     ~ query { CLOSURE src, dst (Edges) };
            CLUSTER     ~ Cluster     ~ query { CLUSTER src, dst AS island (Edges) };
            PATH        ~ Path        ~ query { PATH src, dst HOPS 1 TO 2 AS depth (Edges) };
            TRACE       ~ Trace       ~ query { TRACE src, dst VIA amount MINIMIZE AS route (Edges) };
            hash δ      ~ Distinct    ~ query { δ (π grp (Edges)) };
            product     ~ Join        ~ query { (π grp (Edges)) × (π dst (Edges)) };
            theta join  ~ Join        ~ query { Edges ⨝ src = rdst (ρ R2(rsrc, rdst, rgrp, ramount, rts) (Edges)) };
            full outer  ~ Join        ~ query { Edges |><| src = rdst (ρ R2(rsrc, rdst, rgrp, ramount, rts) (Edges)) };
            union       ~ SetOp       ~ query { (π grp (Edges)) ∪ (π dst (Edges)) };
            union all   ~ SetOp       ~ query { (π grp (Edges)) ⊎ (π dst (Edges)) };
            intersect   ~ SetOp       ~ query { (π grp (Edges)) ∩ (π dst (Edges)) };
            difference  ~ SetOp       ~ query { (π grp (Edges)) − (π dst (Edges)) };
            division    ~ Division    ~ query { (π src, grp (Edges)) ÷ (π grp (Edges)) };
            COVER       ~ ConstructiveCover ~ query { COVER 2 ((π grp (Edges)) × (π dst (Edges))) };
            """;

    @Nested
    @DisplayName("the cap")
    class TheCap {

        @ParameterizedTest(name = "{0} refuses, naming itself")
        @CsvSource(delimiter = '~', textBlock = BLOCKING)
        void refusesAndNamesTheOperator(String label, String operator, String script) {
            assertThatThrownBy(() -> runCapped(script, 1))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining(operator.strip())
                    .hasMessageContaining("buffered more than 1 row");
        }

        @ParameterizedTest(name = "{0} runs when the cap is not reached")
        @CsvSource(delimiter = '~', textBlock = BLOCKING)
        void allowsWhatFits(String label, String operator, String script) {
            assertThatCode(() -> runCapped(script, 1_000)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a streaming operator is not capped — σ, π and λ buffer nothing")
        void streamingOperatorsAreUncapped() {
            assertThatCode(() -> runCapped("query { λ 3 (π grp (σ amount > 0 (Edges))) };", 1))
                    .doesNotThrowAnyException();
        }

        /**
         * A reservoir is metered on what it <em>holds</em>, which is its count, and not on
         * the input it reads to fill it — the shape {@code TOP} uses, and for the same
         * reason. Metering the input would be wrong in both directions: it would refuse a
         * two-row sample of a large table and pass a large sample of a small one.
         *
         * <p>It was unmetered, on the ground that a count is "bounded by construction".
         * The count comes from the query, so that was only ever a bound the query chose.
         */
        @Test
        @DisplayName("a reservoir is charged its count, not the input it read")
        void reservoirIsChargedWhatItHolds() {
            assertThatThrownBy(() -> runCapped("query { SAMPLE 2 ROWS SEED 7 (Edges) };", 1))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("ReservoirSample");

            assertThatCode(() -> runCapped("query { SAMPLE 2 ROWS SEED 7 (Edges) };", 2))
                    .as("holding exactly the cap is within it")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a one-row sample of a four-row relation is charged one row")
        void reservoirIsNotChargedForTheRowsItPassedOver() {
            assertThatCode(() -> runCapped("query { SAMPLE 1 ROWS SEED 7 (Edges) };", 1))
                    .as("four rows read, one held — metering the input would refuse this")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the message says what to do about it")
        void messageSuggestsARemedy() {
            assertThatThrownBy(() -> runCapped("query { τ amount DESC (Edges) };", 1))
                    .hasMessageContaining("maxMaterializedRows");
        }

        @Test
        @DisplayName("unlimited is the default, so an uncapped context runs a four-row buffer")
        void defaultIsUnlimited() {
            assertThat(ExecutionContext.UNLIMITED_MATERIALIZED_ROWS).isEqualTo(Integer.MAX_VALUE);
            assertThatCode(() -> drain("query { τ amount DESC (Edges) };", ctx -> ctx))
                    .doesNotThrowAnyException();
        }

        /**
         * The fast path is taken only when there is nothing to enforce <em>and</em> nobody
         * to tell, so the condition has four corners and the tests above happen to walk
         * three of them. The fourth — capped <em>and</em> listening — is the combination a
         * user actually runs when they pass a cap and a trace together, and it was reached
         * by no test at all.
         */
        @Test
        @DisplayName("a cap and a listener compose: both are honoured at once")
        void capAndListenerCompose() {
            List<QueryEvent> observed = new ArrayList<>();
            assertThatCode(() -> drain("query { τ amount DESC (Edges) };",
                    ctx -> ctx.withMaxMaterializedRows(4).withListener(observed::add)))
                    .doesNotThrowAnyException();
            assertThat(observed).filteredOn(e -> e.code().equals("MATERIALIZE")).isNotEmpty();

            assertThatThrownBy(() -> drain("query { τ amount DESC (Edges) };",
                    ctx -> ctx.withMaxMaterializedRows(2).withListener(observed::add)))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Sort");
        }

        @Test
        @DisplayName("the cap is per operator, not per query: two operators each under it pass")
        void capIsPerOperator() {
            // Both the δ and the τ buffer four rows; a running total would see eight.
            assertThatCode(() -> runCapped("query { τ grp (δ (π grp, amount (Edges))) };", 4))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the event")
    class TheEvent {

        @ParameterizedTest(name = "{0} reports what it buffered")
        @CsvSource(delimiter = '~', textBlock = BLOCKING)
        void reportsWhatItBuffered(String label, String operator, String script) {
            assertThat(materializations(script))
                    .as("%s must report the rows it buffered", label)
                    .anySatisfy(e -> {
                        assertThat(e.target()).contains(operator.strip());
                        assertThat(e.stage()).isEqualTo(QueryEvent.Stage.EXECUTE);
                        assertThat(e.metrics().rows()).isPresent();
                    });
        }

        @ParameterizedTest(name = "{0} reports how long it spent filling the buffer")
        @CsvSource(delimiter = '~', textBlock = BLOCKING)
        void reportsHowLongItSpentBuffering(String label, String operator, String script) {
            // #788: presence and sign only. The number is wall clock — for a person
            // asking which operator the time went into, never for an assertion.
            assertThat(materializations(script))
                    .as("%s must report the time it spent filling its buffer", label)
                    .anySatisfy(e -> {
                        assertThat(e.target()).contains(operator.strip());
                        assertThat(e.metrics().duration()).isPresent();
                        assertThat(e.metrics().duration().orElseThrow().isNegative()).isFalse();
                    });
        }

        @Test
        @DisplayName("the count is the rows read into the buffer")
        void countsTheRowsBuffered() {
            assertThat(materializations("query { τ amount DESC (Edges) };"))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.metrics().rows()).hasValue(4);
                        assertThat(e.description()).isEqualTo("buffered 4 rows for Sort");
                        assertThat(e.target()).contains("Sort");
                    });
        }

        @Test
        @DisplayName("a one-row buffer is described in the singular")
        void singularForOneRow() {
            assertThat(materializations("query { τ amount DESC (σ amount = 10 (Edges)) };"))
                    .singleElement()
                    .satisfies(e -> assertThat(e.description())
                            .isEqualTo("buffered 1 row for Sort"));
        }

        @Test
        @DisplayName("a join reports each side it buffered")
        void aJoinReportsEachSide() {
            assertThat(materializations(
                    "query { Edges |><| src = rdst "
                    + "(ρ R2(rsrc, rdst, rgrp, ramount, rts) (Edges)) };"))
                    .hasSize(2)
                    .allSatisfy(e -> assertThat(e.target()).contains("Join"));
        }

        @Test
        @DisplayName("a streaming operator reports nothing — it buffered nothing")
        void streamingOperatorsReportNothing() {
            assertThat(materializations("query { λ 3 (π grp (σ amount > 0 (Edges))) };"))
                    .isEmpty();
        }

        @Test
        @DisplayName("an operator stopped by the cap reports no count — it never finished a buffer")
        void abandonedBufferIsNotReported() {
            List<QueryEvent> observed = new ArrayList<>();
            assertThatThrownBy(() -> drain("query { τ amount DESC (Edges) };",
                    ctx -> ctx.withListener(observed::add).withMaxMaterializedRows(1)))
                    .isInstanceOf(EvaluationException.class);

            assertThat(observed.stream().filter(e -> e.code().equals("MATERIALIZE")).toList())
                    .as("a partial buffer measures the cap, not the relation")
                    .isEmpty();
        }
    }

    /**
     * {@code TOP} reads every row and keeps {@code offset + count} of them per group, so
     * the buffer the cap has to be about is the one it <em>holds</em>.  Metering its
     * input answered about the wrong buffer in both directions: it refused a {@code TOP 1}
     * over a large table that would have held one row, and passed a {@code TOP 1} over a
     * small one holding a row for every distinct group.
     */
    @Nested
    @DisplayName("a buffer bounded by the operator rather than by its input")
    class BoundedByItsOwnShape {

        /** Four rows in two groups, so a {@code TOP 1 PER grp} holds two rows. */
        private static final String TOP_PER_GROUP =
                "query { TOP 1 amount DESC PER grp (Edges) };";

        @Test
        @DisplayName("TOP holds one row per group, so four rows in two groups fit a cap of two")
        void holdsOnePerGroup() {
            assertThatCode(() -> runCapped(TOP_PER_GROUP, 2)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("past the cap it refuses, naming itself and what it held")
        void refusesOnWhatItHolds() {
            assertThatThrownBy(() -> runCapped(TOP_PER_GROUP, 1))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("TopK")
                    .hasMessageContaining("held more than 1 row")
                    .hasMessageContaining("maxMaterializedRows");
        }

        @Test
        @DisplayName("the remedy it names is the group count, not the rows reaching it")
        void theRemedyIsTheGroupCount() {
            // A σ below a τ shrinks its buffer; below a TOP it need not, so the advice
            // refuse() gives would be wrong here.
            assertThatThrownBy(() -> runCapped(TOP_PER_GROUP, 1))
                    .hasMessageContaining("grows with the number of groups");
        }

        @Test
        @DisplayName("the event reports the rows held, not the rows read")
        void reportsWhatItHeld() {
            assertThat(materializations(TOP_PER_GROUP))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.target()).contains("TopK");
                        assertThat(e.stage()).isEqualTo(QueryEvent.Stage.EXECUTE);
                        assertThat(e.metrics().rows()).hasValue(2);   // four rows read
                        assertThat(e.description()).isEqualTo("buffered 2 rows for TopK");
                        assertThat(e.metrics().duration()).isPresent();
                    });
        }

        @Test
        @DisplayName("an abandoned buffer reports nothing, as for any other operator")
        void abandonedBufferIsNotReported() {
            List<QueryEvent> observed = new ArrayList<>();
            assertThatThrownBy(() -> drain(TOP_PER_GROUP,
                    ctx -> ctx.withListener(observed::add).withMaxMaterializedRows(1)))
                    .isInstanceOf(EvaluationException.class);

            assertThat(observed.stream().filter(e -> e.code().equals("MATERIALIZE")).toList())
                    .isEmpty();
        }
    }
}

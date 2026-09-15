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
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NumberValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a {@code LATERAL} join <em>costs</em>, measured as work rather than as time.
 *
 * <p>A lateral invokes its function once per outer row, and #539 added a memo so that two
 * outer rows carrying the same argument tuple share one planning-and-execution. That is a
 * complexity claim — the body runs once per <em>distinct</em> tuple, not once per row — and
 * it is the kind that regresses silently: the rows stay correct either way, so every
 * correctness test in the suite passes while a 500-row outer relation quietly does a hundred
 * times the work it should.
 *
 * <h2>Why work and not wall-clock</h2>
 *
 * <p>#633 is the precedent. A spool's eager fill did strictly more work than not sharing at
 * all, and {@code ChildStreamLifecycleTest} — which counts stream <em>opens</em> — stayed at
 * a perfect 1 throughout, because the source was still opened once. Only counting the rows
 * actually pulled showed it. A wall-clock benchmark would have caught it too, on a good day,
 * and would have flapped on every other one; a count is exact and reproducible on any
 * machine.
 *
 * <h2>Why this is a tier and not a gate</h2>
 *
 * <p>The property is only visible at a scale that makes the difference obvious, and a query
 * that plans a function body five hundred times is not something to put in front of every
 * commit. Run it deliberately:
 *
 * <pre>
 * ./gradlew :relix-processor:benchmarkTest
 * ./gradlew verifyAll -PtestTiers=benchmark
 * </pre>
 */
@Tag("benchmark")
@DisplayName("LATERAL scales with distinct argument tuples, not with outer rows")
final class LateralScalingBenchmarkTest extends ProcessorTestSupport {

    /** Outer rows. Large enough that per-row work is unmistakable in the count. */
    private static final int OUTER_ROWS = 500;

    /** How many different arguments those rows carry between them. */
    private static final int DISTINCT_ARGUMENTS = 5;

    /** Rows in the relation the function body scans — small, so the memo retains them. */
    private static final int BODY_ROWS = 20;

    private static final String SOURCES = """
            source Customers from database { url: "jdbc:h2:mem", table: "customers",
                schema: { customer_id: NUMBER } };
            source Orders from database { url: "jdbc:h2:mem", table: "orders",
                schema: { order_id: NUMBER, customer_id: NUMBER } };
            def ordersFor(cid: NUMBER): RELATION := { σ customer_id = cid (Orders) };
            """;

    /** Serves both relations and records how often each was opened. */
    private static final class CountingConnector implements DataSourceConnector {
        final Map<String, Integer> opens = new HashMap<>();

        @Override
        public Stream<Row> open(String relationName, Schema schema) {
            // Keyed lower-case: the executor passes the name as the source declared it,
            // and a case-sensitive counter reads as "never opened" rather than as a
            // mismatch.
            opens.merge(relationName.toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
            return "Customers".equalsIgnoreCase(relationName)
                    ? IntStream.range(0, OUTER_ROWS)
                            .mapToObj(i -> (Row) ArrayRow.of(schema,
                                    NumberValue.of(String.valueOf(i % DISTINCT_ARGUMENTS))))
                    : IntStream.range(0, BODY_ROWS)
                            .mapToObj(i -> (Row) ArrayRow.of(schema,
                                    NumberValue.of(String.valueOf(i)),
                                    NumberValue.of(String.valueOf(i % DISTINCT_ARGUMENTS))));
        }

        int opensOf(String relation) {
            return opens.getOrDefault(relation.toLowerCase(java.util.Locale.ROOT), 0);
        }
    }

    private static CountingConnector run(String query) {
        SemanticModel model = model(SOURCES + query);
        RelNode logical =
                ((ExpressionQueryTarget) model.rootQueries().getFirst().target()).expression();
        CountingConnector connector = new CountingConnector();
        ExecutionContext ctx = ExecutionContext.of(model, connector);
        try (Stream<Row> stream = new RelNodeExecutor().execute(logical, ctx)) {
            stream.forEach(_ -> { });
        }
        return connector;
    }

    @Test
    @DisplayName("the body is evaluated once per distinct argument, not once per outer row")
    void bodyRunsOncePerDistinctArgument() {
        CountingConnector connector = run(
                "query { Customers LATERAL ordersFor(customer_id) };");

        assertThat(connector.opensOf("Customers"))
                .as("the outer relation is read once")
                .isEqualTo(1);
        assertThat(connector.opensOf("Orders"))
                .as("%d outer rows carry only %d distinct arguments between them, so the "
                    + "body must be evaluated %d times. One open per outer row means the "
                    + "memo stopped working — the rows would still be right, which is why "
                    + "nothing else in the suite would notice.",
                        OUTER_ROWS, DISTINCT_ARGUMENTS, DISTINCT_ARGUMENTS)
                .isEqualTo(DISTINCT_ARGUMENTS);
    }

    @Test
    @DisplayName("the saving grows with the outer relation, not with the query")
    void theSavingIsAsymptotic() {
        // The same query over a tenth of the rows must do the *same* body work: that is what
        // makes this a complexity property rather than a constant factor. If the two ever
        // differ, the memo has become sensitive to something it should not see.
        CountingConnector large = run("query { Customers LATERAL ordersFor(customer_id) };");
        CountingConnector bounded = run(
                "query { λ 50 (Customers) LATERAL ordersFor(customer_id) };");

        assertThat(bounded.opensOf("Orders"))
                .as("body evaluations track distinct arguments, which both queries share")
                .isEqualTo(large.opensOf("Orders"));
    }

    @Test
    @DisplayName("every outer row still gets its rows — the saving is not a dropped join")
    void resultIsStillComplete() {
        SemanticModel model = model(SOURCES
                + "query { Customers LATERAL ordersFor(customer_id) };");
        RelNode logical =
                ((ExpressionQueryTarget) model.rootQueries().getFirst().target()).expression();

        try (Stream<Row> stream = new RelNodeExecutor()
                .execute(logical, ExecutionContext.of(model, new CountingConnector()))) {
            // Each of BODY_ROWS orders matches exactly one of the DISTINCT_ARGUMENTS ids, so
            // each outer row joins BODY_ROWS / DISTINCT_ARGUMENTS of them. A memo that
            // returned a spent stream would show up here as a short result rather than a
            // wrong count above.
            assertThat(stream.count())
                    .as("a memo bug that dropped rows would look like a *faster* query")
                    .isEqualTo((long) OUTER_ROWS * (BODY_ROWS / DISTINCT_ARGUMENTS));
        }
    }
}

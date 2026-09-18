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
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A caller can stop a query it started, by interrupting the thread running it.
 *
 * <p>There was no way to do that at all: no deadline, no cancel, and nothing in the
 * executor that read the interrupt flag. Closing a stream early does stop the work, the
 * engine being pull-based end to end, but that needs the consuming thread to be in
 * control — which is precisely what it is not while a blocking operator drains its input
 * inside one {@code tryAdvance}.
 *
 * <p>The flag is checked at two points and both are needed: the root stream, which every
 * row a consumer pulls passes through, and the buffering seam, which is where an operator
 * sits for the whole of a drain the root never sees.
 */
@DisplayName("An interrupted thread stops the query it was running")
final class CancellationTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static final String DATA = """
            Rows := [
            | id | grp |
            |----|-----|
            | 1  | a   |
            | 2  | a   |
            | 3  | b   |
            ];
            """;

    /** Never leave the flag raised for whatever runs next on this thread. */
    @AfterEach
    void clearTheFlag() {
        Thread.interrupted();
    }

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    private static void drain(String expression) {
        SemanticModel m = model(DATA + "query { " + expression + " };\n");
        ExecutionContext ctx = ExecutionContext.inlineOnly(m);
        try (Stream<Row> rows = EXECUTOR.execute(queryNode(m.rootQueries().getFirst()), ctx)) {
            rows.forEach(unused -> { });
        }
    }

    /**
     * Both shapes, because they are stopped in different places. A streaming plan yields a
     * row per pull and is caught at the root; a blocking one is inside a single pull for
     * its whole drain and is caught at the buffering seam, which the root cannot reach.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '~', textBlock = """
            streaming   ~ π id (σ id > 0 (Rows))
            blocking    ~ τ id DESC (Rows)
            aggregating ~ γ grp, COUNT(*) → n (Rows)
            set op      ~ (π grp (Rows)) ∩ (π grp (Rows))
            """)
    void anInterruptedQueryStops(String shape, String expression) {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> drain(expression))
                .as("%s: an interrupted thread must not keep running the query", shape)
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("cancelled");
    }

    /**
     * The flag is restored on the way out. Reading it clears it, so a check that did not
     * put it back would hand a caller a thread that no longer knows it was interrupted —
     * and a pool handing that thread on would lose the cancellation entirely.
     */
    @Test
    @DisplayName("the interrupt flag survives the cancellation")
    void theFlagIsRestored() {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> drain("τ id DESC (Rows)"))
                .isInstanceOf(EvaluationException.class);
        assertThat(Thread.currentThread().isInterrupted())
                .as("a caller that wants to interrupt again, or to return the thread to a "
                    + "pool, must find it as it left it")
                .isTrue();
    }

    @Test
    @DisplayName("an uninterrupted query is unaffected")
    void anUninterruptedQueryRuns() {
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        drain("τ id DESC (Rows)");   // no exception
    }
}

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
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RelNodeExecutor — gap-and-island / sessionization (SESSIONIZE)")
final class SessionizeExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    /** Runs the query and maps each output row through {@code render}, in emitted order. */
    private static List<String> run(String src, java.util.function.Function<Row, String> render) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.map(render).toList();
        }
    }

    private static String seqSession(Row r) {
        return r.get("seq").asDisplayString() + ":" + r.get("session").asDisplayString();
    }

    @Test
    @DisplayName("gaps wider than the threshold open new sessions; the first row is session 1")
    void gapsOpenSessions() {
        String src =
                "Readings := [| seq |\n" +
                "             | 1   |\n" +
                "             | 2   |\n" +
                "             | 5   |\n" +
                "             | 6   |\n" +
                "             | 20  |];\n" +
                "query { SESSIONIZE seq GAP 2 AS session (Readings) };";
        assertThat(run(src, SessionizeExecutionTest::seqSession))
                .containsExactly("1:1", "2:1", "5:2", "6:2", "20:3");
    }

    @Test
    @DisplayName("a gap exactly equal to the threshold stays in the same session (strict >)")
    void equalGapStaysInSession() {
        String src =
                "Readings := [| seq |\n" +
                "             | 1   |\n" +
                "             | 3   |];\n" +
                "query { SESSIONIZE seq GAP 2 AS session (Readings) };";
        assertThat(run(src, SessionizeExecutionTest::seqSession))
                .containsExactly("1:1", "3:1");
    }

    @Test
    @DisplayName("unsorted input is ordered by the order column before sessionizing")
    void unsortedInputIsSorted() {
        String src =
                "Readings := [| seq |\n" +
                "             | 5   |\n" +
                "             | 1   |\n" +
                "             | 6   |\n" +
                "             | 2   |];\n" +
                "query { SESSIONIZE seq GAP 2 AS session (Readings) };";
        assertThat(run(src, SessionizeExecutionTest::seqSession))
                .containsExactly("1:1", "2:1", "5:2", "6:2");
    }

    @Test
    @DisplayName("PER partitions are sessionized independently — the id restarts per partition")
    void perPartitionRestart() {
        String src =
                "Hits := [| u | seq |\n" +
                "         | a | 1   |\n" +
                "         | a | 4   |\n" +
                "         | b | 1   |\n" +
                "         | b | 2   |];\n" +
                "query { SESSIONIZE seq GAP 2 PER u AS session (Hits) };";
        List<String> rows = run(src,
                r -> r.get("u").asDisplayString() + ":" + seqSession(r));
        // a: 1→s1, 4 (gap 3 > 2)→s2 ; b: 1→s1, 2 (gap 1)→s1
        assertThat(rows).containsExactlyInAnyOrder("a:1:1", "a:4:2", "b:1:1", "b:2:1");
    }

    @Test
    @DisplayName("a NULL order value keeps the running session, from either side of the gap")
    void nullOrderValueKeepsTheSession() {
        // The gap test bails on a NULL in *either* operand, and each is its own arm. The
        // ordering puts NULLs last, so the first NULL row is the `current is NULL` case
        // and a second one is the `previous is NULL` case — two rows are needed to reach
        // both. A gap cannot be measured against an unknown, and inventing a boundary
        // there would split a session on missing data rather than on elapsed distance.
        String src =
                "Readings := [| seq  |\n" +
                "             | 1    |\n" +
                "             | NULL |\n" +
                "             | 2    |\n" +
                "             | 30   |\n" +
                "             | NULL |];\n" +
                "query { SESSIONIZE seq GAP 2 AS session (Readings) };";
        assertThat(run(src, SessionizeExecutionTest::seqSession))
                .as("NULLs sort last and inherit the running session rather than opening one; "
                        + "the 2 → 30 jump still opens session 2")
                .containsExactly("1:1", "2:1", "30:2", "NULL:2", "NULL:2");
    }

    @Test
    @DisplayName("a single row yields one session")
    void singleRow() {
        String src =
                "Readings := [| seq |\n" +
                "             | 7   |];\n" +
                "query { SESSIONIZE seq GAP 2 AS session (Readings) };";
        assertThat(run(src, SessionizeExecutionTest::seqSession)).containsExactly("7:1");
    }

    @Test
    @DisplayName("ISO-8601 timestamp strings in inline tables work with DURATION gap (issue #277)")
    void timestampStringsWithDurationGap() {
        String src =
                "Events := [\n" +
                "| user_id | ts                   |\n" +
                "| 1       | 2026-01-01T10:00:00Z |\n" +
                "| 1       | 2026-01-01T10:20:00Z |\n" +
                "| 1       | 2026-01-01T11:30:00Z |\n" +
                "| 2       | 2026-01-01T09:00:00Z |\n" +
                "];\n" +
                "query { SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS session (Events) };";
        List<String> rows = run(src,
                r -> r.get("user_id").asDisplayString() + ":"
                        + r.get("ts").asDisplayString() + ":"
                        + r.get("session").asDisplayString());
        // user 1: 10:00→10:20 gap 20m ≤ 30m (session 1); 10:20→11:30 gap 70m > 30m (session 2)
        // user 2: single event → session 1
        assertThat(rows).containsExactlyInAnyOrder(
                "1:2026-01-01T10:00:00Z:1",
                "1:2026-01-01T10:20:00Z:1",
                "1:2026-01-01T11:30:00Z:2",
                "2:2026-01-01T09:00:00Z:1");
    }

    @Test
    @DisplayName("input columns are preserved alongside the appended session column")
    void preservesInputColumns() {
        String src =
                "Readings := [| seq | label |\n" +
                "             | 1   | a     |\n" +
                "             | 9   | b     |];\n" +
                "query { SESSIONIZE seq GAP 2 AS session (Readings) };";
        assertThat(run(src, r -> r.get("label").asDisplayString() + ":" + seqSession(r)))
                .containsExactly("a:1:1", "b:9:2");
    }
}

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AS-OF join (ASOF) execution")
final class AsOfJoinExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named  -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    private static List<Row> collect(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    /** Trades partitioned by sym, with a numeric clock t (any ordered type works). */
    private static final String TRADES =
            "Trades := [| sym | t  | px  |\n" +
            "            | A   | 10 | 100 |\n" +
            "            | A   | 20 | 200 |\n" +
            "            | B   | 15 | 50  |];\n";

    private static final String QUOTES =
            "Quotes := [| qsym | qt | bid |\n" +
            "            | A    | 5  | 99  |\n" +
            "            | A    | 15 | 199 |\n" +
            "            | A    | 25 | 299 |\n" +
            "            | B    | 10 | 49  |];\n";

    @Test
    @DisplayName("backward (>=) joins each probe to the most recent at-or-before quote")
    void backwardNearest() {
        var rows = collect(TRADES + QUOTES +
                "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };");

        assertThat(rows).extracting(r -> r.get("t").asDisplayString() + "->" + r.get("bid").asDisplayString())
                .containsExactly("10->99", "20->199", "15->49");
    }

    @Test
    @DisplayName("output schema concatenates left and right columns")
    void outputSchema() {
        var rows = collect(TRADES + QUOTES +
                "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };");
        assertThat(rows.getFirst().schema().columns())
                .extracting(c -> c.name())
                .containsExactly("sym", "t", "px", "qsym", "qt", "bid");
    }

    @Test
    @DisplayName("forward (<=) joins each probe to the earliest at-or-after quote")
    void forwardNearest() {
        var rows = collect(TRADES + QUOTES +
                "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t <= Quotes.qt Quotes };");

        // (A,10)→qt15=199 ; (A,20)→qt25=299 ; (B,15)→ no qt>=15 → NULL
        assertThat(rows).extracting(r -> r.get("t").asDisplayString() + "->" + r.get("bid").asDisplayString())
                .containsExactly("10->199", "20->299", "15->NULL");
    }

    @Test
    @DisplayName("left-outer: a probe with no qualifying quote keeps NULL right columns")
    void noMatchNullPadded() {
        // Every quote for A is at qt>=15; a probe at t=1 has nothing at-or-before.
        var rows = collect(
                "Trades := [| sym | t |\n" +
                "            | A   | 1 |];\n" +
                "Quotes := [| qsym | qt | bid |\n" +
                "            | A    | 15 | 199 |];\n" +
                "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("bid").isNull()).isTrue();
    }

    @Test
    @DisplayName("strict (>) excludes an exact-timestamp match")
    void strictExcludesEqual() {
        var rows = collect(
                "Trades := [| sym | t  |\n" +
                "            | A   | 15 |];\n" +
                "Quotes := [| qsym | qt | bid |\n" +
                "            | A    | 10 | 49  |\n" +
                "            | A    | 15 | 99  |];\n" +
                // strict > : qt=15 is excluded, so the nearest before is qt=10
                "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t > Quotes.qt Quotes };");

        assertThat(rows.getFirst()).hasValue("bid", "49");
    }

    @Test
    @DisplayName("partition keys isolate matches: a probe never sees another partition's rows")
    void partitionIsolation() {
        var rows = collect(
                "Trades := [| sym | t  |\n" +
                "            | B   | 99 |];\n" +
                "Quotes := [| qsym | qt | bid |\n" +
                "            | A    | 10 | 49  |];\n" +   // only partition A exists
                "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };");

        assertThat(rows.getFirst().get("bid").isNull()).isTrue();
    }

    @Test
    @DisplayName("on a tie (equal match value) the last right row in input order wins")
    void tieKeepsLastInInputOrder() {
        var rows = collect(
                "Trades := [| sym | t  |\n" +
                "            | A   | 20 |];\n" +
                "Quotes := [| qsym | qt | bid |\n" +
                "            | A    | 10 | 1   |\n" +
                "            | A    | 10 | 2   |];\n" +   // tie at qt=10
                "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };");

        assertThat(rows.getFirst()).hasValue("bid", "2");
    }

    @Test
    @DisplayName("INNER mode drops probes with no qualifying match (no NULL padding)")
    void innerModeDropsUnmatchedProbes() {
        // Probe at t=1 has no quote at-or-before; in INNER mode the probe is dropped entirely.
        var rows = collect(
                "Trades := [| sym | t |\n" +
                "            | A   | 1 |];\n" +
                "Quotes := [| qsym | qt | bid |\n" +
                "            | A    | 15 | 199 |];\n" +
                "query { Trades ASOF INNER Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt Quotes };");

        assertThat(rows).isEmpty();
    }

    /** The same trades and quotes on a real clock: text cells converted to TIMESTAMP. */
    private static final String TIMED =
            "RawQuotes := [| qsym | qt                   | bid |\n" +
            "               | A    | 2026-01-02T09:00:00Z | 99  |];\n" +
            "RawTrades := [| sym | t                    | px  |\n" +
            "               | A   | 2026-01-02T09:00:30Z | 100 |\n" +
            "               | A   | 2026-01-02T09:59:00Z | 200 |];\n" +
            "Q := { π qsym, to_timestamp(qt) → qt, bid (RawQuotes) };\n" +
            "T := { π sym, to_timestamp(t) → t, px (RawTrades) };\n";

    @Test
    @DisplayName("WITHIN over TIMESTAMPs drops the candidate that is further away than the bound")
    void withinToleranceBoundsTimestampDistance() {
        var rows = collect(TIMED +
                "query { T ASOF INNER T.sym = Q.qsym ∧ T.t >= Q.qt WITHIN DURATION 'PT1M' Q };");

        // 09:00:30 is 30s after the quote and matches; 09:59:00 is 59 minutes out and does not.
        assertThat(rows).extracting(r -> r.get("px").asDisplayString()).containsExactly("100");
    }

    @Test
    @DisplayName("WITHIN over DATEs measures a whole-day span")
    void withinToleranceBoundsDateDistance() {
        var rows = collect(
                "RawBooks := [| bsym | bd         | close |\n" +
                "              | A    | 2026-01-01 | 99    |];\n" +
                "RawFills := [| sym | d          | px  |\n" +
                "              | A   | 2026-01-02 | 100 |\n" +
                "              | A   | 2026-01-10 | 200 |];\n" +
                "B := { π bsym, to_date(bd) → bd, close (RawBooks) };\n" +
                "F := { π sym, to_date(d) → d, px (RawFills) };\n" +
                "query { F ASOF INNER F.sym = B.bsym ∧ F.d >= B.bd WITHIN DURATION \'P2D\' B };");

        // 1 day after the book date matches; 9 days after does not.
        assertThat(rows).extracting(r -> r.get("px").asDisplayString()).containsExactly("100");
    }

    @Test
    @DisplayName("WITHIN over values with no temporal distance fails loudly rather than passing everything")
    void withinToleranceWithoutDistanceIsAnError() {
        // The validator rejects this ahead of execution wherever the match column\'s type is
        // known (see AsOfJoinSemanticTest); driving the executor past it pins the other half
        // of the rule — the case an ANY column hides until the values arrive.
        assertThatThrownBy(() -> collect(TRADES + QUOTES +
                "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt "
                + "WITHIN DURATION \'PT30M\' Quotes };"))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("AS-OF WITHIN measures a temporal distance")
                .hasMessageContaining("NUMBER");
    }

    @Test
    @DisplayName("TIES(FIRST) picks the first right row in input order among ties")
    void tiesFirstPicksFirstInInputOrder() {
        var rows = collect(
                "Trades := [| sym | t  |\n" +
                "            | A   | 20 |];\n" +
                "Quotes := [| qsym | qt | bid |\n" +
                "            | A    | 10 | 1   |\n" +
                "            | A    | 10 | 2   |];\n" +   // tie at qt=10; FIRST wins bid=1
                "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt TIES(FIRST) Quotes };");

        assertThat(rows.getFirst()).hasValue("bid", "1");
    }

    @Test
    @DisplayName("TIES(LAST) picks the last right row in input order among ties (default behaviour)")
    void tiesLastPicksLastInInputOrder() {
        var rows = collect(
                "Trades := [| sym | t  |\n" +
                "            | A   | 20 |];\n" +
                "Quotes := [| qsym | qt | bid |\n" +
                "            | A    | 10 | 1   |\n" +
                "            | A    | 10 | 2   |];\n" +   // tie at qt=10; LAST wins bid=2
                "query { Trades ASOF Trades.sym = Quotes.qsym ∧ Trades.t >= Quotes.qt TIES(LAST) Quotes };");

        assertThat(rows.getFirst()).hasValue("bid", "2");
    }
}

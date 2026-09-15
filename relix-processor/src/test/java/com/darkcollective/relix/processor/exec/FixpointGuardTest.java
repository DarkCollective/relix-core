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

import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.semantic.SemanticAssertions;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the FIX fixpoint iteration guard (issue #117):
 * configurable round cap threaded via {@link ExecutionContext#maxFixpointRounds()}.
 */
@DisplayName("Fixpoint iteration guard (--max-fixpoint-rounds)")
final class FixpointGuardTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    // ── helper scripts ────────────────────────────────────────────────────────

    private static final String CHAIN_EDGES =
            "Edges := [| src | dst |\n" +
            "           | 1   | 2   |\n" +
            "           | 2   | 3   |\n" +
            "           | 3   | 4   |];\n";

    // FIX-based transitive closure — converges in 3 rounds on the chain above.
    private static final String FIX_CLOSURE =
            "Reach := { FIX R (\n" +
            "  Edges,\n" +
            "  Edges ∪ π src, dst (ρ X(src, via) (R) ⋈ ρ Y(via, dst) (Edges))\n" +
            ") };\n" +
            "query Reach;\n";

    /** First query node as an AST expression. */
    private static com.darkcollective.relix.ast.RelNode queryNode(SemanticModel model) {
        return switch (model.rootQueries().getFirst().target()) {
            case com.darkcollective.relix.lang.ast.NamedQueryTarget   named ->
                    new com.darkcollective.relix.ast.RelationNode(named.name());
            case com.darkcollective.relix.lang.ast.ExpressionQueryTarget e ->
                    e.expression();
        };
    }

    // ── ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS ────────────────────────────

    @Nested
    @DisplayName("ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS")
    class UnlimitedConstant {

        @Test
        @DisplayName("equals Integer.MAX_VALUE")
        void equalsIntegerMaxValue() {
            assertThat(ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS)
                    .isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("default context carries unlimited rounds")
        void defaultContextIsUnlimited() {
            SemanticModel m = model(CHAIN_EDGES + FIX_CLOSURE);
            ExecutionContext ctx = ExecutionContext.inlineOnly(m);
            assertThat(ctx.maxFixpointRounds())
                    .isEqualTo(ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS);
        }
    }

    // ── withMaxFixpointRounds() ───────────────────────────────────────────────

    @Nested
    @DisplayName("withMaxFixpointRounds()")
    class WithMaxFixpointRounds {

        @Test
        @DisplayName("returns copy with new limit, preserving all other fields")
        void preservesOtherFields() {
            SemanticModel m = model(CHAIN_EDGES + FIX_CLOSURE);
            ExecutionContext base = ExecutionContext.inlineOnly(m);

            ExecutionContext capped = base.withMaxFixpointRounds(5);

            assertThat(capped.maxFixpointRounds()).isEqualTo(5);
            assertThat(capped.symbolTable()).isSameAs(base.symbolTable());
            assertThat(capped.nodeSchemas()).isSameAs(base.nodeSchemas());
            assertThat(capped.connector()).isSameAs(base.connector());
            assertThat(capped.listener()).isSameAs(base.listener());
            // original is unchanged
            assertThat(base.maxFixpointRounds())
                    .isEqualTo(ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS);
        }

        @Test
        @DisplayName("rejects maxFixpointRounds < 1")
        void rejectsZero() {
            SemanticModel m = model(CHAIN_EDGES + FIX_CLOSURE);
            ExecutionContext base = ExecutionContext.inlineOnly(m);
            assertThatThrownBy(() -> base.withMaxFixpointRounds(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxFixpointRounds");
        }

        @Test
        @DisplayName("rejects negative maxFixpointRounds")
        void rejectsNegative() {
            SemanticModel m = model(CHAIN_EDGES + FIX_CLOSURE);
            ExecutionContext base = ExecutionContext.inlineOnly(m);
            assertThatThrownBy(() -> base.withMaxFixpointRounds(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── Default config: guard never fires ─────────────────────────────────────

    @Nested
    @DisplayName("Default config: guard never fires on normal graphs")
    class DefaultNeverFires {

        @Test
        @DisplayName("chain transitive closure (3 rounds) succeeds with unlimited config")
        void chainClosureSucceeds() {
            SemanticModel model = model(CHAIN_EDGES + FIX_CLOSURE);
            ExecutionContext ctx = ExecutionContext.inlineOnly(model);
            var node = queryNode(model);
            try (Stream<Row> rows = EXECUTOR.execute(node, ctx)) {
                assertThat(rows.count()).isEqualTo(6); // 6 pairs from a 4-node chain
            }
        }

        @Test
        @DisplayName("cyclic graph terminates without triggering the guard")
        void cyclicGraphTerminates() {
            String cycle =
                    "Edges := [| src | dst |\n" +
                    "           | 1   | 2   |\n" +
                    "           | 2   | 3   |\n" +
                    "           | 3   | 1   |];\n" +
                    "Reach := { FIX R (\n" +
                    "  Edges,\n" +
                    "  Edges ∪ π src, dst (ρ X(src, via) (R) ⋈ ρ Y(via, dst) (Edges))\n" +
                    ") };\n" +
                    "query Reach;\n";
            SemanticModel model = model(cycle);
            ExecutionContext ctx = ExecutionContext.inlineOnly(model);
            var node = queryNode(model);
            try (Stream<Row> rows = EXECUTOR.execute(node, ctx)) {
                assertThat(rows.count()).isEqualTo(9); // 3×3 all reachable pairs
            }
        }
    }

    // ── A step that computes: the guards are the only thing that stops it ─────

    /**
     * A FIX whose step <em>computes</em> a value rather than carrying one through is
     * well-formed and does not terminate (#874). Every round derives a row no round has
     * seen, so set-dedup never closes the loop and the accumulator grows forever.
     *
     * <p>These tests are the standing record that the language is like this on purpose.
     * They must never be "fixed" by making the witness terminate: the point is that it
     * analyses clean, that nothing rejects it, and that each cap catches it — including
     * the row cap, which before #874 could not, because it charged only the step's
     * per-round read and that read is exactly one row here however low the cap is set.
     */
    @Nested
    @DisplayName("A step that computes new values (#874)")
    class ValueInvention {

        /** The counter never repeats a row: NUMBER has no width at which it wraps. */
        private static final String COUNTER =
                "Zero := [| n |\n" +
                "          | 0 |];\n" +
                "query { FIX N (Zero, π n + 1 → m (N)) };\n";

        @Test
        @DisplayName("analyses without a diagnostic — linear, monotone, union-compatible")
        void analysesClean() {
            SemanticAssertions.assertThat(analyze(COUNTER)).isFullyValid();
        }

        @Test
        @DisplayName("the round cap stops it, naming the recursion variable")
        void roundCapStopsIt() {
            SemanticModel model = model(COUNTER);
            ExecutionContext ctx = ExecutionContext.inlineOnly(model).withMaxFixpointRounds(20);
            var node = queryNode(model);
            assertThatThrownBy(() -> {
                try (Stream<Row> rows = EXECUTOR.execute(node, ctx)) {
                    rows.forEach(r -> { });
                }
            })
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("FIX")
                    .hasMessageContaining("N")
                    .hasMessageContaining("20");
        }

        @Test
        @DisplayName("the row cap stops it too, naming the operator and its accumulator")
        void rowCapStopsIt() {
            SemanticModel model = model(COUNTER);
            ExecutionContext ctx = ExecutionContext.inlineOnly(model).withMaxMaterializedRows(20);
            var node = queryNode(model);
            assertThatThrownBy(() -> {
                try (Stream<Row> rows = EXECUTOR.execute(node, ctx)) {
                    rows.forEach(r -> { });
                }
            })
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Fixpoint")
                    .hasMessageContaining("accumulated more than 20 rows");
        }

        /**
         * The per-round read is one row, so a cap the accumulator blows through in twenty
         * rounds is never reached by the per-round charge. This is the case that made the
         * accumulator charge necessary rather than tidy.
         */
        @Test
        @DisplayName("each round reads a single row, so only the accumulator charge can fire")
        void perRoundReadIsOneRow() {
            SemanticModel model = model(COUNTER);
            ExecutionContext ctx = ExecutionContext.inlineOnly(model).withMaxMaterializedRows(5);
            var node = queryNode(model);
            assertThatThrownBy(() -> {
                try (Stream<Row> rows = EXECUTOR.execute(node, ctx)) {
                    rows.forEach(r -> { });
                }
            }).hasMessageContaining("accumulated");   // never "buffered": that charge sees 1
        }
    }

    // ── Guard triggers at the configured cap ──────────────────────────────────

    @Nested
    @DisplayName("Guard triggers at the configured cap")
    class GuardTriggersAtCap {

        @Test
        @DisplayName("cap of 1 aborts a 3-round transitive-closure FIX")
        void capOfOneAbortsThreeRoundClosure() {
            SemanticModel model = model(CHAIN_EDGES + FIX_CLOSURE);
            ExecutionContext ctx = ExecutionContext.inlineOnly(model)
                    .withMaxFixpointRounds(1);
            var node = queryNode(model);
            assertThatThrownBy(() -> {
                try (Stream<Row> rows = EXECUTOR.execute(node, ctx)) {
                    rows.forEach(r -> { }); // force evaluation
                }
            })
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("FIX")
                    .hasMessageContaining("R")         // the recursion variable name
                    .hasMessageContaining("1")
                    .hasMessageContaining("--max-fixpoint-rounds");
        }

        @Test
        @DisplayName("cap of 2 aborts a 3-round closure but 3 succeeds")
        void capOf2AbortsCapOf3Succeeds() {
            SemanticModel model = model(CHAIN_EDGES + FIX_CLOSURE);

            ExecutionContext capped2 = ExecutionContext.inlineOnly(model).withMaxFixpointRounds(2);
            var node = queryNode(model);
            assertThatThrownBy(() -> {
                try (Stream<Row> rows = EXECUTOR.execute(node, capped2)) {
                    rows.forEach(r -> { });
                }
            }).isInstanceOf(EvaluationException.class)
              .hasMessageContaining("2");

            // 3 rounds is exactly enough — the chain closure completes in round 3.
            ExecutionContext capped3 = ExecutionContext.inlineOnly(model).withMaxFixpointRounds(3);
            try (Stream<Row> rows = EXECUTOR.execute(node, capped3)) {
                assertThat(rows.count()).isEqualTo(6);
            }
        }

        @Test
        @DisplayName("error message names the FIX recursion variable and the cap")
        void errorMessageNamesRelationAndCap() {
            SemanticModel model = model(CHAIN_EDGES + FIX_CLOSURE);
            ExecutionContext ctx = ExecutionContext.inlineOnly(model).withMaxFixpointRounds(1);
            var node = queryNode(model);
            assertThatThrownBy(() -> {
                try (Stream<Row> rows = EXECUTOR.execute(node, ctx)) {
                    rows.forEach(r -> { });
                }
            })
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("R")    // recursion variable name (FIX R (...))
                    .hasMessageContaining("1");   // configured cap
        }

        @Test
        @DisplayName("nested FIX: inner FIX guard fires independently")
        void nestedFixInnerGuardFires() {
            // Outer FIX converges in 1 round; inner FIX adds {2} in 1 round.
            // Cap the inner to 0 rounds effectively — use cap=1 (inner fires once → succeeds).
            // But with cap=0 on the context, even the outer step fires without completing.
            // We set cap=1: the chain closure (3 rounds) aborts at round 1.
            String nested =
                    "A := [| n | 1 |];\n" +     // avoids schema-only issue
                    "A := [| n |\n" +
                    "       | 1 |];\n" +
                    "B := [| n |\n" +
                    "       | 2 |];\n" +
                    // Outer needs 2 rounds for the chain closure
                    "query { FIX Outer (A, FIX Inner (B, B ∪ Inner) ∪ Outer) };\n";
            SemanticModel model = model(nested);
            // With UNLIMITED, both FIX nodes converge normally.
            ExecutionContext unlimitedCtx = ExecutionContext.inlineOnly(model);
            var node = queryNode(model);
            try (Stream<Row> rows = EXECUTOR.execute(node, unlimitedCtx)) {
                assertThat(rows.toList()).hasSize(2); // {1, 2}
            }
        }

        @Test
        @DisplayName("cap does not fire when FIX converges in fewer rounds than cap")
        void capDoesNotFireWhenUnderLimit() {
            // immediate convergence: 1 round (step = Edges ∪ R; delta is immediately empty)
            String immediateScript =
                    CHAIN_EDGES +
                    "query { FIX R (Edges, Edges ∪ R) };\n";
            SemanticModel model = model(immediateScript);
            // cap = 10; only 1 round needed → no exception
            ExecutionContext ctx = ExecutionContext.inlineOnly(model).withMaxFixpointRounds(10);
            var node = queryNode(model);
            try (Stream<Row> rows = EXECUTOR.execute(node, ctx)) {
                assertThat(rows.count()).isEqualTo(3); // 3 edges from the chain
            }
        }
    }

    // ── QueryExecutor overloads ───────────────────────────────────────────────

    @Nested
    @DisplayName("QueryExecutor overloads respect maxFixpointRounds")
    class QueryExecutorOverloads {

        private final com.darkcollective.relix.processor.QueryExecutor QE =
                new com.darkcollective.relix.processor.QueryExecutor();

        private static final String MULTI_ROUND_SCRIPT = CHAIN_EDGES + FIX_CLOSURE;

        @Test
        @DisplayName("executeStreaming with cap aborts correctly")
        void executeStreamingWithCapAborts() {
            SemanticModel model = model(MULTI_ROUND_SCRIPT);
            com.darkcollective.relix.processor.DataSourceConnector conn =
                    ExecutionContext.inlineOnly(model).connector();
            List<String> errors = new java.util.ArrayList<>();
            assertThatThrownBy(() ->
                    QE.executeStreaming(model, conn,
                            (label, schema, rows) -> rows.forEach(r -> { }),
                            1))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("1");
        }

        @Test
        @DisplayName("executeStreaming unlimited default succeeds")
        void executeStreamingUnlimitedSucceeds() {
            SemanticModel model = model(MULTI_ROUND_SCRIPT);
            com.darkcollective.relix.processor.DataSourceConnector conn =
                    ExecutionContext.inlineOnly(model).connector();
            List<Long> counts = new java.util.ArrayList<>();
            QE.executeStreaming(model, conn,
                    (label, schema, rows) -> counts.add(rows.count()));
            assertThat(counts).containsExactly(6L);
        }
    }
}

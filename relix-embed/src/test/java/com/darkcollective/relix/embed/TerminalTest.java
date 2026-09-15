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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.plan.BoundednessException;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.provenance.BooleanSemiring;
import com.darkcollective.relix.provenance.CountingSemiring;
import com.darkcollective.relix.provenance.PolynomialSemiring;
import com.darkcollective.relix.provenance.TropicalSemiring;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatRows;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;

@DisplayName("Relation — the terminals")
final class TerminalTest {

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    /** A session over rows the program holds, so every terminal runs with nothing external. */
    private static Relix orders() {
        Relix relix = Relix.open();
        relix.table("Orders", List.of(
                row("order_id", 1, "status", "OPEN", "amount", 100),
                row("order_id", 2, "status", "SHIPPED", "amount", 250),
                row("order_id", 3, "status", "OPEN", "amount", 75)));
        return relix;
    }

    // =========================================================================
    // Inspection
    // =========================================================================

    @Nested
    @DisplayName("inspection runs nothing")
    final class Inspection {

        @Test
        @DisplayName("render returns text the session reads back as the same expression")
        void renderRoundTrips() {
            try (Relix relix = orders()) {
                Relation open = relix.relation("σ status = 'OPEN' (Orders)");

                String text = open.render();

                assertThat(relix.relation(text).node())
                        .isStructurallyEqualTo(open.node());
            }
        }

        @Test
        @DisplayName("schema is the relation's own heading, not its input's")
        void schemaIsTheOutputHeading() {
            try (Relix relix = orders()) {
                Relation projected = relix.relation("Orders").project("order_id", "amount");

                assertThat(projected).schema().hasColumnNames("order_id", "amount");
            }
        }

        @Test
        @DisplayName("toString is the rendered expression, so a relation reads as itself in a log")
        void toStringRenders() {
            try (Relix relix = orders()) {
                Relation open = relix.relation("σ status = 'OPEN' (Orders)");

                assertThat(open).hasToString(open.render());
            }
        }

        @Test
        @DisplayName("ir reports the session's symbols and their headings")
        void irReportsTheSession() {
            try (Relix relix = orders()) {
                relix.define("Open := { σ status = 'OPEN' (Orders) };");

                assertThat(relix.ir())
                        .contains("SYMBOLS")
                        .contains("Orders")
                        .contains("Open");
            }
        }
    }

    @Nested
    @DisplayName("optimized — the rewriter, staged")
    final class Optimized {

        /** Two stacked σs are the rewrite with the least room for ambiguity: SEL-002 merges them. */
        private static final String STACKED = "σ status = 'OPEN' (σ amount > 50 (Orders))";

        @Test
        @DisplayName("returns the rewritten relation, and names the rules that fired")
        void rewritesAndReports() {
            try (Relix relix = orders()) {
                Relation optimised = relix.relation(STACKED).optimized();

                assertThat(optimised).renders().isNotEqualTo(STACKED);
                assertThat(optimised)
                        .as("the rewritten tree alone does not say which rule produced it")
                        .rewrote(OptimizationCode.SEL_002);
            }
        }

        @Test
        @DisplayName("a relation nobody staged carries no events")
        void unstagedCarriesNoEvents() {
            try (Relix relix = orders()) {
                assertThat(relix.relation(STACKED)).events().isEmpty();
                assertThat(relix.relation(STACKED)).rewroteNothing();
            }
        }

        @Test
        @DisplayName("expands a view, so rules optimise across a boundary written for clarity")
        void inlinesViews() {
            try (Relix relix = orders()) {
                relix.define("OpenOrders := { σ status = 'OPEN' (Orders) };");

                Relation inlined = relix.relation("σ amount > 50 (OpenOrders)").optimized();

                assertThat(inlined).renders().doesNotContain("OpenOrders");
                assertThat(inlined).hasSameRowsAs(relix.relation("σ amount > 50 (OpenOrders)"));
            }
        }

        @Test
        @DisplayName("render is staged — the original is untouched")
        void inspectionIsStaged() {
            try (Relix relix = orders()) {
                Relation written = relix.relation(STACKED);
                Relation optimised = written.optimized();

                assertThat(written).renders().isNotEqualTo(optimised.render());
            }
        }

        @Test
        @DisplayName("execution is not staged — both forms give the same rows")
        void executionIsNotStaged() {
            try (Relix relix = orders()) {
                Relation written = relix.relation(STACKED);

                assertThat(written).hasSameRowsAs(written.optimized());
            }
        }
    }

    @Nested
    @DisplayName("explain — the physical plan")
    final class Explain {

        @Test
        @DisplayName("prints the plan with its row estimates")
        void printsThePlan() {
            try (Relix relix = orders()) {
                assertThat(relix.relation("σ status = 'OPEN' (Orders)"))
                        .explains().contains("rows").isNotBlank();
            }
        }

        @Test
        @DisplayName("the JSON form names each operator")
        void jsonNamesEachOperator() {
            try (Relix relix = orders()) {
                String json = relix.relation("σ status = 'OPEN' (Orders)").explainJson();

                assertThat(json).contains("\"op\"");
            }
        }

        @Test
        @DisplayName("the plan and its estimates come back together")
        void planCarriesEstimates() {
            try (Relix relix = orders()) {
                var planned = relix.relation("Orders").plan();

                assertThat(planned.plan()).isNotNull();
                assertThat(planned.estimates()).isNotNull();
            }
        }

        @Test
        @DisplayName("planning reads no rows, so it works with the source unreachable")
        void planningReadsNothing() {
            try (Relix relix = Relix.open()) {
                relix.define("""
                        source Missing from csv("./does-not-exist.csv") {
                            header: true, schema: { id: NUMBER }
                        };
                        """);

                assertThat(relix.relation("Missing")).explains().isNotBlank();
            }
        }
    }

    // =========================================================================
    // Execution
    // =========================================================================

    @Nested
    @DisplayName("rows")
    final class Rows {

        @Test
        @DisplayName("toList drains and closes, returning every row")
        void toListReturnsEveryRow() {
            try (Relix relix = orders()) {
                assertThat(relix.relation("σ status = 'OPEN' (Orders)")).rows()
                        .hasRowCount(2)
                        .hasRow("1", "OPEN", "100")
                        .hasRow("3", "OPEN", "75");
            }
        }

        @Test
        @DisplayName("stream is lazy: nothing is pulled until the consumer asks")
        void streamIsLazy() {
            try (Relix relix = orders()) {
                try (Stream<Tuple> rows = relix.relation("Orders").stream()) {
                    assertThat(rows.limit(1).toList()).hasSize(1);
                }
            }
        }

        @Test
        @DisplayName("run carries the rows and this run's own events")
        void runCarriesItsOwnEvents() {
            try (Relix relix = orders()) {
                com.darkcollective.relix.embed.Rows result =
                        relix.relation("σ status = 'OPEN' (σ amount > 50 (Orders))").run();

                assertThatRows(result.schema(), result.rows()).hasRowCount(2);
                assertThat(result.events())
                        .as("a run observes itself; only a session-scoped feed sees the previous one")
                        .isNotEmpty();
                assertThat(result.size()).isEqualTo(2);
                assertThat(result.isEmpty()).isFalse();
                assertThat(result).hasSize(2);   // it iterates its rows
            }
        }

        @Test
        @DisplayName("run states its own cardinality as the last event, carrying the count")
        void runReportsItsCardinality() {
            try (Relix relix = orders()) {
                com.darkcollective.relix.embed.Rows result =
                        relix.relation("σ status = 'OPEN' (Orders)").run();

                QueryEvent last = result.events().getLast();
                assertThat(last.stage()).isEqualTo(QueryEvent.Stage.EXECUTE);
                assertThat(last.code()).isEqualTo("ROWS");
                assertThat(last.metrics().rows()).hasValue(2);
            }
        }

        @Test
        @DisplayName("run states how long it took, since the feed is the only profiler an embedder has")
        void runReportsItsElapsedTime() {
            // #788. Presence and sign, never a threshold: the number is wall clock, for a
            // person diagnosing a slow query rather than for an assertion.
            try (Relix relix = orders()) {
                com.darkcollective.relix.embed.Rows result =
                        relix.relation("σ status = 'OPEN' (Orders)").run();

                QueryEvent last = result.events().getLast();
                assertThat(last.code()).isEqualTo("ROWS");
                assertThat(last.metrics().duration()).isPresent();
                assertThat(last.metrics().duration().orElseThrow().isNegative()).isFalse();
            }
        }

        @Test
        @DisplayName("count is γ COUNT(*), so it answers without materialising the rows")
        void countCountsRows() {
            try (Relix relix = orders()) {
                assertThat(relix.relation("σ status = 'OPEN' (Orders)")).count().isEqualTo(2L);
                assertThat(relix.relation("Orders")).count().isEqualTo(3L);
            }
        }

        @Test
        @DisplayName("count of nothing is zero, not no answer")
        void countOfNothingIsZero() {
            try (Relix relix = orders()) {
                assertThat(relix.relation("σ status = 'NOPE' (Orders)")).count().isEqualTo(0L);
            }
        }

        @Test
        @DisplayName("a combinator composes onto a relation and the composition executes")
        void combinatorsCompose() {
            try (Relix relix = orders()) {
                Relation top = relix.relation("Orders")
                        .project("order_id", "amount")
                        .limit(1);

                assertThat(top).rows().hasRowCount(1).hasColumns("order_id", "amount");
            }
        }
    }

    @Nested
    @DisplayName("an unbounded relation")
    final class Unbounded {

        private static Relix naturals() {
            Relix relix = Relix.open();
            relix.define("source Naturals from generator { name: \"Naturals\" };");
            return relix;
        }

        @Test
        @DisplayName("is refused by toList rather than hanging")
        void toListRefuses() {
            try (Relix relix = naturals()) {
                assertThatThrownBy(() -> relix.relation("Naturals").toList())
                        .isInstanceOf(BoundednessException.class)
                        .hasMessageContaining("limit(n)");
            }
        }

        @Test
        @DisplayName("is refused by run for the same reason")
        void runRefuses() {
            try (Relix relix = naturals()) {
                assertThatThrownBy(() -> relix.relation("Naturals").run())
                        .isInstanceOf(BoundednessException.class);
            }
        }

        @Test
        @DisplayName("streams, because lazy consumption is what a generator is for")
        void streamsFine() {
            try (Relix relix = naturals()) {
                try (Stream<Tuple> rows = relix.relation("Naturals").stream()) {
                    assertThat(rows.limit(3).toList()).hasSize(3);
                }
            }
        }

        @Test
        @DisplayName("is refused by the planner when an operator would block, and the connector goes with it")
        void planningRefusesAndReleases() {
            try (Relix relix = naturals()) {
                // τ is blocking, so the plan-time check fires — inside stream(), which does
                // not pre-check. The connector opened for the run must be closed on the way
                // out: no stream reaches the caller to close it.
                assertThatThrownBy(() -> relix.relation("Naturals").sort(
                        com.darkcollective.relix.ast.AstBuilders.asc("n")).stream())
                        .isInstanceOf(BoundednessException.class);
            }
        }

        @Test
        @DisplayName("cannot be counted either — γ is blocking, so the planner refuses")
        void countRefuses() {
            try (Relix relix = naturals()) {
                assertThatThrownBy(() -> relix.relation("Naturals").count())
                        .isInstanceOf(BoundednessException.class);
            }
        }

        @Test
        @DisplayName("collects once bounded")
        void boundedCollects() {
            try (Relix relix = naturals()) {
                assertThat(relix.relation("Naturals").limit(3)).hasRowCount(3);
            }
        }
    }

    @Nested
    @DisplayName("provenance")
    final class Provenance {

        @Test
        @DisplayName("annotates the whole relation over the semiring")
        void annotatesOverASemiring() {
            try (Relix relix = orders()) {
                var annotated = relix.relation("σ status = 'OPEN' (Orders)")
                        .provenance(BooleanSemiring.INSTANCE);

                assertThat(annotated.size()).isEqualTo(2);
                assertThat(annotated.stream().toList()).allSatisfy(
                        t -> assertThat(t.annotation()).isEqualTo(Boolean.TRUE));
            }
        }

        @Test
        @DisplayName("ℕ counts multiplicity")
        void countingCountsMultiplicity() {
            try (Relix relix = orders()) {
                var annotated = relix.relation("Orders").provenance(CountingSemiring.INSTANCE);

                assertThat(annotated.size()).isEqualTo(3);
                assertThat(annotated.stream().toList()).allSatisfy(
                        t -> assertThat(t.annotation()).isEqualTo(java.math.BigInteger.ONE));
            }
        }

        @Test
        @DisplayName("a weight column is read into the semiring")
        void readsAWeightColumn() {
            try (Relix relix = Relix.open()) {
                relix.table("Edges", List.of(
                        row("src", "a", "dst", "b", "cost", 3),
                        row("src", "b", "dst", "c", "cost", 4)));

                var annotated = relix.relation("Edges")
                        .provenance(TropicalSemiring.INSTANCE, "cost");

                assertThat(annotated.stream().map(t -> t.annotation()).toList())
                        .containsExactlyInAnyOrder(3.0d, 4.0d);
            }
        }

        @Test
        @DisplayName("a column no row carries weighs the semiring's one")
        void anAbsentWeightColumnWeighsOne() {
            try (Relix relix = orders()) {
                var annotated = relix.relation("Orders")
                        .provenance(TropicalSemiring.INSTANCE, "no_such_column");

                assertThat(annotated.stream().map(t -> t.annotation()).toList())
                        .containsOnly(TropicalSemiring.INSTANCE.one());
            }
        }

        @Test
        @DisplayName("lineage mints a variable per base tuple, which no weight column supplies")
        void lineageMintsVariables() {
            try (Relix relix = orders()) {
                var annotated = relix.relation("σ status = 'OPEN' (Orders)")
                        .provenance(PolynomialSemiring.INSTANCE);

                assertThat(annotated.size()).isEqualTo(2);
                assertThat(annotated.stream().toList())
                        .allSatisfy(t -> assertThat(t.annotation().terms()).isNotEmpty());
            }
        }
    }

    @Nested
    @DisplayName("relative source paths")
    final class BaseDirectory {

        @Test
        @DisplayName("resolve against the session's base directory")
        void resolveAgainstTheBaseDirectory(@TempDir Path directory) throws IOException {
            Files.writeString(directory.resolve("orders.csv"), """
                    order_id,status
                    1,OPEN
                    2,SHIPPED
                    """);

            try (Relix relix = Relix.builder().baseDirectory(directory).build()) {
                relix.define("""
                        source Orders from csv("./orders.csv") {
                            header: true, schema: { order_id: NUMBER, status: STRING }
                        };
                        """);

                assertThat(relix.relation("σ status = 'OPEN' (Orders)")).rows()
                        .hasRowCount(1)
                        .hasRow("1", "OPEN");
            }
        }
    }

    @Nested
    @DisplayName("a closed session")
    final class Closed {

        @Test
        @DisplayName("refuses to execute, rather than failing where the pool is gone")
        void refusesToExecute() {
            Relix relix = orders();
            Relation orders = relix.relation("Orders");
            relix.close();

            assertThatThrownBy(orders::toList)
                    .isInstanceOf(RelixException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("still renders, because inspection touches nothing outside")
        void stillRenders() {
            Relix relix = orders();
            Relation orders = relix.relation("Orders");
            relix.close();

            assertThat(orders).renders().isEqualTo("Orders");
        }
    }

}

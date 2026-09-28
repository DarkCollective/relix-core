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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ITERATE} run end to end: what each stop clause returns, the two ways an
 * {@code UNTIL} test can fail and how they are told apart, the pairing rules of
 * {@code UNTIL … WITHIN … PER}, and the session guards.
 */
@DisplayName("ITERATE — replace-each-round iteration, executed")
final class IterateExecutionTest {

    /** Conway's Life: the eight neighbour offsets, and one generation of a board {@code B}. */
    private static final String LIFE = """
            Offsets := [
            | dx | dy |
            |----|----|
            | -1 | -1 |
            | -1 | 0  |
            | -1 | 1  |
            | 0  | -1 |
            | 0  | 1  |
            | 1  | -1 |
            | 1  | 0  |
            | 1  | 1  |
            ];
            Blinker := [
            | x | y |
            |---|---|
            | 1 | 0 |
            | 1 | 1 |
            | 1 | 2 |
            ];
            Block := [
            | x | y |
            |---|---|
            | 0 | 0 |
            | 0 | 1 |
            | 1 | 0 |
            | 1 | 1 |
            ];
            Glider := [
            | x | y |
            |---|---|
            | 1 | 0 |
            | 2 | 1 |
            | 0 | 2 |
            | 1 | 2 |
            | 2 | 2 |
            ];
            """;

    private static final String GENERATION = """
            π x, y (σ n = 3 (ρ C(x, y, n) (γ nx, ny, COUNT(*) → n (π x + dx → nx, y + dy → ny (B × Offsets)))))
            ∪ π x, y (σ n = 2 (ρ C(x, y, n) (γ nx, ny, COUNT(*) → n (π x + dx → nx, y + dy → ny (B × Offsets)))) ⋈ B)
            """;

    private static final String PAGERANK = """
            Links := [
            | src | dst |
            |-----|-----|
            | A   | B   |
            | A   | C   |
            | B   | C   |
            | C   | A   |
            | D   | C   |
            ];
            Pages := { δ (π src → page (Links) ∪ π dst → page (Links)) };
            OutDegree := { γ src, COUNT(*) → out (Links) };
            Weighted := { Links ⋈ OutDegree };
            Rank := { ITERATE R (
              π page, 0.25 → rank (Pages),
              π page, 0.0375 + 0.85 * Coalesce(passed, 0) → rank (
                Pages ⟕ Pages.page = In.dst ρ In(dst, passed) (
                  γ dst, SUM(rank / out) → passed (ρ From(src, rank) (R) ⋈ Weighted)))
            ) UNTIL rank WITHIN 0.0001 PER page MAX %d ROUNDS };
            """;

    private Relix session;

    @BeforeEach
    void open() {
        session = Relix.builder().build();
    }

    @AfterEach
    void close() {
        session.close();
    }

    private Relation last(String script) {
        return session.script(script).getLast();
    }

    private Relation life(String board, String stop) {
        return last(LIFE + "query { τ x, y (ITERATE B (" + board + ", " + GENERATION + ") " + stop + ") };");
    }

    @Nested
    @DisplayName("ROUNDS n")
    final class Rounds {

        @Test
        @DisplayName("ROUNDS 0 is the base unchanged")
        void zeroIsTheBase() {
            assertThat(life("Blinker", "ROUNDS 0")).rows().hasRowCount(3)
                    .hasRowAt(0, "1", "0").hasRowAt(1, "1", "1").hasRowAt(2, "1", "2");
        }

        @Test
        @DisplayName("each round replaces the last: four generations move a glider one cell diagonally")
        void replacesEachRound() {
            assertThat(life("Glider", "ROUNDS 4")).rows().hasRowCount(5)
                    .hasRowAt(0, "1", "3").hasRowAt(1, "2", "1").hasRowAt(2, "2", "3")
                    .hasRowAt(3, "3", "2").hasRowAt(4, "3", "3");
        }

        @Test
        @DisplayName("a cycle is not an error, since ROUNDS makes no claim to settle")
        void cyclesFreely() {
            assertThat(life("Blinker", "ROUNDS 5")).rows().hasRowCount(3)
                    .hasRowAt(0, "0", "1").hasRowAt(1, "1", "1").hasRowAt(2, "2", "1");
        }

        @Test
        @DisplayName("each round is a set")
        void deduplicatesEachRound() {
            assertThat(last("""
                    T := [
                    | v |
                    |---|
                    | 1 |
                    | 2 |
                    ];
                    query { ITERATE R (T, π 0 → v (R)) ROUNDS 1 };
                    """)).rows().hasRowCount(1).hasRow("0");
        }
    }

    @Nested
    @DisplayName("UNTIL STABLE")
    final class Stable {

        @Test
        @DisplayName("a still life settles")
        void settles() {
            assertThat(life("Block", "UNTIL STABLE MAX 10 ROUNDS")).rows().hasRowCount(4);
        }

        @Test
        @DisplayName("an oscillator is reported as a cycle, with its period")
        void reportsACycle() {
            assertThatThrownBy(() -> life("Blinker", "UNTIL STABLE MAX 50 ROUNDS").toList())
                    .hasMessageContaining("ITERATE 'B' cannot settle")
                    .hasMessageContaining("round 3 is identical to round 1")
                    .hasMessageContaining("period 2");
        }

        @Test
        @DisplayName("something that never repeats is reported as not settling within its cap")
        void reportsTheCap() {
            // A glider moves for ever: no round repeats an earlier one.
            assertThatThrownBy(() -> life("Glider", "UNTIL STABLE MAX 20 ROUNDS").toList())
                    .hasMessageContaining("ITERATE 'B' did not settle within 20 round(s)")
                    .hasMessageContaining("UNTIL STABLE MAX 20 ROUNDS");
        }

        @Test
        @DisplayName("a longer cycle is found once it has been entered")
        void findsALongerPeriod() {
            // v → (v + 1) mod 5: a cycle of period 5 entered at once.
            assertThatThrownBy(() -> last("""
                    T := [
                    | v |
                    |---|
                    | 0 |
                    ];
                    query { ITERATE R (T, π v + 1 - 5 * Int((v + 1) / 5) → v (R)) UNTIL STABLE MAX 100 ROUNDS };
                    """).toList())
                    .hasMessageContaining("period 5");
        }
    }

    @Nested
    @DisplayName("UNTIL … WITHIN … PER")
    final class Converged {

        @Test
        @DisplayName("PageRank converges, and its ranks sum to one within the division's rounding")
        void pageRank() {
            assertThat(last(PAGERANK.formatted(100) + "query { τ page (Rank) };")).rows()
                    .hasRowCount(4)
                    .hasRowAt(0, "A", "0.37249131329")
                    .hasRowAt(1, "B", "0.19580880811")
                    .hasRowAt(2, "C", "0.394199878685")
                    .hasRowAt(3, "D", "0.0375");
            assertThat(last(PAGERANK.formatted(100) + "query { γ SUM(rank) → total (Rank) };"))
                    .rows().hasRow("1.000000000085");
        }

        @Test
        @DisplayName("an unsettled result is refused, not returned")
        void refusesAnUnsettledResult() {
            assertThatThrownBy(() -> last(PAGERANK.formatted(3) + "query Rank;").toList())
                    .hasMessageContaining("ITERATE 'R' did not settle within 3 round(s)")
                    .hasMessageContaining("UNTIL rank WITHIN 0.0001 PER page MAX 3 ROUNDS");
        }

        @Test
        @DisplayName("a selection above it does not change what it computes")
        void aSelectionAboveDoesNotReachIn() {
            // D's rank depends on the whole graph; a filter pushed into the iteration would
            // compute it over D alone.
            assertThat(last(PAGERANK.formatted(100) + "query { σ page = \"D\" (Rank) };"))
                    .rows().hasRowCount(1).hasRow("D", "0.0375");
            assertThat(last(PAGERANK.formatted(100) + "query { σ page = \"C\" (Rank) };"))
                    .rows().hasRow("C", "0.394199878685");
        }

        @Test
        @DisplayName("the tolerance is inclusive")
        void toleranceIsInclusive() {
            // Each round halves the distance to 0: 1, 0.5, 0.25 — a change of exactly 0.25
            // at the third round meets WITHIN 0.25.
            assertThat(last("""
                    T := [
                    | k | v |
                    |---|---|
                    | 1 | 1 |
                    ];
                    query { ITERATE R (T, π k, v / 2 → v (R)) UNTIL v WITHIN 0.25 PER k MAX 10 ROUNDS };
                    """)).rows().hasRow("1", "0.25");
        }

        @Test
        @DisplayName("a key that appears is a change, however close the values")
        void anAppearingKeyIsAChange() {
            assertThat(last("""
                    T := [
                    | k | v |
                    |---|---|
                    | 1 | 0 |
                    ];
                    Extra := [
                    | k | v |
                    |---|---|
                    | 2 | 0 |
                    ];
                    query { τ k (ITERATE R (T, R ∪ Extra) UNTIL v WITHIN 1 PER k MAX 10 ROUNDS) };
                    """)).rows().hasRowCount(2).hasRowAt(0, "1", "0").hasRowAt(1, "2", "0");
        }

        @Test
        @DisplayName("a key that disappears is a change")
        void aDisappearingKeyIsAChange() {
            assertThatThrownBy(() -> last("""
                    T := [
                    | k | v |
                    |---|---|
                    | 1 | 0 |
                    | 2 | 0 |
                    ];
                    query { ITERATE R (T, σ k = 1 (R)) UNTIL v WITHIN 1 PER k MAX 1 ROUNDS };
                    """).toList())
                    .hasMessageContaining("did not settle within 1 round(s)");
        }

        @Test
        @DisplayName("NULL is settled against NULL, and against nothing else")
        void nullsCompareToNulls() {
            String script = """
                    T := [
                    | k | v    |
                    |---|------|
                    | 1 | NULL |
                    ];
                    query { ITERATE R (T, π k, %s → v (R)) UNTIL v WITHIN 1 PER k MAX 1 ROUNDS };
                    """;
            assertThat(last(script.formatted("v"))).rows().hasRowCount(1);
            assertThatThrownBy(() -> last(script.formatted("Coalesce(v, 0)")).toList())
                    .hasMessageContaining("did not settle within 1 round(s)");
        }

        @Test
        @DisplayName("a value that becomes NULL is a change")
        void aValueBecomingNullIsAChange() {
            // The outer join finds no match, so the step replaces 0 with NULL.
            assertThatThrownBy(() -> last("""
                    T := [
                    | k | v |
                    |---|---|
                    | 1 | 0 |
                    ];
                    Other := [
                    | nk | w |
                    |----|---|
                    | 2  | 5 |
                    ];
                    query { ITERATE R (T, π k, w → v (R ⟕ R.k = Other.nk Other))
                            UNTIL v WITHIN 1 PER k MAX 1 ROUNDS };
                    """).toList())
                    .hasMessageContaining("did not settle within 1 round(s)");
        }

        @Test
        @DisplayName("an untyped column holding a string cannot be measured")
        void refusesAStringThatWasThere() {
            // IIf over a number and a string is untyped, so the validator lets it through.
            assertThatThrownBy(() -> last("""
                    T := [
                    | k | v |
                    |---|---|
                    | 1 | 1 |
                    | 2 | 2 |
                    ];
                    query { ITERATE R (π k, IIf(k = 1, v, "x") → v (T), R)
                            UNTIL v WITHIN 1 PER k MAX 3 ROUNDS };
                    """).toList())
                    .hasMessageContaining("UNTIL column 'v' holds a value that is not a number: x");
        }

        @Test
        @DisplayName("nor can one a round turned into a string")
        void refusesAStringARoundProduced() {
            assertThatThrownBy(() -> last("""
                    T := [
                    | k | v |
                    |---|---|
                    | 1 | 1 |
                    ];
                    query { ITERATE R (π k, IIf(k = 0, "x", v) → v (T), π k, IIf(k = 1, "y", v) → v (R))
                            UNTIL v WITHIN 1 PER k MAX 3 ROUNDS };
                    """).toList())
                    .hasMessageContaining("holds a value that is not a number: y");
        }

        @Test
        @DisplayName("a key that does not identify a row in the base is refused")
        void refusesADuplicateKeyInTheBase() {
            assertThatThrownBy(() -> last("""
                    T := [
                    | k | v |
                    |---|---|
                    | 1 | 0 |
                    | 1 | 1 |
                    ];
                    query { ITERATE R (T, R) UNTIL v WITHIN 1 PER k MAX 5 ROUNDS };
                    """).toList())
                    .hasMessageContaining("PER k does not identify a row")
                    .hasMessageContaining("the base has two rows with k = 1");
        }

        @Test
        @DisplayName("a key that stops identifying a row in a later round is refused")
        void refusesADuplicateKeyInARound() {
            assertThatThrownBy(() -> last("""
                    T := [
                    | k | v |
                    |---|---|
                    | 1 | 0 |
                    ];
                    query { ITERATE R (T, R ∪ π k, v + 1 → v (R)) UNTIL v WITHIN 5 PER k MAX 5 ROUNDS };
                    """).toList())
                    .hasMessageContaining("round 1 has two rows with k = 1");
        }

        @Test
        @DisplayName("a schema read from the data cannot be tested for convergence")
        void refusesAnOpenSchema() {
            assertThatThrownBy(() -> last("""
                    T := [
                    | k | v |
                    |---|---|
                    | 1 | 0 |
                    ];
                    query { ITERATE R (T, R) UNTIL v WITHIN 1 PER k MAX 5 ROUNDS };
                    """.replace("query { ITERATE R (T, R)",
                    "source Open from json(\"./missing.json\");\nquery { ITERATE R (Open, R)")).toList())
                    .hasMessageContaining("needs the base's columns to be declared");
        }
    }

    @Nested
    @DisplayName("inside the step, a column is qualified by the bound name")
    final class Qualifying {

        private static final String EDGES = """
                Edges := [
                | src | dst |
                |-----|-----|
                | 1   | 2   |
                | 2   | 3   |
                ];
                """;

        @Test
        @DisplayName("in a FIX step")
        void inAFixStep() {
            assertThat(last(EDGES + """
                    query { τ src, dst (FIX F (Edges,
                        π F.src, E.nxt → dst (F >< F.dst = E.s ρ E(s, nxt) (Edges)))) };
                    """)).rows().hasRowCount(3)
                    .hasRowAt(0, "1", "2").hasRowAt(1, "1", "3").hasRowAt(2, "2", "3");
        }

        @Test
        @DisplayName("in an ITERATE step")
        void inAnIterateStep() {
            assertThat(last(EDGES + """
                    query { ITERATE R (Edges, π R.src, R.dst + 1 → dst (σ R.src = 1 (R))) ROUNDS 1 };
                    """)).rows().hasRowCount(1).hasRow("1", "3");
        }

        @Test
        @DisplayName("and no longer by the base's name, which is not what the step reads")
        void notByTheBaseName() {
            assertThatThrownBy(() -> last(EDGES + """
                    query { FIX F (Edges, π Edges.src, E.nxt → dst (F >< dst = E.s ρ E(s, nxt) (Edges))) };
                    """).toList())
                    .hasMessageContaining("'Edges.src' does not resolve");
        }
    }

    @Nested
    @DisplayName("the debug log")
    final class DebugLog {

        private static final Logger LOG =
                Logger.getLogger("com.darkcollective.relix.processor.exec.RecursionExecutor");

        private final List<String> messages = new ArrayList<>();
        private final Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        private Level previous;

        @BeforeEach
        void listen() {
            previous = LOG.getLevel();
            LOG.setLevel(Level.FINE);
            capture.setLevel(Level.ALL);
            LOG.addHandler(capture);
        }

        @AfterEach
        void stopListening() {
            LOG.removeHandler(capture);
            LOG.setLevel(previous);
        }

        @Test
        @DisplayName("each round of ITERATE, and of FIX, is logged at DEBUG with its size")
        void logsEachRound() {
            life("Blinker", "ROUNDS 2").toList();
            last("""
                    Edges := [
                    | src | dst |
                    |-----|-----|
                    | 1   | 2   |
                    ];
                    query { FIX F (Edges, π src, dst (F ⋈ ρ E(dst, next) (Edges))) };
                    """).toList();
            assertThat(messages)
                    .contains("ITERATE 'B' round 1: input=3", "ITERATE 'B' round 2: input=3")
                    .anySatisfy(m -> assertThat(m).startsWith("FIX 'F' round 1: delta=1"));
        }
    }

    @Nested
    @DisplayName("the session guards")
    final class Guards {

        @Test
        @DisplayName("the round cap bounds ITERATE as it bounds FIX")
        void roundCap() {
            try (Relix capped = Relix.builder().maxFixpointRounds(2).build()) {
                assertThatThrownBy(() -> capped.script(LIFE + "query { ITERATE B (Glider, "
                        + GENERATION + ") ROUNDS 3 };").getLast().toList())
                        .hasMessageContaining("ITERATE 'B' exceeded 2 round(s)")
                        .hasMessageContaining("--max-fixpoint-rounds");
                assertThat(capped.script(LIFE + "query { ITERATE B (Glider, " + GENERATION
                        + ") ROUNDS 2 };").getLast()).rows().hasRowCount(5);
            }
        }

        @Test
        @DisplayName("the row budget counts the rounds it holds")
        void rowBudget() {
            // Five rows fit; the first round holds them and the five it builds, which do not.
            String identity = """
                    T := [
                    | v |
                    |---|
                    | 1 |
                    | 2 |
                    | 3 |
                    | 4 |
                    | 5 |
                    ];
                    query { ITERATE R (T, R) ROUNDS %d };
                    """;
            try (Relix capped = Relix.builder().maxMaterializedRows(8).build()) {
                assertThat(capped.script(identity.formatted(0)).getLast()).rows().hasRowCount(5);
                assertThatThrownBy(() -> capped.script(identity.formatted(1)).getLast().toList())
                        .hasMessageContaining("Iterate held more than 8 rows across the rounds it keeps");
            }
        }
    }
}

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;

/**
 * The promises a session makes that are kept by wiring rather than by structure: the two
 * caps it was built with actually reach execution, and the relations it hands out really
 * are safe to share.
 *
 * <p>Both were broken and neither was visible, for the same reason — the session stored
 * the setting and the terminals never asked for it, and the terminals wrote back into the
 * session and nothing ran two of them at once.
 */
@DisplayName("A session's execution-time guarantees")
final class SessionGuardsTest {

    /** Transitive closure over a 4-node chain: converges in exactly 3 rounds. */
    private static final String CHAIN = """
            Edges := [| src | dst |
                       | 1   | 2   |
                       | 2   | 3   |
                       | 3   | 4   |];
            Reach := { FIX R (
              Edges,
              Edges ∪ π src, dst (ρ X(src, via) (R) ⋈ ρ Y(via, dst) (Edges))
            ) };
            query Reach;
            """;

    @Nested
    @DisplayName("the fixpoint cap reaches execution")
    final class FixpointCap {

        @Test
        @DisplayName("a cap below what the query needs aborts it")
        void capAborts() {
            try (Relix session = Relix.builder().maxFixpointRounds(1).build()) {
                assertThatThrownBy(() -> session.script(CHAIN).getFirst().toList())
                        .hasMessageContaining("exceeded 1");
            }
        }

        @Test
        @DisplayName("a cap above what the query needs leaves it alone")
        void generousCapAllows() {
            try (Relix session = Relix.builder().maxFixpointRounds(50).build()) {
                assertThat(session.script(CHAIN).getFirst()).hasRowCount(6);
            }
        }

        @Test
        @DisplayName("no cap is unlimited, which is the default")
        void uncappedRuns() {
            try (Relix session = Relix.builder().build()) {
                assertThat(session.script(CHAIN).getFirst()).hasRowCount(6);
            }
        }

        @Test
        @DisplayName("the cap applies to a streamed run too, not only a collected one")
        void capAppliesToStream() {
            try (Relix session = Relix.builder().maxFixpointRounds(1).build()) {
                assertThatThrownBy(() -> {
                    try (var rows = session.script(CHAIN).getFirst().stream()) {
                        rows.forEach(r -> { });
                    }
                }).hasMessageContaining("exceeded 1");
            }
        }
    }

    @Nested
    @DisplayName("the blocking-operator row cap reaches execution")
    final class MaterializationCap {

        /** Four rows, a sort above them: one blocking operator holding the lot. */
        private static final String SORTED = """
                Amounts := [| n |
                            | 3 |
                            | 1 |
                            | 4 |
                            | 2 |];
                query { τ n (Amounts) };
                """;

        @Test
        @DisplayName("a cap below what the query buffers aborts it, naming the operator")
        void capAborts() {
            try (Relix session = Relix.builder().maxMaterializedRows(3).build()) {
                assertThatThrownBy(() -> session.script(SORTED).getFirst().toList())
                        .hasMessageContaining("Sort buffered more than 3 rows");
            }
        }

        @Test
        @DisplayName("a cap above what the query buffers leaves it alone")
        void generousCapAllows() {
            try (Relix session = Relix.builder().maxMaterializedRows(50).build()) {
                assertThat(session.script(SORTED).getFirst()).hasRowCount(4);
            }
        }

        @Test
        @DisplayName("no cap is unlimited, which is the default")
        void uncappedRuns() {
            try (Relix session = Relix.builder().build()) {
                assertThat(session.script(SORTED).getFirst()).hasRowCount(4);
            }
        }

        @Test
        @DisplayName("the cap applies to a streamed run too, not only a collected one")
        void capAppliesToStream() {
            try (Relix session = Relix.builder().maxMaterializedRows(3).build()) {
                assertThatThrownBy(() -> {
                    try (var rows = session.script(SORTED).getFirst().stream()) {
                        rows.forEach(r -> { });
                    }
                }).hasMessageContaining("Sort buffered more than 3 rows");
            }
        }

        @Test
        @DisplayName("a cap below 1 is refused at the builder, where the caller can see it")
        void rejectsNonPositive() {
            assertThatThrownBy(() -> Relix.builder().maxMaterializedRows(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxMaterializedRows");
        }
    }

    @Nested
    @DisplayName("relations from one session are safe to share")
    final class Sharing {

        /** How many distinct sources the session observes; the map grows to this. */
        private static final int SOURCES = 24;

        /**
         * Many CSV sources, not one. Two details make the difference between a test that
         * exercises the race and one that cannot: a leaf scan of a <em>file</em> is what
         * the executor reports a measured row count for (an inline table's rows are
         * already in memory and nothing observes them), and the observation is keyed by
         * <em>relation</em> — so a session over one source records one entry and rewrites
         * it forever, which never structurally modifies the map and so never trips.
         * Distinct sources are what make it grow while other runs are reading it.
         */
        private Relix sourcesIn(Path directory) throws IOException {
            Relix session = Relix.builder().baseDirectory(directory).build();
            StringBuilder declarations = new StringBuilder();
            for (int i = 0; i < SOURCES; i++) {
                Files.writeString(directory.resolve("t" + i + ".csv"), """
                        id,amount
                        1,10
                        2,20
                        3,30
                        """);
                declarations.append("source T").append(i)
                        .append(" from csv(\"t").append(i).append(".csv\") { header: true,")
                        .append(" schema: { id: NUMBER, amount: NUMBER } };\n");
            }
            session.define(declarations.toString());
            return session;
        }

        /**
         * Every terminal folds what it measured back into the session, so concurrent
         * execution writes to session state without the caller touching the session at
         * all. With an unsynchronised map this races: {@code statisticsWithObservations}
         * iterates it at the start of every execution while another run is recording into
         * it, which is a {@code ConcurrentModificationException} at best.
         */
        @RepeatedTest(5)
        @DisplayName("draining shared relations on many threads neither throws nor loses rows")
        void concurrentTerminalsAreSafe(@TempDir Path directory) throws Exception {
            try (Relix session = sourcesIn(directory)) {
                List<Relation> shared = new ArrayList<>();
                for (int i = 0; i < SOURCES; i++) {
                    shared.add(session.relation("σ amount > 5 (T" + i + ")"));
                }

                ExecutorService pool = Executors.newFixedThreadPool(16);
                CountDownLatch go = new CountDownLatch(1);
                try {
                    List<Future<Integer>> futures = new ArrayList<>();
                    for (int round = 0; round < 20; round++) {
                        for (Relation relation : shared) {
                            futures.add(pool.submit(() -> {
                                // Start together, so the runs overlap rather than queueing.
                                go.await();
                                return relation.toList().size();
                            }));
                        }
                    }
                    go.countDown();
                    int total = 0;
                    for (Future<Integer> f : futures) {
                        total += f.get();   // rethrows anything a worker threw
                    }
                    assertThat(total).isEqualTo(20 * SOURCES * 3);
                } finally {
                    pool.shutdownNow();
                }
            }
        }

        @Test
        @DisplayName("a relation stays usable after another thread has run it")
        void relationIsReusable(@TempDir Path directory) throws Exception {
            try (Relix session = sourcesIn(directory)) {
                Relation r = session.relation("T0");
                Thread other = new Thread(() -> r.toList());
                other.start();
                other.join();
                assertThat(r).hasRowCount(3);
            }
        }
    }
}

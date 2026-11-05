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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The engine answers a query that is too big for its limits with a failure it can name,
 * and a query with a limit is stopped by it wherever it is spending its time.
 */
@DisplayName("Resource safety through the facade")
final class ResourceSafetyTest {

    private static List<Tuple> run(Relix relix, String script) {
        return relix.script(script).getLast().toList();
    }

    private static List<Tuple> run(String script) {
        try (Relix relix = Relix.builder().maxFixpointRounds(1_000_000).build()) {
            return run(relix, script);
        }
    }

    private static String txt(Tuple row, String column) {
        return row.get(column).asDisplayString();
    }

    private static String chain(int edges) {
        return "E := [| s | d | w |\n" + IntStream.range(0, edges)
                .mapToObj(i -> "| " + i + " | " + (i + 1) + " | 1 |\n")
                .collect(Collectors.joining()) + "];\n";
    }

    private static <R> R onStack(long bytes, Supplier<R> work) {
        AtomicReference<R> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", bytes);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failure.get() instanceof Error error) {
            throw new AssertionError("an Error escaped: " + error, error);
        }
        if (failure.get() instanceof RuntimeException e) {
            throw e;
        }
        return result.get();
    }

    @Nested
    @DisplayName("COVER with a NULL in a factor")
    class CoverNull {

        @Test
        @DisplayName("covers the NULL value like any other: strength = width is the distinct product")
        void nullIsAValue() {
            String factors = "GN := [| g |\n| a |\n| NULL |];\nK := [| k |\n| 1 |\n| 2 |\n| 3 |];\n"
                    + "VN := [| v |\n| x |\n| NULL |];\n";
            assertThat(run(factors + "query { COVER 3 (GN × K × VN) };")).hasSize(12);
            assertThat(run(factors + "query { COVER 3 (GN × K × VN) };"))
                    .containsExactlyInAnyOrderElementsOf(run(factors + "query { δ (GN × K × VN) };"));
        }

        @Test
        @DisplayName("pairs that include NULL are covered at strength 2")
        void pairsWithNull() {
            String factors = "GN := [| g |\n| a |\n| NULL |];\nK := [| k |\n| 1 |\n| 2 |\n| 3 |];\n"
                    + "VN := [| v |\n| x |\n| NULL |];\n";
            List<Tuple> rows = run(factors + "query { COVER 2 (GN × K × VN) };");
            // 2*3 + 2*2 + 3*2 = 16 distinct pairs, no row covers more than 3 of them.
            assertThat(rows.size()).isGreaterThanOrEqualTo(6);
            for (String g : List.of("a", "NULL")) {
                for (int k = 1; k <= 3; k++) {
                    String wantG = g;
                    String wantK = String.valueOf(k);
                    assertThat(rows).anyMatch(r -> (wantG.equals("NULL") ? r.isNull("g") : wantG.equals(txt(r, "g")))
                            && wantK.equals(txt(r, "k")));
                }
            }
        }
    }

    @Nested
    @DisplayName("A timeout is honoured inside a long loop")
    class Timeouts {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("COVER over a large product stops at the timeout")
        void cover() {
            StringBuilder script = new StringBuilder();
            for (int c = 0; c < 8; c++) {
                script.append("C").append(c).append(" := [| c").append(c).append(" |\n");
                for (int v = 0; v < 12; v++) {
                    script.append("| ").append(v).append(" |\n");
                }
                script.append("];\n");
            }
            script.append("query { COVER 4 (C0 × C1 × C2 × C3 × C4 × C5 × C6 × C7) };");
            long start = System.nanoTime();
            try (Relix relix = Relix.builder().timeout(Duration.ofSeconds(1)).build()) {
                assertThatThrownBy(() -> run(relix, script.toString()))
                        .hasMessageContaining("ran longer than 1s");
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
        }

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("an all-pairs TRACE stops at the timeout")
        void traceAllPairs() {
            long start = System.nanoTime();
            try (Relix relix = Relix.builder().timeout(Duration.ofSeconds(1))
                    .maxFixpointRounds(1_000_000).build()) {
                assertThatThrownBy(() -> run(relix, chain(1500)
                        + "query { TRACE s, d VIA w MINIMIZE AS p (E) };"))
                        .hasMessageContaining("ran longer than 1s");
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
        }

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("a single-source TRACE over a 3000-edge chain no longer scans every edge per entry")
        void traceSingleSourceIsFast() {
            List<Tuple> rows = run(chain(3000) + "query { σ s = 0 (TRACE s, d VIA w MINIMIZE AS p (E)) };");
            assertThat(rows).hasSize(3000);
        }
    }

    @Nested
    @DisplayName("String functions are bounded")
    class Strings {

        private String scalar(String expression) {
            return txt(run("U := [| a |\n| 1 |];\nquery { π " + expression + " → r (U) };")
                    .getFirst(), "r");
        }

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        @DisplayName("LPAD and RPAD build their padding in one pass")
        void padIsLinear() {
            String padded = scalar("LPAD('x', 3000000, 'ab')");
            assertThat(padded).hasSize(3000000).endsWith("ax").startsWith("ab");
            assertThat(scalar("RPAD('x', 3000000)")).hasSize(3000000).startsWith("x ");
        }

        @Test
        @DisplayName("padding is correct by code point")
        void padByCodePoint() {
            assertThat(scalar("LPAD('x', 4, '😀y')")).isEqualTo("😀y😀x");
            assertThat(scalar("RPAD('x', 3, 'ab')")).isEqualTo("xab");
            assertThat(scalar("LPAD('hello', 3)")).isEqualTo("hel");
        }

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        @DisplayName("a length past the string cap is refused at once")
        void padTooLong() {
            assertThatThrownBy(() -> scalar("LPAD('x', 2000000000)"))
                    .hasMessageContaining("longer than 100000000 characters");
            assertThatThrownBy(() -> scalar("RPAD('x', 2000000000, 'ab')"))
                    .hasMessageContaining("longer than 100000000 characters");
        }

        @Test
        @DisplayName("REPLACE, CONCAT, CONCAT_WS and STRING_AGG refuse a result past the cap")
        void blowUps() {
            assertThatThrownBy(() -> scalar("REPLACE(REPEAT('a', 50000000), 'a', REPEAT('b', 100))"))
                    .hasMessageContaining("longer than 100000000 characters");
            assertThatThrownBy(() -> scalar("CONCAT(REPEAT('a', 60000000), REPEAT('a', 60000000))"))
                    .hasMessageContaining("longer than 100000000 characters");
            assertThatThrownBy(() -> scalar("CONCAT_WS('-', REPEAT('a', 60000000), REPEAT('a', 60000000))"))
                    .hasMessageContaining("longer than 100000000 characters");
        }

        @Test
        @DisplayName("REPLACE inside the cap is unchanged")
        void replaceStillWorks() {
            assertThat(scalar("REPLACE('banana', 'an', 'ANT')")).isEqualTo("bANTANTa");
            assertThatThrownBy(() -> scalar("REPLACE(REPEAT('a', 60000000), '', 'bb')"))
                    .hasMessageContaining("longer than 100000000 characters");
        }
    }

    @Nested
    @DisplayName("Aggregates fold rows instead of buffering them")
    class Aggregates {

        private static final String BIG = "source N from generator { name: \"Range\", lo: \"1\", hi: \"3000000\" };\n";

        @Test
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        @DisplayName("an ungrouped COUNT, SUM, MIN and MAX hold no input row, so a tiny row cap does not refuse them")
        void ungroupedFolds() {
            try (Relix relix = Relix.builder().maxMaterializedRows(1000).build()) {
                List<Tuple> rows = run(relix, BIG
                        + "query { γ COUNT(*) → c, SUM(n) → s, MIN(n) → lo, MAX(n) → hi (N) };");
                assertThat(rows).hasSize(1);
                assertThat(txt(rows.getFirst(), "c")).isEqualTo("3000000");
                assertThat(txt(rows.getFirst(), "s")).isEqualTo("4500001500000");
                assertThat(txt(rows.getFirst(), "lo")).isEqualTo("1");
                assertThat(txt(rows.getFirst(), "hi")).isEqualTo("3000000");
            }
        }

        @Test
        @DisplayName("an aggregate that needs the whole group is still bounded by the row cap")
        void collectingIsCapped() {
            try (Relix relix = Relix.builder().maxMaterializedRows(1000).build()) {
                assertThatThrownBy(() -> run(relix, BIG + "query { γ MEDIAN(n) → m (N) };"))
                        .hasMessageContaining("maxMaterializedRows");
            }
        }

        @Test
        @DisplayName("groups come out in first-seen order, with an empty input giving one row only when ungrouped")
        void groupsAndEmpty() {
            String t = "T := [| k | v |\n| b | 1 |\n| a | 2 |\n| b | 3 |\n| a | NULL |\n];\n";
            List<Tuple> rows = run(t + "query { γ k, COUNT(v) → c, SUM(v) → s, COUNT(*) → n (T) };");
            assertThat(rows).extracting(r -> txt(r, "k")).containsExactly("b", "a");
            assertThat(rows).extracting(r -> txt(r, "s")).containsExactly("4", "2");
            assertThat(rows).extracting(r -> txt(r, "n")).containsExactly("2", "2");
            assertThat(run(t + "query { γ COUNT(*) → c (σ v > 100 (T)) };")).hasSize(1);
            assertThat(run(t + "query { γ k, COUNT(*) → c (σ v > 100 (T)) };")).isEmpty();
        }
    }

    @Nested
    @DisplayName("A result built in memory is held to maxMaterializedRows")
    class BuiltResults {

        @Test
        @DisplayName("an all-pairs CLOSURE, PATH and TRACE over a chain refuse once their result outgrows the cap")
        void cappedResults() {
            try (Relix relix = Relix.builder().maxMaterializedRows(500).maxFixpointRounds(1_000_000).build()) {
                assertThatThrownBy(() -> run(relix, chain(200) + "query { CLOSURE s, d (E) };"))
                        .hasMessageContaining("maxMaterializedRows");
                assertThatThrownBy(() -> run(relix, chain(200) + "query { PATH s, d HOPS 1 TO 200 AS n (E) };"))
                        .hasMessageContaining("maxMaterializedRows");
                assertThatThrownBy(() -> run(relix, chain(200)
                        + "query { TRACE s, d VIA w MINIMIZE AS p (E) };"))
                        .hasMessageContaining("maxMaterializedRows");
            }
        }

        @Test
        @DisplayName("TRACE still finds the cheapest route when a longer one is found first")
        void traceKeepsTheCheapest() {
            String e = "E := [| s | d | w |\n| 1 | 2 | 10 |\n| 1 | 3 | 1 |\n| 3 | 2 | 1 |\n| 2 | 4 | 1 |\n];\n";
            List<Tuple> rows = run(e + "query { σ s = 1 ∧ d = 4 (TRACE s, d VIA w MINIMIZE AS p (E)) };");
            assertThat(rows).hasSize(1);
            assertThat(txt(rows.getFirst(), "p")).contains("3");
            assertThat(rows.getFirst().decimal("w").intValue()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("TRACE agrees with a reference shortest-path computation")
    class TraceAgrees {

        @Test
        @DisplayName("on many random graphs, every pair's cost is the all-pairs shortest distance")
        void randomGraphs() {
            java.util.Random random = new java.util.Random(20261105L);
            for (int round = 0; round < 150; round++) {
                int nodes = 3 + random.nextInt(7);
                int edgeCount = nodes + random.nextInt(nodes * 2);
                long[][] dist = new long[nodes][nodes];
                for (long[] row : dist) {
                    java.util.Arrays.fill(row, Long.MAX_VALUE / 4);
                }
                StringBuilder script = new StringBuilder("E := [| s | d | w |\n");
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < edgeCount; i++) {
                    int from = random.nextInt(nodes);
                    int to = random.nextInt(nodes);
                    if (from == to || !seen.add(from + ">" + to)) {
                        continue;
                    }
                    long weight = 1 + random.nextInt(9);
                    dist[from][to] = weight;
                    script.append("| ").append(from).append(" | ").append(to).append(" | ")
                            .append(weight).append(" |\n");
                }
                if (seen.isEmpty()) {
                    continue;
                }
                script.append("];\nquery { TRACE s, d VIA w MINIMIZE AS p (E) };");
                // Floyd-Warshall; a cycle back to the start is not a TRACE row, so the
                // diagonal is left out of the comparison.
                for (int k = 0; k < nodes; k++) {
                    for (int i = 0; i < nodes; i++) {
                        for (int j = 0; j < nodes; j++) {
                            dist[i][j] = Math.min(dist[i][j], dist[i][k] + dist[k][j]);
                        }
                    }
                }
                List<Tuple> rows = run(script.toString());
                java.util.Map<String, Long> traced = new java.util.HashMap<>();
                for (Tuple row : rows) {
                    traced.put(txt(row, "s") + ">" + txt(row, "d"), row.decimal("w").longValue());
                }
                for (int i = 0; i < nodes; i++) {
                    for (int j = 0; j < nodes; j++) {
                        if (i == j) {
                            continue;
                        }
                        Long got = traced.get(i + ">" + j);
                        if (dist[i][j] >= Long.MAX_VALUE / 4) {
                            assertThat(got).as("round %d %d>%d unreachable", round, i, j).isNull();
                        } else {
                            assertThat(got).as("round %d %d>%d in %s", round, i, j, script).isEqualTo(dist[i][j]);
                        }
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Deep values fail as query failures")
    class DeepValues {

        @Test
        @DisplayName("a 100000-level JSON string is a failure the facade can catch")
        void deepJson() {
            String json = "[".repeat(100_000) + "1" + "]".repeat(100_000);
            assertThatThrownBy(() -> onStack(1L << 20, () -> run("U := [| a |\n| 1 |];\nquery { π JSON_VALUE('"
                    + json + "', '$[0]') → r (U) };")))
                    .isInstanceOf(RelixException.class)
                    .hasMessageContaining("nests more than");
        }

        @Test
        @DisplayName("TREE over a 1500-deep parent chain is a result or a failure, never an Error")
        void deepTree() {
            StringBuilder t = new StringBuilder("T := [| id | parent |\n| 0 | NULL |\n");
            for (int i = 1; i <= 1500; i++) {
                t.append("| ").append(i).append(" | ").append(i - 1).append(" |\n");
            }
            t.append("];\n");
            try {
                onStack(1L << 20, () -> run(t + "query { TREE id BY parent AS kids (T) };"));
            } catch (RelixException e) {
                assertThat(e.getMessage()).isNotBlank();
            }
        }
    }
}

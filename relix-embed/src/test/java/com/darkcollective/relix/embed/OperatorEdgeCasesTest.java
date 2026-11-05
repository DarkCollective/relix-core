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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Operator edge cases found by the runtime audit")
final class OperatorEdgeCasesTest {

    private static List<String> rows(Relation relation) {
        return relation.toList().stream()
                .map(t -> t.schema().columns().stream().map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining(" ")))
                .toList();
    }

    /** The optimised and as-written runs of one query give the same rows. */
    private static List<String> both(Relix relix, String query) {
        List<String> optimised = rows(relix.relation(query));
        assertThat(rows(relix.relation(query).asWritten())).isEqualTo(optimised);
        return optimised;
    }

    @Test
    @DisplayName("SESSIONIZE over a DATE column splits on the gap between days")
    void sessionizeDates() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    D := [| d          | n |
                    | 2026-01-01 | 1 |
                    | 2026-01-02 | 2 |
                    | 2026-01-03 | 3 |
                    | 2026-01-06 | 4 |
                    | 2026-01-07 | 5 |];
                    """);
            assertThat(both(relix, "τ n (SESSIONIZE d GAP DURATION 'P1D' AS s (D))")).containsExactly(
                    "2026-01-01 1 1", "2026-01-02 2 1", "2026-01-03 3 1", "2026-01-06 4 2", "2026-01-07 5 2");
            assertThat(both(relix, "τ n (SESSIONIZE d GAP DURATION 'PT24H' AS s (D))")).hasSize(5);
        }
    }

    @Test
    @DisplayName("a negative SESSIONIZE gap or ASOF tolerance is an analysis error")
    void negativeBounds() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    E0 := [| u | ts                   | n |
                    | a | 2026-01-01T00:00:00Z | 1 |];
                    F0 := [| v | at                   |
                    | b | 2026-01-01T00:00:00Z |];
                    E := { π u, CAST(ts AS TIMESTAMP) → ts, n (E0) };
                    F := { π v, CAST(at AS TIMESTAMP) → at (F0) };
                    """);
            assertThatThrownBy(() -> relix.relation("SESSIONIZE ts GAP DURATION 'PT-1S' AS s (E)").toList())
                    .hasMessageContaining("GAP threshold must not be negative");
            assertThatThrownBy(() -> relix.relation("E ASOF E.ts >= F.at WITHIN DURATION 'PT-2M' F").toList())
                    .hasMessageContaining("WITHIN tolerance must not be negative");
            assertThat(rows(relix.relation("E ASOF E.ts >= F.at WITHIN DURATION 'PT0S' F"))).hasSize(1);
        }
    }

    @Test
    @DisplayName("OPTIMIZE leaves out a row whose objective or constraint value is NULL")
    void optimizeNulls() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    N := [| id | v    | w    |
                    | a  | 5    | 1    |
                    | b  | NULL | 1    |
                    | c  | 7    | NULL |
                    | d  | 3    | 2    |];
                    """);
            assertThat(both(relix, "τ id (OPTIMIZE MAXIMIZE SUM(v) SUBJECT TO SUM(w) <= 3 (N))"))
                    .containsExactly("a 5 1", "d 3 2");
            assertThat(both(relix, "τ id (OPTIMIZE ALLOCATE (0, 1) MAXIMIZE SUM(v) SUBJECT TO SUM(w) <= 3 -> x (N))"))
                    .extracting(r -> r.substring(0, 1)).containsExactly("a", "b", "c", "d");
        }
    }

    /**
     * Run in a JVM of its own, without the solver on the class path: solvers are discovered
     * once per JVM, so the test JVM's answer cannot be taken back.
     */
    public static final class NoSolverMain {
        public static void main(String[] args) {
            try (Relix relix = Relix.open()) {
                relix.define("S := [| id | v | w |\n| a | 1 | 1 |];");
                Relation relation = relix.relation("OPTIMIZE MAXIMIZE SUM(v) SUBJECT TO SUM(w) <= 3 (S)");
                for (Runnable action : List.<Runnable>of(relation::explain, relation::explainJson, relation::toList)) {
                    try {
                        action.run();
                        System.out.println("NO FAILURE");
                    } catch (RuntimeException e) {
                        System.out.println(e.getClass().getName() + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("explain() without a solver gives the public failure type and message")
    void explainWithoutSolver() throws Exception {
        String classPath = java.util.Arrays.stream(System.getProperty("java.class.path")
                        .split(java.io.File.pathSeparator))
                .filter(entry -> !entry.contains("relix-solver-ojalgo"))
                .collect(Collectors.joining(java.io.File.pathSeparator));
        Process process = new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classPath, NoSolverMain.class.getName())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        process.waitFor();
        assertThat(output.lines().filter(l -> l.startsWith("com.") || l.startsWith("NO ")).toList())
                .hasSize(3)
                .allSatisfy(line -> assertThat(line)
                        .startsWith("com.darkcollective.relix.embed.QueryExecutionException: ")
                        .contains("needs a mathematical-programming solver and none is installed")
                        .doesNotContain("NoSolverInstalledException"));
    }

    @Test
    @DisplayName("DOWNSAMPLE leaves out a row whose timestamp is NULL")
    void downsampleNullTimestamp() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    T0 := [| id | ts                   |
                    | 1  | 2026-01-01T00:10:00Z |
                    | 2  | 2026-01-01T00:20:00Z |
                    | 3  | NULL                 |];
                    T := { π id, CAST(ts AS TIMESTAMP) → ts (T0) };
                    """);
            assertThat(both(relix, "DOWNSAMPLE ts BY '1d' USING COUNT (T)")).hasSize(1)
                    .allSatisfy(r -> assertThat(r).endsWith(" 2"));
        }
    }
}

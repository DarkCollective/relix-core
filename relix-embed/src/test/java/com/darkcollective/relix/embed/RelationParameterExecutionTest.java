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
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Table-valued functions with relation parameters, run: the rule written once and applied
 * to different relations, the argument narrowed to the declared heading, and a chain of
 * calls in which each argument is itself a call.
 */
@DisplayName("Table-valued functions — relation parameters, executed")
final class RelationParameterExecutionTest {

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
            def step(G: RELATION(x: NUMBER, y: NUMBER)) : RELATION := {
              π x, y (σ n = 3 (ρ C(x, y, n) (γ nx, ny, COUNT(*) → n (π x + dx → nx, y + dy → ny (G × Offsets)))))
              ∪ π x, y (σ n = 2 (ρ C(x, y, n) (γ nx, ny, COUNT(*) → n (π x + dx → nx, y + dy → ny (G × Offsets)))) ⋈ G)
            };
            Gen1 := { step(Blinker) };
            Gen2 := { step(Gen1) };
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

    @Test
    @DisplayName("one rule, applied to different relations")
    void oneRuleManyRelations() {
        assertThat(last(LIFE + "query { τ x, y (step(Blinker)) };")).rows().hasRowCount(3)
                .hasRowAt(0, "0", "1").hasRowAt(1, "1", "1").hasRowAt(2, "2", "1");
        assertThat(last(LIFE + "query { step(Block) };")).rows().hasRowCount(4);
    }

    @Test
    @DisplayName("a chain of calls, each argument a view over the last")
    void aChain() {
        // Two generations bring a blinker back upright.
        assertThat(last(LIFE + "query { τ x, y (Gen2) };")).rows().hasRowCount(3)
                .hasRowAt(0, "1", "0").hasRowAt(1, "1", "1").hasRowAt(2, "1", "2");
    }

    @Test
    @DisplayName("the body sees the argument narrowed to the declared heading")
    void narrowsTheArgument() {
        // Edges carries a w the heading does not declare, and Weights has a w too. Were it
        // let through, E ⋈ Weights would join on w as well as dst, and match nothing.
        assertThat(last("""
                Edges := [
                | src | dst | w |
                |-----|-----|---|
                | 1   | 2   | 5 |
                ];
                Weights := [
                | dst | w | label |
                |-----|---|-------|
                | 2   | 9 | nine  |
                ];
                def tag(E: RELATION(src, dst)) : RELATION := { E ⋈ Weights };
                query { tag(Edges) };
                """)).rows().hasRowCount(1).hasRow("1", "2", "9", "nine");
    }

    @Test
    @DisplayName("a column is qualified by the parameter's name")
    void qualifiedByTheParameter() {
        assertThat(last("""
                Edges := [
                | src | dst |
                |-----|-----|
                | 1   | 2   |
                | 3   | 4   |
                ];
                def far(E: RELATION(src, dst)) : RELATION := { π E.dst (σ E.src > 1 (E)) };
                query { far(Edges) };
                """)).rows().hasRowCount(1).hasRow("4");
    }

    @Test
    @DisplayName("a parameter passed on reaches the inner call as the argument")
    void passedOn() {
        assertThat(last("""
                Edges := [
                | src | dst |
                |-----|-----|
                | 1   | 2   |
                | 2   | 3   |
                ];
                def srcs(E: RELATION(src)) : RELATION := { π src (E) };
                def after(E: RELATION(src, dst), k: NUMBER) : RELATION := { σ src > k (srcs(E)) };
                query { after(Edges, 1) };
                """)).rows().hasRowCount(1).hasRow("2");
    }

    @Test
    @DisplayName("an ITERATE step passes its relation to a function, evaluated every round")
    void iterateStepPassesItsRelation() {
        // Were step(B) taken as round-invariant and computed once, round 2 would repeat
        // round 1 and the blinker would stay on its side.
        assertThat(last(LIFE + "query { τ x, y (ITERATE B (Blinker, step(B)) ROUNDS 2) };"))
                .rows().hasRowCount(3)
                .hasRowAt(0, "1", "0").hasRowAt(1, "1", "1").hasRowAt(2, "1", "2");
        assertThatThrownBy(() -> last(LIFE
                + "query { ITERATE B (Blinker, step(B)) UNTIL STABLE MAX 10 ROUNDS };").toList())
                .hasMessageContaining("period 2");
    }

    @Test
    @DisplayName("30P5H2V0 flies two cells in five generations, the rule written once")
    void theSpaceship() {
        assertThat(last("""
                Ship := [
                | y  | row           |
                |----|---------------|
                | 0  | ....O         |
                | 1  | ...OOO        |
                | 2  | ..OO.OO       |
                | 4  | .O.O.O.O..O   |
                | 5  | OO...O...OOO  |
                | 6  | OO...O......O |
                | 7  | ..........O.O |
                | 8  | ........O.O   |
                | 9  | .........O..O |
                | 10 | ............O |
                ];
                source Columns from generator { name: "Range", lo: "1", hi: "13" };
                Around := [
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
                Gen0 := { π n - 1 → x, y (σ Mid(row, n, 1) = "O" (Ship × Columns)) };
                def neighbours(G: RELATION(x: NUMBER, y: NUMBER)) : RELATION := {
                  γ x, y, COUNT(*) → n (π x + dx → x, y + dy → y (G × Around))
                };
                def generation(G: RELATION(x: NUMBER, y: NUMBER)) : RELATION := {
                  π x, y (σ n = 3 (neighbours(G))) ∪ π x, y (σ n = 2 (neighbours(G)) ⋈ G)
                };
                query { π x, y - 2 → y (Gen0) ∆ ITERATE G (Gen0, generation(G)) ROUNDS 5 };
                """)).rows().isEmpty();
    }

    @Test
    @DisplayName("a FIX step may not pass its relation to a function")
    void fixMayNotPassItsRelation() {
        assertThatThrownBy(() -> last("""
                Edges := [
                | src | dst |
                |-----|-----|
                | 1   | 2   |
                ];
                def srcs(E: RELATION(src, dst)) : RELATION := { E };
                query { FIX F (Edges, srcs(F)) };
                """).toList())
                .hasMessageContaining("Recursive relation 'F' is passed to table-valued function 'srcs'");
    }

    @Test
    @DisplayName("a chain through a function that passes its parameter on")
    void aChainThroughAForwardingFunction() {
        // generation hands G on to neighbours; the chain's arguments are still the caller's.
        assertThat(last(LIFE + """
                def neighbours(G: RELATION(x: NUMBER, y: NUMBER)) : RELATION := {
                  γ x, y, COUNT(*) → n (π x + dx → x, y + dy → y (G × Offsets))
                };
                def generation(G: RELATION(x: NUMBER, y: NUMBER)) : RELATION := {
                  π x, y (σ n = 3 (neighbours(G))) ∪ π x, y (σ n = 2 (neighbours(G)) ⋈ G)
                };
                One := { generation(Blinker) };
                Two := { generation(One) };
                Three := { generation(Two) };
                query { τ x, y (Three) };
                """)).rows().hasRowCount(3)
                .hasRowAt(0, "0", "1").hasRowAt(1, "1", "1").hasRowAt(2, "2", "1");
    }

    @Test
    @DisplayName("a function that calls itself in its body is still refused")
    void recursionIsStillRefused() {
        assertThatThrownBy(() -> last("""
                T := [
                | x |
                |---|
                | 1 |
                ];
                def loop(E: RELATION(x)) : RELATION := { loop(E) };
                query { loop(T) };
                """).toList())
                .hasMessageContaining("Recursive table-valued function 'loop'");
    }
}

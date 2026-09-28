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

import com.darkcollective.relix.ast.Expr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.projected;
import static com.darkcollective.relix.ast.AstBuilders.rounds;
import static com.darkcollective.relix.ast.AstBuilders.untilStable;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The binder combinators, {@link Relation#fix} and {@link Relation#iterate}, run end to
 * end: the step is a function of a relation standing for the bound name, so a Java
 * program can write the step a script writes inside {@code FIX R (base, step)}.
 */
@DisplayName("Relation.fix / Relation.iterate — a binder's step, built in Java")
final class BinderCombinatorTest {

    private Relix session;
    private Relation edges;

    @BeforeEach
    void open() {
        session = Relix.builder().build();
        session.define("""
                Edges := [
                | src | dst |
                |-----|-----|
                | 1   | 2   |
                | 2   | 3   |
                ];
                """);
        edges = session.relation("Edges");
    }

    @AfterEach
    void close() {
        session.close();
    }

    @Test
    @DisplayName("fix computes a closure, reading the relation derived so far by its name")
    void fixComputesAClosure() {
        // The example on Relation#fix, run.
        Relation reach = edges.fix("Reach", r ->
                r.join(edges.rename("E", List.of("s", "nxt")),
                                Expr.eq(attr("Reach.dst"), attr("E.s")))
                        .project(List.of(projected(attr("Reach.src")),
                                projected(attr("E.nxt"), "dst"))));
        assertThat(reach).rows().hasRowCount(3)
                .hasRow("1", "2").hasRow("2", "3").hasRow("1", "3");
    }

    @Test
    @DisplayName("iterate replaces each round with the step's output")
    void iterateReplacesEachRound() {
        Relation kept = edges.iterate("R",
                r -> r.select(Expr.lt(attr("R.src"), Expr.num(2))), rounds(1));
        assertThat(kept).rows().hasRowCount(1).hasRow("1", "2");
    }

    @Test
    @DisplayName("iterate stops as asked")
    void iterateStops() {
        Relation settled = edges.iterate("R",
                r -> r.select(Expr.lt(attr("src"), Expr.num(2))), untilStable(5));
        assertThat(settled).rows().hasRowCount(1);
    }

    @Test
    @DisplayName("the step's relation cannot be read outside the step")
    void theHandleStaysInsideItsStep() {
        AtomicReference<Relation> escaped = new AtomicReference<>();
        edges.fix("R", r -> {
            escaped.set(r);
            return r;
        });
        assertThatThrownBy(() -> escaped.get().toList())
                .hasMessageContaining("'R' is the relation a FIX or ITERATE step reads");
    }

    @Test
    @DisplayName("a step from another session is refused")
    void aStepFromAnotherSessionIsRefused() {
        try (Relix other = Relix.builder().build()) {
            other.define("""
                    Others := [
                    | src | dst |
                    |-----|-----|
                    | 9   | 9   |
                    ];
                    """);
            Relation foreign = other.relation("Others");
            assertThatThrownBy(() -> edges.iterate("R", r -> foreign, rounds(1)))
                    .hasMessageContaining("two different sessions");
        }
    }
}

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

/**
 * {@code JOIN … USING (columns)} answers as PostgreSQL's does in all four kinds: the
 * named columns once and first, NULL keys matching nothing, and a full join's column
 * taken from whichever side is present.
 */
@DisplayName("JOIN … USING, evaluated")
final class UsingJoinTest {

    private static final String DATA = """
            A := [| id | k    | x  |
            | 1  | 1    | a1 |
            | 2  | 2    | a2 |
            | 3  | NULL | a3 |];
            B := [| id | k    | x   |
            | 10 | 1    | b1  |
            | 11 | 1    | b1b |
            | 12 | 3    | b3  |
            | 13 | NULL | b4  |];
            """;

    private static List<String> rows(Relix relix, String query) {
        return relix.relation(query).toList().stream()
                .map(t -> t.schema().columns().stream()
                        .map(c -> t.get(c.name()).asDisplayString())
                        .collect(Collectors.joining(" ")))
                .sorted()
                .toList();
    }

    private static List<String> columns(Relix relix, String query) {
        return relix.relation(query).schema().columns().stream().map(c -> c.name()).toList();
    }

    @Test
    @DisplayName("the named column comes once and first; the other shared columns stay twice")
    void heading() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThat(columns(relix, "A JOIN B USING (k)")).containsExactly("k", "id", "x", "id_r", "x_r");
            assertThat(columns(relix, "A FJOIN B USING (k, id)")).containsExactly("k", "id", "x", "x_r");
        }
    }

    @Test
    @DisplayName("inner: matched rows only, and a NULL key matches nothing")
    void inner() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThat(rows(relix, "A JOIN B USING (k)")).containsExactly(
                    "1 1 a1 10 b1", "1 1 a1 11 b1b");
        }
    }

    @Test
    @DisplayName("left: every left row, its key its own")
    void left() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThat(rows(relix, "A LJOIN B USING (k)")).containsExactly(
                    "1 1 a1 10 b1", "1 1 a1 11 b1b", "2 2 a2 NULL NULL", "NULL 3 a3 NULL NULL");
        }
    }

    @Test
    @DisplayName("right: every right row, the key taken from the right")
    void right() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThat(rows(relix, "A ⟖ USING (k) B")).containsExactly(
                    "1 1 a1 10 b1", "1 1 a1 11 b1b", "3 NULL NULL 12 b3", "NULL NULL NULL 13 b4");
        }
    }

    @Test
    @DisplayName("full: every row of both, the key from whichever side is present")
    void full() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThat(rows(relix, "A |><| B USING (k)")).containsExactly(
                    "1 1 a1 10 b1", "1 1 a1 11 b1b", "2 2 a2 NULL NULL", "3 NULL NULL 12 b3",
                    "NULL 3 a3 NULL NULL", "NULL NULL NULL 13 b4");
        }
    }

    @Test
    @DisplayName("an expression input needs no name, and a column above reads the joined heading")
    void expressionInputs() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            assertThat(rows(relix, "π k, x_r (σ id > 1 (A) LJOIN σ id < 13 (B) USING (k))")).containsExactly(
                    "2 NULL", "NULL NULL");
            assertThat(rows(relix, "π k (A JOIN B USING (k, x))")).isEmpty();
        }
    }

    @Test
    @DisplayName("a column of no settled type on either side is not refused for its type")
    void untypedColumn() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA + """
                    N := [| k    | y |
                    | NULL | 1 |];
                    """);
            EmbedAssertions.assertThat(relix.relation("A JOIN N USING (k)")).isEmpty();
            EmbedAssertions.assertThat(relix.relation("N JOIN A USING (k)")).isEmpty();
        }
    }

    @Test
    @DisplayName("a schema-on-read input has no columns to name")
    void openInput() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA + """
                    source J from json("j.json");
                    """);
            assertThatThrownBy(() -> relix.relation("A JOIN J USING (k)").toList())
                    .hasMessageContaining("USING join: open (schema-on-read) input is not supported");
        }
    }

    @Test
    @DisplayName("the embedding API builds the same joins")
    void combinators() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA);
            Relation a = relix.relation("A");
            Relation b = relix.relation("B");
            EmbedAssertions.assertThat(a.fullJoinUsing(b, "k")).hasRowCount(6);
            EmbedAssertions.assertThat(a.leftJoinUsing(b, "k")).hasRowCount(4);
            EmbedAssertions.assertThat(a.rightJoinUsing(b, "k")).hasRowCount(4);
            EmbedAssertions.assertThat(a.joinUsing(b, "k")).hasRowCount(2);
        }
    }

    @Test
    @DisplayName("a column missing from a side, or typed differently on each, is refused")
    void refusals() {
        try (Relix relix = Relix.open()) {
            relix.define(DATA + """
                    C := [| k   |
                    | one |];
                    """);
            assertThatThrownBy(() -> relix.relation("A JOIN B USING (nope)").toList())
                    .hasMessageContaining("USING column 'nope' is not a column of the left input");
            assertThatThrownBy(() -> relix.relation("A JOIN C USING (k)").toList())
                    .hasMessageContaining("USING column 'k' is NUMBER on the left and STRING on the right");
            assertThatThrownBy(() -> relix.relation("A JOIN C USING (id)").toList())
                    .hasMessageContaining("USING column 'id' is not a column of the right input");
            assertThatThrownBy(() -> relix.relation("A JOIN Nope USING (k)").toList())
                    .hasMessageContaining("Nope");
        }
    }
}

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

import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.ObjectAssert;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.str;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL's predicate spellings — {@code <>}, {@code BETWEEN}, {@code IS [NOT] DISTINCT FROM}
 * and {@code LIKE … ESCAPE} — answer as PostgreSQL does, NULL operands included.
 */
@DisplayName("SQL predicate spellings, evaluated")
final class SqlPredicateSpellingsTest {

    private static final String PAIRS = """
            Pairs := [| id | a    | b    |
            | 1  | 1    | 1    |
            | 2  | 1    | 2    |
            | 3  | NULL | 2    |
            | 4  | 1    | NULL |
            | 5  | NULL | NULL |];
            """;

    private static AbstractListAssert<?, List<? extends String>, String, ObjectAssert<String>> ids(Relix relix, String condition) {
        return EmbedAssertions.assertThat(relix.relation("σ " + condition + " (Pairs)")).tuples()
                .extracting(t -> t.get("id").asDisplayString());
    }

    @Test
    @DisplayName("<> is !=: UNKNOWN on a NULL, so neither side keeps it")
    void notEqual() {
        try (Relix relix = Relix.open()) {
            relix.define(PAIRS);
            ids(relix, "a <> b").containsExactly("2");
            ids(relix, "¬(a <> b)").containsExactly("1");
        }
    }

    @Test
    @DisplayName("BETWEEN includes both bounds; NULL subject or bound is in neither BETWEEN nor NOT BETWEEN")
    void between() {
        try (Relix relix = Relix.open()) {
            relix.define(PAIRS);
            ids(relix, "b BETWEEN 1 AND 2").containsExactly("1", "2", "3");
            ids(relix, "b NOT BETWEEN 1 AND 1").containsExactly("2", "3");
            ids(relix, "1 BETWEEN a AND b").containsExactly("1", "2");
            ids(relix, "1 NOT BETWEEN a AND b").isEmpty();
            // Bounds in the wrong order hold nothing, as SQL's (asymmetric) BETWEEN has it.
            ids(relix, "b BETWEEN 2 AND 1").isEmpty();
        }
    }

    @Test
    @DisplayName("IS [NOT] DISTINCT FROM is two-valued: its negation keeps exactly the rows it drops")
    void distinctFrom() {
        try (Relix relix = Relix.open()) {
            relix.define(PAIRS);
            ids(relix, "a IS DISTINCT FROM b").containsExactly("2", "3", "4");
            ids(relix, "a IS NOT DISTINCT FROM b").containsExactly("1", "5");
            ids(relix, "¬(a IS DISTINCT FROM b)").containsExactly("1", "5");
            ids(relix, "¬(a IS NOT DISTINCT FROM b)").containsExactly("2", "3", "4");
            EmbedAssertions.assertThat(relix.relation("π id, IIf(a IS DISTINCT FROM b, 1, 0) → d (Pairs)"))
                    .tuples().extracting(t -> t.get("d").asDisplayString())
                    .containsExactly("0", "1", "1", "1", "0");
        }
    }

    @Test
    @DisplayName("LIKE … ESCAPE matches an escaped % or _ as itself, and refuses a trailing escape when it runs")
    void likeEscape() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    D := [| label |
                    | 10% off |
                    | 100 points |
                    | save_20 |
                    | save 20 |];
                    """);
            EmbedAssertions.assertThat(relix.relation("σ label LIKE \"%0!%%\" ESCAPE \"!\" (D)")).tuples()
                    .extracting(t -> t.string("label")).containsExactly("10% off");
            EmbedAssertions.assertThat(relix.relation("σ label LIKE 'save!_%' ESCAPE '!' (D)")).tuples()
                    .extracting(t -> t.string("label")).containsExactly("save_20");
            EmbedAssertions.assertThat(relix.relation("σ label NOT LIKE 'save!_%' ESCAPE '!' (D)")).tuples()
                    .extracting(t -> t.string("label")).containsExactly("10% off", "100 points", "save 20");
            assertThatThrownBy(() -> relix.relation("σ label LIKE 'save!' ESCAPE '!' (D)").toList())
                    .hasMessageContaining("LIKE pattern must not end with the escape character");
        }
    }

    @Test
    @DisplayName("an escape is kept on the parsed tree")
    void escapeParsed() {
        try (Relix relix = Relix.open()) {
            relix.define("D := [| label |\n| a |];");
            EmbedAssertions.assertThat(relix.relation("σ label LIKE '50!%' ESCAPE '!' (D)"))
                    .node().isStructurallyEqualTo(com.darkcollective.relix.ast.AstBuilders.select(
                            com.darkcollective.relix.ast.AstBuilders.like(attr("label"), str("50!%"), false, '!'),
                            com.darkcollective.relix.ast.AstBuilders.rel("D")));
        }
    }
}

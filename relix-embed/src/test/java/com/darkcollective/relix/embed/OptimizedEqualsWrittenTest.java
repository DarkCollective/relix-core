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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rewrite must preserve the answer: each case here runs one query optimised and as
 * written and requires the same rows.
 *
 * <p>The optimizer's equivalence harness generates its trees over column data, so a
 * rewrite that only fires on constants, or only after a view has been inlined, never
 * met it. These are the cases found by running the manuals' own examples both ways.
 */
@DisplayName("Optimised and as-written execution agree")
final class OptimizedEqualsWrittenTest {

    private static List<String> rows(Relation relation) {
        List<String> out = new ArrayList<>();
        for (Tuple t : relation.toList()) {
            out.add(t.toString());
        }
        return out;
    }

    /** Runs the script's only query both ways, asserts the rows agree and returns them. */
    private static List<String> both(String script) {
        try (Relix relix = Relix.open()) {
            Relation relation = relix.script(script).get(0);
            List<String> optimised = rows(relation);
            List<String> written = rows(relation.asWritten());
            assertThat(optimised).containsExactlyInAnyOrderElementsOf(written);
            return optimised;
        }
    }

    @Test
    @DisplayName("1 / 3 keeps ten places whether or not the optimizer folded it")
    void divisionFoldedAndExecutedAlike() {
        assertThat(both("U := [| a |\n| 1 |];\nquery { π 1 / 3 → r (U) };").get(0))
                .contains("0.3333333333").doesNotContain("0.33333333333");
    }

    @Test
    @DisplayName("a quotient below the tenth place is 0 both ways")
    void tinyQuotient() {
        assertThat(both("U := [| a |\n| 1 |];\nquery { π 1 / 100000000000 → r (U) };").get(0))
                .doesNotContain("E").contains("0");
    }

    @Test
    @DisplayName("a literal beyond DECIMAL64 precision divides the same way")
    void hugeLiteralQuotient() {
        both("U := [| a |\n| 1 |];\nquery { π 1e400 / 3 → r (U) };");
    }

    @Test
    @DisplayName("σ a / b = 0 treats 1 / 1e11 the same whether the sides are columns or literals")
    void predicateOverColumns() {
        both("U := [| a | b |\n| 1 | 100000000000 |];\nquery { σ a / b = 0 (U) };");
    }

    @Test
    @DisplayName("a struct field read through a view is the same inlined or not")
    void structFieldThroughView() {
        List<String> r = both("""
                People := [| id | first | last | city |
                           | 1 | Ada | Lovelace | London |];
                Nested := { π id, {first: first, last: last, home: {city: city}} → person (People) };
                query { π id, person.first → first, person.home.city → city (Nested) };
                """);
        assertThat(r).hasSize(1);
        assertThat(r.get(0)).contains("Ada").contains("London");
    }

    @Test
    @DisplayName("a minimal struct view read: π person.first (Nested)")
    void structFieldThroughViewMinimal() {
        assertThat(both("""
                People := [| id | first |
                           | 1 | Ada |];
                Nested := { π id, {first: first} → person (People) };
                query { π person.first → f (Nested) };
                """)).hasSize(1);
    }

    @Test
    @DisplayName("σ and γ over a struct field of a view agree as well")
    void structFieldInPredicateAndAggregate() {
        String views = """
                People := [| id | first |
                           | 1 | Ada |
                           | 2 | Grace |];
                Nested := { π id, {first: first} → person (People) };
                """;
        assertThat(both(views + "query { σ person.first = 'Ada' (Nested) };")).hasSize(1);
        assertThat(both(views + "query { γ MAX(LEN(person.first)) → m (Nested) };")).hasSize(1);
    }

    @Test
    @DisplayName("LIKE lets % and _ match a line terminator")
    void likeMatchesNewlines() {
        assertThat(both("U := [| a |\n| 1 |];\nquery { π (('a' + CHR(10) + 'b') LIKE 'a%b') → p, (('a' + CHR(10) + 'b') LIKE 'a_b') → q (U) };").get(0))
                .contains("true").doesNotContain("false");
    }

    @Test
    @DisplayName("LIKE with many % over a long value answers promptly")
    void likeDoesNotBacktrack() {
        String pattern = "%a".repeat(60) + "%c";
        String text = "a".repeat(3000) + "b";
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                assertThat(both("U := [| t |\n| " + text + " |];\nquery { σ t LIKE '" + pattern + "' (U) };")).isEmpty());
    }

    @Test
    @DisplayName("TRACE adds weights as exact decimals, in the relaxation and in the single-pair search")
    void traceWeightsAreExact() {
        String edges = "E := [| s | d | w |\n| a | b | 0.1 |\n| b | c | 0.2 |\n| x | y | 9007199254740993 |];\n";
        List<String> all = both(edges + "query { σ s = 'a' ∧ d = 'c' (TRACE s, d VIA w MINIMIZE AS p (E)) };");
        assertThat(all).hasSize(1);
        assertThat(all.get(0)).contains("0.3").doesNotContain("0.30000000000000004");
        List<String> big = both(edges + "query { σ s = 'x' ∧ d = 'y' (TRACE s, d VIA w MINIMIZE AS p (E)) };");
        assertThat(big.get(0)).contains("9007199254740993");
        List<String> pairs = both(edges + "query { TRACE s, d VIA w MINIMIZE AS p (E) };");
        assertThat(pairs.toString()).contains("0.3").doesNotContain("0.30000000000000004")
                .contains("9007199254740993");
    }

    @Test
    @DisplayName("the single-pair TRACE search finds the shorter route when it meets one later")
    void traceShorterRouteFoundLater() {
        String edges = "E := [| s | d | w |\n| s | a | 1 |\n| s | b | 5 |\n| s | c | 2 |\n| a | b | 1 |\n| a | c | 4 |\n| b | t | 1 |];\n";
        List<String> r = both(edges + "query { σ s = 's' ∧ d = 't' (TRACE s, d VIA w MINIMIZE AS p (E)) };");
        assertThat(r).hasSize(1);
        assertThat(r.get(0)).contains("3");
    }
}

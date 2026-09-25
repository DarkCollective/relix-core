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
package com.darkcollective.relix.processor.reference;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Universal quantification against an independent definition, over generated relations.
 *
 * <p>A keyed ∀ over a single connection does push down, so the MySQL and Postgres
 * agreement suites reach that one shape. Nothing reaches the in-engine evaluation, the
 * no-key truth-relation form, or either of them when a value is missing — and a missing
 * value is where ∀ is interesting, because "every row satisfies P" has to decide about a
 * row that has not answered.
 *
 * <p>The inputs are drawn because the cases are group shapes rather than examples: a group
 * that is all true, one with a single false, one with a single unknown, one with both, one
 * of a single row, and the relation with no rows at all. Those are combinations. A seed is
 * named rather than taken from the clock.
 *
 * <p>Alongside the definition there is a second claim, of the kind the closure suite makes
 * against {@code FIX}: ∀ has a set-algebra spelling, {@code π k (R) − π k (σ ¬P (R))}, and
 * the interesting part is that the obvious form of it is <em>wrong</em> — in exactly one
 * place, which is the place ∀ exists to get right.
 */
@DisplayName("Universal quantification against an independent definition")
final class QuantificationReferenceTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    /** Named, never from the clock. */
    private static final long SEED = 20_260_914L;

    private static final int DRAWS = 200;

    /** Four keys of up to three rows each: enough for every group shape to be drawn often. */
    private static final int KEYS = 4;

    /** A value is one of these, and the empty string is how an inline table spells a missing one. */
    private static final List<String> VALUES = List.of("a", "a", "b", "");

    // ── running a query ───────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    private static void each(String script, Consumer<Row> reader) {
        SemanticModel model = model(script);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        try (Stream<Row> rows = EXECUTOR.execute(queryNode(model.rootQueries().getFirst()), ctx)) {
            rows.forEach(reader);
        }
    }

    private static List<String> keysOf(String script) {
        List<String> found = new ArrayList<>();
        each(script, row -> found.add(row.get("k").asDisplayString()));
        return found;
    }

    /** The no-key form answers with a relation that has no columns: one tuple for yes, none for no. */
    private static boolean truth(String script) {
        List<Row> rows = new ArrayList<>();
        each(script, rows::add);
        assertThat(rows).as("a truth relation holds at most one tuple").hasSizeLessThan(2);
        return !rows.isEmpty();
    }

    /** {@code (key, value)} pairs as an inline table; an empty value is a missing one. */
    private static String table(String name, List<String[]> rows) {
        StringBuilder text = new StringBuilder(name + " := [| k | v |\n");
        rows.forEach(r -> text.append("| ").append(r[0]).append(" | ").append(r[1]).append(" |\n"));
        return text.append("];\n").toString();
    }

    /** What the reference is given: the same rows, with a missing value as a Java null. */
    private static List<String[]> asReference(List<String[]> rows) {
        return rows.stream()
                .map(r -> new String[] {r[0], r[1].isEmpty() ? null : r[1]})
                .toList();
    }

    private static List<String[]> draw(Random rng) {
        List<String[]> rows = new ArrayList<>();
        for (int key = 1; key <= KEYS; key++) {
            int count = rng.nextInt(4);   // 0 to 3 rows — a key with none is simply absent
            for (int i = 0; i < count; i++) {
                rows.add(new String[] {String.valueOf(key), VALUES.get(rng.nextInt(VALUES.size()))});
            }
        }
        return rows;
    }

    private static String render(List<String[]> rows) {
        return rows.stream().map(r -> r[0] + "=" + (r[1].isEmpty() ? "_" : r[1])).toList().toString();
    }

    // ── the claims ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("200 generated relations: the keyed form agrees with the definition")
    void theKeyedFormAgreesWithTheDefinition() {
        Random rng = new Random(SEED);
        int qualifying = 0;
        int withUnknown = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<String[]> rows = draw(rng);
            if (rows.isEmpty()) {
                continue;   // the empty relation is its own case, below
            }
            List<String> expected = QuantificationReference.forAll(asReference(rows), "a");
            if (!expected.isEmpty()) {
                qualifying++;
            }
            if (rows.stream().anyMatch(r -> r[1].isEmpty())) {
                withUnknown++;
            }

            assertThat(keysOf(table("R", rows) + "query { ∀ k : v = \"a\" (R) };"))
                    .as("∀ k : v = 'a' — %s", render(rows))
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        assertThat(qualifying)
                .as("draws where some key qualified — a search where ∀ always returned "
                    + "nothing would pass while comparing almost nothing")
                .isGreaterThan(DRAWS / 4);
        assertThat(withUnknown)
                .as("draws carrying a row whose comparison is unknown")
                .isGreaterThan(DRAWS / 2);
    }

    @Test
    @DisplayName("the no-key form is a truth relation, and agrees with the keyed one per group")
    void theNoKeyFormIsATruthRelation() {
        Random rng = new Random(SEED);
        int yes = 0;
        int no = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<String[]> rows = draw(rng);
            if (rows.isEmpty()) {
                continue;
            }
            String table = table("R", rows);

            assertThat(truth(table + "query { ∀ : v = \"a\" (R) };"))
                    .as("∀ : v = 'a' over the whole relation — %s", render(rows))
                    .isEqualTo(QuantificationReference.everyRow(
                            asReference(rows).stream().map(r -> r[1]).toList(), "a"));

            // The no-key form asked of one group is the keyed form's question about that
            // group, so the two have to answer alike — including for a key with no rows at
            // all, where the answer is yes because no row fails. That is the vacuous case,
            // and asking it this way is how a generated draw can reach it: an inline table
            // needs a row, a selection over one need not keep any.
            List<String> qualified = QuantificationReference.forAll(asReference(rows), "a");
            for (int key = 1; key <= KEYS; key++) {
                String name = String.valueOf(key);
                boolean present = rows.stream().anyMatch(r -> r[0].equals(name));
                boolean expected = !present || qualified.contains(name);
                if (expected) {
                    yes++;
                } else {
                    no++;
                }
                assertThat(truth(table + "query { ∀ : v = \"a\" (σ k = " + key + " (R)) };"))
                        .as("∀ over group %d — %s", key, render(rows))
                        .isEqualTo(expected);
            }
        }

        assertThat(yes).as("groups answering yes").isGreaterThan(DRAWS / 2);
        assertThat(no).as("groups answering no").isGreaterThan(DRAWS / 2);
    }

    @Test
    @DisplayName("the obvious set-algebra spelling is wrong, and wrong only where ∀ is strict")
    void theSetAlgebraSpellingNeedsTheUnknownsBackIn() {
        // k=1 is all 'a'; k=2 holds a 'b'; k=3 holds only a missing value; k=4 holds both.
        String rows = """
                R := [| k | v |
                      | 1 | a |
                      | 2 | b |
                      | 3 |   |
                      | 4 | a |
                      | 4 |   |];
                """;

        // "Every row satisfies P" written as "all the keys, less the keys of a row that
        // fails P". It reads like the definition and is not: ¬P over a missing value is
        // itself unknown, so σ drops the row and the group is never subtracted.
        assertThat(keysOf(rows + "query { π k (R) − π k (σ ¬(v = \"a\") (R)) };"))
                .as("the naive difference keeps the groups it could not evaluate")
                .containsExactlyInAnyOrder("1", "3", "4");

        // Subtracting the unknowns as well as the failures is what makes it ∀.
        assertThat(keysOf(rows + "query { π k (R) − π k (σ ¬(v = \"a\") ∨ v IS NULL (R)) };"))
                .as("failures and unknowns both disqualify")
                .containsExactlyInAnyOrder("1");

        assertThat(keysOf(rows + "query { ∀ k : v = \"a\" (R) };"))
                .as("which is what the operator answers, and what the definition says")
                .containsExactlyInAnyOrderElementsOf(
                        QuantificationReference.forAll(List.of(
                                new String[] {"1", "a"}, new String[] {"2", "b"},
                                new String[] {"3", null}, new String[] {"4", "a"},
                                new String[] {"4", null}), "a"));
    }

    @Test
    @DisplayName("with no unknowns the two spellings agree, over generated relations")
    void withoutUnknownsTheSpellingsAgree() {
        Random rng = new Random(SEED);
        int compared = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<String[]> rows = draw(rng).stream()
                    .filter(r -> !r[1].isEmpty())   // the divergence above is its own test
                    .toList();
            if (rows.isEmpty()) {
                continue;
            }
            String table = table("R", rows);

            // Two engine paths that share nothing: a blocking grouped operator, and a
            // projection, a selection and a set difference.
            assertThat(keysOf(table + "query { ∀ k : v = \"a\" (R) };"))
                    .as("∀ vs the difference — %s", render(rows))
                    .containsExactlyInAnyOrderElementsOf(
                            keysOf(table + "query { π k (R) − π k (σ ¬(v = \"a\") (R)) };"));
            compared++;
        }

        assertThat(compared).as("relations actually compared").isGreaterThan(DRAWS / 2);
    }
}

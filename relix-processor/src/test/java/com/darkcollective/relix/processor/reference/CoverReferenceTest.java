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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * COVER's guarantee over generated candidate sets.
 *
 * <p>Unlike the other operators in this package, COVER already had a real oracle: the
 * coverage property, asserted against hand-picked candidate sets. What it did not have was
 * inputs it had not been shown. A greedy search picks one suite among many, and the shapes
 * that catch a greedy going wrong are combinations — a column with one value, a
 * combination occurring in exactly one candidate row, a candidate set far smaller than the
 * full product — rather than examples a person thinks to write.
 *
 * <p>Three claims per draw, and the third is the one that makes the first two mean
 * anything: the suite covers, every row of it came from the candidates, and it is
 * <strong>smaller</strong> than the candidate set. Returning every candidate satisfies
 * coverage completely, so a coverage check alone cannot tell a covering array from an
 * identity function.
 */
@DisplayName("COVER's guarantee over generated candidate sets")
final class CoverReferenceTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    /** Named, never from the clock. */
    private static final long SEED = 20_260_914L;

    private static final int DRAWS = 80;

    /** Four factors; the third has a single level, which is the degenerate column a greedy can trip on. */
    private static final List<List<String>> DOMAINS = List.of(
            List.of("a", "b", "c"),
            List.of("1", "2"),
            List.of("s"),
            List.of("p", "q"));

    // ── running a query ───────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    private static List<List<String>> run(String script) {
        SemanticModel model = model(script);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        List<List<String>> out = new ArrayList<>();
        try (Stream<Row> rows = EXECUTOR.execute(queryNode(model.rootQueries().getFirst()), ctx)) {
            rows.forEach(row -> {
                List<String> cells = new ArrayList<>(row.width());
                for (int i = 0; i < row.width(); i++) {
                    cells.add(row.get(i).asDisplayString());
                }
                out.add(List.copyOf(cells));
            });
        }
        return out;
    }

    private static String table(List<List<String>> rows) {
        StringBuilder text = new StringBuilder("C := [| w | x | y | z |\n");
        rows.forEach(r -> text.append("| ").append(String.join(" | ", r)).append(" |\n"));
        return text.append("];\n").toString();
    }

    // ── the draw ──────────────────────────────────────────────────────────────

    /**
     * A candidate set drawn from the full product of the four domains, keeping each row
     * about three times in four.
     *
     * <p>Drawn from the product rather than freely, because a candidate set is a parameter
     * space and that is the shape one has; dropping rows from it is how the constrained
     * case arises, and a combination excluded that way is one the coverage universe must
     * not demand — the manual's "constraints come free".
     */
    private static List<List<String>> draw(Random rng) {
        List<List<String>> candidates = new ArrayList<>();
        for (String w : DOMAINS.get(0)) {
            for (String x : DOMAINS.get(1)) {
                for (String y : DOMAINS.get(2)) {
                    for (String z : DOMAINS.get(3)) {
                        if (rng.nextInt(4) != 0) {
                            candidates.add(List.of(w, x, y, z));
                        }
                    }
                }
            }
        }
        return candidates;
    }

    // ── the claims ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("80 generated candidate sets, strengths 1 to 3: covered, sound, and smaller")
    void theSuiteCoversAndIsWorthHaving() {
        Random rng = new Random(SEED);
        int reduced = 0;
        int total = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<List<String>> candidates = draw(rng);
            if (candidates.isEmpty()) {
                continue;
            }
            String table = table(candidates);

            for (int t = 1; t <= 3; t++) {
                List<List<String>> suite = run(table + "query { COVER " + t + " (C) };");
                total++;

                assertThat(CoverReference.uncovered(candidates, suite, t))
                        .as("COVER %d over %d candidates — combinations left uncovered", t,
                                candidates.size())
                        .isEmpty();

                // Output ⊆ input: a covering suite is chosen from the candidates, never
                // invented, which is what "a windowed filter" means and what keeps a
                // constraint on the input a constraint on the answer.
                assertThat(suite)
                        .as("COVER %d over %d candidates — every chosen row is a candidate", t,
                                candidates.size())
                        .isSubsetOf(candidates);

                if (suite.size() < new LinkedHashSet<>(candidates).size()) {
                    reduced++;
                }
            }
        }

        // Without this the two assertions above are satisfied by returning every candidate.
        assertThat(reduced)
                .as("runs where the suite was smaller than the candidate set — a coverage "
                    + "check alone cannot tell a covering array from an identity function")
                .isGreaterThan(total / 2);
    }

    @Test
    @DisplayName("at full strength the suite is every distinct candidate, which is δ")
    void fullStrengthIsDistinct() {
        Random rng = new Random(SEED);
        int compared = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            List<List<String>> candidates = draw(rng);
            if (candidates.isEmpty()) {
                continue;
            }
            String table = table(candidates);

            // t = the number of columns demands every distinct row, so there is nothing to
            // choose: the property collapses to deduplication, and the operator has to
            // agree with the operator that does only that.
            assertThat(run(table + "query { COVER 4 (C) };"))
                    .as("COVER 4 vs δ over %d candidates", candidates.size())
                    .containsExactlyInAnyOrderElementsOf(run(table + "query { δ (C) };"));
            compared++;
        }

        assertThat(compared).as("candidate sets actually compared").isGreaterThan(DRAWS / 2);
    }

    @Test
    @DisplayName("the same candidates in the same order give the same suite, every time")
    void theSearchIsDeterministic() {
        Random rng = new Random(SEED);
        int compared = 0;

        for (int draw = 0; draw < 20; draw++) {
            List<List<String>> candidates = draw(rng);
            if (candidates.isEmpty()) {
                continue;
            }
            String query = table(candidates) + "query { COVER 2 (C) };";

            // Order included: the tie-break is documented as the earliest candidate
            // buffered, which is what makes pre-sorting with a τ a way to bias the suite.
            // A search that returned the same rows in a different order would break that.
            assertThat(run(query))
                    .as("two runs over %d candidates", candidates.size())
                    .containsExactlyElementsOf(run(query));
            compared++;
        }

        assertThat(compared).as("candidate sets actually compared").isEqualTo(20);
    }
}

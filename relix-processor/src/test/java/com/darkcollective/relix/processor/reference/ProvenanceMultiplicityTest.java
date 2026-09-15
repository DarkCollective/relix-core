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
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.QueryExecutor;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.processor.provenance.AnnotatedRelation;
import com.darkcollective.relix.provenance.BooleanSemiring;
import com.darkcollective.relix.provenance.CountingSemiring;
import com.darkcollective.relix.provenance.Semiring;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provenance annotations against plain evaluation, which is the one oracle for them that
 * is not a second provenance evaluator.
 *
 * <p>K-relations specialise: over ℕ an annotation <em>is</em> the multiplicity, so the
 * number the provenance evaluator computes for a row has to be the number of times the
 * ordinary executor emits it. Two code paths with nothing in common, asked the same
 * question. Over the booleans, where ⊕ is idempotent, the annotation is membership.
 *
 * <p>What asserted the threading rules was thirty-eight hand-written examples, every one a
 * chosen relation of two or three rows. The rules are about duplicates colliding, and the
 * inputs are where duplicates come from.
 *
 * <p>The correspondence has an exact edge, and it is stated rather than avoided: ⊕ is
 * addition over ℕ, so a SET-declaring operator annotates the <em>bag</em> answer to a query
 * the engine returns as a set. Over the booleans ⊕ is ∨, which is idempotent, and the two
 * agree there as they do everywhere else.
 */
@DisplayName("Provenance annotations against plain evaluation")
final class ProvenanceMultiplicityTest extends ProcessorTestSupport {

    private static final RelNodeExecutor PLAIN = new RelNodeExecutor();
    private static final QueryExecutor ANNOTATED = new QueryExecutor();

    /** Named, never from the clock. */
    private static final long SEED = 20_260_914L;

    private static final int DRAWS = 60;

    /**
     * The positive operators, in the shapes the threading rules are about: a bare scan,
     * a filter, a projection that collides rows, a rename, both joins, a product, and
     * bag union. Every one of them has bag semantics, which is what makes an ℕ
     * annotation comparable to a row count.
     */
    private static final List<String> BAG_SHAPES = List.of(
            "R",
            "σ a = 1 (R)",
            "π a (R)",
            "ρ T(p, q) (R)",
            "R ⋈ S",
            "π a (R ⋈ S)",
            "R × U",
            "π a (R) ⊎ π a (S)",
            "σ b = \"x\" (π a, b (R))");

    // ── running both paths ────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    /** How many times the ordinary executor emits each row. */
    private static Map<List<String>, BigInteger> plainMultiplicities(String script) {
        SemanticModel model = model(script);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        Map<List<String>, BigInteger> counts = new LinkedHashMap<>();
        try (Stream<Row> rows = PLAIN.execute(queryNode(model.rootQueries().getFirst()), ctx)) {
            rows.forEach(row -> counts.merge(cells(row), BigInteger.ONE, BigInteger::add));
        }
        return counts;
    }

    /** What the provenance evaluator annotates each row with. */
    private static <K> Map<List<String>, K> annotations(String script, Semiring<K> semiring) {
        SemanticModel model = model(script);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        List<AnnotatedRelation<K>> out = new ArrayList<>();
        ANNOTATED.executeProvenance(model, ctx.connector(), semiring, (label, rel) -> out.add(rel));
        assertThat(out).hasSize(1);

        Map<List<String>, K> annotated = new LinkedHashMap<>();
        out.getFirst().stream().forEach(a -> {
            List<String> key = new ArrayList<>();
            for (int i = 0; i < a.row().width(); i++) {
                key.add(a.row().get(i).isNull() ? "NULL" : a.row().get(i).asDisplayString());
            }
            annotated.put(key, a.annotation());
        });
        return annotated;
    }

    private static List<String> cells(Row row) {
        List<String> values = new ArrayList<>(row.width());
        for (int i = 0; i < row.width(); i++) {
            values.add(row.get(i).isNull() ? "NULL" : row.get(i).asDisplayString());
        }
        return values;
    }

    // ── the draw ──────────────────────────────────────────────────────────────

    /**
     * Three small relations drawn with deliberate repetition: {@code a} takes two values
     * and {@code b} three across four rows, so a projection collides and a join fans out.
     * A relation of distinct rows would make every multiplicity one, and a count that is
     * always one agrees with mere membership.
     */
    private static String data(Random rng) {
        StringBuilder text = new StringBuilder();
        text.append("R := [| a | b |\n");
        for (int i = 0; i < 4; i++) {
            text.append("| ").append(rng.nextInt(2) + 1).append(" | ")
                    .append("xyz".charAt(rng.nextInt(3))).append(" |\n");
        }
        text.append("];\nS := [| a | c |\n");
        for (int i = 0; i < 3; i++) {
            text.append("| ").append(rng.nextInt(2) + 1).append(" | ")
                    .append("pq".charAt(rng.nextInt(2))).append(" |\n");
        }
        text.append("];\nU := [| d |\n");
        for (int i = 0; i < 2; i++) {
            text.append("| ").append(rng.nextInt(2) + 1).append(" |\n");
        }
        return text.append("];\n").toString();
    }

    // ── the claims ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("over ℕ the annotation is the multiplicity, for every bag-semantics shape")
    void theCountingAnnotationIsTheMultiplicity() {
        Random rng = new Random(SEED);
        int aboveOne = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            String data = data(rng);
            for (String shape : BAG_SHAPES) {
                String script = data + "query { " + shape + " };\n";
                Map<List<String>, BigInteger> plain = plainMultiplicities(script);
                if (plain.values().stream().anyMatch(n -> n.compareTo(BigInteger.ONE) > 0)) {
                    aboveOne++;
                }

                assertThat(annotations(script, CountingSemiring.INSTANCE))
                        .as("%s over%n%s", shape, data)
                        .containsExactlyInAnyOrderEntriesOf(plain);
            }
        }

        assertThat(aboveOne)
                .as("query runs whose result repeats a row — a multiplicity that is always "
                    + "one agrees with mere membership and proves nothing about ⊕")
                .isGreaterThan(DRAWS * 2);
    }

    @Test
    @DisplayName("over the booleans the annotation is membership, set operators included")
    void theBooleanAnnotationIsMembership() {
        Random rng = new Random(SEED);
        int compared = 0;

        List<String> shapes = new ArrayList<>(BAG_SHAPES);
        // ∪ belongs here and not above: ⊕ is ∨, which is idempotent, so the boolean
        // reading agrees with a plain evaluation that deduplicates.
        shapes.add("π a (R) ∪ π a (S)");

        for (int draw = 0; draw < DRAWS; draw++) {
            String data = data(rng);
            for (String shape : shapes) {
                String script = data + "query { " + shape + " };\n";

                assertThat(annotations(script, BooleanSemiring.INSTANCE).keySet())
                        .as("%s over%n%s", shape, data)
                        .containsExactlyInAnyOrderElementsOf(plainMultiplicities(script).keySet());
                compared++;
            }
        }

        assertThat(compared).as("query runs compared").isEqualTo(DRAWS * shapes.size());
    }

    @Test
    @DisplayName("where ⊕ is not idempotent, a SET operator is annotated as a bag")
    void aSetOperatorAnnotatedOverNIsABag() {
        String data = """
                R := [| a |
                      | 1 |
                      | 1 |];
                """;
        String script = data + "query { R ∪ R };\n";

        // The plain answer is a set: one row. The ℕ annotation adds, so it reports four
        // derivations of it — two from each side. Both are right about their own question,
        // and the pair is worth knowing about before reading a provenance report: over a
        // non-idempotent semiring the number counts derivations, not rows returned.
        assertThat(plainMultiplicities(script))
                .as("the engine's own answer deduplicates")
                .containsExactlyEntriesOf(Map.of(List.of("1"), BigInteger.ONE));
        assertThat(annotations(script, CountingSemiring.INSTANCE))
                .as("the annotation adds the derivations on both sides")
                .containsExactlyEntriesOf(Map.of(List.of("1"), BigInteger.valueOf(4)));

        // Under the booleans the same query agrees, ∨ being idempotent.
        assertThat(annotations(script, BooleanSemiring.INSTANCE))
                .containsExactlyEntriesOf(Map.of(List.of("1"), true));
    }
}

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
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Division against an independent statement of what it means, over generated inputs.
 *
 * <p>No SQL backend has this operator, so no agreement suite reaches it, and everything
 * else asserting it is rows somebody wrote out. {@link DivisionReference} is the manual's
 * sentence turned into a loop.
 *
 * <p>The inputs are drawn rather than chosen, because division's interesting cases are
 * about <em>coverage</em> — an {@code a} paired with all of the divisor, one paired with
 * all but one of it, a divisor value absent from the dividend entirely — and those are
 * combinations, not examples. A seed is named rather than taken from the clock: a
 * generated-input test that fails differently each run is worse than none.
 */
@DisplayName("Division against an independent definition")
final class DivisionReferenceTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    /** Named, never from the clock. */
    private static final long SEED = 20_260_912L;

    private static final int DRAWS = 200;

    /** Small enough that a draw often covers the divisor, which is the case that matters. */
    private static final List<String> AS = List.of("p", "q", "r");
    private static final List<String> BS = List.of("x", "y", "z");

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    private static List<String> engine(List<String[]> dividend, List<String> divisor) {
        return engine(dividend, divisor, "R ÷ S");
    }

    private static List<String> engine(List<String[]> dividend, List<String> divisor,
                                       String expression) {
        StringBuilder script = new StringBuilder();
        script.append("R := [\n| a | b |\n|---|---|\n");
        dividend.forEach(p -> script.append("| ").append(p[0]).append(" | ").append(p[1]).append(" |\n"));
        script.append("];\nS := [\n| b |\n|---|\n");
        divisor.forEach(b -> script.append("| ").append(b).append(" |\n"));
        script.append("];\nquery { ").append(expression).append(" };\n");

        SemanticModel m = model(script.toString());
        ExecutionContext ctx = ExecutionContext.inlineOnly(m);
        List<String> out = new ArrayList<>();
        try (Stream<Row> rows = EXECUTOR.execute(queryNode(m.rootQueries().getFirst()), ctx)) {
            rows.forEach(row -> out.add(row.get("a").asDisplayString()));
        }
        return out;
    }

    /** One drawn pair of relations. */
    private record Drawn(List<String[]> dividend, List<String> divisor) {
    }

    /**
     * A dividend holding each {@code (a, b)} with even odds, and a divisor holding each
     * {@code b} with even odds — so full coverage, coverage but for one, and a divisor
     * value the dividend never mentions are all ordinary draws.
     */
    private static Drawn draw(Random rng) {
        List<String[]> dividend = new ArrayList<>();
        for (String a : AS) {
            for (String b : BS) {
                if (rng.nextBoolean()) {
                    dividend.add(new String[] {a, b});
                }
            }
        }
        List<String> divisor = new ArrayList<>();
        for (String b : BS) {
            if (rng.nextBoolean()) {
                divisor.add(b);
            }
        }
        return new Drawn(dividend, divisor);
    }

    @Test
    @DisplayName("200 generated pairs of relations: the engine agrees with the definition")
    void agreesWithTheDefinition() {
        Random rng = new Random(SEED);
        int nonEmptyAnswers = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            Drawn drawn = draw(rng);
            List<String[]> dividend = drawn.dividend();
            List<String> divisor = drawn.divisor();
            if (dividend.isEmpty() || divisor.isEmpty()) {
                continue;   // both are their own question; see the cases below
            }

            List<String> expected = DivisionReference.divide(dividend, divisor);
            if (!expected.isEmpty()) {
                nonEmptyAnswers++;
            }
            assertThat(engine(dividend, divisor))
                    .as("R = %s ÷ S = %s", render(dividend), divisor)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        assertThat(nonEmptyAnswers)
                .as("draws whose answer is not empty — a search where division always "
                    + "returned nothing would pass while checking almost nothing")
                .isGreaterThan(DRAWS / 10);
    }

    @Test
    @DisplayName("the textbook set-algebra spelling gives what the operator gives")
    void theSetAlgebraSpellingAgrees() {
        Random rng = new Random(SEED);
        int compared = 0;

        for (int draw = 0; draw < DRAWS; draw++) {
            Drawn drawn = draw(rng);
            List<String[]> dividend = drawn.dividend();
            List<String> divisor = drawn.divisor();
            if (dividend.isEmpty() || divisor.isEmpty()) {
                continue;
            }

            // π a (R) − π a ((π a (R) × S) − R): every candidate, less those for which some
            // required pairing is missing. It is the definition of ÷ in the language ÷ was
            // added to avoid, so the two share no code at all — one is a dedicated blocking
            // operator, the other a product, two differences and three projections.
            assertThat(engine(dividend, divisor, "π a (R) − π a ((π a (R) × S) − R)"))
                    .as("R = %s ÷ S = %s", render(dividend), divisor)
                    .containsExactlyInAnyOrderElementsOf(
                            DivisionReference.divide(dividend, divisor));
            compared++;
        }

        assertThat(compared).as("pairs of relations actually compared").isGreaterThan(DRAWS / 2);
    }

    @Test
    @DisplayName("dividing by nothing keeps everything, which is vacuous truth and not an edge case")
    void anEmptyDivisorKeepsEveryCandidate() {
        List<String[]> dividend = List.of(new String[] {"p", "x"}, new String[] {"q", "y"});

        assertThat(engine(dividend, List.of()))
                .as("every a is paired with all zero of the divisor's values")
                .containsExactlyInAnyOrderElementsOf(DivisionReference.divide(dividend, List.of()));
    }

    private static String render(List<String[]> pairs) {
        return pairs.stream().map(p -> p[0] + p[1]).toList().toString();
    }
}

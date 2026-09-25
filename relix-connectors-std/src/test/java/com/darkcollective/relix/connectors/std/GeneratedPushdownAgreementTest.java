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
package com.darkcollective.relix.connectors.std;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.plan.internal.Dialect;
import com.darkcollective.relix.semantic.QueryGenerator;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The pushdown corpus, searched rather than written.</b>
 *
 * <p>{@link PushdownCorpus} is some 200 expressions somebody chose, and the last two
 * defects found in the renderer were both cases where a chosen expression did not
 * discriminate: {@code σ amount / qty > 40} folded and agreed for as long as it existed
 * because every quotient in the fixture is whole, and the string-literal escaping had no
 * case with a backslash in it at all. Both were found by a person asking what the corpus
 * did not ask, which is not a repeatable process.
 *
 * <p>This joins the two harnesses that already exist — {@link QueryGenerator} draws trees,
 * {@link PushdownAgreement} runs one query as a folded {@code SELECT} and again in-engine
 * and diffs the rows — so the expressions are not chosen by anyone.
 *
 * <h2>The draws that count</h2>
 *
 * A draw counts only when the planner folds the expression <b>wholly</b>, which is the
 * same criterion the corpus asserts. Anything less is the same plan twice: a bare
 * connection table is already a pushed scan, so an expression whose top operator has no
 * spelling still contains one, still compares, and compares an in-engine evaluation
 * against an in-engine evaluation. Seven corpus cases were once doing exactly that.
 *
 * <p>So the run asserts what it did and not merely that it finished — the discipline
 * {@code TESTING.md} asks of a generated-input test, and the reason a search that stopped
 * folding anything would fail here rather than pass in silence.
 *
 * <p>It runs against H2, in the gate, because that is where the corpus's own portable arm
 * runs. The container suites run the same corpus against real backends and are where a
 * dialect-specific spelling is exercised; a search over those is the obvious next step and
 * is not this.
 */
@DisplayName("Generated queries agree between the folded and in-engine plans (H2 / GENERIC)")
final class GeneratedPushdownAgreementTest {

    private static final String URL =
            "jdbc:h2:mem:generated_pushdown;DB_CLOSE_DELAY=-1";

    /** Named, never from the clock: a generated-input test must fail the same way twice. */
    private static final long SEED = 20_260_912L;

    /** Trees drawn at each depth — a few seconds, which is the budget a gate can afford. */
    private static final int TREES = 400;

    /**
     * Both depths, because they search different things and neither alone is enough.
     *
     * <p>At depth 2 the trees are small and most of them fold, so this is where the volume
     * is: measured, about one draw in eight. At depth 3 only about one in thirty folds —
     * a deeper tree is likelier to contain an operator that declines, and a whole fold
     * needs every one of them to push — but a fold that does happen is a fold *stacked* on
     * another, which is where every defect this found actually lived.
     */
    private static final int[] DEPTHS = {2, 3};

    /**
     * The floor on how many draws must fold wholly. This seed folds roughly 90; the floor
     * sits well below that so a change to what the renderer accepts does not turn this red
     * on its own, and well above zero so a search that stopped reaching the renderer fails
     * rather than passing in silence.
     */
    private static final int FOLDS_EXPECTED = 30;

    private static final String PREAMBLE = GeneratedPushdownFixture.preamble(URL);

    private static final PushdownAgreement AGREEMENT =
            new PushdownAgreement(PREAMBLE, Dialect.GENERIC);

    /** The connection-backed relations the preamble declares. */
    private static final List<String> RELATIONS = List.of("Orders", "Customers");

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL)) {
            GeneratedPushdownFixture.seed(c);
        }
    }

    @Test
    @DisplayName("800 generated trees: what folds returns what the engine returns")
    void generatedQueriesAgree() {
        SemanticModel model = SemanticFixtures.analyze(PREAMBLE + "query { Orders };")
                .model().orElseThrow();

        int folded = 0;
        List<String> foldedExpressions = new ArrayList<>();
        for (int depth : DEPTHS) {
            QueryGenerator generator = new QueryGenerator(new Random(SEED), model, RELATIONS);
            for (int i = 0; i < TREES; i++) {
                RelNode tree = generator.generate(depth).node();
                // Round-tripped through the printer, because the harness takes an
                // expression; that trip is guarded in its own right by
                // RelNodeCorpusRoundTripTest.
                String expression = tree.prettyPrint().strip();
                if (AGREEMENT.assertAgreesWhenFolded(expression)) {
                    folded++;
                    foldedExpressions.add(expression);
                }
            }
        }

        assertThat(folded)
                .as("draws the planner folded wholly — below this the search is comparing "
                    + "in-engine plans with themselves and exercising no renderer")
                .isGreaterThanOrEqualTo(FOLDS_EXPECTED);
        assertThat(foldedExpressions)
                .as("the folded draws must not all be the same shape")
                .doesNotHaveDuplicates();
    }
}

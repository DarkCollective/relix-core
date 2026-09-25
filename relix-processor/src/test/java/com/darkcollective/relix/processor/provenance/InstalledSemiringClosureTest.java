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
package com.darkcollective.relix.processor.provenance;

import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.provenance.BaseTuple;
import com.darkcollective.relix.provenance.Semiring;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.internal.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A semiring the engine has never heard of, driving a weighted closure end to end.
 *
 * <p>This is the claim the provider seam rests on, and it is not the same claim as "a
 * library is discovered": resolving a name proves only that an object was found. Until the
 * engine stopped recognising its own four singletons by identity, a semiring it did not
 * recognise reached {@link Semiring#one()} for every base tuple — so it would have
 * installed, resolved, run, and annotated every pair of a weighted graph with 1. A
 * discovery test passes against that engine. This one does not.
 *
 * <p>The semiring is the kinship coefficient, because it is the case that makes the seam
 * worth having: alternative lines of descent add and successive generations multiply, so
 * sum-product over the reals computes a genealogical relationship directly, and pedigree
 * collapse — an ancestor reached by two distinct lines — falls out of {@code ⊕} rather
 * than having to be looked for. Nothing in the engine mentions any of that.
 */
@DisplayName("A semiring installed through the provider seam drives a weighted closure")
final class InstalledSemiringClosureTest extends ProcessorTestSupport {

    private static final QueryExecutor EXECUTOR = new QueryExecutor();

    /** Enough for any line of descent in these fixtures; the cap is not what is under test. */
    private static final int ROUNDS = 20;

    /**
     * Sum-product over the reals: alternative derivations add, successive steps multiply,
     * and a base tuple with no weight of its own is one generation of descent.
     */
    private static final Semiring<Double> KINSHIP = new Semiring<>() {
        @Override
        public Double zero() {
            return 0.0d;
        }

        @Override
        public Double one() {
            return 1.0d;
        }

        @Override
        public Double plus(Double a, Double b) {
            return a + b;
        }

        @Override
        public Double times(Double a, Double b) {
            return a * b;
        }

        @Override
        public Double base(BaseTuple tuple) {
            return tuple.weight().map(BigDecimal::doubleValue).orElse(0.5d);
        }
    };

    private static Map<String, Double> closure(String script, String weightColumn) {
        SemanticResult result = analyze(script);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        SemanticModel model = result.model().orElseThrow();
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        List<AnnotatedRelation<Double>> out = new ArrayList<>();
        EXECUTOR.executeProvenance(model, ctx.connector(), KINSHIP, weightColumn, ROUNDS,
                (label, rel) -> out.add(rel));
        assertThat(out).hasSize(1);

        Map<String, Double> byPair = new LinkedHashMap<>();
        out.getFirst().stream().forEach(a -> byPair.put(
                a.row().get(0).asDisplayString() + "->" + a.row().get(1).asDisplayString(),
                a.annotation()));
        return byPair;
    }

    /**
     * A pedigree in which {@code D}'s two parents are siblings, so {@code A} is reached
     * from {@code D} by two distinct lines of the same length — the case the semiring is
     * chosen for.
     *
     * <pre>
     *        A
     *       / \
     *      B   C
     *       \ /
     *        D
     * </pre>
     */
    private static final String COUSIN_MARRIAGE = """
            Family := [
            | parent | child |
            |--------|-------|
            | A      | B     |
            | A      | C     |
            | B      | D     |
            | C      | D     |
            ];
            query { CLOSURE parent, child (Family) };
            """;

    @Test
    @DisplayName("each generation halves, and two lines of descent add")
    void pedigreeCollapseIsASum() {
        Map<String, Double> kinship = closure(COUSIN_MARRIAGE, null);

        // One generation.
        assertThat(kinship).containsEntry("A->B", 0.5d);
        assertThat(kinship).containsEntry("B->D", 0.5d);
        // Two generations by two distinct lines: 1/4 through B plus 1/4 through C. A
        // reachability semiring says "yes" here and a path count says "2"; only a
        // sum-product says that D is as related to A as a child is to a parent, which is
        // what cousin marriage means and why the answer is worth computing.
        assertThat(kinship).containsEntry("A->D", 0.5d);
    }

    @Test
    @DisplayName("a single line of descent is a plain power of one half")
    void singleLineIsAPower() {
        Map<String, Double> kinship = closure("""
                Family := [
                | parent | child |
                |--------|-------|
                | A      | B     |
                | B      | C     |
                | C      | D     |
                ];
                query { CLOSURE parent, child (Family) };
                """, null);

        assertThat(kinship).containsEntry("A->B", 0.5d);
        assertThat(kinship).containsEntry("A->C", 0.25d);
        assertThat(kinship).containsEntry("A->D", 0.125d);
    }

    @Test
    @DisplayName("a weight column reaches the semiring through the real row path")
    void readsAWeightColumn() {
        // Each edge states its own fraction, so the answer is the product along the line
        // rather than a power of one half — which it could not be if the weight were being
        // dropped on the way in.
        Map<String, Double> kinship = closure("""
                Family := [
                | parent | child | share |
                |--------|-------|-------|
                | A      | B     | 0.25  |
                | B      | C     | 0.5   |
                ];
                query { CLOSURE parent, child (Family) };
                """, "share");

        assertThat(kinship).containsEntry("A->B", 0.25d);
        assertThat(kinship).containsEntry("B->C", 0.5d);
        assertThat(kinship).containsEntry("A->C", 0.125d);
    }
}

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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.internal.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RelNodeExecutor — endless uniform draw with replacement (ROLL)")
final class RollExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    /** Runs the (bounded) query to completion. */
    private static List<String> faces(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        QueryStatement query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.map(r -> r.get("face").asDisplayString()).toList();
        }
    }

    /** Takes the first {@code n} rows of a possibly-unbounded query via the Java stream. */
    private static List<String> firstN(String src, long n) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        QueryStatement query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.limit(n).map(r -> r.get("face").asDisplayString()).toList();
        }
    }

    private static final String DIE =
            "Die := [| face |\n" +
            "         | 1 |\n" +
            "         | 2 |\n" +
            "         | 3 |\n" +
            "         | 4 |\n" +
            "         | 5 |\n" +
            "         | 6 |];\n";

    @Test
    @DisplayName("a seeded roll is the documented draw sequence")
    void seededMatchesDrawSequence() {
        // new Random(7).nextInt(6) + 1, five times, == [5, 3, 4, 5, 5];
        // this pins the executor to the sequence the reference page prints.
        assertThat(faces(DIE + "query { LIMIT 5 (ROLL SEED 7 (Die)) };"))
                .containsExactly("5", "3", "4", "5", "5");
    }

    @Test
    @DisplayName("draws are with replacement — more rolls than faces, repeats allowed")
    void withReplacement() {
        var rolls = faces(DIE + "query { LIMIT 20 (ROLL SEED 7 (Die)) };");
        assertThat(rolls).hasSize(20);
        assertThat(rolls).allSatisfy(f -> assertThat(f).isIn("1", "2", "3", "4", "5", "6"));
        // 20 draws from 6 faces must repeat some face.
        assertThat(rolls.stream().distinct().count()).isLessThan(20);
    }

    @Test
    @DisplayName("the stream is endless — it keeps drawing past the face count")
    void endless() {
        // A bare ROLL has no bound; taking 50 from a 6-face die proves it does not stop
        // at the faces (a permutation would yield 6).
        assertThat(firstN(DIE + "query { ROLL SEED 1 (Die) };", 50)).hasSize(50);
    }

    @RepeatedTest(5)
    @DisplayName("two runs with the same seed draw the identical sequence")
    void seededIsReproducible() {
        String q = DIE + "query { LIMIT 10 (ROLL SEED 2026 (Die)) };";
        assertThat(faces(q)).isEqualTo(faces(q));
    }

    @Test
    @DisplayName("two runs with different seeds may draw different sequences")
    void differentSeedsDiffer() {
        assertThat(faces(DIE + "query { LIMIT 20 (ROLL SEED 1 (Die)) };"))
                .isNotEqualTo(faces(DIE + "query { LIMIT 20 (ROLL SEED 2 (Die)) };"));
    }

    @Test
    @DisplayName("a single-face die always rolls that face")
    void singleFace() {
        var rolls = faces("Die := [| face |\n| 7 |];\nquery { LIMIT 4 (ROLL SEED 3 (Die)) };");
        assertThat(rolls).containsExactly("7", "7", "7", "7");
    }

    @Test
    @DisplayName("an empty face set yields no rows rather than an endless empty stream")
    void emptyFaces() {
        // Even without a LIMIT, an empty face set terminates — there is nothing to draw.
        assertThat(firstN("Die := [| face |];\nquery { ROLL SEED 1 (Die) };", 10)).isEmpty();
    }
}

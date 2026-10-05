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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

@DisplayName("RelNodeExecutor — goal-seek (SOLVE) end-to-end")
final class SolveExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    private static List<Row> collect(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        QueryStatement query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    private static Row withTotal(List<Row> rows, String total) {
        return rows.stream()
                .filter(r -> total.equals(r.get("total").asDisplayString()))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("fills the blank right-hand factor (rate = total - base) per row")
    void fillsRightHandUnknown() {
        // A left-outer join leaves `rate` NULL for the unmatched key (k=2).
        var rows = collect(
                "Totals := [| k | total | base |\n" +
                "            | 1 | 17    | 10   |\n" +
                "            | 2 | 50    | 20   |];\n" +
                "Rates  := [| rk | rate |\n" +
                "            | 1  | 7    |];\n" +
                "J := { Totals ⟕ Totals.k = Rates.rk Rates };\n" +
                "query { SOLVE total = base + rate (J) };");

        assertThat(rows).hasSize(2);
        // k=2: rate was NULL ⇒ solved to total - base = 50 - 20 = 30.
        assertThat(withTotal(rows, "50").get("rate").asDisplayString()).isEqualTo("30");
        // k=1: all columns present ⇒ row passes through unchanged (rate stays 7).
        assertThat(withTotal(rows, "17").get("rate").asDisplayString()).isEqualTo("7");
    }

    @Test
    @DisplayName("fills the bare left-hand side (total = principal * rate)")
    void fillsLeftHandUnknown() {
        var rows = collect(
                "Base  := [| k | principal | rate |\n" +
                "           | 1 | 4         | 5    |\n" +
                "           | 2 | 6         | 10   |];\n" +
                "Known := [| kk | total |\n" +
                "           | 1  | 99    |];\n" +
                "T := { Base ⟕ Base.k = Known.kk Known };\n" +
                "query { SOLVE total = principal * rate (T) };");

        assertThat(rows).hasSize(2);
        // k=2: total was NULL ⇒ solved to principal * rate = 6 * 10 = 60.
        Row solvedRow = rows.stream()
                .filter(r -> "6".equals(r.get("principal").asDisplayString()))
                .findFirst().orElseThrow();
        assertThat(solvedRow).hasValue("total", "60");
        // k=1: total present ⇒ unchanged (left as 99).
        assertThat(withTotal(rows, "99")).isNotNull();
    }

    @Test
    @DisplayName("solves a system of equations per row, passing the unsolvable through")
    void solvesASystem() {
        var rows = collect("""
                Pairs := [
                | id | total | diff | a | b |
                |----|-------|------|---|---|
                | 1  | 10    | 2    |   |   |
                | 2  |       |      | 7 | 3 |
                | 3  | 10    |      |   |   |
                ];
                query { SOLVE { total = a + b, diff = a - b } (Pairs) };
                """);

        assertThat(rows).hasSize(3);
        assertThat(byId(rows, "1")).hasValue("a", "6").hasValue("b", "4");
        assertThat(byId(rows, "2")).hasValue("total", "10").hasValue("diff", "4");
        // Three unknowns, two equations: left as it came in.
        assertThat(byId(rows, "3").get("a").isNull()).isTrue();
    }

    @Test
    @DisplayName("solves through a def, the call expanded into its body")
    void solvesThroughADef() {
        var rows = collect("""
                Rods := [
                | id | length | L0  | k      | temp |
                |----|--------|-----|--------|------|
                | 1  | 100.1  | 100 |        | 30   |
                | 2  |        | 100 | 0.0001 | 70   |
                ];
                def predicted(L0: NUMBER, k: NUMBER, T: NUMBER) : NUMBER := { L0 * (1 + k * (T - 20)) };
                query { SOLVE length = predicted(L0, k, temp) (Rods) };
                """);

        assertThat(byId(rows, "1")).hasValue("k", "0.0001");
        assertThat(byId(rows, "2")).hasValue("length", "100.5");
    }

    @Test
    @DisplayName("fits PER group by least squares, keeping input order")
    void fitsPerGroup() {
        var rows = collect("""
                Points := [
                | id | series | x | y | a | b |
                |----|--------|---|---|---|---|
                | 1  | s1     | 1 | 3 |   |   |
                | 2  | s2     | 0 | 1 |   |   |
                | 3  | s1     | 2 | 5 |   |   |
                | 4  | s2     | 1 | 3 |   |   |
                | 5  | s1     | 3 | 7 |   |   |
                | 6  | s2     | 2 | 4 |   |   |
                ];
                query { SOLVE y = a * x + b PER series (Points) };
                """);

        assertThat(rows).extracting(r -> r.get("id").asDisplayString())
                .containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(byId(rows, "5")).hasValue("a", "2").hasValue("b", "1");
        assertThat(byId(rows, "6")).hasValue("a", "1.5").hasValue("b", "1.1666666667");
    }

    @Test
    @DisplayName("solves a nonlinear equation iteratively, row by row")
    void solvesIteratively() {
        var rows = collect("""
                Squares := [
                | id | area | side |
                |----|------|------|
                | 1  | 2    |      |
                | 2  |      | 3    |
                ];
                query { SOLVE area = side * side (Squares) };
                """);

        assertThat(byId(rows, "1")).hasValue("side", "1.4142135624");
        assertThat(byId(rows, "2")).hasValue("area", "9");
    }

    @Test
    @DisplayName("honours MAX … ROUNDS, raising when the cap is reached")
    void honoursTheRoundCap() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> collect("""
                        Squares := [
                        | area | side |
                        |------|------|
                        | 1000000 |   |
                        ];
                        query { SOLVE area = side * side MAX 1 ROUNDS (Squares) };
                        """))
                .hasMessageContaining("no solution within 1 rounds");
    }

    @Test
    @DisplayName("honours WITHIN, stopping at a coarser tolerance")
    void honoursTheTolerance() {
        var rows = collect("""
                Squares := [
                | area | side |
                |------|------|
                | 2    |      |
                ];
                query { SOLVE area = side * side WITHIN 0.1 (Squares) };
                """);
        // Stopped as soon as a step fell under 0.1: close to √2, but not to ten digits.
        String side = rows.getFirst().get("side").asDisplayString();
        org.assertj.core.api.Assertions.assertThat(side).isNotEqualTo("1.4142135624");
        org.assertj.core.api.Assertions.assertThat(new java.math.BigDecimal(side)
                        .subtract(new java.math.BigDecimal("1.4142135624")).abs())
                .isLessThan(new java.math.BigDecimal("0.1"));
    }

    private static Row byId(List<Row> rows, String id) {
        return rows.stream()
                .filter(r -> id.equals(r.get("id").asDisplayString()))
                .findFirst().orElseThrow();
    }
}

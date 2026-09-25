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

@DisplayName("RelNodeExecutor — aggregates over operand expressions (E8)")
final class OperandAggregateExecutionTest extends ProcessorTestSupport {

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

    @Test
    @DisplayName("SUM over an arithmetic expression: SUM(price * qty)")
    void sumOverArithmetic() {
        var rows = collect(
                "Sales := [| price | qty |\n" +
                "           | 2 | 3 |\n" +
                "           | 5 | 4 |];\n" +
                "query { γ SUM(price * qty) → revenue (Sales) };");
        assertThat(rows).hasSize(1);
        // 2*3 + 5*4 = 26
        assertThat(rows.getFirst()).hasValue("revenue", "26");
    }

    @Test
    @DisplayName("MIN over a function call: MIN(Abs(delta))")
    void minOverFunctionCall() {
        var rows = collect(
                "D := [| delta |\n" +
                "       | -5 |\n" +
                "       | 3  |\n" +
                "       | -1 |];\n" +
                "query { γ MIN(Abs(delta)) → m (D) };");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).hasValue("m", "1");
    }

    @Test
    @DisplayName("grouped AVG over an expression keeps per-group results")
    void groupedAvgOverExpression() {
        var rows = collect(
                "Sales := [| region | price | qty |\n" +
                "           | a | 2 | 3 |\n" +     // 6
                "           | a | 4 | 1 |\n" +     // 4  → avg 5
                "           | b | 10 | 2 |];\n" +  // 20 → avg 20
                "query { γ region, AVG(price * qty) → avg_rev (Sales) };");
        assertThat(rows).hasSize(2);
        assertThat(rows.stream()
                .filter(r -> r.get("region").asDisplayString().equals("a"))
                .findFirst().orElseThrow().get("avg_rev").asDisplayString())
                .isEqualTo("5");
    }

    @Test
    @DisplayName("ARGMAX returns the yield expression's value at the extremal rank")
    void argmaxOverExpressions() {
        var rows = collect(
                "P := [| score | bonus |\n" +
                "       | 10 | 1 |\n" +
                "       | 25 | 2 |\n" +
                "       | 20 | 3 |];\n" +
                // rank by score, yield bonus * 10 of the max-score row (score 25 → bonus 2 → 20)
                "query { γ ARGMAX(score, bonus * 10) → best (P) };");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).hasValue("best", "20");
    }

    @Test
    @DisplayName("the synthesized output name for an unaliased expression aggregate is operator_expr")
    void unaliasedExpressionColumnName() {
        var rows = collect(
                "Sales := [| price | qty |\n" +
                "           | 2 | 3 |];\n" +
                "query { γ SUM(price * qty) (Sales) };");
        assertThat(rows.getFirst().columnNames()).containsExactly("sum_expr");
    }
}

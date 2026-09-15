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
import com.darkcollective.relix.processor.ExecutionContext;
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
}

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

@DisplayName("RelNodeExecutor — universal quantification (∀)")
final class UniversalExecutionTest extends ProcessorTestSupport {

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

    private static final String ORDERS =
            "Orders := [| customer_id | status  |\n" +
            "            | 1           | done    |\n" +
            "            | 1           | done    |\n" +
            "            | 2           | done    |\n" +
            "            | 2           | pending |\n" +
            "            | 3           | done    |];\n";

    @Test
    @DisplayName("keeps groups in which every row satisfies the predicate")
    void keepsAllSatisfyingGroups() {
        var rows = collect(ORDERS + "query { ∀ customer_id : status = \"done\" (Orders) };");

        // Group 1: all done → kept; group 2: has pending → dropped; group 3: done → kept.
        assertThat(rows).extracting(r -> r.get("customer_id").asDisplayString())
                .containsExactlyInAnyOrder("1", "3");
    }

    @Test
    @DisplayName("output schema is the grouping key only")
    void outputIsKeyOnly() {
        var rows = collect(ORDERS + "query { ∀ customer_id : status = \"done\" (Orders) };");

        assertThat(rows.getFirst().schema().columns()).hasSize(1);
        assertThat(rows.getFirst().schema().column("customer_id")).isPresent();
        assertThat(rows.getFirst().schema().column("status")).isEmpty();
    }

    @Test
    @DisplayName("strict NULL semantics: a group with an UNKNOWN-predicate row is dropped")
    void strictNullDisqualifies() {
        // cid 2 has no matching status row → status is NULL after the outer join,
        // so status = "done" is UNKNOWN for that row and the group is excluded.
        var rows = collect(
                "Cust := [| cid |\n         | 1 |\n         | 2 |];\n" +
                "Done := [| did | status |\n         | 1  | done   |];\n" +
                "J := { Cust ⟕ Cust.cid = Done.did Done };\n" +
                "query { ∀ cid : status = \"done\" (J) };");

        assertThat(rows).extracting(r -> r.get("cid").asDisplayString())
                .containsExactly("1");
    }

    @Test
    @DisplayName("supports multiple grouping keys")
    void multipleKeys() {
        var rows = collect(
                "T := [| region | dept | ok |\n" +
                "      | N       | A    | y  |\n" +
                "      | N       | A    | y  |\n" +
                "      | N       | B    | y  |\n" +
                "      | N       | B    | n  |];\n" +
                "query { ∀ region, dept : ok = \"y\" (T) };");

        // (N,A) all y → kept; (N,B) has an n → dropped.
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).hasValue("region", "N")
                .hasValue("dept", "A");
    }

    @Test
    @DisplayName("ASCII keyword FORALL behaves identically")
    void asciiKeyword() {
        var rows = collect(ORDERS + "query { FORALL customer_id : status = \"done\" (Orders) };");

        assertThat(rows).extracting(r -> r.get("customer_id").asDisplayString())
                .containsExactlyInAnyOrder("1", "3");
    }

    // ─── No-key whole-relation ∀ — the nullary truth relation (DEE/DUM) ────────

    @Test
    @DisplayName("no-key ∀ where every row satisfies P → DEE (one empty tuple)")
    void noKeyAllSatisfyEmitsDee() {
        // Every Orders row has customer_id > 0, so the whole-relation ∀ is true.
        var rows = collect(ORDERS + "query { ∀ : customer_id > 0 (Orders) };");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().schema().isEmpty()).isTrue();
        assertThat(rows.getFirst().schema().columns()).isEmpty();
    }

    @Test
    @DisplayName("no-key ∀ where some row fails P → DUM (no tuple)")
    void noKeySomeFailEmitsDum() {
        // Not every status is "done" (cid 2 has a pending) → whole-relation ∀ false.
        var rows = collect(ORDERS + "query { ∀ : status = \"done\" (Orders) };");

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("no-key ∀ over an empty input is vacuously true → DEE")
    void noKeyEmptyInputVacuouslyTrue() {
        // σ filters every row away; "every row satisfies P" is vacuously true.
        var rows = collect(ORDERS
                + "query { ∀ : status = \"done\" (σ customer_id > 99 (Orders)) };");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().schema().isEmpty()).isTrue();
    }
}

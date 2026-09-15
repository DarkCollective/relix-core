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
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

/** End-to-end tests for the WHY (ω) executor — building the nested provenance document (ADR-0018, #321). */
@DisplayName("WhyExecutor — reified lineage provenance")
final class WhyExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    private static List<Row> run(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    // ── document navigation helpers ──────────────────────────────────────────

    /** The {@code provenance} array of a WHY output row. */
    private static List<Value> derivations(Row row) {
        Value prov = row.get("provenance");
        assertThat(prov).isInstanceOf(ArrayValue.class);
        return ((ArrayValue) prov).elements();
    }

    /** The {@code variables} array of a single derivation (monomial) document. */
    private static List<Value> variables(Value derivation) {
        assertThat(derivation).isInstanceOf(StructValue.class);
        Value vars = ((StructValue) derivation).field("variables").orElseThrow();
        return ((ArrayValue) vars).elements();
    }

    private static String field(Value struct, String name) {
        return ((StructValue) struct).field(name).orElseThrow().asDisplayString();
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("base relation — each row carries one monomial naming its own source tuple")
    void baseRelation() {
        List<Row> rows = run("""
                Orders := [| id | region |
                           | 1  | west   |
                           | 2  | east   |];
                query { ω (Orders) };
                """);
        assertThat(rows).hasSize(2);
        Row first = rows.getFirst();
        // input columns preserved
        assertThat(first).hasValue("id", "1")
                .hasValue("region", "west");
        // provenance = [ {coefficient:1, variables:[ {relation:Orders, ordinal:1, columns:{...}} ]} ]
        List<Value> derivations = derivations(first);
        assertThat(derivations).hasSize(1);
        assertThat(field(derivations.getFirst(), "coefficient")).isEqualTo("1");
        List<Value> vars = variables(derivations.getFirst());
        assertThat(vars).hasSize(1);
        assertThat(field(vars.getFirst(), "relation")).isEqualTo("Orders");
        // captured columns address the exact source row
        Value columns = ((StructValue) vars.getFirst()).field("columns").orElseThrow();
        assertThat(field(columns, "id")).isEqualTo("1");
        assertThat(field(columns, "region")).isEqualTo("west");
    }

    @Test
    @DisplayName("join — a joint derivation is ONE monomial with TWO variables")
    void joinJointDerivation() {
        List<Row> rows = run("""
                Orders    := [| oid | cid |
                              | 1   | 7   |];
                Customers := [| cid | name |
                              | 7   | Ada  |];
                query { ω (Orders ⨝ Orders.cid = Customers.cid Customers) };
                """);
        assertThat(rows).hasSize(1);
        List<Value> derivations = derivations(rows.getFirst());
        assertThat(derivations).hasSize(1);                       // one joint derivation
        List<Value> vars = variables(derivations.getFirst());
        assertThat(vars).hasSize(2);                              // two contributing tuples
        assertThat(vars.stream().map(v -> field(v, "relation")).toList())
                .containsExactlyInAnyOrder("Orders", "Customers");
    }

    @Test
    @DisplayName("view input — lineage reaches the base tables, not the view's own rows")
    void viewInputThreadsToBaseTables() {
        List<Row> rows = run("""
                Orders    := [| oid | cid |
                              | 1   | 7   |];
                Customers := [| cid | name |
                              | 7   | Ada  |];
                Joined    := { Orders ⨝ Orders.cid = Customers.cid Customers };
                query { ω (Joined) };
                """);
        assertThat(rows).hasSize(1);
        List<Value> derivations = derivations(rows.getFirst());
        assertThat(derivations).hasSize(1);                       // still one joint derivation
        List<Value> vars = variables(derivations.getFirst());
        assertThat(vars).hasSize(2);                              // the view is not a source
        assertThat(vars.stream().map(v -> field(v, "relation")).toList())
                .containsExactlyInAnyOrder("Orders", "Customers");
    }

    @Test
    @DisplayName("ρ input — a rename is threaded, not read as an opaque source")
    void renameInputThreadsToBaseTables() {
        List<Row> rows = run("""
                Orders    := [| oid | cid |
                              | 1   | 7   |];
                Customers := [| cid | name |
                              | 7   | Ada  |];
                query { ω (ρ J (Orders ⨝ Orders.cid = Customers.cid Customers)) };
                """);
        assertThat(rows).hasSize(1);
        List<Value> vars = variables(derivations(rows.getFirst()).getFirst());
        assertThat(vars.stream().map(v -> field(v, "relation")).toList())
                .containsExactlyInAnyOrder("Orders", "Customers");
    }

    @Test
    @DisplayName("union duplicate — alternative derivations are MULTIPLE monomials")
    void unionAlternativeDerivations() {
        List<Row> rows = run("""
                Left  := [| x |
                          | 1 |];
                Right := [| x |
                          | 1 |];
                query { ω (Left ⊎ Right) };
                """);
        assertThat(rows).hasSize(1);                              // the value (1) merges to one row
        List<Value> derivations = derivations(rows.getFirst());
        assertThat(derivations).hasSize(2);                       // two alternative derivations
        // each derivation is a single contributing source tuple
        assertThat(derivations).allSatisfy(d -> assertThat(variables(d)).hasSize(1));
        assertThat(derivations.stream().flatMap(d -> variables(d).stream())
                .map(v -> field(v, "relation")).toList())
                .containsExactlyInAnyOrder("Left", "Right");
    }

    @Test
    @DisplayName("μ provenance round-trip — one row per derivation, identifying the source tuple")
    void unnestRoundTrip() {
        List<Row> rows = run("""
                Left  := [| x |
                          | 1 |];
                Right := [| x |
                          | 1 |];
                query { μ provenance (ω (Left ⊎ Right)) };
                """);
        // one original row × two derivations → two rows after the explode
        assertThat(rows).hasSize(2);
        // after μ, each row's provenance cell is a single derivation (monomial) document
        assertThat(rows).allSatisfy(r -> {
            Value derivation = r.get("provenance");
            assertThat(derivation).isInstanceOf(StructValue.class);
            assertThat(variables(derivation)).hasSize(1);
        });
        assertThat(rows.stream()
                .map(r -> field(variables(r.get("provenance")).getFirst(), "relation")).toList())
                .containsExactlyInAnyOrder("Left", "Right");
    }

    @Test
    @DisplayName("a non-truncated polynomial carries truncated=false on each derivation")
    void truncatedFlagFalse() {
        List<Row> rows = run("""
                Orders := [| id |
                           | 1  |];
                query { ω (Orders) };
                """);
        Value derivation = derivations(rows.getFirst()).getFirst();
        assertThat(field(derivation, "truncated")).isEqualTo("false");
    }

    @Test
    @DisplayName("a nested WHY is read as an opaque lift — the outer lineage names it as a source")
    void nestedWhyIsOpaque() {
        // The inner ω(Orders) is not a positive operator, so when the outer ω threads
        // lineage through the π it reads the inner WHY as an opaque base relation: the
        // outer lineage variable's source is the inner WHY. (The π drops the inner
        // provenance column, so the outer reification does not clash on the name.)
        List<Row> rows = run("""
                Orders := [| id |
                           | 1  |];
                query { ω (π id (ω (Orders))) };
                """);
        assertThat(rows).hasSize(1);
        List<Value> vars = variables(derivations(rows.getFirst()).getFirst());
        assertThat(vars).hasSize(1);
        assertThat(field(vars.getFirst(), "relation")).isEqualTo("Why");
    }

    @Test
    @DisplayName("a polynomial past MAX_MONOMIALS is capped and flags truncated=true")
    void truncatedFlagTrue() {
        // 300 identical rows merge to one tuple whose lineage is the ⊕ of 300 distinct
        // variables — past the 256-monomial cap, so it is truncated and flagged.
        StringBuilder src = new StringBuilder("Dup := [| x |\n");
        for (int i = 0; i < 300; i++) {
            src.append("        | 1 |\n");
        }
        src.append("];\nquery { ω (Dup) };\n");

        List<Row> rows = run(src.toString());
        assertThat(rows).hasSize(1);
        List<Value> derivations = derivations(rows.getFirst());
        assertThat(derivations).hasSize(256);                       // capped at MAX_MONOMIALS
        assertThat(derivations).allSatisfy(d ->
                assertThat(field(d, "truncated")).isEqualTo("true"));
    }
}

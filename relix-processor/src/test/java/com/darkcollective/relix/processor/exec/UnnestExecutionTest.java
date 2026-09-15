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
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.UnnestNode;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SchemaInference;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

@DisplayName("Unnest (μ) execution")
final class UnnestExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    /** A source R(id NUMBER, items ANY) whose schema needs no live DB (declared). */
    private static final String SOURCE =
            "source R from database { url: \"jdbc:none\", table: \"r\", schema: { id: NUMBER, items: ANY } };\n";

    private static ArrayValue arr(Value... elements) {
        return new ArrayValue(List.of(elements));
    }

    @Test
    @DisplayName("inner unnest emits one row per array element, carrying the other columns")
    void innerUnnestExplodes() {
        SemanticModel model = model(SOURCE + "query { μ items (R) };");
        DataSourceConnector connector = (name, schema) -> Stream.of(
                ArrayRow.of(schema, num(1), arr(str("a"), str("b"))),
                ArrayRow.of(schema, num(2), arr(str("c"))));

        List<Row> rows = run(model, connector,
                ((com.darkcollective.relix.lang.ast.ExpressionQueryTarget)
                        model.rootQueries().getFirst().target()).expression());

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("id").asDisplayString() + ":" + r.get("items").asDisplayString())
                .containsExactly("1:a", "1:b", "2:c");
    }

    @Test
    @DisplayName("inner drops empty/NULL arrays; outer keeps them as a NULL-bound row")
    void innerVsOuterOnEmptyAndNull() {
        SemanticModel model = model(SOURCE + "query { R };");
        DataSourceConnector connector = (name, schema) -> Stream.of(
                ArrayRow.of(schema, num(1), arr(str("x"), str("y"))),
                ArrayRow.of(schema, num(2), arr()),                 // empty array
                ArrayRow.of(schema, num(3), NullValue.INSTANCE));   // missing/NULL

        RelationNode r = rel("R");
        List<Row> inner = run(model, connector, unnest("items", false, r));
        List<Row> outer = run(model, connector, unnest("items", true, r));

        // inner: x, y from row 1; rows 2 and 3 dropped
        assertThat(inner).extracting(row -> row.get("id").asDisplayString() + ":" + row.get("items").asDisplayString())
                .containsExactly("1:x", "1:y");
        // outer: x, y, then a NULL-bound row for each of rows 2 and 3
        assertThat(outer).hasSize(4);
        assertThat(outer.get(2)).hasValue("id", "2");
        assertThat(outer.get(2).get("items").isNull()).isTrue();
        assertThat(outer.get(3).get("items").isNull()).isTrue();
    }

    @Test
    @DisplayName("WITH ORDINALITY appends a 1-based element-index column (restarting per row)")
    void withOrdinality() {
        SemanticModel model = model(SOURCE + "query { μ items WITH ORDINALITY pos (R) };");
        DataSourceConnector connector = (name, schema) -> Stream.of(
                ArrayRow.of(schema, num(1), arr(str("a"), str("b"), str("c"))),
                ArrayRow.of(schema, num(2), arr(str("d"))));

        List<Row> rows = run(model, connector,
                ((com.darkcollective.relix.lang.ast.ExpressionQueryTarget)
                        model.rootQueries().getFirst().target()).expression());

        assertThat(rows).extracting(r -> r.get("id").asDisplayString() + ":"
                        + r.get("items").asDisplayString() + ":" + r.get("pos").asDisplayString())
                .containsExactly("1:a:1", "1:b:2", "1:c:3", "2:d:1");
    }

    @Test
    @DisplayName("outer WITH ORDINALITY emits a NULL element with NULL ordinality for empty/missing arrays")
    void outerWithOrdinality() {
        SemanticModel model = model(SOURCE + "query { R };");
        DataSourceConnector connector = (name, schema) -> Stream.of(
                ArrayRow.of(schema, num(1), arr(str("x"), str("y"))),
                ArrayRow.of(schema, num(2), arr()));     // empty array

        RelNode node = unnest("items", true, Optional.of("pos"),
                rel("R"));
        List<Row> rows = run(model, connector, node);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).hasValue("pos", "1");
        assertThat(rows.get(1)).hasValue("pos", "2");
        // the empty-array row: NULL element, NULL ordinality
        assertThat(rows.get(2).get("items").isNull()).isTrue();
        assertThat(rows.get(2).get("pos").isNull()).isTrue();
    }

    @Test
    @DisplayName("NEST-001 law: μ g (γ k, COLLECT(x)→g (R)) executes identically to π k, (x→g) (R)")
    void roundTripLawHoldsAtRuntime() {
        // The nest/unnest round-trip equivalence the NEST-001 optimizer rule relies on
        // (ADR-0006): grouping x into an array per k and exploding it back reproduces
        // the original (k, x) rows, which is exactly what the rewritten projection emits.
        String src = "source R from database { url: \"jdbc:none\", table: \"r\","
                + " schema: { k: NUMBER, x: NUMBER } };\n";
        SemanticModel model = model(src + "query { R };");
        DataSourceConnector connector = (name, schema) -> Stream.of(
                ArrayRow.of(schema, num(1), num(10)),
                ArrayRow.of(schema, num(1), num(20)),
                ArrayRow.of(schema, num(2), num(30)));

        RelationNode r = rel("R");
        RelNode nestThenUnnest = new com.darkcollective.relix.ast.UnnestNode("g",
                new com.darkcollective.relix.ast.AggregationNode(List.of("k"),
                        List.of(com.darkcollective.relix.ast.AggregateFunction.aliased(
                                com.darkcollective.relix.ast.AggregateOperator.COLLECT,
                                new com.darkcollective.relix.ast.AttributeOperand("x"), "g")),
                        r));
        RelNode rewrite = new com.darkcollective.relix.ast.ProjectionNode(List.of(
                new com.darkcollective.relix.ast.ProjectedAttribute(
                        new com.darkcollective.relix.ast.AttributeOperand("k"), Optional.empty()),
                new com.darkcollective.relix.ast.ProjectedAttribute(
                        new com.darkcollective.relix.ast.AttributeOperand("x"), Optional.of("g"))),
                r);

        List<String> original = run(model, connector, nestThenUnnest).stream()
                .map(row -> row.get("k").asDisplayString() + ":" + row.get("g").asDisplayString())
                .toList();
        List<String> optimized = run(model, connector, rewrite).stream()
                .map(row -> row.get("k").asDisplayString() + ":" + row.get("g").asDisplayString())
                .toList();

        assertThat(original).containsExactlyInAnyOrderElementsOf(optimized);
        assertThat(original).containsExactlyInAnyOrder("1:10", "1:20", "2:30");
    }

    /** Plans and executes {@code node} against a custom connector, collecting the rows. */
    private static List<Row> run(SemanticModel model, DataSourceConnector connector, RelNode node) {
        SchemaAnnotations annotations =
                SchemaInference.annotate(model.symbolTable(), node, model.nodeSchemas(),
                        model.functions());
        ExecutionContext ctx = ExecutionContext.of(model, annotations, connector);
        try (Stream<Row> stream = EXECUTOR.execute(node, ctx)) {
            return stream.toList();
        }
    }
}

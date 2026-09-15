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

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.processor.eval.OperandEvaluator;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RelNodeExecutor — Sort and Aggregation operators")
final class SortAndAggregationTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    private static List<Row> collect(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    // ── SortNode ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SortNode (τ / SORT)")
    class Sort {

        @Test
        @DisplayName("sorts numbers ascending")
        void numericAsc() {
            var rows = collect(
                    "T := [| id | score |\n" +
                    "       | 3  | 30    |\n" +
                    "       | 1  | 10    |\n" +
                    "       | 2  | 20    |];\n" +
                    "query { τ score ASC (T) };");

            assertThat(rows).extracting(r -> r.get("score").asDisplayString())
                    .containsExactly("10", "20", "30");
        }

        @Test
        @DisplayName("sorts numbers descending")
        void numericDesc() {
            var rows = collect(
                    "T := [| id | score |\n" +
                    "       | 1  | 10    |\n" +
                    "       | 2  | 30    |\n" +
                    "       | 3  | 20    |];\n" +
                    "query { τ score DESC (T) };");

            assertThat(rows).extracting(r -> r.get("score").asDisplayString())
                    .containsExactly("30", "20", "10");
        }

        @Test
        @DisplayName("sorts strings alphabetically ascending")
        void stringAsc() {
            var rows = collect(
                    "T := [| name |\n" +
                    "       | Carol |\n" +
                    "       | Alice |\n" +
                    "       | Bob   |];\n" +
                    "query { τ name ASC (T) };");

            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactly("Alice", "Bob", "Carol");
        }

        @Test
        @DisplayName("sorts strings descending")
        void stringDesc() {
            var rows = collect(
                    "T := [| name |\n" +
                    "       | Alice |\n" +
                    "       | Carol |\n" +
                    "       | Bob   |];\n" +
                    "query { τ name DESC (T) };");

            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactly("Carol", "Bob", "Alice");
        }

        @Test
        @DisplayName("multi-column sort: primary ASC, secondary DESC")
        void multiColumn() {
            var rows = collect(
                    "T := [| dept | salary |\n" +
                    "       | eng  | 80     |\n" +
                    "       | mkt  | 60     |\n" +
                    "       | eng  | 90     |\n" +
                    "       | mkt  | 70     |];\n" +
                    "query { τ dept ASC, salary DESC (T) };");

            // eng group descending salary, then mkt group descending salary
            assertThat(rows).extracting(r -> r.get("dept").asDisplayString() + "/"
                    + r.get("salary").asDisplayString())
                    .containsExactly("eng/90", "eng/80", "mkt/70", "mkt/60");
        }

        @Test
        @DisplayName("sort on empty table returns empty")
        void sortEmpty() {
            var rows = collect(
                    "T := [| id |];\n" +
                    "query { τ id ASC (T) };");
            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("sort preserves duplicates (bag semantics)")
        void preservesDuplicates() {
            var rows = collect(
                    "T := [| id | v |\n" +
                    "       | 1  | 5 |\n" +
                    "       | 2  | 5 |\n" +
                    "       | 3  | 5 |];\n" +
                    "query { τ v ASC (T) };");
            assertThat(rows).hasSize(3);
        }

        @Test
        @DisplayName("sort followed by limit returns top-N rows")
        void sortThenLimit() {
            var rows = collect(
                    "T := [| id | score |\n" +
                    "       | 1  | 50    |\n" +
                    "       | 2  | 10    |\n" +
                    "       | 3  | 90    |\n" +
                    "       | 4  | 30    |];\n" +
                    "query { λ 2 (τ score DESC (T)) };");

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("score", "90");
            assertThat(rows.get(1)).hasValue("score", "50");
        }
    }

    // ── AggregationNode ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("AggregationNode (γ / GROUP)")
    class Aggregation {

        @Test
        // Not COUNT(*) — there is no such form; `id` is simply non-NULL here, so
        // counting it happens to equal the row count.  See {@link AggregateNulls}.
        @DisplayName("COUNT over a non-NULL column counts every row")
        void countAll() {
            var rows = collect(
                    "T := [| id |\n" +
                    "       | 1  |\n" +
                    "       | 2  |\n" +
                    "       | 3  |];\n" +
                    "query { γ COUNT(id) → n (T) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("n", "3");
        }

        @Test
        @DisplayName("SUM groups by department")
        void sumByGroup() {
            var rows = collect(
                    "Sales := [| dept | amount |\n" +
                    "           | eng  | 100    |\n" +
                    "           | mkt  | 200    |\n" +
                    "           | eng  | 150    |\n" +
                    "           | mkt  | 50     |];\n" +
                    "query { γ dept, SUM(amount) → total (Sales) };");

            assertThat(rows).hasSize(2);
            var byDept = rows.stream().collect(
                    java.util.stream.Collectors.toMap(
                            r -> r.get("dept").asDisplayString(),
                            r -> r.get("total").asDisplayString()));
            assertThat(byDept).containsEntry("eng", "250")
                               .containsEntry("mkt", "250");
        }

        @Test
        @DisplayName("AVG produces correct average")
        void average() {
            var rows = collect(
                    "T := [| id | val |\n" +
                    "       | 1  | 10  |\n" +
                    "       | 2  | 20  |\n" +
                    "       | 3  | 30  |];\n" +
                    "query { γ AVG(val) → avg_val (T) };");

            assertThat(rows).hasSize(1);
            // 60 / 3 = 20
            assertThat(rows.get(0)).hasValue("avg_val", "20");
        }

        @Test
        @DisplayName("MIN and MAX per group")
        void minMax() {
            var rows = collect(
                    "T := [| dept | score |\n" +
                    "       | a    | 10    |\n" +
                    "       | a    | 50    |\n" +
                    "       | a    | 30    |];\n" +
                    "query { γ dept, MIN(score) → lo, MAX(score) → hi (T) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("lo", "10")
                    .hasValue("hi", "50");
        }

        @Test
        @DisplayName("COLLECT gathers a group's values into an array (NEST)")
        void collectPerGroup() {
            var rows = collect(
                    "Orders := [| customer | amount |\n" +
                    "            | a        | 10     |\n" +
                    "            | b        | 99     |\n" +
                    "            | a        | 20     |\n" +
                    "            | a        | 30     |];\n" +
                    "query { γ customer, COLLECT(amount) → amounts (Orders) };");

            assertThat(rows).hasSize(2);
            var byCustomer = rows.stream().collect(java.util.stream.Collectors.toMap(
                    r -> r.get("customer").asDisplayString(),
                    r -> r.get("amounts")));
            assertThat(byCustomer.get("a"))
                    .isInstanceOf(com.darkcollective.relix.value.ArrayValue.class);
            assertThat(byCustomer.get("a").asDisplayString()).isEqualTo("[10, 20, 30]");
            assertThat(byCustomer.get("b").asDisplayString()).isEqualTo("[99]");
        }

        @Test
        @DisplayName("COLLECT composes with a scalar aggregate in the same γ")
        void collectAlongsideScalar() {
            var rows = collect(
                    "Orders := [| customer | amount |\n" +
                    "            | a        | 10     |\n" +
                    "            | a        | 20     |];\n" +
                    "query { γ customer, COUNT(amount) → n, COLLECT(amount) → amounts (Orders) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("n", "2")
                    .hasValue("amounts", "[10, 20]");
        }

        @Test
        @DisplayName("scalar COLLECT (no grouping) gathers every row")
        void collectGlobal() {
            var rows = collect(
                    "T := [| v |\n" +
                    "       | 1 |\n" +
                    "       | 2 |\n" +
                    "       | 3 |];\n" +
                    "query { γ COLLECT(v) → all (T) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("all", "[1, 2, 3]");
        }

        @Test
        @DisplayName("COUNT on empty input returns one row with zero")
        void countEmpty() {
            var rows = collect(
                    "T := [| id |];\n" +
                    "query { γ COUNT(id) → n (T) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("n", "0");
        }

        @Test
        @DisplayName("GROUP BY on empty input returns no rows")
        void groupByEmpty() {
            var rows = collect(
                    "T := [| dept | val |];\n" +
                    "query { γ dept, SUM(val) → total (T) };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("multiple groups produce one row each")
        void multipleGroups() {
            var rows = collect(
                    "T := [| cat | v |\n" +
                    "       | x   | 1 |\n" +
                    "       | y   | 2 |\n" +
                    "       | z   | 3 |\n" +
                    "       | x   | 4 |\n" +
                    "       | y   | 5 |];\n" +
                    "query { γ cat, COUNT(v) → n (T) };");

            assertThat(rows).hasSize(3);
        }

        @Test
        @DisplayName("aggregation followed by selection filters groups")
        void aggregateThenSelect() {
            var rows = collect(
                    "Orders := [| dept | amount |\n" +
                    "            | eng  | 100    |\n" +
                    "            | mkt  | 50     |\n" +
                    "            | eng  | 200    |\n" +
                    "            | ops  | 80     |];\n" +
                    "Totals := { γ dept, SUM(amount) → total (Orders) };\n" +
                    "query { σ total > 100 (Totals) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("dept", "eng")
                    .hasValue("total", "300");
        }

        @Test
        @DisplayName("sort then aggregate: group ordering reflects input order")
        void sortThenAggregate() {
            // After sorting by name, groups appear in alphabetical order
            var rows = collect(
                    "T := [| name | score |\n" +
                    "       | Bob  | 20    |\n" +
                    "       | Alice | 30   |\n" +
                    "       | Bob  | 10    |];\n" +
                    "Sorted := { τ name ASC (T) };\n" +
                    "query { γ name, SUM(score) → total (Sorted) };");

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).hasValue("name", "Alice");
            assertThat(rows.get(1)).hasValue("name", "Bob");
        }
    }

    // ── ARGMAX / ARGMIN ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ARGMAX / ARGMIN (groupwise extremum yielding a companion column)")
    class Argmax {

        private static final String ORDERS =
                "Orders := [| oid | cust | amount |\n" +
                "            | 1   | 10   | 100    |\n" +
                "            | 2   | 10   | 250    |\n" +
                "            | 3   | 20   | 80     |\n" +
                "            | 4   | 20   | 300    |];\n";

        @Test
        @DisplayName("ARGMAX yields the companion column at the per-group maximum")
        void argmaxPerGroup() {
            var rows = collect(ORDERS + "query { γ cust, ARGMAX(amount, oid) → top (Orders) };");
            assertThat(rows)
                    .extracting(r -> r.get("cust").asDisplayString() + ":" + r.get("top").asDisplayString())
                    .containsExactlyInAnyOrder("10:2", "20:4");
        }

        @Test
        @DisplayName("ARGMIN yields the companion column at the per-group minimum")
        void argminPerGroup() {
            var rows = collect(ORDERS + "query { γ cust, ARGMIN(amount, oid) → low (Orders) };");
            assertThat(rows)
                    .extracting(r -> r.get("cust").asDisplayString() + ":" + r.get("low").asDisplayString())
                    .containsExactlyInAnyOrder("10:1", "20:3");
        }

        @Test
        @DisplayName("ties resolve to the first row in input order")
        void tieKeepsFirstRow() {
            var rows = collect(
                    "T := [| oid | cust | amount |\n" +
                    "       | 1   | 10   | 50     |\n" +
                    "       | 2   | 10   | 50     |];\n" +
                    "query { γ cust, ARGMAX(amount, oid) → top (T) };");
            assertThat(rows).singleElement()
                    .extracting(r -> r.get("top").asDisplayString()).isEqualTo("1");
        }

        // Exercised directly against the reducer so the empty-group case (which no
        // inline relation can produce — an empty group emits no row) is covered
        // alongside the null-rank ones.

        @Test
        @DisplayName("skips rows with a null rank, and yields null for an empty / all-null group")
        void nullAndEmptyHandling() {
            Schema schema = new Schema(List.of(
                    new ColumnDefinition("rank", ScalarType.NUMBER),
                    new ColumnDefinition("yield", ScalarType.STRING)));
            AggregateFunction argmax = AggregateFunction.arg(AggregateOperator.ARGMAX, "rank", "yield");
            AggregateFunction argmin = AggregateFunction.arg(AggregateOperator.ARGMIN, "rank", "yield");
            OperandEvaluator eval = new OperandEvaluator();

            // Two non-null rows: the later, larger rank wins (exercises the
            // compare branch where a running best already exists).
            List<Row> two = List.of(
                    ArrayRow.of(schema, NumberValue.of("10"), new StringValue("a")),
                    ArrayRow.of(schema, NumberValue.of("20"), new StringValue("b")));
            assertThat(AggregateExecutor.computeAggregate(argmax, two, eval).asDisplayString())
                    .isEqualTo("b");

            // ARGMIN: a later, smaller-rank row replaces the running best.
            List<Row> descending = List.of(
                    ArrayRow.of(schema, NumberValue.of("20"), new StringValue("a")),
                    ArrayRow.of(schema, NumberValue.of("10"), new StringValue("b")));
            assertThat(AggregateExecutor.computeAggregate(argmin, descending, eval).asDisplayString())
                    .isEqualTo("b");

            // A null-rank row is skipped, so the non-null row wins.
            List<Row> mixed = List.of(
                    ArrayRow.of(schema, NumberValue.of("10"), new StringValue("a")),
                    ArrayRow.of(schema, NullValue.INSTANCE,   new StringValue("b")));
            assertThat(AggregateExecutor.computeAggregate(argmax, mixed, eval).asDisplayString())
                    .isEqualTo("a");

            // A group whose ranks are all null yields null.
            List<Row> allNull = List.of(ArrayRow.of(schema, NullValue.INSTANCE, new StringValue("x")));
            assertThat(AggregateExecutor.computeAggregate(argmax, allNull, eval).isNull()).isTrue();

            // An empty group yields null.
            assertThat(AggregateExecutor.computeAggregate(argmax, List.of(), eval).isNull()).isTrue();
        }

        @Test
        @DisplayName("an aggregate no library offers names itself, and says the library is missing")
        void unknownAggregate() {
            // The likeliest cause is a repackaged jar in which ServiceLoader found
            // nothing, which without the hint reads exactly like a typo.
            AggregateFunction sum = AggregateFunction.simple(AggregateOperator.SUM, "amount");
            OperandEvaluator withoutLibraries =
                    new OperandEvaluator(null, FunctionCatalog.empty());

            assertThatThrownBy(() ->
                    AggregateExecutor.computeAggregate(sum, List.of(), withoutLibraries))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Unknown aggregate: SUM")
                    .hasMessageContaining("no function library is installed");
        }

        @Test
        @DisplayName("a NULL yield is yielded, since only the rank decides eligibility")
        void nullYieldIsKept() {
            // ARGMAX declares that it does not skip NULLs, precisely so this row is a
            // candidate: its rank is a number, and what it holds is what it holds.
            Schema schema = new Schema(List.of(
                    new ColumnDefinition("rank", ScalarType.NUMBER),
                    new ColumnDefinition("yield", ScalarType.STRING)));
            AggregateFunction argmax = AggregateFunction.arg(AggregateOperator.ARGMAX, "rank", "yield");

            List<Row> rows = List.of(
                    ArrayRow.of(schema, NumberValue.of("10"), new StringValue("a")),
                    ArrayRow.of(schema, NumberValue.of("20"), NullValue.INSTANCE));

            assertThat(AggregateExecutor.computeAggregate(argmax, rows, new OperandEvaluator())
                    .isNull()).isTrue();
        }
    }

    // ── NULL handling ─────────────────────────────────────────────────────────

    /**
     * Aggregates follow SQL NULL semantics so that in-engine evaluation and a γ
     * pushed down to a SQL backend agree on the same input.  Before this was
     * fixed, {@code COUNT(expr)} counted rows (ignoring its argument) and
     * {@code SUM} over an all-NULL group returned 0 — both of which silently
     * disagreed with the {@code COUNT(col)}/{@code SUM(col)} the SQL pushdown
     * emits, so the same script gave different answers on inline versus
     * JDBC-backed data.
     */
    @Nested
    @DisplayName("aggregate NULL handling (SQL semantics)")
    class AggregateNulls {

        /** A group with some NULLs, and a group whose values are entirely NULL. */
        private static final String MIXED =
                "T := [| g | v    |\n" +
                "       | a | 10   |\n" +
                "       | a | NULL |\n" +
                "       | b | NULL |\n" +
                "       | b | NULL |];\n";

        private static java.util.Map<String, Row> byGroup(List<Row> rows) {
            return rows.stream().collect(java.util.stream.Collectors.toMap(
                    r -> r.get("g").asDisplayString(), r -> r));
        }

        @Test
        @DisplayName("COUNT counts non-NULL values, not rows")
        void countSkipsNulls() {
            var byGroup = byGroup(collect(MIXED + "query { γ g, COUNT(v) → n (T) };"));

            // group a holds [10, NULL] → 1;  group b holds [NULL, NULL] → 0.
            assertThat(byGroup.get("a").get("n").asDisplayString()).isEqualTo("1");
            assertThat(byGroup.get("b").get("n").asDisplayString()).isEqualTo("0");
        }

        @Test
        @DisplayName("SUM skips NULLs, and yields NULL for an all-NULL group")
        void sumSkipsNullsAndYieldsNullForAllNullGroup() {
            var byGroup = byGroup(collect(MIXED + "query { γ g, SUM(v) → total (T) };"));

            assertThat(byGroup.get("a").get("total").asDisplayString()).isEqualTo("10");
            // The key case: NULL, not 0 — 0 is indistinguishable from a real sum of zero.
            assertThat(byGroup.get("b").get("total").isNull()).isTrue();
        }

        @Test
        @DisplayName("AVG / MIN / MAX yield NULL for an all-NULL group")
        void avgMinMaxYieldNullForAllNullGroup() {
            var byGroup = byGroup(collect(
                    MIXED + "query { γ g, AVG(v) → mean, MIN(v) → lo, MAX(v) → hi (T) };"));

            assertThat(byGroup.get("a").get("mean").asDisplayString()).isEqualTo("10");
            assertThat(byGroup.get("b").get("mean").isNull()).isTrue();
            assertThat(byGroup.get("b").get("lo").isNull()).isTrue();
            assertThat(byGroup.get("b").get("hi").isNull()).isTrue();
        }

        @Test
        @DisplayName("scalar (ungrouped) COUNT and SUM apply the same rules")
        void scalarAggregatesSkipNulls() {
            var rows = collect(
                    "T := [| v    |\n" +
                    "       | NULL |\n" +
                    "       | NULL |];\n" +
                    "query { γ COUNT(v) → n, SUM(v) → total (T) };");

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("n", "0");
            assertThat(rows.get(0).get("total").isNull()).isTrue();
        }

        @Test
        @DisplayName("COUNT(*) counts every row — the SQL spelling for a row count")
        void countStarCountsRows() {
            // COUNT(*) parses to COUNT(1); a literal is never NULL, so it counts
            // rows regardless of the data — the complement of COUNT(v) above.
            var byGroup = byGroup(collect(
                    MIXED + "query { γ g, COUNT(*) → rows, COUNT(v) → non_null (T) };"));

            assertThat(byGroup.get("a").get("rows").asDisplayString()).isEqualTo("2");
            assertThat(byGroup.get("b").get("rows").asDisplayString()).isEqualTo("2");
            assertThat(byGroup.get("a").get("non_null").asDisplayString()).isEqualTo("1");
            assertThat(byGroup.get("b").get("non_null").asDisplayString()).isEqualTo("0");
        }

        @Test
        @DisplayName("COLLECT is the exception — it keeps NULLs, matching array_agg / $push")
        void collectKeepsNulls() {
            var byGroup = byGroup(collect(MIXED + "query { γ g, COLLECT(v) → vs (T) };"));

            assertThat(byGroup.get("a").get("vs").asDisplayString()).isEqualTo("[10, NULL]");
            assertThat(byGroup.get("b").get("vs").asDisplayString()).isEqualTo("[NULL, NULL]");
        }
    }
}

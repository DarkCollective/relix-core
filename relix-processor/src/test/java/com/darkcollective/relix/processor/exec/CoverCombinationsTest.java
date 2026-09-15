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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Meta-test: uses Relix's own {@code COVER} operator (ADR-0012) to generate the minimal
 * set of all-pairs (t=2) parameter combinations for each test space, then runs one JUnit
 * parameterised test per COVER-selected row.
 *
 * <p>This is a demonstration of "dog-fooding" — the engine validates itself by using its
 * own {@code COVER} operator to determine which combinations to test.  Adding a new value
 * to an inline parameter domain automatically expands coverage without any manual
 * bookkeeping.
 *
 * <p>Four parameter spaces, each with three or more dimensions:
 * <ol>
 *   <li><b>Unary transforms</b> × value types × sort direction
 *       (5 × 2 × 2 = 20 candidates → ≈ 6 via COVER 2)</li>
 *   <li><b>Comparison predicates</b> × value types × logical composition
 *       (6 × 2 × 3 = 36 candidates → ≈ 9 via COVER 2)</li>
 *   <li><b>Set operators</b> × row overlap × empty right side
 *       (3 × 3 × 2 = 18 candidates → ≈ 6 via COVER 2)</li>
 *   <li><b>Aggregation functions</b> × grouping × additional aggregate
 *       (4 × 2 × 2 = 16 candidates → ≈ 6 via COVER 2)</li>
 * </ol>
 */
@DisplayName("COVER-driven pairwise test combinations — dog-fooding COVER to test Relix")
final class CoverCombinationsTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement q) {
        return switch (q.target()) {
            case NamedQueryTarget   n -> rel(n.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    /** Executes {@code src} and collects the first root query's output rows. */
    private static List<Row> collect(String src) {
        SemanticModel m = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(m);
        QueryStatement q = m.rootQueries().getFirst();
        try (Stream<Row> s = EXECUTOR.execute(queryNode(q), ctx)) {
            return s.toList();
        }
    }

    /**
     * Runs a COVER script and projects each output row into a JUnit {@link Arguments} tuple.
     *
     * @param coverScript the {@code .relix} script; must contain exactly one {@code query}
     * @param columns     column names to extract (in order) from each output row
     */
    private static Stream<Arguments> coverArgs(String coverScript, String... columns) {
        List<Row> rows = collect(coverScript);
        return rows.stream().map(row -> {
            Object[] args = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                args[i] = row.get(columns[i]).asDisplayString();
            }
            return Arguments.of(args);
        });
    }

    // =========================================================================
    // Space 1 — Unary transforms × value types × sort direction
    //
    // Full space: 5 transforms × 2 value types × 2 sort directions = 20
    // COVER 2 selects a near-minimal subset (≈ 6) covering every (transform, type)
    // pair, every (transform, sort_dir) pair, and every (type, sort_dir) pair.
    // The sort_dir dimension is only exercised by the 'sort' transform; for all
    // others it is a don't-care column that COVER assigns for free.
    // =========================================================================

    @Nested
    @DisplayName("1. Unary transforms × value types × sort direction")
    class UnaryTransforms {

        static Stream<Arguments> args() {
            return coverArgs("""
                    Transform := [| transform   |
                                   | projection |
                                   | selection  |
                                   | limit      |
                                   | distinct   |
                                   | sort       |];
                    DataType  := [| data_type |
                                   | number   |
                                   | string   |];
                    SortDir   := [| sort_dir |
                                   | asc     |
                                   | desc    |];
                    query { COVER 2 (Transform × DataType × SortDir) };
                    """,
                    "transform", "data_type", "sort_dir");
        }

        @ParameterizedTest(name = "[{0}] on {1} data (sort_dir={2})")
        @MethodSource("args")
        void appliesWithoutError(String transform, String dataType, String sortDir) {
            String relDef;
            String lit;
            if ("number".equals(dataType)) {
                relDef = "R := [| v |\n       | 3 |\n       | 1 |\n       | 2 |\n       | 1 |];\n";
                lit = "1";
            } else {
                relDef = "R := [| v |\n       | c |\n       | a |\n       | b |\n       | a |];\n";
                lit = "\"a\"";
            }

            String dir = sortDir.toUpperCase();
            String expr = switch (transform) {
                case "projection" -> "π v (R)";
                case "selection"  -> "σ v = " + lit + " (R)";
                case "limit"      -> "λ 3 (R)";
                case "distinct"   -> "δ (R)";
                case "sort"       -> "τ v " + dir + " (R)";
                default           -> throw new IllegalArgumentException(transform);
            };

            var rows = collect(relDef + "query { " + expr + " };\n");
            // All transforms over a 4-row input with a satisfiable predicate yield ≥ 1 row.
            assertThat(rows).isNotEmpty();
        }
    }

    // =========================================================================
    // Space 2 — Comparison predicates × value types × logical composition
    //
    // Full space: 6 ops × 2 types × 3 logical connectors = 36
    // COVER 2 selects ≈ 9 rows.  Every branch below is constructed so that
    // at least one input row satisfies the predicate, guaranteeing a non-empty
    // result regardless of which combination COVER selects.
    // =========================================================================

    @Nested
    @DisplayName("2. Comparison predicates × value types × logical composition")
    class ComparisonPredicates {

        static Stream<Arguments> args() {
            return coverArgs("""
                    CompOp   := [| comp_op |
                                  | eq     |
                                  | ne     |
                                  | lt     |
                                  | le     |
                                  | gt     |
                                  | ge     |];
                    DataType := [| pred_type |
                                  | number   |
                                  | string   |];
                    Logic    := [| logic   |
                                  | single |
                                  | and    |
                                  | or     |];
                    query { COVER 2 (CompOp × DataType × Logic) };
                    """,
                    "comp_op", "pred_type", "logic");
        }

        @ParameterizedTest(name = "[{0}] on {1} ({2})")
        @MethodSource("args")
        void filterProducesNonEmptyResult(String compOp, String predType, String logic) {
            String relDef;
            String lo, mid, hi;
            if ("number".equals(predType)) {
                relDef = "R := [| v |\n       | 1 |\n       | 2 |\n       | 3 |];\n";
                lo = "1"; mid = "2"; hi = "3";
            } else {
                relDef = "R := [| v |\n       | a |\n       | b |\n       | c |];\n";
                lo = "\"a\""; mid = "\"b\""; hi = "\"c\"";
            }

            // Each primary predicate is satisfied by ≥ 1 row in {lo, mid, hi}.
            String primary = switch (compOp) {
                case "eq" -> "v = " + mid;       // matches mid
                case "ne" -> "v != " + lo;        // matches mid and hi
                case "lt" -> "v < " + hi;         // matches lo and mid
                case "le" -> "v <= " + mid;       // matches lo and mid
                case "gt" -> "v > " + lo;         // matches mid and hi
                case "ge" -> "v >= " + mid;       // matches mid and hi
                default   -> throw new IllegalArgumentException(compOp);
            };

            // Compose with AND or OR; both compositions retain ≥ 1 matching row.
            String pred = switch (logic) {
                case "single" -> primary;
                case "and"    -> primary + " AND v != " + lo;   // further narrows, still ≥ 1
                case "or"     -> primary + " OR v = " + lo;     // widens, always ≥ 1
                default       -> throw new IllegalArgumentException(logic);
            };

            var rows = collect(relDef + "query { σ " + pred + " (R) };\n");
            assertThat(rows).isNotEmpty();
        }
    }

    // =========================================================================
    // Space 3 — Set operators × row overlap × empty right side
    //
    // Full space: 3 ops × 3 overlap shapes × 2 empty flags = 18
    // COVER 2 selects ≈ 6.  Exact result sizes are spot-checked for the
    // deterministic (easy-to-prove) cases.
    // =========================================================================

    @Nested
    @DisplayName("3. Set operators × row overlap × empty right side")
    class SetOperators {

        static Stream<Arguments> args() {
            return coverArgs("""
                    SetOp      := [| set_op        |
                                    | union        |
                                    | intersection |
                                    | difference   |];
                    Overlap    := [| overlap  |
                                    | none    |
                                    | partial |
                                    | full    |];
                    EmptyRight := [| empty_right |
                                    | yes        |
                                    | no         |];
                    query { COVER 2 (SetOp × Overlap × EmptyRight) };
                    """,
                    "set_op", "overlap", "empty_right");
        }

        @ParameterizedTest(name = "[{0}] overlap={1} empty_right={2}")
        @MethodSource("args")
        void setOpProducesCorrectResult(String setOp, String overlap, String emptyRight) {
            // Left is always L = {1, 2}
            String lDef = "L := [| k |\n       | 1 |\n       | 2 |];\n";
            boolean emptyR = "yes".equals(emptyRight);

            // Right relation varies by overlap shape (and may be empty)
            String rDef = switch (overlap) {
                case "none"    -> emptyR ? "R := [| k |];\n"
                                         : "R := [| k |\n       | 3 |\n       | 4 |];\n";
                case "partial" -> emptyR ? "R := [| k |];\n"
                                         : "R := [| k |\n       | 2 |\n       | 3 |];\n";
                case "full"    -> emptyR ? "R := [| k |];\n"
                                         : "R := [| k |\n       | 1 |\n       | 2 |];\n";
                default        -> throw new IllegalArgumentException(overlap);
            };

            String opExpr = switch (setOp) {
                case "union"        -> "L ∪ R";
                case "intersection" -> "L ∩ R";
                case "difference"   -> "L − R";
                default             -> throw new IllegalArgumentException(setOp);
            };

            var rows = collect(lDef + rDef + "query { " + opExpr + " };\n");

            // Spot-check results for cases with determinate sizes
            switch (setOp) {
                case "union" -> {
                    // L ∪ R ⊇ L, so always non-empty (L = {1, 2})
                    assertThat(rows).isNotEmpty();
                    // L ∪ ∅ = L regardless of the overlap label
                    if (emptyR) assertThat(rows).hasSize(2);
                }
                case "intersection" -> {
                    if (emptyR || "none".equals(overlap)) {
                        assertThat(rows).isEmpty();    // L ∩ ∅ = ∅; disjoint sets → ∅
                    } else if ("full".equals(overlap)) {
                        assertThat(rows).hasSize(2);   // L ∩ L = L
                    }
                }
                case "difference" -> {
                    if (emptyR) {
                        assertThat(rows).hasSize(2);   // L − ∅ = L
                    } else if ("full".equals(overlap)) {
                        assertThat(rows).isEmpty();    // L − L = ∅
                    } else if ("none".equals(overlap)) {
                        assertThat(rows).hasSize(2);   // L and R disjoint → L − R = L
                    }
                }
            }
        }
    }

    // =========================================================================
    // Space 4 — Aggregation functions × grouping × additional aggregate
    //
    // Full space: 4 agg functions × 2 grouping flags × 2 extra-agg flags = 16
    // COVER 2 selects ≈ 6.  Scalar aggregation always yields 1 row; grouped
    // aggregation over a 2-group input always yields 2 rows.
    // =========================================================================

    @Nested
    @DisplayName("4. Aggregation functions × grouping × additional aggregate")
    class AggregationFunctions {

        static Stream<Arguments> args() {
            return coverArgs("""
                    AggFn    := [| agg_fn |
                                  | COUNT  |
                                  | SUM    |
                                  | MIN    |
                                  | MAX    |];
                    Grouping := [| has_grouping |
                                  | yes         |
                                  | no          |];
                    ExtraAgg := [| extra_agg |
                                  | yes      |
                                  | no       |];
                    query { COVER 2 (AggFn × Grouping × ExtraAgg) };
                    """,
                    "agg_fn", "has_grouping", "extra_agg");
        }

        @ParameterizedTest(name = "[{0}] grouping={1} extra_agg={2}")
        @MethodSource("args")
        void aggregatesProduceCorrectRowCount(String aggFn, String hasGrouping, String extraAgg) {
            // grp infers as STRING (values A, B); v infers as NUMBER (values 10, 20, 30)
            String relDef = "R := [| grp | v |\n       | A | 10 |\n       | A | 20 |\n       | B | 30 |];\n";
            boolean grouped = "yes".equals(hasGrouping);
            boolean extra   = "yes".equals(extraAgg);

            String primaryAgg = switch (aggFn) {
                case "COUNT" -> "COUNT(v) -> cnt";
                case "SUM"   -> "SUM(v) -> total";
                case "MIN"   -> "MIN(v) -> lo";
                case "MAX"   -> "MAX(v) -> hi";
                default      -> throw new IllegalArgumentException(aggFn);
            };

            // Optionally append a COUNT as a second output column to test multi-aggregate output
            String aggSpec = extra ? primaryAgg + ", COUNT(v) -> n" : primaryAgg;

            String query = grouped
                    ? "γ grp, " + aggSpec + " (R)"
                    : "γ " + aggSpec + " (R)";

            var rows = collect(relDef + "query { " + query + " };\n");
            assertThat(rows).isNotEmpty();
            // Grouped: 2 distinct grp values (A, B) → 2 rows; scalar: always 1 row
            assertThat(rows).hasSize(grouped ? 2 : 1);
        }
    }
}

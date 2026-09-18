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
package com.darkcollective.relix.cost;

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ClosureNode;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.ast.DownsampleNode;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ColumnStatistics;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CostEstimator}.
 */
@DisplayName("CostEstimator")
final class CostEstimatorTest {

    private static final Schema EMPTY_SCHEMA = new Schema(
            List.of(new ColumnDefinition("id", ScalarType.NUMBER)));

    // =========================================================================
    // Null guard
    // =========================================================================

    @Test
    @DisplayName("estimate(null) throws NullPointerException")
    void estimateNullThrows() {
        var estimator = new CostEstimator(null);
        assertThatThrownBy(() -> estimator.estimate(null))
                .isInstanceOf(NullPointerException.class);
    }

    // =========================================================================
    // Null symbol table — all leaves resolve to FILE
    // =========================================================================

    @Nested
    @DisplayName("Null symbol table (conservative fallback)")
    class NullSymbolTable {

        @Test
        @DisplayName("RelationNode with null table → FILE")
        void relationNodeNullTable() {
            var est = new CostEstimator(null);
            assertThat(est.estimate(rel("anything"))).isEqualTo(CostTier.FILE);
        }

        @Test
        @DisplayName("Natural join with null table → FILE (max of both FILE leaves)")
        void naturalJoinNullTable() {
            var est = new CostEstimator(null);
            var join = naturalJoin(rel("A"), rel("B"));
            assertThat(est.estimate(join)).isEqualTo(CostTier.FILE);
        }
    }

    // =========================================================================
    // Leaf symbol type → cost tier
    // =========================================================================

    @Nested
    @DisplayName("Leaf symbol type mapping")
    class LeafMapping {

        @Test
        @DisplayName("InlineRelationSymbol → INLINE")
        void inlineRelation() {
            var table = new InMemorySymbolTable();
            table.register(InlineRelationSymbol.of("A", EMPTY_SCHEMA, List.of()));
            var est = new CostEstimator(table);
            assertThat(est.estimate(rel("A"))).isEqualTo(CostTier.INLINE);
        }

        @Test
        @DisplayName("SourceRelationSymbol → FILE")
        void sourceRelation() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = new CostEstimator(table);
            assertThat(est.estimate(rel("S"))).isEqualTo(CostTier.FILE);
        }

        @Test
        @DisplayName("DatabaseRelationSymbol → REMOTE")
        void databaseRelation() {
            var table = new InMemorySymbolTable();
            table.register(DatabaseRelationSymbol.of("D", EMPTY_SCHEMA));
            var est = new CostEstimator(table);
            assertThat(est.estimate(rel("D"))).isEqualTo(CostTier.REMOTE);
        }

        @Test
        @DisplayName("Unknown relation name → FILE (conservative)")
        void unknownRelation() {
            var table = new InMemorySymbolTable();
            var est = new CostEstimator(table);
            assertThat(est.estimate(rel("Unknown"))).isEqualTo(CostTier.FILE);
        }

        @Test
        @DisplayName("QueryRelationSymbol body is INLINE → INLINE")
        void queryRelationInlineBody() {
            var table = new InMemorySymbolTable();
            table.register(InlineRelationSymbol.of("Base", EMPTY_SCHEMA, List.of()));
            table.register(QueryRelationSymbol.of("View", EMPTY_SCHEMA, rel("Base")));
            var est = new CostEstimator(table);
            assertThat(est.estimate(rel("View"))).isEqualTo(CostTier.INLINE);
        }

        @Test
        @DisplayName("QueryRelationSymbol body is REMOTE → REMOTE")
        void queryRelationRemoteBody() {
            var table = new InMemorySymbolTable();
            table.register(DatabaseRelationSymbol.of("DB", EMPTY_SCHEMA));
            table.register(QueryRelationSymbol.of("View", EMPTY_SCHEMA, rel("DB")));
            var est = new CostEstimator(table);
            assertThat(est.estimate(rel("View"))).isEqualTo(CostTier.REMOTE);
        }

        @Test
        @DisplayName("a table-valued function call costs as the body it inlines to")
        void relationFunctionCallCostsItsBody() {
            // A TVF call is a leaf to the traversal — its arguments are operands, not
            // relational children — so the body is the only thing that can carry its
            // cost. Reading the call alone would price a call over a remote table as an
            // inline one, which is the cheapest tier there is.
            var table = new InMemorySymbolTable();
            table.register(DatabaseRelationSymbol.of("DB", EMPTY_SCHEMA));
            table.register(RelationFunctionSymbol.builder("remoteOrders").body(rel("DB")).build());
            var est = new CostEstimator(table);

            assertThat(est.estimate(tvf("remoteOrders"))).isEqualTo(CostTier.REMOTE);
        }

        @Test
        @DisplayName("a call to a function the table does not know is FILE, like an unknown relation")
        void unknownRelationFunctionCall() {
            var est = new CostEstimator(new InMemorySymbolTable());
            assertThat(est.estimate(tvf("nowhere"))).isEqualTo(CostTier.FILE);
        }
    }

    // =========================================================================
    // Unary operators — cost passes through
    // =========================================================================

    @Nested
    @DisplayName("Unary operator cost pass-through")
    class UnaryPassThrough {

        @Test
        @DisplayName("Selection over REMOTE → REMOTE")
        void selectionOverRemote() {
            var table = new InMemorySymbolTable();
            table.register(DatabaseRelationSymbol.of("DB", EMPTY_SCHEMA));
            var est = new CostEstimator(table);
            var sel = select(
                    new com.darkcollective.relix.ast.ComparisonPredicate(
                            new com.darkcollective.relix.ast.AttributeOperand("id"),
                            com.darkcollective.relix.ast.ComparisonOperator.EQUAL,
                            new com.darkcollective.relix.ast.NumberOperand("1")),
                    rel("DB"));
            assertThat(est.estimate(sel)).isEqualTo(CostTier.REMOTE);
        }

        @Test
        @DisplayName("Projection over INLINE → INLINE")
        void projectionOverInline() {
            var table = new InMemorySymbolTable();
            table.register(InlineRelationSymbol.of("A", EMPTY_SCHEMA, List.of()));
            var est = new CostEstimator(table);
            var proj = project(
                    List.of(projected(
                            new com.darkcollective.relix.ast.AttributeOperand("id"))),
                    rel("A"));
            assertThat(est.estimate(proj)).isEqualTo(CostTier.INLINE);
        }

        @Test
        @DisplayName("Limit over FILE → FILE")
        void limitOverFile() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("Csv", EMPTY_SCHEMA));
            var est = new CostEstimator(table);
            var limit = limit(10L, rel("Csv"));
            assertThat(est.estimate(limit)).isEqualTo(CostTier.FILE);
        }
    }

    // =========================================================================
    // Binary operators — max of both inputs
    // =========================================================================

    @Nested
    @DisplayName("Binary operator takes max of both inputs")
    class BinaryMax {

        @Test
        @DisplayName("INLINE ⋈ REMOTE → REMOTE")
        void inlineJoinRemote() {
            var table = new InMemorySymbolTable();
            table.register(InlineRelationSymbol.of("A", EMPTY_SCHEMA, List.of()));
            table.register(DatabaseRelationSymbol.of("B", EMPTY_SCHEMA));
            var est = new CostEstimator(table);
            var join = naturalJoin(rel("A"), rel("B"));
            assertThat(est.estimate(join)).isEqualTo(CostTier.REMOTE);
        }

        @Test
        @DisplayName("INLINE ⋈ INLINE → INLINE")
        void inlineJoinInline() {
            var table = new InMemorySymbolTable();
            table.register(InlineRelationSymbol.of("A", EMPTY_SCHEMA, List.of()));
            table.register(InlineRelationSymbol.of("B", EMPTY_SCHEMA, List.of()));
            var est = new CostEstimator(table);
            var join = naturalJoin(rel("A"), rel("B"));
            assertThat(est.estimate(join)).isEqualTo(CostTier.INLINE);
        }

        @Test
        @DisplayName("FILE × REMOTE → REMOTE")
        void fileProductRemote() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("Csv", EMPTY_SCHEMA));
            table.register(DatabaseRelationSymbol.of("DB", EMPTY_SCHEMA));
            var est = new CostEstimator(table);
            var prod = product(rel("Csv"), rel("DB"));
            assertThat(est.estimate(prod)).isEqualTo(CostTier.REMOTE);
        }
    }

    // =========================================================================
    // CostTier ordering
    // =========================================================================

    @Test
    @DisplayName("CostTier ordinal ordering: INLINE < FILE < REMOTE")
    void costTierOrdering() {
        assertThat(CostTier.INLINE.ordinal()).isLessThan(CostTier.FILE.ordinal());
        assertThat(CostTier.FILE.ordinal()).isLessThan(CostTier.REMOTE.ordinal());
    }

    @Test
    @DisplayName("there is no VIEW tier — a view costs its body, so no path could return one")
    void noViewTier() {
        assertThat(CostTier.values()).containsExactly(
                CostTier.INLINE, CostTier.FILE, CostTier.REMOTE);
    }

    @Test
    @DisplayName("CostTier labels are lower-case non-blank strings")
    void costTierLabels() {
        for (CostTier tier : CostTier.values()) {
            assertThat(tier.label()).isNotBlank().isEqualTo(tier.label().toLowerCase());
        }
    }

    // =========================================================================
    // estimateRows — cardinality estimation
    // =========================================================================

    @Nested
    @DisplayName("estimateRows (cardinality)")
    class EstimateRows {

        /** A statistics source backed by a name → rowCount map. */
        private static StatisticsSource stats(Map<String, Long> rows) {
            return name -> Optional.ofNullable(rows.get(name)).map(RelationStatistics::of);
        }

        private static CostEstimator withStats(InMemorySymbolTable table, Map<String, Long> rows) {
            return new CostEstimator(table, stats(rows));
        }

        private static Predicate anyPred() {
            return cmp(
                    attr("id"), ComparisonOperator.EQUAL, num("1"));
        }

        @Test
        @DisplayName("estimateRows(null) throws NullPointerException")
        void nullThrows() {
            assertThatThrownBy(() -> new CostEstimator(null).estimateRows(null))
                    .isInstanceOf(NullPointerException.class);
        }

        /** An inline relation in {@code namespace} with {@code rowCount} one-column rows. */
        private static InlineRelationSymbol inline(String namespace, String name, int rowCount) {
            Schema schema = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));
            List<Map<String, Operand>> rows = new java.util.ArrayList<>();
            for (int i = 0; i < rowCount; i++) {
                rows.add(Map.<String, Operand>of("id", num(Integer.toString(i))));
            }
            return new InlineRelationSymbol(
                    namespace, name, Provenance.BUILTIN, ShadowPolicy.FORBIDDEN, schema, rows);
        }

        @Test
        @DisplayName("leaf with known row count returns it")
        void leafKnown() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 100L));
            assertThat(est.estimateRows(rel("S"))).hasValue(100L);
        }

        @Test
        @DisplayName("an unbounded closure uses its input row count as a proxy")
        void unboundedClosureCardinality() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("E", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("E", 100L));
            assertThat(est.estimateRows(closure("src", "dst",rel("E"))))
                    .hasValue(100L);
        }

        @Test
        @DisplayName("a closure with both endpoints bound estimates a single pair (≤ 1 row)")
        void singlePairClosureCardinality() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("E", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("E", 100L));
            ClosureNode bounded = closure("src", "dst",rel("E"))
                    .withBounds(Optional.of(num("1")),
                            Optional.of(num("4")));
            assertThat(est.estimateRows(bounded)).hasValue(1L);
        }

        @Test
        @DisplayName("a TRACE with both endpoints bound estimates a single pair (≤ 1 row)")
        void singlePairTraceCardinality() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("E", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("E", 100L));
            var trace = new com.darkcollective.relix.ast.TraceNode(rel("E"),
                    "src", "dst", "cost",
                    com.darkcollective.relix.ast.ObjectiveSense.MINIMIZE, "route")
                    .withBounds(Optional.of(num("1")),
                            Optional.of(num("4")));
            assertThat(est.estimateRows(trace)).hasValue(1L);
            // unbounded TRACE falls back to the input row count
            var unbounded = new com.darkcollective.relix.ast.TraceNode(rel("E"),
                    "src", "dst", "cost",
                    com.darkcollective.relix.ast.ObjectiveSense.MINIMIZE, "route");
            assertThat(est.estimateRows(unbounded)).hasValue(100L);
        }

        @Test
        @DisplayName("inline relation has an exact row count from its in-memory extent")
        void inlineExactCardinality() {
            var table = new InMemorySymbolTable();
            table.register(inline("default", "A", 3));
            // No statistics source — the count comes from the extent itself.
            assertThat(new CostEstimator(table).estimateRows(rel("A")))
                    .hasValue(3L);
        }

        @Test
        @DisplayName("a namespace-qualified catalog ref (relix.*) resolves to an exact count")
        void catalogQualifiedCardinality() {
            var table = new InMemorySymbolTable();
            table.register(inline("relix", "relations", 2));
            var est = new CostEstimator(table);
            // resolveRelation handles the dotted ref; extent gives the exact count.
            assertThat(est.estimateRows(rel("relix.relations"))).hasValue(2L);
            assertThat(est.estimate(rel("relix.relations")))
                    .isEqualTo(CostTier.INLINE);
        }

        @Test
        @DisplayName("leaf without statistics is unknown (empty)")
        void leafUnknown() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            assertThat(new CostEstimator(table).estimateRows(rel("S"))).isEmpty();
        }

        @Test
        @DisplayName("null symbol table still consults the statistics source")
        void nullTableUsesStats() {
            var est = new CostEstimator(null, stats(Map.of("S", 42L)));
            assertThat(est.estimateRows(rel("S"))).hasValue(42L);
        }

        @Test
        @DisplayName("view leaf inherits its body's cardinality")
        void viewInheritsBody() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("Base", EMPTY_SCHEMA));
            table.register(QueryRelationSymbol.of("V", EMPTY_SCHEMA, rel("Base")));
            var est = withStats(table, Map.of("Base", 70L));
            assertThat(est.estimateRows(rel("V"))).hasValue(70L);
        }

        @Test
        @DisplayName("selection scales by default selectivity (0.33)")
        void selectionScales() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 100L));
            var sel = select(anyPred(), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(Math.round(100 * 0.33));
        }

        @Test
        @DisplayName("selection floors at one row")
        void selectionFloors() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 1L));
            var sel = select(anyPred(), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(1L);
        }

        @Test
        @DisplayName("sample scales by its probability")
        void sampleScales() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 100L));
            assertThat(est.estimateRows(sample(0.25, rel("S"))))
                    .hasValue(Math.round(100 * 0.25));
        }

        @Test
        @DisplayName("reservoir sample is min(count, input) when the input size is known")
        void reservoirCapsAtInput() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 100L));
            // count below input → count; count above input → input.
            assertThat(est.estimateRows(reservoirSample(30, rel("S"))))
                    .hasValue(30L);
            assertThat(est.estimateRows(reservoirSample(500, rel("S"))))
                    .hasValue(100L);
        }

        @Test
        @DisplayName("reservoir sample falls back to its count when the input size is unknown")
        void reservoirUnknownInputUsesCount() {
            // No statistics → input row count unknown, but the result never exceeds count.
            var est = new CostEstimator(new InMemorySymbolTable());
            assertThat(est.estimateRows(reservoirSample(7, rel("Unknown"))))
                    .hasValue(7L);
        }

        @Test
        @DisplayName("solve passes its input row count through unchanged")
        void solvePassesThrough() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 100L));
            var solve = new com.darkcollective.relix.ast.SolveNode(
                    new com.darkcollective.relix.ast.AttributeOperand("total"),
                    new com.darkcollective.relix.ast.AttributeOperand("rate"),
                    rel("S"));
            assertThat(est.estimateRows(solve)).hasValue(100L);
        }

        @Test
        @DisplayName("optimize is bounded above by its input row count (a subset)")
        void optimizeBoundedByInput() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 100L));
            var optimize = new com.darkcollective.relix.ast.OptimizeNode(
                    com.darkcollective.relix.ast.ObjectiveSense.MAXIMIZE,
                    new com.darkcollective.relix.ast.AttributeOperand("value"),
                    List.of(new com.darkcollective.relix.ast.OptimizeConstraint(
                            new com.darkcollective.relix.ast.AttributeOperand("weight"),
                            com.darkcollective.relix.ast.ComparisonOperator.LESS_EQUAL, 50)),
                    List.of("region"),
                    rel("S"));
            assertThat(est.estimateRows(optimize)).hasValue(100L);
        }

        @Test
        @DisplayName("top-k without group stats reduces the input by default selectivity")
        void topKReducesWithoutGroupStats() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 100L));   // row count, no column stats
            var top = topK(List.of("id"),
                    List.of(desc("id")), 3,
                    rel("S"));
            // No distinct stats → treat as a coarse selection: max(count=3,
            // round(100 × 0.33) = 33) = 33, capped at the input size (100).
            assertThat(est.estimateRows(top)).hasValue(33L);
        }

        @Test
        @DisplayName("top-k without group stats never estimates below one group's worth (count)")
        void topKFloorsAtCount() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 6L));   // small input, no column stats
            var top = topK(List.of("id"),
                    List.of(desc("id")), 5,
                    rel("S"));
            // round(6 × 0.33) = 2 < count=5 → floor at 5, capped at the input (6).
            assertThat(est.estimateRows(top)).hasValue(5L);
        }

        @Test
        @DisplayName("top-k caps the reduced estimate at the input size")
        void topKCapsReducedAtInput() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 4L));   // count exceeds input
            var top = topK(List.of("id"),
                    List.of(desc("id")), 10,
                    rel("S"));
            // max(count=10, round(4 × 0.33)=1) = 10, capped at the input size (4).
            assertThat(est.estimateRows(top)).hasValue(4L);
        }

        @Test
        @DisplayName("top-k is unknown when both the input row count and group count are unknown")
        void topKUnknown() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = new CostEstimator(table);   // no statistics at all
            var top = topK(List.of("id"),
                    List.of(desc("id")), 3,
                    rel("S"));
            assertThat(est.estimateRows(top)).isEmpty();
        }

        @Test
        @DisplayName("projection, rename, sort and distinct pass the row count through")
        void passThrough() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 55L));
            RelNode base = rel("S");
            var proj = project(
                    List.of(projected(attr("id"))), base);
            var rename = rename("R", List.of(), base);
            var sort = sort(List.of(asc("id")), base);
            var distinct = distinct(base);
            // WHY appends a provenance column but emits one output row per input row.
            var why = new com.darkcollective.relix.ast.WhyNode(base);
            assertThat(est.estimateRows(proj)).hasValue(55L);
            assertThat(est.estimateRows(rename)).hasValue(55L);
            assertThat(est.estimateRows(sort)).hasValue(55L);
            assertThat(est.estimateRows(distinct)).hasValue(55L);
            assertThat(est.estimateRows(why)).hasValue(55L);
        }

        @Test
        @DisplayName("limit caps the row count; count bounds an unknown input")
        void limitCaps() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var known = withStats(table, Map.of("S", 100L));
            assertThat(known.estimateRows(limit(10L, rel("S"))))
                    .hasValue(10L);
            // offset reduces availability: 100 - 95 = 5 < count 10
            assertThat(known.estimateRows(limit(Optional.of(95L), 10L, rel("S"))))
                    .hasValue(5L);
            // unknown input → count is still a hard upper bound
            assertThat(new CostEstimator(table).estimateRows(
                    limit(7L, rel("S")))).hasValue(7L);
        }

        @Test
        @DisplayName("scalar aggregate is one row; grouped aggregate passes through")
        void aggregate() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 80L));
            var scalar = groupBy(
                    List.of(), List.of(AggregateFunction.simple(AggregateOperator.COUNT, "id")),
                    rel("S"));
            var grouped = groupBy(
                    List.of("id"), List.of(AggregateFunction.simple(AggregateOperator.SUM, "id")),
                    rel("S"));
            assertThat(est.estimateRows(scalar)).hasValue(1L);
            assertThat(est.estimateRows(grouped)).hasValue(80L);
        }

        @Test
        @DisplayName("universal quantification estimates as the group count (≤ input rows)")
        void universal() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("S", 80L));
            var universal = AstBuilders.universal(
                    List.of("id"),
                    cmp(attr("x"),
                            ComparisonOperator.GREATER, num("0")),
                    rel("S"));
            // No column stats → falls back to the input row count (upper bound).
            assertThat(est.estimateRows(universal)).hasValue(80L);
        }

        @Test
        @DisplayName("equi/theta/natural joins estimate as the max of both sides")
        void joinsMax() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", EMPTY_SCHEMA));
            table.register(SourceRelationSymbol.of("B", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("A", 10L, "B", 3L));
            var natural = naturalJoin(rel("A"), rel("B"));
            var theta = join(rel("A"), rel("B"), anyPred());
            assertThat(est.estimateRows(natural)).hasValue(10L);
            assertThat(est.estimateRows(theta)).hasValue(10L);
        }

        @Test
        @DisplayName("semi/anti/usemi/difference keep at most the left side's rows")
        void leftBounded() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", EMPTY_SCHEMA));
            table.register(SourceRelationSymbol.of("B", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("A", 8L, "B", 99L));
            assertThat(est.estimateRows(
                    semiJoin(rel("A"), rel("B"), anyPred()))).hasValue(8L);
            assertThat(est.estimateRows(
                    antiJoin(rel("A"), rel("B"), anyPred()))).hasValue(8L);
            assertThat(est.estimateRows(
                    pairwiseUniversal(rel("A"), rel("B"), anyPred()))).hasValue(8L);
            assertThat(est.estimateRows(
                    difference(rel("A"), rel("B")))).hasValue(8L);
        }

        @Test
        @DisplayName("product multiplies, unions sum, intersection takes the min, division keeps left")
        void binarySetOps() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", EMPTY_SCHEMA));
            table.register(SourceRelationSymbol.of("B", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("A", 4L, "B", 3L));
            RelNode a = rel("A");
            RelNode b = rel("B");
            assertThat(est.estimateRows(product(a, b))).hasValue(12L);
            assertThat(est.estimateRows(union(a, b))).hasValue(7L);
            assertThat(est.estimateRows(unionAll(a, b))).hasValue(7L);
            assertThat(est.estimateRows(intersection(a, b))).hasValue(3L);
            assertThat(est.estimateRows(division(a, b))).hasValue(4L);
            // Symmetric difference ≡ (A−B) ∪ (B−A): summed upper bound.
            assertThat(est.estimateRows(symmetricDifference(a, b))).hasValue(7L);
            // Composition ≡ π(A ⋈ B): estimates as the natural join (max of sides here).
            assertThat(est.estimateRows(composition(a, b))).hasValue(4L);
        }

        @Test
        @DisplayName("a binary operator with one unknown side is unknown")
        void oneUnknownSide() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", EMPTY_SCHEMA));
            table.register(SourceRelationSymbol.of("B", EMPTY_SCHEMA));
            var est = withStats(table, Map.of("A", 4L));   // B unknown
            assertThat(est.estimateRows(
                    product(rel("A"), rel("B")))).isEmpty();
        }
    }

    // =========================================================================
    // estimateRows — column-statistics refinements
    // =========================================================================

    @Nested
    @DisplayName("estimateRows (column statistics)")
    class ColumnStats {

        private static Schema schemaOf(String... names) {
            List<ColumnDefinition> cols = new java.util.ArrayList<>();
            for (String n : names) {
                cols.add(new ColumnDefinition(n, ScalarType.NUMBER));
            }
            return new Schema(cols);
        }

        /** Relation statistics: a row count plus per-column distinct counts. */
        private static RelationStatistics rel(long rows, Map<String, Long> distinctByColumn) {
            Map<String, ColumnStatistics> cols = new LinkedHashMap<>();
            distinctByColumn.forEach((name, distinct) ->
                    cols.put(name, new ColumnStatistics(OptionalLong.of(distinct), OptionalLong.empty())));
            return new RelationStatistics(OptionalLong.of(rows), cols, List.of());
        }

        private static StatisticsSource source(Map<String, RelationStatistics> stats) {
            return name -> Optional.ofNullable(stats.get(name));
        }

        @Test
        @DisplayName("grouped aggregation over a base relation uses the grouping column's distinct count")
        void groupedAggregationDistinct() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", schemaOf("region", "amount")));
            var est = new CostEstimator(table, source(Map.of("S", rel(100, Map.of("region", 5L)))));
            var agg = groupBy(List.of("region"),
                    List.of(AggregateFunction.simple(AggregateOperator.SUM, "amount")),
                    AstBuilders.rel("S"));
            assertThat(est.estimateRows(agg)).hasValue(5L);   // distinct(region) = 5
        }

        @Test
        @DisplayName("top-k estimates group count × count, capped at input rows")
        void topKDistinct() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", schemaOf("region", "amount")));
            var est = new CostEstimator(table, source(Map.of("S", rel(100, Map.of("region", 5L)))));
            // 5 groups × 3 per group = 15 (≤ 100 input rows).
            var top = topK(List.of("region"),
                    List.of(desc("amount")), 3,
                    AstBuilders.rel("S"));
            assertThat(est.estimateRows(top)).hasValue(15L);
        }

        @Test
        @DisplayName("top-k uses group count × count when the row count is unknown")
        void topKNoRowCount() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", schemaOf("region", "amount")));
            // Distinct count known, total row count unknown.
            var stats = new RelationStatistics(OptionalLong.empty(),
                    Map.of("region", new ColumnStatistics(OptionalLong.of(5L), OptionalLong.empty())),
                    List.of());
            var est = new CostEstimator(table, source(Map.of("S", stats)));
            var top = topK(List.of("region"),
                    List.of(desc("amount")), 3,
                    AstBuilders.rel("S"));
            // 5 groups × 3 = 15; no input row count to cap against.
            assertThat(est.estimateRows(top)).hasValue(15L);
        }

        @Test
        @DisplayName("grouped aggregation caps the distinct-count product at the row count")
        void groupedAggregationCapped() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", schemaOf("region")));
            var est = new CostEstimator(table, source(Map.of("S", rel(3, Map.of("region", 10L)))));
            var agg = groupBy(List.of("region"),
                    List.of(AggregateFunction.simple(AggregateOperator.SUM, "region")),
                    AstBuilders.rel("S"));
            assertThat(est.estimateRows(agg)).hasValue(3L);   // min(distinct 10, rows 3)
        }

        @Test
        @DisplayName("a theta equi-join uses |L|·|R| / max(distinct(L.k), distinct(R.k))")
        void thetaEquiJoinDistinct() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("a")));
            table.register(SourceRelationSymbol.of("B", schemaOf("b")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(100, Map.of("a", 10L)),
                    "B", rel(50, Map.of("b", 50L)))));
            var join = join(AstBuilders.rel("A"), AstBuilders.rel("B"),
                    cmp(attr("A.a"),
                            ComparisonOperator.EQUAL, attr("B.b")));
            assertThat(est.estimateRows(join)).hasValue(100L);   // 100*50 / max(10,50)
        }

        @Test
        @DisplayName("a natural join uses the shared column's distinct counts")
        void naturalJoinDistinct() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("k", "x")));
            table.register(SourceRelationSymbol.of("B", schemaOf("k", "y")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(100, Map.of("k", 20L)),
                    "B", rel(80, Map.of("k", 40L)))));
            var join = naturalJoin(AstBuilders.rel("A"), AstBuilders.rel("B"));
            assertThat(est.estimateRows(join)).hasValue(200L);   // 100*80 / max(20,40)
        }

        @Test
        @DisplayName("the equi-join estimate does not depend on which side was written first")
        void thetaEquiJoinIsOrientationIndependent() {
            // The key extractor tries `left belongs to L and right to R`, then the reverse.
            // Only the first orientation had a case, so nothing showed a user writing the
            // condition the other way round gets the same estimate — and a silent fallback
            // to max(sides) is a *different number*, not an error anyone would notice.
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("a")));
            table.register(SourceRelationSymbol.of("B", schemaOf("b")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(100, Map.of("a", 10L)),
                    "B", rel(50, Map.of("b", 50L)))));
            var reversed = join(AstBuilders.rel("A"), AstBuilders.rel("B"),
                    cmp(attr("B.b"),
                            ComparisonOperator.EQUAL, attr("A.a")));
            assertThat(est.estimateRows(reversed)).hasValue(100L);   // same as A.a = B.b
        }

        @Test
        @DisplayName("an equality against a literal is not an equi-key")
        void thetaJoinAgainstALiteralFallsBack() {
            // `cmp.right() instanceof AttributeOperand` is its own arm: a filter conjoined
            // into the join condition is not a join key, and reading it as one would
            // divide by a distinct count that describes the wrong thing.
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("a")));
            table.register(SourceRelationSymbol.of("B", schemaOf("b")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(100, Map.of("a", 10L)),
                    "B", rel(50, Map.of("b", 50L)))));
            var join = join(AstBuilders.rel("A"), AstBuilders.rel("B"),
                    cmp(attr("A.a"),
                            ComparisonOperator.EQUAL, num("5")));
            assertThat(est.estimateRows(join)).hasValue(100L);   // max(100, 50)
        }

        @Test
        @DisplayName("an unknown side blocks the shared-column read from either direction")
        void commonColumnsNeedsBothSchemas() {
            // Both sides are looked up and both may fail; a fixture that only ever leaves
            // the right unknown never shows the left is checked.
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("k")));
            var est = new CostEstimator(table, source(Map.of("A", rel(100, Map.of("k", 20L)))));
            assertThat(est.estimateRows(naturalJoin(
                    AstBuilders.rel("Unknown"), AstBuilders.rel("A"))))
                    .as("unknown on the left").isNotEqualTo(java.util.OptionalLong.of(0L));
        }

        @Test
        @DisplayName("an equality with a literal on the LEFT is not an equi-key either")
        void literalOnTheLeftIsNotAnEquiKey() {
            // The operand test reads left then right; a literal-on-the-right fixture
            // short-circuits on the first and never reaches the second.
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("a")));
            table.register(SourceRelationSymbol.of("B", schemaOf("b")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(100, Map.of("a", 10L)),
                    "B", rel(50, Map.of("b", 50L)))));
            var join = join(AstBuilders.rel("A"), AstBuilders.rel("B"),
                    cmp(num("5"),
                            ComparisonOperator.EQUAL, attr("A.a")));
            assertThat(est.estimateRows(join)).hasValue(100L);   // max(100, 50)
        }

        @Test
        @DisplayName("∩ has no estimate unless both sides do — it is bounded by the smaller")
        void intersectionNeedsBothSides() {
            // ∩ estimates as min(|L|, |R|); a side with no statistics leaves the pair
            // incomparable, and guessing the known side would over-report a bound that
            // depends on the one nobody measured.
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("k")));
            table.register(SourceRelationSymbol.of("B", schemaOf("k")));
            var est = new CostEstimator(table, source(Map.of("A", rel(100, Map.of("k", 20L)))));
            assertThat(est.estimateRows(intersection(
                    AstBuilders.rel("A"), AstBuilders.rel("B"))))
                    .as("right side unmeasured").isEmpty();
            assertThat(est.estimateRows(intersection(
                    AstBuilders.rel("B"), AstBuilders.rel("A"))))
                    .as("left side unmeasured").isEmpty();
        }

        @Test
        @DisplayName("a natural join with an unknown side has no shared columns to read")
        void naturalJoinWithAnUnknownSide() {
            // commonColumns needs *both* schemas; a relation the symbol table does not
            // know contributes none, and the estimate must fall back rather than treat
            // "no shared columns" as a fact about the data.
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("k")));
            var est = new CostEstimator(table, source(Map.of("A", rel(100, Map.of("k", 20L)))));
            var join = naturalJoin(AstBuilders.rel("A"), AstBuilders.rel("Unknown"));
            assertThat(est.estimateRows(join)).isNotEqualTo(java.util.OptionalLong.of(0L));
        }

        @Test
        @DisplayName("counted but unschema'd, a natural join's side yields no shared columns")
        void naturalJoinWhereOnlyOneSideHasASchema() {
            // Statistics and schemas arrive on different seams — a snapshot or a previous
            // run's observed counts can name a relation the symbol table does not — so a
            // row count is not evidence that a heading is available. Both orders are
            // written out because which side is short is the query author's choice, and an
            // estimator reading one of the two schemas would answer confidently on half
            // the queries and fall back on the other half with nothing to say which.
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("k")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(100, Map.of("k", 20L)),
                    "Counted", rel(50, Map.of("k", 10L)))));

            assertThat(est.estimateRows(naturalJoin(AstBuilders.rel("Counted"), AstBuilders.rel("A"))))
                    .as("no heading on the left, so no shared column to divide by")
                    .hasValue(100L);
            assertThat(est.estimateRows(naturalJoin(AstBuilders.rel("A"), AstBuilders.rel("Counted"))))
                    .as("and the mirror, which is the other arm of the same guard")
                    .hasValue(100L);
        }

        @Test
        @DisplayName("an equality between two columns of one side is not an equi-key")
        void equalityWithinOneSideIsNotAnEquiKey() {
            // A join key relates the two sides. `A.a = A.a2` constrains A alone, so
            // reading it as one would divide by a distinct count that says nothing about
            // how the sides match up — an estimate too small by the width of that column.
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("a", "a2")));
            table.register(SourceRelationSymbol.of("B", schemaOf("b", "b2")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(100, Map.of("a", 10L, "a2", 10L)),
                    "B", rel(50, Map.of("b", 50L, "b2", 50L)))));

            var leftOnly = join(AstBuilders.rel("A"), AstBuilders.rel("B"),
                    cmp(attr("A.a"), ComparisonOperator.EQUAL, attr("A.a2")));
            assertThat(est.estimateRows(leftOnly))
                    .as("both columns belong to the left, so there is no key to divide by")
                    .hasValue(100L);

            var rightOnly = join(AstBuilders.rel("A"), AstBuilders.rel("B"),
                    cmp(attr("B.b"), ComparisonOperator.EQUAL, attr("B.b2")));
            assertThat(est.estimateRows(rightOnly))
                    .as("and the mirror, which reaches the other of the two orderings tried")
                    .hasValue(100L);
        }

        @Test
        @DisplayName("a non-equality join condition falls back to max(sides)")
        void nonEquiJoinFallsBack() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("a")));
            table.register(SourceRelationSymbol.of("B", schemaOf("b")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(100, Map.of("a", 10L)),
                    "B", rel(50, Map.of("b", 50L)))));
            var join = join(AstBuilders.rel("A"), AstBuilders.rel("B"),
                    cmp(attr("A.a"),
                            ComparisonOperator.LESS, attr("B.b")));
            assertThat(est.estimateRows(join)).hasValue(100L);   // max(100, 50), not a distinct estimate
        }

        @Test
        @DisplayName("a composite natural join multiplies the shared columns' distinct counts")
        void compositeNaturalJoinDistinct() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("k1", "k2", "x")));
            table.register(SourceRelationSymbol.of("B", schemaOf("k1", "k2", "y")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(1000, Map.of("k1", 10L, "k2", 10L)),
                    "B", rel(500, Map.of("k1", 5L, "k2", 4L)))));
            var join = naturalJoin(AstBuilders.rel("A"), AstBuilders.rel("B"));
            // d(A) = 10×10 = 100, d(B) = 5×4 = 20 → 1000×500 / max(100, 20) = 5000.
            // Before #543 this bailed to max(1000, 500) = 1000.
            assertThat(est.estimateRows(join)).hasValue(5000L);
        }

        @Test
        @DisplayName("a composite theta equi-join reads every conjunct of its condition")
        void compositeThetaJoinDistinct() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("a1", "a2")));
            table.register(SourceRelationSymbol.of("B", schemaOf("b1", "b2")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(1000, Map.of("a1", 10L, "a2", 10L)),
                    "B", rel(500, Map.of("b1", 5L, "b2", 4L)))));
            var join = join(AstBuilders.rel("A"), AstBuilders.rel("B"),
                    and(
                            cmp(attr("A.a1"),
                                    ComparisonOperator.EQUAL, attr("B.b1")),
                            cmp(attr("A.a2"),
                                    ComparisonOperator.EQUAL, attr("B.b2"))));
            assertThat(est.estimateRows(join)).hasValue(5000L);
        }

        @Test
        @DisplayName("a condition mixing an equality with a range falls back to max(sides)")
        void mixedConditionFallsBack() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("a1", "a2")));
            table.register(SourceRelationSymbol.of("B", schemaOf("b1", "b2")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(1000, Map.of("a1", 10L, "a2", 10L)),
                    "B", rel(500, Map.of("b1", 5L, "b2", 4L)))));
            // The range conjunct filters further than the equality, so estimating from
            // the equality alone would over-estimate: bail rather than over-count.
            var join = join(AstBuilders.rel("A"), AstBuilders.rel("B"),
                    and(
                            cmp(attr("A.a1"),
                                    ComparisonOperator.EQUAL, attr("B.b1")),
                            cmp(attr("A.a2"),
                                    ComparisonOperator.LESS, attr("B.b2"))));
            assertThat(est.estimateRows(join)).hasValue(1000L);
        }

        @Test
        @DisplayName("a join with a missing column distinct count falls back to max(sides)")
        void missingDistinctFallsBack() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("A", schemaOf("a")));
            table.register(SourceRelationSymbol.of("B", schemaOf("b")));
            var est = new CostEstimator(table, source(Map.of(
                    "A", rel(10, Map.of("a", 5L)),
                    "B", rel(50, Map.of()))));   // B has no column stats
            var join = join(AstBuilders.rel("A"), AstBuilders.rel("B"),
                    cmp(attr("A.a"),
                            ComparisonOperator.EQUAL, attr("B.b")));
            assertThat(est.estimateRows(join)).hasValue(50L);   // max(10, 50)
        }
    }

    // =========================================================================
    // Selection selectivity from column statistics (#536)
    // =========================================================================

    @Nested
    @DisplayName("estimateRows (selection selectivity)")
    class SelectionSelectivity {

        private static Schema schemaOf(String... names) {
            List<ColumnDefinition> cols = new java.util.ArrayList<>();
            for (String n : names) {
                cols.add(new ColumnDefinition(n, ScalarType.NUMBER));
            }
            return new Schema(cols);
        }

        private static StatisticsSource source(Map<String, RelationStatistics> stats) {
            return name -> Optional.ofNullable(stats.get(name));
        }

        /** A `S(a, b, c)` table of `rows` rows with the given per-column distinct counts. */
        private static CostEstimator estimator(long rows, Map<String, Long> distinct) {
            Map<String, ColumnStatistics> cols = new LinkedHashMap<>();
            distinct.forEach((name, d) ->
                    cols.put(name, new ColumnStatistics(OptionalLong.of(d), OptionalLong.empty())));
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", schemaOf("a", "b", "c")));
            return new CostEstimator(table, source(Map.of("S",
                    new RelationStatistics(OptionalLong.of(rows), cols, List.of()))));
        }

        private static Predicate eq(String column, String literal) {
            return cmp(attr(column),
                    ComparisonOperator.EQUAL, num(literal));
        }

        @Test
        @DisplayName("equality on a column with d distinct values estimates rows/d")
        void equalityUsesDistinctCount() {
            var est = estimator(1000, Map.of("a", 100L));
            var sel = select(eq("a", "5"), AstBuilders.rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(10L);   // 1000 × 1/100
        }

        @Test
        @DisplayName("a literal on the left of the equality is read the same way")
        void equalityLiteralOnLeft() {
            var est = estimator(1000, Map.of("a", 100L));
            var sel = select(cmp(num("5"),
                    ComparisonOperator.EQUAL, attr("a")), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(10L);
        }

        @Test
        @DisplayName("a qualified column name resolves to the same statistics")
        void qualifiedColumnResolves() {
            var est = estimator(1000, Map.of("a", 100L));
            var sel = select(eq("S.a", "5"), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(10L);
        }

        @Test
        @DisplayName("inequality on a column with d distinct values estimates rows × (1 − 1/d)")
        void inequalityIsTheComplement() {
            var est = estimator(1000, Map.of("a", 100L));
            var sel = select(cmp(attr("a"),
                    ComparisonOperator.NOT_EQUAL, num("5")), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(990L);   // 1000 × 0.99
        }

        @Test
        @DisplayName("a conjunction multiplies its conjuncts' selectivities")
        void conjunctionMultiplies() {
            var est = estimator(1000, Map.of("a", 10L, "b", 5L));
            var sel = select(
                    and(eq("a", "1"), eq("b", "2")), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(20L);   // 1000 × 0.1 × 0.2
        }

        @Test
        @DisplayName("a disjunction uses 1 − ∏(1 − sᵢ)")
        void disjunctionUnions() {
            var est = estimator(1000, Map.of("a", 10L, "b", 10L));
            var sel = select(
                    or(eq("a", "1"), eq("b", "2")), rel("S"));
            // 1 − 0.9×0.9 = 0.19
            assertThat(est.estimateRows(sel)).hasValue(190L);
        }

        @Test
        @DisplayName("a negation is the complement of what it negates")
        void negationComplements() {
            var est = estimator(1000, Map.of("a", 10L));
            var sel = select(
                    not(eq("a", "1")), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(900L);   // 1000 × 0.9
        }

        @Test
        @DisplayName("an IN list over n literals estimates n/d")
        void elementOfScalesByListSize() {
            var est = estimator(1000, Map.of("a", 100L));
            var sel = select(elementOf(attr("a"),
       set(num("1"), num("2"),
               num("3"))), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(30L);   // 1000 × 3/100
        }

        @Test
        @DisplayName("an IN list longer than the distinct count is capped at every row")
        void elementOfCapsAtOne() {
            var est = estimator(1000, Map.of("a", 2L));
            var sel = select(elementOf(attr("a"),
       set(num("1"), num("2"),
               num("3"), num("4"))), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(1000L);   // 4/2 clamped to 1
        }

        @Test
        @DisplayName("an IN list the statistics cannot speak to falls back to the flat default")
        void elementOfDeclinesOnEveryShapeItCannotMeasure() {
            // Four separate conditions reject, each ending the chain at a different link.
            // The fallback is DEFAULT_SELECTIVITY, so a rejection is visible as the
            // estimate reverting to the flat guess rather than n/d.
            var est = estimator(1000, Map.of("a", 100L));
            List<Predicate> unmeasurable = List.of(
                    // the element is not a bare column
                    elementOf(
               arith(attr("a"),
                       ArithmeticOperator.PLUS, num("1")),
               set(num("1"))),
                    // the right side is not a set literal
                    elementOf(attr("a"),
               attr("b")),
                    // an empty list — there is no n to divide by
                    elementOf(attr("a"),
               set()),
                    // a list holding something that is not a literal
                    elementOf(attr("a"),
               set(attr("b"))));

            for (Predicate p : unmeasurable) {
                assertThat(est.estimateRows(select(p, rel("S"))))
                        .as("%s", p)
                        .isNotEqualTo(java.util.OptionalLong.of(10L));   // not the n/d answer
            }
        }

        @Test
        @DisplayName("IS NULL over a computed operand is not measurable either")
        void isNullDeclinesOnANonColumnOperand() {
            // The null count is a per-column frequency; there is no such statistic for an
            // expression, so the rule must decline rather than attribute the column's
            // count to something derived from it.
            var est = estimator(1000, Map.of("a", 100L));
            var sel = select(nullPred(
                    arith(attr("a"),
                            ArithmeticOperator.PLUS, num("1")), true),
                    rel("S"));
            assertThat(est.estimateRows(sel)).isPresent();
        }

        @Test
        @DisplayName("IS NULL declines when the statistics carry no row count to divide by")
        void isNullDeclinesWithoutARowCount() {
            // A column's null count is meaningless as a *fraction* without the total, and
            // the statistics may carry one without the other.
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", schemaOf("a")));
            var stats = new RelationStatistics(OptionalLong.empty(),
                    Map.of("a", new ColumnStatistics(OptionalLong.of(100L), OptionalLong.of(250L))),
                    List.of());
            var est = new CostEstimator(table, source(Map.of("S", stats)));
            assertThat(est.estimateRows(select(
                    nullPred(attr("a"), true), rel("S"))))
                    .as("no row count — nothing to scale").isEmpty();
        }

        @Test
        @DisplayName("IS NULL uses the null count, the one exact frequency the statistics carry")
        void isNullUsesNullCount() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", schemaOf("a")));
            var stats = new RelationStatistics(OptionalLong.of(1000L),
                    Map.of("a", new ColumnStatistics(OptionalLong.of(100L), OptionalLong.of(250L))),
                    List.of());
            var est = new CostEstimator(table, source(Map.of("S", stats)));
            var sel = select(
                    nullPred(attr("a"), true), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(250L);
            var notNull = select(
                    nullPred(attr("a"), false), rel("S"));
            assertThat(est.estimateRows(notNull)).hasValue(750L);
        }

        @Test
        @DisplayName("a range predicate falls back to the default selectivity — there is no min/max")
        void rangeFallsBackToDefault() {
            var est = estimator(1000, Map.of("a", 100L));
            var sel = select(cmp(attr("a"),
                    ComparisonOperator.GREATER, num("5")), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(330L);   // 1000 × 0.33
        }

        @Test
        @DisplayName("a column with no distinct count falls back to the default selectivity")
        void missingDistinctFallsBackToDefault() {
            var est = estimator(1000, Map.of());
            var sel = select(eq("a", "5"), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(330L);
        }

        @Test
        @DisplayName("a non-base-relation input falls back to the default selectivity")
        void derivedInputFallsBackToDefault() {
            var est = estimator(1000, Map.of("a", 100L));
            // The δ renames nothing, but the estimator will not attribute a column of a
            // derived relation to a catalogued base column.
            var sel = select(eq("a", "5"), distinct(rel("S")));
            assertThat(est.estimateRows(sel)).hasValue(330L);
        }

        @Test
        @DisplayName("a conjunction with one unreadable side still counts the readable one")
        void conjunctionWithOpaqueSideKeepsTheReadableOne() {
            var est = estimator(1000, Map.of("a", 100L));
            Predicate opaque = cmp(attr("b"),
                    ComparisonOperator.GREATER, num("5"));
            var sel = select(
                    and(eq("a", "5"), opaque), rel("S"));
            assertThat(est.estimateRows(sel)).hasValue(3L);   // 1000 × 0.01 × 0.33
        }

        @Test
        @DisplayName("a connective with no readable leaf stays at the flat default")
        void whollyOpaqueConnectiveStaysDefault() {
            var est = estimator(1000, Map.of("a", 100L));
            Predicate opaque = cmp(attr("b"),
                    ComparisonOperator.GREATER, num("5"));
            var sel = select(not(opaque), rel("S"));
            // Not 1 − 0.33: an unreadable predicate's complement is equally unreadable.
            assertThat(est.estimateRows(sel)).hasValue(330L);
        }

        @Test
        @DisplayName("a selection over a relation with no statistics is still unknown")
        void noStatisticsStaysEmptyForSelection() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("S", schemaOf("a")));
            var est = new CostEstimator(table, StatisticsSource.NONE);
            var sel = select(eq("a", "5"), rel("S"));
            assertThat(est.estimateRows(sel)).isEmpty();
        }
    }

    // =========================================================================
    // DOWNSAMPLE cardinality (#543)
    // =========================================================================

    @Nested
    @DisplayName("estimateRows (DOWNSAMPLE)")
    class DownsampleRows {

        private static CostEstimator estimator() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("M", new Schema(List.of(
                    new ColumnDefinition("ts", ScalarType.TIMESTAMP),
                    new ColumnDefinition("host", ScalarType.STRING),
                    new ColumnDefinition("value", ScalarType.NUMBER)))));
            var stats = new RelationStatistics(OptionalLong.of(10_000L),
                    Map.of("host", new ColumnStatistics(OptionalLong.of(4L), OptionalLong.empty())),
                    List.of());
            return new CostEstimator(table, name -> Optional.of(stats).filter(unused -> name.equals("M")));
        }

        private static DownsampleNode downsample(List<String> keys, OptionalLong maxRows) {
            return AstBuilders.downsample("ts", "1h", ConsolidationFunction.AVG,
                    keys, maxRows, rel("M"));
        }

        @Test
        @DisplayName("a keyless DOWNSAMPLE is bounded by its input, not collapsed to one row")
        void keylessIsNotOneRow() {
            // The bucket count is time span ÷ interval, and ColumnStatistics carries no
            // min/max, so it is not derivable. Before #543 this delegated to groupedRows,
            // whose empty-grouping arm returned exactly 1 — thousands of buckets under-
            // estimated as a single row, in the direction that mis-picks a build side.
            assertThat(estimator().estimateRows(downsample(List.of(), OptionalLong.empty())))
                    .hasValue(10_000L);
        }

        @Test
        @DisplayName("grouping keys do not shrink the estimate — buckets multiply them")
        void groupingKeysDoNotShrinkIt() {
            // distinct(host) = 4, but the output is buckets × 4, so 4 is a *lower* bound.
            assertThat(estimator().estimateRows(downsample(List.of("host"), OptionalLong.empty())))
                    .hasValue(10_000L);
        }

        @Test
        @DisplayName("FOR n ROWS caps the estimate globally")
        void maxRowsCaps() {
            assertThat(estimator().estimateRows(downsample(List.of("host"), OptionalLong.of(24L))))
                    .hasValue(24L);
        }

        @Test
        @DisplayName("FOR n ROWS is a bound even when the input size is unknown")
        void maxRowsBoundsWithoutInputRows() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("M", new Schema(List.of(
                    new ColumnDefinition("ts", ScalarType.TIMESTAMP),
                    new ColumnDefinition("value", ScalarType.NUMBER)))));
            var est = new CostEstimator(table, StatisticsSource.NONE);
            assertThat(est.estimateRows(downsample(List.of(), OptionalLong.of(24L))))
                    .hasValue(24L);
        }

        @Test
        @DisplayName("without FOR n ROWS and without an input row count the estimate is unknown")
        void unknownWithoutEither() {
            var table = new InMemorySymbolTable();
            table.register(SourceRelationSymbol.of("M", new Schema(List.of(
                    new ColumnDefinition("ts", ScalarType.TIMESTAMP),
                    new ColumnDefinition("value", ScalarType.NUMBER)))));
            var est = new CostEstimator(table, StatisticsSource.NONE);
            assertThat(est.estimateRows(downsample(List.of(), OptionalLong.empty()))).isEmpty();
        }
    }
}

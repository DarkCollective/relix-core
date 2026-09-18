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
package com.darkcollective.relix.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.UnaryOperator;
import com.darkcollective.relix.ast.AstBuilders;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RelNodeOperands} — the operand-level counterpart of
 * {@link RelNode#children()}.
 *
 * <p>Each expression-carrying node is built with a distinctive marker operand and the
 * walk is asked to report it, so an arm that forgets a field fails here rather than
 * silently weakening whatever analysis is built on the walker.
 */
@DisplayName("RelNodeOperands — the expressions each node carries")
final class RelNodeOperandsTest extends AstTestSupport {

    private static final RelationNode A = rel("A");
    private static final RelationNode B = rel("B");

    private static List<Operand> operandsOf(RelNode node) {
        List<Operand> found = new ArrayList<>();
        RelNodeOperands.forEach(node, found::add, unused -> { });
        return found;
    }

    private static List<Predicate> predicatesOf(RelNode node) {
        List<Predicate> found = new ArrayList<>();
        RelNodeOperands.forEach(node, unused -> { }, found::add);
        return found;
    }

    @Nested
    @DisplayName("the expressions no structural traversal reaches")
    class HiddenExpressions {

        @Test
        @DisplayName("a TVF call's arguments — children() reports it as a leaf")
        void relationFunctionCallArguments() {
            var call = tvf("ordersFor",num("2"), str("open"));

            assertThat(call.children()).isEmpty();
            assertThat(operandsOf(call)).containsExactly(num("2"), str("open"));
        }

        @Test
        @DisplayName("a LATERAL join's arguments — children() reports only the left input")
        void lateralArguments() {
            var lateral = lateral(A, "ordersFor",attr("customer_id"));

            assertThat(lateral.children()).containsExactly(A);
            assertThat(operandsOf(lateral)).containsExactly(attr("customer_id"));
        }
    }

    @Nested
    @DisplayName("operand-carrying operators")
    class Operands {

        @Test
        @DisplayName("π reports every projected expression")
        void projection() {
            assertThat(operandsOf(project(
                    List.of(projected(attr("x")), projected(func("Abs", attr("y")))), A)))
                    .containsExactly(attr("x"), func("Abs", attr("y")));
        }

        @Test
        @DisplayName("γ reports its grouping keys and both halves of each aggregate")
        void aggregation() {
            var argmax = argAgg(AggregateOperator.ARGMAX, attr("rank"),attr("name"),"top");
            assertThat(operandsOf(groupByKeys(
                    List.of(key(func("YEAR", attr("at")),"yr")),
                    List.of(argmax), A)))
                    .containsExactly(func("YEAR", attr("at")), attr("rank"), attr("name"));
        }

        @Test
        @DisplayName("an ungrouped γ carries a null key list and still reports its aggregates")
        void ungroupedAggregation() {
            List<String> noKeys = null;
            assertThat(operandsOf(groupBy(
                    noKeys, List.of(agg(AggregateOperator.SUM, "amount")), A)))
                    .containsExactly(attr("amount"));
        }

        @Test
        @DisplayName("τ and TOP report their sort-key expressions")
        void sortKeys() {
            var spec = sortKey(func("to_timestamp", attr("logged")),
                    SortDirection.DESC);
            assertThat(operandsOf(sort(List.of(spec), A)))
                    .containsExactly(func("to_timestamp", attr("logged")));
            assertThat(operandsOf(topK(List.of("k"), List.of(spec),
                    Optional.empty(), 3L, A)))
                    .containsExactly(func("to_timestamp", attr("logged")));
        }

        @Test
        @DisplayName("AS-OF reports its tolerance alongside its condition")
        void asOfTolerance() {
            var join = asOfJoin(A, B, cmp(attr("t"), ComparisonOperator.LESS_EQUAL,
                    attr("u")), Optional.of(duration("PT1H")), true,
                    TieBreak.FIRST);

            assertThat(operandsOf(join)).containsExactly(duration("PT1H"));
            assertThat(predicatesOf(join)).hasSize(1);
        }

        @Test
        @DisplayName("CLOSURE and TRACE report both endpoint bounds")
        void endpointBounds() {
            assertThat(operandsOf(closure("src", "dst", false, false,
                    Optional.of(num("1")), Optional.of(num("9")),A)))
                    .containsExactly(num("1"), num("9"));
            assertThat(operandsOf(trace("src", "dst", false, "w",
                    ObjectiveSense.MINIMIZE, "path", Optional.of(num("1")),
                    Optional.of(num("9")),A)))
                    .containsExactly(num("1"), num("9"));
        }

        @Test
        @DisplayName("SOLVE reports both sides of its equation")
        void solve() {
            assertThat(operandsOf(AstBuilders.solve(attr("x"), num("10"), A)))
                    .containsExactly(attr("x"), num("10"));
        }

        @Test
        @DisplayName("OPTIMIZE reports its objective and every constraint expression")
        void optimize() {
            assertThat(operandsOf(AstBuilders.optimize(ObjectiveSense.MAXIMIZE, attr("profit"),
                    List.of(constraint(attr("cost"), ComparisonOperator.LESS_EQUAL, 5)),
                    List.of(), Optional.empty(), A)))
                    .containsExactly(attr("profit"), attr("cost"));
        }

        @Test
        @DisplayName("SESSIONIZE reports its gap threshold")
        void sessionizeThreshold() {
            assertThat(operandsOf(sessionize("at", duration("PT30M"), "session", A)))
                    .containsExactly(duration("PT30M"));
        }

        @Test
        @DisplayName("a generator's production bound is reported")
        void produceBound() {
            var bounded = rel("Naturals",AstBuilders.produceBound("n", ComparisonOperator.LESS, num("100")));

            assertThat(operandsOf(bounded)).containsExactly(num("100"));
            assertThat(operandsOf(A)).as("an unbounded relation carries none").isEmpty();
        }
    }

    @Nested
    @DisplayName("window functions")
    class Windows {

        private WindowNode windowOf(WindowFunction function) {
            return new WindowNode(function, List.of(), List.of(),
                    new WindowFrame.PartitionFrame(), "out", A, SourceLocation.UNKNOWN);
        }

        @Test
        @DisplayName("an aggregate window reports its argument")
        void aggregateWindow() {
            assertThat(operandsOf(windowOf(new WindowFunction.AggregateWindow(
                    AggregateOperator.SUM, attr("amount")))))
                    .containsExactly(attr("amount"));
        }

        @Test
        @DisplayName("a ranking window reports its NTILE count when it has one")
        void rankingWindow() {
            assertThat(operandsOf(windowOf(new WindowFunction.RankingWindow(
                    RankingFunction.NTILE, Optional.of(num("4"))))))
                    .containsExactly(num("4"));
            assertThat(operandsOf(windowOf(new WindowFunction.RankingWindow(
                    RankingFunction.RANK, Optional.empty()))))
                    .isEmpty();
        }

        @Test
        @DisplayName("an offset window reports its expression, offset and default")
        void offsetWindow() {
            assertThat(operandsOf(windowOf(new WindowFunction.OffsetWindow(
                    OffsetFunction.LAG, attr("amount"),
                    Optional.of(num("2")), Optional.of(num("0"))))))
                    .containsExactly(attr("amount"), num("2"), num("0"));
        }

        @Test
        @DisplayName("the within-partition ordering is reported too, not only the function")
        void windowSortKeys() {
            var window = new WindowNode(
                    new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("amount")),
                    List.of(),
                    List.of(sortKey(func("to_timestamp", attr("at")),
                            SortDirection.ASC)),
                    new WindowFrame.PartitionFrame(), "out", A, SourceLocation.UNKNOWN);

            assertThat(operandsOf(window))
                    .containsExactly(attr("amount"), func("to_timestamp", attr("at")));
        }
    }

    @Test
    @DisplayName("TREE's sibling ordering is a sort key, so its expression is reported")
    void treeOrderSpecs() {
        var ordered = tree("id", "parent",
                List.of(sortKey(func("LCase", attr("name")), SortDirection.ASC)),
                "children", A);

        assertThat(operandsOf(ordered)).containsExactly(func("LCase", attr("name")));
        assertThat(operandsOf(tree("id", "parent", "children", A)))
                .as("an unordered TREE carries none").isEmpty();
    }

    @Nested
    @DisplayName("predicate-carrying operators")
    class Predicates {

        @Test
        @DisplayName("σ reports its predicate")
        void selection() {
            var predicate = cmp(attr("x"), ComparisonOperator.GREATER, num("1"));
            assertThat(predicatesOf(select(predicate, A)))
                    .containsExactly(predicate);
        }

        @Test
        @DisplayName("∀ reports its predicate")
        void universal() {
            var predicate = cmp(attr("x"), ComparisonOperator.GREATER, num("1"));
            assertThat(predicatesOf(AstBuilders.universal(List.of("k"), predicate, A)))
                    .containsExactly(predicate);
        }

        @Test
        @DisplayName("every conditional join reports its condition through the one arm")
        void conditionalJoins() {
            var condition = cmp(attr("x"), ComparisonOperator.EQUAL, attr("y"));
            List<RelNode> joins = List.of(
                    join(A, B, condition),
                    leftJoin(A, B, condition),
                    rightJoin(A, B, condition),
                    fullJoin(A, B, condition),
                    semiJoin(A, B, condition),
                    antiJoin(A, B, condition),
                    pairwiseUniversal(A, B, condition));

            assertThat(joins).allSatisfy(join ->
                    assertThat(predicatesOf(join)).containsExactly(condition));
        }
    }

    @Nested
    @DisplayName("operators that carry no expressions")
    class NoExpressions {

        @Test
        @DisplayName("the structural set operations and leaves report nothing")
        void structural() {
            List<RelNode> nodes = List.of(
                    A, unitRel(), emptyRel(), recRef("T"), distinct(A), why(A),
                    naturalJoin(A, B), product(A, B), union(A, B),
                    unionAll(A, B), outerUnion(A, B),
                    difference(A, B), intersection(A, B),
                    division(A, B), symmetricDifference(A, B),
                    composition(A, B), fixpoint("T", A, B));

            assertThat(nodes).allSatisfy(node -> {
                assertThat(operandsOf(node)).isEmpty();
                assertThat(predicatesOf(node)).isEmpty();
            });
        }

        @Test
        @DisplayName("the column-name-only operators report nothing")
        void columnNamesOnly() {
            List<RelNode> nodes = List.of(
                    rename("R", List.of(), A),
                    unnest("items", A),
                    cluster("src", "dst", "component",A),
                    path("src", "dst", 1, 3, "depth",A),
                    limit(Optional.of(1L), 5L, A),
                    intervalJoin(A, B, AllenRelation.OVERLAPS, "s1", "e1", "s2", "e2"),
                    cover(2, A),
                    downsample("at", "PT1M", ConsolidationFunction.AVG, A),
                    unpivot(List.of("q1"), "quarter", "amount", A),
                    pivot("amount", "quarter", List.of("cust"), A),
                    tree("id", "parent", "children", A));

            assertThat(nodes).allSatisfy(node -> {
                assertThat(operandsOf(node)).isEmpty();
                assertThat(predicatesOf(node)).isEmpty();
            });
        }

        @Test
        @DisplayName("the sampling operators report nothing — their volatility is not an operand")
        void sampling() {
            assertThat(operandsOf(sample(0.5, A))).isEmpty();
            assertThat(operandsOf(reservoirSample(10, A))).isEmpty();
        }
    }

    @Nested
    @DisplayName("usesSystemState")
    class SystemState {

        @Test
        @DisplayName("an unseeded SAMPLE / RESERVOIR reads a fresh random source")
        void unseededSamplingIsVolatile() {
            assertThat(RelNodeOperands.usesSystemState(sample(0.5, A))).isTrue();
            assertThat(RelNodeOperands.usesSystemState(reservoirSample(10, A))).isTrue();
        }

        @Test
        @DisplayName("a SEED makes it reproducible")
        void seededSamplingIsReproducible() {
            assertThat(RelNodeOperands.usesSystemState(
                    sample(0.5, Optional.of(42L), A))).isFalse();
            assertThat(RelNodeOperands.usesSystemState(
                    reservoirSample(10, Optional.of(42L), A)))
                    .isFalse();
        }

        /**
         * The classification, over every kind there is.
         *
         * <p>What this guards is not a listing but a rewrite: {@code RelationDeterminism}
         * reads this to decide whether a sub-plan may be evaluated once and shared, so a
         * kind wrongly called reproducible is one the planner will share — and
         * {@code X ∆ X} over an unseeded {@code SAMPLE} is a query about two draws.
         *
         * <p>Driven by {@link RelNodeCorpus} rather than by a hand-written list, which had
         * twelve of the forty-four kinds. The switch is exhaustive, so the compiler makes
         * a new operator get an <em>arm</em>; only this makes it get the right one, and it
         * reaches that operator the day its corpus entry lands.
         */
        @Test
        @DisplayName("exactly the unseeded sampling kinds, over the whole hierarchy")
        void everyKindIsClassified() {

            // The corpus's sampling entries are seeded, so on its own this would assert
            // only the false half and pass for a method that always answered false. The
            // unseeded pair is added to make the classification say something in both
            // directions.
            List<RelNode> everything = new java.util.ArrayList<>(RelNodeCorpus.everyKind());
            everything.add(sample(0.5, A));
            everything.add(reservoirSample(10, A));

            for (RelNode node : everything) {
                boolean unseededSampling =
                        (node instanceof SampleNode s && s.seed().isEmpty())
                        || (node instanceof ReservoirSampleNode r && r.seed().isEmpty());
                assertThat(RelNodeOperands.usesSystemState(node))
                        .as("%s", node.getClass().getSimpleName())
                        .isEqualTo(unseededSampling);
            }
        }

        @Test
        @DisplayName("no other node kind reads system state on its own")
        void everythingElseIsReproducible() {
            List<RelNode> nodes = List.of(
                    A, unitRel(), emptyRel(), recRef("T"), distinct(A), why(A),
                    select(cmp(attr("x"), ComparisonOperator.EQUAL, num("1")), A),
                    project(List.of(projected(attr("x"))), A),
                    tvf("f"),
                    lateral(A, "f"),
                    product(A, B), fixpoint("T", A, B));

            assertThat(nodes).allSatisfy(node ->
                    assertThat(RelNodeOperands.usesSystemState(node)).isFalse());
        }
    }

    /** A downsample with the {@code FOR n ROWS} cap, to pin the other constructor arm. */
    @Test
    @DisplayName("a capped DOWNSAMPLE still carries no expressions")
    void cappedDownsample() {
        assertThat(operandsOf(new DownsampleNode("at", "PT1M",
                ConsolidationFunction.AVG, List.of("region"), OptionalLong.of(10), A,
                SourceLocation.UNKNOWN))).isEmpty();
    }

    // =========================================================================
    // map — the rewriting counterpart
    // =========================================================================

    @Nested
    @DisplayName("map — the rewriting counterpart of forEach")
    class Mapping {

        /** Distinctive replacements, never reference-equal to anything a fixture builds. */
        private static final Operand   NEW_OPERAND   = num("777");
        private static final Predicate NEW_PREDICATE =
                cmp(attr("swapped"),
                        ComparisonOperator.EQUAL, num("777"));

        private static RelNode replaceAll(RelNode node) {
            return RelNodeOperands.map(node, unused -> NEW_OPERAND, unused -> NEW_PREDICATE);
        }

        private static RelNode unchanged(RelNode node) {
            return RelNodeOperands.map(node, UnaryOperator.identity(), UnaryOperator.identity());
        }

        /**
         * One node per {@code map} arm.  {@code forEach} is the oracle: whatever it
         * reports for a node, {@code map} must have replaced — so an arm that rebuilds a
         * node while dropping one of its expressions fails here.
         */
        private static List<RelNode> everyCarrier() {
            var condition = cmp(attr("x"), ComparisonOperator.EQUAL, attr("y"));
            var spec = sortKey(func("to_timestamp", attr("at")), SortDirection.DESC);
            return List.of(
                    // expression carriers
                    rel("Naturals",produceBound("n", ComparisonOperator.LESS, num("100"))),
                    tvf("ordersFor",num("2"), str("open")),
                    lateral(A, "ordersFor",attr("cust")),
                    select(condition, A),
                    universal(List.of("k"), condition, A),
                    project(List.of(projected(attr("x")), projected(attr("y"), "z")), A),
                    groupByKeys(
                            List.of(key(func("YEAR", attr("at")),"yr")),
                            List.of(argAgg(AggregateOperator.ARGMAX, attr("rank"),attr("name"),"top")),
                            A),
                    groupBy(null,
                            List.of(agg(AggregateOperator.SUM, "amount")), A),
                    sort(List.of(spec), A),
                    topK(List.of("k"), List.of(spec), Optional.of(1L), 3L, A),
                    tree("id", "parent", List.of(spec), "children", A),
                    join(A, B, condition),
                    asOfJoin(A, B, condition, Optional.of(duration("PT1H")), true,
                            TieBreak.FIRST),
                    closure("src", "dst", false, false, Optional.of(num("1")),
                            Optional.of(num("9")),A),
                    trace("src", "dst", false, "w", ObjectiveSense.MINIMIZE, "path",
                            Optional.of(num("1")), Optional.of(num("9")),A),
                    solve(attr("x"), num("10"), A),
                    optimize(ObjectiveSense.MAXIMIZE, attr("profit"),
                            List.of(constraint(attr("cost"),
                                    ComparisonOperator.LESS_EQUAL, 5)),
                            List.of(), Optional.empty(), A),
                    new WindowNode(new WindowFunction.AggregateWindow(
                                    AggregateOperator.SUM, attr("amount")),
                            List.of(), List.of(spec), new WindowFrame.PartitionFrame(),
                            "out", A, SourceLocation.UNKNOWN),
                    new WindowNode(new WindowFunction.RankingWindow(
                                    RankingFunction.NTILE, Optional.of(num("4"))),
                            List.of(), List.of(spec), new WindowFrame.PartitionFrame(),
                            "out", A, SourceLocation.UNKNOWN),
                    new WindowNode(new WindowFunction.OffsetWindow(OffsetFunction.LAG,
                                    attr("amount"), Optional.of(num("2")), Optional.of(num("0"))),
                            List.of(), List.of(spec), new WindowFrame.PartitionFrame(),
                            "out", A, SourceLocation.UNKNOWN),
                    sessionize("at", duration("PT30M"), "session", A),
                    // nodes with nothing to rewrite
                    A, unitRel(), emptyRel(), recRef("T"), distinct(A), why(A),
                    naturalJoin(A, B), product(A, B), union(A, B),
                    unionAll(A, B), outerUnion(A, B), difference(A, B),
                    intersection(A, B), division(A, B),
                    symmetricDifference(A, B), composition(A, B),
                    fixpoint("T", A, B), rename("R", List.of(), A),
                    unnest("items", A), cluster("src", "dst", "component",A),
                    path("src", "dst", 1, 3, "depth",A),
                    limit(Optional.of(1L), 5L, A),
                    intervalJoin(A, B, AllenRelation.OVERLAPS, "s1", "e1", "s2", "e2"),
                    cover(2, A), downsample("at", "PT1M", ConsolidationFunction.AVG, A),
                    unpivot(List.of("q1"), "quarter", "amount", A),
                    pivot("amount", "quarter", List.of("cust"), A),
                    sample(0.5, A), reservoirSample(10, A));
        }

        @Test
        @DisplayName("every expression forEach reports is replaced, and none is dropped")
        void replacesEveryReportedExpression() {
            assertThat(everyCarrier()).allSatisfy(node -> {
                RelNode mapped = replaceAll(node);

                assertThat(operandsOf(mapped))
                        .as("%s — operand count preserved and every one replaced",
                                node.getClass().getSimpleName())
                        .hasSameSizeAs(operandsOf(node))
                        .allMatch(o -> o == NEW_OPERAND);
                assertThat(predicatesOf(mapped))
                        .as("%s — predicate count preserved and every one replaced",
                                node.getClass().getSimpleName())
                        .hasSameSizeAs(predicatesOf(node))
                        .allMatch(p -> p == NEW_PREDICATE);
            });
        }

        @Test
        @DisplayName("the node's type and children survive the rewrite")
        void rewriteIsShallowAndTypePreserving() {
            assertThat(everyCarrier()).allSatisfy(node -> {
                RelNode mapped = replaceAll(node);
                assertThat(mapped).hasSameClassAs(node);
                assertThat(mapped.children())
                        .as("%s — map does not touch children",
                                node.getClass().getSimpleName())
                        .containsExactlyElementsOf(node.children());
            });
        }

        @Test
        @DisplayName("identity mapping returns the very same instance, like mapChildren")
        void identityReturnsSameInstance() {
            assertThat(everyCarrier()).allSatisfy(node ->
                    assertThat(unchanged(node))
                            .as("%s — no expression changed, so no rebuild",
                                    node.getClass().getSimpleName())
                            .isSameAs(node));
        }

        @Test
        @DisplayName("a node whose expressions are absent is returned as-is")
        void absentOptionalsAreNotRebuilt() {
            List<RelNode> noneCarried = List.of(
                    A,                                                   // no produce bound
                    tvf("now"),          // no arguments
                    lateral(A, "all"),
                    closure("src", "dst", false, false, Optional.empty(), Optional.empty(),A),
                    trace("src", "dst", false, "w", ObjectiveSense.MINIMIZE, "path",
                            Optional.empty(), Optional.empty(),A),
                    new WindowNode(new WindowFunction.RankingWindow(
                                    RankingFunction.RANK, Optional.empty()),
                            List.of(), List.of(), new WindowFrame.PartitionFrame(), "out", A,
                            SourceLocation.UNKNOWN),
                    tree("id", "parent", "children", A));

            assertThat(noneCarried).allSatisfy(node ->
                    assertThat(replaceAll(node)).isSameAs(node));
        }

        @Test
        @DisplayName("changing any one expression rebuilds — every slot is consulted on its own")
        void everySlotIsConsultedIndependently() {
            // Each multi-slot arm decides with `a == n.a && b == n.b ? n : rebuild`, so
            // replacing everything and replacing nothing both leave the middle of that
            // chain untested: a rebuild condition that read only its first component
            // would pass those two and silently drop rewrites to the rest.
            for (RelNode node : everyCarrier()) {
                List<Operand> operands = operandsOf(node);
                List<Predicate> predicates = predicatesOf(node);
                if (operands.size() + predicates.size() < 2) {
                    continue;
                }
                String kind = node.getClass().getSimpleName();

                for (Operand target : operands) {
                    RelNode mapped = RelNodeOperands.map(node,
                            o -> o == target ? NEW_OPERAND : o, UnaryOperator.identity());
                    assertThat(mapped)
                            .as("%s — replacing one operand must rebuild the node", kind)
                            .isNotSameAs(node);
                    assertThat(operandsOf(mapped))
                            .as("%s — the replacement is present", kind)
                            .anyMatch(o -> o == NEW_OPERAND);
                    assertThat(predicatesOf(mapped))
                            .as("%s — untouched predicates keep their instance", kind)
                            .containsExactlyElementsOf(predicates);
                }

                for (Predicate target : predicates) {
                    RelNode mapped = RelNodeOperands.map(node, UnaryOperator.identity(),
                            pr -> pr == target ? NEW_PREDICATE : pr);
                    assertThat(mapped)
                            .as("%s — replacing one predicate must rebuild the node", kind)
                            .isNotSameAs(node);
                    assertThat(predicatesOf(mapped))
                            .as("%s — the replacement is present", kind)
                            .anyMatch(pr -> pr == NEW_PREDICATE);
                    assertThat(operandsOf(mapped))
                            .as("%s — untouched operands keep their instance", kind)
                            .containsExactlyElementsOf(operands);
                }
            }
        }

        @Test
        @DisplayName("only the expression that changed is rebuilt; the rest keep their instance")
        void partialRewriteKeepsUntouchedExpressions() {
            var keep    = func("YEAR", attr("at"));
            var replace = attr("amount");
            var node = groupByKeys(
                    List.of(key(keep,"yr")),
                    List.of(agg(AggregateOperator.SUM, "amount")), A);

            var mapped = (AggregationNode) RelNodeOperands.map(node,
                    o -> o.equals(replace) ? NEW_OPERAND : o, UnaryOperator.identity());

            assertThat(mapped).isNotSameAs(node);
            assertThat(mapped.groupingKeys()).isSameAs(node.groupingKeys());
            assertThat(mapped.groupingKeys().getFirst().expression()).isSameAs(keep);
            assertThat(mapped.aggregates().getFirst().argument()).isSameAs(NEW_OPERAND);
        }

        @Test
        @DisplayName("a conditional join is rebuilt as its own concrete type")
        void conditionalJoinsKeepTheirType() {
            var condition = cmp(attr("x"), ComparisonOperator.EQUAL, attr("y"));
            List<RelNode> joins = List.of(
                    join(A, B, condition),
                    leftJoin(A, B, condition),
                    rightJoin(A, B, condition),
                    fullJoin(A, B, condition),
                    semiJoin(A, B, condition),
                    antiJoin(A, B, condition),
                    pairwiseUniversal(A, B, condition));

            assertThat(joins).allSatisfy(join -> {
                RelNode mapped = replaceAll(join);
                assertThat(mapped).hasSameClassAs(join);
                assertThat(predicatesOf(mapped)).containsExactly(NEW_PREDICATE);
            });
        }

        @Test
        @DisplayName("the fields around a rewritten expression are carried across untouched")
        void surroundingFieldsSurvive() {
            var asOf = asOfJoin(A, B,
                    cmp(attr("t"), ComparisonOperator.LESS_EQUAL, attr("u")),
                    Optional.of(duration("PT1H")), false, TieBreak.LAST);

            var mapped = (AsOfJoinNode) replaceAll(asOf);

            assertThat(mapped.tolerance()).contains(NEW_OPERAND);
            assertThat(mapped.condition()).isSameAs(NEW_PREDICATE);
            assertThat(mapped.inner()).isFalse();
            assertThat(mapped.tieBreak()).isEqualTo(TieBreak.LAST);
            assertThat(mapped.left()).isSameAs(A);
            assertThat(mapped.right()).isSameAs(B);
        }
    }
}

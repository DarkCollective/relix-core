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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.plan.PlanEstimates;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.plan.PhysicalNode.BuildSide;
import com.darkcollective.relix.plan.PhysicalNode.JoinAlgorithm;
import com.darkcollective.relix.plan.PhysicalNode.JoinKeys;
import com.darkcollective.relix.plan.PhysicalNode.JoinKind;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PhysicalPlanPrinter — physical plan → ASCII tree")
final class PhysicalPlanPrinterTest {

    private static final Schema SCHEMA =
            new Schema(List.of(new ColumnDefinition("x", ScalarType.NUMBER)));

    private static PhysicalNode.PushedScan sqlScan(String sql) {
        return new PhysicalNode.PushedScan(SCHEMA, "jdbc", "db", sql);
    }

    @Test
    @DisplayName("a Scan's produce-bound renders the operator symbol, not the enum name")
    void produceBoundUsesOperatorSymbol() {
        var source = new com.darkcollective.relix.symbol.relation.InlineRelationSymbol(
                "default", "Naturals",
                com.darkcollective.relix.symbol.Provenance.USER,
                com.darkcollective.relix.symbol.ShadowPolicy.FORBIDDEN,
                SCHEMA, List.of());
        var scan = new PhysicalNode.Scan(SCHEMA, source,
                java.util.Optional.of(new com.darkcollective.relix.ast.ProduceBound(
                        "n", com.darkcollective.relix.ast.ComparisonOperator.LESS_EQUAL,
                        new com.darkcollective.relix.ast.NumberOperand("10"))));

        // Regression: this printed the raw enum constant ("n LESS_EQUAL 10"), the
        // one operator display site left behind when the rest moved to symbol().
        assertThat(PhysicalPlanPrinter.explain(scan))
                .isEqualTo("Scan Naturals ⟨produce while n ≤ 10⟩\n");
    }

    @Test
    @DisplayName("a PushedScan leaf shows its connector type, connection, and native query")
    void sqlScanLeaf() {
        String out = PhysicalPlanPrinter.explain(sqlScan("SELECT x FROM t WHERE (x > 1)"));
        assertThat(out).isEqualTo("PushedScan [jdbc/db] SELECT x FROM t WHERE (x > 1)\n");
    }

    @Test
    @DisplayName("a join shows kind/algorithm/build side and draws its children as a tree")
    void joinTree() {
        PhysicalNode join = new PhysicalNode.Join(
                SCHEMA, JoinKind.INNER, JoinAlgorithm.HASH, BuildSide.RIGHT,
                Optional.empty(), JoinKeys.none(), Set.of(), Set.of(),
                sqlScan("SELECT x FROM a"), sqlScan("SELECT x FROM b"));

        String out = PhysicalPlanPrinter.explain(join);

        assertThat(out).isEqualTo("""
                Join INNER/HASH build=RIGHT
                ├─ PushedScan [jdbc/db] SELECT x FROM a
                └─ PushedScan [jdbc/db] SELECT x FROM b
                """);
    }

    @Test
    @DisplayName("an AS-OF join shows its direction and draws its children")
    void asOfJoinLabel() {
        PhysicalNode plan = new PhysicalNode.AsOfJoin(
                SCHEMA, JoinKeys.none(), 1, 1, true, true,
                sqlScan("SELECT x FROM trades"), sqlScan("SELECT x FROM quotes"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                AsOfJoin backward/strict
                ├─ PushedScan [jdbc/db] SELECT x FROM trades
                └─ PushedScan [jdbc/db] SELECT x FROM quotes
                """);
    }

    @Test
    @DisplayName("an AS-OF join with inner=true and tolerance shows /inner and /within in label")
    void asOfJoinInnerAndTolerance() {
        PhysicalNode plan = new PhysicalNode.AsOfJoin(
                SCHEMA, JoinKeys.none(), 1, 1, true, false,
                Optional.of(java.time.Duration.parse("PT30M")), true,
                com.darkcollective.relix.ast.TieBreak.LAST,
                sqlScan("SELECT x FROM trades"), sqlScan("SELECT x FROM quotes"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                AsOfJoin backward/inner/within PT30M
                ├─ PushedScan [jdbc/db] SELECT x FROM trades
                └─ PushedScan [jdbc/db] SELECT x FROM quotes
                """);
    }

    @Test
    @DisplayName("an AS-OF join with tieBreak=FIRST shows /ties(first) in label")
    void asOfJoinTiesFirst() {
        PhysicalNode plan = new PhysicalNode.AsOfJoin(
                SCHEMA, JoinKeys.none(), 1, 1, false, false,
                Optional.empty(), false,
                com.darkcollective.relix.ast.TieBreak.FIRST,
                sqlScan("SELECT x FROM trades"), sqlScan("SELECT x FROM quotes"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                AsOfJoin forward/ties(first)
                ├─ PushedScan [jdbc/db] SELECT x FROM trades
                └─ PushedScan [jdbc/db] SELECT x FROM quotes
                """);
    }

    @Test
    @DisplayName("an interval join shows its Allen relation and column indices")
    void intervalJoinLabel() {
        PhysicalNode plan = new PhysicalNode.IntervalJoin(
                SCHEMA, com.darkcollective.relix.ast.AllenRelation.INTERSECTS,
                0, 1, 0, 1,
                sqlScan("SELECT x FROM events"), sqlScan("SELECT x FROM bookings"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                IntervalJoin INTERSECTS [0,1;0,1]
                ├─ PushedScan [jdbc/db] SELECT x FROM events
                └─ PushedScan [jdbc/db] SELECT x FROM bookings
                """);
    }

    @Test
    @DisplayName("a closure shows its from/to columns")
    void closureLabel() {
        PhysicalNode plan = new PhysicalNode.Closure(
                SCHEMA, "src", "dst", false, false, sqlScan("SELECT x FROM edges"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                CLOSURE src→dst
                └─ PushedScan [jdbc/db] SELECT x FROM edges
                """);
    }

    @Test
    @DisplayName("a bounded closure shows its endpoint bounds")
    void boundedClosureLabel() {
        PhysicalNode plan = new PhysicalNode.Closure(
                SCHEMA, "src", "dst", false, false,
                java.util.Optional.of(new com.darkcollective.relix.ast.NumberOperand("1")),
                java.util.Optional.empty(),
                sqlScan("SELECT x FROM edges"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                CLOSURE src→dst [src=1]
                └─ PushedScan [jdbc/db] SELECT x FROM edges
                """);
    }

    @Test
    @DisplayName("a bounded trace shows its endpoint bounds")
    void boundedTraceLabel() {
        PhysicalNode plan = new PhysicalNode.Trace(
                SCHEMA, "src", "dst", false, "cost",
                com.darkcollective.relix.ast.ObjectiveSense.MINIMIZE, "route",
                java.util.Optional.of(new com.darkcollective.relix.ast.StringOperand("JFK")),
                java.util.Optional.of(new com.darkcollective.relix.ast.StringOperand("LAX")),
                sqlScan("SELECT x FROM edges"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                TRACE src, dst VIA cost MINIMIZE AS route [src="JFK"] [dst="LAX"]
                └─ PushedScan [jdbc/db] SELECT x FROM edges
                """);
    }

    @Test
    @DisplayName("a single-pair Dijkstra trace shows its bounds and the [dijkstra] strategy")
    void dijkstraTraceLabel() {
        PhysicalNode plan = new PhysicalNode.Trace(
                SCHEMA, "src", "dst", false, "cost",
                com.darkcollective.relix.ast.ObjectiveSense.MINIMIZE, "route",
                com.darkcollective.relix.plan.TraceAlgorithm.DIJKSTRA,
                java.util.Optional.of(new com.darkcollective.relix.ast.NumberOperand("1")),
                java.util.Optional.of(new com.darkcollective.relix.ast.NumberOperand("4")),
                sqlScan("SELECT x FROM edges"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                TRACE src, dst VIA cost MINIMIZE AS route [src=1] [dst=4] [dijkstra]
                └─ PushedScan [jdbc/db] SELECT x FROM edges
                """);
    }

    @Test
    @DisplayName("a cluster shows its from/to columns and label")
    void clusterLabel() {
        PhysicalNode plan = new PhysicalNode.Cluster(
                SCHEMA, "src", "dst", "cid", sqlScan("SELECT x FROM edges"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                CLUSTER src, dst AS cid
                └─ PushedScan [jdbc/db] SELECT x FROM edges
                """);
    }

    @Test
    @DisplayName("a path shows its from/to columns, hop window, and depth column")
    void pathLabel() {
        PhysicalNode plan = new PhysicalNode.Path(
                SCHEMA, "src", "dst", false, 1, 3, "depth", sqlScan("SELECT x FROM edges"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                PATH src, dst HOPS 1 TO 3 AS depth
                └─ PushedScan [jdbc/db] SELECT x FROM edges
                """);
    }

    @Test
    @DisplayName("a rolling window shows its function, frame, sort, partition, and output column")
    void windowLabel() {
        PhysicalNode plan = new PhysicalNode.Window(
                SCHEMA,
                new com.darkcollective.relix.ast.WindowFunction.AggregateWindow(
                        com.darkcollective.relix.ast.AggregateOperator.SUM,
                        new com.darkcollective.relix.ast.AttributeOperand("price")),
                List.of("g"),
                List.of(new com.darkcollective.relix.ast.SortSpecification(
                        "t", com.darkcollective.relix.ast.SortDirection.ASC)),
                new com.darkcollective.relix.ast.WindowFrame.BoundedFrame(3),
                "w",
                sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                ROLLING SUM(price) OVER 3 ROWS SORT t↑ PER g AS w
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a sessionize shows its order column, gap, partition, and session column")
    void sessionizeLabel() {
        PhysicalNode plan = new PhysicalNode.Sessionize(
                SCHEMA, "seq", new com.darkcollective.relix.ast.NumberOperand("2"),
                List.of("u"), "session", sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                SESSIONIZE seq GAP 2 PER u AS session
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a tree shows its key, parent, order, and children column")
    void treeLabel() {
        PhysicalNode plan = new PhysicalNode.Tree(
                SCHEMA, "node_id", "parent_id",
                List.of(new com.darkcollective.relix.ast.SortSpecification(
                        "ordinal", com.darkcollective.relix.ast.SortDirection.ASC)),
                "children", sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                TREE node_id BY parent_id ORDER ordinal AS children
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a limit shows its count and offset")
    void limitLabel() {
        PhysicalNode plan = new PhysicalNode.Limit(SCHEMA, Optional.of(5L), 10, sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                Limit 10 OFFSET 5
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a top-k node shows its count and grouping keys")
    void topKLabel() {
        PhysicalNode plan = new PhysicalNode.TopK(
                SCHEMA, List.of("customer_id"),
                List.of(new com.darkcollective.relix.ast.SortSpecification(
                        "x", com.darkcollective.relix.ast.SortDirection.DESC)),
                java.util.Optional.empty(), 3, sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                TOP 3 PER customer_id
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a reservoir-sample node shows its row count")
    void reservoirSampleLabel() {
        PhysicalNode plan = new PhysicalNode.ReservoirSample(
                SCHEMA, 100, java.util.Optional.empty(), sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                SAMPLE 100 ROWS
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a streaming δ/γ is labelled 'streaming'; the hash variant is bare")
    void streamingLabels() {
        PhysicalNode hashDistinct = new PhysicalNode.Distinct(SCHEMA, false, sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(hashDistinct)).startsWith("Distinct\n");

        PhysicalNode streamDistinct = new PhysicalNode.Distinct(SCHEMA, true, sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(streamDistinct)).startsWith("Distinct streaming\n");

        PhysicalNode streamAggregate = new PhysicalNode.Aggregate(
                SCHEMA, List.of(GroupingKey.column("x")), List.of(), true, sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(streamAggregate)).startsWith("Aggregate streaming\n");
    }

    @Test
    @DisplayName("a universal node shows ∀ and its grouping keys")
    void universalLabel() {
        PhysicalNode plan = new PhysicalNode.Universal(
                SCHEMA, List.of("a", "b"),
                new com.darkcollective.relix.ast.NullPredicate(
                        new com.darkcollective.relix.ast.AttributeOperand("x"), false),
                sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                Universal a, b
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a no-key whole-relation ∀ shows just ∀ with no trailing keys")
    void universalNoKeyLabel() {
        PhysicalNode plan = new PhysicalNode.Universal(
                com.darkcollective.relix.symbol.Schema.empty(), List.of(),
                new com.darkcollective.relix.ast.NullPredicate(
                        new com.darkcollective.relix.ast.AttributeOperand("x"), false),
                sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                Universal
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("an optimize node shows its objective, constraint, and grouping")
    void optimizeLabel() {
        PhysicalNode plan = new PhysicalNode.Optimize(
                SCHEMA,
                com.darkcollective.relix.ast.ObjectiveSense.MAXIMIZE,
                new com.darkcollective.relix.ast.AttributeOperand("value"),
                List.of(new com.darkcollective.relix.ast.OptimizeConstraint(
                        new com.darkcollective.relix.ast.AttributeOperand("weight"),
                        com.darkcollective.relix.ast.ComparisonOperator.LESS_EQUAL, 100)),
                List.of("region"),
                java.util.Optional.empty(),
                sqlScan("SELECT x FROM t"));
        // ≤ (not <=): operators render through ComparisonOperator.symbol(), so a
        // predicate reads identically in :explain and in :tree.
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                OPTIMIZE max SUM(value) s.t. SUM(weight)≤100 [region]
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a Fixpoint node shows its bound name with base and step children")
    void fixpointLabel() {
        PhysicalNode ref = new PhysicalNode.RecursiveRef(SCHEMA, "R");
        PhysicalNode base = sqlScan("SELECT 1");
        PhysicalNode step = sqlScan("SELECT 2");
        PhysicalNode fix = new PhysicalNode.Fixpoint(SCHEMA, "R", base, step);
        assertThat(PhysicalPlanPrinter.explain(fix)).isEqualTo("""
                FIX R
                ├─ PushedScan [jdbc/db] SELECT 1
                └─ PushedScan [jdbc/db] SELECT 2
                """);
        assertThat(PhysicalPlanPrinter.explain(ref)).isEqualTo("REF R\n");
    }

    @Test
    @DisplayName("a cover node shows its strength")
    void coverLabel() {
        PhysicalNode plan = new PhysicalNode.Cover(SCHEMA, 2, false, sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                COVER 2
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("an exact cover node shows 'EXACT' in its label")
    void exactCoverLabel() {
        PhysicalNode plan = new PhysicalNode.Cover(SCHEMA, 2, true, sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                COVER EXACT 2
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a constructive cover node shows strength and conjunct count")
    void constructiveCoverLabel() {
        var fa = sqlScan("SELECT a FROM a_tbl");
        var fb = sqlScan("SELECT b FROM b_tbl");
        var pred = new com.darkcollective.relix.ast.NullPredicate(
                new com.darkcollective.relix.ast.AttributeOperand("x"), true);
        PhysicalNode plan = new PhysicalNode.ConstructiveCover(
                SCHEMA, 2, List.of(fa, fb), List.of(pred));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                COVER 2 [constructive, 1 conjuncts]
                ├─ PushedScan [jdbc/db] SELECT a FROM a_tbl
                └─ PushedScan [jdbc/db] SELECT b FROM b_tbl
                """);
    }

    @Test
    @DisplayName("a constructive cover with no conjuncts omits the conjunct annotation")
    void constructiveCoverNoConjuncts() {
        var fa = sqlScan("SELECT a FROM a_tbl");
        var fb = sqlScan("SELECT b FROM b_tbl");
        PhysicalNode plan = new PhysicalNode.ConstructiveCover(
                SCHEMA, 3, List.of(fa, fb), List.of());
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                COVER 3 [constructive]
                ├─ PushedScan [jdbc/db] SELECT a FROM a_tbl
                └─ PushedScan [jdbc/db] SELECT b FROM b_tbl
                """);
    }

    @Test
    @DisplayName("the name-only streaming unary operators print their name and nothing else")
    void nameOnlyOperators() {
        var input = sqlScan("SELECT x FROM t");
        assertThat(PhysicalPlanPrinter.explain(new PhysicalNode.Select(
                SCHEMA,
                new com.darkcollective.relix.ast.NullPredicate(
                        new com.darkcollective.relix.ast.AttributeOperand("x"), true),
                input)))
                .startsWith("Select\n");
        assertThat(PhysicalPlanPrinter.explain(new PhysicalNode.Project(SCHEMA, List.of(), input)))
                .startsWith("Project\n");
        assertThat(PhysicalPlanPrinter.explain(new PhysicalNode.Rename(SCHEMA, input)))
                .startsWith("Rename\n");
        assertThat(PhysicalPlanPrinter.explain(new PhysicalNode.Sort(SCHEMA, List.of(), input)))
                .startsWith("Sort\n");
        assertThat(PhysicalPlanPrinter.explain(new PhysicalNode.Division(SCHEMA, input, input)))
                .startsWith("Division\n");
    }

    @Test
    @DisplayName("a non-streaming γ is bare, like the hash δ")
    void hashAggregateIsBare() {
        PhysicalNode plan = new PhysicalNode.Aggregate(
                SCHEMA, List.of(GroupingKey.column("x")), List.of(), false, sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).startsWith("Aggregate\n");
    }

    @Test
    @DisplayName("an ∅ leaf prints Empty and has no children")
    void emptyLabel() {
        assertThat(PhysicalPlanPrinter.explain(new PhysicalNode.Empty(SCHEMA)))
                .isEqualTo("Empty\n");
    }

    @Test
    @DisplayName("a set operation shows its kind")
    void setOpLabel() {
        PhysicalNode plan = new PhysicalNode.SetOp(
                SCHEMA, PhysicalNode.SetKind.UNION_ALL,
                sqlScan("SELECT x FROM a"), sqlScan("SELECT x FROM b"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                SetOp UNION_ALL
                ├─ PushedScan [jdbc/db] SELECT x FROM a
                └─ PushedScan [jdbc/db] SELECT x FROM b
                """);
    }

    @Test
    @DisplayName("a WHY node prints Why; its input is logical, so it draws no children")
    void whyLabel() {
        PhysicalNode plan = new PhysicalNode.Why(
                SCHEMA, new com.darkcollective.relix.ast.RelationNode("Orders"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("Why\n");
    }

    @Test
    @DisplayName("with estimates, every line ends in ~N rows — and ~? where none was made")
    void estimateAnnotations() {
        var child = sqlScan("SELECT x FROM t");
        PhysicalNode root = new PhysicalNode.Distinct(SCHEMA, false, child);
        var estimates = new PlanEstimates();
        estimates.record(root, java.util.OptionalLong.of(1200));
        estimates.record(child, java.util.OptionalLong.empty());

        // ~? is not ~0: "nobody costed this" is not a claim about the data.
        assertThat(PhysicalPlanPrinter.explain(root, estimates)).isEqualTo("""
                Distinct  ~1200 rows
                └─ PushedScan [jdbc/db] SELECT x FROM t  ~? rows
                """);
    }

    @Test
    @DisplayName("without estimates the row column is omitted entirely")
    void noEstimatesOmitsTheColumn() {
        PhysicalNode plan = new PhysicalNode.Distinct(SCHEMA, false, sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan, PlanEstimates.none()))
                .doesNotContain("rows\n")
                .isEqualTo(PhysicalPlanPrinter.explain(plan));
    }

    @Test
    @DisplayName("an unnest shows its column, OUTER, and ordinality column")
    void outerUnnestWithOrdinalityLabel() {
        PhysicalNode plan = new PhysicalNode.Unnest(
                SCHEMA, "tags", true, Optional.of("pos"), sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                Unnest tags OUTER ORDINALITY pos
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a plain unnest shows its column alone")
    void unnestLabel() {
        PhysicalNode plan = new PhysicalNode.Unnest(
                SCHEMA, "tags", false, Optional.empty(), sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                Unnest tags
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a reflexive closure is RCLOSURE, and a target bound reads on the to-column")
    void reflexiveClosureWithTargetBound() {
        PhysicalNode plan = new PhysicalNode.Closure(
                SCHEMA, "src", "dst", false, true,
                java.util.Optional.empty(),
                java.util.Optional.of(new com.darkcollective.relix.ast.NumberOperand("4")),
                sqlScan("SELECT x FROM edges"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                RCLOSURE src→dst [dst=4]
                └─ PushedScan [jdbc/db] SELECT x FROM edges
                """);
    }

    @Test
    @DisplayName("an ungrouped top-k shows its offset and no PER clause")
    void topKOffsetWithoutGrouping() {
        PhysicalNode plan = new PhysicalNode.TopK(
                SCHEMA, List.of(),
                List.of(new com.darkcollective.relix.ast.SortSpecification(
                        "x", com.darkcollective.relix.ast.SortDirection.DESC)),
                Optional.of(5L), 3, sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                TOP 3 OFFSET 5
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("an unpartitioned sessionize omits the PER clause")
    void unpartitionedSessionizeLabel() {
        PhysicalNode plan = new PhysicalNode.Sessionize(
                SCHEMA, "seq", new com.darkcollective.relix.ast.NumberOperand("2"),
                List.of(), "session", sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                SESSIONIZE seq GAP 2 AS session
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a Bernoulli sample shows its probability, and its seed when it has one")
    void bernoulliSampleLabels() {
        PhysicalNode unseeded = new PhysicalNode.BernoulliSample(
                SCHEMA, 0.25, Optional.empty(), sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(unseeded)).startsWith("SAMPLE 0.25\n");

        PhysicalNode seeded = new PhysicalNode.BernoulliSample(
                SCHEMA, 0.5, Optional.of(99L), sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(seeded)).startsWith("SAMPLE 0.5 SEED 99\n");
    }

    @Test
    @DisplayName("a seeded reservoir sample shows its seed")
    void seededReservoirSampleLabel() {
        PhysicalNode plan = new PhysicalNode.ReservoirSample(
                SCHEMA, 100, Optional.of(7L), sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                SAMPLE 100 ROWS SEED 7
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a merge interval join names the /merge strategy")
    void mergeIntervalJoinLabel() {
        PhysicalNode plan = new PhysicalNode.IntervalJoin(
                SCHEMA, com.darkcollective.relix.ast.AllenRelation.INTERSECTS,
                0, 1, 0, 1, true,
                sqlScan("SELECT x FROM events"), sqlScan("SELECT x FROM bookings"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                IntervalJoin INTERSECTS/merge [0,1;0,1]
                ├─ PushedScan [jdbc/db] SELECT x FROM events
                └─ PushedScan [jdbc/db] SELECT x FROM bookings
                """);
    }

    @Test
    @DisplayName("a lateral join shows the TVF name and its argument expressions")
    void lateralJoinLabel() {
        PhysicalNode plan = new PhysicalNode.LateralJoin(
                SCHEMA, "explode",
                List.of(new com.darkcollective.relix.ast.AttributeOperand("id"),
                        new com.darkcollective.relix.ast.NumberOperand("3")),
                args -> sqlScan("SELECT x FROM body"), true,
                sqlScan("SELECT x FROM t"));
        // Only the left input is a physical child; the body is planned per row.
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                LateralJoin explode(id, 3)
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a nullary lateral join prints empty parentheses")
    void nullaryLateralJoinLabel() {
        PhysicalNode plan = new PhysicalNode.LateralJoin(
                SCHEMA, "series", List.of(),
                args -> sqlScan("SELECT x FROM body"), false,
                sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).startsWith("LateralJoin series()\n");
    }

    @Test
    @DisplayName("a downsample shows its bucket, function, grouping keys, and row cap")
    void downsampleLabel() {
        PhysicalNode plan = new PhysicalNode.Downsample(
                SCHEMA, "ts", 300L, com.darkcollective.relix.ast.ConsolidationFunction.AVG,
                List.of("host"), java.util.OptionalLong.of(500), sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                DOWNSAMPLE ts BY 300s USING AVG PER host FOR 500 ROWS
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("an ungrouped, uncapped downsample omits both PER and FOR")
    void bareDownsampleLabel() {
        PhysicalNode plan = new PhysicalNode.Downsample(
                SCHEMA, "ts", 60L, com.darkcollective.relix.ast.ConsolidationFunction.COUNT,
                List.of(), java.util.OptionalLong.empty(), sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                DOWNSAMPLE ts BY 60s USING COUNT
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("an unpivot shows its folded columns and the two output columns")
    void unpivotLabel() {
        PhysicalNode plan = new PhysicalNode.Unpivot(
                SCHEMA, List.of("q1", "q2"), "quarter", "amount", sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                UNPIVOT (q1, q2) AS (quarter, amount)
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a pivot shows its value column, key column, and grouping keys")
    void pivotLabel() {
        PhysicalNode plan = new PhysicalNode.Pivot(
                Schema.open(), "amount", "quarter", List.of("region"), sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                PIVOT amount BY quarter PER region
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("an ungrouped pivot omits the PER clause")
    void ungroupedPivotLabel() {
        PhysicalNode plan = new PhysicalNode.Pivot(
                Schema.open(), "amount", "quarter", List.of(), sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).startsWith("PIVOT amount BY quarter\n");
    }

    @Test
    @DisplayName("an unordered tree omits the ORDER clause")
    void unorderedTreeLabel() {
        PhysicalNode plan = new PhysicalNode.Tree(
                SCHEMA, "node_id", "parent_id", List.of(), "children", sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                TREE node_id BY parent_id AS children
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a tree's descending sibling order is marked DESC; ascending is bare")
    void descendingTreeOrderLabel() {
        PhysicalNode plan = new PhysicalNode.Tree(
                SCHEMA, "node_id", "parent_id",
                List.of(new com.darkcollective.relix.ast.SortSpecification(
                                "rank", com.darkcollective.relix.ast.SortDirection.DESC),
                        new com.darkcollective.relix.ast.SortSpecification(
                                "ordinal", com.darkcollective.relix.ast.SortDirection.ASC)),
                "children", sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                TREE node_id BY parent_id ORDER rank DESC, ordinal AS children
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }

    @Test
    @DisplayName("a spool is drawn once, and its later readers are marked (shared)")
    void spoolIsDrawnOnce() {
        // The shape the planner builds for A ∆ B: one spool per input, read by both
        // differences. Drawing the sub-tree under each reader would show a plan doing
        // twice the work this one does.
        PhysicalNode.Spool a = new PhysicalNode.Spool(SCHEMA, 1, sqlScan("SELECT x FROM a"));
        PhysicalNode.Spool b = new PhysicalNode.Spool(SCHEMA, 2, sqlScan("SELECT x FROM b"));
        PhysicalNode plan = new PhysicalNode.SetOp(SCHEMA, PhysicalNode.SetKind.UNION,
                new PhysicalNode.SetOp(SCHEMA, PhysicalNode.SetKind.DIFFERENCE, a, b),
                new PhysicalNode.SetOp(SCHEMA, PhysicalNode.SetKind.DIFFERENCE, b, a));

        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                SetOp UNION
                ├─ SetOp DIFFERENCE
                │  ├─ Spool #1
                │  │  └─ PushedScan [jdbc/db] SELECT x FROM a
                │  └─ Spool #2
                │     └─ PushedScan [jdbc/db] SELECT x FROM b
                └─ SetOp DIFFERENCE
                   ├─ Spool #2 (shared)
                   └─ Spool #1 (shared)
                """);
    }

    @Test
    @DisplayName("a solve node shows its equation")
    void solveLabel() {
        PhysicalNode plan = new PhysicalNode.Solve(
                SCHEMA,
                new com.darkcollective.relix.ast.AttributeOperand("total"),
                new com.darkcollective.relix.ast.BinaryArithmeticExpression(
                        new com.darkcollective.relix.ast.AttributeOperand("principal"),
                        com.darkcollective.relix.ast.ArithmeticOperator.MULTIPLY,
                        new com.darkcollective.relix.ast.AttributeOperand("rate")),
                sqlScan("SELECT x FROM t"));
        assertThat(PhysicalPlanPrinter.explain(plan)).isEqualTo("""
                SOLVE total = principal * rate
                └─ PushedScan [jdbc/db] SELECT x FROM t
                """);
    }
}

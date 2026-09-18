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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ConsolidationFunction;
import com.darkcollective.relix.json.JsonWriter;
import com.darkcollective.relix.plan.PhysicalNode.BuildSide;
import com.darkcollective.relix.plan.PhysicalNode.JoinAlgorithm;
import com.darkcollective.relix.plan.PhysicalNode.JoinKeys;
import com.darkcollective.relix.plan.PhysicalNode.JoinKind;
import com.darkcollective.relix.plan.PhysicalNode.SetKind;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

final class PhysicalPlanJsonTest {

    // ─── helpers ────────────────────────────────────────────────────────────
    private static final Schema SCHEMA =
            new Schema(List.of(new ColumnDefinition("x", ScalarType.NUMBER)));

    private static PhysicalNode.PushedScan sqlScan(String conn, String sql) {
        return new PhysicalNode.PushedScan(SCHEMA, "jdbc", conn, sql);
    }

    private static PhysicalNode leaf() {
        return sqlScan("db", "SELECT x FROM t");
    }

    @Nested
    class Leaves {

        @Test
        void pushedScanCarriesConnectorTypeConnectionAndQuery() {
            String json = PhysicalPlanJson.toJson(sqlScan("billing", "SELECT x FROM t WHERE x > 1"));
            assertThat(json).isEqualTo(
                    "{\"op\":\"PushedScan\","
                  + "\"connectorType\":\"jdbc\",\"connection\":\"billing\",\"query\":\"SELECT x FROM t WHERE x > 1\","
                  + "\"estimatedRows\":null,"
                  + "\"schema\":{\"open\":false,\"columns\":[{\"name\":\"x\",\"type\":\"N\"}]},"
                  + "\"children\":[]}");
        }

        @Test
        void scanCarriesSourceName() {
            var source = InlineRelationSymbol.of("Orders", SCHEMA, List.of());
            String json = PhysicalPlanJson.toJson(new PhysicalNode.Scan(SCHEMA, source));
            assertThat(json)
                    .contains("\"op\":\"Scan\"")
                    .contains("\"source\":\"Orders\"")
                    .contains("\"children\":[]");
        }
    }

    @Nested
    class Sharing {

        /** The shape the planner builds for {@code A ∆ B}: two spools, two readers each. */
        private static PhysicalNode symmetricDifference() {
            PhysicalNode.Spool a = new PhysicalNode.Spool(SCHEMA, 1, sqlScan("db", "SELECT x FROM a"));
            PhysicalNode.Spool b = new PhysicalNode.Spool(SCHEMA, 2, sqlScan("db", "SELECT x FROM b"));
            return new PhysicalNode.SetOp(SCHEMA, PhysicalNode.SetKind.UNION,
                    new PhysicalNode.SetOp(SCHEMA, PhysicalNode.SetKind.DIFFERENCE, a, b),
                    new PhysicalNode.SetOp(SCHEMA, PhysicalNode.SetKind.DIFFERENCE, b, a));
        }

        @Test
        void spoolCarriesItsId() {
            String json = PhysicalPlanJson.toJson(
                    new PhysicalNode.Spool(SCHEMA, 7, leaf()));
            assertThat(json)
                    .contains("\"op\":\"Spool\"")
                    .contains("\"id\":7");
        }

        @Test
        void laterReadersAreMarkedSharedAndCarryNoChildren() {
            String json = PhysicalPlanJson.toJson(symmetricDifference());

            // Each spool's sub-tree is written once. Writing it per reader would let a
            // consumer count work the plan does once as work it does twice.
            assertThat(json.split("SELECT x FROM a", -1)).hasSize(2);
            assertThat(json.split("SELECT x FROM b", -1)).hasSize(2);
            assertThat(json.split("\"shared\":true", -1))
                    .as("one marked reader per spool")
                    .hasSize(3);
        }

        @Test
        void everyOccurrenceStillCarriesItsId() {
            String json = PhysicalPlanJson.toJson(symmetricDifference());

            // A consumer that wants to know two readers are the same sub-plan needs the
            // id on both of them, including the one written without its children.
            assertThat(json.split("\"id\":1", -1)).hasSize(3);
            assertThat(json.split("\"id\":2", -1)).hasSize(3);
        }
    }

    @Nested
    class PhysicalDecisions {

        @Test
        void joinCarriesKindAlgorithmAndBuildSide() {
            var join = new PhysicalNode.Join(
                    SCHEMA, JoinKind.NATURAL, JoinAlgorithm.HASH, BuildSide.RIGHT,
                    Optional.empty(), JoinKeys.none(), Set.of(), Set.of(),
                    sqlScan("a", "SELECT x FROM a"), sqlScan("b", "SELECT x FROM b"));
            String json = PhysicalPlanJson.toJson(join);
            assertThat(json)
                    .contains("\"op\":\"Join\"")
                    .contains("\"kind\":\"natural\"")
                    .contains("\"algorithm\":\"hash\"")
                    .contains("\"build\":\"right\"");
            // both children present
            assertThat(json).contains("SELECT x FROM a").contains("SELECT x FROM b");
        }

        @Test
        void asOfJoinCarriesDirectionAndStrict() {
            var asOf = new PhysicalNode.AsOfJoin(
                    SCHEMA, JoinKeys.none(), 1, 1, false, true,
                    sqlScan("a", "SELECT x FROM a"), sqlScan("b", "SELECT x FROM b"));
            String json = PhysicalPlanJson.toJson(asOf);
            assertThat(json)
                    .contains("\"op\":\"AsOfJoin\"")
                    .contains("\"direction\":\"forward\"")
                    .contains("\"strict\":true");
        }

        @Test
        void asOfJoinCarriesInnerAndTolerance() {
            var asOf = new PhysicalNode.AsOfJoin(
                    SCHEMA, JoinKeys.none(), 1, 1, true, false,
                    java.util.Optional.of(java.time.Duration.parse("PT30M")), true,
                    com.darkcollective.relix.ast.TieBreak.LAST,
                    sqlScan("a", "SELECT x FROM a"), sqlScan("b", "SELECT x FROM b"));
            String json = PhysicalPlanJson.toJson(asOf);
            assertThat(json)
                    .contains("\"op\":\"AsOfJoin\"")
                    .contains("\"inner\":true")
                    .contains("\"tolerance\":\"PT30M\"");
        }

        @Test
        void asOfJoinCarriesTieBreak() {
            var asOf = new PhysicalNode.AsOfJoin(
                    SCHEMA, JoinKeys.none(), 1, 1, false, false,
                    java.util.Optional.empty(), false,
                    com.darkcollective.relix.ast.TieBreak.FIRST,
                    sqlScan("a", "SELECT x FROM a"), sqlScan("b", "SELECT x FROM b"));
            String json = PhysicalPlanJson.toJson(asOf);
            assertThat(json)
                    .contains("\"op\":\"AsOfJoin\"")
                    .contains("\"tieBreak\":\"first\"");
        }

        @Test
        void intervalJoinCarriesRelationAndIndices() {
            var intervalJoin = new PhysicalNode.IntervalJoin(
                    SCHEMA, com.darkcollective.relix.ast.AllenRelation.INTERSECTS,
                    0, 1, 0, 1,
                    sqlScan("a", "SELECT * FROM events"), sqlScan("b", "SELECT * FROM bookings"));
            String json = PhysicalPlanJson.toJson(intervalJoin);
            assertThat(json)
                    .contains("\"op\":\"IntervalJoin\"")
                    .contains("\"relation\":\"intersects\"")
                    .contains("\"leftStartIdx\":0")
                    .contains("\"leftEndIdx\":1")
                    .contains("\"rightStartIdx\":0")
                    .contains("\"rightEndIdx\":1");
        }

        @Test
        void setOpCarriesKind() {
            var setOp = new PhysicalNode.SetOp(SCHEMA, SetKind.UNION_ALL, leaf(), leaf());
            assertThat(PhysicalPlanJson.toJson(setOp))
                    .contains("\"op\":\"SetOp\"")
                    .contains("\"kind\":\"union_all\"");
        }

        @Test
        void closureCarriesFromToAndReflexive() {
            var closure = new PhysicalNode.Closure(SCHEMA, "src", "dst", false, true, leaf());
            assertThat(PhysicalPlanJson.toJson(closure))
                    .contains("\"op\":\"Closure\"")
                    .contains("\"from\":\"src\"")
                    .contains("\"to\":\"dst\"")
                    .contains("\"reflexive\":true");
        }

        @Test
        void boundedClosureCarriesEndpointBounds() {
            var closure = new PhysicalNode.Closure(SCHEMA, "src", "dst", false, false,
                    java.util.Optional.of(new com.darkcollective.relix.ast.NumberOperand("1")),
                    java.util.Optional.of(new com.darkcollective.relix.ast.NumberOperand("4")),
                    leaf());
            assertThat(PhysicalPlanJson.toJson(closure))
                    .contains("\"op\":\"Closure\"")
                    .contains("\"boundSource\":\"1\"")
                    .contains("\"boundTarget\":\"4\"");
        }

        @Test
        void boundedTraceCarriesEndpointBounds() {
            var trace = new PhysicalNode.Trace(SCHEMA, "src", "dst", false, "cost",
                    com.darkcollective.relix.ast.ObjectiveSense.MINIMIZE, "route",
                    java.util.Optional.of(new com.darkcollective.relix.ast.NumberOperand("1")),
                    java.util.Optional.of(new com.darkcollective.relix.ast.NumberOperand("4")),
                    leaf());
            assertThat(PhysicalPlanJson.toJson(trace))
                    .contains("\"op\":\"Trace\"")
                    .contains("\"boundSource\":\"1\"")
                    .contains("\"boundTarget\":\"4\"");
        }

        @Test
        void clusterCarriesFromToAndLabel() {
            var cluster = new PhysicalNode.Cluster(SCHEMA, "src", "dst", "cid", leaf());
            assertThat(PhysicalPlanJson.toJson(cluster))
                    .contains("\"op\":\"Cluster\"")
                    .contains("\"from\":\"src\"")
                    .contains("\"to\":\"dst\"")
                    .contains("\"label\":\"cid\"");
        }

        @Test
        void pathCarriesEndpointsHopWindowAndDepth() {
            var path = new PhysicalNode.Path(SCHEMA, "src", "dst", false, 1, 3, "depth", leaf());
            assertThat(PhysicalPlanJson.toJson(path))
                    .contains("\"op\":\"Path\"")
                    .contains("\"from\":\"src\"")
                    .contains("\"to\":\"dst\"")
                    .contains("\"minHops\":1")
                    .contains("\"maxHops\":3")
                    .contains("\"depth\":\"depth\"");
        }

        @Test
        void windowCarriesFunctionFrameAndColumn() {
            var win = new PhysicalNode.Window(
                    SCHEMA,
                    new com.darkcollective.relix.ast.WindowFunction.AggregateWindow(
                            com.darkcollective.relix.ast.AggregateOperator.SUM,
                            new com.darkcollective.relix.ast.AttributeOperand("price")),
                    List.of("g"),
                    List.of(new com.darkcollective.relix.ast.SortSpecification(
                            "t", com.darkcollective.relix.ast.SortDirection.ASC)),
                    new com.darkcollective.relix.ast.WindowFrame.BoundedFrame(3),
                    "w", leaf());
            assertThat(PhysicalPlanJson.toJson(win))
                    .contains("\"op\":\"Window\"")
                    .contains("\"kind\":\"rolling\"")
                    .contains("\"function\":\"SUM(price)\"")
                    .contains("\"frame\":\"3 ROWS\"")
                    .contains("\"outputColumn\":\"w\"");
        }

        @Test
        void sessionizeCarriesOrderGapAndColumn() {
            var s = new PhysicalNode.Sessionize(
                    SCHEMA, "seq", new com.darkcollective.relix.ast.NumberOperand("2"),
                    List.of("u"), "session", leaf());
            assertThat(PhysicalPlanJson.toJson(s))
                    .contains("\"op\":\"Sessionize\"")
                    .contains("\"orderColumn\":\"seq\"")
                    .contains("\"threshold\":\"2\"")
                    .contains("\"sessionColumn\":\"session\"");
        }

        @Test
        void treeCarriesKeyParentOrderAndChildren() {
            var t = new PhysicalNode.Tree(
                    SCHEMA, "node_id", "parent_id",
                    List.of(new com.darkcollective.relix.ast.SortSpecification(
                            "ordinal", com.darkcollective.relix.ast.SortDirection.ASC)),
                    "children", leaf());
            assertThat(PhysicalPlanJson.toJson(t))
                    .contains("\"op\":\"Tree\"")
                    .contains("\"keyColumn\":\"node_id\"")
                    .contains("\"parentColumn\":\"parent_id\"")
                    .contains("\"childrenColumn\":\"children\"")
                    .contains("ordinal ASC");
        }

        @Test
        void fixpointCarriesName() {
            var fix = new PhysicalNode.Fixpoint(SCHEMA, "R", leaf(), leaf());
            assertThat(PhysicalPlanJson.toJson(fix))
                    .contains("\"op\":\"Fixpoint\"")
                    .contains("\"name\":\"R\"");
        }

        @Test
        void recursiveRefCarriesName() {
            var ref = new PhysicalNode.RecursiveRef(SCHEMA, "R");
            assertThat(PhysicalPlanJson.toJson(ref))
                    .contains("\"op\":\"RecursiveRef\"")
                    .contains("\"name\":\"R\"");
        }

        @Test
        void universalCarriesGroupingAttributes() {
            var universal = new PhysicalNode.Universal(
                    SCHEMA, List.of("a", "b"),
                    nullPred(attr("x"), false), leaf());
            assertThat(PhysicalPlanJson.toJson(universal))
                    .contains("\"op\":\"Universal\"")
                    .contains("\"groupingAttributes\":[\"a\",\"b\"]");
        }

        @Test
        void optimizeCarriesObjectiveConstraintsAndKeys() {
            var optimize = new PhysicalNode.Optimize(
                    SCHEMA,
                    com.darkcollective.relix.ast.ObjectiveSense.MAXIMIZE,
                    attr("value"),
                    List.of(new com.darkcollective.relix.ast.OptimizeConstraint(
                            attr("weight"),
                            com.darkcollective.relix.ast.ComparisonOperator.LESS_EQUAL, 100)),
                    List.of("region"),
                    java.util.Optional.empty(),
                    leaf());
            assertThat(PhysicalPlanJson.toJson(optimize))
                    .contains("\"op\":\"Optimize\"")
                    .contains("\"sense\":\"maximize\"")
                    .contains("\"objective\":\"value\"")
                    .contains("\"op\":\"LESS_EQUAL\"")
                    .contains("\"bound\":100.0")
                    .contains("\"groupingKeys\":[\"region\"]");
        }

        @Test
        void solveCarriesTheEquation() {
            var solve = new PhysicalNode.Solve(
                    SCHEMA,
                    attr("total"),
                    new com.darkcollective.relix.ast.BinaryArithmeticExpression(
                            attr("principal"),
                            com.darkcollective.relix.ast.ArithmeticOperator.MULTIPLY,
                            attr("rate")),
                    leaf());
            assertThat(PhysicalPlanJson.toJson(solve))
                    .contains("\"op\":\"Solve\"")
                    .contains("\"left\":\"total\"")
                    .contains("\"right\":\"principal * rate\"");
        }

        @Test
        void topKCarriesCountOffsetAndGroupingAttributes() {
            var topK = new PhysicalNode.TopK(
                    SCHEMA, List.of("customer_id"),
                    List.of(new com.darkcollective.relix.ast.SortSpecification(
                            "x", com.darkcollective.relix.ast.SortDirection.DESC)),
                    java.util.Optional.of(5L), 3, leaf());
            assertThat(PhysicalPlanJson.toJson(topK))
                    .contains("\"op\":\"TopK\"")
                    .contains("\"count\":3")
                    .contains("\"offset\":5")
                    .contains("\"groupingAttributes\":[\"customer_id\"]");
        }

        @Test
        void reservoirSampleCarriesCount() {
            var reservoir = new PhysicalNode.ReservoirSample(SCHEMA, 100, java.util.Optional.empty(), leaf());
            assertThat(PhysicalPlanJson.toJson(reservoir))
                    .contains("\"op\":\"ReservoirSample\"")
                    .contains("\"count\":100");
        }

        @Test
        void seededReservoirSampleCarriesSeed() {
            var reservoir = new PhysicalNode.ReservoirSample(SCHEMA, 50, java.util.Optional.of(42L), leaf());
            assertThat(PhysicalPlanJson.toJson(reservoir))
                    .contains("\"op\":\"ReservoirSample\"")
                    .contains("\"count\":50")
                    .contains("\"seed\":42");
        }

        @Test
        void bernoulliSampleCarriesProbability() {
            var bernoulli = new PhysicalNode.BernoulliSample(SCHEMA, 0.25, java.util.Optional.empty(), leaf());
            assertThat(PhysicalPlanJson.toJson(bernoulli))
                    .contains("\"op\":\"BernoulliSample\"")
                    .contains("\"probability\":0.25");
        }

        @Test
        void seededBernoulliSampleCarriesSeed() {
            var bernoulli = new PhysicalNode.BernoulliSample(SCHEMA, 0.5, java.util.Optional.of(99L), leaf());
            assertThat(PhysicalPlanJson.toJson(bernoulli))
                    .contains("\"op\":\"BernoulliSample\"")
                    .contains("\"probability\":0.5")
                    .contains("\"seed\":99");
        }

        @Test
        void coverCarriesStrength() {
            var cover = new PhysicalNode.Cover(SCHEMA, 3, false, leaf());
            assertThat(PhysicalPlanJson.toJson(cover))
                    .contains("\"op\":\"Cover\"")
                    .contains("\"exact\":false")
                    .contains("\"strength\":3");
        }

        @Test
        void exactCoverCarriesExactTrue() {
            var cover = new PhysicalNode.Cover(SCHEMA, 2, true, leaf());
            assertThat(PhysicalPlanJson.toJson(cover))
                    .contains("\"op\":\"Cover\"")
                    .contains("\"exact\":true")
                    .contains("\"strength\":2");
        }

        @Test
        void constructiveCoverCarriesStrengthAndConjunctCount() {
            var pred = nullPred(attr("x"), true);
            var cc = new PhysicalNode.ConstructiveCover(
                    SCHEMA, 2, List.of(leaf(), leaf()), List.of(pred, pred));
            assertThat(PhysicalPlanJson.toJson(cc))
                    .contains("\"op\":\"ConstructiveCover\"")
                    .contains("\"strength\":2")
                    .contains("\"conjunctCount\":2");
        }

        @Test
        void constructiveCoverChildrenAreFactors() {
            var fa = sqlScan("conn", "SELECT a FROM t");
            var fb = sqlScan("conn", "SELECT b FROM t");
            var cc = new PhysicalNode.ConstructiveCover(
                    SCHEMA, 2, List.of(fa, fb), List.of());
            String json = PhysicalPlanJson.toJson(cc);
            assertThat(json)
                    .contains("\"op\":\"ConstructiveCover\"")
                    .contains("\"conjunctCount\":0");
            // Both factor children are embedded in the "children" array.
            long factorCount = json.chars().filter(c -> c == '{').count()
                    - 1; // subtract the root object
            assertThat(factorCount).isGreaterThanOrEqualTo(2);
        }

        @Test
        void unnestCarriesColumnAndOuter() {
            var unnest = new PhysicalNode.Unnest(SCHEMA, "tags", true, Optional.empty(), leaf());
            assertThat(PhysicalPlanJson.toJson(unnest))
                    .contains("\"op\":\"Unnest\"")
                    .contains("\"column\":\"tags\"")
                    .contains("\"outer\":true")
                    .doesNotContain("\"ordinality\"");
        }

        @Test
        void unnestWithOrdinalityCarriesOrdinalityColumn() {
            var unnest = new PhysicalNode.Unnest(SCHEMA, "tags", false, Optional.of("pos"), leaf());
            assertThat(PhysicalPlanJson.toJson(unnest))
                    .contains("\"op\":\"Unnest\"")
                    .contains("\"ordinality\":\"pos\"");
        }

        @Test
        void limitWithOffsetCarriesCountAndOffset() {
            var limit = new PhysicalNode.Limit(SCHEMA, Optional.of(5L), 10, leaf());
            assertThat(PhysicalPlanJson.toJson(limit))
                    .contains("\"op\":\"Limit\"")
                    .contains("\"count\":10")
                    .contains("\"offset\":5");
        }

        @Test
        void limitWithoutOffsetEmitsNullOffset() {
            var limit = new PhysicalNode.Limit(SCHEMA, Optional.empty(), 10, leaf());
            assertThat(PhysicalPlanJson.toJson(limit))
                    .contains("\"count\":10")
                    .contains("\"offset\":null");
        }
    }

    @Nested
    class PlainOperators {
        // Operators that surface no extra physical fields — exercise every arm
        // of the exhaustive switch.

        @Test
        void select() {
            var node = new PhysicalNode.Select(
                    SCHEMA, nullPred(attr("x"), true), leaf());
            assertThat(PhysicalPlanJson.toJson(node)).contains("\"op\":\"Select\"");
        }

        @Test
        void project() {
            var node = new PhysicalNode.Project(SCHEMA, List.of(), leaf());
            assertThat(PhysicalPlanJson.toJson(node)).contains("\"op\":\"Project\"");
        }

        @Test
        void rename() {
            assertThat(PhysicalPlanJson.toJson(new PhysicalNode.Rename(SCHEMA, leaf())))
                    .contains("\"op\":\"Rename\"");
        }

        @Test
        void distinct() {
            assertThat(PhysicalPlanJson.toJson(new PhysicalNode.Distinct(SCHEMA, false, leaf())))
                    .contains("\"op\":\"Distinct\"")
                    .contains("\"streaming\":false");
        }

        @Test
        void streamingDistinct() {
            assertThat(PhysicalPlanJson.toJson(new PhysicalNode.Distinct(SCHEMA, true, leaf())))
                    .contains("\"op\":\"Distinct\"")
                    .contains("\"streaming\":true");
        }

        @Test
        void sort() {
            var node = new PhysicalNode.Sort(SCHEMA, List.of(), leaf());
            assertThat(PhysicalPlanJson.toJson(node)).contains("\"op\":\"Sort\"");
        }

        @Test
        void aggregate() {
            var node = new PhysicalNode.Aggregate(SCHEMA, List.of(), List.of(), false, leaf());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"op\":\"Aggregate\"")
                    .contains("\"streaming\":false");
        }

        @Test
        void division() {
            var node = new PhysicalNode.Division(SCHEMA, leaf(), leaf());
            assertThat(PhysicalPlanJson.toJson(node)).contains("\"op\":\"Division\"");
        }
    }

    @Nested
    class RotationsAndDownsampling {
        // Operators whose detail is emitted only from the switch's later arms.

        @Test
        void downsampleCarriesBucketFunctionKeysAndMaxRows() {
            var node = new PhysicalNode.Downsample(
                    SCHEMA, "ts", 300L, ConsolidationFunction.AVG,
                    List.of("host"), OptionalLong.of(500), leaf());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"op\":\"Downsample\"")
                    .contains("\"timestampColumn\":\"ts\"")
                    .contains("\"intervalSeconds\":300")
                    .contains("\"function\":\"avg\"")
                    .contains("\"groupingKeys\":[\"host\"]")
                    .contains("\"maxRows\":500");
        }

        @Test
        void ungroupedDownsampleOmitsKeysAndMaxRows() {
            var node = new PhysicalNode.Downsample(
                    SCHEMA, "ts", 60L, ConsolidationFunction.COUNT,
                    List.of(), OptionalLong.empty(), leaf());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"op\":\"Downsample\"")
                    .contains("\"function\":\"count\"")
                    .doesNotContain("\"groupingKeys\"")
                    .doesNotContain("\"maxRows\"");
        }

        @Test
        void lateralJoinCarriesFunctionNameAndArguments() {
            var node = new PhysicalNode.LateralJoin(
                    SCHEMA, "explode",
                    List.of(attr("id"),
                            new com.darkcollective.relix.ast.NumberOperand("3")),
                    args -> leaf(), true, leaf());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"op\":\"LateralJoin\"")
                    .contains("\"functionName\":\"explode\"")
                    .contains("\"arguments\":[\"id\",\"3\"]");
        }

        @Test
        void nullaryLateralJoinCarriesAnEmptyArgumentArray() {
            var node = new PhysicalNode.LateralJoin(
                    SCHEMA, "series", List.of(), args -> leaf(), false, leaf());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"op\":\"LateralJoin\"")
                    .contains("\"arguments\":[]");
        }

        @Test
        void unpivotCarriesColumnsNameAndValue() {
            var node = new PhysicalNode.Unpivot(
                    SCHEMA, List.of("q1", "q2"), "quarter", "amount", leaf());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"op\":\"Unpivot\"")
                    .contains("\"columns\":[\"q1\",\"q2\"]")
                    .contains("\"nameColumn\":\"quarter\"")
                    .contains("\"valueColumn\":\"amount\"");
        }

        @Test
        void pivotCarriesValueKeyAndGroupKeys() {
            var node = new PhysicalNode.Pivot(
                    Schema.open(), "amount", "quarter", List.of("region"), leaf());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"op\":\"Pivot\"")
                    .contains("\"valueColumn\":\"amount\"")
                    .contains("\"keyColumn\":\"quarter\"")
                    .contains("\"groupKeys\":[\"region\"]");
        }

        @Test
        void ungroupedPivotOmitsGroupKeys() {
            var node = new PhysicalNode.Pivot(
                    Schema.open(), "amount", "quarter", List.of(), leaf());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"op\":\"Pivot\"")
                    .doesNotContain("\"groupKeys\"");
        }
    }

    @Nested
    class OptionalDetail {
        // The absent/present half of each optional field the earlier tests miss.

        @Test
        void scanCarriesItsProductionBound() {
            var source = InlineRelationSymbol.of("Naturals", SCHEMA, List.of());
            var scan = new PhysicalNode.Scan(SCHEMA, source, Optional.of(
                    new com.darkcollective.relix.ast.ProduceBound(
                            "n", com.darkcollective.relix.ast.ComparisonOperator.LESS_EQUAL,
                            new com.darkcollective.relix.ast.NumberOperand("100"))));
            assertThat(PhysicalPlanJson.toJson(scan))
                    .contains("\"op\":\"Scan\"")
                    .contains("\"produceWhile\":\"n LESS_EQUAL 100\"");
        }

        @Test
        void unboundedScanOmitsProduceWhile() {
            var source = InlineRelationSymbol.of("Orders", SCHEMA, List.of());
            assertThat(PhysicalPlanJson.toJson(new PhysicalNode.Scan(SCHEMA, source)))
                    .doesNotContain("\"produceWhile\"");
        }

        @Test
        void allocatingOptimizeCarriesItsAllocationSpec() {
            var optimize = new PhysicalNode.Optimize(
                    SCHEMA,
                    com.darkcollective.relix.ast.ObjectiveSense.MINIMIZE,
                    attr("cost"),
                    List.of(),
                    List.of(),
                    Optional.of(new com.darkcollective.relix.ast.AllocationSpec(0.0, 1.0, "share")),
                    leaf());
            assertThat(PhysicalPlanJson.toJson(optimize))
                    .contains("\"op\":\"Optimize\"")
                    .contains("\"allocation\":{\"lo\":0.0,\"hi\":1.0,\"column\":\"share\"}");
        }

        @Test
        void topKWithoutOffsetEmitsNullOffset() {
            var topK = new PhysicalNode.TopK(
                    SCHEMA, List.of(),
                    List.of(new com.darkcollective.relix.ast.SortSpecification(
                            "x", com.darkcollective.relix.ast.SortDirection.DESC)),
                    Optional.empty(), 3, leaf());
            assertThat(PhysicalPlanJson.toJson(topK))
                    .contains("\"op\":\"TopK\"")
                    .contains("\"count\":3")
                    .contains("\"offset\":null")
                    .contains("\"groupingAttributes\":[]");
        }

        @Test
        void unpartitionedSessionizeOmitsPartitionKeys() {
            var s = new PhysicalNode.Sessionize(
                    SCHEMA, "seq", new com.darkcollective.relix.ast.NumberOperand("2"),
                    List.of(), "session", leaf());
            assertThat(PhysicalPlanJson.toJson(s))
                    .contains("\"op\":\"Sessionize\"")
                    .doesNotContain("\"partitionKeys\"");
        }

        @Test
        void unorderedTreeOmitsOrderSpecs() {
            var t = new PhysicalNode.Tree(
                    SCHEMA, "node_id", "parent_id", List.of(), "children", leaf());
            assertThat(PhysicalPlanJson.toJson(t))
                    .contains("\"op\":\"Tree\"")
                    .doesNotContain("\"orderSpecs\"");
        }

        @Test
        void descendingTreeOrderSpecIsLabelledDesc() {
            var t = new PhysicalNode.Tree(
                    SCHEMA, "node_id", "parent_id",
                    List.of(new com.darkcollective.relix.ast.SortSpecification(
                            "ordinal", com.darkcollective.relix.ast.SortDirection.DESC)),
                    "children", leaf());
            assertThat(PhysicalPlanJson.toJson(t)).contains("\"orderSpecs\":[\"ordinal DESC\"]");
        }

        @Test
        void whyAddsNoFieldsAndHasNoPhysicalChildren() {
            // WHY carries its input as a *logical* subtree, so children() is empty and
            // the appended provenance column is visible only in the schema.
            var why = new PhysicalNode.Why(
                    SCHEMA, new com.darkcollective.relix.ast.RelationNode("Orders"));
            assertThat(PhysicalPlanJson.toJson(why))
                    .isEqualTo("{\"op\":\"Why\","
                             + "\"estimatedRows\":null,"
                             + "\"schema\":{\"open\":false,\"columns\":[{\"name\":\"x\",\"type\":\"N\"}]},"
                             + "\"children\":[]}");
        }

        @Test
        void emptyRelationCarriesOnlyItsHeading() {
            assertThat(PhysicalPlanJson.toJson(new PhysicalNode.Empty(SCHEMA)))
                    .isEqualTo("{\"op\":\"Empty\","
                             + "\"estimatedRows\":null,"
                             + "\"schema\":{\"open\":false,\"columns\":[{\"name\":\"x\",\"type\":\"N\"}]},"
                             + "\"children\":[]}");
        }
    }

    @Nested
    class WindowLabels {
        // windowFunctionLabel / windowFrameLabel — one case per sealed alternative.

        private PhysicalNode window(
                com.darkcollective.relix.ast.WindowFunction fn,
                com.darkcollective.relix.ast.WindowFrame frame,
                List<String> partitionKeys,
                List<com.darkcollective.relix.ast.SortSpecification> sortSpecs) {
            return new PhysicalNode.Window(
                    SCHEMA, fn, partitionKeys, sortSpecs, frame, "w", leaf());
        }

        @Test
        void rankingWindowIsLabelledWindowNotRolling() {
            var node = window(
                    new com.darkcollective.relix.ast.WindowFunction.RankingWindow(
                            com.darkcollective.relix.ast.RankingFunction.ROW_NUMBER,
                            Optional.empty()),
                    new com.darkcollective.relix.ast.WindowFrame.PartitionFrame(),
                    List.of(),
                    List.of(new com.darkcollective.relix.ast.SortSpecification(
                            "t", com.darkcollective.relix.ast.SortDirection.DESC)));
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"kind\":\"window\"")
                    .contains("\"function\":\"ROW_NUMBER()\"")
                    .contains("\"frame\":\"PARTITION\"")
                    .contains("\"sortSpecs\":[\"t DESC\"]")
                    .doesNotContain("\"partitionKeys\"");
        }

        @Test
        void ntileCarriesItsBucketCount() {
            var node = window(
                    new com.darkcollective.relix.ast.WindowFunction.RankingWindow(
                            com.darkcollective.relix.ast.RankingFunction.NTILE,
                            Optional.of(new com.darkcollective.relix.ast.NumberOperand("4"))),
                    new com.darkcollective.relix.ast.WindowFrame.PartitionFrame(),
                    List.of("region"),
                    List.of());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"function\":\"NTILE(4)\"")
                    .contains("\"partitionKeys\":[\"region\"]");
        }

        @Test
        void offsetWindowCarriesExpressionOffsetAndDefault() {
            var node = window(
                    new com.darkcollective.relix.ast.WindowFunction.OffsetWindow(
                            com.darkcollective.relix.ast.OffsetFunction.LAG,
                            attr("amount"),
                            Optional.of(new com.darkcollective.relix.ast.NumberOperand("2")),
                            Optional.of(new com.darkcollective.relix.ast.NumberOperand("0"))),
                    new com.darkcollective.relix.ast.WindowFrame.PartitionFrame(),
                    List.of(),
                    List.of());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"function\":\"LAG(amount, 2, 0)\"");
        }

        @Test
        void offsetWindowWithoutOffsetOrDefaultLabelsTheExpressionAlone() {
            var node = window(
                    new com.darkcollective.relix.ast.WindowFunction.OffsetWindow(
                            com.darkcollective.relix.ast.OffsetFunction.FIRST_VALUE,
                            attr("amount"),
                            Optional.empty(), Optional.empty()),
                    new com.darkcollective.relix.ast.WindowFrame.PartitionFrame(),
                    List.of(),
                    List.of());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"function\":\"FIRST_VALUE(amount)\"");
        }

        @Test
        void cumulativeFrameIsLabelledAllRows() {
            var node = window(
                    new com.darkcollective.relix.ast.WindowFunction.AggregateWindow(
                            com.darkcollective.relix.ast.AggregateOperator.SUM,
                            attr("amount")),
                    new com.darkcollective.relix.ast.WindowFrame.CumulativeFrame(),
                    List.of(),
                    List.of());
            assertThat(PhysicalPlanJson.toJson(node))
                    .contains("\"kind\":\"rolling\"")
                    .contains("\"frame\":\"ALL ROWS\"");
        }
    }

    @Nested
    class Estimates {

        @Test
        void aCostedNodeCarriesItsRowEstimate() {
            var estimates = new PlanEstimates();
            PhysicalNode root = leaf();
            estimates.record(root, OptionalLong.of(42));
            assertThat(PhysicalPlanJson.toJson(root, estimates)).contains("\"estimatedRows\":42");
        }

        @Test
        void anUncostedNodeIsNullNotZero() {
            var estimates = new PlanEstimates();
            PhysicalNode root = leaf();
            estimates.record(root, OptionalLong.empty());
            assertThat(PhysicalPlanJson.toJson(root, estimates)).contains("\"estimatedRows\":null");
        }

        @Test
        void writeRejectsNullEstimates() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PhysicalPlanJson.write(new JsonWriter(), leaf(), null));
        }
    }

    @Nested
    class SchemaEncoding {

        @Test
        void emitsEachScalarTypeCode() {
            var schema = new Schema(List.of(
                    new ColumnDefinition("n", ScalarType.NUMBER),
                    new ColumnDefinition("s", ScalarType.STRING),
                    new ColumnDefinition("b", ScalarType.BOOLEAN),
                    new ColumnDefinition("a", ScalarType.ANY)));
            String json = PhysicalPlanJson.toJson(new PhysicalNode.PushedScan(schema, "jdbc", "db", "SELECT * FROM t"));
            assertThat(json).contains(
                    "{\"name\":\"n\",\"type\":\"N\"},"
                  + "{\"name\":\"s\",\"type\":\"S\"},"
                  + "{\"name\":\"b\",\"type\":\"B\"},"
                  + "{\"name\":\"a\",\"type\":\"?\"}");
        }

        @Test
        void openSchemaRendersEmptyColumns() {
            String json = PhysicalPlanJson.toJson(new PhysicalNode.Scan(
                    Schema.open(), InlineRelationSymbol.of("Docs", SCHEMA, List.of())));
            assertThat(json).contains("\"schema\":{\"open\":true,\"columns\":[]}");
        }
    }

    @Nested
    class Embedding {

        @Test
        void writeEmbedsIntoAnExistingWriter() {
            JsonWriter w = new JsonWriter();
            w.beginObject().name("physicalPlan");
            PhysicalPlanJson.write(w, leaf());
            w.endObject();
            assertThat(w.toJson())
                    .startsWith("{\"physicalPlan\":{\"op\":\"PushedScan\"")
                    .endsWith("}}");
        }

        @Test
        void writeRejectsNullWriter() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PhysicalPlanJson.write(null, leaf()));
        }

        @Test
        void writeRejectsNullRoot() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PhysicalPlanJson.write(new JsonWriter(), null));
        }
    }
}

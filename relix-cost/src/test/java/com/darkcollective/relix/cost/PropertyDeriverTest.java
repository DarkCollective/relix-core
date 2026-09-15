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
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ObjectiveSense;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PropertyDeriver — bottom-up distinctness")
final class PropertyDeriverTest {

    // ─── helpers ────────────────────────────────────────────────────────────

    private static Predicate pred() {
        return cmp(attr("x"),
                ComparisonOperator.GREATER, num("0"));
    }

    private static boolean duplicateFree(RelNode node) {
        return PropertyDeriver.derive(node).isDuplicateFree();
    }

    // =========================================================================
    // Establishers — output is a set (whole-row distinct)
    // =========================================================================

    @Nested
    @DisplayName("set-producing operators establish whole-row distinctness")
    class Establishers {

        @Test void distinct()        { assertWholeRow(AstBuilders.distinct(rel("R"))); }
        @Test void union()           { assertWholeRow(AstBuilders.union(rel("A"), rel("B"))); }
        @Test void outerUnion()      { assertWholeRow(AstBuilders.outerUnion(rel("A"), rel("B"))); }
        @Test void intersection()    { assertWholeRow(AstBuilders.intersection(rel("A"), rel("B"))); }
        @Test void difference()      { assertWholeRow(AstBuilders.difference(rel("A"), rel("B"))); }
        @Test void symmetricDiff()   { assertWholeRow(symmetricDifference(rel("A"), rel("B"))); }
        @Test void division()        { assertWholeRow(AstBuilders.division(rel("A"), rel("B"))); }
        @Test void closure()         { assertWholeRow(AstBuilders.closure("src", "dst",rel("E"))); }
        @Test void cluster()         { assertWholeRow(AstBuilders.cluster("src", "dst", "cid",rel("E"))); }
        @Test void path()            { assertWholeRow(AstBuilders.path("src", "dst", 1, 3, "depth",rel("E"))); }

        private static void assertWholeRow(RelNode node) {
            RelationProperties p = PropertyDeriver.derive(node);
            assertThat(p.isDuplicateFree()).isTrue();
            assertThat(p.wholeRowDistinct()).isTrue();
        }
    }

    // =========================================================================
    // Key establishers — grouping operators are unique on their grouping keys
    // =========================================================================

    @Nested
    @DisplayName("grouping operators establish a candidate key")
    class KeyEstablishers {

        private static final AggregateFunction SUM =
                AggregateFunction.simple(AggregateOperator.SUM, "amount");

        @Test
        @DisplayName("γ is unique on its grouping keys")
        void aggregation() {
            var node = groupBy(List.of("region", "dept"), List.of(SUM), rel("R"));
            RelationProperties p = PropertyDeriver.derive(node);
            assertThat(p.isDuplicateFree()).isTrue();
            assertThat(p.keys()).containsExactly(Set.of("region", "dept"));
        }

        @Test
        @DisplayName("scalar γ (no grouping keys) is at-most-one-row → duplicate-free")
        void scalarAggregation() {
            var node = groupBy(List.of(), List.of(SUM), rel("R"));
            assertThat(duplicateFree(node)).isTrue();
        }

        @Test
        @DisplayName("∀ is unique on its grouping keys")
        void universal() {
            var node = AstBuilders.universal(List.of("region"), pred(), rel("R"));
            RelationProperties p = PropertyDeriver.derive(node);
            assertThat(p.isDuplicateFree()).isTrue();
            assertThat(p.keys()).containsExactly(Set.of("region"));
        }
    }

    // =========================================================================
    // Preservers — distinctness carried through row-subset operators
    // =========================================================================

    @Nested
    @DisplayName("row-subset operators preserve the input's distinctness")
    class Preservers {

        private static RelNode distinct() { return AstBuilders.distinct(rel("R")); }

        @Test void selection()  { assertThat(duplicateFree(select(pred(), distinct()))).isTrue(); }
        @Test void sort()       { assertThat(duplicateFree(AstBuilders.sort(List.of(asc("id")), distinct()))).isTrue(); }
        @Test void limit()      { assertThat(duplicateFree(AstBuilders.limit(10L, distinct()))).isTrue(); }
        @Test void sample()     { assertThat(duplicateFree(AstBuilders.sample(0.5, distinct()))).isTrue(); }
        @Test void reservoir()  { assertThat(duplicateFree(reservoirSample(10L, distinct()))).isTrue(); }
        @Test void topK()       { assertThat(duplicateFree(AstBuilders.topK(List.of("region"), List.of(asc("id")), 3L, distinct()))).isTrue(); }

        @Test
        void optimize() {
            var node = AstBuilders.optimize(ObjectiveSense.MAXIMIZE, attr("v"),
                    List.of(constraint(attr("w"),
                            ComparisonOperator.LESS_EQUAL, 10.0)),
                    List.of("region"), distinct());
            assertThat(duplicateFree(node)).isTrue();
        }

        @Test
        @DisplayName("⋉ / ▷ / USEMI preserve the LEFT input's distinctness")
        void semiAndAntiJoinPreserveLeft() {
            assertThat(duplicateFree(semiJoin(distinct(), rel("B"), pred()))).isTrue();
            assertThat(duplicateFree(antiJoin(distinct(), rel("B"), pred()))).isTrue();
            assertThat(duplicateFree(pairwiseUniversal(distinct(), rel("B"), pred()))).isTrue();
            // ...but not when the left input is not distinct
            assertThat(duplicateFree(semiJoin(rel("A"), rel("B"), pred()))).isFalse();
            assertThat(duplicateFree(pairwiseUniversal(rel("A"), rel("B"), pred()))).isFalse();
        }

        @Test
        @DisplayName("a preserver over a non-distinct base relation stays non-distinct")
        void preserverOverBaseStaysUnknown() {
            assertThat(duplicateFree(select(pred(), rel("R")))).isFalse();
        }

        @Test
        @DisplayName("relation-only rename passes the candidate key through unchanged")
        void renameRelationOnly() {
            var grouped = groupBy(List.of("region"),
                    List.of(AggregateFunction.simple(AggregateOperator.SUM, "amount")), rel("R"));
            RelationProperties p = PropertyDeriver.derive(rename("X", List.of(), grouped));
            assertThat(p.keys()).containsExactly(Set.of("region"));
        }

        @Test
        @DisplayName("column-renaming rename keeps distinctness as whole-row distinct")
        void renameColumns() {
            RelationProperties p = PropertyDeriver.derive(
                    rename("X", List.of("a", "b"), AstBuilders.distinct(rel("R"))));
            assertThat(p.isDuplicateFree()).isTrue();
            assertThat(p.wholeRowDistinct()).isTrue();
        }

        @Test
        @DisplayName("column-renaming rename over a non-distinct input stays non-distinct")
        void renameColumnsOverBase() {
            assertThat(duplicateFree(rename("X", List.of("a"), rel("R")))).isFalse();
        }
    }

    // =========================================================================
    // Breakers — conservatively not duplicate-free
    // =========================================================================

    @Nested
    @DisplayName("operators that may introduce duplicates break distinctness")
    class Breakers {

        // Feed duplicate-free inputs to prove the break is structural, not input-driven.
        private static RelNode d() { return distinct(rel("R")); }

        @Test void baseRelation()   { assertThat(duplicateFree(rel("R"))).isFalse(); }
        @Test void projection()     { assertThat(duplicateFree(project(
                List.of(projected(attr("id"))), d()))).isFalse(); }
        @Test void unnest()         { assertThat(duplicateFree(AstBuilders.unnest("tags", d()))).isFalse(); }
        @Test void solve()          { assertThat(duplicateFree(AstBuilders.solve(
                attr("a"), attr("b"), d()))).isFalse(); }
        @Test void naturalJoin()    { assertThat(duplicateFree(AstBuilders.naturalJoin(d(), d()))).isFalse(); }
        @Test void thetaJoin()      { assertThat(duplicateFree(join(d(), d(), pred()))).isFalse(); }
        @Test void leftOuter()      { assertThat(duplicateFree(leftJoin(d(), d(), pred()))).isFalse(); }
        @Test void rightOuter()     { assertThat(duplicateFree(rightJoin(d(), d(), pred()))).isFalse(); }
        @Test void fullOuter()      { assertThat(duplicateFree(fullJoin(d(), d(), pred()))).isFalse(); }
        @Test void product()        { assertThat(duplicateFree(AstBuilders.product(d(), d()))).isFalse(); }
        @Test void composition()    { assertThat(duplicateFree(AstBuilders.composition(d(), d()))).isFalse(); }
        @Test void unionAll()       { assertThat(duplicateFree(AstBuilders.unionAll(d(), d()))).isFalse(); }
        @Test void window()         { assertThat(duplicateFree(AstBuilders.window(
                new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("amount")),
                List.of("g"), List.of(asc("t")), new WindowFrame.BoundedFrame(3), "w", d()))).isFalse(); }
        // WHY appends a provenance column and is a hard barrier — distinctness derived
        // conservatively even over a duplicate-free input.
        @Test void why()            { assertThat(duplicateFree(
                new com.darkcollective.relix.ast.WhyNode(d()))).isFalse(); }
    }

    // =========================================================================
    // Recursion
    // =========================================================================

    @Test
    @DisplayName("distinctness propagates through a chain of preservers above an establisher")
    void deepChain() {
        // δ-free fact at γ carries up through σ and τ.
        var grouped = groupBy(List.of("region"),
                List.of(AggregateFunction.simple(AggregateOperator.SUM, "amount")), rel("R"));
        var chain = sort(List.of(asc("region")), select(pred(), grouped));
        assertThat(duplicateFree(chain)).isTrue();
    }

    // =========================================================================
    // Boundedness — leaf source, contagious-up propagation, λ rescue
    // =========================================================================

    @Nested
    @DisplayName("boundedness derivation")
    class BoundednessDerivation {

        /** A stub source under which the leaf named "Inf" is unbounded, all else bounded. */
        private static final BoundednessSource SRC =
                name -> "Inf".equals(name) ? Boundedness.UNBOUNDED : Boundedness.BOUNDED;

        private static RelationNode inf() { return rel("Inf"); }

        private static Boundedness boundedness(RelNode node) {
            return PropertyDeriver.boundedness(node, SRC);
        }

        @Test
        @DisplayName("a leaf takes its boundedness from the source")
        void leaf() {
            assertThat(boundedness(inf())).isEqualTo(Boundedness.UNBOUNDED);
            assertThat(boundedness(rel("R"))).isEqualTo(Boundedness.BOUNDED);
        }

        @Test
        @DisplayName("the single-arg derive assumes every leaf is BOUNDED")
        void allBoundedDefault() {
            assertThat(PropertyDeriver.derive(inf()).boundedness()).isEqualTo(Boundedness.BOUNDED);
        }

        @Test
        @DisplayName("unboundedness is contagious upward through operators")
        void contagiousUpward() {
            assertThat(boundedness(select(pred(), inf()))).isEqualTo(Boundedness.UNBOUNDED);
            assertThat(boundedness(groupBy(List.of("region"),
                    List.of(AggregateFunction.simple(AggregateOperator.SUM, "amount")), inf())))
                    .isEqualTo(Boundedness.UNBOUNDED);
            // a binary operator with one unbounded side is unbounded
            assertThat(boundedness(join(rel("R"), inf(), pred())))
                    .isEqualTo(Boundedness.UNBOUNDED);
            // WHY is contagious upward like any non-λ operator
            assertThat(boundedness(new com.darkcollective.relix.ast.WhyNode(inf())))
                    .isEqualTo(Boundedness.UNBOUNDED);
            // ...and stays bounded when every input is bounded
            assertThat(boundedness(join(rel("R"), rel("S"), pred())))
                    .isEqualTo(Boundedness.BOUNDED);
        }

        @Test
        @DisplayName("a bounded-frame window bounds its input (like λ); a cumulative one is contagious")
        void windowBoundedness() {
            assertThat(boundedness(window(
                    new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("amount")),
                    List.of("g"), List.of(asc("t")), new WindowFrame.BoundedFrame(3), "w", inf())))
                    .isEqualTo(Boundedness.BOUNDED);
            assertThat(boundedness(window(
                    new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("amount")),
                    List.of("g"), List.of(asc("t")), new WindowFrame.CumulativeFrame(), "w", inf())))
                    .isEqualTo(Boundedness.UNBOUNDED);
        }

        @Test
        @DisplayName("λ (LIMIT) bounds its input — the rescue")
        void limitRescue() {
            assertThat(boundedness(AstBuilders.limit(10L, inf())))
                    .isEqualTo(Boundedness.BOUNDED);
            // a blocking op over a bounded λ is therefore over a bounded input
            assertThat(boundedness(groupBy(List.of("region"),
                    List.of(AggregateFunction.simple(AggregateOperator.SUM, "amount")),
                    AstBuilders.limit(10L, inf()))))
                    .isEqualTo(Boundedness.BOUNDED);
        }

        @Test
        @DisplayName("derive(node, source) carries both distinctness and boundedness")
        void combinedProperties() {
            RelationProperties p = PropertyDeriver.derive(distinct(inf()), SRC);
            assertThat(p.isDuplicateFree()).isTrue();                  // δ → whole-row distinct
            assertThat(p.boundedness()).isEqualTo(Boundedness.UNBOUNDED);
        }
    }

    @Nested
    @DisplayName("distinctness leaf seam (DistinctnessSource)")
    class DistinctnessLeaf {

        /** A stub source under which only the leaf named "Gen" is duplicate-free. */
        private static final DistinctnessSource SRC = name -> "Gen".equals(name);

        @Test
        @DisplayName("a leaf the source marks duplicate-free derives whole-row distinct")
        void distinctLeaf() {
            assertThat(PropertyDeriver.derive(rel("Gen"), SRC).isDuplicateFree()).isTrue();
        }

        @Test
        @DisplayName("any other leaf is conservatively not distinct")
        void otherLeaf() {
            assertThat(PropertyDeriver.derive(rel("R"), SRC).isDuplicateFree()).isFalse();
        }

        @Test
        @DisplayName("leaf distinctness carries up through order-preserving operators")
        void carriesThroughRename() {
            // ρ NewName (Gen) — relation-only rename preserves distinctness
            var renamed = rename("NewName", List.of(), rel("Gen"));
            assertThat(PropertyDeriver.derive(renamed, SRC).isDuplicateFree()).isTrue();
        }

        @Test
        @DisplayName("the no-source default (NONE) leaves every leaf not distinct")
        void noneDefault() {
            assertThat(PropertyDeriver.derive(rel("Gen")).isDuplicateFree()).isFalse();
        }
    }
}

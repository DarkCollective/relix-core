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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.SortDirection;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import com.darkcollective.relix.ast.RankingFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Rename elimination — RENAME-001..004")
final class RenameEliminationPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    private RelNode apply(RelNode node) {
        return RenameEliminationPass.apply(node, "Q", ctx);
    }

    private boolean collapsed() {
        return !ctx.recordsFor(OptimizationCode.RENAME_001).isEmpty();
    }

    private boolean removed() {
        return !ctx.recordsFor(OptimizationCode.RENAME_002).isEmpty();
    }

    private int firings(OptimizationCode code) {
        return ctx.recordsFor(code).size();
    }

    /** A pair-form ρ with no relation name — the textbook ρ_{a→b}(R). */
    private static RenameNode pairs(RelNode input, String... fromTo) {
        var list = new java.util.ArrayList<RenameNode.RenamePair>();
        for (int i = 0; i < fromTo.length; i += 2) {
            list.add(new RenameNode.RenamePair(fromTo[i], fromTo[i + 1]));
        }
        return rename(Optional.empty(), List.of(), list, input);
    }

    /** A pair-form ρ that also re-anchors provenance to {@code name}. */
    private static RenameNode namedPairs(String name, RelNode input, String... fromTo) {
        RenameNode bare = pairs(input, fromTo);
        return rename(Optional.of(name), List.of(), bare.pairs(), input);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    /** A relation-only ρ — the shape {@code ViewInliner} produces. */
    private static RenameNode alias(String name, RelNode input) {
        return rename(name, List.of(), input);
    }

    private static Predicate eq(String column, String value) {
        return cmp(attr(column), ComparisonOperator.EQUAL,
                num(value));
    }

    // =========================================================================
    // RENAME-002 — dropping an unreferenced alias
    // =========================================================================

    @Nested
    @DisplayName("RENAME-002 — a relation-only ρ nothing names")
    class Rename002 {

        @Test
        @DisplayName("an unreferenced alias over a leaf is removed")
        void unreferencedAliasRemoved() {
            RelNode result = apply(alias("V", rel("Orders")));

            assertThat(result).isEqualTo(rel("Orders"));
            assertThat(removed()).isTrue();
        }

        @Test
        @DisplayName("the alias is removed from the middle of a σ chain, leaving the σ pair adjacent")
        void removedFromMiddleOfChain() {
            // σ a = 1 (ρ V (σ b = 2 (Orders))) — the ρ is exactly what stops the two
            // selections being adjacent for SEL-002.
            RelNode tree = select(eq("a", "1"),
                    alias("V", select(eq("b", "2"), rel("Orders"))));

            RelNode result = apply(tree);

            assertThat(result).isNode(SelectionNode.class);
            assertThat(((SelectionNode) result).input()).isNode(SelectionNode.class);
            assertThat(removed()).isTrue();
        }

        @Test
        @DisplayName("a referenced alias is kept — dropping it would break V.col resolution")
        void referencedAliasKept() {
            RelNode tree = select(eq("V.a", "1"), alias("V", rel("Orders")));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("the alias reference is found in a projection, not just a predicate")
        void referenceInProjectionKeepsIt() {
            RelNode tree = project(
                    List.of(projected(attr("V.a"))),
                    alias("V", rel("Orders")));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("the alias reference is found in a join condition")
        void referenceInJoinConditionKeepsIt() {
            RelNode tree = join(
                    alias("V", rel("Orders")), rel("Customers"),
                    cmp(attr("V.cust"),
                            ComparisonOperator.EQUAL, attr("Customers.id")));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("the alias reference is found in a sort key")
        void referenceInSortKeyKeepsIt() {
            RelNode tree = sort(
                    List.of(sortKey(attr("V.a"), SortDirection.ASC)),
                    alias("V", rel("Orders")));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("the alias reference is found inside a function call's arguments")
        void referenceInsideAFunctionCallKeepsIt() {
            // The sweep walks into a call's arguments and ignores the call's own name.
            // Missing the arguments would drop an alias that Len(V.a) still resolves through.
            RelNode tree = project(
                    List.of(projected(
                            func("Len",attr("V.a")),"n")),
                    alias("V", rel("Orders")));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("the alias reference is found inside a function call in a predicate")
        void referenceInsideAFunctionCallInAPredicateKeepsIt() {
            // The predicate and operand sweeps are separate overloads, so a call in a σ
            // is a different arm from a call in a π.
            RelNode tree = select(
                    cmp(
                            func("Len",attr("V.a")),
                            ComparisonOperator.EQUAL, num("1")),
                    alias("V", rel("Orders")));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("a function's own name is not read as a column qualifier")
        void aFunctionNameIsNotAQualifier() {
            // `V(a)` would be a reference to the alias if the call's name were swept as
            // an attribute — it is not, so the alias is still unreferenced and goes.
            RelNode tree = project(
                    List.of(projected(
                            func("V",attr("a")),"n")),
                    alias("V", rel("Orders")));

            assertThat(apply(tree)).isNotEqualTo(tree);
            assertThat(removed()).isTrue();
        }

        @Test
        @DisplayName("the alias reference is found in an aggregate's YIELD expression")
        void referenceInAggregateYieldKeepsIt() {
            // ARGMAX(rank, yield) carries a second expression the one-expression aggregates
            // do not, and it is the only place a reference can hide in a γ's aggregate list.
            RelNode tree = groupByKeys(
                    List.of(GroupingKey.column("k")),
                    List.of(argAgg(AggregateOperator.ARGMAX,
                            attr("rank"),attr("V.name"),"top")),
                    alias("V", rel("Orders")));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("the alias reference is found in a γ's grouping key")
        void referenceInGroupingKeyKeepsIt() {
            RelNode tree = groupByKeys(
                    List.of(GroupingKey.of(attr("V.k"))),
                    List.of(agg(AggregateOperator.COUNT, "id", "n")),
                    alias("V", rel("Orders")));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("the alias reference is found in an aggregate's own argument")
        void referenceInAggregateArgumentKeepsIt() {
            RelNode tree = groupByKeys(
                    List.of(GroupingKey.column("k")),
                    List.of(agg(AggregateOperator.SUM, "V.amount", "total")),
                    alias("V", rel("Orders")));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("the alias reference is found in a μ's column")
        void referenceInUnnestColumnKeepsIt() {
            // μ names one column and no expression, so it is the only operator whose
            // reference is a bare string rather than an operand tree — and the only arm
            // of the sweep no test reached. Dropping the ρ would leave μ naming a
            // qualifier the tree no longer exposes.
            RelNode tree = unnest("V.items", alias("V", rel("Orders")));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("a ρ deeper in the subtree re-exposes its own alias, not just base names")
        void aNestedAliasIsAlsoAReExposedName() {
            // ρ Outer (σ p (ρ Inner (Orders))) — the σ keeps the two ρ non-adjacent, so
            // RENAME-001 cannot collapse them and the removal check has to walk the whole
            // subtree. Dropping Outer would put Inner back on the columns' provenance.
            RelNode inner = alias("Inner", rel("Orders"));
            RelNode tree = select(eq("Inner.a", "1"),
                    alias("Outer", select(eq("a", "2"), inner)));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("…and goes when that nested alias is referenced by nobody")
        void anUnreferencedNestedAliasDoesNotBlockTheRemoval() {
            RelNode inner = alias("Inner", rel("Orders"));
            RelNode tree = select(eq("a", "1"),
                    alias("Outer", select(eq("a", "2"), inner)));

            assertThat(apply(tree)).isNotEqualTo(tree);
            assertThat(removed()).isTrue();
        }

        @Test
        @DisplayName("the alias is kept when the name it would re-expose is itself referenced")
        void reExposedNameReferencedKeepsIt() {
            // (ρ V (Orders)) ⨝ Orders, with a reference to Orders.id: today that names
            // exactly one column, because the left side is stamped V. Dropping the ρ
            // would make it ambiguous and fall back to a bare-name first match.
            RelNode tree = join(
                    alias("V", rel("Orders")), rel("Orders"),
                    cmp(attr("Orders.id"),
                            ComparisonOperator.EQUAL, num("1")));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("an unrelated qualified reference does not block the removal")
        void unrelatedQualifierDoesNotBlock() {
            RelNode tree = join(
                    alias("V", rel("Orders")), rel("Customers"),
                    cmp(attr("Customers.id"),
                            ComparisonOperator.EQUAL, num("1")));

            RelNode result = apply(tree);

            assertThat(((ThetaJoinNode) result).left()).isEqualTo(rel("Orders"));
            assertThat(removed()).isTrue();
        }

        @Test
        @DisplayName("a ρ that renames columns is never removed — it changes the schema")
        void columnRenamingRhoKept() {
            RelNode tree = rename("V", List.of("x", "y"), rel("Orders"));

            assertThat(apply(tree)).isEqualTo(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("a tree with no rename at all is returned unchanged, by identity")
        void noRenameIsIdentity() {
            RelNode tree = select(eq("a", "1"), rel("Orders"));

            assertThat(apply(tree)).isSameAs(tree);
        }
    }

    // =========================================================================
    // RENAME-001 — collapsing a chain
    // =========================================================================

    @Nested
    @DisplayName("RENAME-001 — a relation-only ρ over another ρ")
    class Rename001 {

        @Test
        @DisplayName("a three-deep chain of unreferenced aliases disappears in one application")
        void chainOfThreeDisappearsInOnePass() {
            // The walk is bottom-up, so each ρ is already gone by RENAME-002 before the
            // one above it is examined — RENAME-001 never gets the chance to fire, and
            // does not need to. Convergence in one application either way.
            RelNode result = apply(alias("V", alias("W", alias("X", rel("Orders")))));

            assertThat(result).isEqualTo(rel("Orders"));
            assertThat(ctx.recordsFor(OptimizationCode.RENAME_002)).hasSize(3);
        }

        @Test
        @DisplayName("collapsing is what removes an alias RENAME-002 had to keep")
        void collapseRemovesAnAliasRename002CannotDrop() {
            // ρ V (ρ Orders? no — ρ W (Orders)) with Orders referenced: RENAME-002 must
            // keep the inner ρ, because dropping it would re-expose `Orders` as a
            // qualifier on the left of the join and make Orders.id ambiguous. RENAME-001
            // still collapses the pair, because ρ V stamps every column regardless.
            RelNode tree = join(
                    alias("V", alias("W", rel("Orders"))), rel("Orders"),
                    cmp(attr("Orders.id"),
                            ComparisonOperator.EQUAL, num("1")));

            RelNode result = apply(tree);

            assertThat(collapsed()).isTrue();
            RelNode left = ((ThetaJoinNode) result).left();
            assertThat(left).isNode(RenameNode.class);
            assertThat(((RenameNode) left).relationName()).contains("V");
            assertThat(((RenameNode) left).input()).isEqualTo(rel("Orders"));
        }

        @Test
        @DisplayName("collapsing is unconditional — it fires even when the inner alias is referenced")
        void collapsesEvenWhenInnerAliasReferenced() {
            // W is not a resolvable qualifier above the outer ρ either way: ρ V
            // re-anchors every column's provenance to V. So the reference to W.a is
            // already resolving by bare-name fallback, with or without the collapse.
            RelNode tree = select(eq("W.a", "1"),
                    alias("V", alias("W", rel("Orders"))));

            RelNode result = apply(tree);

            assertThat(collapsed()).isTrue();
            // The collapse leaves ρ V (Orders); nothing names V and nothing names
            // Orders, so RENAME-002 then takes that too.
            assertThat(((SelectionNode) result).input()).isEqualTo(rel("Orders"));
        }

        @Test
        @DisplayName("the outer alias absorbs the inner ρ's column renames")
        void absorbsInnerColumnRenames() {
            RelNode result = apply(alias("V", rename("W", List.of("x", "y"), rel("Orders"))));

            RenameNode r = assertThat(result).asNode(RenameNode.class);
            assertThat(r.relationName()).contains("V");
            assertThat(r.attributes()).containsExactly("x", "y");
            assertThat(r.input()).isEqualTo(rel("Orders"));
            assertThat(collapsed()).isTrue();
        }

        @Test
        @DisplayName("a column-renaming outer ρ does not absorb the ρ beneath it")
        void columnRenamingOuterDoesNotCollapse() {
            RelNode tree = rename("V", List.of("x"), alias("W", rel("Orders")));

            RelNode result = apply(tree);

            assertThat(collapsed()).isFalse();
            // The inner alias is still eligible for removal on its own.
            assertThat(((RenameNode) result).input()).isEqualTo(rel("Orders"));
        }
    }

    // =========================================================================
    // The conservative bail
    // =========================================================================

    @Nested
    @DisplayName("a node the sweep cannot enumerate abandons the whole pass")
    class ConservativeBail {

        @Test
        @DisplayName("a WINDOW anywhere in the tree stops every removal")
        void windowNodeBails() {
            // WindowNode's partition keys are column references this pass does not
            // enumerate, so "unreferenced" cannot be derived — and a rename removed on
            // an incomplete sweep is a wrong answer, not a slow one.
            RelNode tree = window(
                    new WindowFunction.RankingWindow(RankingFunction.ROW_NUMBER, Optional.empty()),
                    List.of("region"),
                    List.of(desc("amount")),
                    new WindowFrame.PartitionFrame(), "rk",
                    alias("V", rel("Orders")));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("the core relational operators are all enumerated, so δ does not bail")
        void distinctDoesNotBail() {
            RelNode result = apply(distinct(alias("V", rel("Orders"))));

            assertThat(((DistinctNode) result).input()).isEqualTo(rel("Orders"));
            assertThat(removed()).isTrue();
        }
    }

    // =========================================================================
    // RENAME-003 — identity pairs
    // =========================================================================

    @Nested
    @DisplayName("RENAME-003 — identity column-rename pairs")
    class Rename003 {

        @Test
        @DisplayName("ρ id→id, a→q (R) → ρ a→q (R)")
        void identityPairDropped() {
            RelNode result = apply(pairs(rel("Orders"), "id", "id", "a", "q"));

            RenameNode r = (RenameNode) result;
            assertThat(r.pairs()).containsExactly(new RenameNode.RenamePair("a", "q"));
            assertThat(firings(OptimizationCode.RENAME_003)).isEqualTo(1);
        }

        @Test
        @DisplayName("a ρ whose pairs all cancel and which names no relation goes entirely")
        void wholeNodeDropped() {
            RelNode base = rel("Orders");

            assertThat(apply(pairs(base, "id", "id"))).isSameAs(base);
            assertThat(firings(OptimizationCode.RENAME_003)).isEqualTo(1);
        }

        @Test
        @DisplayName("a cancelled ρ that still names a relation is handed to RENAME-002")
        void cancelledButNamedGoesThroughTheSweep() {
            // Nothing references V here, so the sweep removes it — but it is RENAME-002
            // that decides, not RENAME-003.
            RelNode result = apply(namedPairs("V", rel("Orders"), "id", "id"));

            assertThat(result).isEqualTo(rel("Orders"));
            assertThat(firings(OptimizationCode.RENAME_003)).isEqualTo(1);
            assertThat(removed()).isTrue();
        }

        @Test
        @DisplayName("a cancelled ρ whose alias IS referenced keeps its relation name")
        void cancelledButReferencedSurvives() {
            RelNode tree = select(eq("V.amount", "1"),
                    namedPairs("V", rel("Orders"), "id", "id"));

            RelNode result = apply(tree);

            RenameNode kept = (RenameNode) ((SelectionNode) result).input();
            assertThat(kept.relationName()).contains("V");
            assertThat(kept.pairs()).isEmpty();
            assertThat(firings(OptimizationCode.RENAME_003)).isEqualTo(1);
            assertThat(removed()).isFalse();
        }

        @Test
        @DisplayName("a → A is not an identity — the heading is printed with the new spelling")
        void caseChangeIsARealRename() {
            RelNode node = pairs(rel("Orders"), "a", "A");

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings(OptimizationCode.RENAME_003)).isZero();
        }

        @Test
        @DisplayName("a ρ with no identity pair is left alone")
        void noIdentityPair() {
            RelNode node = pairs(rel("Orders"), "a", "b");

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings(OptimizationCode.RENAME_003)).isZero();
        }
    }

    // =========================================================================
    // RENAME-004 — composing stacked pair-form renames
    // =========================================================================

    @Nested
    @DisplayName("RENAME-004 — stacked column renames composed")
    class Rename004 {

        @Test
        @DisplayName("ρ b→c (ρ a→b (R)) → ρ a→c (R)")
        void chainComposed() {
            RelNode result = apply(pairs(pairs(rel("Orders"), "a", "b"), "b", "c"));

            RenameNode r = (RenameNode) result;
            assertThat(r.pairs()).containsExactly(new RenameNode.RenamePair("a", "c"));
            assertThat(r.input()).isEqualTo(rel("Orders"));
            assertThat(firings(OptimizationCode.RENAME_004)).isEqualTo(1);
        }

        @Test
        @DisplayName("an outer pair that matches no inner target is carried over")
        void unrelatedOuterPairKept() {
            RelNode result = apply(pairs(pairs(rel("Orders"), "a", "b"), "x", "y"));

            RenameNode r = (RenameNode) result;
            assertThat(r.pairs()).containsExactly(
                    new RenameNode.RenamePair("a", "b"),
                    new RenameNode.RenamePair("x", "y"));
            assertThat(firings(OptimizationCode.RENAME_004)).isEqualTo(1);
        }

        @Test
        @DisplayName("a rename cycle composes to an identity pair, which RENAME-003 drops")
        void cycleCancels() {
            RelNode base = rel("Orders");

            assertThat(apply(pairs(pairs(base, "a", "b"), "b", "a"))).isSameAs(base);
            assertThat(firings(OptimizationCode.RENAME_004)).isEqualTo(1);
            assertThat(firings(OptimizationCode.RENAME_003)).isEqualTo(1);
        }

        @Test
        @DisplayName("a swap inside ONE ρ is a real rename and is left alone")
        void swapIsNotACycle() {
            // ρ (a→b, b→a) exchanges two column names. Cancelling it — which is what
            // conflating this with the stacked case would do — deletes the swap.
            RelNode node = pairs(rel("Orders"), "a", "b", "b", "a");

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings(OptimizationCode.RENAME_003)).isZero();
            assertThat(firings(OptimizationCode.RENAME_004)).isZero();
        }

        @Test
        @DisplayName("declines when the outer renames a column the inner has consumed")
        void overlappingSourcesDecline() {
            // After ρ a→b there is no `a` left, so the outer a→c renames nothing today;
            // composing naively would produce a ρ carrying both a→b and a→c.
            RelNode node = pairs(pairs(rel("Orders"), "a", "b"), "a", "c");

            assertThat(apply(node)).isSameAs(node);
            assertThat(firings(OptimizationCode.RENAME_004)).isZero();
        }

        @Test
        @DisplayName("the outer relation name wins, the inner alias goes (RENAME-001's argument)")
        void outerRelationNameWins() {
            RelNode tree = select(eq("V.c", "1"),
                    namedPairs("V", namedPairs("W", rel("Orders"), "a", "b"), "b", "c"));

            RelNode result = apply(tree);

            RenameNode composed = (RenameNode) ((SelectionNode) result).input();
            assertThat(composed.relationName()).contains("V");
            assertThat(composed.pairs()).containsExactly(new RenameNode.RenamePair("a", "c"));
            assertThat(firings(OptimizationCode.RENAME_004)).isEqualTo(1);
        }

        @Test
        @DisplayName("an unnamed outer keeps the inner's relation name, so nothing resolvable is lost")
        void innerRelationNameSurvives() {
            RelNode tree = select(eq("W.c", "1"),
                    pairs(namedPairs("W", rel("Orders"), "a", "b"), "b", "c"));

            RelNode result = apply(tree);

            RenameNode composed = (RenameNode) ((SelectionNode) result).input();
            assertThat(composed.relationName()).contains("W");
            assertThat(composed.pairs()).containsExactly(new RenameNode.RenamePair("a", "c"));
            assertThat(firings(OptimizationCode.RENAME_004)).isEqualTo(1);
        }

        @Test
        @DisplayName("the positional form is out of scope — it renames by position")
        void positionalFormDeclines() {
            RelNode node = pairs(rename("E", List.of("x", "y"), rel("Orders")), "x", "z");

            RelNode result = apply(node);

            assertThat(firings(OptimizationCode.RENAME_004)).isZero();
            assertThat(result).isNode(RenameNode.class);
        }
    }
}

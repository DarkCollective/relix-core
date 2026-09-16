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

import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Equi-key extraction and side resolution, at their own seam.
 *
 * <p>These decide <em>which column of which input</em> a join probes on, so a wrong
 * answer is a join on the wrong pair — wrong rows, not a slower plan. The interesting
 * cases are the ambiguous ones: an equi-join's two sides usually share the joined
 * column's <em>name</em>, which is precisely when the bare-name lookup cannot decide and
 * the qualifier has to. Driving this through {@link Planner} rarely arranges that
 * collision, and never arranges the shapes that must resolve to <em>no</em> key.
 */
@DisplayName("JoinPlanning — equi-keys and side resolution")
final class JoinPlanningTest {

    /** {@code Orders(id, amount)} and {@code Customers(id, name)} — note the shared `id`. */
    private static final Schema LEFT = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("amount", ScalarType.NUMBER)));
    private static final Schema RIGHT = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("name", ScalarType.STRING)));

    /** A pair of schemas sharing no column name at all. */
    private static final Schema DISJOINT_RIGHT = new Schema(List.of(
            new ColumnDefinition("cust", ScalarType.NUMBER),
            new ColumnDefinition("name", ScalarType.STRING)));

    private static final Set<String> LEFT_RELS = Set.of("orders");
    private static final Set<String> RIGHT_RELS = Set.of("customers");

    private static Predicate eq(String left, String right) {
        return cmp(attr(left), ComparisonOperator.EQUAL,
                attr(right));
    }

    private static PhysicalNode.JoinKeys keys(Predicate condition) {
        return JoinPlanning.extractKeys(condition, LEFT, RIGHT, LEFT_RELS, RIGHT_RELS);
    }

    private static PhysicalNode.JoinKeys keysDisjoint(Predicate condition) {
        return JoinPlanning.extractKeys(condition, LEFT, DISJOINT_RIGHT, LEFT_RELS, RIGHT_RELS);
    }

    @Nested
    @DisplayName("side resolution")
    class Sides {

        @Test
        @DisplayName("a name only one schema has needs no qualifier")
        void exclusiveNames() {
            var k = keysDisjoint(eq("amount", "cust"));
            assertThat(k.left()).containsExactly(1);
            assertThat(k.right()).containsExactly(0);
        }

        @Test
        @DisplayName("…and resolves with the operands written the other way round")
        void exclusiveNamesReversed() {
            var k = keysDisjoint(eq("cust", "amount"));
            assertThat(k.left()).as("the left index is still the left schema's")
                    .containsExactly(1);
            assertThat(k.right()).containsExactly(0);
        }

        @Test
        @DisplayName("a shared name is resolved by its qualifier, in both orientations")
        void sharedNameNeedsAQualifier() {
            var forward = keys(eq("Orders.id", "Customers.id"));
            assertThat(forward.left()).containsExactly(0);
            assertThat(forward.right()).containsExactly(0);

            var reversed = keys(eq("Customers.id", "Orders.id"));
            assertThat(reversed.left()).as("orientation does not swap the sides")
                    .containsExactly(0);
            assertThat(reversed.right()).containsExactly(0);
        }

        @Test
        @DisplayName("a shared name with no qualifier resolves to no key at all")
        void sharedNameWithoutAQualifier() {
            assertThat(keys(eq("id", "id")).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a qualifier neither side owns resolves to no key")
        void unknownQualifier() {
            assertThat(keys(eq("Shipments.id", "Customers.id")).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("an unknown column resolves to no key")
        void unknownColumn() {
            assertThat(keys(eq("Orders.missing", "Customers.id")).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("two columns of the SAME side are not a join key")
        void bothOperandsOnOneSide() {
            // a.left == b.left — an intra-side equality is a filter, not a join key, and
            // reading it as one would probe a side against itself.
            assertThat(keys(eq("Orders.id", "amount")).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("AS-OF match extraction")
    class AsOfMatch {

        private static JoinPlanning.AsOfMatch match(Predicate condition) {
            return JoinPlanning.extractAsOfMatch(condition, LEFT, RIGHT, LEFT_RELS, RIGHT_RELS);
        }

        private static Predicate lt(String left, String right) {
            return cmp(attr(left),
                    ComparisonOperator.LESS_EQUAL, attr(right));
        }

        @Test
        @DisplayName("the ordering inequality is normalised so the probe column is on the left")
        void orientsTheProbeToTheLeft() {
            // Written either way round, the match must name the same pair with the same
            // roles — the probe side drives the scan, so swapping them reverses which
            // relation is searched and silently answers a different question.
            var forward = match(lt("amount", "name"));
            var reversed = match(lt("name", "amount"));
            assertThat(forward).isNotNull();
            assertThat(reversed).isNotNull();
            assertThat(reversed.leftMatchIndex()).isEqualTo(forward.leftMatchIndex());
            assertThat(reversed.rightMatchIndex()).isEqualTo(forward.rightMatchIndex());
            assertThat(reversed.backward()).as("the direction flips with the operands")
                    .isNotEqualTo(forward.backward());
        }

        @Test
        @DisplayName("an inequality against a literal is not an ordering key")
        void aLiteralIsNotAMatchColumn() {
            assertThat(match(cmp(attr("amount"),
                    ComparisonOperator.LESS_EQUAL, num("5")))).isNull();
            assertThat(match(cmp(num("5"),
                    ComparisonOperator.LESS_EQUAL, attr("amount")))).isNull();
        }

        @Test
        @DisplayName("two columns of the same side are not an ordering key either")
        void sameSideIsNotAMatch() {
            assertThat(match(lt("id", "amount")))
                    .as("both resolve to the left input")
                    .isNull();
        }

        @Test
        @DisplayName("a column neither side owns yields no match")
        void unresolvableColumnIsNotAMatch() {
            assertThat(match(lt("amount", "missing"))).isNull();
        }
    }

    @Nested
    @DisplayName("what counts as a key")
    class KeyShapes {

        @Test
        @DisplayName("a conjunction contributes every equality it holds, in order")
        void conjunctionOfEqualities() {
            var k = JoinPlanning.extractKeys(
                    and(eq("Orders.id", "Customers.id"),
                            eq("amount", "name")),
                    LEFT, RIGHT, LEFT_RELS, RIGHT_RELS);
            assertThat(k.left()).containsExactly(0, 1);
            assertThat(k.right()).containsExactly(0, 1);
        }

        @Test
        @DisplayName("an inequality is not an equi-key")
        void inequalityIsNotAKey() {
            assertThat(JoinPlanning.extractKeys(
                    cmp(attr("amount"),
                            ComparisonOperator.LESS, attr("cust")),
                    LEFT, DISJOINT_RIGHT, LEFT_RELS, RIGHT_RELS).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("an equality against a literal is not an equi-key")
        void literalIsNotAKey() {
            assertThat(JoinPlanning.extractKeys(
                    cmp(attr("amount"),
                            ComparisonOperator.EQUAL, num("5")),
                    LEFT, DISJOINT_RIGHT, LEFT_RELS, RIGHT_RELS).isEmpty()).isTrue();
            assertThat(JoinPlanning.extractKeys(
                    cmp(num("5"),
                            ComparisonOperator.EQUAL, attr("amount")),
                    LEFT, DISJOINT_RIGHT, LEFT_RELS, RIGHT_RELS).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("OR and NOT are not guaranteed-true conjuncts, so they yield nothing")
        void disjunctionAndNegationYieldNothing() {
            Predicate key = eq("amount", "cust");
            assertThat(keysDisjoint(or(key, key)).isEmpty()).isTrue();
            assertThat(keysDisjoint(not(key)).isEmpty()).isTrue();
        }
    }
}

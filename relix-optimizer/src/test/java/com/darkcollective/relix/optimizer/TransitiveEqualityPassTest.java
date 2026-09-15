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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnProvenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Transitive equality propagation — EQ-001 (#531)")
final class TransitiveEqualityPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static final RelationNode CUSTOMERS = rel("Customers");
    private static final RelationNode SALES     = rel("Sales");

    /** {@code Customers(cid, name)} — both columns stamped with their source relation. */
    private static Schema customersSchema() {
        return new Schema(List.of(
                new ColumnDefinition("cid", ScalarType.NUMBER, new ColumnProvenance("Customers", "cid")),
                new ColumnDefinition("name", ScalarType.STRING, new ColumnProvenance("Customers", "name"))));
    }

    /** {@code Sales(cid, amount)} — note the shared bare column name {@code cid}. */
    private static Schema salesSchema() {
        return new Schema(List.of(
                new ColumnDefinition("cid", ScalarType.NUMBER, new ColumnProvenance("Sales", "cid")),
                new ColumnDefinition("amount", ScalarType.NUMBER, new ColumnProvenance("Sales", "amount"))));
    }

    private RelNode apply(RelNode node) {
        return apply(node, Map.of());
    }

    /**
     * Applies the pass with the two leaves annotated, plus whatever else the caller
     * needs.  In production every node carries an annotation; a unit fixture only has
     * to annotate the nodes the pass actually looks up — a join's two inputs.
     */
    private RelNode apply(RelNode node, Map<RelNode, Schema> extra) {
        var all = new HashMap<RelNode, Schema>();
        all.put(CUSTOMERS, customersSchema());
        all.put(SALES, salesSchema());
        all.putAll(extra);
        return TransitiveEqualityPass.apply(node, "Q", new SchemaAnnotations(all), ctx);
    }

    private boolean fired() {
        return !ctx.recordsFor(OptimizationCode.EQ_001).isEmpty();
    }

    private static Predicate eqCols(String left, String right) {
        return cmp(attr(left), ComparisonOperator.EQUAL,
                attr(right));
    }

    private static Predicate eqLit(String column, String value) {
        return cmp(attr(column), ComparisonOperator.EQUAL,
                num(value));
    }

    /** The join key equality shared by every fixture. */
    private static Predicate joinKeys() {
        return eqCols("Customers.cid", "Sales.cid");
    }

    /** The predicate of a σ sitting directly on {@code side}, or null when there is none. */
    private static Predicate filterOn(RelNode side) {
        return (side instanceof SelectionNode s) ? s.predicate() : null;
    }

    // =========================================================================
    // The law that pays
    // =========================================================================

    @Nested
    @DisplayName("a literal binding reaches the other side of the join")
    class Propagation {

        @Test
        @DisplayName("σ above the join binds the left column; the right input gets the derived σ")
        void bindingAboveReachesTheRightInput() {
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, SALES, joinKeys()));

            RelNode result = apply(tree);

            ThetaJoinNode join = (ThetaJoinNode) ((SelectionNode) result).input();
            assertThat(join.left()).isSameAs(CUSTOMERS);
            assertThat(filterOn(join.right())).isEqualTo(eqLit("Sales.cid", "1"));
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("the same binding written against the right column reaches the left input")
        void bindingReachesTheLeftInput() {
            RelNode tree = select(eqLit("Sales.cid", "2"),
                    join(CUSTOMERS, SALES, joinKeys()));

            ThetaJoinNode join = (ThetaJoinNode) ((SelectionNode) apply(tree)).input();

            assertThat(filterOn(join.left())).isEqualTo(eqLit("Customers.cid", "2"));
            assertThat(join.right()).isSameAs(SALES);
        }

        @Test
        @DisplayName("a binding conjoined into the join condition itself propagates")
        void bindingInsideTheConditionPropagates() {
            RelNode tree = join(CUSTOMERS, SALES,
                    and(joinKeys(), eqLit("Customers.cid", "1")));

            ThetaJoinNode join = (ThetaJoinNode) apply(tree);

            assertThat(filterOn(join.right())).isEqualTo(eqLit("Sales.cid", "1"));
        }

        @Test
        @DisplayName("a binding already pushed onto one input still reaches the other")
        void bindingOnAnInputPropagates() {
            RelNode filtered = select(eqLit("Customers.cid", "1"), CUSTOMERS);
            RelNode tree = join(filtered, SALES, joinKeys());

            ThetaJoinNode join = (ThetaJoinNode) apply(tree, Map.of(filtered, customersSchema()));

            assertThat(filterOn(join.right())).isEqualTo(eqLit("Sales.cid", "1"));
        }

        @Test
        @DisplayName("the derived predicate keeps the qualifier's original spelling")
        void keepsOriginalSpelling() {
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, SALES, eqCols("Customers.cid", "Sales.CID")));

            ThetaJoinNode join = (ThetaJoinNode) ((SelectionNode) apply(tree)).input();
            Operand derived = ((ComparisonPredicate) filterOn(join.right())).left();

            assertThat(((AttributeOperand) derived).name()).isEqualTo("Sales.CID");
        }
    }

    // =========================================================================
    // Idempotence
    // =========================================================================

    @Nested
    @DisplayName("a derived predicate is not re-derived")
    class Idempotence {

        @Test
        @DisplayName("a second application adds nothing and returns the same tree")
        void secondApplicationIsANoOp() {
            RelNode once = apply(select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, SALES, joinKeys())));
            int recordsAfterFirst = ctx.size();

            RelNode twice = apply(once);

            assertThat(twice).isSameAs(once);
            assertThat(ctx.size()).isEqualTo(recordsAfterFirst);
        }

        /** {@code π cid, amount (input)} — an opaque step the σ-chain walk stops at. */
        private static RelNode project(RelNode input) {
            return AstBuilders.project(List.of(
                    ProjectedAttribute.simple(attr("cid")),
                    ProjectedAttribute.simple(attr("amount"))), input);
        }

        @Test
        @DisplayName("a binding below a π is found by the subtree scan, which the σ-chain misses")
        void bindingBelowAProjectionIsRecognised() {
            // The σ-chain walk stops at the first non-σ, so a π between the join and the
            // derived filter hides it from the chain entirely. This is the case the
            // whole-subtree check exists for, and the only way to reach it — a binding
            // still in the chain is picked up as a *fact* and no derivation is attempted.
            RelNode right = project(select(eqLit("Sales.cid", "1"), SALES));
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, right, joinKeys()));

            assertThat(apply(tree, Map.of(right, salesSchema()))).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("the same check reads a binding written literal-first")
        void bindingBelowAProjectionWrittenLiteralFirst() {
            // `1 = Sales.cid` pins the column just as `Sales.cid = 1` does; the two are
            // separate arms, and a rule seeing only one would re-derive on every sweep.
            Predicate reversed = cmp(num("1"),
                    ComparisonOperator.EQUAL, attr("Sales.cid"));
            RelNode right = project(select(reversed, SALES));
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, right, joinKeys()));

            assertThat(apply(tree, Map.of(right, salesSchema()))).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a binding to a DIFFERENT literal does not suppress the derivation")
        void aDifferentLiteralIsNotTheSameBinding() {
            // The other half of the claim: the check must match the literal too, or it
            // would silence the rule wherever the column is constrained at all.
            RelNode right = project(select(eqLit("Sales.cid", "2"), SALES));
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, right, joinKeys()));

            assertThat(apply(tree, Map.of(right, salesSchema()))).isNotSameAs(tree);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a non-equality on the same column does not count as a binding")
        void anInequalityIsNotABinding() {
            RelNode right = project(select(
                    cmp(attr("Sales.cid"),
                            ComparisonOperator.GREATER, num("1")), SALES));
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, right, joinKeys()));

            assertThat(apply(tree, Map.of(right, salesSchema()))).isNotSameAs(tree);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("an equality against a computed value is neither a union nor a binding")
        void aComputedSideIsNotABinding() {
            // The collector reads three shapes: column = column, column = literal, and
            // its mirror. `cid = Abs(x)` is none of them — the value is not known until
            // the row is read, so there is nothing to substitute into the other side —
            // and both operand orders reach the same conclusion by different arms.
            RelNode computedRight = select(
                    cmp(attr("Sales.cid"), ComparisonOperator.EQUAL,
                            func("Abs", attr("Sales.delta"))), SALES);
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, computedRight, joinKeys()));
            assertThat(apply(tree, Map.of(computedRight, salesSchema()))).isNotSameAs(tree);
            assertThat(fired())
                    .as("the literal binding above the join still propagates; the computed "
                            + "equality simply contributes nothing")
                    .isTrue();

            RelNode computedLeftFirst = select(
                    cmp(func("Abs", attr("Sales.delta")), ComparisonOperator.EQUAL,
                            attr("Sales.cid")), SALES);
            RelNode mirrored = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, computedLeftFirst, joinKeys()));
            assertThat(apply(mirrored, Map.of(computedLeftFirst, salesSchema())))
                    .isNotSameAs(mirrored);
            assertThat(fired()).isTrue();

            // And with no column on either side, which is the arm neither order reaches.
            RelNode noColumn = select(
                    cmp(func("Abs", attr("Sales.delta")), ComparisonOperator.EQUAL, num("5")),
                    SALES);
            RelNode neither = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, noColumn, joinKeys()));
            assertThat(apply(neither, Map.of(noColumn, salesSchema()))).isNotSameAs(neither);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a non-comparison predicate in the subtree is not read as a binding")
        void aNonComparisonIsNotABinding() {
            RelNode right = project(select(
                    new com.darkcollective.relix.ast.NullPredicate(
                            attr("Sales.cid"), false), SALES));
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, right, joinKeys()));

            assertThat(apply(tree, Map.of(right, salesSchema()))).isNotSameAs(tree);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a binding on a different column does not count either")
        void aDifferentColumnIsNotABinding() {
            RelNode right = project(select(eqLit("Sales.amount", "1"), SALES));
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, right, joinKeys()));

            assertThat(apply(tree, Map.of(right, salesSchema()))).isNotSameAs(tree);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a binding carried further down (below another σ) is still seen as present")
        void deeperBindingIsRecognised() {
            // What SEL-004 leaves behind once it has carried the derived σ past another
            // filter: the binding is no longer directly above the input, so recognising
            // it needs the whole-subtree scan rather than the σ-chain alone.
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS,
                            select(eqLit("amount", "9"),
                                    select(eqLit("Sales.cid", "1"), SALES)),
                            joinKeys()));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }
    }

    // =========================================================================
    // Soundness limits
    // =========================================================================

    @Nested
    @DisplayName("what must not fire")
    class SoundnessLimits {

        @Test
        @DisplayName("an outer join's condition is never read as a fact")
        void outerJoinIsNeverUsed() {
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    leftJoin(CUSTOMERS, SALES, joinKeys()));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an inequality binds no class")
        void inequalityBindsNothing() {
            RelNode tree = select(
                    cmp(attr("Customers.cid"),
                            ComparisonOperator.GREATER, num("1")),
                    join(CUSTOMERS, SALES, joinKeys()));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an equality inside a disjunction is not a fact")
        void disjunctionIsOpaque() {
            Predicate either = new com.darkcollective.relix.ast.OrPredicate(
                    eqLit("Customers.cid", "1"), eqLit("Customers.cid", "2"));
            RelNode tree = select(either,
                    join(CUSTOMERS, SALES, joinKeys()));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a bare column name both sides own is ambiguous, so nothing is derived")
        void ambiguousBareColumnIsSkipped() {
            // The class is {Customers.cid, cid} with the binding on the qualified
            // member, so the one that would receive a derived σ is the bare `cid` —
            // which names a column of both inputs. Guessing a side would filter the
            // wrong relation.
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, SALES, eqCols("Customers.cid", "cid")));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an unannotated input side stops the derivation")
        void missingSchemaStopsIt() {
            RelNode unannotated = rel("Unknown");
            RelNode tree = select(eqLit("Customers.cid", "1"),
                    join(CUSTOMERS, unannotated, joinKeys()));

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a σ below the join is not a fact for the join's other side")
        void filterBelowOneInputDoesNotLeak() {
            // Customers ⨝ (σ amount = 9 (Sales)) — `amount` is in no equality class
            // with a join key, so there is nothing to carry across.
            RelNode tree = join(CUSTOMERS,
                    select(eqLit("amount", "9"), SALES), joinKeys());

            assertThat(apply(tree)).isSameAs(tree);
            assertThat(fired()).isFalse();
        }
    }
}

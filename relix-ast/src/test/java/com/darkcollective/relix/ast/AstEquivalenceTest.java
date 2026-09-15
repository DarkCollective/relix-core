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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import com.darkcollective.relix.ast.AstBuilders;

import static com.darkcollective.relix.ast.Expr.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AstEquivalence}.
 *
 * <p>These cover the <em>normalisation rules</em> — what is folded together and what
 * deliberately is not — using hand-built fragments, which is legitimate here because
 * every case turns on something other than the source location. The evidence that
 * locations are actually ignored on <em>real</em> input cannot come from this module
 * (nothing here parses), and lives in {@code AstEquivalenceParsedTest} in
 * {@code relix-parser}, per the trap {@link AstEquivalence}'s Javadoc records.
 */
@DisplayName("AstEquivalence")
final class AstEquivalenceTest {

    private static final SourceLocation LINE_3 = new SourceLocation("a.relix", 3, 1);
    private static final SourceLocation LINE_7 = new SourceLocation("a.relix", 7, 12);

    // =========================================================================
    // Null guards
    // =========================================================================

    @Nested
    @DisplayName("Null guards")
    class NullGuards {

        @Test
        @DisplayName("equivalent(Operand, null) throws")
        void operandNull() {
            assertThatThrownBy(() -> AstEquivalence.equivalent(num("1"), (Operand) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("equivalent(Predicate, null) throws")
        void predicateNull() {
            Predicate p = nullPred(attr("a"), true);
            assertThatThrownBy(() -> AstEquivalence.equivalent(p, (Predicate) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("equivalent(RelNode, null) throws")
        void relNodeNull() {
            assertThatThrownBy(() -> AstEquivalence.equivalent(rel("R"), (RelNode) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("digest(null) throws")
        void digestNull() {
            assertThatThrownBy(() -> AstEquivalence.digest(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // Source location
    // =========================================================================

    @Nested
    @DisplayName("Source location is ignored")
    class Locations {

        @Test
        @DisplayName("the same comparison written at two positions is equivalent, but not equal")
        void sameComparisonDifferentLines() {
            Predicate a = new ComparisonPredicate(new AttributeOperand("x", LINE_3),
                    ComparisonOperator.GREATER, new NumberOperand("5", LINE_3), LINE_3);
            Predicate b = new ComparisonPredicate(new AttributeOperand("x", LINE_7),
                    ComparisonOperator.GREATER, new NumberOperand("5", LINE_7), LINE_7);
            assertThat(a).isNotEqualTo(b);          // record equality includes the location
            assertThat(AstEquivalence.equivalent(a, b)).isTrue();
        }

        @Test
        @DisplayName("locations nested inside a compound operand are ignored too")
        void nestedLocations() {
            Operand a = new BinaryArithmeticExpression(new AttributeOperand("x", LINE_3),
                    ArithmeticOperator.PLUS, new NumberOperand("1", LINE_3), LINE_3);
            Operand b = new BinaryArithmeticExpression(new AttributeOperand("x", LINE_7),
                    ArithmeticOperator.PLUS, new NumberOperand("1", LINE_7), LINE_7);
            assertThat(a).isNotEqualTo(b);
            assertThat(AstEquivalence.equivalent(a, b)).isTrue();
        }
    }

    // =========================================================================
    // Numeric normalisation
    // =========================================================================

    @Nested
    @DisplayName("Numeric literals")
    class Numbers {

        @Test
        @DisplayName("5, 5.0 and 05 are the same number")
        void scaleAndLeadingZerosIgnored() {
            assertThat(AstEquivalence.equivalent(num("5"), num("5.0")))
                    .isTrue();
            assertThat(AstEquivalence.equivalent(num("5"), num("05")))
                    .isTrue();
            assertThat(AstEquivalence.equivalent(num("5.00"), num("5.000")))
                    .isTrue();
        }

        @Test
        @DisplayName("different numbers are not equivalent")
        void differentNumbers() {
            assertThat(AstEquivalence.equivalent(num("5"), num("6")))
                    .isFalse();
        }

        @Test
        @DisplayName("comparison is exact, not rounded to DECIMAL64")
        void exactNotRounded() {
            // 20 significant digits differing only in the last: equal under
            // MathContext.DECIMAL64 (16 digits), distinct in fact. Rounding here would
            // be a false positive, which is the one direction a rewrite must not take.
            var a = num("12345678901234567891");
            var b = num("12345678901234567892");
            assertThat(AstEquivalence.equivalent(a, b)).isFalse();
        }

        @Test
        @DisplayName("an unparseable literal falls back to string comparison rather than throwing")
        void unparseableFallsBack() {
            assertThat(AstEquivalence.equivalent(num("0x1f"), num("0x1f")))
                    .isTrue();
            assertThat(AstEquivalence.equivalent(num("0x1f"), num("31")))
                    .isFalse();
        }
    }

    // =========================================================================
    // Names
    // =========================================================================

    @Nested
    @DisplayName("Names")
    class Names {

        @Test
        @DisplayName("attribute names compare case-insensitively")
        void attributeCaseInsensitive() {
            assertThat(AstEquivalence.equivalent(attr("ID"), attr("id")))
                    .isTrue();
        }

        @Test
        @DisplayName("a qualifier is part of the reference — Users.id is not id")
        void qualifierIsSignificant() {
            assertThat(AstEquivalence.equivalent(
                    attr("Users.id"), attr("id"))).isFalse();
            assertThat(AstEquivalence.equivalent(
                    attr("A.x"), attr("B.x"))).isFalse();
        }

        @Test
        @DisplayName("function names compare case-insensitively, arguments structurally")
        void functionNames() {
            Operand a = func("upper",attr("name"));
            Operand b = func("UPPER",attr("NAME"));
            Operand c = func("UPPER",attr("other"));
            assertThat(AstEquivalence.equivalent(a, b)).isTrue();
            assertThat(AstEquivalence.equivalent(a, c)).isFalse();
        }
    }

    // =========================================================================
    // Operand forms
    // =========================================================================

    @Nested
    @DisplayName("Operand forms")
    class OperandForms {

        @Test
        @DisplayName("literals of different kinds are never equivalent")
        void differentKinds() {
            assertThat(AstEquivalence.equivalent(num("1"), str("1")))
                    .isFalse();
            assertThat(AstEquivalence.equivalent(bool(true), num("1")))
                    .isFalse();
        }

        @Test
        @DisplayName("string literals compare case-sensitively — a value is not an identifier")
        void stringsAreCaseSensitive() {
            assertThat(AstEquivalence.equivalent(str("A"), str("a")))
                    .isFalse();
        }

        @Test
        @DisplayName("temporal literals compare by their parsed value")
        void temporalLiterals() {
            assertThat(AstEquivalence.equivalent(
                    date(LocalDate.of(2026, 1, 2)),
                    date(LocalDate.of(2026, 1, 2)))).isTrue();
            assertThat(AstEquivalence.equivalent(
                    time(LocalTime.of(9, 30)),
                    time(LocalTime.of(9, 31)))).isFalse();
            assertThat(AstEquivalence.equivalent(
                    timestamp(Instant.EPOCH),
                    timestamp(Instant.EPOCH))).isTrue();
            assertThat(AstEquivalence.equivalent(
                    duration(Duration.ofMinutes(1)),
                    duration(Duration.ofSeconds(60)))).isTrue();
        }

        @Test
        @DisplayName("booleans compare by value")
        void booleans() {
            assertThat(AstEquivalence.equivalent(bool(true), bool(true)))
                    .isTrue();
            assertThat(AstEquivalence.equivalent(bool(true), bool(false)))
                    .isFalse();
        }

        @Test
        @DisplayName("a unary negation recurses into its operand")
        void unary() {
            assertThat(AstEquivalence.equivalent(
                    AstBuilders.unary(num("5")),
                    AstBuilders.unary(num("5.0")))).isTrue();
            assertThat(AstEquivalence.equivalent(
                    AstBuilders.unary(num("5")), num("5"))).isFalse();
        }

        @Test
        @DisplayName("arithmetic compares operator and both sides, positionally")
        void arithmetic() {
            Operand a = arith(attr("x"),
                    ArithmeticOperator.PLUS, num("1"));
            Operand differentOp = arith(attr("x"),
                    ArithmeticOperator.MINUS, num("1"));
            assertThat(AstEquivalence.equivalent(a, differentOp)).isFalse();
        }

        @Test
        @DisplayName("set, array and struct constructions compare element-wise")
        void constructions() {
            assertThat(AstEquivalence.equivalent(
                    set(num("1"), num("2")),
                    set(num("1.0"), num("2.0"))))
                    .isTrue();
            assertThat(AstEquivalence.equivalent(
                    arrayOf(num("1")),
                    arrayOf(num("1"), num("2"))))
                    .isFalse();
            assertThat(AstEquivalence.equivalent(
                    structOf(
                            new StructConstruction.Field("A", num("1"))),
                    structOf(
                            new StructConstruction.Field("a", num("1.0")))))
                    .isTrue();
        }

        @Test
        @DisplayName("struct fields are positional — reordering them is a different struct")
        void structFieldsArePositional() {
            assertThat(AstEquivalence.equivalent(
                    structOf(
                            new StructConstruction.Field("a", num("1")),
                            new StructConstruction.Field("b", num("2"))),
                    structOf(
                            new StructConstruction.Field("b", num("2")),
                            new StructConstruction.Field("a", num("1")))))
                    .isFalse();
        }

        @Test
        @DisplayName("a condition operand recurses into the predicate it wraps")
        void conditionOperand() {
            Predicate inner = new ComparisonPredicate(attr("x"),
                    ComparisonOperator.GREATER, num("5"), LINE_3);
            Predicate innerElsewhere = new ComparisonPredicate(attr("x"),
                    ComparisonOperator.GREATER, num("5.0"), LINE_7);
            assertThat(AstEquivalence.equivalent(
                    condition(inner), condition(innerElsewhere))).isTrue();
        }
    }

    // =========================================================================
    // Predicate forms
    // =========================================================================

    @Nested
    @DisplayName("Predicate forms")
    class PredicateForms {

        private static Predicate cmp(String column, ComparisonOperator op, String number) {
            return AstBuilders.cmp(attr(column), op, num(number));
        }

        @Test
        @DisplayName("a comparison compares its operator and both operands")
        void comparison() {
            assertThat(AstEquivalence.equivalent(
                    cmp("x", ComparisonOperator.GREATER, "5"),
                    cmp("x", ComparisonOperator.GREATER, "5.0"))).isTrue();
            assertThat(AstEquivalence.equivalent(
                    cmp("x", ComparisonOperator.GREATER, "5"),
                    cmp("x", ComparisonOperator.GREATER_EQUAL, "5"))).isFalse();
        }

        @Test
        @DisplayName("AND, OR and NOT recurse; a different connective is never equivalent")
        void connectives() {
            Predicate p = cmp("x", ComparisonOperator.GREATER, "5");
            Predicate q = cmp("y", ComparisonOperator.LESS, "3");
            assertThat(AstEquivalence.equivalent(and(p, q), and(p, q)))
                    .isTrue();
            assertThat(AstEquivalence.equivalent(and(p, q), or(p, q)))
                    .isFalse();
            assertThat(AstEquivalence.equivalent(not(p), not(p))).isTrue();
            assertThat(AstEquivalence.equivalent(not(p), p)).isFalse();
        }

        @Test
        @DisplayName("AND is not commutative here — this is structural, not semantic, equality")
        void andIsNotCommutative() {
            Predicate p = cmp("x", ComparisonOperator.GREATER, "5");
            Predicate q = cmp("y", ComparisonOperator.LESS, "3");
            assertThat(AstEquivalence.equivalent(and(p, q), and(q, p)))
                    .isFalse();
        }

        @Test
        @DisplayName("IS NULL and IS NOT NULL over the same operand differ")
        void nullPredicate() {
            var operand = attr("a");
            assertThat(AstEquivalence.equivalent(
                    nullPred(operand, true), nullPred(operand, true))).isTrue();
            assertThat(AstEquivalence.equivalent(
                    nullPred(operand, true), nullPred(operand, false))).isFalse();
        }

        @Test
        @DisplayName("∈ compares element, set and negation")
        void elementOf() {
            var set = set(num("1"));
            var scaled = set(num("1.0"));
            assertThat(AstEquivalence.equivalent(
                    AstBuilders.elementOf(attr("a"), set),
                    AstBuilders.elementOf(attr("a"), scaled))).isTrue();
            assertThat(AstEquivalence.equivalent(
                    AstBuilders.elementOf(attr("a"), set),
                    notElementOf(attr("a"), set))).isFalse();
        }

        @Test
        @DisplayName("LIKE compares operand, pattern and negation")
        void pattern() {
            var operand = attr("name");
            assertThat(AstEquivalence.equivalent(
                    like(operand, str("a%")),
                    like(operand, str("a%")))).isTrue();
            assertThat(AstEquivalence.equivalent(
                    like(operand, str("a%")),
                    like(operand, str("b%")))).isFalse();
        }
    }

    // =========================================================================
    // RelNode
    // =========================================================================

    @Nested
    @DisplayName("RelNode")
    class RelNodes {

        @Test
        @DisplayName("identical trees written at different positions are equivalent")
        void sameTreeDifferentPositions() {
            RelNode a = new SelectionNode(
                    new ComparisonPredicate(new AttributeOperand("x", LINE_3),
                            ComparisonOperator.GREATER, new NumberOperand("5", LINE_3), LINE_3),
                    new RelationNode("R", LINE_3), LINE_3);
            RelNode b = new SelectionNode(
                    new ComparisonPredicate(new AttributeOperand("x", LINE_7),
                            ComparisonOperator.GREATER, new NumberOperand("5", LINE_7), LINE_7),
                    new RelationNode("R", LINE_7), LINE_7);
            assertThat(a).isNotEqualTo(b);
            assertThat(AstEquivalence.equivalent(a, b)).isTrue();
            assertThat(AstEquivalence.digest(a)).isEqualTo(AstEquivalence.digest(b));
        }

        @Test
        @DisplayName("structurally different trees are not equivalent")
        void differentTrees() {
            RelNode a = select(
                    cmp(attr("x"),
                            ComparisonOperator.GREATER, num("5")),
                    rel("R"));
            RelNode b = select(
                    cmp(attr("x"),
                            ComparisonOperator.GREATER, num("6")),
                    rel("R"));
            assertThat(AstEquivalence.equivalent(a, b)).isFalse();
        }

        @Test
        @DisplayName("the same instance is equivalent to itself without printing it")
        void identity() {
            RelNode r = rel("R");
            assertThat(AstEquivalence.equivalent(r, r)).isTrue();
        }

        @Test
        @DisplayName("the documented limitation: literal spelling still separates two trees")
        void literalSpellingIsNotNormalisedAtNodeLevel() {
            // A false negative, not a false positive — the rule declines to fire. Pinned
            // so that a future arm-by-arm normalisation has to update this deliberately.
            RelNode a = select(
                    cmp(attr("x"),
                            ComparisonOperator.GREATER, num("5")),
                    rel("R"));
            RelNode b = select(
                    cmp(attr("x"),
                            ComparisonOperator.GREATER, num("5.0")),
                    rel("R"));
            assertThat(AstEquivalence.equivalent(a, b)).isFalse();
            // …while the bounds themselves compare equal.
            assertThat(AstEquivalence.equivalent(num("5"), num("5.0")))
                    .isTrue();
        }
    }

    // =========================================================================
    // Every arm, from both sides
    // =========================================================================

    /**
     * Each form's {@code b instanceof X} guard, exercised for every other form.
     *
     * <p>Written as a cross-product rather than as a handful of hand-picked pairs
     * because the guard is per-arm: testing that a number is not a string says nothing
     * about whether a duration is correctly distinguished from a timestamp. A false
     * positive here is the expensive direction — {@code digest} keys shared-subplan
     * detection, so two fragments wrongly called the same are one spool feeding a
     * consumer that wanted the other.
     */
    @Nested
    @DisplayName("Different forms are never equivalent")
    class DifferentForms {

        private final List<Operand> operands = operands();

        private final List<Predicate> predicates = predicates();

        private static List<Operand> operands() {
            return List.of(
                attr("x"),
                str("s"),
                num("1"),
                bool(true),
                date(LocalDate.of(2026, 1, 2)),
                time(LocalTime.of(9, 30)),
                timestamp(Instant.EPOCH),
                duration(Duration.ofMinutes(1)),
                arith(attr("x"),
                        ArithmeticOperator.PLUS, num("1")),
                func("Len",attr("x")),
                set(num("1")),
                unary(num("1")),
                structOf(
                        new StructConstruction.Field("a", num("1"))),
                arrayOf(num("1")),
                condition(nullPred(attr("x"), true)));
        }

        private static List<Predicate> predicates() {
            return List.of(
                cmp(attr("x"),
                        ComparisonOperator.GREATER, num("5")),
                and(nullPred(attr("x"), true),
                        nullPred(attr("y"), true)),
                or(nullPred(attr("x"), true),
                        nullPred(attr("y"), true)),
                not(nullPred(attr("x"), true)),
                nullPred(attr("x"), true),
                AstBuilders.elementOf(attr("x"),
                        set(num("1"))),
                like(attr("x"), str("a%")));
        }

        @Test
        @DisplayName("every operand form is distinguished from every other")
        void everyOperandPair() {
            for (Operand a : operands) {
                for (Operand b : operands) {
                    if (a == b) {
                        continue;
                    }
                    assertThat(AstEquivalence.equivalent(a, b))
                            .as("%s vs %s", a.getClass().getSimpleName(), b.getClass().getSimpleName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("every predicate form is distinguished from every other")
        void everyPredicatePair() {
            for (Predicate a : predicates) {
                for (Predicate b : predicates) {
                    if (a == b) {
                        continue;
                    }
                    assertThat(AstEquivalence.equivalent(a, b))
                            .as("%s vs %s", a.getClass().getSimpleName(), b.getClass().getSimpleName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("each form is equivalent to a separately built copy of itself")
        void eachFormMatchesItsOwnCopy() {
            // The other half of the same claim: distinguishing every pair would also be
            // satisfied by a method that always answered false. The copy is built from a
            // second list rather than reused from the first, because `equivalent` returns
            // early on reference identity — comparing an element with itself would never
            // reach the arm being checked.
            List<Operand> copies = operands();
            assertThat(copies).hasSameSizeAs(operands);
            for (int i = 0; i < operands.size(); i++) {
                Operand a = operands.get(i);
                Operand b = copies.get(i);
                assertThat(b).as("the copy must be a distinct instance").isNotSameAs(a);
                assertThat(AstEquivalence.equivalent(a, b))
                        .as("%s vs a separately built copy", a.getClass().getSimpleName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("each predicate form is equivalent to a separately built copy of itself")
        void eachPredicateFormMatchesItsOwnCopy() {
            List<Predicate> copies = predicates();
            for (int i = 0; i < predicates.size(); i++) {
                Predicate a = predicates.get(i);
                Predicate b = copies.get(i);
                assertThat(b).as("the copy must be a distinct instance").isNotSameAs(a);
                assertThat(AstEquivalence.equivalent(a, b))
                        .as("%s vs a separately built copy", a.getClass().getSimpleName())
                        .isTrue();
            }
        }
    }

    /**
     * A pair agreeing on everything but one nested position.
     *
     * <p>Every recursive form compares its parts with a chain of {@code &&}, and a chain
     * is only as tested as its last link: a test that varies the operator has not shown
     * that the right-hand operand is compared at all. Each case here differs in exactly
     * one place, so it can only fail through the conjunct that names that place.
     */
    @Nested
    @DisplayName("Differing in one nested position is enough")
    class OneNestedDifference {

        private static final Operand X = attr("x");
        private static final Operand ONE = num("1");
        private static final Operand TWO = num("2");

        @Test
        @DisplayName("arithmetic: a difference in either operand is found")
        void arithmeticOperands() {
            var base = arith(X, ArithmeticOperator.PLUS, ONE);
            assertThat(AstEquivalence.equivalent(base,
                    arith(attr("y"),
                            ArithmeticOperator.PLUS, ONE)))
                    .as("left differs").isFalse();
            assertThat(AstEquivalence.equivalent(base,
                    arith(X, ArithmeticOperator.PLUS, TWO)))
                    .as("right differs").isFalse();
        }

        @Test
        @DisplayName("unary: a difference in the negated operand is found")
        void unaryOperand() {
            assertThat(AstEquivalence.equivalent(unary(ONE), unary(TWO)))
                    .isFalse();
        }

        @Test
        @DisplayName("a function call compares its name as well as its arguments")
        void functionName() {
            assertThat(AstEquivalence.equivalent(
                    func("Len",X), func("Abs",X)))
                    .isFalse();
        }

        @Test
        @DisplayName("a condition operand compares the predicate it wraps")
        void conditionPredicate() {
            assertThat(AstEquivalence.equivalent(
                    condition(nullPred(X, true)),
                    condition(nullPred(X, false)))).isFalse();
        }

        @Test
        @DisplayName("a set literal compares its elements, not only its size")
        void setElements() {
            assertThat(AstEquivalence.equivalent(
                    set(ONE, TWO),
                    set(ONE, num("3")))).isFalse();
        }

        @Test
        @DisplayName("an array construction compares its elements, not only its size")
        void arrayElements() {
            assertThat(AstEquivalence.equivalent(
                    arrayOf(ONE, TWO),
                    arrayOf(ONE, num("3")))).isFalse();
        }

        @Test
        @DisplayName("a struct compares field values, not only field names")
        void structFieldValues() {
            assertThat(AstEquivalence.equivalent(
                    structOf(new StructConstruction.Field("a", ONE)),
                    structOf(new StructConstruction.Field("a", TWO))))
                    .isFalse();
        }

        @Test
        @DisplayName("each temporal form compares its value, not merely its type")
        void temporalValues() {
            assertThat(AstEquivalence.equivalent(
                    date(LocalDate.of(2026, 1, 2)),
                    date(LocalDate.of(2026, 1, 3)))).isFalse();
            assertThat(AstEquivalence.equivalent(
                    time(LocalTime.of(9, 30)),
                    time(LocalTime.of(9, 31)))).isFalse();
            assertThat(AstEquivalence.equivalent(
                    timestamp(Instant.EPOCH),
                    timestamp(Instant.EPOCH.plusSeconds(1)))).isFalse();
            assertThat(AstEquivalence.equivalent(
                    duration(Duration.ofMinutes(1)),
                    duration(Duration.ofMinutes(2)))).isFalse();
        }

        @Test
        @DisplayName("a comparison compares both of its operands")
        void comparisonOperands() {
            var base = cmp(X, ComparisonOperator.GREATER, ONE);
            assertThat(AstEquivalence.equivalent(base,
                    cmp(attr("y"),
                            ComparisonOperator.GREATER, ONE)))
                    .as("left differs").isFalse();
            assertThat(AstEquivalence.equivalent(base,
                    cmp(X, ComparisonOperator.GREATER, TWO)))
                    .as("right differs").isFalse();
        }

        @Test
        @DisplayName("AND and OR each compare both of their branches")
        void connectiveBranches() {
            Predicate p = nullPred(X, true);
            Predicate q = nullPred(attr("y"), true);
            Predicate r = nullPred(attr("z"), true);
            assertThat(AstEquivalence.equivalent(and(p, q), and(r, q)))
                    .as("AND left differs").isFalse();
            assertThat(AstEquivalence.equivalent(and(p, q), and(p, r)))
                    .as("AND right differs").isFalse();
            assertThat(AstEquivalence.equivalent(or(p, q), or(r, q)))
                    .as("OR left differs").isFalse();
            assertThat(AstEquivalence.equivalent(or(p, q), or(p, r)))
                    .as("OR right differs").isFalse();
        }

        @Test
        @DisplayName("NOT compares the predicate it wraps")
        void notInner() {
            assertThat(AstEquivalence.equivalent(
                    not(nullPred(X, true)),
                    not(nullPred(X, false)))).isFalse();
        }

        @Test
        @DisplayName("a NULL test compares its operand as well as its sense")
        void nullOperand() {
            assertThat(AstEquivalence.equivalent(
                    nullPred(X, true),
                    nullPred(attr("y"), true))).isFalse();
        }

        @Test
        @DisplayName("∈ compares both the element and the set")
        void elementOfParts() {
            var set = set(ONE);
            var other = set(TWO);
            assertThat(AstEquivalence.equivalent(
                    AstBuilders.elementOf(X, set),
                    AstBuilders.elementOf(attr("y"), set)))
                    .as("element differs").isFalse();
            assertThat(AstEquivalence.equivalent(
                    AstBuilders.elementOf(X, set),
                    AstBuilders.elementOf(X, other)))
                    .as("set differs").isFalse();
        }

        @Test
        @DisplayName("LIKE compares its operand, its pattern and its negation")
        void patternParts() {
            var pattern = str("a%");
            assertThat(AstEquivalence.equivalent(
                    like(X, pattern),
                    like(attr("y"), pattern)))
                    .as("operand differs").isFalse();
            assertThat(AstEquivalence.equivalent(
                    like(X, pattern),
                    notLike(X, pattern)))
                    .as("negation differs").isFalse();
        }
    }
}

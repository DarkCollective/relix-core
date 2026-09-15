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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.processor.eval.PredicateEvaluator.Truth.FALSE;
import static com.darkcollective.relix.processor.eval.PredicateEvaluator.Truth.TRUE;
import static com.darkcollective.relix.processor.eval.PredicateEvaluator.Truth.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The NULL rule of every predicate kind, stated once.
 *
 * <p>{@link PredicateEvaluatorTest} covers what each predicate does with values it
 * has; this covers what it does with the one it does not. The two are separated
 * because the answers here are the ones a reader is most likely to assume rather
 * than look up — and because the whole set has to be read together to see the
 * property that matters: a row a predicate does not keep is not thereby a row its
 * negation keeps.
 */
@DisplayName("Three-valued logic — what each predicate answers about a NULL")
final class ThreeValuedLogicTest extends ProcessorTestSupport {

    private PredicateEvaluator eval;
    private Row row;

    @BeforeEach
    void setUp() {
        eval = new PredicateEvaluator(new OperandEvaluator());
        var schema = schema(
                col("id",     ScalarType.NUMBER),
                col("amount", ScalarType.NUMBER),
                col("code",   ScalarType.STRING));
        row = row(schema, num(1), nullVal(), nullVal());   // amount and code are NULL
    }

    /** A comparison of the NULL {@code amount} against a number. */
    private static ComparisonPredicate cmp(ComparisonOperator op, String literal) {
        return AstBuilders.cmp(attr("amount"), op, AstBuilders.num(literal));
    }

    private static Predicate truePred() {
        return AstBuilders.cmp(AstBuilders.num("1"), ComparisonOperator.EQUAL,
                AstBuilders.num("1"));
    }

    private static Predicate falsePred() {
        return AstBuilders.cmp(AstBuilders.num("1"), ComparisonOperator.EQUAL,
                AstBuilders.num("2"));
    }

    /** UNKNOWN: a comparison against the row's NULL amount. */
    private static Predicate unknownPred() {
        return cmp(ComparisonOperator.GREATER, "100");
    }

    @Nested
    @DisplayName("the leaves")
    class Leaves {

        @Test
        @DisplayName("every comparison against a NULL is UNKNOWN, equality included")
        void comparisons() {
            for (ComparisonOperator op : ComparisonOperator.values()) {
                assertThat(eval.truth(cmp(op, "100"), row))
                        .as("amount %s 100, where amount is NULL", op.symbol())
                        .isEqualTo(UNKNOWN);
            }
        }

        @Test
        @DisplayName("NULL = NULL is UNKNOWN, not true — two unknowns are not the same value")
        void nullEqualsNull() {
            assertThat(eval.truth(AstBuilders.cmp(
                    attr("amount"), ComparisonOperator.EQUAL, attr("code")), row))
                    .isEqualTo(UNKNOWN);
        }

        @Test
        @DisplayName("IS NULL / IS NOT NULL are never UNKNOWN — that is the point of them")
        void nullTests() {
            assertThat(eval.truth(nullPred(attr("amount"), true), row)).isEqualTo(TRUE);
            assertThat(eval.truth(nullPred(attr("amount"), false), row)).isEqualTo(FALSE);
            assertThat(eval.truth(nullPred(attr("id"), true), row)).isEqualTo(FALSE);
            assertThat(eval.truth(nullPred(attr("id"), false), row)).isEqualTo(TRUE);
        }

        @Test
        @DisplayName("LIKE and NOT LIKE against a NULL are both UNKNOWN")
        void patterns() {
            assertThat(eval.truth(
                    like(attr("code"), AstBuilders.str("A%")), row))
                    .isEqualTo(UNKNOWN);
            assertThat(eval.truth(
                    notLike(attr("code"), AstBuilders.str("A%")), row))
                    .isEqualTo(UNKNOWN);
        }

        @Test
        @DisplayName("a NULL element is UNKNOWN in a set and UNKNOWN out of it")
        void membershipOfNull() {
            SetLiteralOperand set = set(AstBuilders.num("1"), AstBuilders.num("2"));
            assertThat(eval.truth(elementOf(attr("amount"), set), row))
                    .isEqualTo(UNKNOWN);
            assertThat(eval.truth(notElementOf(attr("amount"), set), row))
                    .isEqualTo(UNKNOWN);
        }

        @Test
        @DisplayName("a NULL in the set makes a non-match UNKNOWN — it might have been the value")
        void nullInsideTheSet() {
            // id is 1. Matching 1 is TRUE regardless of what else the set holds…
            SetLiteralOperand hit = set(AstBuilders.num("1"), attr("amount"));
            assertThat(eval.truth(elementOf(attr("id"), hit), row))
                    .isEqualTo(TRUE);
            // …but failing to match 2 is only FALSE if nothing unknown remains.
            SetLiteralOperand miss = set(AstBuilders.num("2"), attr("amount"));
            assertThat(eval.truth(elementOf(attr("id"), miss), row))
                    .isEqualTo(UNKNOWN);
            assertThat(eval.truth(notElementOf(attr("id"), miss), row))
                    .isEqualTo(UNKNOWN);
        }
    }

    @Nested
    @DisplayName("the connectives")
    class Connectives {

        @Test
        @DisplayName("¬UNKNOWN is UNKNOWN — negation does not resolve a missing value")
        void negation() {
            assertThat(eval.truth(not(unknownPred()), row)).isEqualTo(UNKNOWN);
            assertThat(eval.truth(not(truePred()), row)).isEqualTo(FALSE);
            assertThat(eval.truth(not(falsePred()), row)).isEqualTo(TRUE);
        }

        @Test
        @DisplayName("AND: FALSE wins over UNKNOWN, TRUE does not")
        void conjunction() {
            assertThat(eval.truth(and(unknownPred(), falsePred()), row)).isEqualTo(FALSE);
            assertThat(eval.truth(and(falsePred(), unknownPred()), row)).isEqualTo(FALSE);
            assertThat(eval.truth(and(unknownPred(), truePred()), row)).isEqualTo(UNKNOWN);
            assertThat(eval.truth(and(truePred(), unknownPred()), row)).isEqualTo(UNKNOWN);
            assertThat(eval.truth(and(unknownPred(), unknownPred()), row)).isEqualTo(UNKNOWN);
        }

        @Test
        @DisplayName("OR: TRUE wins over UNKNOWN, FALSE does not")
        void disjunction() {
            assertThat(eval.truth(or(unknownPred(), truePred()), row)).isEqualTo(TRUE);
            assertThat(eval.truth(or(truePred(), unknownPred()), row)).isEqualTo(TRUE);
            assertThat(eval.truth(or(unknownPred(), falsePred()), row)).isEqualTo(UNKNOWN);
            assertThat(eval.truth(or(falsePred(), unknownPred()), row)).isEqualTo(UNKNOWN);
        }
    }

    @Nested
    @DisplayName("what an operator does with the third value")
    class Consumers {

        @Test
        @DisplayName("a row survives neither a predicate nor its negation")
        void neitherHalfKeepsIt() {
            Predicate p = unknownPred();
            assertThat(eval.evaluate(p, row)).isFalse();
            assertThat(eval.evaluate(not(p), row)).isFalse();
        }

        @Test
        @DisplayName("a predicate in operand position yields NULL, not false")
        void conditionOperandYieldsNull() {
            // The one place the third value becomes data: projecting `amount > 100`
            // for a row with no amount must not claim the amount is small.
            assertThat(new OperandEvaluator()
                    .evaluate(condition(unknownPred()), row).isNull())
                    .isTrue();
            assertThat(new OperandEvaluator()
                    .evaluate(condition(truePred()), row).asDisplayString())
                    .isEqualTo("true");
        }
    }
}

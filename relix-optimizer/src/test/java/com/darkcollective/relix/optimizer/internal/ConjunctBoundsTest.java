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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Range analysis over a conjunct list — the machinery behind {@code PRED-004} and
 * {@code PRED-005}, tested at its own seam.
 *
 * <p>{@link PredicateSimplificationPassTest} drives it through the pass, which normalises
 * a comparison to attribute-on-left first ({@code PRED-003}) and only ever asks about the
 * shapes a rewrite happens to produce. Several of the decisions here are invisible from
 * there: the literal-first orientation, the tie-break between an inclusive and an
 * exclusive bound at the same endpoint, and what "no bound in one direction" means.
 */
@DisplayName("ConjunctBounds — folding per-column bounds into an interval")
final class ConjunctBoundsTest {

    /** {@code column ⊙ literal}. */
    private static Predicate col(String column, ComparisonOperator op, Operand literal) {
        return cmp(attr(column), op, literal);
    }

    /** {@code literal ⊙ column} — the orientation PRED-003 would have normalised away. */
    private static Predicate lit(Operand literal, ComparisonOperator op, String column) {
        return cmp(literal, op, attr(column));
    }

    private static ConjunctBounds.Result analyse(Predicate... conjuncts) {
        return ConjunctBounds.analyse(List.of(conjuncts));
    }

    @Nested
    @DisplayName("Contradiction")
    class Contradictions {

        @Test
        @DisplayName("a lower bound above an upper bound admits nothing")
        void lowerAboveUpper() {
            assertThat(analyse(col("x", ComparisonOperator.GREATER, num("5")),
                    col("x", ComparisonOperator.LESS, num("3"))).contradiction()).isTrue();
        }

        @Test
        @DisplayName("a literal-first bound is mirrored, not ignored")
        void literalFirstIsMirrored() {
            // `5 < x` is a *lower* bound of 5. Reading it as written would make this pair
            // look like two upper bounds and miss the contradiction entirely.
            assertThat(analyse(lit(num("5"), ComparisonOperator.LESS, "x"),
                    col("x", ComparisonOperator.LESS, num("3"))).contradiction()).isTrue();
            assertThat(analyse(lit(num("3"), ComparisonOperator.GREATER, "x"),
                    col("x", ComparisonOperator.GREATER, num("5"))).contradiction()).isTrue();
        }

        @Test
        @DisplayName("two different equalities on one column contradict without a rule of their own")
        void twoEqualities() {
            assertThat(analyse(col("x", ComparisonOperator.EQUAL, num("5")),
                    col("x", ComparisonOperator.EQUAL, num("6"))).contradiction()).isTrue();
        }

        @Test
        @DisplayName("at a shared endpoint, both bounds must include it")
        void sharedEndpoint() {
            assertThat(analyse(col("x", ComparisonOperator.GREATER_EQUAL, num("5")),
                    col("x", ComparisonOperator.LESS_EQUAL, num("5"))).contradiction())
                    .as("x ≥ 5 ∧ x ≤ 5 holds at 5").isFalse();
            assertThat(analyse(col("x", ComparisonOperator.GREATER, num("5")),
                    col("x", ComparisonOperator.LESS_EQUAL, num("5"))).contradiction())
                    .as("an exclusive lower bound excludes it").isTrue();
            assertThat(analyse(col("x", ComparisonOperator.GREATER_EQUAL, num("5")),
                    col("x", ComparisonOperator.LESS, num("5"))).contradiction())
                    .as("an exclusive upper bound excludes it").isTrue();
        }

        @Test
        @DisplayName("a bound in one direction only is never a contradiction")
        void oneDirectionOnly() {
            assertThat(analyse(col("x", ComparisonOperator.GREATER, num("5")))
                    .contradiction()).as("no upper bound").isFalse();
            assertThat(analyse(col("x", ComparisonOperator.LESS, num("5")))
                    .contradiction()).as("no lower bound").isFalse();
        }

        @Test
        @DisplayName("bounds on different columns never contradict each other")
        void differentColumns() {
            assertThat(analyse(col("x", ComparisonOperator.GREATER, num("5")),
                    col("y", ComparisonOperator.LESS, num("3"))).contradiction()).isFalse();
        }

        @Test
        @DisplayName("a column is keyed case-insensitively, as everywhere else")
        void columnKeyIsCaseInsensitive() {
            assertThat(analyse(col("X", ComparisonOperator.GREATER, num("5")),
                    col("x", ComparisonOperator.LESS, num("3"))).contradiction()).isTrue();
        }

        @Test
        @DisplayName("mixed literal kinds abandon the column rather than compare across them")
        void mixedKindsAbandonTheColumn() {
            var result = analyse(col("x", ComparisonOperator.GREATER, num("5")),
                    col("x", ComparisonOperator.LESS, str("a")));
            assertThat(result.contradiction()).isFalse();
            assertThat(result.retained()).as("and nothing is dropped either").hasSize(2);
        }
    }

    @Nested
    @DisplayName("Subsumption")
    class Subsumption {

        @Test
        @DisplayName("the looser of two lower bounds is dropped")
        void looserLowerBoundDropped() {
            var loose = col("x", ComparisonOperator.GREATER, num("1"));
            var tight = col("x", ComparisonOperator.GREATER, num("3"));
            assertThat(analyse(loose, tight).retained()).containsExactly(tight);
        }

        @Test
        @DisplayName("the looser of two upper bounds is dropped")
        void looserUpperBoundDropped() {
            var loose = col("x", ComparisonOperator.LESS, num("9"));
            var tight = col("x", ComparisonOperator.LESS, num("4"));
            assertThat(analyse(loose, tight).retained()).containsExactly(tight);
        }

        @Test
        @DisplayName("at the same endpoint the exclusive bound wins, whichever came first")
        void exclusiveBeatsInclusiveEitherWay() {
            var inclusive = col("x", ComparisonOperator.GREATER_EQUAL, num("5"));
            var exclusive = col("x", ComparisonOperator.GREATER, num("5"));
            assertThat(analyse(inclusive, exclusive).retained())
                    .as("candidate is tighter").containsExactly(exclusive);
            assertThat(analyse(exclusive, inclusive).retained())
                    .as("incumbent is already the tighter one").containsExactly(exclusive);
        }

        @Test
        @DisplayName("two identical bounds keep the earliest-written one")
        void identicalBoundsKeepTheFirst() {
            var first = col("x", ComparisonOperator.GREATER, num("5"));
            var second = col("x", ComparisonOperator.GREATER, num("5"));
            assertThat(analyse(first, second).retained()).containsExactly(first);
        }

        /**
         * The same tie, written inclusively. The one above is a tie between two
         * <em>exclusive</em> bounds, which the tie-break settles by short-circuiting on
         * its first term — so it never asks whether the candidate is inclusive, and the
         * inclusive-against-inclusive tie was decided by a line no test had run.
         */
        @Test
        @DisplayName("two identical inclusive bounds keep the earliest-written one too")
        void identicalInclusiveBoundsKeepTheFirst() {
            var first = col("x", ComparisonOperator.GREATER_EQUAL, num("5"));
            var second = col("x", ComparisonOperator.GREATER_EQUAL, num("5"));
            assertThat(analyse(first, second).retained()).containsExactly(first);
        }

        @Test
        @DisplayName("an equality survives beside a range it implies")
        void equalityBeatsARange() {
            var equality = col("x", ComparisonOperator.EQUAL, num("5"));
            var range = col("x", ComparisonOperator.GREATER, num("3"));
            assertThat(analyse(equality, range).retained()).containsExactly(equality);
        }

        @Test
        @DisplayName("bounds in opposite directions are both kept")
        void oppositeDirectionsBothKept() {
            var lower = col("x", ComparisonOperator.GREATER, num("1"));
            var upper = col("x", ComparisonOperator.LESS, num("9"));
            assertThat(analyse(lower, upper).retained()).containsExactly(lower, upper);
        }
    }

    @Nested
    @DisplayName("What is not a bound")
    class NotBounds {

        private void isNotABound(Predicate p) {
            var other = col("x", ComparisonOperator.GREATER, num("5"));
            var result = analyse(p, other);
            assertThat(result.contradiction()).isFalse();
            assertThat(result.retained())
                    .as("a non-bound is carried through untouched")
                    .containsExactly(p, other);
        }

        @Test
        @DisplayName("≠ excises a point rather than moving an endpoint")
        void notEqual() {
            isNotABound(col("x", ComparisonOperator.NOT_EQUAL, num("2")));
        }

        @Test
        @DisplayName("a BOOLEAN literal is a literal but not usefully orderable")
        void booleanLiteral() {
            isNotABound(col("flag", ComparisonOperator.GREATER, bool(true)));
        }

        @Test
        @DisplayName("a column-to-column comparison bounds nothing")
        void columnToColumn() {
            isNotABound(cmp(attr("a"), ComparisonOperator.LESS,
                    attr("b")));
        }

        @Test
        @DisplayName("a predicate that is not a comparison at all")
        void nonComparison() {
            isNotABound(nullPred(attr("a"), true));
        }

        @Test
        @DisplayName("an empty conjunct list analyses to nothing")
        void emptyList() {
            var result = ConjunctBounds.analyse(List.of());
            assertThat(result.contradiction()).isFalse();
            assertThat(result.retained()).isEmpty();
        }
    }
}

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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.ast.Expr.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("Predicates — shared conjunct/literal helpers")
final class PredicatesTest {

    // ─── helpers ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("conjuncts")
    class Conjuncts {

        @Test
        @DisplayName("a non-conjunction yields itself")
        void singleton() {
            Predicate p = eq(attr("a"), num("1"));
            assertThat(Predicates.conjuncts(p)).containsExactly(p);
        }

        @Test
        @DisplayName("flattens a nested conjunction left-to-right")
        void flattensNested() {
            Predicate a = eq(attr("a"), num("1"));
            Predicate b = eq(attr("b"), num("2"));
            Predicate c = eq(attr("c"), num("3"));

            // a ∧ (b ∧ c) and (a ∧ b) ∧ c both flatten to [a, b, c].
            assertThat(Predicates.conjuncts(and(a, and(b, c)))).containsExactly(a, b, c);
            assertThat(Predicates.conjuncts(and(and(a, b), c))).containsExactly(a, b, c);
        }

        @Test
        @DisplayName("does not descend through OR or NOT — they stay opaque leaves")
        void doesNotDescendThroughOrNot() {
            Predicate a  = eq(attr("a"), num("1"));
            Predicate b  = eq(attr("b"), num("2"));
            Predicate or = or(a, b);
            Predicate no = not(a);

            // The OR keeps its inner conjunction hidden — that is the contract a
            // pushdown rule relies on to know it may not split those apart.
            assertThat(Predicates.conjuncts(and(or, no))).containsExactly(or, no);
            assertThat(Predicates.conjuncts(or(and(a, b), b)))
                    .hasSize(1);
        }

        @Test
        @DisplayName("returns a mutable list callers may accumulate into")
        void resultIsMutable() {
            Predicate a = eq(attr("a"), num("1"));
            var out = Predicates.conjuncts(a);
            out.add(eq(attr("b"), num("2")));
            assertThat(out).hasSize(2);
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> Predicates.conjuncts(null));
        }
    }

    @Nested
    @DisplayName("conjoin")
    class Conjoin {

        @Test
        @DisplayName("a single conjunct is returned as-is")
        void singleton() {
            Predicate a = eq(attr("a"), num("1"));
            assertThat(Predicates.conjoin(List.of(a))).isSameAs(a);
        }

        @Test
        @DisplayName("round-trips with conjuncts")
        void roundTrips() {
            Predicate a = eq(attr("a"), num("1"));
            Predicate b = eq(attr("b"), num("2"));
            Predicate c = eq(attr("c"), num("3"));
            List<Predicate> parts = List.of(a, b, c);

            assertThat(Predicates.conjuncts(Predicates.conjoin(parts)))
                    .containsExactlyElementsOf(parts);
        }

        @Test
        @DisplayName("builds a left-deep chain")
        void leftDeep() {
            Predicate a = eq(attr("a"), num("1"));
            Predicate b = eq(attr("b"), num("2"));
            Predicate c = eq(attr("c"), num("3"));

            var top = (AndPredicate) Predicates.conjoin(List.of(a, b, c));
            assertThat(top.right()).isSameAs(c);
            assertThat(top.left()).isInstanceOf(AndPredicate.class);
            assertThat(((AndPredicate) top.left()).left()).isSameAs(a);
        }

        @Test
        @DisplayName("rejects an empty list rather than returning a silent tautology")
        void rejectsEmpty() {
            assertThatIllegalArgumentException().isThrownBy(() -> Predicates.conjoin(List.of()));
        }
    }

    @Nested
    @DisplayName("isLiteral")
    class IsLiteral {

        @Test
        @DisplayName("accepts every literal form, temporal ones included")
        void acceptsAllLiterals() {
            // The temporal cases are the regression: they were missing from one
            // copy of this test, which silently disabled rewrites keyed on it.
            assertThat(Predicates.isLiteral(num("1"))).isTrue();
            assertThat(Predicates.isLiteral(str("a"))).isTrue();
            assertThat(Predicates.isLiteral(bool(true))).isTrue();
            assertThat(Predicates.isLiteral(date(LocalDate.parse("2024-01-01")))).isTrue();
            assertThat(Predicates.isLiteral(time(LocalTime.parse("09:30:00")))).isTrue();
            assertThat(Predicates.isLiteral(
                    timestamp(Instant.parse("2024-01-01T00:00:00Z")))).isTrue();
            assertThat(Predicates.isLiteral(duration(Duration.ofHours(2)))).isTrue();
        }

        @Test
        @DisplayName("rejects anything computed")
        void rejectsComputed() {
            assertThat(Predicates.isLiteral(attr("a"))).isFalse();
            assertThat(Predicates.isLiteral(arith(
                    num("1"), ArithmeticOperator.PLUS, num("2")))).isFalse();
            assertThat(Predicates.isLiteral(func("Abs",num("1")))).isFalse();
        }
    }

    @Nested
    @DisplayName("isColumn")
    class IsColumn {

        @Test
        @DisplayName("matches case-insensitively and ignores a relation qualifier")
        void matchesIgnoringCaseAndQualifier() {
            assertThat(Predicates.isColumn(attr("id"), "id")).isTrue();
            assertThat(Predicates.isColumn(attr("ID"), "id")).isTrue();
            assertThat(Predicates.isColumn(attr("Users.id"), "id")).isTrue();
        }

        @Test
        @DisplayName("rejects a different column or a non-attribute")
        void rejectsOthers() {
            assertThat(Predicates.isColumn(attr("other"), "id")).isFalse();
            assertThat(Predicates.isColumn(num("1"), "id")).isFalse();
        }
    }

    @Nested
    @DisplayName("equalityLiteralFor")
    class EqualityLiteralFor {

        @Test
        @DisplayName("finds the literal in either argument order")
        void eitherOrder() {
            var five = num("5");
            assertThat(Predicates.equalityLiteralFor(eq(attr("id"), five), "id")).contains(five);
            assertThat(Predicates.equalityLiteralFor(eq(five, attr("id")), "id")).contains(five);
        }

        @Test
        @DisplayName("finds a temporal literal too")
        void temporalLiteral() {
            var ts = timestamp(Instant.parse("2024-01-01T00:00:00Z"));
            assertThat(Predicates.equalityLiteralFor(eq(attr("at"), ts), "at")).contains(ts);
        }

        @Test
        @DisplayName("empty for a different column, operator, or a computed side")
        void emptyOtherwise() {
            var five = num("5");
            assertThat(Predicates.equalityLiteralFor(eq(attr("id"), five), "other")).isEmpty();
            assertThat(Predicates.equalityLiteralFor(
                    cmp(attr("id"), ComparisonOperator.LESS, five), "id"))
                    .isEmpty();
            assertThat(Predicates.equalityLiteralFor(eq(attr("id"), attr("other")), "id")).isEmpty();
            assertThat(Predicates.equalityLiteralFor(nullPred(attr("id"), true), "id"))
                    .isEmpty();
        }
    }
}

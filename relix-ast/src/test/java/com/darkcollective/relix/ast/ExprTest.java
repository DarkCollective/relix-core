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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;
import static com.darkcollective.relix.ast.AstAssertions.assertThatThrownBy;

@DisplayName("Expr — the readable spelling of the AST factories")
final class ExprTest extends Expr {

    @Nested
    @DisplayName("literals from Java values")
    final class Literals {

        @Test
        @DisplayName("an integral value keeps its exact spelling")
        void integralValue() {
            assertThat(num(42L).value()).isEqualTo("42");
            assertThat(num(-7L).value()).isEqualTo("-7");
            assertThat(num(Long.MAX_VALUE).value()).isEqualTo("9223372036854775807");
        }

        @Test
        @DisplayName("a double reads as the shortest decimal that round-trips, not its binary expansion")
        void doubleValue() {
            assertThat(num(0.1).value()).isEqualTo("0.1");
            assertThat(num(1.0E-7).value()).isEqualTo("0.0000001");
            assertThat(num(1234.5).value()).isEqualTo("1234.5");
        }

        @Test
        @DisplayName("a double's trailing zero is not part of the value, so 5.0 spells 5")
        void doubleDropsTrailingZeros() {
            // Double.toString(5.0) is "5.0" and BigDecimal.valueOf keeps that scale;
            // the literal is the number, and NumberOperand comparison is by exact
            // BigDecimal anyway. num(BigDecimal) is the way to mean a scale.
            assertThat(num(5.0).value()).isEqualTo("5");
            assertThat(num(100.0).value()).isEqualTo("100");
            assertThat(num(new BigDecimal("5.0")).value()).isEqualTo("5.0");
        }

        @Test
        @DisplayName("a BigDecimal keeps its scale, so 5 and 5.0 stay distinct literals")
        void decimalValue() {
            assertThat(num(new BigDecimal("5")).value()).isEqualTo("5");
            assertThat(num(new BigDecimal("5.00")).value()).isEqualTo("5.00");
        }

        @Test
        @DisplayName("temporal values are carried as parsed java.time values, never reformatted text")
        void temporalValues() {
            LocalDate day = LocalDate.of(2026, 6, 15);
            LocalTime at = LocalTime.of(13, 40);
            Instant when = Instant.parse("2026-06-15T13:40:00Z");
            Duration how = Duration.ofMinutes(30);

            assertThat(date(day).value()).isEqualTo(day);
            assertThat(time(at).value()).isEqualTo(at);
            assertThat(timestamp(when).value()).isEqualTo(when);
            assertThat(duration(how).value()).isEqualTo(how);
        }
    }

    @Nested
    @DisplayName("lit — a literal for a value whose type is known only at runtime")
    final class Lit {

        @Test
        @DisplayName("dispatches on the runtime type")
        void dispatches() {
            assertThat(lit("open")).isEqualTo(str("open"));
            assertThat(lit(true)).isEqualTo(bool(true));
            assertThat(lit(42)).isEqualTo(num(42L));
            assertThat(lit(42L)).isEqualTo(num(42L));
            assertThat(lit((short) 7)).isEqualTo(num(7L));
            assertThat(lit(0.5)).isEqualTo(num(0.5));
            assertThat(lit(0.5f)).isEqualTo(num(0.5));
            assertThat(lit(new BigDecimal("5.00"))).isEqualTo(num(new BigDecimal("5.00")));
            assertThat(lit(LocalDate.of(2026, 6, 15))).isEqualTo(date(LocalDate.of(2026, 6, 15)));
            assertThat(lit(LocalTime.of(9, 0))).isEqualTo(time(LocalTime.of(9, 0)));
            assertThat(lit(Instant.EPOCH)).isEqualTo(timestamp(Instant.EPOCH));
            assertThat(lit(Duration.ofDays(1))).isEqualTo(duration(Duration.ofDays(1)));
        }

        @Test
        @DisplayName("an operand passes through, so a call site may mix values and expressions")
        void operandPassesThrough() {
            Operand column = attr("amount");
            assertThat(lit(column)).isSameAs(column);
        }

        @Test
        @DisplayName("null is rejected rather than becoming the string \"null\"")
        void rejectsNull() {
            assertThatThrownBy(() -> lit(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("isNull");
        }

        @Test
        @DisplayName("a value with no literal form is rejected, naming its type")
        void rejectsUnsupported() {
            assertThatThrownBy(() -> lit(List.of(1, 2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("List");
        }
    }

    @Nested
    @DisplayName("binding a value is not string-building")
    final class Binding {

        @Test
        @DisplayName("a value that would be dangerous as text is carried as a literal node")
        void valueNeverBecomesSyntax() {
            // The point of the whole surface: this is data in a StringOperand, never
            // characters the parser will read. There is nothing to escape because
            // nothing is parsed.
            String hostile = "'; DROP TABLE Orders --";
            Predicate p = eq(attr("status"), str(hostile));

            assertThat(p).isInstanceOf(ComparisonPredicate.class);
            ComparisonPredicate c = (ComparisonPredicate) p;
            assertThat(c.right()).isEqualTo(new StringOperand(hostile));
            assertThat(((StringOperand) c.right()).value()).isEqualTo(hostile);
        }

        @Test
        @DisplayName("the built node is the one AstBuilders builds the long way")
        void sameNodeAsTheRecordSpelling() {
            assertThat(eq(attr("id"), num(42L)))
                    .isEqualTo(cmp(attr("id"), ComparisonOperator.EQUAL, num("42")));
        }
    }

    @Nested
    @DisplayName("comparisons")
    final class Comparisons {

        @Test
        @DisplayName("each shorthand names its operator")
        void eachOperator() {
            assertThat(eq(attr("a"), num(1L)).operator()).isEqualTo(ComparisonOperator.EQUAL);
            assertThat(ne(attr("a"), num(1L)).operator()).isEqualTo(ComparisonOperator.NOT_EQUAL);
            assertThat(lt(attr("a"), num(1L)).operator()).isEqualTo(ComparisonOperator.LESS);
            assertThat(le(attr("a"), num(1L)).operator()).isEqualTo(ComparisonOperator.LESS_EQUAL);
            assertThat(gt(attr("a"), num(1L)).operator()).isEqualTo(ComparisonOperator.GREATER);
            assertThat(ge(attr("a"), num(1L)).operator()).isEqualTo(ComparisonOperator.GREATER_EQUAL);
        }
    }

    @Nested
    @DisplayName("connectives")
    final class Connectives {

        private final Predicate a = eq(attr("a"), num(1L));
        private final Predicate b = eq(attr("b"), num(2L));
        private final Predicate c = eq(attr("c"), num(3L));

        @Test
        @DisplayName("one predicate folds to itself, not to a one-armed conjunction")
        void singleFoldsToItself() {
            assertThat(allOf(a)).isSameAs(a);
            assertThat(anyOf(a)).isSameAs(a);
        }

        @Test
        @DisplayName("several fold left, nesting the way a parsed a ∧ b ∧ c does")
        void foldsLeft() {
            assertThat(allOf(a, b, c)).isEqualTo(and(and(a, b), c));
            assertThat(anyOf(a, b, c)).isEqualTo(or(or(a, b), c));
        }

        @Test
        @DisplayName("the List form matches the varargs form")
        void listFormMatches() {
            assertThat(allOf(List.of(a, b, c))).isEqualTo(allOf(a, b, c));
            assertThat(anyOf(List.of(a, b, c))).isEqualTo(anyOf(a, b, c));
        }

        @Test
        @DisplayName("no predicates is rejected — there is no identity element to invent")
        void rejectsEmpty() {
            assertThatThrownBy(() -> allOf())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("allOf");
            assertThatThrownBy(() -> anyOf(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("anyOf");
        }
    }

    @Nested
    @DisplayName("null tests, membership and arithmetic")
    final class Rest {

        @Test
        @DisplayName("isNull and isNotNull are the two directions of one node")
        void nullTests() {
            assertThat(isNull(attr("a")).isNull()).isTrue();
            assertThat(isNotNull(attr("a")).isNull()).isFalse();
        }

        @Test
        @DisplayName("in builds the set literal for you")
        void membership() {
            assertThat(in(attr("status"), str("OPEN"), str("HELD")))
                    .isEqualTo(elementOf(attr("status"), set(str("OPEN"), str("HELD"))));
        }

        @Test
        @DisplayName("each arithmetic shorthand names its operator")
        void arithmetic() {
            assertThat(plus(attr("a"), num(1L)).operator()).isEqualTo(ArithmeticOperator.PLUS);
            assertThat(minus(attr("a"), num(1L)).operator()).isEqualTo(ArithmeticOperator.MINUS);
            assertThat(times(attr("a"), num(1L)).operator()).isEqualTo(ArithmeticOperator.MULTIPLY);
            assertThat(dividedBy(attr("a"), num(1L)).operator()).isEqualTo(ArithmeticOperator.DIVIDE);
        }
    }

    @Nested
    @DisplayName("one surface")
    final class OneSurface {

        @Test
        @DisplayName("extending Expr reaches the node factories too")
        void inheritsAstBuilders() {
            // The whole tree below is built from one import: the relational factories
            // come from AstBuilders, the readable expression forms from Expr.
            RelNode plan = select(
                    allOf(eq(attr("status"), str("OPEN")), gt(attr("amount"), num(100L))),
                    rel("Orders"));

            assertThat(plan).isNode(SelectionNode.class)
                    .input().isRelation("Orders");
        }
    }
}

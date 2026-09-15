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

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.function.FunctionCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.Expr.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MongoExpressions — predicate/$match and operand/$project translation")
final class MongoExpressionsTest {

    /**
     * The installed function libraries — a function's aggregation-expression spelling
     * comes from the function itself, so a renderer with no catalogue folds no calls.
     */
    private static final PushdownFunctions FUNCTIONS =
            PushdownFunctions.of(FunctionCatalog.discover());

    private static Optional<String> match(Predicate predicate) {
        return MongoExpressions.match(predicate);
    }

    @Nested
    @DisplayName("comparisons")
    class Comparisons {

        @Test
        @DisplayName("each operator maps to its $-operator, field versus literal")
        void operators() {
            assertThat(match(cmp(attr("a"), ComparisonOperator.EQUAL, num("1"))))
                    .hasValue("{\"a\": {\"$eq\": 1}}");
            // $ne alone matches a document with no `a` at all, which is the row
            // ¬UNKNOWN drops — hence the null guard on this operator and no other.
            assertThat(match(cmp(attr("a"), ComparisonOperator.NOT_EQUAL, num("1"))))
                    .hasValue("{\"$and\": [{\"a\": {\"$ne\": 1}}, {\"a\": {\"$ne\": null}}]}");
            assertThat(match(cmp(attr("a"), ComparisonOperator.LESS, num("1"))))
                    .hasValue("{\"a\": {\"$lt\": 1}}");
            assertThat(match(cmp(attr("a"), ComparisonOperator.LESS_EQUAL, num("1"))))
                    .hasValue("{\"a\": {\"$lte\": 1}}");
            assertThat(match(cmp(attr("a"), ComparisonOperator.GREATER, num("1"))))
                    .hasValue("{\"a\": {\"$gt\": 1}}");
            assertThat(match(cmp(attr("a"), ComparisonOperator.GREATER_EQUAL, num("1"))))
                    .hasValue("{\"a\": {\"$gte\": 1}}");
        }

        @Test
        @DisplayName("the field qualifier is dropped (single-collection)")
        void dropsQualifier() {
            assertThat(match(cmp(attr("Docs.id"), ComparisonOperator.EQUAL, num("7"))))
                    .hasValue("{\"id\": {\"$eq\": 7}}");
        }

        @Test
        @DisplayName("a boolean literal renders as a JSON value")
        void booleanLiteral() {
            assertThat(match(cmp(attr("ok"), ComparisonOperator.EQUAL, bool(true))))
                    .hasValue("{\"ok\": {\"$eq\": true}}");
            assertThat(match(cmp(attr("ok"), ComparisonOperator.EQUAL, bool(false))))
                    .hasValue("{\"ok\": {\"$eq\": false}}");
        }

        @Test
        @DisplayName("a string literal escapes quotes, backslashes, and control chars")
        void stringEscaping() {
            String raw = "a\"b\\c\nd\re\tf" + ((char) 1) + "g";
            assertThat(match(cmp(attr("s"), ComparisonOperator.EQUAL, str(raw))))
                    .hasValue("{\"s\": {\"$eq\": \"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\"}}");
        }

        @Test
        @DisplayName("a negative numeric literal (unary minus) renders signed")
        void negativeLiteral() {
            assertThat(match(cmp(attr("delta"), ComparisonOperator.LESS,
                    unary(num("5")))))
                    .hasValue("{\"delta\": {\"$lt\": -5}}");
        }

        @Test
        @DisplayName("unary minus over a non-number is not a literal — falls back")
        void unaryMinusOverNonNumber() {
            assertThat(match(cmp(attr("a"), ComparisonOperator.LESS, unary(attr("b"))))).isEmpty();
        }

        @Test
        @DisplayName("comparing two fields is not translatable (would need $expr)")
        void fieldVersusField() {
            assertThat(match(cmp(attr("a"), ComparisonOperator.GREATER, attr("b")))).isEmpty();
        }

        @Test
        @DisplayName("a computed left side is not translatable")
        void computedLeftSide() {
            Operand expr = arith(
                    attr("price"), ArithmeticOperator.MULTIPLY, num("2"));
            assertThat(match(cmp(expr, ComparisonOperator.GREATER, num("3")))).isEmpty();
        }
    }

    @Nested
    @DisplayName("boolean composition")
    class Composition {

        private final Predicate a = cmp(attr("a"), ComparisonOperator.GREATER, num("0"));
        private final Predicate b = cmp(attr("b"), ComparisonOperator.LESS, num("9"));

        @Test
        @DisplayName("AND → $and, OR → $or")
        void andOr() {
            assertThat(match(and(a, b))).hasValue(
                    "{\"$and\": [{\"a\": {\"$gt\": 0}}, {\"b\": {\"$lt\": 9}}]}");
            assertThat(match(or(a, b))).hasValue(
                    "{\"$or\": [{\"a\": {\"$gt\": 0}}, {\"b\": {\"$lt\": 9}}]}");
        }

        @Test
        @DisplayName("NOT is not pushed — $nor keeps the documents ¬UNKNOWN drops")
        void not() {
            // A null guard would need every field the inner predicate reads, which a
            // general predicate does not hand over. Declining leaves the σ in-engine,
            // where the three-valued answer is the engine's own.
            assertThat(match(AstBuilders.not(a))).isEmpty();
        }

        @Test
        @DisplayName("an untranslatable branch makes the whole composition fall back")
        void untranslatableBranchPropagates() {
            Predicate bad = cmp(attr("a"), ComparisonOperator.EQUAL, attr("b"));
            assertThat(match(and(a, bad))).isEmpty();
            assertThat(match(and(bad, a))).isEmpty();
            assertThat(match(or(a, bad))).isEmpty();
            assertThat(match(AstBuilders.not(bad))).isEmpty();
        }
    }

    @Nested
    @DisplayName("null checks and set membership")
    class NullAndIn {

        @Test
        @DisplayName("IS NULL → $eq null, IS NOT NULL → $ne null")
        void nullChecks() {
            assertThat(match(nullPred(attr("a"), true))).hasValue("{\"a\": {\"$eq\": null}}");
            assertThat(match(nullPred(attr("a"), false))).hasValue("{\"a\": {\"$ne\": null}}");
        }

        @Test
        @DisplayName("a null check on a non-field falls back")
        void nullOnNonField() {
            assertThat(match(nullPred(num("1"), true))).isEmpty();
        }

        @Test
        @DisplayName("IN → $in, NOT IN → $nin over a literal set")
        void inAndNotIn() {
            SetLiteralOperand set = set(num("1"), num("2"));
            assertThat(match(elementOf(attr("a"), set)))
                    .hasValue("{\"a\": {\"$in\": [1, 2]}}");
            assertThat(match(notElementOf(attr("a"), set)))
                    .hasValue("{\"$and\": [{\"a\": {\"$nin\": [1, 2]}}, {\"a\": {\"$ne\": null}}]}");
        }

        @Test
        @DisplayName("a non-literal set element makes IN fall back")
        void inWithNonLiteralElement() {
            SetLiteralOperand set = set(num("1"), attr("b"));
            assertThat(match(elementOf(attr("a"), set))).isEmpty();
        }

        @Test
        @DisplayName("an IN whose left side is not a field falls back")
        void inWithNonFieldElement() {
            SetLiteralOperand set = set(num("1"));
            assertThat(match(elementOf(num("1"), set))).isEmpty();
        }
    }

    @Nested
    @DisplayName("LIKE / NOT LIKE pattern predicates")
    class PatternPredicates {

        @Test
        @DisplayName("LIKE %pattern% → $regex anchored ^.*pattern.*$")
        void likeWithLeadingAndTrailingPercent() {
            assertThat(match(like(attr("name"), str("%smith%"))))
                    .hasValue("{\"name\": {\"$regex\": \"^.*smith.*$\"}}");
        }

        @Test
        @DisplayName("LIKE prefix% → $regex ^prefix.*$")
        void likePrefix() {
            assertThat(match(like(attr("name"), str("Alice%"))))
                    .hasValue("{\"name\": {\"$regex\": \"^Alice.*$\"}}");
        }

        @Test
        @DisplayName("LIKE _BC → $regex ^.BC$")
        void likeUnderscore() {
            assertThat(match(like(attr("code"), str("_BC"))))
                    .hasValue("{\"code\": {\"$regex\": \"^.BC$\"}}");
        }

        @Test
        @DisplayName("LIKE with regex metacharacters escapes them")
        void likeEscapesMetacharacters() {
            // pattern "a.b" → regex "^a\.b$"; backslash in JSON is doubled → "^a\\.b$"
            assertThat(match(like(attr("val"), str("a.b"))))
                    .hasValue("{\"val\": {\"$regex\": \"^a\\\\.b$\"}}");
        }

        @Test
        @DisplayName("NOT LIKE → $not $regex")
        void notLike() {
            assertThat(match(AstBuilders.notLike(attr("name"), str("%test%"))))
                    .hasValue("{\"$and\": [{\"name\": {\"$not\": {\"$regex\": \"^.*test.*$\"}}}, "
                            + "{\"name\": {\"$ne\": null}}]}");
        }

        @Test
        @DisplayName("qualifier on field is dropped")
        void qualifierDropped() {
            assertThat(match(like(attr("Docs.name"), str("A%"))))
                    .hasValue("{\"name\": {\"$regex\": \"^A.*$\"}}");
        }

        @Test
        @DisplayName("LIKE with a non-attribute left side falls back")
        void nonFieldLeftFallsBack() {
            assertThat(match(like(
                    str("literal"), str("A%")))).isEmpty();
        }

        @Test
        @DisplayName("LIKE with a non-string pattern falls back")
        void nonStringPatternFallsBack() {
            assertThat(match(like(attr("name"), num("42")))).isEmpty();
        }
    }

    @Nested
    @DisplayName("temporal literals (ADR-0013)")
    class TemporalLiterals {

        @Test
        @DisplayName("a TIMESTAMP comparison folds to a $date $match")
        void timestampMatch() {
            var ts = new com.darkcollective.relix.ast.TimestampOperand(
                    java.time.Instant.parse("2026-06-15T13:40:00Z"));
            assertThat(match(cmp(attr("at"), ComparisonOperator.GREATER_EQUAL, ts)))
                    .hasValue("{\"at\": {\"$gte\": {\"$date\": \"2026-06-15T13:40:00Z\"}}}");
        }

        @Test
        @DisplayName("DATE / DURATION literals are not pushable (fall back to in-engine)")
        void dateAndDurationNotPushable() {
            var date = new com.darkcollective.relix.ast.DateOperand(java.time.LocalDate.parse("2026-06-15"));
            var dur = new com.darkcollective.relix.ast.DurationOperand(java.time.Duration.parse("PT30M"));
            assertThat(match(cmp(attr("d"), ComparisonOperator.EQUAL, date))).isEmpty();
            assertThat(match(cmp(attr("held"), ComparisonOperator.GREATER, dur))).isEmpty();
        }
    }

    @Nested
    @DisplayName("$project expression rendering (ADR-0013)")
    class TemporalExpressions {

        private static Optional<String> expr(Operand op) {
            return MongoExpressions.expression(op, FUNCTIONS);
        }

        @Test
        @DisplayName("an attribute reference becomes a field-reference expression")
        void attributeFieldRef() {
            assertThat(expr(attr("at"))).hasValue("\"$at\"");
            assertThat(expr(attr("Docs.at"))).hasValue("\"$at\"");   // qualifier stripped
        }

        @Test
        @DisplayName("scalar literals render as JSON values")
        void scalarLiterals() {
            assertThat(expr(num("42"))).hasValue("42");
            assertThat(expr(str("hour"))).hasValue("\"hour\"");
            assertThat(expr(bool(true))).hasValue("true");
            assertThat(expr(bool(false))).hasValue("false");
        }

        @Test
        @DisplayName("a TIMESTAMP literal renders as extended-JSON $date")
        void timestampLiteral() {
            assertThat(expr(timestamp(Instant.parse("2026-06-15T13:40:00Z"))))
                    .hasValue("{\"$date\": \"2026-06-15T13:40:00Z\"}");
        }

        @Test
        @DisplayName("YEAR/MONTH/DAY/HOUR/MINUTE/SECOND render as MongoDB extraction operators")
        void extractionFunctions() {
            assertThat(expr(func("YEAR",attr("at")))).hasValue("{\"$year\": \"$at\"}");
            assertThat(expr(func("MONTH",attr("at")))).hasValue("{\"$month\": \"$at\"}");
            assertThat(expr(func("DAY",attr("at")))).hasValue("{\"$dayOfMonth\": \"$at\"}");
            assertThat(expr(func("HOUR",attr("at")))).hasValue("{\"$hour\": \"$at\"}");
            assertThat(expr(func("MINUTE",attr("at")))).hasValue("{\"$minute\": \"$at\"}");
            assertThat(expr(func("SECOND",attr("at")))).hasValue("{\"$second\": \"$at\"}");
        }

        @Test
        @DisplayName("function names are case-insensitive")
        void functionNameCaseInsensitive() {
            assertThat(expr(func("year",attr("at")))).hasValue("{\"$year\": \"$at\"}");
            assertThat(expr(func("Year",attr("at")))).hasValue("{\"$year\": \"$at\"}");
        }

        @Test
        @DisplayName("DATE_TRUNC renders as $dateTrunc with date and unit")
        void dateTrunc() {
            // DATE_TRUNC('hour', at) → {"$dateTrunc": {"date": "$at", "unit": "hour"}}
            var call = func("DATE_TRUNC",str("hour"), attr("at"));
            assertThat(expr(call))
                    .hasValue("{\"$dateTrunc\": {\"date\": \"$at\", \"unit\": \"hour\"}}");
        }

        @Test
        @DisplayName("EXTRACT with wrong arg count is not pushable")
        void extractWrongArgCount() {
            assertThat(expr(func("YEAR"))).isEmpty();
        }

        @Test
        @DisplayName("DATE_TRUNC with wrong arg count is not pushable")
        void dateTruncWrongArgCount() {
            assertThat(expr(func("DATE_TRUNC",str("hour")))).isEmpty();
        }

        @Test
        @DisplayName("EXTRACT with an untranslatable inner operand falls back")
        void extractUntranslatableInner() {
            assertThat(expr(func("YEAR",func("UCase",attr("at"))))).isEmpty();
        }

        @Test
        @DisplayName("a function with no MongoDB spelling is not pushable")
        void functionWithoutASpelling() {
            assertThat(expr(func("UCase",attr("name")))).isEmpty();
        }

        @Test
        @DisplayName("a name no library offers is not pushable")
        void unknownFunction() {
            assertThat(expr(func("NoSuchFunction",attr("name"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("arithmetic and other operand types fall back")
        void unsupportedOperandTypes() {
            // Arithmetic expressions, unary minus, set literals — all fall back
            assertThat(expr(new com.darkcollective.relix.ast.BinaryArithmeticExpression(
                    attr("a"), com.darkcollective.relix.ast.ArithmeticOperator.PLUS, attr("b"))))
                    .isEmpty();
        }
    }
}

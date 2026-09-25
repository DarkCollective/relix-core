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
package com.darkcollective.relix.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;


/**
 * Comprehensive operand testing for all operand types and edge cases.
 * Covers: AttributeOperand, NumberOperand, StringOperand, BooleanOperand,
 * FunctionCall, BinaryArithmeticExpression, SetLiteralOperand
 */
final class OperandComprehensiveTest extends ParserTestSupport {

    @Nested
    class NumberOperandTests {
        @Test
        public void parsesPositiveInteger() {
            assertParsesTo("π 42 (R)",
                    project(List.of(projected(num("42"))), rel("R")));
        }

        @Test
        public void parsesPositiveDecimal() {
            assertParsesTo("σ rate = 3.14 (Config)",
                    select(cmp(attr("rate"), ComparisonOperator.EQUAL, num("3.14")), rel("Config")));
        }

        // Note: Negative numbers are parsed as unary minus operations, not as number literals
        // e.g., "-100" is parsed as -(100), not as literal -100
        // Scientific notation (e.g., "1e10", "1.5e-10") is not supported by the lexer

        @Test
        public void parsesVeryLargeNumber() {
            assertParsesTo("σ id = 9999999999999 (R)",
                    select(cmp(attr("id"), ComparisonOperator.EQUAL, num("9999999999999")), rel("R")));
        }

        @Test
        public void parsesZero() {
            assertParsesTo("σ count ≠ 0 (R)",
                    select(cmp(attr("count"), ComparisonOperator.NOT_EQUAL, num("0")), rel("R")));
        }

        @Test
        public void parsesLeadingZeroDecimal() {
            assertParsesTo("σ rate = 0.5 (R)",
                    select(cmp(attr("rate"), ComparisonOperator.EQUAL, num("0.5")), rel("R")));
        }

        @Test
        public void parsesVerySmallDecimal() {
            // The lexer reads decimal digits without precision limit; value is preserved as a string
            assertParsesTo("σ precision = 0.000000001 (R)",
                    select(
                            cmp(attr("precision"), ComparisonOperator.EQUAL, num("0.000000001")),
                            rel("R")));
        }

        @Test
        public void parsesFloatingPointLiteralAsString() {
            // Floating-point precision is a runtime concern; the parser captures the literal exactly
            assertParsesTo("σ val = 0.1 (R)",
                    select(
                            cmp(attr("val"), ComparisonOperator.EQUAL, num("0.1")),
                            rel("R")));
        }
    }

    @Nested
    class StringOperandTests {
        @Test
        public void parsesSimpleString() {
            assertParsesTo("σ name = \"John\" (Users)",
                    select(cmp(attr("name"), ComparisonOperator.EQUAL, str("John")), rel("Users")));
        }

        @Test
        public void parsesEmptyString() {
            assertParsesTo("σ comment = \"\" (R)",
                    select(cmp(attr("comment"), ComparisonOperator.EQUAL, str("")), rel("R")));
        }

        @Test
        public void parsesStringWithNumbers() {
            assertParsesTo("σ id = \"USER123\" (R)",
                    select(cmp(attr("id"), ComparisonOperator.EQUAL, str("USER123")), rel("R")));
        }

        @Test
        public void parsesStringWithUnderscore() {
            assertParsesTo("σ code = \"test_value\" (R)",
                    select(cmp(attr("code"), ComparisonOperator.EQUAL, str("test_value")), rel("R")));
        }

        @Test
        public void parsesStringWithHyphen() {
            assertParsesTo("σ name = \"John-Doe\" (R)",
                    select(cmp(attr("name"), ComparisonOperator.EQUAL, str("John-Doe")), rel("R")));
        }

        @Test
        public void parsesStringWithDot() {
            assertParsesTo("σ domain = \"example.com\" (R)",
                    select(cmp(attr("domain"), ComparisonOperator.EQUAL, str("example.com")), rel("R")));
        }

        @Test
        public void parsesStringWithSpaces() {
            assertParsesTo("σ note = \"Hello World\" (R)",
                    select(cmp(attr("note"), ComparisonOperator.EQUAL, str("Hello World")), rel("R")));
        }

        // Note: Escape sequence handling is complex - the parser reads them as written
        // These tests would require detailed escape sequence testing per the lexer implementation

        @Test
        public void parsesLongString() {
            String longStr = "a".repeat(100);
            assertParsesTo("σ text = \"" + longStr + "\" (R)",
                    select(cmp(attr("text"), ComparisonOperator.EQUAL, str(longStr)), rel("R")));
        }

        @Test
        public void parsesUnicodeString() {
            // The lexer reads UTF-8 source; non-ASCII characters inside string literals pass through unchanged
            assertParsesTo("σ greeting = \"héllo\" (R)",
                    select(
                            cmp(attr("greeting"), ComparisonOperator.EQUAL, str("héllo")),
                            rel("R")));
        }

        @Test
        public void parsesStringResemblingReservedKeyword() {
            // Reserved keywords inside a string literal have no special meaning
            assertParsesTo("σ code = \"SELECT * FROM t\" (R)",
                    select(
                            cmp(attr("code"), ComparisonOperator.EQUAL, str("SELECT * FROM t")),
                            rel("R")));
        }
    }

    @Nested
    class BooleanOperandTests {
        @Test
        public void parsesTrue() {
            assertParsesTo("σ active = true (Users)",
                    select(cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)), rel("Users")));
        }

        @Test
        public void parsesFalse() {
            assertParsesTo("σ inactive = false (R)",
                    select(cmp(attr("inactive"), ComparisonOperator.EQUAL, bool(false)), rel("R")));
        }

        @Test
        public void uppercaseTrueIsABoolean() {
            // Keyword matching is case-insensitive; 'TRUE' is the same as 'true'
            assertParsesTo("σ flag = TRUE (R)",
                    select(
                            cmp(attr("flag"), ComparisonOperator.EQUAL, bool(true)),
                            rel("R")));
        }

        @Test
        public void titleCaseFalseIsABoolean() {
            // Keyword matching is case-insensitive; 'False' is the same as 'false'
            assertParsesTo("σ flag = False (R)",
                    select(
                            cmp(attr("flag"), ComparisonOperator.EQUAL, bool(false)),
                            rel("R")));
        }
    }

    @Nested
    class FunctionCallTests {
        @Test
        public void parsesFunctionInProjection() {
            assertParsesTo("π UPPER(name) (R)",
                    project(List.of(projected(func("UPPER",attr("name")))), rel("R")));
        }

        @Test
        public void parsesFunctionWithMultipleArgs() {
            // Assuming CONCAT or similar multi-arg functions
            assertParsesTo("π CONCAT(first_name, \" \", last_name) (R)",
                    project(
                            List.of(projected(
                            func("CONCAT",attr("first_name"), str(" "), attr("last_name"))
                    )),
                            rel("R")));
        }

        @Test
        public void parsesFunctionInSelection() {
            assertParsesTo("σ LENGTH(password) > 8 (Users)",
                    select(cmp(func("LENGTH",attr("password")), ComparisonOperator.GREATER, num("8")), rel("Users")));
        }

        @Test
        public void parsesFunctionInArithmetic() {
            // ABS(price - cost) * 1.1
            assertParsesTo("σ ABS(price - cost) * 1.1 > 50 (Products)",
                    select(
                            cmp(
                                    arith(
                                            func("ABS",arith(attr("price"), ArithmeticOperator.MINUS, attr("cost"))),
                                            ArithmeticOperator.MULTIPLY,
                                            num("1.1")
                                    ),
                                    ComparisonOperator.GREATER,
                                    num("50")
                            ),
                            rel("Products")));
        }

        @Test
        public void parsesFunctionWithAlias() {
            assertParsesTo("π UPPER(name) → upper_name (R)",
                    project(
                            List.of(
                            projected(func("UPPER",attr("name")), "upper_name")
                    ),
                            rel("R")));
        }

        @Test
        public void parsesNestedFunctionCalls() {
            assertParsesTo("π UPPER(TRIM(name)) (R)",
                    project(
                            List.of(
                            projected(func("UPPER", func("TRIM", attr("name"))))
                    ),
                            rel("R")));
        }

        @Test
        public void parsesZeroArgFunction() {
            // Functions with no arguments are valid; NOW() is a zero-arg builtin
            assertParsesTo("π NOW() (R)",
                    project(
                            List.of(
                            projected(func("NOW"))
                    ),
                            rel("R")));
        }

        @Test
        public void functionNamesAreCaseSensitive() {
            // 'upper' and 'UPPER' are different function names at the parse level
            assertParsesTo("π upper(name) (R)",
                    project(
                            List.of(
                            projected(func("upper", attr("name")))
                    ),
                            rel("R")));
        }
    }

    @Nested
    class BinaryArithmeticExpressionTests {
        @Test
        public void parsesAdditionOperand() {
            assertParsesTo("π price + tax (Products)",
                    project(List.of(projected(arith(attr("price"), ArithmeticOperator.PLUS, attr("tax")))), rel("Products")));
        }

        @Test
        public void parsesComplexArithmetic() {
            // (a + b) * c - d / e
            assertParsesTo("σ (a + b) * c - d / e = 0 (R)",
                    select(
                            cmp(
                                    arith(
                                            arith(arith(attr("a"), ArithmeticOperator.PLUS, attr("b")), ArithmeticOperator.MULTIPLY, attr("c")),
                                            ArithmeticOperator.MINUS,
                                            arith(attr("d"), ArithmeticOperator.DIVIDE, attr("e"))
                                    ),
                                    ComparisonOperator.EQUAL,
                                    num("0")
                            ),
                            rel("R")));
        }

        @Test
        public void parsesArithmeticWithFunctions() {
            // ABS(price) * ROUND(qty, 0)
            assertParsesTo("σ ABS(price) * ROUND(qty, 0) > 100 (R)",
                    select(
                            cmp(
                                    arith(
                                            func("ABS",attr("price")),
                                            ArithmeticOperator.MULTIPLY,
                                            func("ROUND",attr("qty"), num("0"))
                                    ),
                                    ComparisonOperator.GREATER,
                                    num("100")
                            ),
                            rel("R")));
        }

        @Test
        public void divisionByZeroIsNotAParseError() {
            // The parser makes no attempt to detect division by zero; it is a runtime error
            assertParsesTo("σ a / 0 = 0 (R)",
                    select(
                            cmp(
                                    arith(attr("a"), ArithmeticOperator.DIVIDE, num("0")),
                                    ComparisonOperator.EQUAL,
                                    num("0")
                            ),
                            rel("R")));
        }
    }

    @Nested
    class UnaryOperandTests {
        @Test
        public void parsesUnaryMinusOnNumber() {
            assertParsesTo("σ balance = -100 (Accounts)",
                    select(cmp(attr("balance"), ComparisonOperator.EQUAL, unary(num("100"))), rel("Accounts")));
        }

        @Test
        public void parsesUnaryMinusOnAttribute() {
            assertParsesTo("π -price (Products)",
                    project(List.of(projected(unary(attr("price")))), rel("Products")));
        }

        @Test
        public void parsesUnaryMinusOnParenthesizedExpression() {
            assertParsesTo("π -(a + b) (R)",
                    project(List.of(projected(unary(arith(attr("a"), ArithmeticOperator.PLUS, attr("b"))))), rel("R")));
        }

        @Test
        public void parsesUnaryMinusBindsTighterThanBinary() {
            assertParsesTo("π -a * b (R)",
                    project(List.of(projected(arith(unary(attr("a")), ArithmeticOperator.MULTIPLY, attr("b")))), rel("R")));
        }

        @Test
        public void parsesDoubleUnaryMinus() {
            assertParsesTo("π - -a (R)",
                    project(List.of(projected(unary(unary(attr("a"))))), rel("R")));
        }
    }

    @Nested
    class SetLiteralOperandTests {
        @Test
        public void parsesSetOfNumbers() {
            // σ status ∈ {1, 2, 3}
            assertParsesTo("σ status ∈ {1, 2, 3} (R)",
                    select(
                            elementOf(attr("status"), set(num("1"), num("2"), num("3"))),
                            rel("R")));
        }

        @Test
        public void parsesSetOfStrings() {
            // σ role ∈ {"admin", "user", "guest"}
            assertParsesTo("σ role ∈ {\"admin\", \"user\", \"guest\"} (R)",
                    select(
                            elementOf(attr("role"), set(str("admin"), str("user"), str("guest"))),
                            rel("R")));
        }

        @Test
        public void parsesSetOfSingleElement() {
            assertParsesTo("σ id ∈ {42} (R)",
                    select(
                            elementOf(attr("id"), set(num("42"))),
                            rel("R")));
        }

        @Test
        public void parsesNegatedElementOf() {
            // σ status ∉ {0, 99}
            assertParsesTo("σ status ∉ {0, 99} (R)",
                    select(
                            notElementOf(attr("status"), set(num("0"), num("99"))),
                            rel("R")));
        }

        @Test
        public void parsesIdentifierAsSetExpression() {
            // Set expression can be a relation name (parsed as AttributeOperand)
            assertParsesTo("σ status ∈ StatusSet (R)",
                    select(
                            elementOf(attr("status"), attr("StatusSet")),
                            rel("R")));
        }

        @Test
        public void parsesMixedTypeSetLiteral() {
            // Type compatibility of set elements is a semantic concern; the parser accepts any operands
            assertParsesTo("σ val ∈ {1, \"hello\"} (R)",
                    select(
                            elementOf(attr("val"), set(num("1"), str("hello"))),
                            rel("R")));
        }

        @Test
        public void parsesSetWithArithmeticExpressions() {
            // Each set element is a full operand, so arithmetic expressions are valid
            assertParsesTo("σ id ∈ {1 + 2, 10 - 3} (R)",
                    select(
                            elementOf(attr("id"), set(
                                    arith(num("1"), ArithmeticOperator.PLUS, num("2")),
                                    arith(num("10"), ArithmeticOperator.MINUS, num("3"))
                            )),
                            rel("R")));
        }

        @Test
        public void parsesEmptySetLiteral() {
            // An empty set {} is syntactically valid; semantic checks may reject it
            assertParsesTo("σ id ∈ {} (R)",
                    select(
                            elementOf(attr("id"), set()),
                            rel("R")));
        }
    }

    @Nested
    class AttributeOperandTests {
        @Test
        public void parsesSimpleAttribute() {
            assertParsesTo("π name (R)",
                    project(List.of(projected(attr("name"))), rel("R")));
        }

        @Test
        public void parsesQualifiedAttribute() {
            assertParsesTo("π Users.id (R)",
                    project(List.of(projected(attr("Users.id"))), rel("R")));
        }

        @Test
        public void parsesAttributeWithUnderscore() {
            assertParsesTo("π user_id (R)",
                    project(List.of(projected(attr("user_id"))), rel("R")));
        }

        @Test
        public void parsesAttributeWithNumbers() {
            assertParsesTo("π col123 (R)",
                    project(List.of(projected(attr("col123"))), rel("R")));
        }

        @Test
        public void attributeNamesAreCaseSensitive() {
            // Identifier lexing is case-sensitive; 'UserID' and 'userid' are distinct attributes
            assertParsesTo("π UserID (R)",
                    project(List.of(projected(attr("UserID"))), rel("R")));
        }

        @Test
        public void uppercaseKeywordIsReserved() {
            // Keyword matching is case-insensitive; 'TRUE' in projection position is the boolean literal
            assertParsesTo("π TRUE (R)",
                    project(List.of(projected(bool(true))), rel("R")));
        }

        @Test
        public void keywordSelectIsValidAsAttributeName() {
            // Keywords are word-shaped and are valid as attribute/column names in operand position
            assertParsesTo("π SELECT (R)",
                    project(List.of(projected(attr("SELECT"))), rel("R")));
        }

        @Test
        public void keywordJoinIsValidAsAttributeName() {
            // 'JOIN' is word-shaped and valid as a column name; unambiguous as an attribute here
            assertParsesTo("σ JOIN = 1 (R)",
                    select(
                            cmp(attr("JOIN"), ComparisonOperator.EQUAL, num("1")),
                            rel("R")));
        }
    }

    @Nested
    class NullPredicateTests {
        @Test
        public void parsesNullPredicate() {
            // Null is represented through NullPredicate, not a NullOperand
            assertParsesTo("σ email = ⊥ (Users)",
                    select(nullPred(attr("email"), true), rel("Users")));
        }

        // Note: NOT NULL handling depends on parser implementation
        // The parser may handle ≠ ⊥ as a comparison rather than null comparison

        @Test
        public void nullPredicateWithFunctionCallOperand() {
            // The left side of = ⊥ can be any operand, including a function call
            assertParsesTo("σ UPPER(name) = ⊥ (R)",
                    select(
                            nullPred(func("UPPER", attr("name")), true),
                            rel("R")));
        }

        @Test
        public void nullPredicateWithArithmeticOperand() {
            // Arithmetic expressions are also valid on the left side of a null predicate
            assertParsesTo("σ a + b = ⊥ (R)",
                    select(
                            nullPred(arith(attr("a"), ArithmeticOperator.PLUS, attr("b")), true),
                            rel("R")));
        }

        @Test
        public void nullPredicateWithQualifiedAttribute() {
            assertParsesTo("σ Users.email = ⊥ (R)",
                    select(
                            nullPred(attr("Users.email"), true),
                            rel("R")));
        }
    }

}

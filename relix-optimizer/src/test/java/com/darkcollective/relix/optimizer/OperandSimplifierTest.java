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

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.UnaryOperand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.darkcollective.relix.ast.Expr.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OperandSimplifier")
final class OperandSimplifierTest {

    private OptimizationContext ctx;
    private OperandSimplifier simplifier;

    @BeforeEach
    void setUp() {
        ctx = OptimizerFixtures.context();
        simplifier = new OperandSimplifier("Q", ctx);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    // =========================================================================
    // Leaf operands — unchanged
    // =========================================================================

    @Nested
    @DisplayName("Leaf operands — returned unchanged")
    class Leaves {

        @Test @DisplayName("NumberOperand is returned as-is")
        void numberUnchanged() {
            var n = num("42");
            assertThat(simplifier.simplify(n)).isSameAs(n);
        }

        @Test @DisplayName("StringOperand is returned as-is")
        void stringUnchanged() {
            var s = str("hello");
            assertThat(simplifier.simplify(s)).isSameAs(s);
        }

        @Test @DisplayName("AttributeOperand is returned as-is")
        void attributeUnchanged() {
            var a = attr("price");
            assertThat(simplifier.simplify(a)).isSameAs(a);
        }

        @Test @DisplayName("BooleanOperand is returned as-is")
        void booleanUnchanged() {
            var b = bool(true);
            assertThat(simplifier.simplify(b)).isSameAs(b);
        }

        @Test @DisplayName("no records after simplifying a leaf")
        void noRecords() {
            simplifier.simplify(num("1"));
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // EXPR-001: numeric constant folding
    // =========================================================================

    @Nested
    @DisplayName("EXPR-001 — numeric constant folding")
    class Expr001 {

        @Test @DisplayName("2 + 3 → 5")
        void addIntegers() {
            Operand result = simplifier.simplify(arith(num("2"), ArithmeticOperator.PLUS, num("3")));
            assertThat(result).isEqualTo(num("5"));
        }

        @Test @DisplayName("10 - 4 → 6")
        void subtract() {
            Operand result = simplifier.simplify(arith(num("10"), ArithmeticOperator.MINUS, num("4")));
            assertThat(result).isEqualTo(num("6"));
        }

        @Test @DisplayName("3 * 7 → 21")
        void multiply() {
            Operand result = simplifier.simplify(arith(num("3"), ArithmeticOperator.MULTIPLY, num("7")));
            assertThat(result).isEqualTo(num("21"));
        }

        @Test @DisplayName("10 / 4 → 2.5")
        void divide() {
            Operand result = simplifier.simplify(arith(num("10"), ArithmeticOperator.DIVIDE, num("4")));
            assertThat(result).isEqualTo(num("2.5"));
        }

        @Test @DisplayName("2.5 + 1.5 → 4 (integer normalisation)")
        void decimalFoldsToInteger() {
            Operand result = simplifier.simplify(arith(num("2.5"), ArithmeticOperator.PLUS, num("1.5")));
            assertThat(result).isEqualTo(num("4"));
        }

        @Test @DisplayName("records EXPR-001 code")
        void recordsCode() {
            simplifier.simplify(arith(num("2"), ArithmeticOperator.PLUS, num("3")));
            assertThat(ctx).fired(OptimizationCode.EXPR_001, 1);
        }

        @Test @DisplayName("divide by zero is NOT folded")
        void divideByZeroNotFolded() {
            var expr = arith(num("6"), ArithmeticOperator.DIVIDE, num("0"));
            Operand result = simplifier.simplify(expr);
            assertThat(result).isInstanceOf(BinaryArithmeticExpression.class);
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test @DisplayName("non-literal operand prevents folding")
        void nonLiteralPreventsfolding() {
            var expr = arith(attr("x"), ArithmeticOperator.PLUS, num("3"));
            Operand result = simplifier.simplify(expr);
            assertThat(result).isInstanceOf(BinaryArithmeticExpression.class);
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // EXPR-002: string concatenation
    // =========================================================================

    @Nested
    @DisplayName("EXPR-002 — string constant concatenation")
    class Expr002 {

        @Test @DisplayName("\"hello\" + \" world\" → \"hello world\"")
        void concatenates() {
            Operand result = simplifier.simplify(
                    arith(str("hello"), ArithmeticOperator.PLUS, str(" world")));
            assertThat(result).isEqualTo(str("hello world"));
        }

        @Test @DisplayName("empty string concatenation produces combined string")
        void emptyStrings() {
            Operand result = simplifier.simplify(
                    arith(str(""), ArithmeticOperator.PLUS, str("x")));
            assertThat(result).isEqualTo(str("x"));
        }

        @Test @DisplayName("records EXPR-002 code")
        void recordsCode() {
            simplifier.simplify(arith(str("a"), ArithmeticOperator.PLUS, str("b")));
            assertThat(ctx).fired(OptimizationCode.EXPR_002, 1);
        }

        @Test @DisplayName("a string on one side only is not a concatenation")
        void oneStringOperandIsNotConcatenation() {
            // Both operands must be string literals. Testing only a non-PLUS operator
            // leaves the second instanceof unentered, so nothing has shown that a string
            // against a number declines rather than concatenating a rendered number in.
            assertThat(simplifier.simplify(arith(str("a"), ArithmeticOperator.PLUS, num("1"))))
                    .as("string + number").isInstanceOf(BinaryArithmeticExpression.class);
            assertThat(simplifier.simplify(arith(num("1"), ArithmeticOperator.PLUS, str("a"))))
                    .as("number + string").isInstanceOf(BinaryArithmeticExpression.class);
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_002);
        }

        @Test @DisplayName("non-PLUS operator does not concatenate strings")
        void nonPlusNoConcat() {
            var expr = arith(str("a"), ArithmeticOperator.MINUS, str("b"));
            Operand result = simplifier.simplify(expr);
            assertThat(result).isInstanceOf(BinaryArithmeticExpression.class);
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // EXPR-003: additive identity
    // =========================================================================

    @Nested
    @DisplayName("EXPR-003 — additive identity elimination")
    class Expr003 {

        @Test @DisplayName("x + 0 → x")
        void xPlusZero() {
            var x = attr("price");
            Operand result = simplifier.simplify(arith(x, ArithmeticOperator.PLUS, num("0")));
            assertThat(result).isSameAs(x);
        }

        @Test @DisplayName("0 + x → x")
        void zeroPlusX() {
            var x = attr("price");
            Operand result = simplifier.simplify(arith(num("0"), ArithmeticOperator.PLUS, x));
            assertThat(result).isSameAs(x);
        }

        @Test @DisplayName("x - 0 → x")
        void xMinusZero() {
            var x = attr("price");
            Operand result = simplifier.simplify(arith(x, ArithmeticOperator.MINUS, num("0")));
            assertThat(result).isSameAs(x);
        }

        @Test @DisplayName("0 - x is NOT simplified by EXPR-003")
        void zeroMinusX() {
            var expr = arith(num("0"), ArithmeticOperator.MINUS, attr("price"));
            Operand result = simplifier.simplify(expr);
            assertThat(result).isInstanceOf(BinaryArithmeticExpression.class);
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_003);
        }

        @Test @DisplayName("0.0 + x also fires (numeric equality)")
        void zeroDecimalFires() {
            var x = attr("price");
            Operand result = simplifier.simplify(arith(num("0.0"), ArithmeticOperator.PLUS, x));
            assertThat(result).isSameAs(x);
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1);
        }

        @Test @DisplayName("records EXPR-003 code")
        void recordsCode() {
            simplifier.simplify(arith(attr("price"), ArithmeticOperator.PLUS, num("0")));
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1);
        }
    }

    // =========================================================================
    // EXPR-004: multiplicative identity
    // =========================================================================

    @Nested
    @DisplayName("EXPR-004 — multiplicative identity elimination")
    class Expr004 {

        @Test @DisplayName("x * 1 → x")
        void xTimesOne() {
            var x = attr("price");
            Operand result = simplifier.simplify(arith(x, ArithmeticOperator.MULTIPLY, num("1")));
            assertThat(result).isSameAs(x);
        }

        @Test @DisplayName("1 * x → x")
        void oneTimesX() {
            var x = attr("price");
            Operand result = simplifier.simplify(arith(num("1"), ArithmeticOperator.MULTIPLY, x));
            assertThat(result).isSameAs(x);
        }

        @Test @DisplayName("x / 1 → x")
        void xDivOne() {
            var x = attr("price");
            Operand result = simplifier.simplify(arith(x, ArithmeticOperator.DIVIDE, num("1")));
            assertThat(result).isSameAs(x);
        }

        @Test @DisplayName("1 / x is NOT simplified by EXPR-004")
        void oneDivX() {
            var expr = arith(num("1"), ArithmeticOperator.DIVIDE, attr("price"));
            Operand result = simplifier.simplify(expr);
            assertThat(result).isInstanceOf(BinaryArithmeticExpression.class);
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_004);
        }

        @Test @DisplayName("1.0 * x also fires (numeric equality)")
        void oneDecimalFires() {
            var x = attr("price");
            Operand result = simplifier.simplify(arith(num("1.0"), ArithmeticOperator.MULTIPLY, x));
            assertThat(result).isSameAs(x);
            assertThat(ctx).fired(OptimizationCode.EXPR_004, 1);
        }

        @Test @DisplayName("records EXPR-004 code")
        void recordsCode() {
            simplifier.simplify(arith(attr("price"), ArithmeticOperator.MULTIPLY, num("1")));
            assertThat(ctx).fired(OptimizationCode.EXPR_004, 1);
        }
    }

    // =========================================================================
    // EXPR-005: multiplication by zero
    // =========================================================================

    @Nested
    @DisplayName("EXPR-005 — multiplication by zero")
    class Expr005 {

        @Test @DisplayName("x * 0 → 0")
        void xTimesZero() {
            Operand result = simplifier.simplify(
                    arith(attr("price"), ArithmeticOperator.MULTIPLY, num("0")));
            assertThat(result).isEqualTo(num("0"));
        }

        @Test @DisplayName("0 * x → 0")
        void zeroTimesX() {
            Operand result = simplifier.simplify(
                    arith(num("0"), ArithmeticOperator.MULTIPLY, attr("price")));
            assertThat(result).isEqualTo(num("0"));
        }

        @Test @DisplayName("0 * 0 → 0 (fires EXPR-001 first, then returns 0)")
        void zeroTimesZeroFolded() {
            Operand result = simplifier.simplify(
                    arith(num("0"), ArithmeticOperator.MULTIPLY, num("0")));
            // EXPR-001 fires: 0 * 0 → 0
            assertThat(result).isEqualTo(num("0"));
            assertThat(ctx).fired(OptimizationCode.EXPR_001, 1);
        }

        @Test @DisplayName("records EXPR-005 code")
        void recordsCode() {
            simplifier.simplify(arith(attr("q"), ArithmeticOperator.MULTIPLY, num("0")));
            assertThat(ctx).fired(OptimizationCode.EXPR_005, 1);
        }
    }

    // =========================================================================
    // EXPR-006: double negation
    // =========================================================================

    @Nested
    @DisplayName("EXPR-006 — double negation elimination")
    class Expr006 {

        @Test @DisplayName("-(-x) → x")
        void doubleNegation() {
            var x = attr("price");
            var expr = unary(unary(x));
            Operand result = simplifier.simplify(expr);
            assertThat(result).isSameAs(x);
        }

        @Test @DisplayName("-x is not simplified (single negation)")
        void singleNegationUnchanged() {
            var x = attr("price");
            var expr = unary(x);
            Operand result = simplifier.simplify(expr);
            assertThat(result).isSameAs(expr);
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test @DisplayName("records EXPR-006 code")
        void recordsCode() {
            simplifier.simplify(unary(unary(attr("x"))));
            assertThat(ctx).fired(OptimizationCode.EXPR_006, 1);
        }

        @Test @DisplayName("triple negation: -(-(- x)) → -(x) (one elimination)")
        void tripleNegation() {
            var x = attr("price");
            // --(-x) — the inner -(x) is a single UnaryOperand;
            // the outer is --(inner), which fires EXPR-006 and yields inner
            var inner = unary(x);                  // -(x)
            var middle = unary(inner);              // -(-(x))
            var outer = unary(middle);              // -(-(-(x)))
            Operand result = simplifier.simplify(outer);
            // outer has operand middle; middle simplifies to x (EXPR-006 fires);
            // then outer becomes UnaryOperand(x) — single negation, unchanged
            assertThat(result).isInstanceOf(UnaryOperand.class);
            assertThat(((UnaryOperand) result).operand()).isSameAs(x);
            assertThat(ctx).fired(OptimizationCode.EXPR_006, 1);
        }
    }

    // =========================================================================
    // Bottom-up composition
    // =========================================================================

    @Nested
    @DisplayName("Bottom-up composition")
    class BottomUp {

        @Test @DisplayName("(2 * 3) + 0 → 6 (EXPR-001 fires twice: inner fold then outer fold)")
        void foldThenFold() {
            // Step 1: simplify left child 2*3 → 6 (EXPR-001)
            // Step 2: outer is now 6 + 0; both are NumberOperands → EXPR-001 fires again: 6
            var inner = arith(num("2"), ArithmeticOperator.MULTIPLY, num("3"));
            var outer = arith(inner, ArithmeticOperator.PLUS, num("0"));
            Operand result = simplifier.simplify(outer);
            assertThat(result).isEqualTo(num("6"));
            // EXPR-001 fires twice (2*3 and 6+0), EXPR-003 is pre-empted by EXPR-001
            assertThat(ctx).fired(OptimizationCode.EXPR_001, 2);
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_003);
        }

        @Test @DisplayName("attr * (0 + 0) → attr * 0 → 0 (fold then EXPR-005)")
        void foldThenZeroMul() {
            // Step 1: simplify right child 0+0 → 0 (EXPR-001)
            // Step 2: outer is attr * 0 → 0 (EXPR-005)
            var qty = attr("qty");
            var inner = arith(num("0"), ArithmeticOperator.PLUS, num("0"));
            var outer = arith(qty, ArithmeticOperator.MULTIPLY, inner);
            Operand result = simplifier.simplify(outer);
            assertThat(result).isEqualTo(num("0"));
            assertThat(ctx).fired(OptimizationCode.EXPR_001, 1); // 0+0→0
            assertThat(ctx).fired(OptimizationCode.EXPR_005, 1); // attr*0→0
        }

        @Test @DisplayName("1 * (2 + 3) → 5 (inner fold, then EXPR-001 on outer)")
        void identityAfterFold() {
            // Step 1: simplify right child 2+3 → 5 (EXPR-001)
            // Step 2: outer is 1 * 5; both NumberOperands → EXPR-001: 5
            var inner = arith(num("2"), ArithmeticOperator.PLUS, num("3"));
            var outer = arith(num("1"), ArithmeticOperator.MULTIPLY, inner);
            Operand result = simplifier.simplify(outer);
            assertThat(result).isEqualTo(num("5"));
            assertThat(ctx).fired(OptimizationCode.EXPR_001, 2);
        }
    }

    // =========================================================================
    // FunctionCall and SetLiteralOperand
    // =========================================================================

    @Nested
    @DisplayName("FunctionCall argument simplification")
    class FunctionCalls {

        @Test @DisplayName("arguments are simplified")
        void argsSimplified() {
            var expr = func("Round",
                    arith(num("2"), ArithmeticOperator.PLUS, num("3")),
                    num("2")
            );
            Operand result = simplifier.simplify(expr);
            assertThat(result).isInstanceOf(FunctionCall.class);
            var fc = (FunctionCall) result;
            assertThat(fc.arguments().get(0)).isEqualTo(num("5"));
        }

        @Test @DisplayName("unchanged function call returns same instance")
        void unchangedFunctionCallReturnsSameInstance() {
            var expr = func("Len",attr("name"));
            assertThat(simplifier.simplify(expr)).isSameAs(expr);
        }
    }

    @Nested
    @DisplayName("SetLiteralOperand element simplification")
    class SetLiterals {

        @Test @DisplayName("elements are simplified")
        void elemsSimplified() {
            var expr = set(
                    arith(num("1"), ArithmeticOperator.PLUS, num("2")),
                    num("5")
            );
            Operand result = simplifier.simplify(expr);
            assertThat(result).isInstanceOf(SetLiteralOperand.class);
            var sl = (SetLiteralOperand) result;
            assertThat(sl.elements().get(0)).isEqualTo(num("3"));
            assertThat(sl.elements().get(1)).isEqualTo(num("5"));
        }

        @Test @DisplayName("unchanged set literal returns same instance")
        void unchangedSetLiteralReturnsSameInstance() {
            var expr = set(num("1"), num("2"));
            assertThat(simplifier.simplify(expr)).isSameAs(expr);
        }
    }

    // =========================================================================
    // EXPR-007: constant term accumulation
    // =========================================================================

    @Nested
    @DisplayName("EXPR-007 — constant term accumulation")
    class Expr007 {

        @Test @DisplayName("(x + k1) + k2 → x + K")
        void xPlusK1PlusK2() {
            var x = attr("price");
            // (price + 2) + 3 → price + 5
            var inner = arith(x, ArithmeticOperator.PLUS, num("2"));
            var outer = arith(inner, ArithmeticOperator.PLUS, num("3"));
            Operand result = simplifier.simplify(outer);
            assertThat(result).isEqualTo(
                    arith(x, ArithmeticOperator.PLUS, num("5")));
            assertThat(ctx).fired(OptimizationCode.EXPR_007, 1);
        }

        @Test @DisplayName("(k1 + x) + k2 → x + K (commutativity)")
        void k1PlusXPlusK2() {
            var x = attr("price");
            // (2 + price) + 3 → price + 5
            var inner = arith(num("2"), ArithmeticOperator.PLUS, x);
            var outer = arith(inner, ArithmeticOperator.PLUS, num("3"));
            Operand result = simplifier.simplify(outer);
            assertThat(result).isEqualTo(
                    arith(x, ArithmeticOperator.PLUS, num("5")));
            assertThat(ctx).fired(OptimizationCode.EXPR_007, 1);
        }

        @Test @DisplayName("k1 + (x + k2) → x + K")
        void k1PlusXPlusK2Right() {
            var x = attr("price");
            // 3 + (price + 2) → price + 5
            var inner = arith(x, ArithmeticOperator.PLUS, num("2"));
            var outer = arith(num("3"), ArithmeticOperator.PLUS, inner);
            Operand result = simplifier.simplify(outer);
            assertThat(result).isEqualTo(
                    arith(x, ArithmeticOperator.PLUS, num("5")));
            assertThat(ctx).fired(OptimizationCode.EXPR_007, 1);
        }

        @Test @DisplayName("k1 + (k2 + x) → x + K")
        void k1PlusK2PlusXRight() {
            var x = attr("price");
            // 3 + (2 + price) → price + 5
            var inner = arith(num("2"), ArithmeticOperator.PLUS, x);
            var outer = arith(num("3"), ArithmeticOperator.PLUS, inner);
            Operand result = simplifier.simplify(outer);
            assertThat(result).isEqualTo(
                    arith(x, ArithmeticOperator.PLUS, num("5")));
            assertThat(ctx).fired(OptimizationCode.EXPR_007, 1);
        }

        @Test @DisplayName("(x * k1) * k2 → x * K")
        void xTimesK1TimesK2() {
            var x = attr("rate");
            // (rate * 1.1) * 1.2 → rate * 1.32
            var inner = arith(x, ArithmeticOperator.MULTIPLY, num("1.1"));
            var outer = arith(inner, ArithmeticOperator.MULTIPLY, num("1.2"));
            Operand result = simplifier.simplify(outer);
            assertThat(result).isInstanceOf(BinaryArithmeticExpression.class);
            var bae = (BinaryArithmeticExpression) result;
            assertThat(bae.left()).isSameAs(x);
            assertThat(bae.operator()).isEqualTo(ArithmeticOperator.MULTIPLY);
            assertThat(((NumberOperand) bae.right()).value()).isEqualTo("1.32");
            assertThat(ctx).fired(OptimizationCode.EXPR_007, 1);
        }

        @Test @DisplayName("MINUS not accumulated (not commutative in same way)")
        void minusNotAccumulated() {
            var x = attr("price");
            // (price - 2) - 3 → should NOT apply EXPR-007 for MINUS
            var inner = arith(x, ArithmeticOperator.MINUS, num("2"));
            var outer = arith(inner, ArithmeticOperator.MINUS, num("3"));
            Operand result = simplifier.simplify(outer);
            // EXPR-007 doesn't apply to MINUS chains
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_007);
        }

        @Test @DisplayName("does not fire when the inner operator differs from the outer")
        void doesNotFireForMismatchedInnerOperator() {
            // (price * 2) + 3 — reassociating across two different operations is not a law.
            var inner = arith(attr("price"), ArithmeticOperator.MULTIPLY, num("2"));
            var outer = arith(inner, ArithmeticOperator.PLUS, num("3"));
            simplifier.simplify(outer);
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_007);
        }

        @Test @DisplayName("does not fire for a mismatched operator on the right either")
        void doesNotFireForMismatchedInnerOperatorOnTheRight() {
            // 3 + (price * 2) — the same rejection, reached through the other arm.
            var inner = arith(attr("price"), ArithmeticOperator.MULTIPLY, num("2"));
            var outer = arith(num("3"), ArithmeticOperator.PLUS, inner);
            simplifier.simplify(outer);
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_007);
        }

        @Test @DisplayName("does not fire when the right-hand nested expression has no literal")
        void doesNotFireForNonLiteralInnerOnTheRight() {
            // 3 + (a + b) — no constant to accumulate with, on the right-child arm.
            var inner = arith(attr("a"), ArithmeticOperator.PLUS, attr("b"));
            var outer = arith(num("3"), ArithmeticOperator.PLUS, inner);
            simplifier.simplify(outer);
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_007);
        }

        @Test @DisplayName("does not fire when inner has two non-literals")
        void doesNotFireForNonLiteralInner() {
            var x = attr("a");
            var y = attr("b");
            // (a + b) + 3 — inner has no literal, EXPR-007 does not apply
            var inner = arith(x, ArithmeticOperator.PLUS, y);
            var outer = arith(inner, ArithmeticOperator.PLUS, num("3"));
            simplifier.simplify(outer);
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_007);
        }
    }

    // =========================================================================
    // EXPR-008: idempotent function call elimination
    // =========================================================================

    @Nested
    @DisplayName("EXPR-008 — idempotent function call elimination")
    class Expr008 {

        @Test @DisplayName("UCase(UCase(x)) → UCase(x)")
        void ucaseIdempotent() {
            var x   = attr("name");
            var inner = func("UCase",x);
            var outer = func("UCase",inner);
            Operand result = simplifier.simplify(outer);
            assertThat(result).isSameAs(inner);
            assertThat(ctx).fired(OptimizationCode.EXPR_008, 1);
        }

        @Test @DisplayName("LCase(LCase(x)) → LCase(x)")
        void lcaseIdempotent() {
            var x    = attr("name");
            var inner = func("LCase",x);
            var outer = func("LCase",inner);
            Operand result = simplifier.simplify(outer);
            assertThat(result).isSameAs(inner);
            assertThat(ctx).fired(OptimizationCode.EXPR_008, 1);
        }

        @Test @DisplayName("f(f(x, y)) is not collapsed — the inner call must be single-argument too")
        void innerCallMustAlsoBeSingleArgument() {
            // The rule is f(f(x)) → f(x). A two-argument inner call is a *different*
            // function shape, and collapsing it would drop an argument outright — so the
            // inner arity is its own guard, distinct from the outer one tested above.
            var inner = func("Round",attr("x"), num("2"));
            var outer = func("Round",inner);
            Operand result = simplifier.simplify(outer);

            assertThat(result).isInstanceOf(FunctionCall.class);
            assertThat(((FunctionCall) result).arguments()).hasSize(1);
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_008);
        }

        @Test @DisplayName("Abs(Abs(x)) → Abs(x)")
        void absIdempotent() {
            var x    = attr("value");
            var inner = func("Abs",x);
            var outer = func("Abs",inner);
            Operand result = simplifier.simplify(outer);
            assertThat(result).isSameAs(inner);
            assertThat(ctx).fired(OptimizationCode.EXPR_008, 1);
        }

        @Test @DisplayName("Trim(Trim(x)) → Trim(x) (already tagged IDEMPOTENT)")
        void trimIdempotent() {
            var x    = attr("text");
            var inner = func("Trim",x);
            var outer = func("Trim",inner);
            Operand result = simplifier.simplify(outer);
            assertThat(result).isSameAs(inner);
        }

        @Test @DisplayName("case-insensitive: ucase(UCASE(x)) → inner")
        void caseInsensitiveMatch() {
            var x    = attr("name");
            var inner = func("UCASE",x);
            var outer = func("ucase",inner);
            Operand result = simplifier.simplify(outer);
            assertThat(result).isSameAs(inner);
            assertThat(ctx).fired(OptimizationCode.EXPR_008, 1);
        }

        @Test @DisplayName("Len(Len(x)) is NOT simplified — Len is not idempotent")
        void lenNotIdempotent() {
            var x    = attr("name");
            var inner = func("Len",x);
            var outer = func("Len",inner);
            Operand result = simplifier.simplify(outer);
            // Len is not IDEMPOTENT — outer call stays
            assertThat(result).isInstanceOf(FunctionCall.class);
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_008);
        }

        @Test @DisplayName("UCase(LCase(x)) is NOT simplified — different function names")
        void differentFunctionsNotEliminated() {
            var x    = attr("name");
            var inner = func("LCase",x);
            var outer = func("UCase",inner);
            Operand result = simplifier.simplify(outer);
            assertThat(result).isInstanceOf(FunctionCall.class);
            assertThat(ctx).didNotFire(OptimizationCode.EXPR_008);
        }
    }

    // =========================================================================
    // Conditions in operand position (an IIf test) — issue #542
    // =========================================================================

    @Nested
    @DisplayName("ConditionOperand — the predicate an IIf tests")
    class Conditions {

        /** {@code IIf(cond, "yes", "no")}. */
        private FunctionCall iif(com.darkcollective.relix.ast.Predicate cond) {
            return func("IIf",
                    new com.darkcollective.relix.ast.ConditionOperand(cond),
                    str("yes"), str("no"));
        }

        private com.darkcollective.relix.ast.ComparisonPredicate cmp(
                Operand l, com.darkcollective.relix.ast.ComparisonOperator op, Operand r) {
            return new com.darkcollective.relix.ast.ComparisonPredicate(l, op, r);
        }

        private com.darkcollective.relix.ast.Predicate conditionOf(Operand result) {
            var call = (FunctionCall) result;
            return ((com.darkcollective.relix.ast.ConditionOperand) call.arguments().get(0)).predicate();
        }

        @Test @DisplayName("an identity multiplication inside the condition is folded (EXPR-004)")
        void operandInsideConditionSimplified() {
            var cond = cmp(arith(attr("qty"), ArithmeticOperator.MULTIPLY, num("1")),
                    com.darkcollective.relix.ast.ComparisonOperator.GREATER, num("0"));
            Operand result = simplifier.simplify(iif(cond));

            var simplified = (com.darkcollective.relix.ast.ComparisonPredicate) conditionOf(result);
            assertThat(simplified.left()).isEqualTo(attr("qty"));
            assertThat(ctx).fired(OptimizationCode.EXPR_004, 1);
        }

        @Test @DisplayName("the condition is normalised to attribute-on-left (PRED-003)")
        void predicateRuleAppliedInsideCondition() {
            var cond = cmp(num("5"), com.darkcollective.relix.ast.ComparisonOperator.GREATER, attr("age"));
            Operand result = simplifier.simplify(iif(cond));

            var simplified = (com.darkcollective.relix.ast.ComparisonPredicate) conditionOf(result);
            assertThat(simplified.left()).isEqualTo(attr("age"));
            assertThat(simplified.operator())
                    .isEqualTo(com.darkcollective.relix.ast.ComparisonOperator.LESS);
            assertThat(ctx).fired(OptimizationCode.PRED_003, 1);
        }

        @Test @DisplayName("a double NOT inside the condition is removed (PRED-002)")
        void doubleNotInsideCondition() {
            var inner = cmp(attr("age"), com.darkcollective.relix.ast.ComparisonOperator.GREATER, num("5"));
            var cond  = new com.darkcollective.relix.ast.NotPredicate(
                    new com.darkcollective.relix.ast.NotPredicate(inner));
            Operand result = simplifier.simplify(iif(cond));

            assertThat(conditionOf(result))
                    .isInstanceOf(com.darkcollective.relix.ast.ComparisonPredicate.class);
            assertThat(ctx).fired(OptimizationCode.PRED_002, 1);
        }

        @Test @DisplayName("both halves apply in order: operands fold, then the folded literals do")
        void expressionThenPredicateRules() {
            // 2 * 3 > 5  →  6 > 5  →  constant-true, so `TRUE ∧ p` collapses to p.
            var constant = cmp(arith(num("2"), ArithmeticOperator.MULTIPLY, num("3")),
                    com.darkcollective.relix.ast.ComparisonOperator.GREATER, num("5"));
            var kept = cmp(attr("age"), com.darkcollective.relix.ast.ComparisonOperator.LESS, num("30"));
            Operand result = simplifier.simplify(
                    iif(new com.darkcollective.relix.ast.AndPredicate(constant, kept)));

            assertThat(conditionOf(result)).isSameAs(kept);
            assertThat(ctx).fired(OptimizationCode.EXPR_001, 1);
            assertThat(ctx).fired(OptimizationCode.PRED_001, 1);
        }

        @Test @DisplayName("a nested IIf terminates, and every level is simplified")
        void nestedConditionsTerminate() {
            var innerCond = cmp(arith(attr("a"), ArithmeticOperator.PLUS, num("0")),
                    com.darkcollective.relix.ast.ComparisonOperator.GREATER, num("1"));
            var outerCond = cmp(iif(innerCond),
                    com.darkcollective.relix.ast.ComparisonOperator.EQUAL, str("yes"));
            Operand result = simplifier.simplify(iif(outerCond));

            var outer = (com.darkcollective.relix.ast.ComparisonPredicate) conditionOf(result);
            var inner = (com.darkcollective.relix.ast.ComparisonPredicate) conditionOf(outer.left());
            assertThat(inner.left()).isEqualTo(attr("a"));
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1);
        }

        @Test @DisplayName("a condition with nothing to simplify comes back as the same instance")
        void cleanConditionUnchanged() {
            var call = iif(cmp(attr("age"),
                    com.darkcollective.relix.ast.ComparisonOperator.GREATER, num("5")));
            assertThat(simplifier.simplify(call)).isSameAs(call);
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test @DisplayName("a fold in the SECOND operand of each form is enough to rebuild it")
        void everyPredicateFormReachedOnItsRightOperand() {
            // Each form rebuilds itself under `changedLeft || changedRight`, and a chain
            // of || is only as tested as its second arm: folding the first operand every
            // time never demonstrates that the second is looked at at all.
            var foldable = arith(attr("x"), ArithmeticOperator.PLUS, num("0"));
            List<com.darkcollective.relix.ast.Predicate> forms = List.of(
                    cmp(attr("y"), com.darkcollective.relix.ast.ComparisonOperator.GREATER, foldable),
                    new com.darkcollective.relix.ast.ElementOfPredicate(
                            attr("y"), new com.darkcollective.relix.ast.SetLiteralOperand(
                                    List.of(foldable)), false),
                    new com.darkcollective.relix.ast.PatternPredicate(attr("y"), foldable, false),
                    new com.darkcollective.relix.ast.AndPredicate(
                            cmp(attr("y"), com.darkcollective.relix.ast.ComparisonOperator.LESS, num("9")),
                            cmp(foldable, com.darkcollective.relix.ast.ComparisonOperator.GREATER, num("1"))),
                    new com.darkcollective.relix.ast.OrPredicate(
                            cmp(attr("y"), com.darkcollective.relix.ast.ComparisonOperator.LESS, num("9")),
                            cmp(foldable, com.darkcollective.relix.ast.ComparisonOperator.GREATER, num("1"))));
            for (com.darkcollective.relix.ast.Predicate form : forms) {
                ctx = OptimizerFixtures.context();
                simplifier = new OperandSimplifier("Q", ctx);
                simplifier.simplify(iif(form));
                assertThat(ctx)
                        .as("EXPR-003 should have reached %s's right operand",
                                form.getClass().getSimpleName())
                        .fired(OptimizationCode.EXPR_003, 1);
            }
        }

        @Test @DisplayName("every predicate form inside a condition is reached")
        void everyPredicateFormReached() {
            var foldable = arith(attr("x"), ArithmeticOperator.PLUS, num("0"));
            List<com.darkcollective.relix.ast.Predicate> forms = List.of(
                    new com.darkcollective.relix.ast.NullPredicate(foldable, false),
                    new com.darkcollective.relix.ast.ElementOfPredicate(foldable, attr("s"), false),
                    new com.darkcollective.relix.ast.PatternPredicate(foldable, str("%a%"), false),
                    new com.darkcollective.relix.ast.OrPredicate(
                            cmp(foldable, com.darkcollective.relix.ast.ComparisonOperator.GREATER, num("1")),
                            cmp(attr("y"), com.darkcollective.relix.ast.ComparisonOperator.LESS, num("9"))));
            for (com.darkcollective.relix.ast.Predicate form : forms) {
                ctx = OptimizerFixtures.context();
                simplifier = new OperandSimplifier("Q", ctx);
                simplifier.simplify(iif(form));
                assertThat(ctx)
                        .as("EXPR-003 should have reached %s", form.getClass().getSimpleName())
                        .fired(OptimizationCode.EXPR_003, 1);
            }
        }
    }

    // =========================================================================
    // Current-time functions are never folded or deduped (ADR-0013 slice 4)
    // =========================================================================

    @Nested
    @DisplayName("Current-time functions are never folded")
    class CurrentTimeFunctions {

        @Test @DisplayName("NOW() is returned unchanged and records nothing")
        void nowNotFolded() {
            Operand result = simplifier.simplify(func("NOW"));
            assertThat(result).isInstanceOf(FunctionCall.class);
            assertThat(((FunctionCall) result).functionName()).isEqualTo("NOW");
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test @DisplayName("CURRENT_DATE() and CURRENT_TIME() are returned unchanged")
        void currentDateTimeNotFolded() {
            for (String name : List.of("CURRENT_DATE", "CURRENT_TIME")) {
                assertThat(simplifier.simplify(func(name)))
                        .isInstanceOf(FunctionCall.class);
            }
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // Temporal literals — leaves, like the other literal forms
    // =========================================================================

    @Nested
    @DisplayName("Temporal literals — returned unchanged")
    class TemporalLeaves {

        @Test @DisplayName("every temporal literal form is a leaf")
        void temporalLiteralsUnchanged() {
            List<Operand> literals = List.of(
                    date(LocalDate.of(2026, 8, 27)),
                    time(LocalTime.of(9, 30)),
                    timestamp(Instant.parse("2026-08-27T09:30:00Z")),
                    duration(Duration.ofMinutes(15)));
            for (Operand literal : literals) {
                assertThat(simplifier.simplify(literal))
                        .as("%s should be returned as-is", literal.getClass().getSimpleName())
                        .isSameAs(literal);
            }
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // Nested constructions — NF² values built in a projection
    // =========================================================================

    @Nested
    @DisplayName("StructConstruction — field values are simplified")
    class Structs {

        @Test @DisplayName("a foldable field value is simplified in place")
        void foldsFieldValue() {
            // {total: qty * 1} → {total: qty}
            var qty = attr("qty");
            var struct = structOf(
                    new StructConstruction.Field("total", arith(qty, ArithmeticOperator.MULTIPLY, num("1"))));
            Operand result = simplifier.simplify(struct);
            assertThat(result).isInstanceOf(StructConstruction.class);
            assertThat(((StructConstruction) result).fields())
                    .containsExactly(new StructConstruction.Field("total", qty));
            assertThat(ctx).fired(OptimizationCode.EXPR_004, 1);
        }

        @Test @DisplayName("field names are preserved and order is kept")
        void preservesNamesAndOrder() {
            var struct = structOf(
                    new StructConstruction.Field("a", arith(num("2"), ArithmeticOperator.PLUS, num("3"))),
                    new StructConstruction.Field("b", attr("untouched")),
                    new StructConstruction.Field("c", arith(attr("n"), ArithmeticOperator.PLUS, num("0"))));
            var result = (StructConstruction) simplifier.simplify(struct);
            assertThat(result.fields()).extracting(StructConstruction.Field::name)
                    .containsExactly("a", "b", "c");
            assertThat(result.fields().get(0).value()).isEqualTo(num("5"));
            assertThat(result.fields().get(2).value()).isEqualTo(attr("n"));
        }

        @Test @DisplayName("a struct with nothing to fold is returned unchanged")
        void unchangedStructIsSameInstance() {
            var struct = structOf(
                    new StructConstruction.Field("a", attr("x")),
                    new StructConstruction.Field("b", num("1")));
            assertThat(simplifier.simplify(struct)).isSameAs(struct);
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test @DisplayName("a later field alone is enough to rebuild the struct")
        void foldsInALaterFieldOnly() {
            // The changed-detection loop scans every field, not just the first.
            var struct = structOf(
                    new StructConstruction.Field("a", attr("x")),
                    new StructConstruction.Field("b", arith(attr("y"), ArithmeticOperator.PLUS, num("0"))));
            Operand result = simplifier.simplify(struct);
            assertThat(result).isNotSameAs(struct);
            assertThat(((StructConstruction) result).fields().get(1).value()).isEqualTo(attr("y"));
        }

        @Test @DisplayName("simplification reaches a struct nested inside a struct")
        void reachesNestedStruct() {
            var inner = structOf(
                    new StructConstruction.Field("net", arith(attr("gross"), ArithmeticOperator.MINUS, num("0"))));
            var outer = structOf(new StructConstruction.Field("line", inner));
            var result = (StructConstruction) simplifier.simplify(outer);
            var nested = (StructConstruction) result.fields().get(0).value();
            assertThat(nested.fields().get(0).value()).isEqualTo(attr("gross"));
            assertThat(ctx).fired(OptimizationCode.EXPR_003, 1);
        }

        @Test @DisplayName("an empty struct is returned unchanged")
        void emptyStructUnchanged() {
            var struct = structOf();
            assertThat(simplifier.simplify(struct)).isSameAs(struct);
        }
    }

    @Nested
    @DisplayName("ArrayConstruction — elements are simplified")
    class Arrays {

        @Test @DisplayName("a foldable element is simplified in place")
        void foldsElement() {
            // [1 + 2, qty * 1] → [3, qty]
            var qty = attr("qty");
            var array = arrayOf(
                    arith(num("1"), ArithmeticOperator.PLUS, num("2")),
                    arith(qty, ArithmeticOperator.MULTIPLY, num("1")));
            Operand result = simplifier.simplify(array);
            assertThat(result).isInstanceOf(ArrayConstruction.class);
            assertThat(((ArrayConstruction) result).elements()).containsExactly(num("3"), qty);
        }

        @Test @DisplayName("element order is preserved")
        void preservesOrder() {
            var array = arrayOf(
                    attr("a"), arith(attr("b"), ArithmeticOperator.PLUS, num("0")), attr("c"));
            var result = (ArrayConstruction) simplifier.simplify(array);
            assertThat(result.elements()).containsExactly(attr("a"), attr("b"), attr("c"));
        }

        @Test @DisplayName("an array with nothing to fold is returned unchanged")
        void unchangedArrayIsSameInstance() {
            var array = arrayOf(attr("x"), num("1"));
            assertThat(simplifier.simplify(array)).isSameAs(array);
            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test @DisplayName("an empty array is returned unchanged")
        void emptyArrayUnchanged() {
            var array = arrayOf();
            assertThat(simplifier.simplify(array)).isSameAs(array);
        }

        @Test @DisplayName("simplification reaches an array nested inside a struct")
        void reachesArrayInsideStruct() {
            var array = arrayOf(
                    arith(attr("n"), ArithmeticOperator.MULTIPLY, num("1")));
            var struct = structOf(
                    new StructConstruction.Field("items", array));
            var result = (StructConstruction) simplifier.simplify(struct);
            var nested = (ArrayConstruction) result.fields().get(0).value();
            assertThat(nested.elements()).containsExactly(attr("n"));
        }
    }
}

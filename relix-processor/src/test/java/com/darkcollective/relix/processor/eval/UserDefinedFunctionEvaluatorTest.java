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
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import com.darkcollective.relix.symbol.table.SymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OperandEvaluator — user-defined function evaluation")
final class UserDefinedFunctionEvaluatorTest extends ProcessorTestSupport {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Registers a single user-defined function in a fresh symbol table. */
    private static SymbolTable tableWith(ScalarFunctionSymbol fn) {
        InMemorySymbolTable table = new InMemorySymbolTable();
        table.register(fn);
        return table;
    }

    /** Number literal operand (AST node, not a Value). */
    private static NumberOperand numLit(String value) {
        return AstBuilders.num(value);
    }

    /** String literal operand (AST node, not a Value). */
    private static StringOperand strLit(String value) {
        return AstBuilders.str(value);
    }

    /** Builds a FunctionCall with the given argument operands. */
    private static FunctionCall call(String name, Operand... args) {
        return AstBuilders.func(name, args);
    }

    // ── test cases ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Basic dispatch")
    class BasicDispatch {

        @Test
        @DisplayName("no-arg constructor does not support user-defined functions")
        void noArgCtorNoUdf() {
            OperandEvaluator eval = new OperandEvaluator();
            ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("double")
                    .parameter("x", ScalarType.NUMBER)
                    .returnType(ScalarType.NUMBER)
                    .body(arith(attr("x"), ArithmeticOperator.MULTIPLY, numLit("2")))
                    .build();
            // Register but evaluator has no table — should throw
            Row row = row(schema(col("val", ScalarType.NUMBER)), num(10));
            assertThatThrownBy(() -> eval.evaluate(call("double", attr("val")), row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Unknown function: double");
        }

        @Test
        @DisplayName("evaluator with symbol table resolves user-defined function")
        void withTableResolvesUdf() {
            ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("double")
                    .parameter("x", ScalarType.NUMBER)
                    .returnType(ScalarType.NUMBER)
                    .body(arith(attr("x"), ArithmeticOperator.MULTIPLY, numLit("2")))
                    .build();
            SymbolTable table = tableWith(fn);
            OperandEvaluator eval = new OperandEvaluator(table);

            Row row = row(schema(col("val", ScalarType.NUMBER)), num(10));
            Value result = eval.evaluate(call("double", attr("val")), row);

            assertThat(result).isEqualTo(new NumberValue(java.math.BigDecimal.valueOf(20)));
        }

        @Test
        @DisplayName("built-in function still takes priority over a user-defined function with the same name")
        void builtinTakesPriority() {
            // Redefine "abs" as a UDF that returns -999; built-in should still win.
            ScalarFunctionSymbol fake = ScalarFunctionSymbol.builder("abs")
                    .parameter("x", ScalarType.NUMBER)
                    .returnType(ScalarType.NUMBER)
                    .body(numLit("-999"))
                    .provenance(Provenance.USER)
                    .shadowPolicy(ShadowPolicy.PERMITTED)
                    .build();
            SymbolTable table = tableWith(fake);
            OperandEvaluator eval = new OperandEvaluator(table);

            Row row = row(schema(col("val", ScalarType.NUMBER)), num(-5));
            Value result = eval.evaluate(call("Abs", attr("val")), row);

            // Built-in Abs should return 5
            assertThat(((NumberValue) result).value())
                    .isEqualByComparingTo(java.math.BigDecimal.valueOf(5));
        }
    }

    @Nested
    @DisplayName("Argument binding")
    class ArgumentBinding {

        private OperandEvaluator eval;

        @BeforeEach
        void setUp() {
            // discount(price, pct) := price * (1 - pct / 100)
            Operand body = arith(
                    attr("price"),
                    ArithmeticOperator.MULTIPLY,
                    arith(
                            numLit("1"),
                            ArithmeticOperator.MINUS,
                            arith(attr("pct"), ArithmeticOperator.DIVIDE, numLit("100"))
                    )
            );
            ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("discount")
                    .parameter("price", ScalarType.NUMBER)
                    .parameter("pct",   ScalarType.NUMBER)
                    .returnType(ScalarType.NUMBER)
                    .body(body)
                    .build();
            eval = new OperandEvaluator(tableWith(fn));
        }

        @Test
        @DisplayName("arguments are bound left-to-right to parameter names")
        void argsBindLeftToRight() {
            Row row = row(schema(col("p", ScalarType.NUMBER), col("d", ScalarType.NUMBER)),
                    num(100), num(20));
            // discount(p, d) = 100 * (1 - 20/100) = 80
            Value result = eval.evaluate(call("discount", attr("p"), attr("d")), row);
            assertThat(((NumberValue) result).value())
                    .isEqualByComparingTo(java.math.BigDecimal.valueOf(80));
        }

        @Test
        @DisplayName("literal arguments are accepted alongside column refs")
        void literalArguments() {
            Row row = row(schema(col("price", ScalarType.NUMBER)), num(200));
            // discount(price, 10) = 200 * (1 - 10/100) = 180
            Value result = eval.evaluate(call("discount", attr("price"), numLit("10")), row);
            assertThat(((NumberValue) result).value())
                    .isEqualByComparingTo(java.math.BigDecimal.valueOf(180));
        }
    }

    @Nested
    @DisplayName("String functions")
    class StringFunctions {

        @Test
        @DisplayName("user-defined string function: greet(name) := 'Hello, ' + name")
        void stringConcatFunction() {
            Operand body = arith(strLit("Hello, "), ArithmeticOperator.PLUS, attr("name"));
            ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("greet")
                    .parameter("name", ScalarType.STRING)
                    .returnType(ScalarType.STRING)
                    .body(body)
                    .build();
            OperandEvaluator eval = new OperandEvaluator(tableWith(fn));

            Row row = row(schema(col("user", ScalarType.STRING)), str("World"));
            Value result = eval.evaluate(call("greet", attr("user")), row);

            assertThat(result).isEqualTo(new StringValue("Hello, World"));
        }
    }

    @Nested
    @DisplayName("Zero-argument functions")
    class ZeroArgFunctions {

        @Test
        @DisplayName("zero-arg UDF returns a constant literal")
        void zeroArgConstant() {
            ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("magic")
                    .returnType(ScalarType.NUMBER)
                    .body(numLit("42"))
                    .build();
            OperandEvaluator eval = new OperandEvaluator(tableWith(fn));

            Row row = row(schema("id"), num(1));
            Value result = eval.evaluate(call("magic"), row);
            assertThat(((NumberValue) result).value())
                    .isEqualByComparingTo(java.math.BigDecimal.valueOf(42));
        }
    }

    @Nested
    @DisplayName("Case insensitivity")
    class CaseInsensitivity {

        @Test
        @DisplayName("function call is case-insensitive")
        void caseInsensitive() {
            ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("Triple")
                    .parameter("x", ScalarType.NUMBER)
                    .returnType(ScalarType.NUMBER)
                    .body(arith(attr("x"), ArithmeticOperator.MULTIPLY, numLit("3")))
                    .build();
            OperandEvaluator eval = new OperandEvaluator(tableWith(fn));

            Row row = row(schema(col("val", ScalarType.NUMBER)), num(7));
            assertThat(eval.evaluate(call("triple", attr("val")), row))
                    .isEqualTo(new NumberValue(java.math.BigDecimal.valueOf(21)));
            assertThat(eval.evaluate(call("TRIPLE", attr("val")), row))
                    .isEqualTo(new NumberValue(java.math.BigDecimal.valueOf(21)));
        }
    }

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("unknown function with symbol table still throws")
        void unknownFunction() {
            OperandEvaluator eval = new OperandEvaluator(new InMemorySymbolTable());
            Row row = row(schema("x"), num(1));
            assertThatThrownBy(() -> eval.evaluate(call("nonexistent", attr("x")), row))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("Unknown function: nonexistent");
        }
    }
}

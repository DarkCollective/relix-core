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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SolveEquation;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.arith;
import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.equation;
import static com.darkcollective.relix.ast.AstBuilders.func;
import static com.darkcollective.relix.ast.AstBuilders.num;
import static com.darkcollective.relix.semantic.SemanticFixtures.FUNCTIONS;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SolveEquations — expanding defs inside a SOLVE equation")
final class SolveEquationsTest {

    private static Operand plus(Operand l, Operand r)  { return arith(l, ArithmeticOperator.PLUS, r); }
    private static Operand times(Operand l, Operand r) { return arith(l, ArithmeticOperator.MULTIPLY, r); }

    /** {@code double(x) := x * 2}, plus whatever else a test registers. */
    private static InMemorySymbolTable table(ScalarFunctionSymbol... extra) {
        InMemorySymbolTable symbols = new InMemorySymbolTable();
        symbols.register(ScalarFunctionSymbol.builder("double")
                .parameter("x", ScalarType.NUMBER).returnType(ScalarType.NUMBER)
                .body(times(attr("x"), num("2"))).build());
        for (ScalarFunctionSymbol s : extra) {
            symbols.register(s);
        }
        return symbols;
    }

    private static SolveEquation expand(SolveEquation e, InMemorySymbolTable symbols) {
        return SolveEquations.expand(e, FUNCTIONS, symbols);
    }

    @Test
    @DisplayName("expands a call on either side of an operator")
    void expandsEitherSide() {
        var left = expand(equation(attr("y"), plus(func("double", attr("a")), num("1"))), table());
        assertThat(left.right()).isEqualTo(plus(times(attr("a"), num("2")), num("1")));

        var right = expand(equation(attr("y"), plus(num("1"), func("double", attr("a")))), table());
        assertThat(right.right()).isEqualTo(plus(num("1"), times(attr("a"), num("2"))));
    }

    @Test
    @DisplayName("returns an equation with no call exactly as written")
    void leavesPlainArithmeticAlone() {
        SolveEquation plain = equation(attr("y"), plus(attr("a"), attr("b")));
        assertThat(expand(plain, table()).right()).isSameAs(plain.right());
    }

    @Test
    @DisplayName("leaves a def that reaches itself as a call")
    void stopsAtRecursion() {
        ScalarFunctionSymbol loop = ScalarFunctionSymbol.builder("loop")
                .parameter("x", ScalarType.NUMBER).returnType(ScalarType.NUMBER)
                .body(plus(func("loop", attr("x")), num("1"))).build();
        var expanded = expand(equation(attr("y"), func("loop", attr("a"))), table(loop));
        // Expanded once; the inner call to itself is left for validation to refuse.
        assertThat(expanded.right()).isEqualTo(plus(func("loop", attr("a")), num("1")));
    }

    @Test
    @DisplayName("leaves a call to a function with no body")
    void leavesABodilessSymbol() {
        ScalarFunctionSymbol opaque = ScalarFunctionSymbol.builder("opaque")
                .parameter("x", ScalarType.NUMBER).returnType(ScalarType.NUMBER).build();
        Operand call = func("opaque", attr("a"));
        assertThat(expand(equation(attr("y"), call), table(opaque)).right())
                .isSameAs(call);
    }

    @Test
    @DisplayName("leaves a def whose body names something other than its parameters")
    void leavesABodyWithAFreeName() {
        ScalarFunctionSymbol leftFree = ScalarFunctionSymbol.builder("leftFree")
                .parameter("x", ScalarType.NUMBER).returnType(ScalarType.NUMBER)
                .body(times(attr("rate"), attr("x"))).build();
        ScalarFunctionSymbol rightFree = ScalarFunctionSymbol.builder("rightFree")
                .parameter("x", ScalarType.NUMBER).returnType(ScalarType.NUMBER)
                .body(times(attr("x"), attr("rate"))).build();
        InMemorySymbolTable symbols = table(leftFree, rightFree);
        Operand l = func("leftFree", attr("a"));
        Operand r = func("rightFree", attr("a"));
        assertThat(expand(equation(attr("y"), l), symbols).right()).isSameAs(l);
        assertThat(expand(equation(attr("y"), r), symbols).right()).isSameAs(r);
    }

    @Test
    @DisplayName("expands a whole list in order")
    void expandsAList() {
        List<SolveEquation> out = SolveEquations.expandAll(List.of(
                equation(attr("y"), func("double", attr("a"))),
                equation(attr("z"), attr("b"))), FUNCTIONS, table());
        assertThat(out).extracting(SolveEquation::right)
                .containsExactly(times(attr("a"), num("2")), attr("b"));
    }
}

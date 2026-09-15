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

import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class AggregateFunctionTest {

    @Test
    void simpleBuildsAttributeArgumentWithNoYieldOrAlias() {
        var f = AggregateFunction.simple(AggregateOperator.SUM, "x");
        assertThat(f.operator()).isEqualTo(AggregateOperator.SUM);
        assertThat(f.argument()).isEqualTo(attr("x"));
        assertThat(f.yieldExpr()).isEmpty();
        assertThat(f.alias()).isEmpty();
    }

    @Test
    void aliasedBuildsAttributeArgumentWithAlias() {
        var f = AggregateFunction.aliased(AggregateOperator.SUM, "x", "total");
        assertThat(f.argument()).isEqualTo(attr("x"));
        assertThat(f.alias()).contains("total");
    }

    @Test
    void ofBuildsArbitraryExpressionArgument() {
        Operand expr = new BinaryArithmeticExpression(
                attr("price"), ArithmeticOperator.MULTIPLY, attr("qty"));
        var f = AggregateFunction.of(AggregateOperator.SUM, expr);
        assertThat(f.argument()).isSameAs(expr);
        assertThat(f.yieldExpr()).isEmpty();
    }

    @Test
    void argBuildsWithYieldAndNoAlias() {
        var f = AggregateFunction.arg(AggregateOperator.ARGMAX, "amount", "order_id");
        assertThat(f.operator()).isEqualTo(AggregateOperator.ARGMAX);
        assertThat(f.argument()).isEqualTo(attr("amount"));
        assertThat(f.yieldExpr()).contains(attr("order_id"));
        assertThat(f.alias()).isEmpty();
    }

    @Test
    void argAliasedBuildsWithYieldAndAlias() {
        var f = AggregateFunction.argAliased(AggregateOperator.ARGMIN, "amount", "order_id", "lowest");
        assertThat(f.yieldExpr()).contains(attr("order_id"));
        assertThat(f.alias()).contains("lowest");
    }

    @Test
    void simpleAndAliasedHaveNoYieldExpr() {
        assertThat(AggregateFunction.simple(AggregateOperator.SUM, "x").yieldExpr()).isEmpty();
        assertThat(AggregateFunction.aliased(AggregateOperator.SUM, "x", "t").yieldExpr()).isEmpty();
    }

    // ── outputName() ────────────────────────────────────────────────────────────

    @Test
    void outputNameUsesAliasWhenPresent() {
        assertThat(AggregateFunction.aliased(AggregateOperator.SUM, "x", "total").outputName())
                .isEqualTo("total");
    }

    @Test
    void outputNameSynthesizesOperatorUnderscoreColumnForBareAttribute() {
        assertThat(AggregateFunction.simple(AggregateOperator.SUM, "amount").outputName())
                .isEqualTo("sum_amount");
    }

    @Test
    void outputNameStripsQualifierFromAttribute() {
        assertThat(AggregateFunction.of(AggregateOperator.MAX, attr("Orders.amount")).outputName())
                .isEqualTo("max_amount");
    }

    @Test
    void outputNameUsesFunctionNameForFunctionCallArgument() {
        Operand fn = new FunctionCall("Abs", java.util.List.of(attr("delta")));
        assertThat(AggregateFunction.of(AggregateOperator.MIN, fn).outputName())
                .isEqualTo("min_Abs");
    }

    @Test
    void outputNameFallsBackToExprForComplexArgument() {
        Operand expr = new BinaryArithmeticExpression(
                attr("price"), ArithmeticOperator.MULTIPLY, attr("qty"));
        assertThat(AggregateFunction.of(AggregateOperator.SUM, expr).outputName())
                .isEqualTo("sum_expr");
    }

    // ── validation ────────────────────────────────────────────────────────────

    @Test
    void blankAliasRejected() {
        assertThatThrownBy(() -> AggregateFunction.aliased(AggregateOperator.SUM, "x", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                AggregateFunction.argAliased(AggregateOperator.ARGMAX, "a", "y", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullArgumentRejected() {
        assertThatThrownBy(() -> AggregateFunction.of(AggregateOperator.SUM, null))
                .isInstanceOf(NullPointerException.class);
    }
}

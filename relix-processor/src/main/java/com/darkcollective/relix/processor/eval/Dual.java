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

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.List;

/**
 * A value together with its partial derivatives with respect to a row's unknowns —
 * forward-mode differentiation over the arithmetic a {@code SOLVE} equation may hold.
 *
 * <p>For an equation that is affine in the unknowns, one evaluation at any point gives
 * the coefficients exactly (they are the gradient), which is how a linear system is
 * set up. The arithmetic runs at {@link #CONTEXT}'s 34 significant digits; the caller
 * rounds what it reports.
 *
 * @param value    the value at the point; never null
 * @param gradient one partial derivative per unknown, in the unknowns' order; never null
 */
record Dual(BigDecimal value, BigDecimal[] gradient) {

    /** The precision every intermediate result is carried at. */
    static final MathContext CONTEXT = MathContext.DECIMAL128;

    /**
     * Evaluates {@code expr} at {@code point}: an unknown column reads its coordinate,
     * any other column its value in {@code row}.
     *
     * @param expr     an arithmetic expression; must not be null
     * @param unknowns the unknown columns' names, lower-cased, in coordinate order
     * @param point    one value per unknown
     * @param row      supplies the known columns
     * @return the value and gradient of {@code expr} at the point
     * @throws EvaluationException on division by zero, a non-numeric known value, or a
     *         construct outside the arithmetic vocabulary
     */
    static Dual evaluate(Operand expr, List<String> unknowns, BigDecimal[] point, Row row) {
        int n = unknowns.size();
        return switch (expr) {
            case AttributeOperand a -> {
                String name = a.unqualifiedName();
                int j = unknowns.indexOf(name.toLowerCase(java.util.Locale.ROOT));
                if (j >= 0) {
                    BigDecimal[] g = zeros(n);
                    g[j] = BigDecimal.ONE;
                    yield new Dual(point[j], g);
                }
                yield constant(number(row.get(name)), n);
            }
            case NumberOperand num -> constant(new BigDecimal(num.value()), n);
            case UnaryOperand u -> {
                Dual d = evaluate(u.operand(), unknowns, point, row);
                yield new Dual(d.value.negate(), map(d.gradient, BigDecimal::negate));
            }
            case BinaryArithmeticExpression b -> {
                Dual l = evaluate(b.left(), unknowns, point, row);
                Dual r = evaluate(b.right(), unknowns, point, row);
                yield switch (b.operator()) {
                    case PLUS -> new Dual(l.value.add(r.value, CONTEXT), combine(l, r, false));
                    case MINUS -> new Dual(l.value.subtract(r.value, CONTEXT), combine(l, r, true));
                    case MULTIPLY -> {
                        BigDecimal[] g = new BigDecimal[n];
                        for (int i = 0; i < n; i++) {
                            g[i] = l.value.multiply(r.gradient[i], CONTEXT)
                                    .add(r.value.multiply(l.gradient[i], CONTEXT), CONTEXT);
                        }
                        yield new Dual(l.value.multiply(r.value, CONTEXT), g);
                    }
                    case DIVIDE -> {
                        if (r.value.signum() == 0) {
                            throw new EvaluationException(
                                    "SOLVE: division by zero while solving the equations");
                        }
                        BigDecimal square = r.value.multiply(r.value, CONTEXT);
                        BigDecimal[] g = new BigDecimal[n];
                        for (int i = 0; i < n; i++) {
                            g[i] = l.gradient[i].multiply(r.value, CONTEXT)
                                    .subtract(l.value.multiply(r.gradient[i], CONTEXT), CONTEXT)
                                    .divide(square, CONTEXT);
                        }
                        yield new Dual(l.value.divide(r.value, CONTEXT), g);
                    }
                };
            }
            default -> throw new EvaluationException(
                    "SOLVE: equation contains a construct that cannot be solved");
        };
    }

    /** The numeric value of a known column, refusing anything else. */
    static BigDecimal number(Value value) {
        if (!(value instanceof NumberValue nv)) {
            throw new EvaluationException(
                    "SOLVE: equation requires NUMBER operands, got " + value.type());
        }
        return nv.value();
    }

    private static Dual constant(BigDecimal value, int n) {
        return new Dual(value, zeros(n));
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] z = new BigDecimal[n];
        Arrays.fill(z, BigDecimal.ZERO);
        return z;
    }

    /** The gradient of {@code l + r}, or of {@code l − r} when {@code subtract}. */
    private static BigDecimal[] combine(Dual l, Dual r, boolean subtract) {
        BigDecimal[] g = new BigDecimal[l.gradient.length];
        for (int i = 0; i < g.length; i++) {
            g[i] = subtract ? l.gradient[i].subtract(r.gradient[i], CONTEXT)
                    : l.gradient[i].add(r.gradient[i], CONTEXT);
        }
        return g;
    }

    private static BigDecimal[] map(BigDecimal[] in, java.util.function.UnaryOperator<BigDecimal> f) {
        BigDecimal[] out = new BigDecimal[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = f.apply(in[i]);
        }
        return out;
    }
}

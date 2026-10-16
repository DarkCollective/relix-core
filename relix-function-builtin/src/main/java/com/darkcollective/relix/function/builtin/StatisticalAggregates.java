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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.Accumulator;
import com.darkcollective.relix.function.AggregateFunction;
import com.darkcollective.relix.function.AggregateProperty;
import com.darkcollective.relix.function.AggregateSignature;
import com.darkcollective.relix.function.Arity;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.darkcollective.relix.function.builtin.Arguments.reject;

/**
 * The statistical aggregates: the variance and standard-deviation family, and the
 * percentiles with {@code MEDIAN}.
 *
 * <h2>Exact, then rounded once</h2>
 * The variance family keeps the count, the sum and the sum of squares as exact decimals,
 * so the subtraction that loses precision in floating point — a running sum of squares
 * minus a squared sum — loses nothing here, and no streaming update such as Welford's is
 * needed to protect it. The quotient is rounded half-up to ten places, as {@code AVG}'s
 * is, and a standard deviation is the square root of the exact variance, rounded the
 * same way.
 *
 * <h2>Which of these a backend is offered</h2>
 * None, for the reason {@code AVG} is not: every backend computes these in its own
 * precision — PostgreSQL to sixteen or more digits, its {@code percentile_cont} in
 * binary floating point — so a pushed call would answer with different digits.
 *
 * <h2>Percentiles are not decomposable</h2>
 * A percentile needs every value of its group at once. Its accumulator cannot be merged,
 * which is how a rule that would compute it in parts is refused.
 */
final class StatisticalAggregates {

    /** Ten places, rounding half-up: as {@code AVG}. */
    private static final int SCALE = 10;

    private StatisticalAggregates() {
    }

    static List<AggregateFunction> all() {
        return List.of(
                // STDDEV and VARIANCE are the sample forms, as in PostgreSQL.
                variance("STDDEV", true, true),
                variance("STDDEV_SAMP", true, true),
                variance("STDDEV_POP", false, true),
                variance("VARIANCE", true, false),
                variance("VAR_SAMP", true, false),
                variance("VAR_POP", false, false),
                percentile("PERCENTILE_CONT", ScalarType.NUMBER, context -> new Continuous("PERCENTILE_CONT"),
                        types -> ScalarType.NUMBER),
                percentile("PERCENTILE_DISC", ScalarType.ANY, context -> new Discrete(context.valueOrder()),
                        types -> types.isEmpty() ? ScalarType.ANY : types.get(0)),
                new Definition(new AggregateSignature("MEDIAN", List.of(param("value")), Arity.exactly(1),
                        ScalarType.NUMBER, Set.of(AggregateProperty.ORDER_INSENSITIVE), true,
                        AggregateSignature.AGGREGATE, Optional.empty()),
                        context -> new Continuous("MEDIAN"), types -> ScalarType.NUMBER));
    }

    private static AggregateFunction variance(String name, boolean sample, boolean root) {
        return new Definition(new AggregateSignature(name, List.of(param("value")), Arity.exactly(1),
                ScalarType.NUMBER, Set.of(AggregateProperty.ORDER_INSENSITIVE), true,
                AggregateSignature.AGGREGATE, Optional.empty()),
                context -> new Variance(name, sample, root), types -> ScalarType.NUMBER);
    }

    /** A percentile: the value, then the fraction, as {@code PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY x)} reads. */
    private static AggregateFunction percentile(String name, Type returns,
                                                Function<FunctionContext, Accumulator> reduction,
                                                Function<List<Type>, Type> returnTypes) {
        return new Definition(new AggregateSignature(name, List.of(param("value"), param("fraction")),
                Arity.exactly(2), returns, Set.of(AggregateProperty.ORDER_INSENSITIVE), true,
                AggregateSignature.AGGREGATE, Optional.empty()), reduction, returnTypes);
    }

    private static ParameterDefinition param(String name) {
        return new ParameterDefinition(name, ScalarType.ANY);
    }

    private record Definition(AggregateSignature signature, Function<FunctionContext, Accumulator> reduction,
                              Function<List<Type>, Type> returnTypes) implements AggregateFunction {

        @Override
        public Accumulator accumulator(FunctionContext context) {
            return reduction.apply(context);
        }

        @Override
        public Type returnTypeFor(List<Type> argumentTypes) {
            return returnTypes.apply(argumentTypes);
        }
    }

    /** The number a statistical aggregate reads, refusing anything else. */
    private static BigDecimal numeric(Value value, String function) {
        if (!(value instanceof NumberValue n)) {
            throw reject(function + ": expected NUMBER, got " + value.type());
        }
        return n.value();
    }

    /**
     * The variance or standard deviation, of the sample (dividing by {@code n - 1}) or
     * the population (by {@code n}). NULL for no values, and for a sample of one.
     */
    private static final class Variance implements Accumulator {

        private final String name;
        private final boolean sample;
        private final boolean root;
        private long n;
        private BigDecimal sum = BigDecimal.ZERO;
        private BigDecimal sumOfSquares = BigDecimal.ZERO;

        Variance(String name, boolean sample, boolean root) {
            this.name = name;
            this.sample = sample;
            this.root = root;
        }

        @Override
        public void accumulate(List<Value> arguments) {
            BigDecimal x = numeric(arguments.get(0), name);
            n++;
            sum = sum.add(x);
            sumOfSquares = sumOfSquares.add(x.multiply(x));
        }

        @Override
        public void merge(Accumulator other) {
            Variance that = (Variance) other;
            n += that.n;
            sum = sum.add(that.sum);
            sumOfSquares = sumOfSquares.add(that.sumOfSquares);
        }

        @Override
        public Value finish() {
            long divisor = sample ? n - 1 : n;
            if (divisor <= 0) {
                return NullValue.INSTANCE;
            }
            BigDecimal count = BigDecimal.valueOf(n);
            // n·Σx² − (Σx)² is n² times the population variance, exactly.
            BigDecimal spread = count.multiply(sumOfSquares).subtract(sum.multiply(sum));
            BigDecimal variance = spread.divide(count.multiply(BigDecimal.valueOf(divisor)),
                    MathContext.DECIMAL128);
            BigDecimal result = root ? variance.sqrt(MathContext.DECIMAL128) : variance;
            return new NumberValue(result.setScale(SCALE, RoundingMode.HALF_UP));
        }
    }

    /**
     * The fraction a percentile is asked for, the same in every row: refused outside
     * [0, 1], or where it changes from row to row.
     */
    private abstract static class Percentile implements Accumulator {

        private final String name;
        private BigDecimal fraction;

        Percentile(String name) {
            this.name = name;
        }

        BigDecimal fraction() {
            return fraction;
        }

        void readFraction(List<Value> arguments) {
            if (arguments.size() < 2) {
                fraction = new BigDecimal("0.5");
                return;
            }
            BigDecimal p = numeric(arguments.get(1), name);
            if (p.signum() < 0 || p.compareTo(BigDecimal.ONE) > 0) {
                throw reject(name + ": percentile value " + p.toPlainString() + " is not between 0 and 1");
            }
            if (fraction != null && fraction.compareTo(p) != 0) {
                throw reject(name + ": the fraction must be the same for every row of a group");
            }
            fraction = p;
        }
    }

    /**
     * {@code PERCENTILE_CONT} and {@code MEDIAN}: the value at the fraction's position
     * among the sorted values, interpolated linearly between the two either side of it,
     * as PostgreSQL's is. NULL for no values.
     */
    private static final class Continuous extends Percentile {

        private final String name;
        private final List<BigDecimal> values = new ArrayList<>();

        Continuous(String name) {
            super(name);
            this.name = name;
        }

        @Override
        public void accumulate(List<Value> arguments) {
            readFraction(arguments);
            values.add(numeric(arguments.get(0), name));
        }

        @Override
        public Value finish() {
            if (values.isEmpty()) {
                return NullValue.INSTANCE;
            }
            values.sort(Comparator.naturalOrder());
            BigDecimal position = fraction().multiply(BigDecimal.valueOf(values.size() - 1L));
            int below = position.setScale(0, RoundingMode.FLOOR).intValueExact();
            BigDecimal low = values.get(below);
            if (below + 1 >= values.size()) {
                return new NumberValue(low);
            }
            BigDecimal weight = position.subtract(BigDecimal.valueOf(below));
            return new NumberValue(low.add(values.get(below + 1).subtract(low).multiply(weight)));
        }
    }

    /**
     * {@code PERCENTILE_DISC}: the first value, in the engine's order, whose place among
     * the sorted values reaches the fraction — always one of the values, of any type that
     * orders. NULL for no values.
     */
    private static final class Discrete extends Percentile {

        private final Comparator<Value> order;
        private final List<Value> values = new ArrayList<>();

        Discrete(Comparator<Value> order) {
            super("PERCENTILE_DISC");
            this.order = order;
        }

        @Override
        public void accumulate(List<Value> arguments) {
            readFraction(arguments);
            values.add(arguments.get(0));
        }

        @Override
        public Value finish() {
            if (values.isEmpty()) {
                return NullValue.INSTANCE;
            }
            values.sort(order);
            int index = fraction().multiply(BigDecimal.valueOf(values.size()))
                    .setScale(0, RoundingMode.CEILING).intValueExact() - 1;
            return values.get(Math.max(index, 0));
        }
    }
}

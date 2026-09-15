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
import com.darkcollective.relix.function.PushdownSpelling;
import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The eight built-in aggregates: the five SQL reducers, the one that gathers, and the
 * two that yield a companion value from the row that won.
 *
 * <p>Each is one definition — form, NULL rule, optimizer contracts, backend spelling and
 * the reduction itself — where the engine used to hold the form in one place and an
 * inline switch arm in another.
 *
 * <h2>NULL handling</h2>
 * <p>{@linkplain AggregateSignature#skipsNulls() Declared, not implemented here}: the
 * engine drops a NULL row before the accumulator sees it, so {@code COUNT(expr)} counts
 * values rather than rows and {@code SUM}/{@code AVG}/{@code MIN}/{@code MAX} yield NULL
 * over a group with nothing to reduce. Two definitions opt out and say why: {@code COLLECT}
 * keeps NULLs because they are part of what the group contained, and {@code ARGMAX} /
 * {@code ARGMIN} skip a NULL <em>rank</em> while yielding whatever the winning row holds.
 *
 * <h2>Precision</h2>
 * <p>{@code SUM} adds the decimals it was given and keeps every digit. {@code AVG}
 * divides, which does not always terminate, so it rounds half-up at ten decimal places —
 * the same figure the engine has always produced.
 */
final class AggregateFunctions {

    /** Ten places, rounding half-up: the division {@code AVG} cannot do exactly. */
    private static final int AVERAGE_SCALE = 10;

    private AggregateFunctions() {
    }

    static List<AggregateFunction> all() {
        return List.of(
                // COUNT is the one reducer with an answer for the empty group: no values
                // is a fact about the group, not a missing one.
                reducer("COUNT", ScalarType.NUMBER, value("value"),
                        Set.of(AggregateProperty.ORDER_INSENSITIVE), sqlCall("COUNT"),
                        context -> new Count()),

                reducer("SUM", ScalarType.NUMBER, value("value"),
                        Set.of(AggregateProperty.ORDER_INSENSITIVE), sqlCall("SUM"),
                        context -> new Sum()),

                // AVG supplies no SQL spelling, and is the one reducer that cannot.
                // It is the only aggregate that *divides*, so it has division's problem
                // exactly: a relix average is exact decimal to ten places, rounded
                // half-up, and no backend's AVG carries that scale — H2 answers
                // 152.33333333333334 where the engine answers 152.3333333333. The other
                // four are exact integer arithmetic or a selection, and push.
                //
                // Declining costs a γ containing an AVG its whole fold, which is the
                // ordinary price of a decline: a slower plan, never a wrong answer. A
                // caller wanting the backend's own average can ask for SUM and COUNT and
                // divide, and then the rounding is theirs to choose.
                reducer("AVG", ScalarType.NUMBER, value("value"),
                        Set.of(AggregateProperty.ORDER_INSENSITIVE),
                        (target, arguments) -> Optional.empty(),
                        context -> new Average()),

                // MIN and MAX are the duplicate-insensitive pair: they do not care how
                // many times they saw a value, which is what lets a δ below them go.
                extremum("MIN", sqlCall("MIN"), true),
                extremum("MAX", sqlCall("MAX"), false),

                new Collect(),
                new ArgExtremum("ARGMAX", false),
                new ArgExtremum("ARGMIN", true));
    }

    // ── Definitions ───────────────────────────────────────────────────────────

    /** A NULL-skipping one-argument reduction with a fixed result type. */
    private static AggregateFunction reducer(String name, Type returns,
                                             ParameterDefinition parameter,
                                             Set<AggregateProperty> properties,
                                             PushdownSpelling pushdown,
                                             Function<FunctionContext, Accumulator> reduction) {
        return new Reducer(signature(name, returns, List.of(parameter), properties, true),
                pushdown, reduction, argumentTypes -> returns);
    }

    /**
     * {@code MIN} / {@code MAX}: the extreme value, ranked by the engine's own order so
     * that the smallest here is the first row of an ascending sort there. The result is
     * whatever the argument was, so the declared type is {@code ANY} and the real answer
     * comes from the argument at the call site.
     */
    private static AggregateFunction extremum(String name, PushdownSpelling pushdown,
                                              boolean smallest) {
        AggregateSignature signature = signature(name, ScalarType.ANY,
                List.of(value("value")),
                Set.of(AggregateProperty.DUPLICATE_INSENSITIVE,
                        AggregateProperty.ORDER_INSENSITIVE),
                true);
        return new Reducer(signature, pushdown,
                context -> new Extremum(context.valueOrder(), smallest),
                AggregateFunctions::firstOrAny);
    }

    /** The first argument's type, which is what a one-value reduction returns. */
    private static Type firstOrAny(List<Type> argumentTypes) {
        return argumentTypes.isEmpty() ? ScalarType.ANY : argumentTypes.get(0);
    }

    /** An ordinary reduction: signature, spelling, result-type rule, and the reduction. */
    private record Reducer(AggregateSignature signature, PushdownSpelling pushdown,
                           Function<FunctionContext, Accumulator> reduction,
                           Function<List<Type>, Type> returnTypes)
            implements AggregateFunction {

        @Override
        public Accumulator accumulator(FunctionContext context) {
            return reduction.apply(context);
        }

        @Override
        public Type returnTypeFor(List<Type> argumentTypes) {
            return returnTypes.apply(argumentTypes);
        }
    }

    /**
     * {@code COLLECT} — the inverse of {@code UNNEST}: a group's values, in row order,
     * as one array.
     *
     * <p>It keeps NULLs, matching {@code array_agg} and {@code $push}, and it is neither
     * duplicate- nor order-insensitive: both are visible in what it produces. It has no
     * backend spelling, because it produces a nested value and no dialect relix targets
     * agrees on how one comes back.
     */
    private static final class Collect implements AggregateFunction {

        @Override
        public AggregateSignature signature() {
            return AggregateFunctions.signature("COLLECT", new ArrayType(ScalarType.ANY),
                    List.of(value("value")), Set.<AggregateProperty>of(), false);
        }

        @Override
        public Type returnTypeFor(List<Type> argumentTypes) {
            return new ArrayType(argumentTypes.isEmpty() ? ScalarType.ANY : argumentTypes.get(0));
        }

        @Override
        public Accumulator accumulator(FunctionContext context) {
            return new Gather();
        }
    }

    /**
     * {@code ARGMAX(rank, yield)} / {@code ARGMIN} — the yield expression's value from
     * the row whose rank is extreme. This is the "row with the maximum" that SQL needs a
     * window function or a self-join for, which is also why it has no spelling.
     *
     * <p>Ties keep the first row in input order, so it is order-sensitive, and a second
     * copy of a winning value can change nothing but is not <em>declared</em>
     * duplicate-insensitive: the yield is a second reduction the optimizer would have to
     * reason about, and being conservative here costs a rewrite, not a result.
     */
    private static final class ArgExtremum implements AggregateFunction {

        private final String name;
        private final boolean smallest;

        ArgExtremum(String name, boolean smallest) {
            this.name = name;
            this.smallest = smallest;
        }

        @Override
        public AggregateSignature signature() {
            // Arity 1 to 2: the parser admits a one-argument form, where the ranking
            // expression is also what is yielded.
            return new AggregateSignature(name,
                    List.of(value("rank"), value("yield")), Arity.between(1, 2),
                    ScalarType.ANY, Set.of(), false,
                    AggregateSignature.AGGREGATE, Optional.empty());
        }

        @Override
        public Type returnTypeFor(List<Type> argumentTypes) {
            // The yield when there is one, else the rank — which is what a one-argument
            // call reduces to.
            return argumentTypes.isEmpty() ? ScalarType.ANY
                    : argumentTypes.get(argumentTypes.size() - 1);
        }

        @Override
        public Accumulator accumulator(FunctionContext context) {
            return new PickByRank(context.valueOrder(), smallest);
        }
    }

    // ── Accumulators ──────────────────────────────────────────────────────────

    /** Counts the rows it is given; NULL rows never arrive. */
    private static final class Count implements Accumulator {

        private long seen;

        @Override
        public void accumulate(List<Value> arguments) {
            seen++;
        }

        @Override
        public void merge(Accumulator other) {
            seen += ((Count) other).seen;
        }

        @Override
        public Value finish() {
            return new NumberValue(BigDecimal.valueOf(seen));
        }
    }

    /**
     * Adds the numbers it is given, ignoring a value that is not one.
     *
     * <p>Ignoring rather than rejecting is deliberate and long-standing: a column typed
     * {@code ANY} from a schema-less source can carry anything, and a sum that fails on
     * the one row holding a stray string is less useful than one that adds the rest.
     */
    private static final class Sum implements Accumulator {

        private BigDecimal total = BigDecimal.ZERO;
        private boolean any;

        @Override
        public void accumulate(List<Value> arguments) {
            if (arguments.get(0) instanceof NumberValue n) {
                total = total.add(n.value());
                any = true;
            }
        }

        @Override
        public void merge(Accumulator other) {
            Sum that = (Sum) other;
            total = total.add(that.total);
            any |= that.any;
        }

        @Override
        public Value finish() {
            return any ? new NumberValue(total) : NullValue.INSTANCE;
        }
    }

    /** Holds the sum and the count, which is what makes an average decomposable. */
    private static final class Average implements Accumulator {

        private BigDecimal total = BigDecimal.ZERO;
        private int counted;

        @Override
        public void accumulate(List<Value> arguments) {
            if (arguments.get(0) instanceof NumberValue n) {
                total = total.add(n.value());
                counted++;
            }
        }

        @Override
        public void merge(Accumulator other) {
            Average that = (Average) other;
            total = total.add(that.total);
            counted += that.counted;
        }

        @Override
        public Value finish() {
            return counted == 0 ? NullValue.INSTANCE
                    : new NumberValue(total.divide(BigDecimal.valueOf(counted),
                            AVERAGE_SCALE, RoundingMode.HALF_UP));
        }
    }

    /** Keeps the extreme value seen, by the engine's order. */
    private static final class Extremum implements Accumulator {

        private final Comparator<Value> order;
        private final boolean smallest;
        private Value best;

        Extremum(Comparator<Value> order, boolean smallest) {
            this.order = order;
            this.smallest = smallest;
        }

        @Override
        public void accumulate(List<Value> arguments) {
            offer(arguments.get(0));
        }

        @Override
        public void merge(Accumulator other) {
            Value theirs = ((Extremum) other).best;
            if (theirs != null) {
                offer(theirs);
            }
        }

        private void offer(Value candidate) {
            int comparison = best == null ? 0 : order.compare(candidate, best);
            if (best == null || (smallest ? comparison < 0 : comparison > 0)) {
                best = candidate;
            }
        }

        @Override
        public Value finish() {
            return best == null ? NullValue.INSTANCE : best;
        }
    }

    /** Gathers every value, in the order the rows arrived. */
    private static final class Gather implements Accumulator {

        private final List<Value> gathered = new ArrayList<>();

        @Override
        public void accumulate(List<Value> arguments) {
            gathered.add(arguments.get(0));
        }

        @Override
        public void merge(Accumulator other) {
            gathered.addAll(((Gather) other).gathered);
        }

        @Override
        public Value finish() {
            return new ArrayValue(List.copyOf(gathered));
        }
    }

    /**
     * Keeps the yield of the row with the extreme rank; a NULL rank is not a candidate,
     * and a tie keeps the row that arrived first.
     */
    private static final class PickByRank implements Accumulator {

        private final Comparator<Value> order;
        private final boolean smallest;
        private Value bestRank;
        private Value bestYield;

        PickByRank(Comparator<Value> order, boolean smallest) {
            this.order = order;
            this.smallest = smallest;
        }

        @Override
        public void accumulate(List<Value> arguments) {
            Value rank = arguments.get(0);
            if (rank.isNull()) {
                return;
            }
            // A one-argument call yields its own ranking value.
            offer(rank, arguments.get(arguments.size() - 1));
        }

        @Override
        public void merge(Accumulator other) {
            PickByRank that = (PickByRank) other;
            if (that.bestRank != null) {
                offer(that.bestRank, that.bestYield);
            }
        }

        private void offer(Value rank, Value yielded) {
            int comparison = bestRank == null ? 0 : order.compare(rank, bestRank);
            if (bestRank == null || (smallest ? comparison < 0 : comparison > 0)) {
                bestRank = rank;
                bestYield = yielded;
            }
        }

        @Override
        public Value finish() {
            // The rank is the sentinel, not the yield: a winning row may perfectly well
            // hold a NULL, and that NULL is the answer.
            return bestRank == null ? NullValue.INSTANCE : bestYield;
        }
    }

    // ── Shorthands ────────────────────────────────────────────────────────────

    private static AggregateSignature signature(String name, Type returns,
                                                List<ParameterDefinition> parameters,
                                                Set<AggregateProperty> properties,
                                                boolean skipsNulls) {
        return new AggregateSignature(name, parameters, Arity.exactly(parameters.size()),
                returns, properties, skipsNulls, AggregateSignature.AGGREGATE,
                Optional.empty());
    }

    /** A one-value parameter — what most reductions take per row. */
    private static ParameterDefinition value(String name) {
        return new ParameterDefinition(name, ScalarType.ANY);
    }

    /** {@code NAME(arg)} in any SQL dialect — the shape all five reducers share. */
    private static PushdownSpelling sqlCall(String name) {
        return (target, arguments) -> target.isFamily(PushdownTarget.SQL)
                && arguments.size() == 1
                ? Optional.of(name + "(" + arguments.get(0) + ")")
                : Optional.empty();
    }
}

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
import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.CONTEXT;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The eight aggregates: what they declare, and what they reduce a group to.
 *
 * <p>The declarations matter as much as the reductions here. {@code skipsNulls} is what
 * makes the engine drop a row before the accumulator sees it, and
 * {@code DUPLICATE_INSENSITIVE} is what licenses the optimizer to remove a δ below a γ —
 * so a wrong flag changes results elsewhere, not here.
 */
@DisplayName("The built-in aggregates")
final class AggregateFunctionsTest {

    private static final Map<String, AggregateFunction> BY_NAME =
            new BuiltinFunctionLibrary().aggregateFunctions().stream()
                    .collect(Collectors.toMap(
                            fn -> fn.signature().canonicalName(), Function.identity()));

    private static AggregateFunction aggregate(String name) {
        AggregateFunction fn = BY_NAME.get(name.toLowerCase(Locale.ROOT));
        assertThat(fn).as("no aggregate named '%s'", name).isNotNull();
        return fn;
    }

    /** Reduces the given rows, each row being one argument list. */
    private static Value reduce(String name, List<List<Value>> rows) {
        Accumulator accumulator = aggregate(name).accumulator(CONTEXT);
        rows.forEach(accumulator::accumulate);
        return accumulator.finish();
    }

    /** Reduces one value per row — the shape all but two of them take. */
    private static Value reduce(String name, Value... values) {
        return reduce(name, java.util.Arrays.stream(values).map(List::of).toList());
    }

    private static String display(Value value) {
        return value.asDisplayString();
    }

    @Nested
    @DisplayName("What they declare")
    final class Declarations {

        @Test
        @DisplayName("the SQL reducers skip NULLs; COLLECT and the ARG pair do not")
        void nullHandling() {
            assertThat(List.of("COUNT", "SUM", "AVG", "MIN", "MAX"))
                    .allSatisfy(name ->
                            assertThat(aggregate(name).signature().skipsNulls()).isTrue());

            // COLLECT keeps NULLs because they are part of what the group held; the ARG
            // pair skips a NULL rank itself, and must still see a row whose yield is NULL.
            assertThat(List.of("COLLECT", "ARGMAX", "ARGMIN"))
                    .allSatisfy(name ->
                            assertThat(aggregate(name).signature().skipsNulls()).isFalse());
        }

        @Test
        @DisplayName("only MIN and MAX are duplicate-insensitive — what DIST-002 reads")
        void duplicateInsensitivity() {
            List<String> insensitive = BY_NAME.values().stream()
                    .filter(fn -> fn.signature().has(AggregateProperty.DUPLICATE_INSENSITIVE))
                    .map(fn -> fn.signature().name())
                    .sorted()
                    .toList();

            assertThat(insensitive).containsExactly("MAX", "MIN");
        }

        @Test
        @DisplayName("COLLECT and the ARG pair are order-sensitive; the reducers are not")
        void orderSensitivity() {
            List<String> insensitive = BY_NAME.values().stream()
                    .filter(fn -> fn.signature().has(AggregateProperty.ORDER_INSENSITIVE))
                    .map(fn -> fn.signature().name())
                    .sorted()
                    .toList();

            // COLLECT gathers in row order and the ARG pair resolves a tie to the first
            // row, so all three would notice a reordering of the input.
            assertThat(insensitive).containsExactly("AVG", "COUNT", "MAX", "MIN", "SUM");
        }

        @Test
        @DisplayName("the five reducers spell themselves for SQL; the other three decline")
        void spellings() {
            assertThat(aggregate("SUM").pushdown()
                    .render(PushdownTarget.sql("postgres"), List.of("amount")))
                    .hasValue("SUM(amount)");
            assertThat(aggregate("COUNT").pushdown()
                    .render(PushdownTarget.sql(""), List.of("(price * qty)")))
                    .hasValue("COUNT((price * qty))");

            // No dialect relix targets agrees on how a nested value comes back, and the
            // ARG pair is what SQL needs a window function for.
            assertThat(List.of("COLLECT", "ARGMAX", "ARGMIN")).allSatisfy(name ->
                    assertThat(aggregate(name).pushdown()
                            .render(PushdownTarget.sql("postgres"), List.of("x"))).isEmpty());
        }

        @Test
        @DisplayName("a spelling declines MongoDB and a wrong argument count")
        void spellingsDecline() {
            assertThat(aggregate("SUM").pushdown()
                    .render(PushdownTarget.mongo(), List.of("amount"))).isEmpty();
            assertThat(aggregate("SUM").pushdown()
                    .render(PushdownTarget.sql(""), List.of("a", "b"))).isEmpty();
        }

        @Test
        @DisplayName("result types follow the call: fixed, the argument's, or an array of it")
        void resultTypes() {
            assertThat(aggregate("SUM").returnTypeFor(List.of(ScalarType.NUMBER)))
                    .isEqualTo(ScalarType.NUMBER);
            assertThat(aggregate("MIN").returnTypeFor(List.of(ScalarType.TIMESTAMP)))
                    .isEqualTo(ScalarType.TIMESTAMP);
            assertThat(aggregate("COLLECT").returnTypeFor(List.of(ScalarType.STRING)))
                    .isEqualTo(new ArrayType(ScalarType.STRING));
            // ARGMAX(rank, yield) is typed by what it yields, not by what it ranks.
            assertThat(aggregate("ARGMAX")
                    .returnTypeFor(List.of(ScalarType.NUMBER, ScalarType.STRING)))
                    .isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("a call with no argument types at all still has an answer")
        void resultTypesWithoutArguments() {
            // Inference asks before it has typed anything when the argument is
            // unresolvable; ANY is the same answer it gives everywhere else.
            List<Type> none = List.of();
            assertThat(aggregate("MIN").returnTypeFor(none)).isEqualTo(ScalarType.ANY);
            assertThat(aggregate("ARGMAX").returnTypeFor(none)).isEqualTo(ScalarType.ANY);
            assertThat(aggregate("COLLECT").returnTypeFor(none))
                    .isEqualTo(new ArrayType(ScalarType.ANY));
        }

        @Test
        @DisplayName("the ARG pair accepts one argument as well as two")
        void argArity() {
            assertThat(aggregate("ARGMAX").signature().arity().accepts(1)).isTrue();
            assertThat(aggregate("ARGMAX").signature().arity().accepts(2)).isTrue();
            assertThat(aggregate("ARGMAX").signature().arity().accepts(3)).isFalse();
        }
    }

    @Nested
    @DisplayName("What they reduce a group to")
    final class Reductions {

        @Test
        @DisplayName("COUNT counts the rows it is given, and answers zero for none")
        void count() {
            assertThat(display(reduce("COUNT", n("1"), n("2"), n("3")))).isEqualTo("3");
            // No values is a fact about the group, not a missing one — unlike SUM.
            assertThat(display(reduce("COUNT"))).isEqualTo("0");
        }

        @Test
        @DisplayName("SUM adds exactly, and is NULL over a group with nothing to add")
        void sum() {
            assertThat(display(reduce("SUM", n("0.1"), n("0.2")))).isEqualTo("0.3");
            assertThat(reduce("SUM").isNull()).isTrue();
        }

        @Test
        @DisplayName("SUM ignores a value that is not a number rather than failing")
        void sumIgnoresNonNumbers() {
            // A column typed ANY can carry anything; a sum that fails on one stray
            // string is less useful than one that adds the rest.
            assertThat(display(reduce("SUM", n("1"), s("oops"), n("2")))).isEqualTo("3");
            assertThat(reduce("SUM", s("oops")).isNull()).isTrue();
        }

        @Test
        @DisplayName("AVG divides, rounding half-up at ten places")
        void average() {
            assertThat(display(reduce("AVG", n("1"), n("2")))).isEqualTo("1.5");
            // 5/3 does not terminate, which is why AVG rounds rather than dividing exactly.
            assertThat(display(reduce("AVG", n("1"), n("2"), n("2"))))
                    .isEqualTo("1.6666666667");
            assertThat(reduce("AVG").isNull()).isTrue();
        }

        @Test
        @DisplayName("MIN and MAX rank by the context's order, and are NULL over nothing")
        void extremes() {
            assertThat(display(reduce("MIN", n("3"), n("1"), n("2")))).isEqualTo("1");
            assertThat(display(reduce("MAX", n("3"), n("1"), n("2")))).isEqualTo("3");
            assertThat(display(reduce("MIN", s("pear"), s("apple")))).isEqualTo("apple");
            assertThat(reduce("MIN").isNull()).isTrue();
            assertThat(reduce("MAX").isNull()).isTrue();
        }

        @Test
        @DisplayName("COLLECT gathers in row order, NULLs included")
        void collect() {
            assertThat(display(reduce("COLLECT", s("a"), NullValue.INSTANCE, s("b"))))
                    .isEqualTo("[a, NULL, b]");
            assertThat(display(reduce("COLLECT"))).isEqualTo("[]");
        }

        @Test
        @DisplayName("ARGMAX yields the companion value of the winning row; ties keep the first")
        void argmax() {
            List<List<Value>> rows = List.of(
                    List.of(n("10"), s("a")),
                    List.of(n("30"), s("b")),
                    List.of(n("30"), s("c")),
                    List.of(n("20"), s("d")));

            assertThat(display(reduce("ARGMAX", rows))).isEqualTo("b");
            assertThat(display(reduce("ARGMIN", rows))).isEqualTo("a");
        }

        @Test
        @DisplayName("ARGMAX skips a NULL rank and is NULL when every rank is one")
        void argmaxNullRanks() {
            assertThat(display(reduce("ARGMAX", List.of(
                    List.of(NullValue.INSTANCE, s("skipped")),
                    List.of(n("1"), s("kept"))))))
                    .isEqualTo("kept");
            assertThat(reduce("ARGMAX", List.<List<Value>>of(
                    List.of(NullValue.INSTANCE, s("x")))).isNull()).isTrue();
            assertThat(reduce("ARGMAX", List.<List<Value>>of()).isNull()).isTrue();
        }

        @Test
        @DisplayName("a one-argument ARGMAX yields what it ranked by")
        void argmaxSingleArgument() {
            assertThat(display(reduce("ARGMAX", List.of(
                    List.of(n("10")), List.of(n("40")), List.of(n("20"))))))
                    .isEqualTo("40");
        }
    }

    @Nested
    @DisplayName("Combining partial groups")
    final class Merging {

        /** Reduces {@code left} and {@code right} separately, then folds one into the other. */
        private Value merged(String name, List<List<Value>> left, List<List<Value>> right) {
            Accumulator a = aggregate(name).accumulator(CONTEXT);
            Accumulator b = aggregate(name).accumulator(CONTEXT);
            left.forEach(a::accumulate);
            right.forEach(b::accumulate);
            a.merge(b);
            return a.finish();
        }

        private List<List<Value>> single(Value... values) {
            return java.util.Arrays.stream(values).map(List::of).toList();
        }

        @Test
        @DisplayName("all eight give the same answer in parts as in one pass")
        void everyAggregateIsDecomposable() {
            assertThat(display(merged("COUNT", single(n("1")), single(n("2"), n("3")))))
                    .isEqualTo("3");
            assertThat(display(merged("SUM", single(n("1")), single(n("2")))))
                    .isEqualTo("3");
            assertThat(display(merged("AVG", single(n("1")), single(n("2"), n("3")))))
                    .isEqualTo("2");
            assertThat(display(merged("MIN", single(n("5")), single(n("2")))))
                    .isEqualTo("2");
            assertThat(display(merged("MAX", single(n("5")), single(n("9")))))
                    .isEqualTo("9");
            assertThat(display(merged("COLLECT", single(s("a")), single(s("b")))))
                    .isEqualTo("[a, b]");
            assertThat(display(merged("ARGMAX",
                    List.of(List.of(n("1"), s("low"))),
                    List.of(List.of(n("9"), s("high"))))))
                    .isEqualTo("high");
            assertThat(display(merged("ARGMIN",
                    List.of(List.of(n("1"), s("low"))),
                    List.of(List.of(n("9"), s("high"))))))
                    .isEqualTo("low");
        }

        @Test
        @DisplayName("folding in a part that saw nothing changes nothing")
        void mergingAnEmptyPart() {
            assertThat(display(merged("MIN", single(n("4")), List.of()))).isEqualTo("4");
            assertThat(display(merged("MAX", List.of(), single(n("4"))))).isEqualTo("4");
            assertThat(display(merged("ARGMAX",
                    List.of(List.of(n("1"), s("only"))), List.of())))
                    .isEqualTo("only");
            assertThat(merged("SUM", List.of(), List.of()).isNull()).isTrue();
        }
    }

    @Nested
    @DisplayName("Ranking through the context")
    final class Ranking {

        @Test
        @DisplayName("MIN reduces by the order the context supplies, not one of its own")
        void readsTheContextOrder() {
            // A context whose order is reversed makes MIN report the largest value —
            // the point being that the aggregate holds no comparator itself, so a
            // library's reduction and the engine's sort cannot disagree.
            Accumulator reversed = aggregate("MIN").accumulator(
                    com.darkcollective.relix.function.FunctionContext.of(
                            java.time.Clock.systemUTC(),
                            java.util.Comparator.<Value, String>comparing(
                                    v -> ((StringValue) v).value()).reversed()));
            reversed.accumulate(List.of(s("apple")));
            reversed.accumulate(List.of(s("pear")));

            assertThat(Optional.of(display(reversed.finish()))).hasValue("pear");
        }
    }
}

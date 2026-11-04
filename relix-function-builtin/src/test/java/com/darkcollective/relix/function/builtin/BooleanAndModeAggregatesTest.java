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
import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BOOL_AND and BOOL_OR (#295), and MODE (#296), through the library's own SPI. */
@DisplayName("The boolean aggregates and MODE")
final class BooleanAndModeAggregatesTest {

    private static final Value T = BooleanValue.TRUE;
    private static final Value F = BooleanValue.FALSE;

    private static AggregateFunction aggregate(String name) {
        return new BuiltinFunctionLibrary().aggregateFunctions().stream()
                .filter(f -> f.signature().name().equals(name)).findFirst().orElseThrow();
    }

    /** The result of {@code name} over {@code values}, fed in the order given. */
    private static Value fold(String name, Value... values) {
        Accumulator accumulator = aggregate(name).accumulator(BuiltinCalls.CONTEXT);
        for (Value value : values) {
            accumulator.accumulate(List.of(value));
        }
        return accumulator.finish();
    }

    private static Value n(String x) {
        return new NumberValue(new BigDecimal(x));
    }

    private static Value s(String x) {
        return new StringValue(x);
    }

    @Nested
    @DisplayName("BOOL_AND and BOOL_OR")
    final class Booleans {

        @Test
        @DisplayName("all true, mixed and all false")
        void folds() {
            assertThat(fold("BOOL_AND", T, T, T)).isEqualTo(T);
            assertThat(fold("BOOL_AND", T, F, T)).isEqualTo(F);
            assertThat(fold("BOOL_OR", F, T, F)).isEqualTo(T);
            assertThat(fold("BOOL_OR", F, F)).isEqualTo(F);
        }

        @Test
        @DisplayName("a group with no value, empty or all NULL, is NULL: the engine skips the NULLs")
        void emptyIsNull() {
            assertThat(fold("BOOL_AND")).isEqualTo(NullValue.INSTANCE);
            assertThat(fold("BOOL_OR")).isEqualTo(NullValue.INSTANCE);
            assertThat(aggregate("BOOL_AND").signature().skipsNulls()).isTrue();
            assertThat(aggregate("BOOL_OR").signature().skipsNulls()).isTrue();
        }

        @Test
        @DisplayName("computed in parts and merged, the answer is the whole's")
        void merges() {
            Accumulator left = aggregate("BOOL_AND").accumulator(BuiltinCalls.CONTEXT);
            Accumulator right = aggregate("BOOL_AND").accumulator(BuiltinCalls.CONTEXT);
            Accumulator empty = aggregate("BOOL_AND").accumulator(BuiltinCalls.CONTEXT);
            left.accumulate(List.of(T));
            right.accumulate(List.of(F));
            left.merge(empty);
            assertThat(left.finish()).isEqualTo(T);
            left.merge(right);
            assertThat(left.finish()).isEqualTo(F);
        }

        @Test
        @DisplayName("a value that is not a BOOLEAN is refused, naming the aggregate")
        void refusesNonBoolean() {
            assertThatThrownBy(() -> fold("BOOL_OR", n("1")))
                    .hasMessage("BOOL_OR: expected BOOLEAN, got NUMBER");
        }

        @Test
        @DisplayName("PostgreSQL spells them bool_and and bool_or; no other dialect is offered one")
        void spellings() {
            assertThat(aggregate("BOOL_AND").pushdown().render(new PushdownTarget("sql", "postgres"), List.of("b")))
                    .contains("BOOL_AND(b)");
            assertThat(aggregate("BOOL_OR").pushdown().render(new PushdownTarget("sql", "postgres"), List.of("b")))
                    .contains("BOOL_OR(b)");
            for (String dialect : List.of("mysql", "sqlite", "duckdb", "sqlserver", "oracle", "db2", "")) {
                assertThat(aggregate("BOOL_AND").pushdown().render(new PushdownTarget("sql", dialect), List.of("b")))
                        .as(dialect).isEqualTo(Optional.empty());
            }
            assertThat(aggregate("BOOL_OR").pushdown().render(new PushdownTarget("mongo", ""), List.of("b")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("MODE")
    final class Mode {

        @Test
        @DisplayName("the one most frequent value")
        void singleMode() {
            assertThat(fold("MODE", n("3"), n("1"), n("3"), n("2"))).isEqualTo(n("3"));
        }

        @Test
        @DisplayName("1 and 1.0 are one value, under the engine's equality")
        void engineEquality() {
            assertThat(((NumberValue) fold("MODE", n("2"), n("1"), n("1.0"))).value()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("of tied values the first to arrive wins: the engine sorts the group first")
        void tieGoesToFirstArrived() {
            // Ascending, as the engine feeds MODE(x): the smallest.
            assertThat(fold("MODE", n("1"), n("1"), n("2"), n("2"), n("3"))).isEqualTo(n("1"));
            // Descending, as it feeds MODE(x ORDER BY x DESC): the largest.
            assertThat(fold("MODE", n("3"), n("2"), n("2"), n("1"), n("1"))).isEqualTo(n("2"));
        }

        @Test
        @DisplayName("strings and dates")
        void strings() {
            assertThat(fold("MODE", s("b"), s("a"), s("b"))).isEqualTo(s("b"));
            Value day = new DateValue(LocalDate.of(2026, 3, 1));
            assertThat(fold("MODE", day, new DateValue(LocalDate.of(2026, 3, 2)), day)).isEqualTo(day);
        }

        @Test
        @DisplayName("NULL for no values; NULLs are skipped before it, and its type is its argument's")
        void emptyIsNull() {
            assertThat(fold("MODE")).isEqualTo(NullValue.INSTANCE);
            assertThat(aggregate("MODE").signature().skipsNulls()).isTrue();
            assertThat(aggregate("MODE").returnTypeFor(List.of(com.darkcollective.relix.symbol.ScalarType.DATE)))
                    .isEqualTo(com.darkcollective.relix.symbol.ScalarType.DATE);
        }

        @Test
        @DisplayName("it cannot be merged, so it is never computed in parts, and it has no backend spelling")
        void notDecomposable() {
            Accumulator a = aggregate("MODE").accumulator(BuiltinCalls.CONTEXT);
            assertThatThrownBy(() -> a.merge(aggregate("MODE").accumulator(BuiltinCalls.CONTEXT)))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(aggregate("MODE").pushdown().render(new PushdownTarget("sql", "postgres"), List.of("x")))
                    .isEmpty();
        }
    }
}

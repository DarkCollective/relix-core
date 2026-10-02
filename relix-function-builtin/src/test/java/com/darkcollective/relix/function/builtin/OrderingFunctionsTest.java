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

import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.function;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.number;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The ordering built-ins — LEAST / GREATEST")
final class OrderingFunctionsTest {

    private static final Value NULL = NullValue.INSTANCE;

    private static Value date(String iso) {
        return new DateValue(LocalDate.parse(iso));
    }

    @Nested
    @DisplayName("Picking the extreme")
    final class Extremes {

        @Test
        @DisplayName("LEAST and GREATEST pick the smallest and largest NUMBER")
        void numbers() {
            assertThat(number(call("LEAST", n("5"), n("2"), n("8")))).isEqualByComparingTo("2");
            assertThat(number(call("GREATEST", n("5"), n("2"), n("8")))).isEqualByComparingTo("8");
        }

        @Test
        @DisplayName("they take two arguments or more")
        void variadic() {
            assertThat(number(call("LEAST", n("3"), n("1")))).isEqualByComparingTo("1");
            assertThat(number(call("GREATEST", n("1"), n("2"), n("3"), n("4"), n("5"))))
                    .isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("they order STRINGs by the engine's own order")
        void strings() {
            assertThat(text(call("LEAST", s("pear"), s("apple"), s("fig")))).isEqualTo("apple");
            assertThat(text(call("GREATEST", s("pear"), s("apple"), s("fig")))).isEqualTo("pear");
        }

        @Test
        @DisplayName("they order the temporal types too")
        void temporal() {
            Value earlier = date("2026-01-01");
            Value later = date("2026-12-31");
            assertThat(call("LEAST", later, earlier)).isEqualTo(earlier);
            assertThat(call("GREATEST", later, earlier)).isEqualTo(later);
        }

        @Test
        @DisplayName("ties keep an equal value, so the result is that value either way")
        void ties() {
            assertThat(number(call("LEAST", n("4"), n("4")))).isEqualByComparingTo("4");
            assertThat(number(call("GREATEST", n("4"), n("4")))).isEqualByComparingTo("4");
        }
    }

    @Nested
    @DisplayName("NULL and incomparable arguments")
    final class EdgeCases {

        @Test
        @DisplayName("any NULL argument propagates to a NULL result")
        void nullPropagates() {
            assertThat(call("LEAST", n("1"), NULL, n("3")).isNull()).isTrue();
            assertThat(call("GREATEST", NULL, n("3")).isNull()).isTrue();
            assertThat(call("LEAST", NULL, NULL).isNull()).isTrue();
        }

        @Test
        @DisplayName("arguments of kinds that cannot be ranked are an error")
        void incomparable() {
            assertThatThrownBy(() -> call("LEAST", n("1"), s("a")))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> call("GREATEST", s("a"), n("1")))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Signature")
    final class Signature {

        @Test
        @DisplayName("the result type follows the arguments — one shared type, or ANY")
        void returnType() {
            assertThat(function("LEAST").returnTypeFor(List.of(ScalarType.NUMBER, ScalarType.NUMBER)))
                    .isEqualTo(ScalarType.NUMBER);
            assertThat(function("GREATEST").returnTypeFor(List.of(ScalarType.STRING, ScalarType.STRING)))
                    .isEqualTo(ScalarType.STRING);
            assertThat(function("LEAST").returnTypeFor(List.of(ScalarType.NUMBER, ScalarType.STRING)))
                    .isEqualTo(ScalarType.ANY);
        }

        @Test
        @DisplayName("folds on NULL-propagating dialects (MySQL/Db2), not on the skip-NULL ones")
        void pushesDownToPropagatingDialectsOnly() {
            assertThat(function("LEAST").pushdown()
                    .render(PushdownTarget.sql("mysql"), List.of("a", "b")))
                    .contains("LEAST(a, b)");
            assertThat(function("GREATEST").pushdown()
                    .render(PushdownTarget.sql("db2"), List.of("a", "b")))
                    .contains("GREATEST(a, b)");
            assertThat(function("LEAST").pushdown()
                    .render(PushdownTarget.sql("postgres"), List.of("a", "b")))
                    .isEmpty();
        }
    }
}

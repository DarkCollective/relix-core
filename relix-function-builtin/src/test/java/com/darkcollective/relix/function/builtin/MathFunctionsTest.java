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

import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.number;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("The numeric built-ins")
final class MathFunctionsTest {

    private static final Value NULL = NullValue.INSTANCE;

    private static double approx(String name, String argument) {
        return number(call(name, n(argument))).doubleValue();
    }

    @Nested
    @DisplayName("Rounding")
    final class Rounding {

        @Test
        @DisplayName("Int floors, Fix truncates, Ceil raises — and they differ on negatives")
        void directions() {
            assertThat(number(call("Int", n("-2.5")))).isEqualTo("-3");
            assertThat(number(call("Fix", n("-2.5")))).isEqualTo("-2");
            assertThat(number(call("Ceil", n("-2.5")))).isEqualTo("-2");
            assertThat(number(call("Int", n("2.5")))).isEqualTo("2");
            assertThat(number(call("Fix", n("2.5")))).isEqualTo("2");
            assertThat(number(call("Ceil", n("2.1")))).isEqualTo("3");
        }

        @Test
        @DisplayName("Round goes to the nearest, halves up, at the requested precision")
        void round() {
            assertThat(number(call("Round", n("3.14159"), n("2")))).isEqualTo("3.14");
            assertThat(number(call("Round", n("2.5")))).isEqualTo("3");
            assertThat(number(call("Round", n("2.4")))).isEqualTo("2");
        }
    }

    @Nested
    @DisplayName("Magnitude and sign")
    final class MagnitudeAndSign {

        @Test
        @DisplayName("Abs and Sgn read a number's size and direction")
        void absAndSgn() {
            assertThat(number(call("Abs", n("-7")))).isEqualTo("7");
            assertThat(number(call("Abs", n("7")))).isEqualTo("7");
            assertThat(number(call("Sgn", n("-3")))).isEqualTo("-1");
            assertThat(number(call("Sgn", n("0")))).isEqualTo("0");
            assertThat(number(call("Sgn", n("3")))).isEqualTo("1");
        }

        @Test
        @DisplayName("exact operations keep the precision they were given")
        void exactness() {
            assertThat(number(call("Abs", n("-1.500")))).isEqualTo(new BigDecimal("1.500"));
        }
    }

    @Nested
    @DisplayName("Transcendentals")
    final class Transcendentals {

        @Test
        @DisplayName("the familiar identities hold")
        void identities() {
            assertThat(approx("Sqr", "9")).isEqualTo(3.0, within(1e-12));
            assertThat(approx("Log", "1")).isEqualTo(0.0, within(1e-12));
            assertThat(approx("Exp", "0")).isEqualTo(1.0, within(1e-12));
            assertThat(approx("Sin", "0")).isEqualTo(0.0, within(1e-12));
            assertThat(approx("Cos", "0")).isEqualTo(1.0, within(1e-12));
            assertThat(approx("Tan", "0")).isEqualTo(0.0, within(1e-12));
            assertThat(approx("Atn", "0")).isEqualTo(0.0, within(1e-12));
            assertThat(number(call("Power", n("2"), n("10"))).doubleValue())
                    .isEqualTo(1024.0, within(1e-9));
        }

        @Test
        @DisplayName("an argument outside the domain is rejected, not returned as NaN")
        void domains() {
            assertThatThrownBy(() -> call("Sqr", n("-1")))
                    .hasMessage("Sqr: argument must be non-negative");
            assertThatThrownBy(() -> call("Log", n("0")))
                    .hasMessage("Log: argument must be positive");
            assertThatThrownBy(() -> call("Log", n("-1")))
                    .hasMessage("Log: argument must be positive");
        }
    }

    @Nested
    @DisplayName("Rand")
    final class Rand {

        @Test
        @DisplayName("lands in [0, 1) and declares no optimizer contract")
        void rand() {
            for (int i = 0; i < 50; i++) {
                assertThat(number(call("Rand")).doubleValue()).isBetween(0.0, 1.0);
            }
            assertThat(BuiltinCalls.function("Rand").signature().properties()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Missing and mistyped values")
    final class Edges {

        @Test
        @DisplayName("a NULL input yields NULL, not an error")
        void nullIn() {
            for (String name : new String[]{"Abs", "Int", "Fix", "Round", "Ceil", "Sgn",
                    "Sqr", "Log", "Exp", "Sin", "Cos", "Tan", "Atn"}) {
                assertThat(call(name, NULL).isNull()).as(name).isTrue();
            }
            assertThat(call("Power", NULL, n("2")).isNull()).isTrue();
            assertThat(call("Power", n("2"), NULL).isNull()).isTrue();
        }

        @Test
        @DisplayName("a value of the wrong type is reported as such")
        void wrongType() {
            assertThatThrownBy(() -> call("Abs", s("x")))
                    .hasMessage("Abs: expected NUMBER argument, got STRING");
            assertThatThrownBy(() -> call("Sqr", s("x")))
                    .hasMessage("Sqr: expected NUMBER argument, got STRING");
            assertThatThrownBy(() -> call("Round", n("1"), s("x")))
                    .hasMessage("Round: expected NUMBER argument, got STRING");
            assertThatThrownBy(() -> call("Power", s("x"), n("2")))
                    .hasMessage("Power: expected NUMBER argument, got STRING");
        }
    }
}

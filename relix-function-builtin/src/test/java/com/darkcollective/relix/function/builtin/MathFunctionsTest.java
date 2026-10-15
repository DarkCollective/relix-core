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
    @DisplayName("Modulo")
    final class Modulo {

        @Test
        @DisplayName("truncates towards zero, so the remainder's sign follows the dividend")
        void sign() {
            assertThat(number(call("Mod", n("7"), n("3")))).isEqualTo("1");
            assertThat(number(call("Mod", n("-7"), n("3")))).isEqualTo("-1");
            assertThat(number(call("Mod", n("7"), n("-3")))).isEqualTo("1");
            assertThat(number(call("Mod", n("-7"), n("-3")))).isEqualTo("-1");
        }

        @Test
        @DisplayName("keeps the exact decimal remainder")
        void decimal() {
            assertThat(number(call("Mod", n("5.5"), n("2")))).isEqualTo("1.5");
        }

        @Test
        @DisplayName("rejects a zero divisor rather than returning NULL or NaN")
        void zeroDivisor() {
            assertThatThrownBy(() -> call("Mod", n("7"), n("0")))
                    .hasMessage("Mod: divisor must be non-zero");
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
            assertThatThrownBy(() -> call("Log10", n("0")))
                    .hasMessage("Log10: argument must be positive");
            assertThatThrownBy(() -> call("Asin", n("2")))
                    .hasMessage("Asin: argument must be between -1 and 1");
            assertThatThrownBy(() -> call("Acos", n("-1.5")))
                    .hasMessage("Acos: argument must be between -1 and 1");
        }
    }

    @Nested
    @DisplayName("Logarithms to a base")
    final class Bases {

        @Test
        @DisplayName("Log10 and Log(b, y) land exactly on an exact power")
        void exactPowers() {
            assertThat(number(call("Log10", n("1000")))).isEqualByComparingTo("3");
            assertThat(number(call("Log", n("10"), n("1000")))).isEqualByComparingTo("3");
            assertThat(number(call("Log", n("2"), n("8")))).isEqualByComparingTo("3");
            assertThat(number(call("Log", n("2"), n("0.5")))).isEqualByComparingTo("-1");
        }

        @Test
        @DisplayName("Log(b, y) between powers is ln y / ln b")
        void between() {
            assertThat(number(call("Log", n("2"), n("10"))).doubleValue())
                    .isEqualTo(Math.log(10) / Math.log(2), within(1e-15));
            assertThat(number(call("Log", n("1")))).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a non-positive argument or a base of 1 is rejected, and NULL passes through")
        void domain() {
            assertThatThrownBy(() -> call("Log", n("0"), n("8")))
                    .hasMessage("Log: arguments must be positive");
            assertThatThrownBy(() -> call("Log", n("2"), n("-8")))
                    .hasMessage("Log: arguments must be positive");
            assertThatThrownBy(() -> call("Log", n("1"), n("8")))
                    .hasMessage("Log: base must not be 1");
            assertThat(call("Log", NULL, n("8")).isNull()).isTrue();
            assertThat(call("Log", n("2"), NULL).isNull()).isTrue();
        }
    }

    @Nested
    @DisplayName("Inverse trigonometry and angle units")
    final class Angles {

        @Test
        @DisplayName("Asin, Acos and Atan2 answer as PostgreSQL does, at the boundaries too")
        void inverses() {
            assertThat(approx("Asin", "1")).isEqualTo(Math.PI / 2, within(1e-15));
            assertThat(approx("Asin", "-1")).isEqualTo(-Math.PI / 2, within(1e-15));
            assertThat(approx("Acos", "-1")).isEqualTo(Math.PI, within(1e-15));
            assertThat(approx("Acos", "0.5")).isEqualTo(Math.PI / 3, within(1e-15));
            assertThat(number(call("Atan2", n("1"), n("-1"))).doubleValue())
                    .isEqualTo(3 * Math.PI / 4, within(1e-15));
            assertThat(number(call("Atan2", n("-1"), n("0"))).doubleValue())
                    .isEqualTo(-Math.PI / 2, within(1e-15));
            assertThat(number(call("Atan2", n("0"), n("0")))).isEqualByComparingTo("0");
            assertThat(call("Atan2", NULL, n("1")).isNull()).isTrue();
            assertThat(call("Atan2", n("1"), NULL).isNull()).isTrue();
        }

        @Test
        @DisplayName("Degrees and Radians convert either way")
        void units() {
            assertThat(approx("Degrees", "3.141592653589793")).isEqualTo(180.0);
            assertThat(approx("Radians", "180")).isEqualTo(Math.PI);
            assertThat(approx("Degrees", "-1")).isEqualTo(-57.29577951308232, within(1e-12));
        }
    }

    @Nested
    @DisplayName("Pi")
    final class Pi {

        @Test
        @DisplayName("is π as the nearest double, and a pure deterministic constant")
        void pi() {
            assertThat(number(call("Pi")).doubleValue()).isEqualTo(Math.PI);

            var properties = BuiltinCalls.function("Pi").signature().properties();
            assertThat(properties).contains(
                    com.darkcollective.relix.symbol.FunctionProperty.PURE,
                    com.darkcollective.relix.symbol.FunctionProperty.DETERMINISTIC);
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
                    "Sqr", "Log", "Exp", "Sin", "Cos", "Tan", "Atn",
                    "Log10", "Asin", "Acos", "Degrees", "Radians"}) {
                assertThat(call(name, NULL).isNull()).as(name).isTrue();
            }
            assertThat(call("Power", NULL, n("2")).isNull()).isTrue();
            assertThat(call("Power", n("2"), NULL).isNull()).isTrue();
            assertThat(call("Mod", NULL, n("2")).isNull()).isTrue();
            assertThat(call("Mod", n("2"), NULL).isNull()).isTrue();
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
            assertThatThrownBy(() -> call("Mod", s("x"), n("2")))
                    .hasMessage("Mod: expected NUMBER argument, got STRING");
        }
    }
}

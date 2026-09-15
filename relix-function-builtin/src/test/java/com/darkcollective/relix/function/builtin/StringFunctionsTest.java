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

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.number;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The string built-ins")
final class StringFunctionsTest {

    private static final Value NULL = NullValue.INSTANCE;

    @Nested
    @DisplayName("Length and case")
    final class LengthAndCase {

        @Test
        @DisplayName("Len counts characters")
        void len() {
            assertThat(number(call("Len", s("hello")))).isEqualTo("5");
            assertThat(number(call("Len", s("")))).isEqualTo("0");
        }

        @Test
        @DisplayName("UCase and LCase map case without a locale surprise")
        void case_() {
            assertThat(text(call("UCase", s("aBc")))).isEqualTo("ABC");
            assertThat(text(call("LCase", s("AbC")))).isEqualTo("abc");
        }

        @Test
        @DisplayName("the three trims each take their own side")
        void trims() {
            assertThat(text(call("Trim", s("  hi  ")))).isEqualTo("hi");
            assertThat(text(call("LTrim", s("  hi  ")))).isEqualTo("hi  ");
            assertThat(text(call("RTrim", s("  hi  ")))).isEqualTo("  hi");
        }
    }

    @Nested
    @DisplayName("Slicing")
    final class Slicing {

        @Test
        @DisplayName("Left and Right take a prefix and a suffix")
        void leftRight() {
            assertThat(text(call("Left", s("hello"), n("2")))).isEqualTo("he");
            assertThat(text(call("Right", s("hello"), n("2")))).isEqualTo("lo");
        }

        @Test
        @DisplayName("a length beyond the string takes all of it")
        void beyondTheEnd() {
            assertThat(text(call("Left", s("hi"), n("99")))).isEqualTo("hi");
            assertThat(text(call("Right", s("hi"), n("99")))).isEqualTo("hi");
            assertThat(text(call("Mid", s("hi"), n("1"), n("99")))).isEqualTo("hi");
            assertThat(text(call("Mid", s("hi"), n("9")))).isEmpty();
        }

        @Test
        @DisplayName("a negative length is rejected rather than read as zero")
        void negativeLength() {
            assertThatThrownBy(() -> call("Left", s("hi"), n("-1")))
                    .hasMessage("Left: length must be non-negative");
            assertThatThrownBy(() -> call("Right", s("hi"), n("-1")))
                    .hasMessage("Right: length must be non-negative");
            assertThatThrownBy(() -> call("Mid", s("hi"), n("1"), n("-1")))
                    .hasMessage("Mid: length must be non-negative");
        }

        @Test
        @DisplayName("Mid counts from 1, in both of its forms")
        void mid() {
            assertThat(text(call("Mid", s("hello"), n("2")))).isEqualTo("ello");
            assertThat(text(call("Mid", s("hello"), n("2"), n("3")))).isEqualTo("ell");
        }

        @Test
        @DisplayName("Mid rejects a start before the first character")
        void midStart() {
            assertThatThrownBy(() -> call("Mid", s("hello"), n("0")))
                    .hasMessage("Mid: start must be >= 1");
        }
    }

    @Nested
    @DisplayName("Search and replace")
    final class SearchAndReplace {

        @Test
        @DisplayName("InStr reports a 1-based position, or 0 for absent")
        void inStr() {
            assertThat(number(call("InStr", s("hello"), s("l")))).isEqualTo("3");
            assertThat(number(call("InStr", s("hello"), s("z")))).isEqualTo("0");
        }

        @Test
        @DisplayName("the three-argument form starts the search at a position")
        void inStrFrom() {
            assertThat(number(call("InStr", n("4"), s("hello"), s("l")))).isEqualTo("4");
            assertThat(number(call("InStr", n("0"), s("hello"), s("h")))).isEqualTo("1");
            assertThat(number(call("InStr", n("4"), s("hello"), s("h")))).isEqualTo("0");
        }

        @Test
        @DisplayName("Replace replaces every occurrence, literally")
        void replace() {
            assertThat(text(call("Replace", s("a-b-c"), s("-"), s("+")))).isEqualTo("a+b+c");
            assertThat(text(call("Replace", s("a.b"), s("."), s("!")))).isEqualTo("a!b");
        }
    }

    @Nested
    @DisplayName("Characters")
    final class Characters {

        @Test
        @DisplayName("Chr and Asc are inverses over the code-point range")
        void chrAsc() {
            assertThat(text(call("Chr", n("65")))).isEqualTo("A");
            assertThat(number(call("Asc", s("A")))).isEqualTo("65");
            assertThat(number(call("Asc", s("Abc")))).isEqualTo("65");
        }

        @Test
        @DisplayName("Chr rejects a code outside the range")
        void chrRange() {
            assertThatThrownBy(() -> call("Chr", n("-1")))
                    .hasMessage("Chr: invalid character code -1");
            // Past the last code point there is, U+10FFFF.
            assertThatThrownBy(() -> call("Chr", n("1114112")))
                    .hasMessage("Chr: invalid character code 1114112");
            // A surrogate is half of a character rather than one, and a string holding a
            // lone one is not well-formed text.
            assertThatThrownBy(() -> call("Chr", n("55357")))
                    .hasMessage("Chr: invalid character code 55357");
        }

        @Test
        @DisplayName("Chr builds a character outside the basic plane, and Asc reads it back")
        void chrSupplementary() {
            // 65535 used to be the ceiling, because the result was a single Java char.
            // A code point above it is one character, and Asc answers with the code point
            // rather than with the leading surrogate it happens to be stored as.
            assertThat(text(call("Chr", n("128512")))).isEqualTo("\uD83D\uDE00");
            assertThat(number(call("Asc", s("\uD83D\uDE00")))).isEqualTo("128512");
            assertThat(number(call("Asc", call("Chr", n("70000"))))).isEqualTo("70000");
        }

        @Test
        @DisplayName("Asc has no answer for an empty string")
        void ascEmpty() {
            assertThatThrownBy(() -> call("Asc", s("")))
                    .hasMessage("Asc: empty string");
        }
    }

    @Nested
    @DisplayName("Missing and mistyped values")
    final class Edges {

        @Test
        @DisplayName("a NULL input yields NULL, not an error")
        void nullIn() {
            for (String name : new String[]{"Len", "UCase", "LCase", "Trim", "LTrim",
                    "RTrim", "Asc"}) {
                assertThat(call(name, NULL).isNull()).as(name).isTrue();
            }
            assertThat(call("Left", NULL, n("1")).isNull()).isTrue();
            assertThat(call("Right", NULL, n("1")).isNull()).isTrue();
            assertThat(call("Mid", NULL, n("1")).isNull()).isTrue();
            assertThat(call("Chr", NULL).isNull()).isTrue();
            assertThat(call("Replace", NULL, s("a"), s("b")).isNull()).isTrue();
            assertThat(call("InStr", NULL, s("a")).isNull()).isTrue();
            assertThat(call("InStr", s("a"), NULL).isNull()).isTrue();
            assertThat(call("InStr", n("1"), NULL, s("a")).isNull()).isTrue();
            assertThat(call("InStr", n("1"), s("a"), NULL).isNull()).isTrue();
        }

        @Test
        @DisplayName("a value of the wrong type is reported as such")
        void wrongType() {
            assertThatThrownBy(() -> call("Len", n("1")))
                    .hasMessage("Len: expected STRING argument, got NUMBER");
            assertThatThrownBy(() -> call("Left", s("hi"), s("x")))
                    .hasMessage("Left: expected NUMBER argument, got STRING");
        }

        @Test
        @DisplayName("a fractional length is rounded, not rejected")
        void fractionalLength() {
            assertThat(text(call("Left", s("hello"), n("1.5")))).isEqualTo("he");
        }

        @Test
        @DisplayName("a length no int can hold is reported rather than truncated")
        void oversizedLength() {
            assertThatThrownBy(() -> call("Left", s("hi"), n("99999999999999999999")))
                    .hasMessage("Left: expected integer argument, got 99999999999999999999");
        }
    }
}

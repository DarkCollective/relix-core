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

import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.number;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.text;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.truth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The type-check and conversion built-ins")
final class ConversionFunctionsTest {

    private static final Value NULL = NullValue.INSTANCE;

    @Nested
    @DisplayName("Asking about a value")
    final class Asking {

        @Test
        @DisplayName("IsNull answers for any value and never fails")
        void isNull() {
            assertThat(truth(call("IsNull", NULL))).isTrue();
            assertThat(truth(call("IsNull", s("x")))).isFalse();
            assertThat(truth(call("IsNull", n("0")))).isFalse();
        }

        @Test
        @DisplayName("IsNumeric means convertible, so a string of digits counts")
        void isNumeric() {
            assertThat(truth(call("IsNumeric", n("1")))).isTrue();
            assertThat(truth(call("IsNumeric", s("42")))).isTrue();
            assertThat(truth(call("IsNumeric", s("-3.5e2")))).isTrue();
            assertThat(truth(call("IsNumeric", s("forty")))).isFalse();
            assertThat(truth(call("IsNumeric", s("")))).isFalse();
            assertThat(truth(call("IsNumeric", NULL))).isFalse();
            assertThat(truth(call("IsNumeric", BooleanValue.TRUE))).isFalse();
        }
    }

    @Nested
    @DisplayName("Converting a value")
    final class Converting {

        @Test
        @DisplayName("CStr renders a value the way it is displayed")
        void cStr() {
            assertThat(text(call("CStr", n("42")))).isEqualTo("42");
            assertThat(text(call("CStr", s("already")))).isEqualTo("already");
            assertThat(text(call("CStr", BooleanValue.TRUE)))
                    .isEqualTo(BooleanValue.TRUE.asDisplayString());
        }

        @Test
        @DisplayName("CInt rounds a number and reads a boolean as 1 or 0")
        void cInt() {
            assertThat(number(call("CInt", n("2.6")))).isEqualTo("3");
            assertThat(number(call("CInt", n("2.4")))).isEqualTo("2");
            assertThat(number(call("CInt", BooleanValue.TRUE))).isEqualTo("1");
            assertThat(number(call("CInt", BooleanValue.FALSE))).isEqualTo("0");
        }

        @Test
        @DisplayName("CInt parses a numeric string as it is written")
        void cIntParses() {
            assertThat(number(call("CInt", s("42")))).isEqualTo("42");
            assertThat(number(call("CInt", s("2.6")))).isEqualTo("2.6");
        }

        @Test
        @DisplayName("CDbl keeps the fractional part it was given")
        void cDbl() {
            assertThat(number(call("CDbl", n("2.6")))).isEqualTo("2.6");
            assertThat(number(call("CDbl", s("2.6")))).isEqualTo("2.6");
            assertThat(number(call("CDbl", BooleanValue.FALSE))).isEqualTo("0");
        }

        @Test
        @DisplayName("a string that is not a number is quoted back")
        void unparseable() {
            assertThatThrownBy(() -> call("CInt", s("forty")))
                    .hasMessage("CInt: cannot convert \"forty\" to a number");
            assertThatThrownBy(() -> call("CDbl", s("forty")))
                    .hasMessage("CDbl: cannot convert \"forty\" to a number");
        }
    }

    @Nested
    @DisplayName("What cannot be converted")
    final class Refusals {

        @Test
        @DisplayName("NULL is a missing value, not a zero or an empty string")
        void nulls() {
            assertThatThrownBy(() -> call("CStr", NULL)).hasMessage("CStr: cannot convert NULL");
            assertThatThrownBy(() -> call("CInt", NULL)).hasMessage("CInt: cannot convert NULL");
            assertThatThrownBy(() -> call("CDbl", NULL)).hasMessage("CDbl: cannot convert NULL");
        }

        @Test
        @DisplayName("a nested value has no numeric reading")
        void nested() {
            Value struct = new StructValue(Map.of("a", n("1")));
            Value array = new ArrayValue(List.of(n("1")));

            assertThatThrownBy(() -> call("CInt", struct))
                    .hasMessage("CInt: cannot convert a nested value");
            assertThatThrownBy(() -> call("CInt", array))
                    .hasMessage("CInt: cannot convert a nested value");
            assertThatThrownBy(() -> call("CDbl", struct))
                    .hasMessage("CDbl: cannot convert a nested value");
            assertThatThrownBy(() -> call("CDbl", array))
                    .hasMessage("CDbl: cannot convert a nested value");
        }

        @Test
        @DisplayName("a temporal value is not silently an epoch number")
        void temporal() {
            List<Value> temporals = List.of(
                    new DateValue(LocalDate.of(2026, 7, 8)),
                    new TimeValue(LocalTime.NOON),
                    new TimestampValue(Instant.EPOCH),
                    new DurationValue(Duration.ofMinutes(5)));

            for (Value value : temporals) {
                assertThatThrownBy(() -> call("CInt", value))
                        .hasMessage("CInt: cannot convert a temporal value");
                assertThatThrownBy(() -> call("CDbl", value))
                        .hasMessage("CDbl: cannot convert a temporal value");
            }
        }
    }
}

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
package com.darkcollective.relix.value;

import com.darkcollective.relix.value.JsonValues.JsonParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonValues — JSON text → relix Value")
final class JsonValuesTest {

    @Nested
    @DisplayName("scalars")
    class Scalars {

        @Test
        void numbers() {
            assertThat(JsonValues.parse("42")).isEqualTo(new NumberValue(new BigDecimal("42")));
            assertThat(JsonValues.parse("-3.14")).isEqualTo(new NumberValue(new BigDecimal("-3.14")));
            assertThat(JsonValues.parse("1e3")).isEqualTo(new NumberValue(new BigDecimal("1e3")));
        }

        @Test
        @DisplayName("every character the number scanner accepts")
        void everyNumberCharacter() {
            // The scanner's alphabet is one long ||-chain: digits, '.', 'e', 'E', '+', '-'.
            // A test using only digits and a decimal point never reaches most of it.
            assertThat(JsonValues.parse("1.5E+10"))
                    .isEqualTo(new NumberValue(new BigDecimal("1.5E+10")));
            assertThat(JsonValues.parse("2e-7"))
                    .isEqualTo(new NumberValue(new BigDecimal("2e-7")));
            assertThat(JsonValues.parse("-0.5E5"))
                    .isEqualTo(new NumberValue(new BigDecimal("-0.5E5")));
        }

        @Test
        @DisplayName("every character the whitespace skipper accepts")
        void everyWhitespaceCharacter() {
            // Space, tab, newline and carriage return are four separate arms.
            assertThat(JsonValues.parse(" \t\n\r42\r\n\t "))
                    .isEqualTo(new NumberValue(new BigDecimal("42")));
        }

        @Test
        void stringsAndEscapes() {
            assertThat(JsonValues.parse("\"hi\"")).isEqualTo(new StringValue("hi"));
            assertThat(JsonValues.parse("\"a\\\"b\\n\\t\\u0041\""))
                    .isEqualTo(new StringValue("a\"b\n\tA"));
        }

        @Test
        void booleansAndNull() {
            assertThat(JsonValues.parse("true")).isEqualTo(BooleanValue.of(true));
            assertThat(JsonValues.parse("false")).isEqualTo(BooleanValue.of(false));
            assertThat(JsonValues.parse("null")).isEqualTo(NullValue.INSTANCE);
        }
    }

    @Nested
    @DisplayName("structures")
    class Structures {

        @Test
        void objectBecomesStruct() {
            Value v = JsonValues.parse("{\"id\": 1, \"name\": \"Alice\"}");
            assertThat(v).isInstanceOf(StructValue.class);
            StructValue s = (StructValue) v;
            assertThat(s.field("id")).contains(new NumberValue(new BigDecimal("1")));
            assertThat(s.field("name")).contains(new StringValue("Alice"));
        }

        @Test
        void arrayBecomesArrayValue() {
            assertThat(JsonValues.parse("[1, 2, 3]")).isEqualTo(new ArrayValue(List.of(
                    new NumberValue(new BigDecimal("1")),
                    new NumberValue(new BigDecimal("2")),
                    new NumberValue(new BigDecimal("3")))));
        }

        @Test
        void deeplyNested() {
            Value v = JsonValues.parse(
                    "{\"user\": {\"name\": \"Bo\"}, \"tags\": [\"x\", \"y\"], \"n\": null}");
            StructValue s = (StructValue) v;
            assertThat(((StructValue) s.field("user").orElseThrow()).field("name"))
                    .contains(new StringValue("Bo"));
            assertThat(s.field("tags").orElseThrow()).isInstanceOf(ArrayValue.class);
            assertThat(s.field("n")).contains(NullValue.INSTANCE);
        }

        @Test
        void emptyObjectAndArray() {
            assertThat(JsonValues.parse("{}")).isEqualTo(new StructValue(Map.of()));
            assertThat(JsonValues.parse("[]")).isEqualTo(new ArrayValue(List.of()));
        }

        @Test
        void leadingAndTrailingWhitespace() {
            assertThat(JsonValues.parse("  \n {\"a\": 1}\t ")).isInstanceOf(StructValue.class);
        }

        /**
         * Whitespace <em>inside</em> a structure — between the brace and the first key,
         * around the colon, and before the closing brace or comma.
         *
         * <p>Neither this test nor {@code JsonRoundTripTest} could have existed by
         * accident: the round trip tests the reader against {@code JsonWriter}, and the
         * writer emits compact JSON. A round-trip test therefore cannot produce an input
         * with interior whitespace, so every {@code skipWhitespace()} call in
         * {@code parseObject}/{@code parseArray} could be deleted without any test
         * failing. Mutation testing found five such calls.
         */
        @Test
        void whitespaceInsideStructures() {
            StructValue expected = new StructValue(new LinkedHashMap<>(Map.of(
                    "a", new NumberValue(new BigDecimal("1")))));

            assertThat(JsonValues.parse("{ \"a\": 1}")).isEqualTo(expected);
            assertThat(JsonValues.parse("{\"a\" : 1}")).isEqualTo(expected);
            assertThat(JsonValues.parse("{\"a\":  1}")).isEqualTo(expected);
            assertThat(JsonValues.parse("{\"a\": 1 }")).isEqualTo(expected);
            assertThat(JsonValues.parse("{ \"a\" : 1 }")).isEqualTo(expected);

            assertThat(JsonValues.parse("{ }")).isEqualTo(new StructValue(Map.of()));
            assertThat(JsonValues.parse("[ ]")).isEqualTo(new ArrayValue(List.of()));

            ArrayValue nums = new ArrayValue(List.of(
                    new NumberValue(new BigDecimal("1")), new NumberValue(new BigDecimal("2"))));
            assertThat(JsonValues.parse("[ 1, 2]")).isEqualTo(nums);
            assertThat(JsonValues.parse("[1 , 2]")).isEqualTo(nums);
            assertThat(JsonValues.parse("[1, 2 ]")).isEqualTo(nums);
            assertThat(JsonValues.parse("[ 1 , 2 ]")).isEqualTo(nums);

            assertThat(JsonValues.parse("{ \"o\" : { \"n\" : [ 1 ] } }"))
                    .isInstanceOf(StructValue.class);
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        void malformedInputThrows() {
            assertThatThrownBy(() -> JsonValues.parse("{\"a\": }")).isInstanceOf(JsonParseException.class);
            assertThatThrownBy(() -> JsonValues.parse("[1, 2")).isInstanceOf(JsonParseException.class);
            assertThatThrownBy(() -> JsonValues.parse("\"unterminated")).isInstanceOf(JsonParseException.class);
            assertThatThrownBy(() -> JsonValues.parse("")).isInstanceOf(JsonParseException.class);
        }

        @Test
        @DisplayName("a lone minus sign is not a number")
        void loneMinusThrows() {
            // The scanner consumes the '-' before testing what follows, so this is the
            // arm the "no digits at all" case never reaches.
            assertThatThrownBy(() -> JsonValues.parse("-"))
                    .isInstanceOf(JsonParseException.class)
                    .hasMessageContaining("Invalid value");
        }

        @Test
        @DisplayName("a truncated document reports rather than reading past the end")
        void truncatedDocumentThrows() {
            assertThatThrownBy(() -> JsonValues.parse("{\"a\""))
                    .isInstanceOf(JsonParseException.class);
            assertThatThrownBy(() -> JsonValues.parse("{"))
                    .isInstanceOf(JsonParseException.class);
        }

        @Test
        void trailingContentThrows() {
            assertThatThrownBy(() -> JsonValues.parse("1 2"))
                    .isInstanceOf(JsonParseException.class)
                    .hasMessageContaining("Trailing");
        }
    }
}

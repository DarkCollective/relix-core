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
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.function.builtin.BuiltinCalls.call;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.function;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.n;
import static com.darkcollective.relix.function.builtin.BuiltinCalls.s;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The nested-data built-ins")
final class NestedFunctionsTest {

    private static final Value NULL = NullValue.INSTANCE;

    /** A struct in a stated field order, which is the order entries must come back in. */
    private static StructValue object(Object... namesAndValues) {
        Map<String, Value> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            fields.put((String) namesAndValues[i], (Value) namesAndValues[i + 1]);
        }
        return new StructValue(fields);
    }

    private static List<Value> entriesOf(Value result) {
        return ((ArrayValue) result).elements();
    }

    private static String keyOf(Value entry) {
        return ((StringValue) ((StructValue) entry).field("key").orElseThrow()).value();
    }

    private static Value valueOf(Value entry) {
        return ((StructValue) entry).field("value").orElseThrow();
    }

    @Nested
    @DisplayName("Reading an object as entries")
    final class ReadingAnObject {

        @Test
        @DisplayName("each field becomes one {key, value} struct")
        void eachFieldBecomesAnEntry() {
            List<Value> entries = entriesOf(call("Entries",
                    object("us", n("3"), "gb", n("7"))));

            assertThat(entries).hasSize(2);
            assertThat(keyOf(entries.get(0))).isEqualTo("us");
            assertThat(valueOf(entries.get(0))).isEqualTo(new NumberValue(new BigDecimal("3")));
            assertThat(keyOf(entries.get(1))).isEqualTo("gb");
            assertThat(valueOf(entries.get(1))).isEqualTo(new NumberValue(new BigDecimal("7")));
        }

        @Test
        @DisplayName("entries come in the object's own field order, not a sorted one")
        void entryOrderIsTheObjectsOwn() {
            Value result = call("Entries", object(
                    "zulu", n("1"), "alpha", n("2"), "mike", n("3")));

            assertThat(entriesOf(result).stream().map(NestedFunctionsTest::keyOf))
                    .as("field order is what lets the function declare itself deterministic")
                    .containsExactly("zulu", "alpha", "mike");
        }

        @Test
        @DisplayName("a key is carried verbatim, including one no identifier could spell")
        void keysAreCarriedVerbatim() {
            Value result = call("Entries", object(
                    "Mixed Case", n("1"), "has.a.dot", n("2"), "", n("3")));

            assertThat(entriesOf(result).stream().map(NestedFunctionsTest::keyOf))
                    .as("a key is data, so no name rule applies to it — which is the whole "
                        + "reason a struct type cannot describe this object")
                    .containsExactly("Mixed Case", "has.a.dot", "");
        }

        @Test
        @DisplayName("a value is carried as it was, nested values included")
        void valuesAreCarriedAsTheyAre() {
            StructValue nested = object("city", s("Leeds"));
            ArrayValue list = new ArrayValue(List.of(n("1"), n("2")));

            List<Value> entries = entriesOf(call("Entries",
                    object("addr", nested, "tags", list, "missing", NULL)));

            assertThat(valueOf(entries.get(0))).isEqualTo(nested);
            assertThat(valueOf(entries.get(1))).isEqualTo(list);
            assertThat(valueOf(entries.get(2)))
                    .as("a NULL-valued field is still a field, so it is still an entry")
                    .isEqualTo(NULL);
        }

        @Test
        @DisplayName("an object with no fields is an empty array, so an inner μ drops the row")
        void anEmptyObjectIsAnEmptyArray() {
            assertThat(entriesOf(call("Entries", object()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Reading anything else")
    final class ReadingAnythingElse {

        @Test
        @DisplayName("a NULL, a scalar and an array are each NULL rather than an error")
        void nonObjectsAreNull() {
            assertThat(call("Entries", NULL))
                    .as("navigation's rule: messy data must not crash a query")
                    .isEqualTo(NULL);
            assertThat(call("Entries", s("text"))).isEqualTo(NULL);
            assertThat(call("Entries", n("1"))).isEqualTo(NULL);
            assertThat(call("Entries", BooleanValue.TRUE)).isEqualTo(NULL);
        }

        @Test
        @DisplayName("an array is NULL because μ already explodes one")
        void anArrayIsNull() {
            assertThat(call("Entries", new ArrayValue(List.of(n("1"), n("2")))))
                    .as("reading an array here would be a second spelling of unnest")
                    .isEqualTo(NULL);
        }
    }

    @Nested
    @DisplayName("What it tells a backend")
    final class WhatItTellsABackend {

        @Test
        @DisplayName("no backend spelling at all, Mongo's $objectToArray included")
        void noBackendSpelling() {
            var pushdown = function("Entries").pushdown();

            assertThat(pushdown.render(PushdownTarget.mongo(), List.of("\"$rates\"")))
                    .as("$objectToArray names the entry fields k and v, so it computes a "
                        + "different value and may not be offered as this function")
                    .isEmpty();
            assertThat(pushdown.render(PushdownTarget.sql(""), List.of("rates"))).isEmpty();
            assertThat(pushdown.render(PushdownTarget.sql("postgres"), List.of("rates"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Counting an array's elements (#293)")
    final class CountingAnArray {

        private final ArrayValue three = new ArrayValue(List.of(s("a"), NULL, n("1")));

        @Test
        @DisplayName("CARDINALITY counts every element, NULL ones included")
        void countsNullElements() {
            assertThat(call("CARDINALITY", three)).isEqualTo(new NumberValue(new BigDecimal("3")));
            assertThat(call("ARRAY_LENGTH", three)).isEqualTo(new NumberValue(new BigDecimal("3")));
        }

        @Test
        @DisplayName("an empty array is 0 by either name, and a NULL array NULL")
        void emptyAndNull() {
            ArrayValue empty = new ArrayValue(List.of());
            assertThat(call("CARDINALITY", empty)).isEqualTo(new NumberValue(BigDecimal.ZERO));
            assertThat(call("ARRAY_LENGTH", empty, n("1"))).isEqualTo(new NumberValue(BigDecimal.ZERO));
            assertThat(call("CARDINALITY", NULL)).isEqualTo(NULL);
            assertThat(call("ARRAY_LENGTH", NULL, n("1"))).isEqualTo(NULL);
        }

        @Test
        @DisplayName("a value that is not an array is refused, as ARRAY_TO_STRING refuses it")
        void nonArrayRefused() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> call("CARDINALITY", s("abc")))
                    .hasMessage("CARDINALITY: expected an array, got STRING");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> call("ARRAY_LENGTH", n("3")))
                    .hasMessage("ARRAY_LENGTH: expected an array, got NUMBER");
        }

        @Test
        @DisplayName("ARRAY_LENGTH's dimension must be 1, the only one an array has")
        void dimensionMustBeOne() {
            assertThat(call("ARRAY_LENGTH", three, n("1"))).isEqualTo(new NumberValue(new BigDecimal("3")));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> call("ARRAY_LENGTH", three, n("2")))
                    .hasMessage("ARRAY_LENGTH: an array has one dimension, so the dimension must be 1, got 2");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> call("ARRAY_LENGTH", three, s("1")))
                    .hasMessage("ARRAY_LENGTH: expected NUMBER dimension, got STRING");
            assertThat(call("ARRAY_LENGTH", three, NULL)).isEqualTo(NULL);
        }

        @Test
        @DisplayName("Len of an array names CARDINALITY")
        void lenOfAnArrayNamesCardinality() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> call("LENGTH", three))
                    .hasMessageContaining("use CARDINALITY for an array's length");
        }

        @Test
        @DisplayName("no backend counts it: the list is built in the engine")
        void noPushdown() {
            assertThat(function("CARDINALITY").pushdown().render(new PushdownTarget("sql", "postgres"), List.of("x")))
                    .isEmpty();
        }
    }
}

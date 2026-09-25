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

import com.darkcollective.relix.value.internal.ValuePath;
import com.darkcollective.relix.value.internal.ValuePath.Step;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Nested values — StructValue, ArrayValue, ValuePath")
final class NestedValueTest {

    private static StringValue str(String s) {
        return new StringValue(s);
    }

    private static NumberValue num(long n) {
        return new NumberValue(java.math.BigDecimal.valueOf(n));
    }

    private static StructValue struct(Object... pairs) {
        Map<String, Value> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], (Value) pairs[i + 1]);
        }
        return new StructValue(fields);
    }

    @Nested
    @DisplayName("StructValue")
    class Structs {

        @Test
        @DisplayName("reports ANY, is non-null, and looks up fields case-insensitively")
        void basics() {
            StructValue s = struct("Name", str("Alice"), "age", num(30));
            assertThat(s.isNull()).isFalse();
            assertThat(s.type()).isEqualTo(ScalarType.ANY);
            assertThat(s.field("name")).contains(str("Alice"));
            assertThat(s.field("AGE")).contains(num(30));
            assertThat(s.field("missing")).isEmpty();
        }

        @Test
        @DisplayName("equality is structural and order-independent")
        void structuralEquality() {
            assertThat(struct("a", num(1), "b", num(2)))
                    .isEqualTo(struct("b", num(2), "a", num(1)));
            assertThat(struct("a", num(1))).isNotEqualTo(struct("a", num(2)));
        }

        @Test
        @DisplayName("displays as a brace-delimited object")
        void display() {
            assertThat(struct("name", str("Alice"), "tags",
                    new ArrayValue(List.of(str("x"), str("y")))).asDisplayString())
                    .isEqualTo("{name: Alice, tags: [x, y]}");
        }
    }

    @Nested
    @DisplayName("ArrayValue")
    class Arrays {

        @Test
        @DisplayName("reports ANY, indexes in range, structural order-sensitive equality")
        void basics() {
            ArrayValue a = new ArrayValue(List.of(num(1), num(2)));
            assertThat(a.type()).isEqualTo(ScalarType.ANY);
            assertThat(a.at(0)).contains(num(1));
            assertThat(a.at(1)).contains(num(2));
            // Both ends of the valid range, then one step past each. at(size) is the
            // case that distinguishes `index < size` from `index <= size`; at(5) on a
            // two-element array is three steps past the boundary and passes either way,
            // so the off-by-one had no test until mutation testing named it.
            assertThat(a.at(2)).isEmpty();
            assertThat(a.at(5)).isEmpty();
            assertThat(a.at(-1)).isEmpty();
            assertThat(a).isEqualTo(new ArrayValue(List.of(num(1), num(2))));
            assertThat(a).isNotEqualTo(new ArrayValue(List.of(num(2), num(1))));
        }

        @Test
        @DisplayName("displays as a bracket-delimited list")
        void display() {
            assertThat(new ArrayValue(List.of(num(1), str("a"))).asDisplayString())
                    .isEqualTo("[1, a]");
        }
    }

    @Nested
    @DisplayName("ValuePath — null-propagating navigation")
    class Paths {

        private final StructValue doc = struct(
                "user", struct("name", str("Alice")),
                "items", new ArrayValue(List.of(
                        struct("price", num(10)),
                        struct("price", num(20)))));

        @Test
        @DisplayName("navigates struct fields and array indices")
        void navigatesNested() {
            assertThat(ValuePath.navigate(doc, "user.name")).isEqualTo(str("Alice"));
            assertThat(ValuePath.navigate(doc, "items[1].price")).isEqualTo(num(20));
        }

        @Test
        @DisplayName("yields NULL on a missing field, bad index, or wrong shape")
        void nullPropagates() {
            assertThat(ValuePath.navigate(doc, "user.missing")).isEqualTo(NullValue.INSTANCE);
            assertThat(ValuePath.navigate(doc, "items[9].price")).isEqualTo(NullValue.INSTANCE);
            assertThat(ValuePath.navigate(doc, "user.name.nope")).isEqualTo(NullValue.INSTANCE);
            assertThat(ValuePath.navigate(doc, "user[0]")).isEqualTo(NullValue.INSTANCE);
        }

        @Test
        @DisplayName("parses dotted/bracketed paths into steps")
        void parse() {
            assertThat(ValuePath.parse("a.b[0].c")).containsExactly(
                    new Step.Field("a"), new Step.Field("b"),
                    new Step.Index(0), new Step.Field("c"));
            assertThat(ValuePath.parse("[2].name")).containsExactly(
                    new Step.Index(2), new Step.Field("name"));
        }

        @Test
        @DisplayName("rejects an unterminated bracket or non-integer index")
        void badPaths() {
            assertThatThrownBy(() -> ValuePath.parse("a[0"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unterminated");
            assertThatThrownBy(() -> ValuePath.parse("a[x]"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("integer");
        }
    }
}

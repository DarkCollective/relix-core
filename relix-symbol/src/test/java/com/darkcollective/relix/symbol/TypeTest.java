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
package com.darkcollective.relix.symbol;

import com.darkcollective.relix.symbol.StructType.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Type — scalar / struct / array hierarchy")
final class TypeTest {

    @Nested
    @DisplayName("ScalarType")
    class Scalars {

        @Test
        @DisplayName("is a Type and displays its lower-cased name")
        void scalarIsType() {
            Type t = ScalarType.NUMBER;
            assertThat(t.display()).isEqualTo("number");
            assertThat(ScalarType.ANY.display()).isEqualTo("any");
        }

        @Test
        @DisplayName("code() is the one-letter type code via the Type interface")
        void code() {
            Type t = ScalarType.NUMBER;
            assertThat(t.code()).isEqualTo("N");
            assertThat(ScalarType.STRING.code()).isEqualTo("S");
            assertThat(ScalarType.BOOLEAN.code()).isEqualTo("B");
            assertThat(ScalarType.ANY.code()).isEqualTo("?");
        }
    }

    @Nested
    @DisplayName("ArrayType")
    class Arrays {

        @Test
        @DisplayName("displays its element type recursively")
        void display() {
            assertThat(new ArrayType(ScalarType.STRING).display()).isEqualTo("array<string>");
            assertThat(new ArrayType(new ArrayType(ScalarType.NUMBER)).display())
                    .isEqualTo("array<array<number>>");
        }

        @Test
        @DisplayName("code() wraps the element code in brackets, recursively")
        void code() {
            assertThat(new ArrayType(ScalarType.NUMBER).code()).isEqualTo("[N]");
            assertThat(new ArrayType(new ArrayType(ScalarType.STRING)).code()).isEqualTo("[[S]]");
        }

        @Test
        @DisplayName("rejects a null element type")
        void nullElement() {
            assertThatThrownBy(() -> new ArrayType(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("StructType")
    class Structs {

        private static StructType struct(Field... fields) {
            return new StructType(List.of(fields));
        }

        @Test
        @DisplayName("displays its fields recursively")
        void display() {
            StructType s = struct(
                    new Field("id", ScalarType.NUMBER),
                    new Field("tags", new ArrayType(ScalarType.STRING)),
                    new Field("user", struct(new Field("name", ScalarType.STRING))));
            assertThat(s.display())
                    .isEqualTo("struct{id: number, tags: array<string>, user: struct{name: string}}");
        }

        @Test
        @DisplayName("code() is a compact recursive {field:code,…}")
        void code() {
            StructType s = struct(
                    new Field("id", ScalarType.NUMBER),
                    new Field("tags", new ArrayType(ScalarType.STRING)),
                    new Field("user", struct(new Field("name", ScalarType.STRING))));
            assertThat(s.code()).isEqualTo("{id:N,tags:[S],user:{name:S}}");
        }

        @Test
        @DisplayName("field lookup is case-insensitive")
        void caseInsensitiveLookup() {
            StructType s = struct(new Field("UserId", ScalarType.NUMBER));
            assertThat(s.field("userid")).map(Field::name).contains("UserId");
            assertThat(s.field("missing")).isEmpty();
        }

        @Test
        @DisplayName("rejects case-insensitively duplicate field names")
        void rejectsDuplicateNames() {
            assertThatThrownBy(() -> struct(
                    new Field("name", ScalarType.STRING),
                    new Field("NAME", ScalarType.NUMBER)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate");
        }

        @Test
        @DisplayName("copies its field list defensively")
        void defensiveCopy() {
            List<Field> fields = new ArrayList<>(List.of(new Field("a", ScalarType.NUMBER)));
            StructType s = new StructType(fields);
            fields.add(new Field("b", ScalarType.STRING));
            assertThat(s.fields()).hasSize(1);
        }

        @Test
        @DisplayName("Field rejects a blank name or null type")
        void fieldValidation() {
            assertThatThrownBy(() -> new Field("  ", ScalarType.NUMBER))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Field("x", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    @DisplayName("a Type can be pattern-matched exhaustively (sealed)")
    void exhaustiveSwitch() {
        List<Type> types = List.of(
                ScalarType.NUMBER, new ArrayType(ScalarType.STRING),
                new StructType(List.of(new Field("a", ScalarType.ANY))));
        for (Type type : types) {
            String kind = switch (type) {
                case ScalarType s -> "scalar";
                case ArrayType a  -> "array";
                case StructType s -> "struct";
            };
            assertThat(kind).isIn("scalar", "array", "struct");
        }
    }
}

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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ArrayRow — positional Row implementation")
final class RowTest extends ProcessorTestSupport {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("of(schema, List) accepts matching value count")
        void ofListAcceptsMatchingCount() {
            var schema = schema("id", "name");
            var row = ArrayRow.of(schema, List.of(num(1), str("Alice")));
            assertThat(row.width()).isEqualTo(2);
        }

        @Test
        @DisplayName("of(schema, Value...) varargs convenience")
        void ofVarargs() {
            var schema = schema("x");
            var row = ArrayRow.of(schema, num(42));
            assertThat(row.get(0)).isEqualTo(num(42));
        }

        @Test
        @DisplayName("rejects value count mismatch")
        void rejectsValueCountMismatch() {
            var schema = schema("id", "name");
            assertThatThrownBy(() -> ArrayRow.of(schema, num(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match schema width");
        }

        @Test
        @DisplayName("rejects null value in list")
        void rejectsNullValueInList() {
            var schema = schema("x");
            assertThatThrownBy(() -> ArrayRow.of(schema, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("accepts NullValue as a legitimate absent value")
        void acceptsNullValue() {
            var schema = schema("x");
            var row = ArrayRow.of(schema, NullValue.INSTANCE);
            assertThat(row.get("x").isNull()).isTrue();
        }
    }

    @Nested
    @DisplayName("Column access")
    class ColumnAccess {

        @Test
        @DisplayName("get(String) returns correct value")
        void getByNameReturnsCorrectValue() {
            var schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
            var row = ArrayRow.of(schema, num(7), str("Bob"));
            assertThat(row.get("id")).isEqualTo(num(7));
            assertThat(row.get("name")).isEqualTo(str("Bob"));
        }

        @Test
        @DisplayName("get(String) is case-insensitive")
        void getByNameIsCaseInsensitive() {
            var schema = schema("UserId");
            var row = ArrayRow.of(schema, num(1));
            assertThat(row.get("userid")).isEqualTo(num(1));
            assertThat(row.get("USERID")).isEqualTo(num(1));
            assertThat(row.get("UserId")).isEqualTo(num(1));
        }

        @Test
        @DisplayName("get(String) throws for unknown column")
        void getByNameThrowsForUnknownColumn() {
            var row = ArrayRow.of(schema("id"), num(1));
            assertThatThrownBy(() -> row.get("nonexistent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nonexistent");
        }

        @Test
        @DisplayName("get(int) returns value at correct position")
        void getByIndexReturnsCorrectValue() {
            var schema = schema("a", "b", "c");
            var row = ArrayRow.of(schema, str("x"), num(2), bool(true));
            assertThat(row.get(0)).isEqualTo(str("x"));
            assertThat(row.get(1)).isEqualTo(num(2));
            assertThat(row.get(2)).isEqualTo(bool(true));
        }

        @Test
        @DisplayName("get(int) throws for negative index")
        void getByIndexThrowsForNegative() {
            var row = ArrayRow.of(schema("x"), str("v"));
            assertThatThrownBy(() -> row.get(-1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("get(int) throws for index equal to width")
        void getByIndexThrowsAtWidth() {
            var row = ArrayRow.of(schema("x"), str("v"));
            assertThatThrownBy(() -> row.get(1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("Schema")
    class SchemaAccess {

        @Test
        @DisplayName("schema() returns the construction schema")
        void schemaIsRetained() {
            var schema = schema(col("id", ScalarType.NUMBER));
            var row = ArrayRow.of(schema, num(1));
            assertThat(row.schema()).isSameAs(schema);
        }

        @Test
        @DisplayName("width() matches schema column count")
        void widthMatchesSchema() {
            var row = ArrayRow.of(schema("a", "b", "c"), str("x"), str("y"), str("z"));
            assertThat(row.width()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Equality and hashCode")
    class EqualityAndHash {

        @Test
        @DisplayName("equal when schema and values match")
        void equalWhenSchemaAndValuesMatch() {
            var schema = schema("id", "name");
            var r1 = ArrayRow.of(schema, num(1), str("Alice"));
            var r2 = ArrayRow.of(schema, num(1), str("Alice"));
            assertThat(r1).isEqualTo(r2);
            assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        }

        @Test
        @DisplayName("not equal when values differ")
        void notEqualWhenValuesDiffer() {
            var schema = schema("id");
            assertThat(ArrayRow.of(schema, num(1))).isNotEqualTo(ArrayRow.of(schema, num(2)));
        }

        @Test
        @DisplayName("not equal when schemas differ")
        void notEqualWhenSchemasDiffer() {
            var r1 = ArrayRow.of(schema("a"), num(1));
            var r2 = ArrayRow.of(schema("b"), num(1));
            assertThat(r1).isNotEqualTo(r2);
        }

        @Test
        @DisplayName("not equal to null")
        void notEqualToNull() {
            var row = ArrayRow.of(schema("x"), str("v"));
            assertThat(row).isNotEqualTo(null);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("includes column names and display values")
        void includesColumnNamesAndValues() {
            var schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
            var row = ArrayRow.of(schema, num(1), str("Alice"));
            assertThat(row.toString()).contains("id=1").contains("name=Alice");
        }

        @Test
        @DisplayName("NULL values display as 'NULL'")
        void nullDisplaysCorrectly() {
            var row = ArrayRow.of(schema("x"), NullValue.INSTANCE);
            assertThat(row.toString()).contains("x=NULL");
        }
    }

    @Nested
    @DisplayName("Row.of, the factory a connector builds rows with")
    class Factory {

        @Test
        @DisplayName("reads back by name and by position, from a list or varargs")
        void builds() {
            Schema heading = schema("id", "name");
            Row fromList = Row.of(heading, List.of(num(1), str("Ada")));
            Row fromArgs = Row.of(heading, num(1), str("Ada"));
            assertThat(fromList.get("name")).isEqualTo(str("Ada"));
            assertThat(fromArgs.get(0)).isEqualTo(num(1));
            assertThat(fromList.schema()).isEqualTo(heading);
        }

        @Test
        @DisplayName("refuses a value count that does not match the heading")
        void mismatch() {
            Schema heading = schema("id");
            assertThatThrownBy(() -> Row.of(heading, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

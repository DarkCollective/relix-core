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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Schema#resolvePath(String)} — reading a dotted name as a path into a
 * struct column.
 *
 * <p>The rule this pins is the precedence one: a whole-name match wins before the
 * name is read as a path, so a column that really is called {@code a.b} stays
 * reachable and does not become a field lookup on a column called {@code a}.
 */
@DisplayName("Schema — resolving a dotted path into a struct column")
final class SchemaPathResolutionTest {

    private static final StructType PERSON = new StructType(List.of(
            new StructType.Field("first", ScalarType.STRING),
            new StructType.Field("address", new StructType(List.of(
                    new StructType.Field("city", ScalarType.STRING))))));

    private static final Schema SCHEMA = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("person", PERSON),
            new ColumnDefinition("payload", ScalarType.ANY),
            new ColumnDefinition("tags", new ArrayType(ScalarType.STRING))));

    @Nested
    @DisplayName("what resolves")
    class Resolves {

        @Test
        @DisplayName("a plain column, by its own name")
        void plainColumn() {
            assertThat(SCHEMA.resolvePath("id")).hasValue(ScalarType.NUMBER);
            assertThat(SCHEMA.resolvePath("person")).hasValue(PERSON);
        }

        @Test
        @DisplayName("a struct field, and a field of a field")
        void structField() {
            assertThat(SCHEMA.resolvePath("person.first")).hasValue(ScalarType.STRING);
            assertThat(SCHEMA.resolvePath("person.address.city")).hasValue(ScalarType.STRING);
        }

        @Test
        @DisplayName("case-insensitively, like every other column reference")
        void caseInsensitive() {
            assertThat(SCHEMA.resolvePath("Person.First")).hasValue(ScalarType.STRING);
        }

        @Test
        @DisplayName("any path into an ANY column, to ANY — its shape arrives with the row")
        void intoAny() {
            assertThat(SCHEMA.resolvePath("payload.anything")).hasValue(ScalarType.ANY);
            assertThat(SCHEMA.resolvePath("payload.deeply.nested.thing")).hasValue(ScalarType.ANY);
        }

        @Test
        @DisplayName("anything at all against an open schema")
        void openSchema() {
            assertThat(Schema.open().resolvePath("whatever.you.like")).hasValue(ScalarType.ANY);
        }
    }

    @Nested
    @DisplayName("what does not")
    class DoesNot {

        @Test
        @DisplayName("a field the struct does not declare")
        void unknownField() {
            assertThat(SCHEMA.resolvePath("person.last")).isEmpty();
            assertThat(SCHEMA.resolvePath("person.address.postcode")).isEmpty();
        }

        @Test
        @DisplayName("a path whose head names no column")
        void unknownHead() {
            assertThat(SCHEMA.resolvePath("nobody.first")).isEmpty();
        }

        @Test
        @DisplayName("a field of something that has none")
        void notAStruct() {
            assertThat(SCHEMA.resolvePath("id.first")).isEmpty();
            assertThat(SCHEMA.resolvePath("tags.first")).isEmpty();
        }

        @Test
        @DisplayName("a malformed path — a leading, trailing or doubled dot")
        void malformed() {
            assertThat(SCHEMA.resolvePath(".first")).isEmpty();
            assertThat(SCHEMA.resolvePath("person.")).isEmpty();
            assertThat(SCHEMA.resolvePath("person..first")).isEmpty();
        }
    }

    @Test
    @DisplayName("a column whose name contains a dot wins over reading that name as a path")
    void wholeNameWinsFirst() {
        // Written with a delimited identifier, `person.first` is a column in its own
        // right. Resolving it as a path would silently read a different value.
        Schema collision = new Schema(List.of(
                new ColumnDefinition("person", PERSON),
                new ColumnDefinition("person.first", ScalarType.NUMBER)));
        assertThat(collision.resolvePath("person.first")).hasValue(ScalarType.NUMBER);
    }
}

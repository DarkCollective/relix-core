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
package com.darkcollective.relix.lang;

import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.source.ColumnSpec;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A schema column may be nested.
 *
 * <p>The type system has carried {@link StructType} and {@link ArrayType} since the NF²
 * work, and {@code ColumnDefinition.type()} has been a {@link Type} throughout — but the
 * grammar could only spell the seven scalars, so a script's one route to a nested column
 * was {@code ANY}. That works and gives up everything a declaration is for: no type
 * checking on the fields, no inference through a path, and nothing that states what the
 * column holds.
 *
 * <p>Only a <em>column</em> is nestable. A {@code def}'s parameters and return type stay
 * scalar, because {@code ParameterDefinition} holds a {@code ScalarType} and widening that
 * changes every function signature — a separate decision, not a side effect of this one.
 */
@DisplayName("Nested schema column types")
final class NestedSchemaTypeTest {

    private static final String CONNECTION =
            "connection mg from mongodb { uri: \"mongodb://h\", database: \"d\" };\n";

    /** The declared type of {@code Docs}' column {@code name}, for a schema block. */
    private static Type columnType(String schema, String name) {
        Script script = ScriptParser.parse(CONNECTION
                + "source Docs from mg { table: \"docs\", schema: " + schema + " };\n"
                + "query Docs;");
        SourceDeclaration source = script.statements().stream()
                .filter(SourceDeclaration.class::isInstance)
                .map(SourceDeclaration.class::cast)
                .findFirst().orElseThrow();
        List<ColumnSpec> columns =
                ((ConnectionTableSourceConfig) source.config()).columns();
        return columns.stream()
                .filter(c -> c.name().equals(name))
                .findFirst().orElseThrow().type();
    }

    private static void assertRejected(String schema, String because) {
        assertThatThrownBy(() -> columnType(schema, "x"))
                .isInstanceOf(LangParseException.class)
                .hasMessageContaining(because);
    }

    @Nested
    @DisplayName("struct")
    final class Structs {

        @Test
        @DisplayName("{ field: TYPE, … } declares a struct")
        void structOfScalars() {
            assertThat(columnType("{ id: NUMBER, addr: { city: STRING, zip: STRING } }", "addr"))
                    .isEqualTo(struct(
                            new StructType.Field("city", ScalarType.STRING),
                            new StructType.Field("zip", ScalarType.STRING)));
        }

        @Test
        @DisplayName("field order is the declared order")
        void fieldOrderIsDeclarationOrder() {
            // The heading of a struct is ordered, like a relation's — a reader of
            // ColumnDefinition.code() sees the fields as the author wrote them.
            StructType type = (StructType) columnType("{ s: { b: STRING, a: NUMBER } }", "s");

            assertThat(type.fields()).extracting(StructType.Field::name)
                    .containsExactly("b", "a");
        }

        @Test
        @DisplayName("a trailing comma is accepted, as it is in a flat schema")
        void trailingCommaIsAccepted() {
            assertThat(columnType("{ s: { a: NUMBER, } }", "s"))
                    .isEqualTo(struct(
                            new StructType.Field("a", ScalarType.NUMBER)));
        }

        @Test
        @DisplayName("an empty struct is refused — it would describe nothing")
        void emptyStructIsRefused() {
            assertRejected("{ x: { } }", "at least one field");
        }
    }

    @Nested
    @DisplayName("array")
    final class Arrays {

        @Test
        @DisplayName("[TYPE] declares an array")
        void arrayOfScalar() {
            assertThat(columnType("{ tags: [STRING] }", "tags"))
                    .isEqualTo(array(ScalarType.STRING));
        }

        @Test
        @DisplayName("an array of structs is the ordinary document shape")
        void arrayOfStruct() {
            assertThat(columnType("{ items: [{ sku: STRING, qty: NUMBER }] }", "items"))
                    .isEqualTo(array(struct(
                            new StructType.Field("sku", ScalarType.STRING),
                            new StructType.Field("qty", ScalarType.NUMBER))));
        }

        @Test
        @DisplayName("BOOLEAN nests like every other scalar")
        void arrayOfBoolean() {
            assertThat(columnType("{ flags: [BOOLEAN] }", "flags"))
                    .isEqualTo(array(ScalarType.BOOLEAN));
            assertThat(columnType("{ prefs: { optIn: BOOLEAN } }", "prefs"))
                    .isEqualTo(struct(
                            new StructType.Field("optIn", ScalarType.BOOLEAN)));
        }

        @Test
        @DisplayName("an unclosed array is refused")
        void unclosedArrayIsRefused() {
            assertRejected("{ x: [STRING }", "rbracket");
        }
    }

    @Nested
    @DisplayName("composition")
    final class Composition {

        @Test
        @DisplayName("nesting composes to any depth")
        void nestsArbitrarily() {
            assertThat(columnType("{ a: { b: { c: [NUMBER] } } }", "a"))
                    .isEqualTo(struct(
                            new StructType.Field("b", struct(
                                    new StructType.Field("c", array(ScalarType.NUMBER))))));
        }

        @Test
        @DisplayName("a nested column sits beside flat ones without disturbing them")
        void mixesWithScalarColumns() {
            assertThat(columnType("{ id: NUMBER, addr: { city: STRING }, name: STRING }", "id"))
                    .isEqualTo(ScalarType.NUMBER);
            assertThat(columnType("{ id: NUMBER, addr: { city: STRING }, name: STRING }", "name"))
                    .isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("a column modifier still parses after a nested type")
        void modifierFollowsANestedType() {
            // `[` means an array where a type is expected and a modifier after one, so the
            // two spellings cannot be confused — but only because the type is parsed first.
            Script script = ScriptParser.parse("""
                    source Api from http {
                        url: "https://api.example.com/x",
                        method: GET,
                        extract: json("$."),
                        schema: {
                            city: in  STRING as query("q") [required],
                            addr: out { street: STRING, zip: STRING },
                            tags: out [STRING]
                        }
                    };
                    query Api;
                    """);

            assertThat(script.statements()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("names the grammar does not spell bare")
    final class DelimitedNames {

        @Test
        @DisplayName("a column may be named between backticks")
        void delimitedColumn() {
            assertThat(columnType("{ id: NUMBER, `unit-price`: NUMBER }", "unit-price"))
                    .isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("so may a struct field")
        void delimitedField() {
            assertThat(columnType("{ addr: { `post-code`: STRING } }", "addr"))
                    .isEqualTo(new StructType(List.of(
                            new StructType.Field("post-code", ScalarType.STRING))));
        }

        @Test
        @DisplayName("a relation name may not — only a database names its columns")
        void delimitedRelationNameIsRefused() {
            assertThatThrownBy(() -> ScriptParser.parse(CONNECTION
                    + "source `my-docs` from mg { table: \"docs\" };\nquery { 1 };"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Expected source name");
        }
    }

    @Nested
    @DisplayName("what stays scalar")
    final class StillScalar {

        @Test
        @DisplayName("a def parameter cannot be nested")
        void defParameterIsScalarOnly() {
            assertThatThrownBy(() -> ScriptParser.parse(
                    "def f(p: { a: STRING }): NUMBER := { 1 };\nquery { 1 };"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Expected scalar type");
        }

        @Test
        @DisplayName("a def return type cannot be nested")
        void defReturnTypeIsScalarOnly() {
            assertThatThrownBy(() -> ScriptParser.parse(
                    "def f(p: STRING): [NUMBER] := { 1 };\nquery { 1 };"))
                    .isInstanceOf(LangParseException.class)
                    .hasMessageContaining("Expected scalar type");
        }
    }
}

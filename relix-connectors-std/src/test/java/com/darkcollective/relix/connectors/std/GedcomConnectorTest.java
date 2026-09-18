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
package com.darkcollective.relix.connectors.std;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.ConnectorRegistry;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.StructValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GedcomConnector — a .ged file as individuals and families")
final class GedcomConnectorTest {

    /**
     * A three-generation pedigree in which {@code I4}'s parents are first cousins, so an
     * ancestor is reached by two distinct lines. The dates deliberately mix an exact day,
     * an approximation and a bare year, which is what GEDCOM actually contains.
     */
    private static final String TREE = """
            0 HEAD
            1 SOUR relix
            0 @I1@ INDI
            1 NAME John /Smith/
            1 SEX M
            1 BIRT
            2 DATE 1 JAN 1900
            2 PLAC London, England
            1 DEAT
            2 DATE ABT 1970
            0 @I2@ INDI
            1 NAME Mary /Smith/
            1 SEX F
            1 BIRT
            2 DATE 1902
            0 @I3@ INDI
            1 NAME Alice /Smith/
            1 SEX F
            0 @I4@ INDI
            1 NAME Robert /Smith/
            1 SEX M
            0 @F1@ FAM
            1 HUSB @I1@
            1 WIFE @I2@
            1 CHIL @I3@
            1 CHIL @I4@
            0 TRLR
            """;

    @TempDir
    Path dir;

    private Path file;

    @BeforeEach
    void writeTree() throws IOException {
        file = dir.resolve("family.ged");
        Files.writeString(file, TREE, StandardCharsets.UTF_8);
    }

    private static Schema schema(ColumnDefinition... columns) {
        return new Schema(List.of(columns));
    }

    private static ColumnDefinition col(String name, ScalarType type) {
        return new ColumnDefinition(name, type);
    }

    private List<Row> open(String table, Schema schema) {
        try (Stream<Row> rows = new GedcomConnector()
                .open(new ConnectorConfig(Map.of("path", file.toString())), table, schema)) {
            return rows.toList();
        }
    }

    @Nested
    @DisplayName("discovery")
    final class Discovery {

        @Test
        @DisplayName("the registry finds it by its type token, with no plugin JARs present")
        void discovered(@TempDir Path emptyPluginDir) {
            try (ConnectorRegistry registry = ConnectorRegistry.create(emptyPluginDir)) {
                assertThat(registry.forType("gedcom")).get().isInstanceOf(GedcomConnector.class);
            }
        }
    }

    @Nested
    @DisplayName("individuals")
    final class Individuals {

        @Test
        @DisplayName("one row per INDI, with the name read as a person would say it")
        void names() {
            List<Row> rows = open("individuals",
                    schema(col("id", ScalarType.STRING), col("name", ScalarType.STRING),
                            col("surname", ScalarType.STRING), col("sex", ScalarType.STRING)));

            assertThat(rows).hasSize(4);
            assertThat(rows.getFirst().get("id").asDisplayString()).isEqualTo("I1");
            assertThat(rows.getFirst().get("name").asDisplayString()).isEqualTo("John Smith");
            assertThat(rows.getFirst().get("surname").asDisplayString()).isEqualTo("Smith");
            assertThat(rows.getFirst().get("sex").asDisplayString()).isEqualTo("M");
        }

        @Test
        @DisplayName("an exact date is a DATE, and its text is kept beside it")
        void exactDate() {
            StructValue birth = (StructValue) firstBirth();

            assertThat(birth.fields().get("date").asDisplayString()).isEqualTo("1900-01-01");
            assertThat(birth.fields().get("text").asDisplayString()).isEqualTo("1 JAN 1900");
            assertThat(birth.fields().get("place").asDisplayString())
                    .isEqualTo("London, England");
        }

        @Test
        @DisplayName("an approximate date is NULL, with the text saying why")
        void approximateDate() {
            // Both NULL is "no date recorded"; a NULL date beside text is "not an exact
            // one" — a distinction one column cannot make, and the reason there are two.
            List<Row> rows = open("individuals",
                    schema(col("id", ScalarType.STRING),
                            new ColumnDefinition("death", eventType())));
            StructValue death = (StructValue) rows.getFirst().get("death");

            assertThat(death.fields().get("date").isNull()).isTrue();
            assertThat(death.fields().get("text").asDisplayString()).isEqualTo("ABT 1970");
        }

        @Test
        @DisplayName("a bare year is not an exact date either")
        void bareYear() {
            List<Row> rows = open("individuals",
                    schema(col("id", ScalarType.STRING),
                            new ColumnDefinition("birth", eventType())));
            StructValue birth = (StructValue) rows.get(1).get("birth");

            assertThat(birth.fields().get("date").isNull()).isTrue();
            assertThat(birth.fields().get("text").asDisplayString()).isEqualTo("1902");
        }

        @Test
        @DisplayName("an individual with no event at all has a NULL struct, not an empty one")
        void missingEvent() {
            List<Row> rows = open("individuals",
                    schema(col("id", ScalarType.STRING),
                            new ColumnDefinition("birth", eventType())));

            assertThat(rows.get(2).get("id").asDisplayString()).isEqualTo("I3");
            assertThat(rows.get(2).get("birth").isNull()).isTrue();
        }

        private com.darkcollective.relix.value.Value firstBirth() {
            return open("individuals",
                    schema(col("id", ScalarType.STRING),
                            new ColumnDefinition("birth", eventType())))
                    .getFirst().get("birth");
        }
    }

    @Nested
    @DisplayName("families")
    final class Families {

        @Test
        @DisplayName("a family is a hyperedge: two parents and an array of children")
        void hyperedge() {
            List<Row> rows = open("families",
                    schema(col("id", ScalarType.STRING), col("husband", ScalarType.STRING),
                            col("wife", ScalarType.STRING),
                            new ColumnDefinition("children",
                                    new ArrayType(ScalarType.STRING))));

            assertThat(rows).hasSize(1);
            Row f = rows.getFirst();
            assertThat(f.get("id").asDisplayString()).isEqualTo("F1");
            assertThat(f.get("husband").asDisplayString()).isEqualTo("I1");
            assertThat(f.get("wife").asDisplayString()).isEqualTo("I2");
            assertThat(((ArrayValue) f.get("children")).elements())
                    .extracting(v -> v.asDisplayString())
                    .containsExactly("I3", "I4");
        }
    }

    @Nested
    @DisplayName("the declared schema decides the heading")
    final class DeclaredSchema {

        @Test
        @DisplayName("a column this connector has nothing for is NULL, not an error")
        void unknownColumnIsNull() {
            // GEDCOM is extensible, so a heading that names something the file does not
            // carry is an ordinary state of affairs rather than a broken declaration.
            List<Row> rows = open("individuals",
                    schema(col("id", ScalarType.STRING), col("occupation", ScalarType.STRING)));

            assertThat(rows.getFirst().get("occupation").isNull()).isTrue();
        }

        @Test
        @DisplayName("a struct gets exactly the fields it declares")
        void structIsNarrowedToItsDeclaration() {
            List<Row> rows = open("individuals",
                    schema(col("id", ScalarType.STRING),
                            new ColumnDefinition("birth", new StructType(List.of(
                                    new StructType.Field("place", ScalarType.STRING))))));
            StructValue birth = (StructValue) rows.getFirst().get("birth");

            assertThat(birth.fields()).containsOnlyKeys("place");
        }

        @Test
        @DisplayName("an unknown table names the two that exist")
        void unknownTable() {
            assertThatThrownBy(() -> open("events", schema(col("id", ScalarType.STRING))))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("individuals")
                    .hasMessageContaining("families");
        }
    }


    @Nested
    @DisplayName("a file that is not quite as expected")
    final class Tolerance {

        /**
         * A tolerant reader's edge cases, all of which real files contain: a name with no
         * slashed surname, an empty surname, and a pointer written without its delimiting
         * {@code @}.
         */
        private static final String AWKWARD = """
                0 HEAD
                0 @I9@ INDI
                1 NAME Prince
                0 @I8@ INDI
                1 NAME John //
                0 @F9@ FAM
                1 HUSB I9
                1 WIFE @I8@
                1 CHIL @I7
                1 CHIL @@
                0 TRLR
                """;

        private List<Row> openAwkward(String table, Schema schema) throws IOException {
            Path awkward = dir.resolve("awkward.ged");
            Files.writeString(awkward, AWKWARD, StandardCharsets.UTF_8);
            try (Stream<Row> rows = new GedcomConnector().open(
                    new ConnectorConfig(Map.of("path", awkward.toString())), table, schema)) {
                return rows.toList();
            }
        }

        @Test
        @DisplayName("a name with no slashed surname has a name and a NULL surname")
        void noSurname() throws IOException {
            List<Row> rows = openAwkward("individuals",
                    schema(col("name", ScalarType.STRING), col("surname", ScalarType.STRING)));

            assertThat(rows.getFirst().get("name").asDisplayString()).isEqualTo("Prince");
            assertThat(rows.getFirst().get("surname").isNull()).isTrue();
        }

        @Test
        @DisplayName("an empty slashed surname is no surname, not an empty one")
        void emptySurname() throws IOException {
            List<Row> rows = openAwkward("individuals",
                    schema(col("name", ScalarType.STRING), col("surname", ScalarType.STRING)));

            assertThat(rows.get(1).get("name").asDisplayString()).isEqualTo("John");
            assertThat(rows.get(1).get("surname").isNull()).isTrue();
        }

        @Test
        @DisplayName("a malformed pointer is kept verbatim rather than guessed at")
        void malformedPointer() throws IOException {
            // An unterminated `@I7` and a bare `@@` are not ids this reader can strip, and
            // inventing one would be worse than handing back what the file said: the value
            // is still there to be looked at, and it simply matches no individual.
            List<Row> rows = openAwkward("families",
                    schema(new ColumnDefinition("children", new ArrayType(ScalarType.STRING))));

            assertThat(((ArrayValue) rows.getFirst().get("children")).elements())
                    .extracting(v -> v.asDisplayString())
                    .containsExactly("@I7", "@@");
        }

        @Test
        @DisplayName("a pointer written without its @ delimiters is read as the id itself")
        void undelimitedPointer() throws IOException {
            // Refusing it would reject a file other genealogy programs accept, and the
            // value is unambiguous either way.
            List<Row> rows = openAwkward("families",
                    schema(col("husband", ScalarType.STRING), col("wife", ScalarType.STRING)));

            assertThat(rows.getFirst().get("husband").asDisplayString()).isEqualTo("I9");
            assertThat(rows.getFirst().get("wife").asDisplayString()).isEqualTo("I8");
        }
    }

    @Nested
    @DisplayName("a schema that does not match the data")
    final class Mismatch {

        @Test
        @DisplayName("a scalar type over an event yields the event's own rendering")
        void scalarOverStruct() {
            // Declaring `birth: STRING` asks for a scalar where the data is nested. The
            // row is still produced — a heading is a request, and refusing one the file
            // could nearly satisfy would make an extensible format unusable.
            List<Row> rows = open("individuals",
                    schema(col("id", ScalarType.STRING), col("birth", ScalarType.STRING)));

            assertThat(rows.getFirst().get("birth").isNull()).isFalse();
        }

        @Test
        @DisplayName("a struct type over a scalar field yields that scalar, not a struct")
        void structOverScalar() {
            List<Row> rows = open("individuals",
                    schema(col("id", ScalarType.STRING),
                            new ColumnDefinition("name", new StructType(List.of(
                                    new StructType.Field("given", ScalarType.STRING))))));

            assertThat(rows.getFirst().get("name")).isNotInstanceOf(StructValue.class);
            assertThat(rows.getFirst().get("name").asDisplayString()).isEqualTo("John Smith");
        }
    }

    private static StructType eventType() {
        return new StructType(List.of(
                new StructType.Field("date", ScalarType.DATE),
                new StructType.Field("text", ScalarType.STRING),
                new StructType.Field("place", ScalarType.STRING)));
    }
}

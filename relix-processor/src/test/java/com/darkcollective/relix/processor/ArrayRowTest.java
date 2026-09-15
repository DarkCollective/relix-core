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

import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ArrayRow — positional row backed by a value array")
final class ArrayRowTest extends ProcessorTestSupport {

    private static final Schema SCHEMA =
            schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));

    // ── construction & access ──────────────────────────────────────────────────

    @Nested
    @DisplayName("construction and access")
    class Access {

        @Test
        @DisplayName("get by index and by (case-insensitive) name")
        void getByIndexAndName() {
            Row row = ArrayRow.of(SCHEMA, num(1), str("Alice"));
            assertThat(row.get(0)).isEqualTo(num(1));
            assertThat(row).hasValue("name", "Alice")
                    .hasValue("NAME", "Alice");
            assertThat(row.width()).isEqualTo(2);
        }

        @Test
        @DisplayName("get by qualified name strips the relation prefix")
        void getByQualifiedName() {
            Row row = ArrayRow.of(SCHEMA, num(1), str("Alice"));
            assertThat(row).hasValue("Users.name", "Alice");
        }

        @Test
        @DisplayName("qualified name resolves by provenance to a collided column (#454)")
        void getByQualifiedNameUsesProvenance() {
            // A joined schema: devices.name (physical `name`) + rooms.name (physical `name_r`).
            Schema joined = schema(
                    new com.darkcollective.relix.symbol.ColumnDefinition(
                            "name", com.darkcollective.relix.symbol.ScalarType.STRING,
                            new com.darkcollective.relix.symbol.ColumnProvenance("devices", "name")),
                    new com.darkcollective.relix.symbol.ColumnDefinition(
                            "name_r", com.darkcollective.relix.symbol.ScalarType.STRING,
                            new com.darkcollective.relix.symbol.ColumnProvenance("rooms", "name")));
            Row row = ArrayRow.of(joined, str("Thermostat"), str("LivingRoom"));

            assertThat(row).hasValue("devices.name", "Thermostat")
                    .hasValue("rooms.name", "LivingRoom");
            // The legacy physical names still work.
            assertThat(row).hasValue("name", "Thermostat")
                    .hasValue("name_r", "LivingRoom");
        }

        @Test
        @DisplayName("a dotted name reads a struct field even when its last segment names a column")
        void structPathBeatsTheBareNameFallback() {
            // The heading a `μ skills` produces over a profile: a top-level `name`, and
            // a `skills` struct that also has a `name`. Reading `skills.name` as a
            // relation qualifier gives the bare `name`, which is the person's — a wrong
            // answer with no diagnostic, and the reason the struct path is tried first.
            Schema profile = schema(
                    col("name", ScalarType.STRING),
                    new com.darkcollective.relix.symbol.ColumnDefinition("skills",
                            new com.darkcollective.relix.symbol.StructType(List.of(
                                    new com.darkcollective.relix.symbol.StructType.Field(
                                            "name", ScalarType.STRING),
                                    new com.darkcollective.relix.symbol.StructType.Field(
                                            "years", ScalarType.NUMBER)))));
            Row row = ArrayRow.of(profile, str("Grace"),
                    new com.darkcollective.relix.value.StructValue(
                            new java.util.LinkedHashMap<>(java.util.Map.of(
                                    "name", str("Java"), "years", num(9)))));

            assertThat(row).hasValue("skills.name", "Java")
                    .hasValue("name", "Grace");
            assertThat(row.get("skills.years")).isEqualTo(num(9));
        }

        @Test
        @DisplayName("a relation qualifier still wins over a same-named struct column")
        void provenanceIsTriedBeforeTheStructPath() {
            // A column `city` genuinely coming from `location`, beside a `location`
            // struct that also has a `city`. Both readings apply; the relation one is
            // checked first, which is the order Schema.resolvePath documents.
            Schema both = schema(
                    new com.darkcollective.relix.symbol.ColumnDefinition("city",
                            ScalarType.STRING,
                            new com.darkcollective.relix.symbol.ColumnProvenance("location", "city")),
                    new com.darkcollective.relix.symbol.ColumnDefinition("location",
                            new com.darkcollective.relix.symbol.StructType(List.of(
                                    new com.darkcollective.relix.symbol.StructType.Field(
                                            "city", ScalarType.STRING)))));
            Row row = ArrayRow.of(both, str("from provenance"),
                    new com.darkcollective.relix.value.StructValue(
                            java.util.Map.of("city", str("from the struct"))));

            assertThat(row).hasValue("location.city", "from provenance");
        }

        @Test
        @DisplayName("a dotted name whose path misses falls back to the bare column")
        void staleQualifierStillFallsBackToTheBareName() {
            // `location` names a column here but carries no `postcode` field, so the
            // path does not resolve and the legacy bare-name reading answers — which is
            // what a qualified reference over a schema with no provenance relies on.
            Schema profile = schema(
                    col("postcode", ScalarType.STRING),
                    new com.darkcollective.relix.symbol.ColumnDefinition("location",
                            new com.darkcollective.relix.symbol.StructType(List.of(
                                    new com.darkcollective.relix.symbol.StructType.Field(
                                            "city", ScalarType.STRING)))));
            Row row = ArrayRow.of(profile, str("CB1"),
                    new com.darkcollective.relix.value.StructValue(
                            java.util.Map.of("city", str("Cambridge"))));

            assertThat(row).hasValue("location.postcode", "CB1");
        }

        @Test
        @DisplayName("a column whose own name contains a dot wins before either reading")
        void wholeNameWinsFirst() {
            Schema delimited = schema(col("a.b", ScalarType.STRING), col("b", ScalarType.STRING));
            Row row = ArrayRow.of(delimited, str("the dotted column"), str("the bare one"));

            assertThat(row).hasValue("a.b", "the dotted column");
        }

        @Test
        @DisplayName("unknown column name throws")
        void unknownColumnThrows() {
            Row row = ArrayRow.of(SCHEMA, num(1), str("Alice"));
            assertThatThrownBy(() -> row.get("missing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing");
        }

        @Test
        @DisplayName("value count must match schema width")
        void widthMismatchThrows() {
            assertThatThrownBy(() -> ArrayRow.of(SCHEMA, num(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("width");
        }
    }

    // ── immutability (no aliasing of caller-supplied values) ────────────────────

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("mutating the source list after of(List) does not affect the row")
        void sourceListIsNotAliased() {
            List<Value> values = new ArrayList<>(List.of(num(1), str("Alice")));
            Row row = ArrayRow.of(SCHEMA, values);
            values.set(1, str("Mallory"));
            assertThat(row).hasValue("name", "Alice");
        }

        @Test
        @DisplayName("mutating the source array after of(varargs) does not affect the row")
        void sourceArrayIsNotAliased() {
            Value[] values = { num(1), str("Alice") };
            Row row = ArrayRow.of(SCHEMA, values);
            values[1] = str("Mallory");
            assertThat(row).hasValue("name", "Alice");
        }
    }

    // ── withSchema (metadata-only re-labelling) ─────────────────────────────────

    @Nested
    @DisplayName("withSchema")
    class WithSchema {

        @Test
        @DisplayName("re-labels columns while preserving values and width")
        void relabelsPreservingValues() {
            ArrayRow original = ArrayRow.of(SCHEMA, num(1), str("Alice"));
            Schema renamed = schema(col("uid", ScalarType.NUMBER), col("uname", ScalarType.STRING));

            ArrayRow result = original.withSchema(renamed);

            assertThat(result.schema()).isEqualTo(renamed);
            assertThat(result.get("uid")).isEqualTo(num(1));
            assertThat(result).hasValue("uname", "Alice");
            assertThat(result.get(0)).isEqualTo(num(1));
            assertThat(result.get(1).asDisplayString()).isEqualTo("Alice");
        }

        @Test
        @DisplayName("does not mutate the original row")
        void originalUnchanged() {
            ArrayRow original = ArrayRow.of(SCHEMA, num(1), str("Alice"));
            original.withSchema(schema(col("uid", ScalarType.NUMBER), col("uname", ScalarType.STRING)));
            assertThat(original.schema()).isEqualTo(SCHEMA);
            assertThat(original).hasValue("name", "Alice");
        }

        @Test
        @DisplayName("rejects a schema of a different width")
        void widthMismatchThrows() {
            ArrayRow original = ArrayRow.of(SCHEMA, num(1), str("Alice"));
            Schema narrower = schema(col("only", ScalarType.NUMBER));
            assertThatThrownBy(() -> original.withSchema(narrower))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("width");
        }
    }
}

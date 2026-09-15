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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a declared nested column buys, once the grammar can spell one.
 *
 * <p>{@code ANY} was the only route before, and it works — the connectors read a
 * sub-document as a struct either way. What it cannot do is <em>type</em> anything: a path
 * through an {@code ANY} column infers as {@code ANY}, so nothing downstream knows what it
 * holds and no misuse is caught. These tests are the difference, stated as inferred types
 * rather than as prose.
 */
@DisplayName("A declared nested column types the paths through it")
final class NestedColumnInferenceTest {

    private static final String DOCS = """
            connection mg from mongodb { uri: "mongodb://h", database: "app" };
            source Docs from mg {
                table: "docs",
                schema: {
                    id: NUMBER,
                    addr: { city: STRING, geo: { lat: NUMBER, lon: NUMBER } },
                    tags: [STRING],
                    items: [{ sku: STRING, qty: NUMBER }]
                }
            };
            """;

    /** The inferred output schema of the first query's expression. */
    private static Schema outputOf(String query) {
        SemanticModel model = model(DOCS + query);
        RelNode node = ((ExpressionQueryTarget) model.rootQueries().getFirst().target())
                .expression();
        return model.nodeSchemas().get(node).orElseThrow();
    }

    private static Type typeOf(String query, String column) {
        return outputOf(query).column(column).orElseThrow().type();
    }

    @Nested
    @DisplayName("the declaration reaches the symbol table")
    final class Declaration {

        @Test
        @DisplayName("a struct column is a StructType, not ANY")
        void structColumnIsTyped() {
            Schema schema = model(DOCS + "query Docs;")
                    .symbolTable().lookupRelation("Docs").orElseThrow().schema();

            assertThat(schema.column("addr").orElseThrow().type())
                    .isEqualTo(new StructType(List.of(
                            new StructType.Field("city", ScalarType.STRING),
                            new StructType.Field("geo", new StructType(List.of(
                                    new StructType.Field("lat", ScalarType.NUMBER),
                                    new StructType.Field("lon", ScalarType.NUMBER)))))));
        }

        @Test
        @DisplayName("an array column is an ArrayType of its element")
        void arrayColumnIsTyped() {
            Schema schema = model(DOCS + "query Docs;")
                    .symbolTable().lookupRelation("Docs").orElseThrow().schema();

            assertThat(schema.column("tags").orElseThrow().type())
                    .isEqualTo(new ArrayType(ScalarType.STRING));
        }
    }

    @Nested
    @DisplayName("inference through a path")
    final class Paths {

        @Test
        @DisplayName("a field's own type flows out of a projection")
        void projectedFieldKeepsItsType() {
            // The whole point of #684: through an ANY column this is ANY, and nothing
            // downstream — a comparison, an aggregate, a pushdown renderer — can know what
            // it is dealing with.
            assertThat(typeOf("query { π addr.city → city (Docs) };", "city"))
                    .isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("a path through two levels still types")
        void deepPathKeepsItsType() {
            assertThat(typeOf("query { π addr.geo.lat → lat (Docs) };", "lat"))
                    .isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("an intermediate struct projects as a struct")
        void intermediateStructKeepsItsShape() {
            assertThat(typeOf("query { π addr.geo → geo (Docs) };", "geo"))
                    .isEqualTo(new StructType(List.of(
                            new StructType.Field("lat", ScalarType.NUMBER),
                            new StructType.Field("lon", ScalarType.NUMBER))));
        }
    }

    @Nested
    @DisplayName("unnesting a declared array")
    final class Unnesting {

        @Test
        @DisplayName("μ over an array column yields its element type")
        void unnestYieldsTheElementType() {
            assertThat(typeOf("query { μ tags (Docs) };", "tags"))
                    .as("μ replaces the array column with one element, so it takes the "
                        + "element's type — ANY only when the declaration could not say")
                    .isEqualTo(ScalarType.STRING);
        }

        @Test
        @DisplayName("μ over an array of structs yields the struct")
        void unnestYieldsAStructElement() {
            assertThat(typeOf("query { μ items (Docs) };", "items"))
                    .isEqualTo(new StructType(List.of(
                            new StructType.Field("sku", ScalarType.STRING),
                            new StructType.Field("qty", ScalarType.NUMBER))));
        }

        @Test
        @DisplayName("a field of an unnested struct is reachable and typed")
        void fieldOfAnUnnestedElement() {
            // The shape a document store actually has: an array of sub-documents, unnested
            // and then read field by field. Untyped end to end before this.
            assertThat(typeOf("query { π items.sku → sku (μ items (Docs)) };", "sku"))
                    .isEqualTo(ScalarType.STRING);
        }
    }

    /**
     * A dotted name is ambiguous, and these are the cases where the two readings collide.
     *
     * <p>{@code skills.name} can mean "the column {@code name} coming from the relation
     * {@code skills}" or "the field {@code name} of the column {@code skills}". Both
     * spellings are legal Relix and the schema is the only thing that can tell them
     * apart — so a path whose last segment happens to name a column has to resolve
     * exactly as one whose last segment does not. It used to be refused instead.
     */
    @Nested
    @DisplayName("a path whose leaf name collides with a column")
    final class NameCollisions {

        private static final String PEOPLE = """
                connection mg from mongodb { uri: "mongodb://h", database: "app" };
                source People from mg {
                    table: "people",
                    schema: {
                        name: STRING,
                        level: STRING,
                        location: { city: STRING },
                        skills: [{ name: STRING, level: NUMBER, years: NUMBER }]
                    }
                };
                """;

        @Test
        @DisplayName("π resolves the path rather than reporting a stale qualifier")
        void projectionResolvesTheCollidingPath() {
            // `name` is both a column of People and a field of a skill. Read as a
            // relation qualifier, `skills.name` finds the bare `name` present and reads
            // as a stale qualifier; read as a path, it is the skill's name — which is
            // what it says.
            SemanticResult result = analyze(PEOPLE
                    + "query { π skills.name → s (μ skills (People)) };");

            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("σ resolves the path rather than reporting a stale qualifier")
        void selectionResolvesTheCollidingPath() {
            SemanticResult result = analyze(PEOPLE
                    + "query { σ skills.name = \"Java\" (μ skills (People)) };");

            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("the resolved path takes the field's type, not the column's")
        void theCollidingPathTypesAsTheField() {
            // A person has a seniority `level` (a string); a skill has a proficiency
            // `level` (a number). The two readings of `skills.level` therefore differ in
            // type as well as in value, which is the sharpest form the claim takes.
            SemanticModel model = model(PEOPLE
                    + "query { π skills.level → l (μ skills (People)) };");
            RelNode node = ((ExpressionQueryTarget) model.rootQueries().getFirst().target())
                    .expression();
            assertThat(model.nodeSchemas().get(node).orElseThrow()
                    .column("l").orElseThrow().type())
                    .isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("a genuinely stale qualifier is still refused")
        void aStaleQualifierIsStillAnError() {
            // The complement, and the reason the path reading is tried second rather
            // than instead: `orders.name` names no column of this heading under either
            // reading, and must stay an error.
            SemanticResult result = analyze(PEOPLE
                    + "query { π orders.name → s (μ skills (People)) };");

            assertThat(result.errors()).isNotEmpty();
        }

        @Test
        @DisplayName("a sort key may be a path into a nested column")
        void sortKeyResolvesAPath() {
            // τ validated its key by stripping the qualifier and looking the tail up, so
            // this was refused with a diagnostic naming a column ('years') the query
            // never wrote.
            SemanticResult result = analyze(PEOPLE
                    + "query { τ skills.years (μ skills (People)) };");

            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("a sort key naming nothing is still refused")
        void sortKeyOnAMissingColumnIsStillAnError() {
            SemanticResult result = analyze(PEOPLE
                    + "query { τ skills.tenure (μ skills (People)) };");

            assertThat(result.errors()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("what a declaration now catches")
    final class Diagnostics {

        @Test
        @DisplayName("a field the struct does not declare is an error, not ANY")
        void unknownFieldIsDiagnosed() {
            // Through an ANY column this analyses cleanly and fails at runtime, or worse,
            // returns NULL for every row. A declaration is what turns it into a message.
            SemanticResult result = analyze(DOCS + "query { π addr.postcode → p (Docs) };");

            assertThat(result.errors())
                    .as("a misspelled field must be caught where a misspelled column is")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("an ANY column still accepts any path, as it must")
        void anyColumnStaysPermissive() {
            // The complement, and the reason ANY is still the right default for a source
            // whose shape is not known: schema-on-read means the analyzer cannot object.
            SemanticResult result = analyze("""
                    connection mg from mongodb { uri: "mongodb://h", database: "app" };
                    source Loose from mg { table: "docs", schema: { blob: ANY } };
                    query { π blob.whatever → w (Loose) };
                    """);

            assertThat(result.errors()).isEmpty();
        }
    }
}

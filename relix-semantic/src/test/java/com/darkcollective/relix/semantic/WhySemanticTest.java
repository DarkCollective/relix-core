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
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for WHY (ω) schema inference and validation (ADR-0018, issue #319). */
@DisplayName("WHY — schema inference and validation")
final class WhySemanticTest {

    private InMemorySymbolTable table;
    private SchemaAnnotations   annotations;

    @BeforeEach
    void setUp() {
        table       = new InMemorySymbolTable();
        annotations = new SchemaAnnotations();

        // Orders(order_id: NUMBER, region: STRING, amount: NUMBER)
        table.register(new SourceRelationSymbol("default", "Orders", Provenance.BUILTIN,
                ShadowPolicy.PERMITTED, new Schema(List.of(
                        col("order_id", ScalarType.NUMBER),
                        col("region",   ScalarType.STRING),
                        col("amount",   ScalarType.NUMBER)))));

        // Clashing(order_id: NUMBER, provenance: STRING) — already carries the reserved name.
        table.register(new SourceRelationSymbol("default", "Clashing", Provenance.BUILTIN,
                ShadowPolicy.PERMITTED, new Schema(List.of(
                        col("order_id",   ScalarType.NUMBER),
                        col("provenance", ScalarType.STRING)))));

        // Open(*: ANY) — schema-on-read.
        table.register(new SourceRelationSymbol("default", "Open", Provenance.BUILTIN,
                ShadowPolicy.PERMITTED, Schema.open()));
    }

    private static ColumnDefinition col(String name, ScalarType type) {
        return new ColumnDefinition(name, type);
    }

    private SchemaInferenceVisitor visitor() {
        var errors = new ArrayList<SemanticError>();
        return new SchemaInferenceVisitor(table, annotations, errors, "<test>",
                SemanticFixtures.FUNCTIONS);
    }

    private List<SemanticError> inferAndValidate(RelNode tree) {
        var inferErrors = new ArrayList<SemanticError>();
        tree.accept(new SchemaInferenceVisitor(table, annotations, inferErrors, "<test>",
                SemanticFixtures.FUNCTIONS));
        var valErrors = new ArrayList<SemanticError>();
        tree.accept(new RelAlgebraValidator(table, annotations, SemanticFixtures.FUNCTIONS,
                valErrors, "<test>"));
        return valErrors;
    }

    @Nested
    @DisplayName("Schema inference")
    class SchemaInferenceTests {

        @Test
        @DisplayName("Appends the ANY provenance column to the input schema, last")
        void appendsProvenanceColumn() {
            Optional<Schema> result = why(rel("Orders")).accept(visitor());

            assertThat(result).isPresent();
            Schema schema = result.get();
            assertThat(schema.column("order_id")).isPresent();
            assertThat(schema.column("region")).isPresent();
            assertThat(schema.column("amount")).isPresent();
            assertThat(schema.column("provenance")).isPresent();
            assertThat(schema.column("provenance").get().type()).isEqualTo(ScalarType.ANY);
            assertThat(schema.columns().get(schema.width() - 1).name()).isEqualTo("provenance");
            assertThat(schema.width()).isEqualTo(4);
        }

        @Test
        @DisplayName("An open (schema-on-read) input stays open")
        void openInputStaysOpen() {
            Optional<Schema> result = why(rel("Open")).accept(visitor());
            assertThat(result).isPresent();
            assertThat(result.get().isOpen()).isTrue();
        }

        @Test
        @DisplayName("A provenance-name clash with an existing column skips the append")
        void nameClashSkipsAppend() {
            Optional<Schema> result = why(rel("Clashing")).accept(visitor());
            assertThat(result).isPresent();
            // still 2 columns — the clashing append was skipped (validator reports it)
            assertThat(result.get().width()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("A well-formed WHY produces no errors")
        void valid() {
            assertThat(inferAndValidate(why(rel("Orders")))).isEmpty();
        }

        @Test
        @DisplayName("An input already carrying the reserved provenance column is reported")
        void reservedClash() {
            assertThat(inferAndValidate(why(rel("Clashing"))))
                    .anySatisfy(e -> assertThat(e.message())
                            .contains("reserved column 'provenance'"));
        }

        @Test
        @DisplayName("An open input defers the reserved-name check to runtime (no error)")
        void openInputNoError() {
            assertThat(inferAndValidate(why(rel("Open")))).isEmpty();
        }
    }
}

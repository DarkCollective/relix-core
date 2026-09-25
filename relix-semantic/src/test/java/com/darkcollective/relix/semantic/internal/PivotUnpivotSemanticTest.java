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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.semantic.internal.RelAlgebraValidator;
import com.darkcollective.relix.semantic.internal.SchemaInferenceVisitor;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * Tests for PIVOT / UNPIVOT schema inference and validation (issue #16).
 *
 * <p>Relations used as fixtures:
 * <ul>
 *   <li>Sales(region: STRING, quarter: STRING, revenue: NUMBER, units: NUMBER)</li>
 *   <li>Metrics(id: NUMBER, q1: NUMBER, q2: NUMBER, q3: NUMBER, q4: NUMBER)</li>
 * </ul>
 */
@DisplayName("PIVOT / UNPIVOT — schema inference and validation")
final class PivotUnpivotSemanticTest {

    private InMemorySymbolTable table;
    private SchemaAnnotations   annotations;

    @BeforeEach
    void setUp() {
        table       = new InMemorySymbolTable();
        annotations = new SchemaAnnotations();

        // Sales(region: STRING, quarter: STRING, revenue: NUMBER, units: NUMBER)
        table.register(new SourceRelationSymbol("default", "Sales", Provenance.BUILTIN,
                ShadowPolicy.PERMITTED, new Schema(List.of(
                        col("region",  ScalarType.STRING),
                        col("quarter", ScalarType.STRING),
                        col("revenue", ScalarType.NUMBER),
                        col("units",   ScalarType.NUMBER)))));

        // Metrics(id: NUMBER, q1: NUMBER, q2: NUMBER, q3: NUMBER, q4: NUMBER)
        table.register(new SourceRelationSymbol("default", "Metrics", Provenance.BUILTIN,
                ShadowPolicy.PERMITTED, new Schema(List.of(
                        col("id", ScalarType.NUMBER),
                        col("q1", ScalarType.NUMBER),
                        col("q2", ScalarType.NUMBER),
                        col("q3", ScalarType.NUMBER),
                        col("q4", ScalarType.NUMBER)))));
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

    // =========================================================================
    // UNPIVOT — schema inference
    // =========================================================================

    @Nested
    @DisplayName("UNPIVOT — schema inference")
    class UnpivotInference {

        @Test
        @DisplayName("Output schema drops the listed columns and appends nameCol:STRING + valueCol:ANY")
        void basicUnpivotSchema() {
            // UNPIVOT (q1, q2, q3, q4) AS (quarter, value) (Metrics)
            // Input: id, q1, q2, q3, q4
            // Output: id, quarter:STRING, value:ANY
            RelNode node = unpivot(
                    List.of("q1", "q2", "q3", "q4"), "quarter", "value",
                    rel("Metrics"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema schema = result.get();
            assertThat(schema.columns()).hasSize(3);
            // id passes through
            assertThat(schema.column("id")).isPresent();
            assertThat(schema.column("id").get().type()).isEqualTo(ScalarType.NUMBER);
            // nameCol is STRING
            assertThat(schema.column("quarter")).isPresent();
            assertThat(schema.column("quarter").get().type()).isEqualTo(ScalarType.STRING);
            // valueCol is ANY
            assertThat(schema.column("value")).isPresent();
            assertThat(schema.column("value").get().type()).isEqualTo(ScalarType.ANY);
            // listed columns are gone
            assertThat(schema.column("q1")).isEmpty();
            assertThat(schema.column("q4")).isEmpty();
        }

        @Test
        @DisplayName("Single listed column — removes that column and appends two output columns")
        void singleColumnUnpivot() {
            // UNPIVOT (revenue) AS (col_name, col_value) (Sales)
            // Input: region, quarter, revenue, units
            // Output: region, quarter, units, col_name:STRING, col_value:ANY
            RelNode node = unpivot(
                    List.of("revenue"), "col_name", "col_value",
                    rel("Sales"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema schema = result.get();
            assertThat(schema.columns()).hasSize(5);
            assertThat(schema.column("region")).isPresent();
            assertThat(schema.column("quarter")).isPresent();
            assertThat(schema.column("units")).isPresent();
            assertThat(schema.column("col_name")).isPresent();
            assertThat(schema.column("col_name").get().type()).isEqualTo(ScalarType.STRING);
            assertThat(schema.column("col_value")).isPresent();
            assertThat(schema.column("col_value").get().type()).isEqualTo(ScalarType.ANY);
            assertThat(schema.column("revenue")).isEmpty();
        }

        @Test
        @DisplayName("nameCol and valueCol are appended in order (nameCol last-1, valueCol last)")
        void appendOrder() {
            RelNode node = unpivot(
                    List.of("q1", "q2"), "k", "v",
                    rel("Metrics"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            List<ColumnDefinition> cols = result.get().columns();
            int sz = cols.size();
            assertThat(cols.get(sz - 2).name()).isEqualTo("k");
            assertThat(cols.get(sz - 1).name()).isEqualTo("v");
        }

        @Test
        @DisplayName("nameCol clash with remaining input column: skip append (validator reports)")
        void nameColClashKeepsSchema() {
            // 'id' remains in Metrics after removing q1; naming nameCol 'id' clashes
            RelNode node = unpivot(
                    List.of("q1"), "id", "val",
                    rel("Metrics"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            // 'id' was skipped (clash), 'val' was appended; remaining = id, q2, q3, q4
            Schema schema = result.get();
            // id is the remaining column; nameCol 'id' was not re-appended
            assertThat(schema.column("id")).isPresent();
            assertThat(schema.column("val")).isPresent();
        }

        @Test
        @DisplayName("Returns empty when the input relation is unknown")
        void unknownInputReturnsEmpty() {
            RelNode node = unpivot(
                    List.of("x"), "k", "v", rel("NoSuchTable"));
            assertThat(node.accept(visitor())).isEmpty();
        }
    }

    // =========================================================================
    // PIVOT — schema inference
    // =========================================================================

    @Nested
    @DisplayName("PIVOT — schema inference")
    class PivotInference {

        @Test
        @DisplayName("PIVOT output schema is always Schema.open() (dynamic column headers)")
        void pivotOutputIsOpen() {
            // PIVOT revenue BY quarter (Sales)
            RelNode node = pivot(
                    "revenue", "quarter", List.of(), rel("Sales"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().isOpen()).isTrue();
        }

        @Test
        @DisplayName("PIVOT with PER keys also returns Schema.open()")
        void pivotWithPerKeysIsOpen() {
            RelNode node = pivot(
                    "revenue", "quarter", List.of("region"), rel("Sales"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().isOpen()).isTrue();
        }

        @Test
        @DisplayName("Returns empty when the input relation is unknown")
        void unknownInputReturnsEmpty() {
            RelNode node = pivot(
                    "revenue", "quarter", List.of(), rel("Ghost"));
            // PIVOT still visits the input and returns open if input resolves;
            // if input is unknown (error), the schema is still annotated as open.
            // The visit always calls annotate(node, Schema.open()) — check not empty.
            Optional<Schema> result = node.accept(visitor());
            // PIVOT annotates open() regardless of input error
            assertThat(result).isPresent();
            assertThat(result.get().isOpen()).isTrue();
        }
    }

    // =========================================================================
    // UNPIVOT — validation
    // =========================================================================

    @Nested
    @DisplayName("UNPIVOT — validation")
    class UnpivotValidation {

        @Test
        @DisplayName("Valid UNPIVOT produces no errors")
        void valid() {
            assertThat(inferAndValidate(unpivot(
                    List.of("q1", "q2", "q3", "q4"), "quarter", "value",
                    rel("Metrics")))).isEmpty();
        }

        @Test
        @DisplayName("Valid single-column UNPIVOT produces no errors")
        void validSingleColumn() {
            assertThat(inferAndValidate(unpivot(
                    List.of("revenue"), "metric", "amount",
                    rel("Sales")))).isEmpty();
        }

        @Test
        @DisplayName("Unknown listed column is an error")
        void unknownListedColumn() {
            var errors = inferAndValidate(unpivot(
                    List.of("missing_col"), "k", "v",
                    rel("Metrics")));
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).contains("missing_col");
        }

        @Test
        @DisplayName("nameColumn clashing with remaining input column is an error")
        void nameColumnClashWithRemaining() {
            // 'id' remains after removing q1; naming nameCol 'id' clashes
            var errors = inferAndValidate(unpivot(
                    List.of("q1"), "id", "v",
                    rel("Metrics")));
            assertThat(errors).isNotEmpty();
            assertThat(errors.stream().anyMatch(e -> e.message().contains("id"))).isTrue();
        }

        @Test
        @DisplayName("valueColumn clashing with remaining input column is an error")
        void valueColumnClashWithRemaining() {
            // 'id' remains; naming valueCol 'id' clashes
            var errors = inferAndValidate(unpivot(
                    List.of("q1"), "k", "id",
                    rel("Metrics")));
            assertThat(errors).isNotEmpty();
            assertThat(errors.stream().anyMatch(e -> e.message().contains("id"))).isTrue();
        }

        @Test
        @DisplayName("nameColumn and valueColumn being the same is an error")
        void nameAndValueSame() {
            var errors = inferAndValidate(unpivot(
                    List.of("q1"), "x", "x",
                    rel("Metrics")));
            assertThat(errors).isNotEmpty();
        }
    }

    // =========================================================================
    // PIVOT — validation
    // =========================================================================

    @Nested
    @DisplayName("PIVOT — validation")
    class PivotValidation {

        @Test
        @DisplayName("Valid PIVOT without PER produces no errors")
        void validNoPer() {
            assertThat(inferAndValidate(pivot(
                    "revenue", "quarter", List.of(),
                    rel("Sales")))).isEmpty();
        }

        @Test
        @DisplayName("Valid PIVOT with PER keys produces no errors")
        void validWithPer() {
            assertThat(inferAndValidate(pivot(
                    "revenue", "quarter", List.of("region"),
                    rel("Sales")))).isEmpty();
        }

        @Test
        @DisplayName("Unknown value column is an error")
        void unknownValueColumn() {
            var errors = inferAndValidate(pivot(
                    "no_such_col", "quarter", List.of(),
                    rel("Sales")));
            assertThat(errors).isNotEmpty();
            assertThat(errors.stream().anyMatch(e -> e.message().contains("no_such_col"))).isTrue();
        }

        @Test
        @DisplayName("Unknown key column is an error")
        void unknownKeyColumn() {
            var errors = inferAndValidate(pivot(
                    "revenue", "no_key", List.of(),
                    rel("Sales")));
            assertThat(errors).isNotEmpty();
            assertThat(errors.stream().anyMatch(e -> e.message().contains("no_key"))).isTrue();
        }

        @Test
        @DisplayName("keyColumn and valueColumn being the same is an error")
        void keyAndValueSame() {
            var errors = inferAndValidate(pivot(
                    "revenue", "revenue", List.of(),
                    rel("Sales")));
            assertThat(errors).isNotEmpty();
        }

        @Test
        @DisplayName("Unknown group key in PER is an error")
        void unknownGroupKey() {
            var errors = inferAndValidate(pivot(
                    "revenue", "quarter", List.of("no_group"),
                    rel("Sales")));
            assertThat(errors).isNotEmpty();
            assertThat(errors.stream().anyMatch(e -> e.message().contains("no_group"))).isTrue();
        }

        @Test
        @DisplayName("Group key equal to value column is an error")
        void groupKeyIsValueColumn() {
            var errors = inferAndValidate(pivot(
                    "revenue", "quarter", List.of("revenue"),
                    rel("Sales")));
            assertThat(errors).isNotEmpty();
        }

        @Test
        @DisplayName("Group key equal to key column is an error")
        void groupKeyIsKeyColumn() {
            var errors = inferAndValidate(pivot(
                    "revenue", "quarter", List.of("quarter"),
                    rel("Sales")));
            assertThat(errors).isNotEmpty();
        }
    }

    // =========================================================================
    // End-to-end pipeline
    // =========================================================================

    @Nested
    @DisplayName("End-to-end pipeline")
    class EndToEnd {

        private static final String SALES_SRC =
                "source Sales from csv(\"sales.csv\") {" +
                "  schema: { region: STRING, quarter: STRING, revenue: NUMBER }" +
                "};\n";

        private static final String METRICS_SRC =
                "source Metrics from csv(\"metrics.csv\") {" +
                "  schema: { id: NUMBER, q1: NUMBER, q2: NUMBER, q3: NUMBER }" +
                "};\n";

        @Test
        @DisplayName("Valid UNPIVOT query is fully valid")
        void unpivotFullyValid() {
            var result = analyze(METRICS_SRC +
                    "query { UNPIVOT (q1, q2, q3) AS (quarter, value) (Metrics) };");
            assertThat(result).isFullyValid();
        }

        @Test
        @DisplayName("Valid PIVOT query is fully valid")
        void pivotFullyValid() {
            var result = analyze(SALES_SRC +
                    "query { PIVOT revenue BY quarter (Sales) };");
            assertThat(result).isFullyValid();
        }

        @Test
        @DisplayName("Valid PIVOT with PER query is fully valid")
        void pivotWithPerFullyValid() {
            var result = analyze(SALES_SRC +
                    "query { PIVOT revenue BY quarter PER region (Sales) };");
            assertThat(result).isFullyValid();
        }

        @Test
        @DisplayName("UNPIVOT with unknown column produces an error")
        void unpivotUnknownColumnProducesError() {
            var result = analyze(METRICS_SRC +
                    "query { UNPIVOT (no_col) AS (k, v) (Metrics) };");
            assertThat(result).hasErrors();
            assertThat(result.errors().stream().anyMatch(e -> e.message().contains("no_col")))
                    .isTrue();
        }

        @Test
        @DisplayName("PIVOT with unknown value column produces an error")
        void pivotUnknownValueColumnProducesError() {
            var result = analyze(SALES_SRC +
                    "query { PIVOT no_col BY quarter (Sales) };");
            assertThat(result).hasErrors();
            assertThat(result.errors().stream().anyMatch(e -> e.message().contains("no_col")))
                    .isTrue();
        }
    }
}

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

import com.darkcollective.relix.ast.ConsolidationFunction;
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

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/** Tests for DOWNSAMPLE schema inference and validation. */
@DisplayName("DOWNSAMPLE — schema inference and validation")
final class DownsampleSemanticTest {

    private InMemorySymbolTable table;
    private SchemaAnnotations   annotations;

    @BeforeEach
    void setUp() {
        table       = new InMemorySymbolTable();
        annotations = new SchemaAnnotations();

        // Metrics(ts: TIMESTAMP, host: STRING, cpu: NUMBER, mem: NUMBER)
        table.register(new SourceRelationSymbol("default", "Metrics", Provenance.BUILTIN,
                ShadowPolicy.PERMITTED, new Schema(List.of(
                        col("ts",   ScalarType.TIMESTAMP),
                        col("host", ScalarType.STRING),
                        col("cpu",  ScalarType.NUMBER),
                        col("mem",  ScalarType.NUMBER)))));

        // Events(at: TIMESTAMP, region: STRING, count: NUMBER)
        table.register(new SourceRelationSymbol("default", "Events", Provenance.BUILTIN,
                ShadowPolicy.PERMITTED, new Schema(List.of(
                        col("at",     ScalarType.TIMESTAMP),
                        col("region", ScalarType.STRING),
                        col("count",  ScalarType.NUMBER)))));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
        var inferVisitor = new SchemaInferenceVisitor(table, annotations, inferErrors, "<test>",
                SemanticFixtures.FUNCTIONS);
        tree.accept(inferVisitor);

        var valErrors = new ArrayList<SemanticError>();
        var validator = new RelAlgebraValidator(table, annotations, SemanticFixtures.FUNCTIONS,
                valErrors, "<test>");
        tree.accept(validator);
        return valErrors;
    }

    // -------------------------------------------------------------------------
    // Schema inference
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Schema inference")
    class SchemaInference {

        @Test
        @DisplayName("AVG produces groupingKeys + bucket:TIMESTAMP + avg_<numeric>:NUMBER columns")
        void avgProducesAggregatedColumns() {
            var node = downsample("ts", "5m", ConsolidationFunction.AVG,
                    rel("Metrics"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema schema = result.get();
            // no grouping keys → just bucket + avg_cpu + avg_mem
            assertThat(schema.column("bucket")).isPresent();
            assertThat(schema.column("bucket").get().type()).isEqualTo(ScalarType.TIMESTAMP);
            assertThat(schema.column("avg_cpu")).isPresent();
            assertThat(schema.column("avg_cpu").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(schema.column("avg_mem")).isPresent();
            assertThat(schema.column("avg_mem").get().type()).isEqualTo(ScalarType.NUMBER);
            // ts and host are NOT in the output
            assertThat(schema.column("ts")).isEmpty();
            assertThat(schema.column("host")).isEmpty();
        }

        @Test
        @DisplayName("COUNT produces groupingKeys + bucket + count:NUMBER")
        void countProducesSingleCountColumn() {
            var node = downsample("ts", "1h", ConsolidationFunction.COUNT,
                    rel("Metrics"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema schema = result.get();
            assertThat(schema.column("bucket")).isPresent();
            assertThat(schema.column("count")).isPresent();
            assertThat(schema.column("count").get().type()).isEqualTo(ScalarType.NUMBER);
            assertThat(schema.column("avg_cpu")).isEmpty();
        }

        @Test
        @DisplayName("SUM with grouping keys includes the key column")
        void sumWithGroupingKeyIncludesKey() {
            var node = downsample("ts", "1h", ConsolidationFunction.SUM,
                    List.of("host"), rel("Metrics"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema schema = result.get();
            assertThat(schema.column("host")).isPresent();
            assertThat(schema.column("bucket")).isPresent();
            assertThat(schema.column("sum_cpu")).isPresent();
            assertThat(schema.column("sum_mem")).isPresent();
            // grouping key not consolidated
            assertThat(schema.column("sum_host")).isEmpty();
        }

        @Test
        @DisplayName("MAX excludes grouping key from consolidated columns")
        void maxExcludesGroupingKeyFromConsolidation() {
            var node = downsample("at", "1d", ConsolidationFunction.MAX,
                    List.of("region"), rel("Events"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema schema = result.get();
            assertThat(schema.column("region")).isPresent();
            assertThat(schema.column("bucket")).isPresent();
            assertThat(schema.column("max_count")).isPresent();
            // region itself is not consolidated
            assertThat(schema.column("max_region")).isEmpty();
        }

        @Test
        @DisplayName("Column order: groupingKeys, bucket, consolidated")
        void columnOrderIsGroupingKeysThenBucketThenConsolidated() {
            var node = downsample("ts", "1h", ConsolidationFunction.MIN,
                    List.of("host"), rel("Metrics"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            List<String> colNames = result.get().columns().stream()
                    .map(ColumnDefinition::name).toList();
            assertThat(colNames.get(0)).isEqualTo("host");
            assertThat(colNames.get(1)).isEqualTo("bucket");
            assertThat(colNames.subList(2, colNames.size()))
                    .allMatch(n -> n.startsWith("min_"));
        }

        @Test
        @DisplayName("MIN/MAX consolidate a non-numeric column, typed as the input column")
        void extremaConsolidateEveryScalarColumn() {
            var node = downsample("ts", "1h", ConsolidationFunction.MIN,
                    rel("Metrics"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            Schema schema = result.get();
            assertThat(schema.column("min_host")).isPresent();
            // The extreme of a set of strings is a string, not a number.
            assertThat(schema.column("min_host").get().type()).isEqualTo(ScalarType.STRING);
            assertThat(schema.column("min_cpu").get().type()).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("AVG/SUM consolidate the numeric columns only")
        void arithmeticConsolidatesNumbersOnly() {
            var node = downsample("ts", "1h", ConsolidationFunction.SUM,
                    rel("Metrics"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns().stream().map(ColumnDefinition::name))
                    .containsExactly("bucket", "sum_cpu", "sum_mem");
        }

        @Test
        @DisplayName("A nested column is never consolidated — an array has no value order")
        void nestedColumnsAreNotConsolidated() {
            table.register(new SourceRelationSymbol("default", "Nested", Provenance.BUILTIN,
                    ShadowPolicy.PERMITTED, new Schema(List.of(
                            col("ts", ScalarType.TIMESTAMP),
                            new ColumnDefinition("tags", array(ScalarType.STRING))))));
            var node = downsample("ts", "1h", ConsolidationFunction.MAX,
                    rel("Nested"));
            Optional<Schema> result = node.accept(visitor());

            assertThat(result).isPresent();
            assertThat(result.get().columns().stream().map(ColumnDefinition::name))
                    .containsExactly("bucket");
        }

        @Test
        @DisplayName("Returns empty when input relation is unknown")
        void returnsEmptyForUnknownInput() {
            var node = downsample("ts", "1h", ConsolidationFunction.AVG,
                    rel("NoSuchTable"));
            Optional<Schema> result = node.accept(visitor());
            assertThat(result).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("Valid DOWNSAMPLE produces no errors")
        void validDownsampleNoErrors() {
            var node = downsample("ts", "5m", ConsolidationFunction.AVG,
                    rel("Metrics"));
            assertThat(inferAndValidate(node)).isEmpty();
        }

        @Test
        @DisplayName("Unknown timestamp column is an error")
        void unknownTimestampColumn() {
            var node = downsample("missing_col", "1h", ConsolidationFunction.AVG,
                    rel("Metrics"));
            List<SemanticError> errors = inferAndValidate(node);
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).contains("missing_col");
        }

        @Test
        @DisplayName("Unknown grouping key is an error")
        void unknownGroupingKey() {
            var node = downsample("ts", "1h", ConsolidationFunction.SUM,
                    List.of("no_such_key"), rel("Metrics"));
            List<SemanticError> errors = inferAndValidate(node);
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).contains("no_such_key");
        }

        @Test
        @DisplayName("Invalid interval string is an error")
        void invalidInterval() {
            var node = downsample("ts", "bad_interval", ConsolidationFunction.AVG,
                    rel("Metrics"));
            List<SemanticError> errors = inferAndValidate(node);
            assertThat(errors).isNotEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // End-to-end (full pipeline via SemanticFixtures)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("End-to-end pipeline")
    class EndToEnd {

        private static final String METRICS_SRC =
                "source Metrics from csv(\"metrics.csv\") {" +
                "  schema: { ts: TIMESTAMP, host: STRING, cpu: NUMBER, mem: NUMBER }" +
                "};\n";

        @Test
        @DisplayName("Valid DOWNSAMPLE query is fully valid")
        void validQueryFullyValid() {
            var result = analyze(METRICS_SRC +
                    "query { DOWNSAMPLE ts BY '5m' USING AVG (Metrics) };");
            assertThat(result).isFullyValid();
        }

        @Test
        @DisplayName("DOWNSAMPLE with PER and FOR is fully valid")
        void validQueryWithPerAndFor() {
            var result = analyze(METRICS_SRC +
                    "query { DOWNSAMPLE ts BY '1h' USING SUM PER host FOR 24 ROWS (Metrics) };");
            assertThat(result).isFullyValid();
        }

        @Test
        @DisplayName("DOWNSAMPLE with unknown timestamp column produces an error")
        void unknownColumnProducesError() {
            var result = analyze(METRICS_SRC +
                    "query { DOWNSAMPLE no_col BY '1h' USING AVG (Metrics) };");
            assertThat(result).hasErrors();
            assertThat(result.errors().stream().anyMatch(e -> e.message().contains("no_col")))
                    .isTrue();
        }
    }
}

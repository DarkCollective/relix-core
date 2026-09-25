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
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SessionizeNode;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.Expr.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/** Tests for SESSIONIZE schema inference and validation (issue #15). */
@DisplayName("SESSIONIZE — schema inference and validation")
final class SessionizeSemanticTest {

    private InMemorySymbolTable table;
    private SchemaAnnotations   annotations;

    @BeforeEach
    void setUp() {
        table       = new InMemorySymbolTable();
        annotations = new SchemaAnnotations();

        // Events(ts: TIMESTAMP, user_id: NUMBER, page: STRING)
        table.register(new SourceRelationSymbol("default", "Events", Provenance.BUILTIN,
                ShadowPolicy.PERMITTED, new Schema(List.of(
                        col("ts",      ScalarType.TIMESTAMP),
                        col("user_id", ScalarType.NUMBER),
                        col("page",    ScalarType.STRING)))));

        // Readings(seq: NUMBER, value: NUMBER)
        table.register(new SourceRelationSymbol("default", "Readings", Provenance.BUILTIN,
                ShadowPolicy.PERMITTED, new Schema(List.of(
                        col("seq",   ScalarType.NUMBER),
                        col("value", ScalarType.NUMBER)))));
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

    private static SessionizeNode node(String order, Operand gap, List<String> keys,
                                       String session, String relation) {
        return sessionize(order, gap, keys, session,rel(relation));
    }

    @Nested
    @DisplayName("Schema inference")
    class SchemaInferenceTests {

        @Test
        @DisplayName("Appends the NUMBER session column to the input schema")
        void appendsSessionColumn() {
            var n = node("ts", duration(Duration.ofMinutes(30)),
                    List.of("user_id"), "session", "Events");
            Optional<Schema> result = n.accept(visitor());

            assertThat(result).isPresent();
            Schema schema = result.get();
            assertThat(schema.column("ts")).isPresent();
            assertThat(schema.column("user_id")).isPresent();
            assertThat(schema.column("page")).isPresent();
            assertThat(schema.column("session")).isPresent();
            assertThat(schema.column("session").get().type()).isEqualTo(ScalarType.NUMBER);
            // session is appended last
            assertThat(schema.columns().get(schema.width() - 1).name()).isEqualTo("session");
        }

        @Test
        @DisplayName("A session-name clash keeps the input schema (validator reports it)")
        void nameClashKeepsInputSchema() {
            var n = node("ts", duration(Duration.ofMinutes(5)),
                    List.of(), "page", "Events");
            Optional<Schema> result = n.accept(visitor());
            assertThat(result).isPresent();
            assertThat(result.get().width()).isEqualTo(3);
        }

        @Test
        @DisplayName("Returns empty when the input relation is unknown")
        void returnsEmptyForUnknownInput() {
            var n = node("ts", num("5"), List.of(), "s", "NoSuchTable");
            assertThat(n.accept(visitor())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("Valid temporal SESSIONIZE produces no errors")
        void validTemporal() {
            assertThat(inferAndValidate(node("ts", duration(Duration.ofMinutes(30)),
                    List.of("user_id"), "session", "Events"))).isEmpty();
        }

        @Test
        @DisplayName("Valid numeric SESSIONIZE produces no errors")
        void validNumeric() {
            assertThat(inferAndValidate(node("seq", num("5"),
                    List.of(), "run", "Readings"))).isEmpty();
        }

        @Test
        @DisplayName("Unknown order column is an error")
        void unknownOrderColumn() {
            var errors = inferAndValidate(node("missing", num("5"),
                    List.of(), "s", "Readings"));
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).contains("missing");
        }

        @Test
        @DisplayName("Unknown partition key is an error")
        void unknownPartitionKey() {
            var errors = inferAndValidate(node("ts", duration(Duration.ofMinutes(5)),
                    List.of("no_key"), "s", "Events"));
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0).message()).contains("no_key");
        }

        @Test
        @DisplayName("Session column clashing with an input column is an error")
        void sessionColumnClash() {
            var errors = inferAndValidate(node("ts", duration(Duration.ofMinutes(5)),
                    List.of(), "page", "Events"));
            assertThat(errors).anyMatch(e -> e.message().contains("page"));
        }

        @Test
        @DisplayName("A temporal order column requires a DURATION threshold")
        void temporalRequiresDuration() {
            var errors = inferAndValidate(node("ts", num("5"),
                    List.of(), "s", "Events"));
            assertThat(errors).anyMatch(e -> e.message().contains("DURATION"));
        }

        @Test
        @DisplayName("A NUMBER order column requires a NUMBER threshold")
        void numberRequiresNumber() {
            var errors = inferAndValidate(node("seq", duration(Duration.ofMinutes(5)),
                    List.of(), "s", "Readings"));
            assertThat(errors).anyMatch(e -> e.message().contains("NUMBER"));
        }

        @Test
        @DisplayName("A non-literal threshold is an error")
        void nonLiteralThreshold() {
            var errors = inferAndValidate(node("ts", str("nope"),
                    List.of(), "s", "Events"));
            assertThat(errors).anyMatch(e -> e.message().contains("NUMBER or DURATION"));
        }
    }

    @Nested
    @DisplayName("End-to-end pipeline")
    class EndToEnd {

        private static final String EVENTS_SRC =
                "source Events from csv(\"events.csv\") {" +
                "  schema: { ts: TIMESTAMP, user_id: NUMBER, page: STRING }" +
                "};\n";

        @Test
        @DisplayName("Valid SESSIONIZE query is fully valid")
        void validQueryFullyValid() {
            var result = analyze(EVENTS_SRC +
                    "query { SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS session (Events) };");
            assertThat(result).isFullyValid();
        }

        @Test
        @DisplayName("SESSIONIZE with an unknown order column produces an error")
        void unknownColumnProducesError() {
            var result = analyze(EVENTS_SRC +
                    "query { SESSIONIZE no_col GAP DURATION 'PT30M' AS session (Events) };");
            assertThat(result).hasErrors();
            assertThat(result.errors().stream().anyMatch(e -> e.message().contains("no_col")))
                    .isTrue();
        }
    }
}

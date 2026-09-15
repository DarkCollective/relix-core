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

import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * Semantic analysis of the nullary truth-relation literals (issue #51): both infer
 * the closed zero-column heading, resolve against no symbol, and report no errors.
 */
@DisplayName("Truth relations — schema inference, validation, and IR")
final class TruthRelationSemanticTest {

    private static SemanticModel model(String src) {
        SemanticResult result = analyze(src);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        return result.model().orElseThrow();
    }

    /** The inferred schema of the view named {@code name}. */
    private static Schema schemaOf(SemanticModel model, String name) {
        var symbol = model.symbolTable().lookupRelation(name).orElseThrow();
        assertThat(symbol).isInstanceOf(QueryRelationSymbol.class);
        return model.nodeSchemas().get(((QueryRelationSymbol) symbol).body()).orElseThrow();
    }

    @Nested
    @DisplayName("schema inference")
    class Inference {

        @Test
        @DisplayName("UNIT infers the empty (closed, zero-column) heading")
        void unitInfersTheEmptySchema() {
            Schema schema = schemaOf(model("U := { UNIT };\nquery { U };"), "U");
            assertThat(schema.isEmpty()).isTrue();
            assertThat(schema.isOpen()).as("closed, not open").isFalse();
            assertThat(schema.width()).isZero();
        }

        @Test
        @DisplayName("EMPTY infers the same heading as UNIT")
        void emptyInfersTheSameSchema() {
            Schema schema = schemaOf(model("E := { EMPTY };\nquery { E };"), "E");
            assertThat(schema).isEqualTo(Schema.empty());
        }

        @Test
        @DisplayName("the literals are the same heading a no-key ∀ produces")
        void matchesNoKeyForallOutput() {
            var m = model("""
                    Trades := [| trade_id | status  |
                               | 1        | settled |];
                    Answer := { ∀ : status = "settled" (Trades) };
                    Lit    := { UNIT };
                    query { Answer };
                    """);
            assertThat(schemaOf(m, "Lit")).isEqualTo(schemaOf(m, "Answer"));
        }

        @Test
        @DisplayName("R × UNIT keeps R's heading unchanged, in order")
        void productWithUnitPreservesTheHeading() {
            var m = model("""
                    Trades := [| trade_id | desk  | status  |
                               | 1        | FX    | settled |];
                    Gated  := { Trades × UNIT };
                    query { Gated };
                    """);
            assertThat(schemaOf(m, "Gated")).hasColumnNames("trade_id", "desk", "status");
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("a bare literal resolves against no symbol and reports no error")
        void noUndefinedRelationError() {
            assertThat(analyze("query { UNIT };").errors()).isEmpty();
            assertThat(analyze("query { EMPTY };").errors()).isEmpty();
        }

        @Test
        @DisplayName("the two literals are union-compatible with each other")
        void unionOfTheTwoLiteralsIsValid() {
            assertThat(analyze("query { UNIT ∪ EMPTY };").errors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("IR report")
    class Ir {

        @Test
        @DisplayName("the tree labels the literal with its keyword and the LIT kind code")
        void labelsTheLiteral() {
            String report = IrReport.generate(model("Gate := { UNIT };\nquery { Gate };"));
            assertThat(report).contains("UNIT [LIT]");
        }

        @Test
        @DisplayName("the key documents the LIT kind code it emits")
        void keyDocumentsLit() {
            String report = IrReport.generate(model("Gate := { EMPTY };\nquery { Gate };"));
            assertThat(report).contains("LIT=truth-literal");
            assertThat(report).contains("EMPTY [LIT]");
        }
    }
}

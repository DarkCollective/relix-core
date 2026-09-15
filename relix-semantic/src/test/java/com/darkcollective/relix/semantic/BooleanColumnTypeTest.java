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
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;

/**
 * What a declared {@code BOOLEAN} column buys, now that the grammar can spell one.
 *
 * <p>The catalog has always reported a boolean column as {@code BOOLEAN} — a dotted
 * reference to a live table types it correctly. A declared {@code source} could not say
 * it, so the offline form had to write {@code ANY}, and {@code ANY} compares with
 * anything. These tests are that difference, stated as an inferred type and as a
 * diagnostic rather than as prose.
 */
@DisplayName("A declared BOOLEAN column is typed, not merely accepted")
final class BooleanColumnTypeTest {

    private static final String FLAGS = """
            source Flags from database {
                url: "jdbc:h2:mem:x", table: "flags",
                schema: { id: NUMBER, active: BOOLEAN }
            };
            """;

    /** The inferred output schema of the first query's expression. */
    private static Schema outputOf(String script) {
        SemanticModel model = model(script);
        RelNode node = ((ExpressionQueryTarget) model.rootQueries().getFirst().target())
                .expression();
        return model.nodeSchemas().get(node).orElseThrow();
    }

    private static Type typeOf(String script, String column) {
        return outputOf(script).column(column).orElseThrow().type();
    }

    @Nested
    @DisplayName("the declaration reaches inference")
    final class Declaration {

        @Test
        @DisplayName("the column infers as BOOLEAN, the type the catalog would have reported")
        void columnInfersAsBoolean() {
            assertThat(typeOf(FLAGS + "query { Flags };", "active"))
                    .isEqualTo(ScalarType.BOOLEAN);
        }

        @Test
        @DisplayName("a predicate over it analyses")
        void predicateOverItAnalyses() {
            assertThat(analyze(FLAGS + "query { σ active = true (Flags) };")).isFullyValid();
        }

        @Test
        @DisplayName("it projects, keeping its type")
        void projectionKeepsTheType() {
            assertThat(typeOf(FLAGS + "query { π active (Flags) };", "active"))
                    .isEqualTo(ScalarType.BOOLEAN);
        }
    }

    @Nested
    @DisplayName("what ANY threw away")
    final class TypingIsChecked {

        @Test
        @DisplayName("comparing it with a temporal is refused")
        void temporalComparisonIsRefused() {
            assertThat(analyze(FLAGS
                    + "query { σ active > TIMESTAMP '2020-01-01T00:00:00Z' (Flags) };"))
                    .hasErrors();
        }

        @Test
        @DisplayName("the same predicate over an ANY column is not checked at all")
        void anyIsUnchecked() {
            assertThat(analyze("""
                    source Flags from database {
                        url: "jdbc:h2:mem:x", table: "flags",
                        schema: { id: NUMBER, active: ANY }
                    };
                    query { σ active > TIMESTAMP '2020-01-01T00:00:00Z' (Flags) };
                    """)).isFullyValid();
        }
    }

    @Nested
    @DisplayName("a def may name it too — one scalar vocabulary, not two")
    final class Definitions {

        @Test
        @DisplayName("BOOLEAN is a parameter and a return type")
        void defTakesAndReturnsBoolean() {
            assertThat(analyze(FLAGS + """
                    def flip(b: BOOLEAN): BOOLEAN := { IIf(b, false, true) };
                    query { σ flip(active) = true (Flags) };
                    """)).isFullyValid();
        }
    }
}

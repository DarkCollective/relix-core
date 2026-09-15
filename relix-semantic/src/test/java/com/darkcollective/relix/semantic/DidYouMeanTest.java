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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks that "unknown ..." diagnostics carry a "did you mean?"
 * suggestion for ordinary typos. The plain "unknown" reporting is exercised
 * elsewhere; these tests pin the suggestion enrichment specifically.
 */
@DisplayName("'Did you mean?' suggestions on unknown names")
final class DidYouMeanTest {

    private static final String SOURCE = """
            source People from csv("people.csv") {
                header: true,
                schema: { id: NUMBER, name: STRING }
            };
            """;

    @Test
    @DisplayName("an undefined query target suggests the closest defined relation")
    void queryTargetTypoSuggestsRelation() {
        List<SemanticError> errors = analyze(SOURCE + "query Peple;").errors();

        assertThat(errors).anySatisfy(e ->
                assertThat(e.message())
                        .contains("Peple")
                        .contains("did you mean 'People'?"));
    }

    @Test
    @DisplayName("a query target with no close relation gets no suggestion")
    void queryTargetWithNoCloseMatchHasNoSuggestion() {
        List<SemanticError> errors = analyze(SOURCE + "query ZZZZZ;").errors();

        assertThat(errors).anySatisfy(e ->
                assertThat(e.message())
                        .contains("ZZZZZ")
                        .doesNotContain("did you mean"));
    }

    @Test
    @DisplayName("an unknown function near a built-in suggests that built-in")
    void unknownFunctionTypoSuggestsBuiltin() {
        // "Coalese" is one edit from the built-in "Coalesce".
        List<SemanticError> errors =
                analyze(SOURCE + "query { π Coalese(name) → r (People) };").errors();

        assertThat(errors).anySatisfy(e ->
                assertThat(e.message())
                        .containsIgnoringCase("Coalese")
                        .contains("did you mean 'Coalesce'?"));
    }

    // ── S1b: column-name suggestions across the common operators ──────────────

    @Test
    @DisplayName("a projection column typo suggests the closest column")
    void projectionColumnTypoSuggestsColumn() {
        List<SemanticError> errors =
                analyze(SOURCE + "query { π nam → r (People) };").errors();

        assertThat(errors).anySatisfy(e ->
                assertThat(e.message())
                        .contains("nam")
                        .contains("did you mean 'name'?"));
    }

    @Test
    @DisplayName("a selection-predicate column typo suggests the closest column")
    void selectionColumnTypoSuggestsColumn() {
        List<SemanticError> errors =
                analyze(SOURCE + "query { σ nam = \"x\" (People) };").errors();

        assertThat(errors).anySatisfy(e ->
                assertThat(e.message())
                        .contains("nam")
                        .contains("did you mean 'name'?"));
    }

    @Test
    @DisplayName("a sort attribute typo suggests the closest column")
    void sortColumnTypoSuggestsColumn() {
        List<SemanticError> errors =
                analyze(SOURCE + "query { τ nam (People) };").errors();

        assertThat(errors).anySatisfy(e ->
                assertThat(e.message())
                        .contains("nam")
                        .contains("did you mean 'name'?"));
    }

    @Test
    @DisplayName("a group-by attribute typo suggests the closest column")
    void groupByColumnTypoSuggestsColumn() {
        List<SemanticError> errors =
                analyze(SOURCE + "query { γ idd, COUNT(id) → c (People) };").errors();

        assertThat(errors).anySatisfy(e ->
                assertThat(e.message())
                        .contains("idd")
                        .contains("did you mean 'id'?"));
    }

    @Test
    @DisplayName("a column with no close match lists the available columns")
    void columnWithNoCloseMatchListsAvailable() {
        List<SemanticError> errors =
                analyze(SOURCE + "query { π zzzzzz → r (People) };").errors();

        assertThat(errors).anySatisfy(e ->
                assertThat(e.message())
                        .contains("zzzzzz")
                        .doesNotContain("did you mean")
                        .contains("available: id, name"));
    }
}

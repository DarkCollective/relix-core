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

    // ── Undefined relations: the same hints a column gets ─────────────────────

    private static final String RELATIONS = """
            R := [| id | seconds |
                  | 1  | 1200    |];
            results := [| runner_id | place |
                        | 1         | 2     |];
            Finished := { σ seconds > 1000 (R) };
            """;

    private static String relationError(String script) {
        return SemanticFixtures.errorMessages(analyze(script)).stream()
                .filter(m -> m.startsWith("Undefined relation"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no undefined-relation error for: " + script));
    }

    @Test
    @DisplayName("a misspelt relation suggests the closest defined one")
    void relationTypoSuggestsRelation() {
        assertThat(relationError(RELATIONS + "query { σ id > 0 (Rr) };"))
                .isEqualTo("Undefined relation: 'Rr' — did you mean 'R'?");
        assertThat(relationError(RELATIONS + "query { σ place > 0 (result) };"))
                .isEqualTo("Undefined relation: 'result' — did you mean 'results'?");
        assertThat(relationError(RELATIONS + "query { Finishd };"))
                .isEqualTo("Undefined relation: 'Finishd' — did you mean 'Finished'?");
        assertThat(relationError(RELATIONS + "query { R >< R.id = reslts.runner_id reslts };"))
                .isEqualTo("Undefined relation: 'reslts' — did you mean 'results'?");
    }

    @Test
    @DisplayName("a misspelt relix.* relation is suggested by its qualified name")
    void systemRelationTypoSuggestsQualifiedName() {
        assertThat(relationError(RELATIONS + "query { relix.relatons };"))
                .isEqualTo("Undefined relation: 'relix.relatons' — did you mean 'relix.relations'?");
    }

    @Test
    @DisplayName("a relation with no close match lists the defined relations")
    void relationWithNoCloseMatchListsDefined() {
        assertThat(relationError(RELATIONS + "query { σ id > 0 (orders) };"))
                .isEqualTo("Undefined relation: 'orders' (defined: R, results, Finished)");
    }

    @Test
    @DisplayName("a relation in a script that declares none says so")
    void relationInEmptyScriptSaysNoneDeclared() {
        String expected = "Undefined relation: 'loans': this script declares no relations;"
                + " declare it first, as an inline table (loans := [| … |];), a source or a connection";
        assertThat(relationError("query { loans };")).isEqualTo(expected);
        assertThat(relationError("query { σ seconds > 1000 (loans) };")).isEqualTo(expected);
    }

    @Test
    @DisplayName("a relation named in another case still resolves")
    void relationInAnotherCaseResolves() {
        assertThat(analyze(RELATIONS + "query { σ place > 0 (RESULTS) };").errors()).isEmpty();
    }
}

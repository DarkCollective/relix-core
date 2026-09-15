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
package com.darkcollective.relix.embed.equivalence;

import com.darkcollective.relix.processor.QueryExecutor;
import com.darkcollective.relix.processor.QueryResult;
import com.darkcollective.relix.semantic.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dotted access into a nested column, end to end.
 *
 * <p>The unit coverage is {@code SchemaPathResolutionTest} (the resolver) and
 * {@code ArrayRowTest} (the navigation). This is the pair of them meeting: a script
 * that <em>analyses</em> a struct-field reference and then <em>evaluates</em> it,
 * which is the combination that was broken — the schema knew the field was there
 * and the validator refused the reference anyway.
 */
@DisplayName("Struct-field access resolves and evaluates")
final class StructFieldAccessTest {

    private static List<String> run(String script, int queryIndex) {
        SemanticResult analysis = analyze(script);
        assertThat(analysis.errors()).as("analysis errors").isEmpty();
        List<QueryResult> results = new QueryExecutor().execute(analysis);
        return results.get(queryIndex).rows().stream()
                .map(row -> java.util.stream.IntStream.range(0, row.width())
                        .mapToObj(i -> row.get(i).isNull() ? "NULL" : row.get(i).asDisplayString())
                        .collect(java.util.stream.Collectors.joining("|")))
                .toList();
    }

    private static final String NESTED = """
            People := [
            | id | first | last  | city   |
            |----|-------|-------|--------|
            | 1  | Ada   | Byron | London |
            | 2  | Grace | Hop   | Boston |
            ];

            Nested := { π id, {first: first, last: last, home: {city: city}} → person (People) };
            """;

    @Test
    @DisplayName("a projected field, named by the field rather than the path")
    void projectsAField() {
        assertThat(run(NESTED + "query { π id, person.first (Nested) };", 0))
                .containsExactly("1|Ada", "2|Grace");
    }

    @Test
    @DisplayName("a field of a field")
    void projectsANestedField() {
        assertThat(run(NESTED + "query { π id, person.home.city → city (Nested) };", 0))
                .containsExactly("1|London", "2|Boston");
    }

    @Test
    @DisplayName("a field in a selection predicate")
    void filtersOnAField() {
        assertThat(run(NESTED + "query { σ person.first = 'Ada' (Nested) };", 0))
                .hasSize(1);
    }

    @Test
    @DisplayName("a field inside a function call and an aggregate")
    void readsAFieldInAnExpression() {
        assertThat(run(NESTED + "query { π id, Len(person.last) → n (Nested) };", 0))
                .containsExactly("1|5", "2|3");
        assertThat(run(NESTED + "query { γ MAX(Len(person.first)) → longest (Nested) };", 0))
                .containsExactly("5");
    }

    @Test
    @DisplayName("a field the struct does not declare is an analysis error, not a runtime one")
    void unknownFieldIsRejected() {
        SemanticResult analysis = analyze(NESTED + "query { π id, person.middle (Nested) };");
        assertThat(analysis.errors()).isNotEmpty();
        assertThat(analysis.errors().getFirst().message()).contains("person.middle");
    }
}

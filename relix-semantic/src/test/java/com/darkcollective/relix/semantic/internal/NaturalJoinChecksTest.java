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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.ast.AstBuilders.rename;
import static com.darkcollective.relix.ast.AstBuilders.renamePair;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;

/** A natural join that matches on more than a key is warned about (#164). */
@DisplayName("Natural joins that match on more than a key")
final class NaturalJoinChecksTest {

    private static final String HOMES = """
            rooms := [
            | room_id | name    |
            |---------|---------|
            | 10      | Kitchen |
            ];
            devices := [
            | device_id | room_id | name        |
            |-----------|---------|-------------|
            | 100       | 10      | Smoke Alarm |
            ];
            """;

    @Test
    @DisplayName("a shared non-key column is named beside the key, with the USING join to write")
    void sharedDescriptiveColumn() {
        assertThat(analyze(HOMES + "query { devices JOIN rooms };"))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("Natural join of devices and rooms matches on room_id and name; "
                        + "to join on room_id alone, write devices JOIN rooms USING (room_id)");
    }

    @Test
    @DisplayName("keys are read through a view and through σ")
    void throughViewAndSelection() {
        assertThat(analyze(HOMES + """
                kitchens := { σ name = 'Kitchen' (rooms) };
                query { σ device_id > 0 (devices) ⋈ kitchens };
                """))
                .hasErrorContaining("Natural join of devices and kitchens matches on room_id and name");
    }

    @Test
    @DisplayName("a join on one shared column, or on a composite key, says nothing")
    void keyJoins() {
        assertThat(analyze("""
                rooms := [
                | room_id | label   |
                |---------|---------|
                | 10      | Kitchen |
                ];
                devices := [
                | device_id | room_id |
                |-----------|---------|
                | 100       | 10      |
                ];
                lines := [
                | order_id | line_no | qty |
                |----------|---------|-----|
                | 1        | 1       | 2   |
                | 1        | 2       | 1   |
                | 2        | 1       | 5   |
                ];
                shipped := [
                | order_id | line_no | day |
                |----------|---------|-----|
                | 1        | 1       | Mon |
                | 1        | 2       | Mon |
                | 2        | 1       | Tue |
                ];
                query { devices JOIN rooms };
                query { lines JOIN shipped };
                """))
                .isFullyValid();
    }

    @Test
    @DisplayName("an input whose keys are not known says nothing")
    void unknownKeys() {
        assertThat(analyze(HOMES + "query { π room_id, name (devices) ⋈ π room_id, name (rooms) };"))
                .isFullyValid();
    }

    // ── keys a database declares, which may span columns ──────────────────────────

    private static final String WIDE = """
            A := [
            | name | t_no | t_code | code | t_key | key | xid | x_id | id | a |
            |------|------|--------|------|-------|-----|-----|------|----|---|
            | n    | 1    | 1      | 1    | 1     | 1   | 1   | 1    | 1  | 1 |
            ];
            B := [
            | name | t_no | t_code | code | t_key | key | xid | x_id | id | b |
            |------|------|--------|------|-------|-----|-----|------|----|---|
            | n    | 1    | 1      | 1    | 1     | 1   | 1   | 1    | 1  | 1 |
            ];
            """;

    private static final SymbolTable SYMBOLS = analyze(WIDE).model().orElseThrow().symbolTable();

    private static Optional<String> warning(RelNode left, RelNode right, Map<String, List<List<String>>> keys) {
        RelationSymbol a = SYMBOLS.lookupRelation("A").orElseThrow();
        RelationSymbol b = SYMBOLS.lookupRelation("B").orElseThrow();
        Map<String, RelationStatistics> statistics = new java.util.HashMap<>();
        keys.forEach((name, k) -> statistics.put(SYMBOLS.lookupRelation(name).orElseThrow().canonicalName(),
                new RelationStatistics(OptionalLong.empty(), Map.of(), k)));
        Schema leftSchema = a.schema();
        Schema rightSchema = b.schema();
        return NaturalJoinChecks.warning(left, right, leftSchema, rightSchema, SYMBOLS, statistics);
    }

    @Test
    @DisplayName("a key that is every matched column says nothing")
    void keyIsTheWholeMatch() {
        List<String> all = List.of("name", "t_no", "t_code", "code", "t_key", "key", "xid", "x_id", "id");
        org.assertj.core.api.Assertions.assertThat(warning(rel("A"), rel("B"), Map.of("B", List.of(all))))
                .isEmpty();
    }

    @Test
    @DisplayName("among keys of one size, the first that reads as an identifier is suggested")
    void identifierPreferred() {
        List<List<String>> singles = List.of("name", "t_no", "t_code", "code", "t_key", "key", "xid", "x_id", "id")
                .stream().map(List::of).toList();
        org.assertj.core.api.Assertions.assertThat(warning(rel("A"), rel("B"), Map.of("B", singles)))
                .hasValueSatisfying(message -> org.assertj.core.api.Assertions.assertThat(message)
                        .contains("matches on name, t_no, t_code, code, t_key, key, xid, x_id and id")
                        .endsWith("to join on t_no alone, write A JOIN B USING (t_no)"));
    }

    @Test
    @DisplayName("a rename of columns hides the keys beneath it; a rename of the relation keeps them")
    void renames() {
        Map<String, List<List<String>>> keys = Map.of("A", List.of(List.of("id")));
        RelNode columnsByPosition = rename(Optional.empty(),
                List.of("name", "t_no", "t_code", "code", "t_key", "key", "xid", "x_id", "id", "a"),
                List.of(), rel("A"));
        RelNode columnsByPair = rename(Optional.empty(), List.of(), List.of(renamePair("a", "z")), rel("A"));
        RelNode relationOnly = rename(Optional.of("Aa"), List.of(), List.of(), rel("A"));
        org.assertj.core.api.Assertions.assertThat(warning(columnsByPosition, rel("B"), keys)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(warning(columnsByPair, rel("B"), keys)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(warning(relationOnly, rel("B"), keys))
                .hasValueSatisfying(message -> org.assertj.core.api.Assertions.assertThat(message)
                        .startsWith("Natural join of Aa and B").endsWith("USING (id)"));
    }
}

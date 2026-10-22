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

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.Severity;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.SystemRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.bool;
import static com.darkcollective.relix.ast.AstBuilders.num;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;

/** An inline table's schema block types its columns, as a CSV source's schema does (#205). */
@DisplayName("Inline-table schema blocks")
final class InlineSchemaTest {

    private static final String READINGS = """
            Readings := [
            | capability_id | read_at              | value |
            |---------------|----------------------|-------|
            | 701           | 2026-10-22T14:00:00Z | 70.0  |
            | 702           | 2026-10-22T13:00:00Z |       |
            ] schema { read_at: TIMESTAMP };
            query { Readings };
            """;

    private static InlineRelationSymbol relation(String src, String name) {
        SymbolTable table = analyze(src).model().orElseThrow().symbolTable();
        return (InlineRelationSymbol) table.lookupRelation(name).orElseThrow();
    }

    private static String str(Operand op) {
        return ((StringOperand) op).value();
    }

    @Test
    @DisplayName("a declared column takes its type and the others are inferred, as relix.columns reports")
    void declaredAndInferred() {
        assertThat(analyze(READINGS)).isFullyValid();
        InlineRelationSymbol readings = relation(READINGS, "Readings");
        assertThat(readings.schema().columns().stream().map(ColumnDefinition::type))
                .containsExactly(ScalarType.NUMBER, ScalarType.TIMESTAMP, ScalarType.NUMBER);
        assertThat(readings.rows().getFirst().get("read_at")).isInstanceOf(TimestampOperand.class);
        assertThat(readings.rows().get(1)).doesNotContainKey("value");

        SystemRelationSymbol columns = (SystemRelationSymbol) analyze(READINGS).model().orElseThrow()
                .symbolTable().lookupRelation("relix", "columns").orElseThrow();
        assertThat(columns.rows().stream()
                .filter(r -> str(r.get("relation")).equals("Readings"))
                .map(r -> str(r.get("column")) + ":" + str(r.get("type"))))
                .containsExactly("capability_id:N", "read_at:TS", "value:N");
    }

    @Test
    @DisplayName("a cell that does not parse as its column's type is one error, at its row")
    void badCell() {
        var result = analyze("""
                Readings := [
                | id | read_at              |
                |----|----------------------|
                | 1  | 2026-10-22T14:00:00Z |
                | 2  | yesterday            |
                ] schema { read_at: TIMESTAMP };
                query { Readings };
                """);
        List<SemanticError> errors = result.errors().stream()
                .filter(e -> e.severity() == Severity.ERROR).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().message())
                .isEqualTo("Readings.read_at, row 2: 'yesterday' is not a TIMESTAMP, the type its schema declares");
        assertThat(errors.getFirst().line()).isEqualTo(5);
    }

    @Test
    @DisplayName("the csv form takes a schema too, and its bad cells are placed at their rows")
    void csvForm() {
        var result = analyze("""
                Flags := csv[
                  id, at, on
                  1, 2026-10-22, true
                  2, 2026-10-23, 0
                  3, , maybe
                ] schema { on: BOOLEAN, at: DATE };
                query { Flags };
                """);
        assertThat(result).hasErrorContaining("Flags.on, row 3: 'maybe' is not a BOOLEAN");
        assertThat(result.errors().stream().filter(e -> e.severity() == Severity.ERROR))
                .singleElement().extracting(SemanticError::line).isEqualTo(5);
    }

    @Test
    @DisplayName("booleans read as a CSV source reads them, and a declared column is not warned about")
    void booleansAndNoWarnings() {
        String src = """
                Flags := csv[
                  id, at, on
                  1, 2026-10-22T14:00:00Z, TRUE
                  2, , 0
                ] schema { on: BOOLEAN, at: TIMESTAMP };
                query { Flags };
                """;
        assertThat(analyze(src)).isFullyValid();
        InlineRelationSymbol flags = relation(src, "Flags");
        assertThat(flags.rows().getFirst().get("on")).isEqualTo(bool(true));
        assertThat(flags.rows().get(1).get("on")).isEqualTo(bool(false));
    }

    @Test
    @DisplayName("a declared NUMBER column checks its cells as decimals")
    void declaredNumber() {
        assertThat(analyze("""
                T := [
                | n   |
                |-----|
                | 1.5 |
                | two |
                ] schema { n: NUMBER };
                query { T };
                """)).hasErrorContaining("T.n, row 2: 'two' is not a NUMBER");
        assertThat(relation("T := [\n| n |\n|---|\n| 3 |\n] schema { n: NUMBER };", "T")
                .rows().getFirst().get("n")).isEqualTo(num("3"));
    }
}

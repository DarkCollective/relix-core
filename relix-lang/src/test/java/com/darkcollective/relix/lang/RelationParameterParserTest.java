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
package com.darkcollective.relix.lang;

import com.darkcollective.relix.lang.ast.DefRelationStatement;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptPrinter;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A table-valued {@code def}'s relation parameter — {@code E: RELATION(col [: TYPE], …)}. */
@DisplayName("def — relation parameters")
final class RelationParameterParserTest {

    private static DefRelationStatement def(String source) {
        Script script = ScriptParser.parse(source);
        return (DefRelationStatement) script.statements().getFirst();
    }

    @Test
    @DisplayName("a relation parameter declares its columns, each typed or ANY")
    void declaresAHeading() {
        DefRelationStatement def = def(
                "def f(E: RELATION(src, weight: NUMBER, tags: [STRING]), k: NUMBER): RELATION := { E };");
        ParameterDefinition e = def.parameters().get(0);
        assertThat(e.isRelation()).isTrue();
        assertThat(e.type()).isEqualTo(ScalarType.ANY);
        assertThat(e.heading().orElseThrow().columns()).containsExactly(
                new ColumnDefinition("src", ScalarType.ANY),
                new ColumnDefinition("weight", ScalarType.NUMBER),
                new ColumnDefinition("tags", new ArrayType(ScalarType.STRING)));
        assertThat(def.parameters().get(1).isRelation()).isFalse();
    }

    @Test
    @DisplayName("RELATION is case-insensitive in a parameter as in the return type")
    void caseInsensitive() {
        assertThat(def("def f(E: relation(x)): RELATION := { E };").parameters().get(0).isRelation())
                .isTrue();
    }

    @Test
    @DisplayName("the script printer writes it back as it is read")
    void roundTrips() {
        String source = "def f(E: RELATION(src, weight: NUMBER), k: NUMBER): RELATION := { E };";
        Script parsed = ScriptParser.parse(source);
        String printed = ScriptPrinter.print(parsed);
        assertThat(printed).contains("E: RELATION(src, weight: number), k: number");
        Script reparsed = ScriptParser.parse(printed);
        assertThat(((DefRelationStatement) reparsed.statements().getFirst()).parameters())
                .isEqualTo(((DefRelationStatement) parsed.statements().getFirst()).parameters());
        assertThat(ScriptPrinter.print(reparsed)).isEqualTo(printed);
    }

    @Test
    @DisplayName("a column named twice is refused")
    void refusesADuplicateColumn() {
        assertThatThrownBy(() -> def("def f(E: RELATION(x, X)): RELATION := { E };"))
                .hasMessageContaining("names column 'X' more than once");
    }

    @Test
    @DisplayName("a heading declares at least one column")
    void refusesAnEmptyHeading() {
        assertThatThrownBy(() -> def("def f(E: RELATION()): RELATION := { E };"))
                .hasMessageContaining("column name in relation parameter 'E'");
    }

    @Test
    @DisplayName("a scalar function cannot take a relation parameter")
    void refusedOnAScalarFunction() {
        assertThatThrownBy(() -> ScriptParser.parse("def g(E: RELATION(x)): NUMBER := { 1 };"))
                .hasMessageContaining("Only a table-valued function (': RELATION') can take a relation "
                        + "parameter, and 'g' declares 'E' as one");
    }

    @Test
    @DisplayName("a relation parameter needs its column list")
    void needsItsColumns() {
        assertThatThrownBy(() -> def("def f(E: RELATION): RELATION := { E };"))
                .hasMessageContaining("(");
    }

    @Test
    @DisplayName("the parameters keep their order")
    void keepsOrder() {
        assertThat(def("def f(a: NUMBER, E: RELATION(x), b: STRING): RELATION := { E };").parameters())
                .extracting(ParameterDefinition::name)
                .isEqualTo(List.of("a", "E", "b"));
    }
}

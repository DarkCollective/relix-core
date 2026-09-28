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

import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static org.assertj.core.api.Assertions.assertThat;

/** The names a table-valued function's body resolves against. */
final class ParameterScopeTest {

    private static final Schema HEADING =
            new Schema(List.of(new ColumnDefinition("src", ScalarType.NUMBER)));

    private static RelationFunctionSymbol function(ParameterDefinition... parameters) {
        var builder = RelationFunctionSymbol.builder("f").body(rel("E"));
        for (ParameterDefinition p : parameters) {
            builder.parameter(p);
        }
        return builder.build();
    }

    @Test
    void aFunctionWithoutRelationParametersSeesTheTableItself() {
        SymbolTable base = new InMemorySymbolTable();
        assertThat(ParameterScope.of(base, function(new ParameterDefinition("k", ScalarType.NUMBER))))
                .isSameAs(base);
    }

    @Test
    void aRelationParameterIsARelationWithItsHeading() {
        var base = new InMemorySymbolTable();
        base.register(SourceRelationSymbol.of("E", Schema.empty()));
        SymbolTable scope = ParameterScope.of(base, function(ParameterDefinition.relation("E", HEADING)));
        assertThat(scope.lookupRelation("e")).get()
                .extracting(r -> r.schema()).isEqualTo(HEADING);
    }

    @Test
    void everythingElseIsTheUnderlyingTables() {
        var base = new InMemorySymbolTable();
        SymbolTable scope = ParameterScope.of(base, function(ParameterDefinition.relation("E", HEADING)));
        var other = SourceRelationSymbol.of("Other", HEADING);
        assertThat(scope.register(other).registered()).isTrue();
        assertThat(scope.lookupRelation("Other")).isEqualTo(base.lookupRelation("Other"));
        assertThat(scope.lookupRelation("default", "Other")).isEqualTo(base.lookupRelation("default", "Other"));
        assertThat(scope.lookupFunction("f")).isEqualTo(base.lookupFunction("f"));
        assertThat(scope.lookupFunction("default", "f")).isEqualTo(base.lookupFunction("default", "f"));
        assertThat(scope.lookupFunction("f", List.of())).isEqualTo(base.lookupFunction("f", List.of()));
        assertThat(scope.allSymbols()).containsExactlyInAnyOrderElementsOf(base.allSymbols());
    }
}

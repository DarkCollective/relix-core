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
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BuiltinProvider — none(), custom registration, functional interface")
final class BuiltinProviderTest {

    private static Schema schema(String... cols) {
        return new Schema(
                java.util.Arrays.stream(cols)
                        .map(c -> new ColumnDefinition(c, ScalarType.STRING))
                        .toList());
    }

    @Test
    @DisplayName("none() leaves the symbol table unmodified")
    void noneDoesNotRegisterAnything() {
        var table = new InMemorySymbolTable();
        BuiltinProvider.none().register(table);
        assertThat(table.allSymbols()).isEmpty();
    }

    @Test
    @DisplayName("Custom provider can register a relation symbol")
    void customProviderRegistersSymbol() {
        var table = new InMemorySymbolTable();
        BuiltinProvider provider = t ->
                t.register(new SourceRelationSymbol(
                        "builtin", "sys_config", Provenance.BUILTIN,
                        ShadowPolicy.FORBIDDEN, schema("key", "value")));

        provider.register(table);

        assertThat(table.lookupRelation("builtin", "sys_config")).isPresent();
    }

    @Test
    @DisplayName("BuiltinProvider is a functional interface — lambda is valid")
    void isFunctionalInterface() {
        // The fact this compiles proves BuiltinProvider is a @FunctionalInterface
        BuiltinProvider provider = table -> { };
        var table = new InMemorySymbolTable();
        provider.register(table); // should not throw
        assertThat(table.allSymbols()).isEmpty();
    }

    @Test
    @DisplayName("Provider called multiple times accumulates registrations")
    void calledMultipleTimes() {
        var table = new InMemorySymbolTable();
        BuiltinProvider provider = t -> {
            t.register(SourceRelationSymbol.builtin("feed_a", schema("id")));
            t.register(SourceRelationSymbol.builtin("feed_b", schema("id")));
        };
        provider.register(table);
        assertThat(table.allSymbols()).hasSize(2);
    }
}

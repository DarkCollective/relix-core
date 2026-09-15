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
package com.darkcollective.relix.symbol.relation;

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.SymbolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InlineRelationSymbol — defaults, row immutability, canonical name")
final class InlineRelationSymbolTest extends SymbolTestSupport {

    private static final Schema SCHEMA = new Schema(List.of(
            new ColumnDefinition("id", ScalarType.NUMBER),
            new ColumnDefinition("name", ScalarType.STRING)));

    private static final List<Map<String, Operand>> ROWS = List.of(
            Map.of("id", num("1"), "name", str("Alice")));

    @Test
    @DisplayName("of() factory applies default namespace, provenance, and shadow policy")
    void factoryAppliesDefaults() {
        InlineRelationSymbol sym = InlineRelationSymbol.of("Data", SCHEMA, ROWS);
        assertThat(sym.namespace()).isEqualTo("default");
        assertThat(sym.provenance()).isEqualTo(Provenance.USER);
        assertThat(sym.shadowPolicy()).isEqualTo(ShadowPolicy.PERMITTED);
    }

    @Test
    @DisplayName("Rows are stored and accessible")
    void rowsAreStoredAndAccessible() {
        InlineRelationSymbol sym = InlineRelationSymbol.of("Data", SCHEMA, ROWS);
        assertThat(sym.rows()).hasSize(1);
        assertThat(sym.rows().get(0).get("id")).isEqualTo(num("1"));
    }

    @Test
    @DisplayName("canonicalName() is lower-cased declaredName")
    void canonicalNameIsLowerCased() {
        InlineRelationSymbol sym = InlineRelationSymbol.of("MyData", SCHEMA, ROWS);
        assertThat(sym.canonicalName()).isEqualTo("mydata");
        assertThat(sym.declaredName()).isEqualTo("MyData");
    }

    @Test
    @DisplayName("Row list is unmodifiable after construction")
    void rowListIsUnmodifiable() {
        InlineRelationSymbol sym = InlineRelationSymbol.of("Data", SCHEMA, ROWS);
        assertThatThrownBy(() -> sym.rows().add(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Individual row maps are unmodifiable after construction")
    void individualRowMapsAreUnmodifiable() {
        InlineRelationSymbol sym = InlineRelationSymbol.of("Data", SCHEMA, ROWS);
        assertThatThrownBy(() -> sym.rows().get(0).put("extra", str("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Mutating source row list does not affect symbol")
    void mutatingSourceRowListDoesNotAffectSymbol() {
        List<Map<String, Operand>> mutableRows = new ArrayList<>(ROWS);
        InlineRelationSymbol sym = InlineRelationSymbol.of("Data", SCHEMA, mutableRows);
        mutableRows.add(Map.of("id", num("99"), "name", str("Z")));
        assertThat(sym.rows()).hasSize(1);
    }

    @Test
    @DisplayName("Mutating source row map does not affect symbol")
    void mutatingSourceRowMapDoesNotAffectSymbol() {
        Map<String, Operand> mutableRow = new HashMap<>();
        mutableRow.put("id", num("1"));
        mutableRow.put("name", str("Alice"));
        InlineRelationSymbol sym = InlineRelationSymbol.of("Data", SCHEMA, List.of(mutableRow));
        mutableRow.put("extra", str("injected"));
        assertThat(sym.rows().get(0)).doesNotContainKey("extra");
    }

    @Test
    @DisplayName("Allows empty row list")
    void allowsEmptyRowList() {
        InlineRelationSymbol sym = InlineRelationSymbol.of("Empty", SCHEMA, List.of());
        assertThat(sym.rows()).isEmpty();
    }

    @Test
    @DisplayName("Rejects blank declaredName")
    void rejectsBlankDeclaredName() {
        assertThatThrownBy(() -> InlineRelationSymbol.of("", SCHEMA, ROWS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects null rows")
    void rejectsNullRows() {
        assertThatThrownBy(() ->
                new InlineRelationSymbol("default", "Data", Provenance.USER, ShadowPolicy.PERMITTED, SCHEMA, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects blank namespace")
    void rejectsBlankNamespace() {
        assertThatThrownBy(() ->
                new InlineRelationSymbol("  ", "Data", Provenance.USER, ShadowPolicy.PERMITTED, SCHEMA, ROWS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

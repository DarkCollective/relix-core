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

import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.SymbolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DatabaseRelationSymbol — defaults, validation, canonical name")
final class DatabaseRelationSymbolTest extends SymbolTestSupport {

    @Test
    @DisplayName("of() factory applies default namespace, provenance, and shadow policy")
    void factoryAppliesDefaults() {
        DatabaseRelationSymbol sym = DatabaseRelationSymbol.of("Users", schema("id"));
        assertThat(sym.namespace()).isEqualTo("default");
        assertThat(sym.provenance()).isEqualTo(Provenance.USER);
        assertThat(sym.shadowPolicy()).isEqualTo(ShadowPolicy.PERMITTED);
        assertThat(sym.declaredName()).isEqualTo("Users");
    }

    @Test
    @DisplayName("builtin() factory applies builtin namespace, provenance, and FORBIDDEN policy")
    void builtinFactoryAppliesBuiltinDefaults() {
        DatabaseRelationSymbol sym = DatabaseRelationSymbol.builtin("SysLog", schema("id"));
        assertThat(sym.namespace()).isEqualTo("builtin");
        assertThat(sym.provenance()).isEqualTo(Provenance.BUILTIN);
        assertThat(sym.shadowPolicy()).isEqualTo(ShadowPolicy.FORBIDDEN);
    }

    @Test
    @DisplayName("canonicalName() is lower-cased declaredName")
    void canonicalNameIsLowerCased() {
        DatabaseRelationSymbol sym = DatabaseRelationSymbol.of("UsErS", schema("id"));
        assertThat(sym.canonicalName()).isEqualTo("users");
        assertThat(sym.declaredName()).isEqualTo("UsErS");
    }

    @Test
    @DisplayName("Schema is stored correctly")
    void schemaIsStoredCorrectly() {
        Schema s = new Schema(List.of(
                new ColumnDefinition("id", ScalarType.NUMBER),
                new ColumnDefinition("name", ScalarType.STRING)));
        DatabaseRelationSymbol sym = DatabaseRelationSymbol.of("Orders", s);
        assertThat(sym.schema().width()).isEqualTo(2);
        assertThat(sym.schema().column("id")).isPresent();
    }

    @Test
    @DisplayName("Rejects blank declaredName")
    void rejectsBlankDeclaredName() {
        assertThatThrownBy(() -> DatabaseRelationSymbol.of("  ", schema("id")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects blank namespace")
    void rejectsBlankNamespace() {
        assertThatThrownBy(() ->
                new DatabaseRelationSymbol("  ", "Users", Provenance.USER, ShadowPolicy.PERMITTED, schema("id")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects null schema")
    void rejectsNullSchema() {
        assertThatThrownBy(() ->
                new DatabaseRelationSymbol("default", "Users", Provenance.USER, ShadowPolicy.PERMITTED, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects null provenance")
    void rejectsNullProvenance() {
        assertThatThrownBy(() ->
                new DatabaseRelationSymbol("default", "Users", null, ShadowPolicy.PERMITTED, schema("id")))
                .isInstanceOf(NullPointerException.class);
    }
}

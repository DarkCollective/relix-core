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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.SymbolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QueryRelationSymbol — defaults, body storage, canonical name")
final class QueryRelationSymbolTest extends SymbolTestSupport {

    private static final RelNode BODY = new com.darkcollective.relix.ast.RelationNode("Users");

    @Test
    @DisplayName("of() factory applies default namespace, provenance, and shadow policy")
    void factoryAppliesDefaults() {
        QueryRelationSymbol sym = QueryRelationSymbol.of("ActiveUsers", schema("id"), BODY);
        assertThat(sym.namespace()).isEqualTo("default");
        assertThat(sym.provenance()).isEqualTo(Provenance.USER);
        assertThat(sym.shadowPolicy()).isEqualTo(ShadowPolicy.PERMITTED);
    }

    @Test
    @DisplayName("Body is stored and accessible")
    void bodyIsStoredAndAccessible() {
        QueryRelationSymbol sym = QueryRelationSymbol.of("ActiveUsers", schema("id"), BODY);
        assertThat(sym.body()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("canonicalName() is lower-cased declaredName")
    void canonicalNameIsLowerCased() {
        QueryRelationSymbol sym = QueryRelationSymbol.of("ActiveUsers", schema("id"), BODY);
        assertThat(sym.canonicalName()).isEqualTo("activeusers");
        assertThat(sym.declaredName()).isEqualTo("ActiveUsers");
    }

    @Test
    @DisplayName("Rejects null body")
    void rejectsNullBody() {
        assertThatThrownBy(() ->
                new QueryRelationSymbol("default", "ActiveUsers", Provenance.USER, ShadowPolicy.PERMITTED, schema("id"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects blank declaredName")
    void rejectsBlankDeclaredName() {
        assertThatThrownBy(() -> QueryRelationSymbol.of("", schema("id"), BODY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects blank namespace")
    void rejectsBlankNamespace() {
        assertThatThrownBy(() ->
                new QueryRelationSymbol(" ", "ActiveUsers", Provenance.USER, ShadowPolicy.PERMITTED, schema("id"), BODY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Pretty-print of body is accessible via RelNode API")
    void bodyPrettyPrintIsAccessible() {
        QueryRelationSymbol sym = QueryRelationSymbol.of("V", schema("id"), BODY);
        assertThat(sym.body().prettyPrint()).isEqualTo("Users");
    }
}

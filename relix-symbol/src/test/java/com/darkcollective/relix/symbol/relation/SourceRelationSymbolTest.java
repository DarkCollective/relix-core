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

import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.SymbolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SourceRelationSymbol — defaults, validation, canonical name")
final class SourceRelationSymbolTest extends SymbolTestSupport {

    @Test
    @DisplayName("of() factory applies default namespace, provenance, and shadow policy")
    void factoryAppliesDefaults() {
        SourceRelationSymbol sym = SourceRelationSymbol.of("weather", schema("city", "temp"));
        assertThat(sym.namespace()).isEqualTo("default");
        assertThat(sym.provenance()).isEqualTo(Provenance.USER);
        assertThat(sym.shadowPolicy()).isEqualTo(ShadowPolicy.PERMITTED);
        assertThat(sym.declaredName()).isEqualTo("weather");
    }

    @Test
    @DisplayName("builtin() factory applies builtin namespace, provenance, and FORBIDDEN policy")
    void builtinFactoryAppliesBuiltinDefaults() {
        SourceRelationSymbol sym = SourceRelationSymbol.builtin("sys_feed", schema("id"));
        assertThat(sym.namespace()).isEqualTo("builtin");
        assertThat(sym.provenance()).isEqualTo(Provenance.BUILTIN);
        assertThat(sym.shadowPolicy()).isEqualTo(ShadowPolicy.FORBIDDEN);
    }

    @Test
    @DisplayName("canonicalName() is lower-cased declaredName")
    void canonicalNameIsLowerCased() {
        SourceRelationSymbol sym = SourceRelationSymbol.of("CurrentWeather", schema("city"));
        assertThat(sym.canonicalName()).isEqualTo("currentweather");
        assertThat(sym.declaredName()).isEqualTo("CurrentWeather");
    }

    @Test
    @DisplayName("schema() returns the schema supplied at construction")
    void schemaIsStoredCorrectly() {
        var s = schema(
                col("city",     ScalarType.STRING),
                col("temp",     ScalarType.NUMBER),
                col("humidity", ScalarType.NUMBER));
        SourceRelationSymbol sym = SourceRelationSymbol.of("weather", s);
        assertThat(sym.schema().width()).isEqualTo(3);
        assertThat(sym.schema().column("city")).isPresent();
        assertThat(sym.schema().column("temp")).isPresent();
    }

    @Test
    @DisplayName("implements RelationSymbol")
    void implementsRelationSymbol() {
        SourceRelationSymbol sym = SourceRelationSymbol.of("weather", schema("city"));
        assertThat(sym).isInstanceOf(RelationSymbol.class);
    }

    @Test
    @DisplayName("Rejects blank declaredName")
    void rejectsBlankDeclaredName() {
        assertThatThrownBy(() -> SourceRelationSymbol.of("  ", schema("id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("Rejects blank namespace")
    void rejectsBlankNamespace() {
        assertThatThrownBy(() ->
                new SourceRelationSymbol("  ", "weather", Provenance.USER, ShadowPolicy.PERMITTED, schema("id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("Rejects null schema")
    void rejectsNullSchema() {
        assertThatThrownBy(() ->
                new SourceRelationSymbol("default", "weather", Provenance.USER, ShadowPolicy.PERMITTED, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects null provenance")
    void rejectsNullProvenance() {
        assertThatThrownBy(() ->
                new SourceRelationSymbol("default", "weather", null, ShadowPolicy.PERMITTED, schema("id")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects null shadow policy")
    void rejectsNullShadowPolicy() {
        assertThatThrownBy(() ->
                new SourceRelationSymbol("default", "weather", Provenance.USER, null, schema("id")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects null declaredName")
    void rejectsNullDeclaredName() {
        assertThatThrownBy(() ->
                new SourceRelationSymbol("default", null, Provenance.USER, ShadowPolicy.PERMITTED, schema("id")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects null namespace")
    void rejectsNullNamespace() {
        assertThatThrownBy(() ->
                new SourceRelationSymbol(null, "weather", Provenance.USER, ShadowPolicy.PERMITTED, schema("id")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Two symbols with same fields are equal (record equality)")
    void recordEquality() {
        var s = schema("id");
        SourceRelationSymbol a = SourceRelationSymbol.of("Foo", s);
        SourceRelationSymbol b = SourceRelationSymbol.of("Foo", s);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}

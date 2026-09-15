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
package com.darkcollective.relix.symbol.function;

import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.SymbolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScalarFunctionSymbol — builder defaults, parameters, properties, body")
final class ScalarFunctionSymbolTest extends SymbolTestSupport {

    @Test
    @DisplayName("Builder applies sensible defaults for minimal function")
    void builderAppliesDefaults() {
        ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("myFunc").build();
        assertThat(fn.namespace()).isEqualTo("default");
        assertThat(fn.declaredName()).isEqualTo("myFunc");
        assertThat(fn.provenance()).isEqualTo(Provenance.USER);
        assertThat(fn.shadowPolicy()).isEqualTo(ShadowPolicy.PERMITTED);
        assertThat(fn.returnType()).isEqualTo(ScalarType.ANY);
        assertThat(fn.parameters()).isEmpty();
        assertThat(fn.properties()).isEmpty();
        assertThat(fn.body()).isEmpty();
    }

    @Test
    @DisplayName("Builder stores parameters in declaration order")
    void builderStoresParametersInOrder() {
        ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("add")
                .parameter("x", ScalarType.NUMBER)
                .parameter("y", ScalarType.NUMBER)
                .returnType(ScalarType.NUMBER)
                .build();
        assertThat(fn.parameters()).hasSize(2);
        assertThat(fn.parameters().get(0)).isEqualTo(new ParameterDefinition("x", ScalarType.NUMBER));
        assertThat(fn.parameters().get(1)).isEqualTo(new ParameterDefinition("y", ScalarType.NUMBER));
    }

    @Test
    @DisplayName("parameterSignature() reflects parameter types in order")
    void parameterSignatureReflectsParameterTypes() {
        ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("f")
                .parameter("a", ScalarType.STRING)
                .parameter("b", ScalarType.NUMBER)
                .build();
        assertThat(fn.parameterSignature())
                .containsExactly(ScalarType.STRING, ScalarType.NUMBER);
    }

    @Test
    @DisplayName("parameterSignature() is empty for zero-argument function")
    void parameterSignatureEmptyForZeroArgFunction() {
        ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("now").build();
        assertThat(fn.parameterSignature()).isEmpty();
    }

    @Test
    @DisplayName("Builder accumulates multiple function properties")
    void builderAccumulatesProperties() {
        ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("abs")
                .property(FunctionProperty.PURE)
                .property(FunctionProperty.DETERMINISTIC)
                .build();
        assertThat(fn.properties())
                .containsExactlyInAnyOrder(FunctionProperty.PURE, FunctionProperty.DETERMINISTIC);
    }

    @Test
    @DisplayName("Properties set is unmodifiable")
    void propertiesSetIsUnmodifiable() {
        ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("f")
                .property(FunctionProperty.COMMUTATIVE)
                .build();
        assertThatThrownBy(() -> fn.properties().add(FunctionProperty.PURE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Parameters list is unmodifiable")
    void parametersListIsUnmodifiable() {
        ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("f")
                .parameter("x", ScalarType.NUMBER)
                .build();
        assertThatThrownBy(() -> fn.parameters().add(new ParameterDefinition("y", ScalarType.STRING)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Builder stores body expression when provided")
    void builderStoresBodyWhenProvided() {
        NumberOperand expr = num("42");
        ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("answer")
                .body(expr)
                .build();
        assertThat(fn.body()).isEqualTo(Optional.of(expr));
    }

    @Test
    @DisplayName("canonicalName() is lower-cased declaredName")
    void canonicalNameIsLowerCased() {
        ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("MyFunc").build();
        assertThat(fn.canonicalName()).isEqualTo("myfunc");
        assertThat(fn.declaredName()).isEqualTo("MyFunc");
    }

    @Test
    @DisplayName("Builder configures a builtin function correctly")
    void builderConfiguresBuiltinFunction() {
        ScalarFunctionSymbol fn = ScalarFunctionSymbol.builder("length")
                .namespace("builtin")
                .provenance(Provenance.BUILTIN)
                .shadowPolicy(ShadowPolicy.FORBIDDEN)
                .parameter("s", ScalarType.STRING)
                .returnType(ScalarType.NUMBER)
                .property(FunctionProperty.PURE)
                .build();
        assertThat(fn.namespace()).isEqualTo("builtin");
        assertThat(fn.provenance()).isEqualTo(Provenance.BUILTIN);
        assertThat(fn.shadowPolicy()).isEqualTo(ShadowPolicy.FORBIDDEN);
        assertThat(fn.returnType()).isEqualTo(ScalarType.NUMBER);
        assertThat(fn.properties()).contains(FunctionProperty.PURE);
    }

    @Test
    @DisplayName("Builder rejects blank function name")
    void builderRejectsBlankName() {
        assertThatThrownBy(() -> ScalarFunctionSymbol.builder(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Builder rejects null function name")
    void builderRejectsNullName() {
        assertThatThrownBy(() -> ScalarFunctionSymbol.builder(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects blank namespace in full constructor")
    void rejectsBlankNamespace() {
        assertThatThrownBy(() ->
                new ScalarFunctionSymbol("  ", "f", Provenance.USER, ShadowPolicy.PERMITTED,
                        List.of(), ScalarType.ANY, java.util.Set.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects blank declaredName in full constructor")
    void rejectsBlankDeclaredName() {
        assertThatThrownBy(() ->
                new ScalarFunctionSymbol("default", "  ", Provenance.USER, ShadowPolicy.PERMITTED,
                        List.of(), ScalarType.ANY, java.util.Set.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

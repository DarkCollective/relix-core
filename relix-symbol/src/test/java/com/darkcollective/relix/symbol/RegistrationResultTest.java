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
package com.darkcollective.relix.symbol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RegistrationResult — outcome predicates and immutability")
final class RegistrationResultTest extends SymbolTestSupport {

    private final Symbol symbol = dbRelation("Users");

    @Test
    @DisplayName("isSuccess() true when registered with no errors")
    void isSuccessWhenRegisteredNoErrors() {
        RegistrationResult result = new RegistrationResult(symbol, true, List.of());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasWarnings()).isFalse();
        assertThat(result.isRejected()).isFalse();
    }

    @Test
    @DisplayName("hasWarnings() true when registered with errors")
    void hasWarningsWhenRegisteredWithErrors() {
        SymbolError warning = new SymbolError(
                SymbolError.Kind.SHADOW_WARNING, "default", "Users", "shadowed");
        RegistrationResult result = new RegistrationResult(symbol, true, List.of(warning));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.isRejected()).isFalse();
    }

    @Test
    @DisplayName("isRejected() true when not registered")
    void isRejectedWhenNotRegistered() {
        SymbolError error = new SymbolError(
                SymbolError.Kind.SHADOW_FORBIDDEN, "builtin", "Users", "forbidden");
        RegistrationResult result = new RegistrationResult(symbol, false, List.of(error));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.hasWarnings()).isFalse();
        assertThat(result.isRejected()).isTrue();
    }

    @Test
    @DisplayName("errors list is unmodifiable")
    void errorsListIsUnmodifiable() {
        SymbolError error = new SymbolError(
                SymbolError.Kind.SHADOW_WARNING, "default", "X", "warn");
        RegistrationResult result = new RegistrationResult(symbol, true, List.of(error));
        assertThatThrownBy(() -> result.errors().add(error))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Mutating the source error list does not affect result")
    void sourceListMutationDoesNotAffectResult() {
        List<SymbolError> source = new ArrayList<>();
        RegistrationResult result = new RegistrationResult(symbol, true, source);
        source.add(new SymbolError(SymbolError.Kind.SHADOW_WARNING, "default", "X", "warn"));
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("Rejects null symbol")
    void rejectsNullSymbol() {
        assertThatThrownBy(() -> new RegistrationResult(null, true, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Rejects null errors list")
    void rejectsNullErrorsList() {
        assertThatThrownBy(() -> new RegistrationResult(symbol, true, null))
                .isInstanceOf(NullPointerException.class);
    }
}

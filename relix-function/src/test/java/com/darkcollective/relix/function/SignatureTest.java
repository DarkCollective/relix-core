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
package com.darkcollective.relix.function;

import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("Signatures")
final class SignatureTest {

    private static final ParameterDefinition S = new ParameterDefinition("s", ScalarType.STRING);

    @Nested
    @DisplayName("FunctionSignature")
    final class Scalar {

        @Test
        @DisplayName("of derives the arity from the parameter list")
        void ofDerivesTheArity() {
            FunctionSignature signature = FunctionSignature.of("Left", ScalarType.STRING, "string",
                    Set.of(FunctionProperty.PURE), S,
                    new ParameterDefinition("n", ScalarType.NUMBER));

            assertThat(signature.arity()).isEqualTo(Arity.exactly(2));
            assertThat(signature.parameters()).hasSize(2);
            assertThat(signature.docKey()).isEmpty();
            assertThat(signature.category()).isEqualTo("string");
        }

        @Test
        @DisplayName("indexes under the lower-cased name but keeps the declared spelling")
        void canonicalNameIsLowerCased() {
            FunctionSignature signature =
                    FunctionSignature.of("UCase", ScalarType.STRING, "string", Set.of(), S);

            assertThat(signature.canonicalName()).isEqualTo("ucase");
            assertThat(signature.name()).isEqualTo("UCase");
        }

        @Test
        @DisplayName("returns the declared type whatever the arguments are")
        void returnTypeIsFixedByDefault() {
            FunctionSignature signature =
                    FunctionSignature.of("Len", ScalarType.NUMBER, "string", Set.of(), S);

            assertThat(signature.returnTypeFor(List.of(ScalarType.STRING)))
                    .isEqualTo(ScalarType.NUMBER);
            assertThat(signature.returnTypeFor(List.of())).isEqualTo(ScalarType.NUMBER);
        }

        @Test
        @DisplayName("reports the properties it declares")
        void reportsItsProperties() {
            FunctionSignature signature = FunctionSignature.of("UCase", ScalarType.STRING,
                    "string", Set.of(FunctionProperty.PURE, FunctionProperty.IDEMPOTENT), S);

            assertThat(signature.has(FunctionProperty.PURE)).isTrue();
            assertThat(signature.has(FunctionProperty.IDEMPOTENT)).isTrue();
            assertThat(signature.has(FunctionProperty.COMMUTATIVE)).isFalse();
        }

        @Test
        @DisplayName("copies the lists it is handed")
        void copiesItsCollections() {
            List<ParameterDefinition> parameters = new ArrayList<>(List.of(S));
            FunctionSignature signature = new FunctionSignature("Len", parameters,
                    Arity.exactly(1), ScalarType.NUMBER, Set.of(), "string", Optional.empty());

            parameters.clear();

            assertThat(signature.parameters()).containsExactly(S);
        }

        @Test
        @DisplayName("rejects a blank name or category")
        void rejectsBlanks() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FunctionSignature.of(" ", ScalarType.STRING, "string", Set.of()))
                    .withMessageContaining("name must not be blank");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FunctionSignature.of("Len", ScalarType.NUMBER, " ", Set.of()))
                    .withMessageContaining("category must not be blank");
        }

        @Test
        @DisplayName("rejects a missing component")
        void rejectsNulls() {
            assertThatNullPointerException().isThrownBy(() ->
                    new FunctionSignature("Len", List.of(), Arity.exactly(0), null,
                            Set.of(), "string", Optional.empty()));
            assertThatNullPointerException().isThrownBy(() ->
                    new FunctionSignature("Len", List.of(), Arity.exactly(0), ScalarType.NUMBER,
                            Set.of(), "string", null));
        }
    }

    @Nested
    @DisplayName("AggregateSignature")
    final class Aggregate {

        @Test
        @DisplayName("of declares a NULL-skipping aggregate of the default category")
        void ofDeclaresTheSqlDefault() {
            AggregateSignature signature = AggregateSignature.of("Sum", ScalarType.NUMBER,
                    Set.of(AggregateProperty.ORDER_INSENSITIVE),
                    new ParameterDefinition("x", ScalarType.NUMBER));

            assertThat(signature.skipsNulls()).isTrue();
            assertThat(signature.category()).isEqualTo(AggregateSignature.AGGREGATE);
            assertThat(signature.arity()).isEqualTo(Arity.exactly(1));
            assertThat(signature.canonicalName()).isEqualTo("sum");
        }

        @Test
        @DisplayName("an aggregate that keeps NULLs says so")
        void nullKeepingIsDeclarable() {
            AggregateSignature signature = new AggregateSignature("Collect", List.of(),
                    Arity.exactly(1), ScalarType.ANY, Set.of(), false,
                    "aggregate", Optional.of("collect"));

            assertThat(signature.skipsNulls()).isFalse();
            assertThat(signature.docKey()).contains("collect");
        }

        @Test
        @DisplayName("reports the properties it declares")
        void reportsItsProperties() {
            AggregateSignature signature = AggregateSignature.of("Min", ScalarType.ANY,
                    Set.of(AggregateProperty.DUPLICATE_INSENSITIVE,
                            AggregateProperty.ORDER_INSENSITIVE));

            assertThat(signature.has(AggregateProperty.DUPLICATE_INSENSITIVE)).isTrue();
            assertThat(signature.returnTypeFor(List.of(ScalarType.NUMBER)))
                    .isEqualTo(ScalarType.ANY);
        }

        @Test
        @DisplayName("rejects a blank name or category")
        void rejectsBlanks() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AggregateSignature.of("", ScalarType.NUMBER, Set.of()))
                    .withMessageContaining("name must not be blank");
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new AggregateSignature("Sum", List.of(), Arity.exactly(1),
                            ScalarType.NUMBER, Set.of(), true, "", Optional.empty()))
                    .withMessageContaining("category must not be blank");
        }

        @Test
        @DisplayName("rejects a missing component")
        void rejectsNulls() {
            assertThatNullPointerException().isThrownBy(() ->
                    new AggregateSignature("Sum", null, Arity.exactly(1),
                            ScalarType.NUMBER, Set.of(), true, "aggregate", Optional.empty()));
        }
    }
}

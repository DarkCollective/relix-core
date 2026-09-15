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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SemanticResult — success, failure, partial, predicates")
final class SemanticResultTest {

    private static SemanticModel emptyModel() {
        return new SemanticModel(
                "default",
                new InMemorySymbolTable(),
                Map.of(),
                SchemaAnnotations.empty(),
                List.of());
    }

    private static SemanticError anError() {
        return SemanticError.error("./test.relix", 1, 1, "Some error");
    }

    @Nested
    @DisplayName("success()")
    class Success {

        @Test
        @DisplayName("hasModel=true, hasErrors=false, isFullyValid=true")
        void predicates() {
            var result = SemanticResult.success(emptyModel());
            assertThat(result.hasModel()).isTrue();
            assertThat(result.hasErrors()).isFalse();
            assertThat(result.hasDiagnostics()).isFalse();
            assertThat(result.isFullyValid()).isTrue();
        }

        @Test
        @DisplayName("Model is present and matches what was passed")
        void modelIsPresent() {
            var model = emptyModel();
            var result = SemanticResult.success(model);
            assertThat(result.model()).contains(model);
        }

        @Test
        @DisplayName("Errors list is empty")
        void errorsIsEmpty() {
            assertThat(SemanticResult.success(emptyModel()).errors()).isEmpty();
        }

        @Test
        @DisplayName("Null model throws NullPointerException")
        void nullModelThrows() {
            assertThatThrownBy(() -> SemanticResult.success(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("the fourth combination — no errors, and no model either")
    class NoErrorsNoModel {

        @Test
        @DisplayName("isFullyValid needs a model, not merely the absence of errors")
        void quietFailureIsNotValid() {
            // isFullyValid is `!hasErrors() && hasModel()`, and the success/failure
            // factories only ever produce the two agreeing combinations. The record is
            // public, so a caller can build the disagreeing one — and it must not read as
            // valid, or a consumer would call model().orElseThrow() on a clean result.
            var quiet = new SemanticResult(Optional.empty(), List.of());
            assertThat(quiet.hasErrors()).isFalse();
            assertThat(quiet.hasModel()).isFalse();
            assertThat(quiet.isFullyValid())
                    .as("no errors is not the same as a usable model")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("failure()")
    class Failure {

        @Test
        @DisplayName("hasModel=false, hasErrors=true, isFullyValid=false")
        void predicates() {
            var result = SemanticResult.failure(List.of(anError()));
            assertThat(result.hasModel()).isFalse();
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.isFullyValid()).isFalse();
        }

        @Test
        @DisplayName("Model is absent")
        void modelIsAbsent() {
            assertThat(SemanticResult.failure(List.of(anError())).model()).isEmpty();
        }

        @Test
        @DisplayName("Errors are present")
        void errorsPresent() {
            var e = anError();
            var result = SemanticResult.failure(List.of(e));
            assertThat(result.errors()).containsExactly(e);
        }

        @Test
        @DisplayName("Null errors throws NullPointerException")
        void nullErrorsThrows() {
            assertThatThrownBy(() -> SemanticResult.failure(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("partial()")
    class Partial {

        @Test
        @DisplayName("hasModel=true, hasErrors=true, isFullyValid=false")
        void predicates() {
            var result = SemanticResult.partial(emptyModel(), List.of(anError()));
            assertThat(result.hasModel()).isTrue();
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.isFullyValid()).isFalse();
        }

        @Test
        @DisplayName("Both model and errors are accessible")
        void bothAccessible() {
            var model = emptyModel();
            var e = anError();
            var result = SemanticResult.partial(model, List.of(e));
            assertThat(result.model()).contains(model);
            assertThat(result.errors()).containsExactly(e);
        }

        @Test
        @DisplayName("Null model throws NullPointerException")
        void nullModelThrows() {
            assertThatThrownBy(() -> SemanticResult.partial(null, List.of(anError())))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null errors throws NullPointerException")
        void nullErrorsThrows() {
            assertThatThrownBy(() -> SemanticResult.partial(emptyModel(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Warning-only result")
    class WarningOnly {

        @Test
        @DisplayName("hasErrors=false but hasDiagnostics=true for warnings-only result")
        void warningOnlyIsNotAnError() {
            var warning = SemanticError.warning("./f.relix", 1, 1, "Unused import");
            var result = SemanticResult.partial(emptyModel(), List.of(warning));
            assertThat(result.hasDiagnostics()).isTrue();
            assertThat(result.hasErrors()).isFalse();   // warnings are not errors
            assertThat(result.isFullyValid()).isTrue();  // no errors + has model
        }
    }

    @Nested
    @DisplayName("Defensive copy of errors list")
    class DefensiveCopy {

        @Test
        @DisplayName("Mutating the original errors list does not affect the result")
        void errorsAreCopied() {
            var errors = new ArrayList<SemanticError>();
            errors.add(anError());
            var result = SemanticResult.failure(errors);
            errors.add(anError()); // mutate original
            assertThat(result.errors()).hasSize(1);
        }
    }
}

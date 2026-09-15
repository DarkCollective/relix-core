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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two claims {@link SemanticResultAssert} was written for, tested as claims about
 * the <em>message</em>: {@code isFullyValid()} must print the diagnostics it found —
 * the thing {@code assertThat(result.isFullyValid()).isTrue()} withholds — and
 * {@code messages()} must be the one home for a projection ten test classes each
 * defined for themselves.
 */
@DisplayName("SemanticResultAssert — analysis assertions that print the diagnostics")
final class SemanticResultAssertTest {

    private static final String VALID = """
            Users := [| id | name  |
                       | 1  | Alice |];
            query { σ id = 1 (Users) };
            """;

    private static final String UNKNOWN_RELATION = "query { σ id = 1 (NoSuchRelation) };";

    @Nested
    @DisplayName("validity")
    class Validity {

        @Test
        @DisplayName("a clean script is fully valid")
        void valid() {
            assertThat(analyze(VALID)).isFullyValid().hasNoErrors();
        }

        @Test
        @DisplayName("isFullyValid prints the diagnostics — the whole reason to state it this way")
        void failurePrintsDiagnostics() {
            assertThatThrownBy(() -> assertThat(analyze(UNKNOWN_RELATION)).isFullyValid())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("NoSuchRelation");
        }

        @Test
        @DisplayName("hasErrors reports 'no diagnostics' rather than 'expected true'")
        void hasErrorsOnACleanScript() {
            assertThatThrownBy(() -> assertThat(analyze(VALID)).hasErrors())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected at least one error, but no diagnostics");
        }

        @Test
        @DisplayName("an invalid script has errors")
        void invalid() {
            assertThat(analyze(UNKNOWN_RELATION)).hasErrors();
        }
    }

    @Nested
    @DisplayName("messages")
    class Messages {

        @Test
        @DisplayName("hasErrorContaining is the substring claim nearly every error test makes")
        void containing() {
            assertThat(analyze(UNKNOWN_RELATION)).hasErrorContaining("NoSuchRelation");
        }

        @Test
        @DisplayName("a missing substring lists what was reported instead")
        void missingSubstring() {
            assertThatThrownBy(() -> assertThat(analyze(UNKNOWN_RELATION)).hasErrorContaining("Orders"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected a diagnostic containing \"Orders\"")
                    .hasMessageContaining("NoSuchRelation");
        }

        @Test
        @DisplayName("hasNoErrorContaining is the narrower claim that one complaint is gone")
        void notContaining() {
            assertThat(analyze(UNKNOWN_RELATION)).hasNoErrorContaining("Users");
            assertThatThrownBy(() -> assertThat(analyze(UNKNOWN_RELATION)).hasNoErrorContaining("NoSuchRelation"))
                    .isInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("messages() hands the projection to AssertJ — the ten copies' single home")
        void messages() {
            assertThat(analyze(VALID)).messages().isEmpty();
            assertThat(analyze(UNKNOWN_RELATION)).messages()
                    .anyMatch(m -> m.contains("NoSuchRelation"));
        }

        @Test
        @DisplayName("hasDiagnosticCount counts warnings alongside errors")
        void count() {
            assertThat(analyze(VALID)).hasDiagnosticCount(0);
            assertThatThrownBy(() -> assertThat(analyze(UNKNOWN_RELATION)).hasDiagnosticCount(0))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected 0 diagnostic(s)");
        }
    }

    @Nested
    @DisplayName("the model")
    class Model {

        @Test
        @DisplayName("model() returns it when analysis produced one")
        void present() {
            assertThat(assertThat(analyze(VALID)).model().rootQueries()).hasSize(1);
        }

        @Test
        @DisplayName("model() fails with the diagnostics when it did not")
        void absent() {
            SemanticResult noModel = SemanticResult.failure(
                    java.util.List.of(SemanticError.error("x.relix", 1, 1, "boom")));
            assertThatThrownBy(() -> assertThat(noModel).model())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected a model")
                    .hasMessageContaining("boom");
        }
    }
}

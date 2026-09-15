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

import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ListAssert;

import java.util.List;

/**
 * Assertions on a {@link SemanticResult}, whose failures print the diagnostics.
 *
 * <p>Two things brought this into existence, and both are about what the suite
 * prints rather than what it costs to write.
 *
 * <p>The first is {@link #isFullyValid()}.  Written as
 * {@code assertThat(result.isFullyValid()).isTrue()} — which is how nearly every
 * site writes it — a regression reports {@code expecting actual to be true} and
 * withholds the very errors the analyser just produced.  Here the errors are the
 * failure message.
 *
 * <p>The second is {@link #messages()}.  Ten test classes across two modules each
 * defined {@code result.errors().stream().map(SemanticError::message).toList()},
 * under four different names, because there was nowhere to put it — the same
 * duplication {@link SemanticFixtures} was created to end for {@code analyze()},
 * on the assertion side, where it had never been done.
 *
 * <p>Obtain one from {@link SemanticAssertions#assertThat(SemanticResult)}.
 *
 * @see SemanticAssertions
 */
public final class SemanticResultAssert extends AbstractAssert<SemanticResultAssert, SemanticResult> {

    SemanticResultAssert(SemanticResult actual) {
        super(actual, SemanticResultAssert.class);
        if (actual != null) {
            as("analysis produced %s", diagnostics(actual));
        }
    }

    /**
     * Asserts analysis completed with a model and no diagnostics at all.
     *
     * <p>On failure the message lists what was reported, which is the whole reason
     * to state it this way.
     *
     * @return this assert, for chaining
     */
    public SemanticResultAssert isFullyValid() {
        isNotNull();
        if (!actual.isFullyValid()) {
            failWithMessage("expected a fully valid analysis, but %s",
                    actual.hasModel() ? diagnostics(actual) : "no model was produced and " + diagnostics(actual));
        }
        return this;
    }

    /**
     * Asserts analysis produced no {@link Severity#ERROR} diagnostics, allowing
     * warnings.
     *
     * @return this assert, for chaining
     */
    public SemanticResultAssert hasNoErrors() {
        isNotNull();
        if (actual.hasErrors()) {
            failWithMessage("expected no errors, but %s", diagnostics(actual));
        }
        return this;
    }

    /**
     * Asserts analysis reported at least one {@link Severity#ERROR}.
     *
     * @return this assert, for chaining
     */
    public SemanticResultAssert hasErrors() {
        isNotNull();
        if (!actual.hasErrors()) {
            failWithMessage("expected at least one error, but %s", diagnostics(actual));
        }
        return this;
    }

    /**
     * Asserts exactly {@code count} diagnostics were reported, errors and warnings
     * alike.
     *
     * @param count the expected diagnostic count
     * @return this assert, for chaining
     */
    public SemanticResultAssert hasDiagnosticCount(int count) {
        isNotNull();
        if (actual.errors().size() != count) {
            failWithMessage("expected %d diagnostic(s) but %s", count, diagnostics(actual));
        }
        return this;
    }

    /**
     * Asserts some diagnostic's message contains {@code fragment}.
     *
     * <p>The dominant shape of an error assertion in this suite: the message is
     * checked by substring because the position and phrasing around it are not the
     * claim.
     *
     * @param fragment the substring an error message must contain
     * @return this assert, for chaining
     */
    public SemanticResultAssert hasErrorContaining(String fragment) {
        isNotNull();
        if (messageList().stream().noneMatch(m -> m.contains(fragment))) {
            failWithMessage("expected a diagnostic containing %s, but %s",
                    "\"" + fragment + "\"", diagnostics(actual));
        }
        return this;
    }

    /**
     * Asserts no diagnostic's message contains {@code fragment} — the claim a test
     * makes when it says one specific complaint was fixed, without claiming the
     * script is clean.
     *
     * @param fragment the substring no error message may contain
     * @return this assert, for chaining
     */
    public SemanticResultAssert hasNoErrorContaining(String fragment) {
        isNotNull();
        if (messageList().stream().anyMatch(m -> m.contains(fragment))) {
            failWithMessage("expected no diagnostic containing %s, but %s",
                    "\"" + fragment + "\"", diagnostics(actual));
        }
        return this;
    }

    /**
     * Asserts a model was produced, and returns it — for the tests whose subject is
     * the model rather than the diagnostics.
     *
     * @return the model
     */
    public SemanticModel model() {
        isNotNull();
        return actual.model().orElseGet(() -> {
            failWithMessage("expected a model, but none was produced and %s", diagnostics(actual));
            return null;   // unreachable; failWithMessage throws
        });
    }

    /**
     * Hands the diagnostic messages to AssertJ, for the claims a list assert already
     * states well ({@code containsExactly}, {@code anyMatch}, {@code isEmpty}).
     *
     * <p>This is the single home for the message projection ten test classes each
     * wrote for themselves.
     *
     * @return an assert on the diagnostic messages, in report order
     */
    public ListAssert<String> messages() {
        isNotNull();
        return Assertions.assertThat(messageList()).as("diagnostic messages");
    }

    /**
     * Hands the messages of the {@link Severity#ERROR} diagnostics alone to AssertJ.
     *
     * <p>Distinct from {@link #messages()} in exactly the way {@link #hasErrors()} is
     * distinct from {@link #hasDiagnosticCount(int)}: a warning is a diagnostic and is
     * not an error, and a test that means one should not be able to write the other by
     * accident.
     *
     * @return an assert on the error messages, in report order
     */
    public ListAssert<String> errorMessages() {
        isNotNull();
        return Assertions.assertThat(actual.errors().stream()
                .filter(e -> e.severity() == Severity.ERROR)
                .map(SemanticError::message).toList()).as("error messages");
    }

    private List<String> messageList() {
        return SemanticFixtures.errorMessages(actual);
    }

    /** Renders the diagnostics as the failure message's subject. */
    private static String diagnostics(SemanticResult result) {
        if (result.errors().isEmpty()) {
            return "no diagnostics";
        }
        return "these diagnostics:" + System.lineSeparator()
                + result.errors().stream().map(e -> "    " + e)
                        .reduce((a, b) -> a + System.lineSeparator() + b).orElse("");
    }
}

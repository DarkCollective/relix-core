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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.optimizer.internal.OptimizationContext;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ListAssert;

import java.util.List;

/**
 * Assertions on an {@link OptimizationContext}'s audit trail, whose failures list the
 * rules that did fire.
 *
 * <p>This one is close to sugar and was written for a single failure mode.
 * {@code assertThat(ctx.countOf(SEL_003)).isEqualTo(1)} is already a one-liner, and
 * wrapping a one-liner is a loss — but when it goes red it says
 * {@code expected 1 but was 0}, and the question a reader has at that point is
 * always <em>which rule fired instead</em>.  In a pipeline test, where a rewrite
 * failing to fire usually means a different rule matched first, that is the whole
 * diagnosis, and today it costs a re-run.  {@link #fired(OptimizationCode)} answers
 * it in the failure message.
 *
 * <p>Obtain one from {@link OptimizerAssertions#assertThat(OptimizationContext)}.
 *
 * @see OptimizerAssertions
 */
public final class OptimizationContextAssert
        extends AbstractAssert<OptimizationContextAssert, OptimizationContext> {

    OptimizationContextAssert(OptimizationContext actual) {
        super(actual, OptimizationContextAssert.class);
        if (actual != null) {
            as("optimizer recorded %s", trail(actual));
        }
    }

    /**
     * Asserts the rule fired at least once.
     *
     * @param code the rule that must have fired
     * @return this assert, for chaining
     */
    public OptimizationContextAssert fired(OptimizationCode code) {
        isNotNull();
        if (actual.countOf(code) == 0) {
            failWithMessage("expected %s to fire, but %s", code.code(), trail(actual));
        }
        return this;
    }

    /**
     * Asserts the rule fired exactly {@code times} times.
     *
     * @param code  the rule to count
     * @param times the expected number of firings
     * @return this assert, for chaining
     */
    public OptimizationContextAssert fired(OptimizationCode code, int times) {
        isNotNull();
        long count = actual.countOf(code);
        if (count != times) {
            failWithMessage("expected %s to fire %d time(s) but it fired %d; %s",
                    code.code(), times, count, trail(actual));
        }
        return this;
    }

    /**
     * Asserts the rule did not fire — the claim a guard test makes when it says a
     * rewrite was correctly declined.
     *
     * @param code the rule that must not have fired
     * @return this assert, for chaining
     */
    public OptimizationContextAssert didNotFire(OptimizationCode code) {
        isNotNull();
        long count = actual.countOf(code);
        if (count != 0) {
            failWithMessage("expected %s not to fire, but it fired %d time(s); %s",
                    code.code(), count, trail(actual));
        }
        return this;
    }

    /**
     * Asserts nothing was rewritten at all.
     *
     * @return this assert, for chaining
     */
    public OptimizationContextAssert recordedNothing() {
        isNotNull();
        if (!actual.isEmpty()) {
            failWithMessage("expected no transformation, but %s", trail(actual));
        }
        return this;
    }

    /**
     * Hands the recorded rule codes to AssertJ, for the claims a list assert states
     * well — a pipeline test asserting the exact sequence of rules that ran.
     *
     * @return an assert on the codes, in the order they were recorded
     */
    public ListAssert<String> codes() {
        isNotNull();
        return Assertions.assertThat(codeList()).as("recorded rule codes");
    }

    private List<String> codeList() {
        return actual.records().stream().map(r -> r.code().code()).toList();
    }

    /** Renders the audit trail as the failure message's subject. */
    private static String trail(OptimizationContext ctx) {
        if (ctx.isEmpty()) {
            return "no transformations";
        }
        return "these transformations:" + System.lineSeparator()
                + ctx.records().stream()
                        .map(r -> "    " + r.code().code() + "  " + r.relationName() + "  " + r.detail())
                        .reduce((a, b) -> a + System.lineSeparator() + b).orElse("");
    }
}

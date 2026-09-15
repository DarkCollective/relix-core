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

import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Argument")
final class ArgumentTest {

    @Test
    @DisplayName("of hands over a value that is already computed")
    void ofHandsOverAValue() {
        Value value = new StringValue("x");

        assertThat(Argument.of(value).value()).isSameAs(value);
    }

    @Test
    @DisplayName("memoizing evaluates once however often it is read")
    void memoizingEvaluatesOnce() {
        AtomicInteger evaluations = new AtomicInteger();
        Argument argument = Argument.memoizing(() -> {
            evaluations.incrementAndGet();
            return new StringValue("computed");
        });

        assertThat(argument.value()).isEqualTo(new StringValue("computed"));
        assertThat(argument.value()).isEqualTo(new StringValue("computed"));
        assertThat(evaluations)
                .as("a lazy function may read an argument in two branches; that must not "
                        + "evaluate the expression behind it twice")
                .hasValue(1);
    }

    @Test
    @DisplayName("memoizing defers evaluation until it is asked")
    void memoizingDefers() {
        AtomicInteger evaluations = new AtomicInteger();
        Argument argument = Argument.memoizing(() -> {
            evaluations.incrementAndGet();
            return new StringValue("computed");
        });

        assertThat(evaluations).hasValue(0);
        argument.value();
        assertThat(evaluations).hasValue(1);
    }

    @Test
    @DisplayName("a failing argument fails where it is read, not where it is built")
    void failureSurfacesOnRead() {
        Argument argument = Argument.memoizing(() -> {
            throw new IllegalStateException("not numeric");
        });

        assertThatThrownBy(argument::value)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("not numeric");
    }

    @Test
    @DisplayName("rejects an evaluation that produced no value")
    void rejectsANullResult() {
        Argument argument = Argument.memoizing(() -> null);

        assertThatNullPointerException().isThrownBy(argument::value)
                .withMessageContaining("evaluated to null");
    }

    @Test
    @DisplayName("rejects a missing value or supplier")
    void rejectsMissingInputs() {
        assertThatNullPointerException().isThrownBy(() -> Argument.of(null));
        assertThatNullPointerException().isThrownBy(() -> Argument.memoizing(null));
    }
}

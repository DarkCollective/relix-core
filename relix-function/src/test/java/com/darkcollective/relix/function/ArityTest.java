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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("Arity")
final class ArityTest {

    @Test
    @DisplayName("exactly accepts one count and nothing else")
    void exactlyAcceptsOneCount() {
        Arity two = Arity.exactly(2);

        assertThat(two.accepts(2)).isTrue();
        assertThat(two.accepts(1)).isFalse();
        assertThat(two.accepts(3)).isFalse();
        assertThat(two.isUnbounded()).isFalse();
        assertThat(two.describe()).isEqualTo("2");
    }

    @Test
    @DisplayName("between accepts the whole inclusive range")
    void betweenAcceptsTheRange() {
        Arity range = Arity.between(1, 3);

        assertThat(range.accepts(0)).isFalse();
        assertThat(range.accepts(1)).isTrue();
        assertThat(range.accepts(3)).isTrue();
        assertThat(range.accepts(4)).isFalse();
        assertThat(range.describe()).isEqualTo("1 to 3");
    }

    @Test
    @DisplayName("atLeast accepts any count from its minimum up")
    void atLeastHasNoCeiling() {
        Arity open = Arity.atLeast(1);

        assertThat(open.accepts(0)).isFalse();
        assertThat(open.accepts(1)).isTrue();
        assertThat(open.accepts(1_000)).isTrue();
        assertThat(open.isUnbounded()).isTrue();
        assertThat(open.max()).isEqualTo(Arity.UNBOUNDED);
        assertThat(open.describe()).isEqualTo("at least 1");
    }

    @Test
    @DisplayName("a nullary function accepts no arguments at all")
    void nullaryAcceptsNothing() {
        Arity none = Arity.exactly(0);

        assertThat(none.accepts(0)).isTrue();
        assertThat(none.accepts(1)).isFalse();
    }

    @Test
    @DisplayName("rejects a negative minimum")
    void rejectsANegativeMinimum() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Arity(-1, 2))
                .withMessageContaining("must not be negative");
    }

    @Test
    @DisplayName("rejects a maximum below the minimum")
    void rejectsAnInvertedRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Arity(3, 2))
                .withMessageContaining("below the minimum");
    }
}

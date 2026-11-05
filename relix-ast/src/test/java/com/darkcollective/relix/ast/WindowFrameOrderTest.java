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
package com.darkcollective.relix.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("WindowFrame.Between — an end before its start")
final class WindowFrameOrderTest {

    private static WindowFrame.Bound number(boolean preceding, String n) {
        Operand offset = Expr.num(n);
        return preceding ? WindowFrame.Bound.preceding(offset) : WindowFrame.Bound.following(offset);
    }

    private static WindowFrame.Bound days(boolean preceding, long n) {
        Operand offset = Expr.duration(Duration.ofDays(n));
        return preceding ? WindowFrame.Bound.preceding(offset) : WindowFrame.Bound.following(offset);
    }

    @Test
    @DisplayName("two PRECEDING ends: the start must reach back at least as far as the end")
    void preceding() {
        assertThat(new WindowFrame.Between(WindowFrame.Unit.ROWS, number(true, "2"), number(true, "1"))).isNotNull();
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WindowFrame.Between(WindowFrame.Unit.ROWS, number(true, "1"), number(true, "2")))
                .withMessageContaining("cannot end before it starts");
    }

    @Test
    @DisplayName("two FOLLOWING ends: the end must reach forward at least as far as the start")
    void following() {
        assertThat(new WindowFrame.Between(WindowFrame.Unit.ROWS, number(false, "1"), number(false, "1"))).isNotNull();
        assertThatIllegalArgumentException().isThrownBy(() ->
                new WindowFrame.Between(WindowFrame.Unit.ROWS, number(false, "1"), number(false, "0")))
                .withMessageContaining("cannot end before it starts");
    }

    @Test
    @DisplayName("offsets that are not both numbers are not compared")
    void mixedOffsetsAreNotCompared() {
        assertThat(new WindowFrame.Between(WindowFrame.Unit.RANGE, number(true, "1"), days(true, 2))).isNotNull();
        assertThat(new WindowFrame.Between(WindowFrame.Unit.RANGE, days(true, 1), number(true, "2"))).isNotNull();
        assertThat(new WindowFrame.Between(WindowFrame.Unit.RANGE, days(true, 1), days(true, 2))).isNotNull();
    }
}

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
package com.darkcollective.relix.provenance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class TropicalSemiringTest {

    private static final TropicalSemiring S = TropicalSemiring.INSTANCE;

    @Test
    void identitiesAreInfinityAndZero() {
        assertThat(S.zero()).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(S.one()).isEqualTo(0.0d);
    }

    @Test
    void plusIsMin() {
        assertThat(S.plus(2.0d, 5.0d)).isEqualTo(2.0d);
        assertThat(S.plus(5.0d, 2.0d)).isEqualTo(2.0d);
    }

    @Test
    void timesIsAddition() {
        assertThat(S.times(2.0d, 5.0d)).isEqualTo(7.0d);
    }

    @Test
    void infinityAnnihilatesUnderTimes() {
        assertThat(S.times(Double.POSITIVE_INFINITY, 5.0d))
                .isEqualTo(Double.POSITIVE_INFINITY);
    }
}

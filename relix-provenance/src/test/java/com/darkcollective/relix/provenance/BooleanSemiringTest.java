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

final class BooleanSemiringTest {

    private static final BooleanSemiring S = BooleanSemiring.INSTANCE;

    @Test
    void identitiesAreFalseAndTrue() {
        assertThat(S.zero()).isFalse();
        assertThat(S.one()).isTrue();
    }

    @Test
    void plusIsLogicalOr() {
        assertThat(S.plus(false, false)).isFalse();
        assertThat(S.plus(false, true)).isTrue();
        assertThat(S.plus(true, false)).isTrue();
        assertThat(S.plus(true, true)).isTrue();
    }

    @Test
    void timesIsLogicalAnd() {
        assertThat(S.times(false, false)).isFalse();
        assertThat(S.times(false, true)).isFalse();
        assertThat(S.times(true, false)).isFalse();
        assertThat(S.times(true, true)).isTrue();
    }
}

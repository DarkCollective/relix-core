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

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

final class CountingSemiringTest {

    private static final CountingSemiring S = CountingSemiring.INSTANCE;

    @Test
    void identitiesAreZeroAndOne() {
        assertThat(S.zero()).isEqualTo(BigInteger.ZERO);
        assertThat(S.one()).isEqualTo(BigInteger.ONE);
    }

    @Test
    void plusAdds() {
        assertThat(S.plus(BigInteger.valueOf(3), BigInteger.valueOf(4)))
                .isEqualTo(BigInteger.valueOf(7));
    }

    @Test
    void timesMultiplies() {
        assertThat(S.times(BigInteger.valueOf(3), BigInteger.valueOf(4)))
                .isEqualTo(BigInteger.valueOf(12));
    }

    @Test
    void usesArbitraryPrecisionWithoutOverflow() {
        BigInteger big = BigInteger.valueOf(Long.MAX_VALUE);
        assertThat(S.times(big, BigInteger.TWO))
                .isEqualTo(big.shiftLeft(1))
                .isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE));
    }
}

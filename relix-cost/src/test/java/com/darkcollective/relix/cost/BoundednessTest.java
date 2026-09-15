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
package com.darkcollective.relix.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.cost.Boundedness.BOUNDED;
import static com.darkcollective.relix.cost.Boundedness.UNBOUNDED;
import static com.darkcollective.relix.cost.Boundedness.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Boundedness — the BOUNDED < UNKNOWN < UNBOUNDED lattice")
final class BoundednessTest {

    @Test
    @DisplayName("lub is the more-unbounded of the two (contagious upward)")
    void lub() {
        assertThat(BOUNDED.lub(BOUNDED)).isEqualTo(BOUNDED);
        assertThat(BOUNDED.lub(UNKNOWN)).isEqualTo(UNKNOWN);
        assertThat(UNKNOWN.lub(BOUNDED)).isEqualTo(UNKNOWN);
        assertThat(BOUNDED.lub(UNBOUNDED)).isEqualTo(UNBOUNDED);
        assertThat(UNBOUNDED.lub(BOUNDED)).isEqualTo(UNBOUNDED);
        assertThat(UNKNOWN.lub(UNBOUNDED)).isEqualTo(UNBOUNDED);
        assertThat(UNBOUNDED.lub(UNBOUNDED)).isEqualTo(UNBOUNDED);
    }

    @Test
    @DisplayName("lub is idempotent")
    void idempotent() {
        for (Boundedness b : Boundedness.values()) {
            assertThat(b.lub(b)).isEqualTo(b);
        }
    }

    @Test
    @DisplayName("ALL_BOUNDED source reports BOUNDED for any name")
    void allBoundedSource() {
        assertThat(BoundednessSource.ALL_BOUNDED.boundednessOf("anything")).isEqualTo(BOUNDED);
    }
}

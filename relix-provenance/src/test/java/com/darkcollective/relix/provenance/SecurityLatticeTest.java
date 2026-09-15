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

import static com.darkcollective.relix.provenance.SecurityLevel.CONFIDENTIAL;
import static com.darkcollective.relix.provenance.SecurityLevel.PUBLIC;
import static com.darkcollective.relix.provenance.SecurityLevel.SECRET;
import static com.darkcollective.relix.provenance.SecurityLevel.TOP_SECRET;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class SecurityLatticeTest {

    private static final SecurityLattice S = SecurityLattice.INSTANCE;

    @Test
    void identitiesAreTopSecretAndPublic() {
        assertThat(S.zero()).isEqualTo(TOP_SECRET);
        assertThat(S.one()).isEqualTo(PUBLIC);
    }

    @Test
    void plusKeepsTheMostAccessibleLevel() {
        assertThat(S.plus(SECRET, CONFIDENTIAL)).isEqualTo(CONFIDENTIAL);
        assertThat(S.plus(CONFIDENTIAL, SECRET)).isEqualTo(CONFIDENTIAL);
        assertThat(S.plus(PUBLIC, TOP_SECRET)).isEqualTo(PUBLIC);
    }

    @Test
    void timesDemandsTheHighestClearance() {
        assertThat(S.times(SECRET, CONFIDENTIAL)).isEqualTo(SECRET);
        assertThat(S.times(CONFIDENTIAL, SECRET)).isEqualTo(SECRET);
        assertThat(S.times(PUBLIC, TOP_SECRET)).isEqualTo(TOP_SECRET);
    }

    @Test
    void operationsAreIdempotentOnEqualLevels() {
        assertThat(S.plus(SECRET, SECRET)).isEqualTo(SECRET);
        assertThat(S.times(SECRET, SECRET)).isEqualTo(SECRET);
    }
}

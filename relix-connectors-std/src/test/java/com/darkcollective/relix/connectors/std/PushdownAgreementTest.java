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
package com.darkcollective.relix.connectors.std;

import com.darkcollective.relix.plan.internal.Dialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.stream.Stream;

/**
 * The {@link PushdownCorpus} against H2, which resolves to the {@link Dialect#GENERIC}
 * dialect — the one every unidentified backend gets, and therefore the one whose SQL
 * has to be portable rather than clever.
 *
 * <p>This suite is in the gate. Its MySQL counterpart is in the {@code integration}
 * tier, needs Docker, and runs the same corpus: see {@code MySqlPushdownAgreementTest}
 * in this package for why one database is not enough.
 *
 * @see PushdownAgreement
 */
@DisplayName("A pushed-down query returns what the in-engine one returns (H2 / GENERIC)")
final class PushdownAgreementTest {

    private static final String URL = "jdbc:h2:mem:pushdown_agreement;DB_CLOSE_DELAY=-1";

    private static final PushdownAgreement AGREEMENT =
            new PushdownAgreement(PushdownFixture.preamble(URL, null, null), Dialect.GENERIC);

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL)) {
            PushdownFixture.seed(c);
        }
    }

    @TestFactory
    Stream<DynamicNode> agreement() {
        return PushdownCorpus.tests(AGREEMENT);
    }
}

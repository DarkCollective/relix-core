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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.stream.Stream;

/**
 * The pushdown corpus against a real Db2, in the {@code integration} tier.
 *
 * <p>Db2 11.5 answered most of what it was expected to make difficult: it has
 * {@code NULLS LAST}, a boolean type, typed temporal literals, {@code LATERAL} in
 * PostgreSQL's own spelling and a {@code FETCH FIRST} that needs no ordering, and a UTF-8
 * database compares and orders strings by their bytes. What it does differently is
 * smaller and sharper: an unquoted name is folded to upper case, so the dialect leaves
 * plain identifiers bare; and its {@code LEFT} counts bytes and pads, so counting and
 * slicing go through {@code SUBSTRING} in {@code CODEUNITS32}.
 *
 * <p>The image is the Docker Hub {@code ibmcom/db2}; IBM's current one is published only
 * to its own registry. The licence has to be accepted explicitly, the container runs
 * privileged, and it takes minutes to create its database — which is why this suite is in
 * the tier people run on purpose.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("A pushed-down query returns what the in-engine one returns (Db2)")
final class Db2PushdownAgreementTest {

    @Container
    private static final Db2Container DB2 =
            new Db2Container(DockerImageName.parse("ibmcom/db2:11.5.8.0"))
                    .acceptLicense();

    private static PushdownAgreement agreement;

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                DB2.getJdbcUrl(), DB2.getUsername(), DB2.getPassword())) {
            PushdownFixture.seed(c, PushdownFixture.Flavour.DB2);
        }
        agreement = new PushdownAgreement(PushdownFixture.preamble(
                DB2.getJdbcUrl(), DB2.getUsername(), DB2.getPassword()), Dialect.DB2);
    }

    @TestFactory
    @DisplayName("the whole corpus")
    Stream<DynamicNode> agreement() {
        return PushdownCorpus.tests(agreement);
    }
}

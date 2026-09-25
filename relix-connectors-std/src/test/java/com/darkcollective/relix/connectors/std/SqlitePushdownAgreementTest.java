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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.stream.Stream;

/**
 * The pushdown corpus against a real SQLite — in the <b>gate</b>, like DuckDB's.
 *
 * <p>SQLite's SQL is close to what {@link Dialect#GENERIC} renders, which is what a SQLite
 * connection resolved to before it had a constant, and the interesting cases are the ones
 * where close was not the same: no date or time types, so every temporal literal and
 * function declines; a {@code LIKE} that ignores case, respelled as {@code GLOB}; no
 * {@code LEFT} or {@code RIGHT}; rounding in binary floating point. The corpus states
 * which way each of those goes, and this is the suite that holds a SQLite to it.
 *
 * <p>A file rather than {@code :memory:}, for the reason the DuckDB suite gives: an
 * in-memory database belongs to the connection that opened it.
 */
@DisplayName("A pushed-down query returns what the in-engine one returns (SQLite)")
final class SqlitePushdownAgreementTest {

    @TempDir
    static Path directory;

    private static PushdownAgreement agreement;

    @BeforeAll
    static void seed() throws SQLException {
        String url = "jdbc:sqlite:" + directory.resolve("pushdown.sqlite");
        try (Connection c = DriverManager.getConnection(url)) {
            PushdownFixture.seed(c, PushdownFixture.Flavour.SQLITE);
        }
        agreement = new PushdownAgreement(PushdownFixture.preamble(url, null, null), Dialect.SQLITE);
    }

    @TestFactory
    @DisplayName("the whole corpus")
    Stream<DynamicNode> agreement() {
        return PushdownCorpus.tests(agreement);
    }
}

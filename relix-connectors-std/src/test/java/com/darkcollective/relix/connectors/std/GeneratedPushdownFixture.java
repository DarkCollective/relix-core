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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The table the generated search runs over, which is deliberately <em>not</em>
 * {@link PushdownFixture}'s.
 *
 * <p>That one is hostile on purpose: a tab-padded name, an {@code ß}, an astral emoji and
 * a {@code U+FF5E} beside it, because the corpus exists to break a careless spelling and
 * those are the values that do it. Several of its cases are therefore declared as accepted
 * divergences — the generic dialect orders strings by UTF-16 code unit where the engine
 * orders by code point, and the two put exactly that pair the opposite way round.
 *
 * <p>A search cannot declare a divergence case by case, so running it over data that is
 * itself a known divergence reports the engine's documented behaviour as a defect. The
 * strings here are plain ASCII, where code-point and code-unit order coincide, so a
 * disagreement is about the renderer rather than about collation.
 *
 * <p>Everything else is kept: a NULL in every nullable column, a repeated value so a
 * grouping key has a group of more than one, and two tables sharing a key so a join has
 * something to match on.
 */
final class GeneratedPushdownFixture {

    private GeneratedPushdownFixture() {
    }

    /** The connection and source declarations the generated expressions are appended to. */
    static String preamble(String url) {
        return "connection db from database { url: \"" + url + "\" };\n"
                + "source Orders from db {\n"
                + "    table: \"gen_orders\",\n"
                + "    schema: { oid: NUMBER, cid: NUMBER, region: STRING,\n"
                + "              amount: NUMBER, qty: NUMBER, placed: TIMESTAMP }\n"
                + "};\n"
                + "source Customers from db {\n"
                + "    table: \"gen_customers\",\n"
                + "    schema: { cid: NUMBER, name: STRING, tier: STRING, joined: DATE }\n"
                + "};\n";
    }

    /** Creates and fills both tables. */
    static void seed(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("DROP TABLE IF EXISTS gen_orders");
            st.execute("DROP TABLE IF EXISTS gen_customers");
            st.execute("CREATE TABLE gen_orders (oid INT, cid INT, region VARCHAR(10),"
                    + " amount INT, qty INT, placed TIMESTAMP)");
            st.execute("CREATE TABLE gen_customers (cid INT, name VARCHAR(20),"
                    + " tier VARCHAR(10), joined DATE)");
            st.execute("INSERT INTO gen_orders VALUES "
                    + "(1, 10, 'west', 100,  2,    '2024-01-15 08:30:00'), "
                    + "(2, 10, 'west', 250,  1,    '2024-02-15 23:45:10'), "
                    + "(3, 20, 'east', 100,  4,    '2024-02-29 00:00:00'), "
                    + "(4, 20, 'east', NULL, 3,    '2023-12-31 23:59:59'), "
                    + "(5, NULL, NULL, 50,   NULL, NULL)");
            st.execute("INSERT INTO gen_customers VALUES "
                    + "(10, 'ada',   'gold',   DATE '2020-03-01'), "
                    + "(20, 'grace', NULL,     DATE '2021-07-14'), "
                    + "(30, 'alan',  'silver', NULL), "
                    + "(40, 'bob',   'gold',   DATE '2022-01-01')");
        }
    }
}

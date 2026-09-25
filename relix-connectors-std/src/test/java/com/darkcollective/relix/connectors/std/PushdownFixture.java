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
 * The tables every pushdown-agreement suite runs against, and the relix declarations
 * that name them.
 *
 * <p>It exists so that H2, MySQL and Postgres are handed <em>the same</em> rows: an
 * agreement suite compares a folded run against an in-engine one, and a corpus shared
 * between backends is only a comparison of backends if the data underneath it is
 * identical. Seeding each suite separately is how they quietly drift until a
 * disagreement is a difference in the fixture rather than in the engine.
 *
 * <h2>Why these rows</h2>
 * Every nullable column holds a NULL somewhere and every predicate in the corpus has
 * a boundary: two regions plus a NULL one, a repeated {@code amount} so a sort key on
 * it alone ties, a customer with no orders and an order with no customer so an inner
 * join drops rows from both sides.
 *
 * <h2>Why there is a second pair of tables</h2>
 * {@code orders} and {@code customers} have no key an AS-OF join is <em>about</em>.
 * That join matches each probe to the nearest row of a history <em>in time</em>, so
 * asking anything of it needs two relations sharing a partition key and a temporal
 * one — which {@code probes} and {@code history} are. They are seeded for every suite
 * whether or not it folds an AS-OF, because a fixture is a shared claim only while the
 * rows are identical; the moment one suite seeds something another does not, a
 * disagreement between them stops being evidence about the engine.
 *
 * <p>Their rows are entirely boundaries, because that is where a nearest-row lookup is
 * wrong: a probe before every history row (no match at all), one landing exactly on a
 * history row (which tells {@code >=} from {@code >}, and so a rendered {@code <=} from
 * a rendered {@code <}), one between two, one after the last, one in a partition whose
 * nearest row in <em>time</em> belongs to another partition, one whose partition has no
 * history at all, and a NULL in each of the two columns the match is decided by. No two
 * history rows share a {@code (sym, ts)}, deliberately: a tie has more than one correct
 * answer — the engine resolves it by input order and {@code LIMIT 1} resolves it by the
 * database's — so a case over one would agree or disagree by luck.
 *
 * <h2>Why there are two temporal columns of the same value</h2>
 * {@code placed} and {@code stamped} hold the same instants, in a column the server
 * converts by session time zone and one it does not. In H2 the two types are synonyms;
 * on a named backend they are not, and the difference is the one a temporal fold has to
 * survive — a spelling that truncates or extracts runs on the <em>server</em>, where a
 * converted column presents a different wall clock than the engine read it in whenever
 * the session is not at UTC. Carrying both is what lets a suite ask whether the folded
 * and in-engine paths still agree, rather than picking the column that cannot expose the
 * question.
 *
 * <p>Which SQL types those are is the {@link Flavour}'s answer, because the pair is not
 * spelled the same way twice.
 *
 * <h2>Why one table is called {@code order-lines}</h2>
 * A name that is not a plain identifier reaches the database only delimited, and a
 * dialect that leaves names bare is exactly the one that can forget to. So one table and
 * one of its columns ({@code unit-price}) are named that way, and are created with the
 * database's own delimiter, read from its metadata rather than spelled per backend.
 */
final class PushdownFixture {

    private PushdownFixture() {
    }

    /**
     * The dialect-specific half of the DDL: which SQL types spell the unconverted and
     * converted temporal columns.
     *
     * <p>It exists because the pair does not survive being written once.
     * {@code DATETIME}/{@code TIMESTAMP} is the pair on MySQL and a pair of synonyms on
     * H2, and Postgres has no {@code DATETIME} at all — its {@code TIMESTAMP} is the
     * <em>unconverted</em> one, so seeding it with the same script would produce two
     * identical columns and a suite that passes while asking nothing.
     *
     * <p>Nothing else in the fixture varies, which is the point: the rows are the same
     * rows, so a disagreement between two suites is a difference in the engine rather
     * than in the data.
     */
    enum Flavour {

        /**
         * H2 and MySQL. On MySQL a {@code TIMESTAMP} is stored as UTC and converted into
         * the session zone on the way out, where a {@code DATETIME} is handed back as
         * written; on H2 the two are synonyms and the distinction simply costs nothing.
         */
        DEFAULT("DATETIME", "TIMESTAMP NULL"),

        /**
         * Postgres, where the pair is {@code timestamp} and {@code timestamptz}. The
         * conversion runs the other way round from MySQL's and matters more: a
         * {@code timestamptz} is an instant the server renders in the session zone, so
         * {@code date_trunc} and {@code EXTRACT} over one read a wall clock the session
         * decides, while the driver still hands the raw column back to the engine as the
         * instant it is.
         */
        POSTGRES("TIMESTAMP", "TIMESTAMPTZ"),

        /**
         * DuckDB, whose pair is PostgreSQL's and behaves as PostgreSQL's does: a
         * {@code TIMESTAMPTZ} is rendered in the session's {@code TimeZone}, so
         * {@code date_trunc} and {@code EXTRACT} over one read a wall clock the session
         * decides. Unlike pgjdbc, its driver reports the column as
         * {@code TIMESTAMP_WITH_TIMEZONE}, so the connector recognises it by type code.
         */
        DUCKDB("TIMESTAMP", "TIMESTAMPTZ");

        private final String unconverted;
        private final String converted;

        Flavour(String unconverted, String converted) {
            this.unconverted = unconverted;
            this.converted = converted;
        }
    }

    /**
     * Creates and fills the fixture's tables on an open connection, dropping whatever
     * is there first so a suite may be re-run against a live database.
     *
     * @param connection an open connection; the caller keeps ownership
     * @throws SQLException if the database rejects the script
     */
    static void seed(Connection connection) throws SQLException {
        seed(connection, Flavour.DEFAULT, "", null);
    }

    /**
     * The same, in a backend's own spelling of the temporal pair.
     *
     * @param connection an open connection; the caller keeps ownership
     * @param flavour    which SQL types spell the two temporal columns
     * @throws SQLException if the database rejects the script
     */
    static void seed(Connection connection, Flavour flavour) throws SQLException {
        seed(connection, flavour, "", null);
    }

    /**
     * The same, under a table-name suffix and an explicit column collation.
     *
     * <p>Both exist for one caller: the MySQL suite seeds a second copy of the fixture
     * whose string columns are declared binary, so it can check what a connection
     * declaring {@code collation: exact} claims. A suffix rather than a second database,
     * because the container grants its user only the one it created.
     *
     * @param connection an open connection; the caller keeps ownership
     * @param flavour    which SQL types spell the two temporal columns
     * @param suffix     appended to each table name; empty for the ordinary fixture
     * @param collation  a collation for every string column, or null for the default
     * @throws SQLException if the database rejects the script
     */
    static void seed(Connection connection, Flavour flavour, String suffix, String collation)
            throws SQLException {
        String collate = collation == null ? "" : " COLLATE " + collation;
        String orders = "orders" + suffix;
        String customers = "customers" + suffix;
        String probes = "probes" + suffix;
        String history = "history" + suffix;
        String q = connection.getMetaData().getIdentifierQuoteString();
        String lines = q + "order-lines" + suffix + q;
        try (Statement st = connection.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + lines);
            st.execute("DROP TABLE IF EXISTS " + orders);
            st.execute("DROP TABLE IF EXISTS " + customers);
            st.execute("DROP TABLE IF EXISTS " + probes);
            st.execute("DROP TABLE IF EXISTS " + history);
            st.execute("CREATE TABLE " + orders + " ("
                    + "oid INT, cid INT, region VARCHAR(10)" + collate
                    + ", code VARCHAR(20)" + collate
                    + ", amount INT, qty INT"
                    + ", placed " + flavour.unconverted
                    + ", stamped " + flavour.converted + ")");
            st.execute("CREATE TABLE " + customers + " ("
                    + "cid INT, name VARCHAR(20)" + collate
                    + ", tier VARCHAR(10)" + collate + ", joined DATE)");
            // `stamped` repeats `placed`, including its NULL. The near-midnight and
            // end-of-month values are the ones a truncation moves across a boundary
            // when a session time zone shifts the wall clock the server reads.
            st.execute("INSERT INTO " + orders + " VALUES "
                    + "(1, 10, 'west', 'AB-1', 100,  2,    '2024-01-15 08:30:00', '2024-01-15 08:30:00'), "
                    + "(2, 10, 'west', 'AB-2', 250,  1,    '2024-02-15 23:45:10', '2024-02-15 23:45:10'), "
                    + "(3, 20, 'east', 'XY-1', 100,  4,    '2024-02-29 00:00:00', '2024-02-29 00:00:00'), "
                    + "(4, 20, 'east', NULL,   NULL, 3,    '2023-12-31 23:59:59', '2023-12-31 23:59:59'), "
                    + "(5, NULL, NULL, 'AB-3', 50,   NULL, NULL,                  NULL)");
            // The last three names are what a string spelling has to survive, and each
            // is one specific hazard rather than decoration. A leading and trailing
            // TAB, because SQL TRIM removes spaces and String.strip removes every
            // Unicode whitespace character. An 'ß', because Java upper-cases it to
            // "SS" and a SQL collation may not. An astral character, because Java
            // counts it as two UTF-16 units and SQL counts it as one. Mixed case
            // throughout, because SQL comparison and search are collation-dependent
            // where the engine's are exact.
            //
            // They are written as literal characters rather than as escapes on purpose:
            // a backslash escape means a tab in MySQL and a backslash in H2, so a
            // fixture using one would seed two different tables and every disagreement
            // after it would be its fault.
            st.execute("INSERT INTO " + customers + " VALUES "
                    + "(10, 'Ada',      'gold',   DATE '2020-03-01'), "
                    + "(20, 'grace',    NULL,     DATE '2021-07-14'), "
                    + "(30, 'Alan',     'silver', NULL), "
                    + "(40, '\tAda\t',   'Gold',   DATE '2022-01-01'), "
                    + "(50, 'Straße',   'GOLD',   DATE '2022-06-30'), "
                    + "(60, 'a\uD83D\uDE00b',    '',       DATE '2023-02-02'), "
                    // U+FF5E, next to the emoji above, is the pair that tells code-point
                    // order from UTF-16 order: 0xFF5E is below the emoji by code point
                    // and ABOVE the emoji's leading surrogate 0xD83D as a code unit, so
                    // the two orders put these two rows the opposite way round. Without
                    // this row every ordering case passes under either rule.
                    + "(70, '\uFF5E',        'bronze', DATE '2023-05-05'), "
                    // Bare, so that comparing rows 70 and 80 compares these two
                    // characters and nothing before them — which is what makes an
                    // ORDER BY here tell code-point order from UTF-16 order. In row 60
                    // the leading 'a' decides and the emoji is never reached.
                    + "(80, '\uD83D\uDE00',    'bronze', DATE '2023-06-06')");

            // The AS-OF pair. `ts` takes the unconverted type, the one the server hands
            // back as written: which wall clock a converted column presents is the
            // question `stamped` above is for, and asking two questions with one column
            // would leave a failure meaning either.
            st.execute("CREATE TABLE " + probes + " ("
                    + "pid INT, sym VARCHAR(10)" + collate
                    + ", ts " + flavour.unconverted + ")");
            st.execute("CREATE TABLE " + history + " ("
                    + "hid INT, sym VARCHAR(10)" + collate
                    + ", ts " + flavour.unconverted + ", px INT)");
            // Three rows an hour apart in partition A, one in B, and none in C — so a
            // probe can fall before, on, between and after them, and a partition can be
            // empty. B's row is at 10:30 rather than on the hour on purpose: a probe of
            // B's at 11:00 is nearer in time to A's 11:00 row than to B's own, so a fold
            // that dropped the partition equality would answer this row and no other
            // differently.
            st.execute("INSERT INTO " + history + " VALUES "
                    + "(1, 'A', '2024-03-01 10:00:00', 100), "
                    + "(2, 'A', '2024-03-01 11:00:00', 110), "
                    + "(3, 'A', '2024-03-01 12:00:00', 120), "
                    + "(4, 'B', '2024-03-01 10:30:00', 200)");
            // Every probe is a boundary. Read backward (`>=`), 1 has no match, 2 matches
            // the row it sits on, 3 the row before it, 4 the last row, 5 B's row rather
            // than A's nearer one, and 6 nothing, its partition being empty. Read forward
            // (`<=`) the same rows say it the other way round: 1 matches the first row
            // and 4 is the one with no match. 7 and 8 hold the NULLs — a partition key
            // and a match value the comparison cannot decide. 9 is A's partition spelled
            // in lower case: the same partition to a case-insensitive collation and a
            // different one to the engine, which is what a string join key has to survive.
            st.execute("INSERT INTO " + probes + " VALUES "
                    + "(1, 'A',  '2024-03-01 09:00:00'), "
                    + "(2, 'A',  '2024-03-01 11:00:00'), "
                    + "(3, 'A',  '2024-03-01 11:30:00'), "
                    + "(4, 'A',  '2024-03-01 13:00:00'), "
                    + "(5, 'B',  '2024-03-01 11:00:00'), "
                    + "(6, 'C',  '2024-03-01 11:00:00'), "
                    + "(7, NULL, '2024-03-01 11:00:00'), "
                    + "(8, 'A',  NULL), "
                    + "(9, 'a',  '2024-03-01 11:00:00')");

            // Two lines on order 1, one with no price on order 3, and one whose order
            // does not exist, so a join over it drops a row from each side.
            st.execute("CREATE TABLE " + lines + " (lid INT, oid INT, "
                    + q + "unit-price" + q + " INT)");
            st.execute("INSERT INTO " + lines + " VALUES "
                    + "(1, 1, 10), (2, 1, 20), (3, 3, NULL), (4, 99, 5)");
        }
    }

    /**
     * The relix declarations the corpus is written against: one connection and the
     * tables bound through it.
     *
     * <p>Every relation is declared as {@code source … from <connection>}, which is what
     * makes each a <em>bare scan</em> the planner can name a table alias for — the
     * precondition for folding a join into a single {@code SELECT}.
     *
     * @param url      the JDBC URL
     * @param user     the connection user, or null for a database that needs none
     * @param password the connection password, or null
     * @return the script prefix every corpus expression is appended to
     */
    static String preamble(String url, String user, String password) {
        return preamble(url, user, password, null);
    }

    /**
     * The same, with an extra connection property — {@code "collation: exact"}, say.
     *
     * @param url        the JDBC URL
     * @param user       the connection user, or null for a database that needs none
     * @param password   the connection password, or null
     * @param extra      a further {@code key: value} pair for the connection block, or null
     * @return the script prefix every corpus expression is appended to
     */
    static String preamble(String url, String user, String password, String extra) {
        return preamble(url, user, password, extra, "");
    }

    /**
     * The same, naming a suffixed pair of tables — the relix relation names are
     * unchanged, so one corpus reads either copy.
     *
     * @param url      the JDBC URL
     * @param user     the connection user, or null
     * @param password the connection password, or null
     * @param extra    a further {@code key: value} pair for the connection block, or null
     * @param suffix   the table-name suffix the fixture was seeded under
     * @return the script prefix every corpus expression is appended to
     */
    static String preamble(String url, String user, String password, String extra, String suffix) {
        String credentials = user == null ? ""
                : ", user: \"" + user + "\", password: \"" + (password == null ? "" : password) + "\"";
        String extras = extra == null ? "" : ", " + extra;
        return "connection db from database { url: \"" + url + "\"" + credentials + extras + " };\n"
                + "source Orders from db {\n"
                + "    table: \"orders" + suffix + "\",\n"
                + "    schema: { oid: NUMBER, cid: NUMBER, region: STRING, code: STRING,\n"
                + "              amount: NUMBER, qty: NUMBER, placed: TIMESTAMP,\n"
                + "              stamped: TIMESTAMP }\n"
                + "};\n"
                + "source Customers from db {\n"
                + "    table: \"customers" + suffix + "\",\n"
                + "    schema: { cid: NUMBER, name: STRING, tier: STRING, joined: DATE }\n"
                + "};\n"
                + "source Probes from db {\n"
                + "    table: \"probes" + suffix + "\",\n"
                + "    schema: { pid: NUMBER, sym: STRING, ts: TIMESTAMP }\n"
                + "};\n"
                + "source History from db {\n"
                + "    table: \"history" + suffix + "\",\n"
                + "    schema: { hid: NUMBER, sym: STRING, ts: TIMESTAMP, px: NUMBER }\n"
                + "};\n"
                + "source Lines from db {\n"
                + "    table: \"order-lines" + suffix + "\",\n"
                + "    schema: { lid: NUMBER, oid: NUMBER, `unit-price`: NUMBER }\n"
                + "};\n\n";
    }
}

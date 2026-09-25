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
package com.darkcollective.relix.plan.internal;

import java.util.Map;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.ScriptBuilders;
import com.darkcollective.relix.lang.ast.source.DatabaseConnectionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Dialect — SQL surface syntax per database")
final class DialectTest {

    @Nested
    @DisplayName("identifier quoting")
    class Quoting {

        @Test
        @DisplayName("generic leaves identifiers unquoted")
        void generic() {
            assertThat(Dialect.GENERIC.quote("Col")).isEqualTo("Col");
        }

        @Test
        @DisplayName("postgres double-quotes and doubles embedded quotes")
        void postgres() {
            assertThat(Dialect.POSTGRES.quote("Col")).isEqualTo("\"Col\"");
            assertThat(Dialect.POSTGRES.quote("a\"b")).isEqualTo("\"a\"\"b\"");
        }

        @Test
        @DisplayName("mysql back-tick-quotes and doubles embedded back-ticks")
        void mysql() {
            assertThat(Dialect.MYSQL.quote("col")).isEqualTo("`col`");
            assertThat(Dialect.MYSQL.quote("a`b")).isEqualTo("`a``b`");
        }

        @Test
        @DisplayName("generic double-quotes a name that is not a plain identifier, exactly as written")
        void genericDelimitsANonIdentifier() {
            assertThat(Dialect.GENERIC.quote("_col9")).isEqualTo("_col9");
            assertThat(Dialect.GENERIC.quote("order-lines")).isEqualTo("\"order-lines\"");
            assertThat(Dialect.GENERIC.quote("Unit Price")).isEqualTo("\"Unit Price\"");
            assertThat(Dialect.GENERIC.quote("9lives")).isEqualTo("\"9lives\"");
            assertThat(Dialect.GENERIC.quote("a\"b")).isEqualTo("\"a\"\"b\"");
        }

        @Test
        @DisplayName("a table name is quoted part by part, a dot separating schema from table")
        void tableNamesAreQuotedPerPart() {
            assertThat(Dialect.GENERIC.table("orders")).isEqualTo("orders");
            assertThat(Dialect.GENERIC.table("public.orders")).isEqualTo("public.orders");
            assertThat(Dialect.GENERIC.table("sales.order-lines")).isEqualTo("sales.\"order-lines\"");
            assertThat(Dialect.POSTGRES.table("public.orders")).isEqualTo("\"public\".\"orders\"");
            assertThat(Dialect.MYSQL.table("shop.orders")).isEqualTo("`shop`.`orders`");
            assertThat(Dialect.DUCKDB.table("main.orders")).isEqualTo("\"main\".\"orders\"");
        }
    }

    @Nested
    @DisplayName("row limiting")
    class Limiting {

        @Test
        @DisplayName("LIMIT without an offset")
        void noOffset() {
            assertThat(Dialect.GENERIC.limit(10, 0)).isEqualTo("LIMIT 10");
        }

        @Test
        @DisplayName("LIMIT with an offset")
        void withOffset() {
            assertThat(Dialect.GENERIC.limit(10, 5)).isEqualTo("LIMIT 10 OFFSET 5");
        }
    }

    @Nested
    @DisplayName("boolean aggregate (boolAnd)")
    class BoolAnd {

        @Test
        @DisplayName("generic uses COUNT(*) = COUNT(CASE WHEN … THEN 1 END) — strict semantics")
        void generic() {
            assertThat(Dialect.GENERIC.boolAnd("(status = 'done')"))
                    .hasValue("COUNT(*) = COUNT(CASE WHEN (status = 'done') THEN 1 END)");
        }

        @Test
        @DisplayName("postgres uses the same strict form, not its native bool_and")
        void postgres() {
            // bool_and skips NULL inputs like any aggregate, so a group whose only
            // interesting row is UNKNOWN comes back TRUE — lenient, and a third
            // answer to a question ∀ answers strictly in-engine and on GENERIC.
            assertThat(Dialect.POSTGRES.boolAnd("(status = 'done')"))
                    .hasValue("COUNT(*) = COUNT(CASE WHEN (status = 'done') THEN 1 END)");
        }

        @Test
        @DisplayName("mysql uses it too — the strict form is SQL-92, so nothing lacks it")
        void mysql() {
            // MySQL was withheld from this while the Postgres arm still said bool_and,
            // an aggregate MySQL does lack, and stayed withheld after the arm changed
            // to the portable form. MySqlPushdownAgreementTest is what runs it.
            assertThat(Dialect.MYSQL.boolAnd("(status = 'done')"))
                    .hasValue("COUNT(*) = COUNT(CASE WHEN (status = 'done') THEN 1 END)");
        }

        @Test
        @DisplayName("no dialect declines")
        void everyDialect() {
            for (Dialect dialect : Dialect.values()) {
                assertThat(dialect.boolAnd("(p)")).as("%s", dialect).isPresent();
            }
        }
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        private static ConnectionDeclaration connection(String url, String dialect) {
            return ScriptBuilders.connection("db", new DatabaseConnectionConfig(
                    url, Optional.empty(), Optional.empty(), Optional.ofNullable(dialect)));
        }

        @Test
        @DisplayName("byName maps known names case-insensitively, else generic")
        void byName() {
            assertThat(Dialect.byName("PostgreSQL")).isEqualTo(Dialect.POSTGRES);
            assertThat(Dialect.byName("postgres")).isEqualTo(Dialect.POSTGRES);
            assertThat(Dialect.byName("MySQL")).isEqualTo(Dialect.MYSQL);
            assertThat(Dialect.byName("mariadb")).isEqualTo(Dialect.MYSQL);
            assertThat(Dialect.byName("DuckDB")).isEqualTo(Dialect.DUCKDB);
            assertThat(Dialect.byName("h2")).isEqualTo(Dialect.GENERIC);
            assertThat(Dialect.byName("wat")).isEqualTo(Dialect.GENERIC);
        }

        @Test
        @DisplayName("fromUrl infers by JDBC prefix, else generic")
        void fromUrl() {
            assertThat(Dialect.fromUrl("jdbc:postgresql://h/db")).isEqualTo(Dialect.POSTGRES);
            assertThat(Dialect.fromUrl("jdbc:mysql://h/db")).isEqualTo(Dialect.MYSQL);
            assertThat(Dialect.fromUrl("jdbc:mariadb://h/db")).isEqualTo(Dialect.MYSQL);
            assertThat(Dialect.fromUrl("jdbc:duckdb:/tmp/x.duckdb")).isEqualTo(Dialect.DUCKDB);
            assertThat(Dialect.fromUrl("jdbc:duckdb:")).isEqualTo(Dialect.DUCKDB);
            assertThat(Dialect.fromUrl("jdbc:h2:mem:x")).isEqualTo(Dialect.GENERIC);
        }

        @Test
        @DisplayName("a declared dialect overrides the URL")
        void declaredWins() {
            assertThat(Dialect.of(connection("jdbc:h2:mem:x", "postgres")))
                    .isEqualTo(Dialect.POSTGRES);
        }

        @Test
        @DisplayName("with no declared dialect, the URL is used")
        void inferredFromUrl() {
            assertThat(Dialect.of(connection("jdbc:mysql://h/db", null)))
                    .isEqualTo(Dialect.MYSQL);
        }

        @Test
        @DisplayName("a connection with no url resolves generic rather than raising")
        void urllessConnectionIsGeneric() {
            // A connection whose coordinates are a live handle has neither a url nor a
            // declared dialect. Reading it through ConnectionDeclaration.config() raised
            // IllegalStateException; getting a dialect wrong costs pushdown, raising here
            // would cost the query.
            ConnectionDeclaration injected = ScriptBuilders.connection(
                    true, "warehouse", "jdbc", Map.of());
            assertThat(Dialect.of(injected)).isEqualTo(Dialect.GENERIC);
        }

        @Test
        @DisplayName("a urlless connection still honours a declared dialect")
        void urllessConnectionHonoursDeclaredDialect() {
            ConnectionDeclaration injected = ScriptBuilders.connection(
                    true, "warehouse", "jdbc", Map.of("dialect", "postgres"));
            assertThat(Dialect.of(injected)).isEqualTo(Dialect.POSTGRES);
        }
    }

    @Nested
    @DisplayName("window function support")
    class WindowFunctions {

        @Test
        @DisplayName("GENERIC and POSTGRES support window functions")
        void genericAndPostgresSupport() {
            assertThat(Dialect.GENERIC.supportsWindowFunctions()).isTrue();
            assertThat(Dialect.POSTGRES.supportsWindowFunctions()).isTrue();
        }

        @Test
        @DisplayName("MYSQL does not support window functions (conservative — version unknown)")
        void mysqlDoesNotSupport() {
            assertThat(Dialect.MYSQL.supportsWindowFunctions()).isFalse();
        }

        @Test
        @DisplayName("windowOverClause — bounded frame: ROWS BETWEEN n-1 PRECEDING AND CURRENT ROW")
        void boundedFrame() {
            String over = Dialect.GENERIC.windowOverClause(
                    List.of("ticker"), List.of("t ASC"),
                    new WindowFrame.BoundedFrame(3));
            assertThat(over).isEqualTo(" OVER (PARTITION BY ticker ORDER BY t ASC ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)");
        }

        @Test
        @DisplayName("windowOverClause — cumulative frame: ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW")
        void cumulativeFrame() {
            String over = Dialect.GENERIC.windowOverClause(
                    List.of("region"), List.of("month ASC"),
                    new WindowFrame.CumulativeFrame());
            assertThat(over).isEqualTo(" OVER (PARTITION BY region ORDER BY month ASC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)");
        }

        @Test
        @DisplayName("windowOverClause — partition frame (ranking/offset): no ROWS clause")
        void partitionFrame() {
            String over = Dialect.GENERIC.windowOverClause(
                    List.of("dept"), List.of("salary DESC"),
                    new WindowFrame.PartitionFrame());
            assertThat(over).isEqualTo(" OVER (PARTITION BY dept ORDER BY salary DESC)");
        }

        @Test
        @DisplayName("windowOverClause — no partition keys: OVER (ORDER BY …)")
        void noPartitionKeys() {
            String over = Dialect.GENERIC.windowOverClause(
                    List.of(), List.of("t ASC"),
                    new WindowFrame.CumulativeFrame());
            assertThat(over).isEqualTo(" OVER (ORDER BY t ASC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)");
        }

        @Test
        @DisplayName("windowOverClause — GENERIC and POSTGRES produce identical ANSI SQL")
        void genericAndPostgresSameRendering() {
            List<String> keys = List.of("dept");
            List<String> order = List.of("sal DESC");
            WindowFrame frame = new WindowFrame.PartitionFrame();
            assertThat(Dialect.GENERIC.windowOverClause(keys, order, frame))
                    .isEqualTo(Dialect.POSTGRES.windowOverClause(keys, order, frame));
        }

        @Test
        @DisplayName("windowOverClause — bounded frame with n=1: ROWS BETWEEN 0 PRECEDING AND CURRENT ROW")
        void boundedFrameN1() {
            String over = Dialect.GENERIC.windowOverClause(
                    List.of(), List.of("t ASC"),
                    new WindowFrame.BoundedFrame(1));
            assertThat(over).isEqualTo(" OVER (ORDER BY t ASC ROWS BETWEEN 0 PRECEDING AND CURRENT ROW)");
        }
    }

    @Nested
    @DisplayName("LATERAL AS-OF support (ADR-0014 slice 5)")
    class LateralAsOf {

        @Test
        @DisplayName("POSTGRES and DUCKDB, which spell LATERAL alike, support a LATERAL AS-OF push")
        void postgresSupports() {
            assertThat(Dialect.POSTGRES.supportsLateralAsOf()).isTrue();
            assertThat(Dialect.DUCKDB.supportsLateralAsOf()).isTrue();
        }

        @Test
        @DisplayName("GENERIC (H2, no LATERAL) and MYSQL (version-gated) fall back to in-engine")
        void genericAndMysqlDoNot() {
            assertThat(Dialect.GENERIC.supportsLateralAsOf()).isFalse();
            assertThat(Dialect.MYSQL.supportsLateralAsOf()).isFalse();
        }
    }

    @Nested
    @DisplayName("Temporal literals (ADR-0013)")
    class TemporalLiterals {

        private final LocalDate date = LocalDate.parse("2026-06-15");
        private final LocalTime time = LocalTime.parse("13:40:00");
        private final Instant instant = Instant.parse("2026-06-15T13:40:00Z");

        @Test
        @DisplayName("GENERIC renders quoted ISO literals (implicit cast)")
        void generic() {
            assertThat(Dialect.GENERIC.dateLiteral(date)).isEqualTo("'2026-06-15'");
            assertThat(Dialect.GENERIC.timeLiteral(time)).isEqualTo("'13:40'");
            assertThat(Dialect.GENERIC.timestampLiteral(instant)).isEqualTo("'2026-06-15 13:40:00'");
        }

        @Test
        @DisplayName("POSTGRES renders typed literals; TIMESTAMP WITH TIME ZONE keeps the Z instant")
        void postgres() {
            assertThat(Dialect.POSTGRES.dateLiteral(date)).isEqualTo("DATE '2026-06-15'");
            assertThat(Dialect.POSTGRES.timeLiteral(time)).isEqualTo("TIME '13:40'");
            assertThat(Dialect.POSTGRES.timestampLiteral(instant))
                    .isEqualTo("TIMESTAMP WITH TIME ZONE '2026-06-15T13:40:00Z'");
        }

        @Test
        @DisplayName("MYSQL renders typed literals; TIMESTAMP is the UTC wall-clock")
        void mysql() {
            assertThat(Dialect.MYSQL.dateLiteral(date)).isEqualTo("DATE '2026-06-15'");
            assertThat(Dialect.MYSQL.timeLiteral(time)).isEqualTo("TIME '13:40'");
            assertThat(Dialect.MYSQL.timestampLiteral(instant)).isEqualTo("TIMESTAMP '2026-06-15 13:40:00'");
        }

        @Test
        @DisplayName("DUCKDB renders PostgreSQL's typed literals")
        void duckdb() {
            assertThat(Dialect.DUCKDB.dateLiteral(date)).isEqualTo("DATE '2026-06-15'");
            assertThat(Dialect.DUCKDB.timeLiteral(time)).isEqualTo("TIME '13:40'");
            assertThat(Dialect.DUCKDB.timestampLiteral(instant))
                    .isEqualTo("TIMESTAMP WITH TIME ZONE '2026-06-15T13:40:00Z'");
        }
    }

    @Nested
    @DisplayName("pushdown target (ADR-0026)")
    class PushdownTargets {

        @Test
        @DisplayName("every dialect is the SQL family, named by its own variant")
        void variantPerDialect() {
            assertThat(Dialect.GENERIC.pushdownTarget())
                    .isEqualTo(new PushdownTarget(PushdownTarget.SQL, ""));
            assertThat(Dialect.POSTGRES.pushdownTarget())
                    .isEqualTo(PushdownTarget.sql("postgres"));
            assertThat(Dialect.MYSQL.pushdownTarget())
                    .isEqualTo(PushdownTarget.sql("mysql"));
            assertThat(Dialect.DUCKDB.pushdownTarget())
                    .isEqualTo(PushdownTarget.sql("duckdb"));
        }

        @Test
        @DisplayName("the generic dialect has no variant, so a per-dialect spelling declines it")
        void genericHasNoVariant() {
            // A spelling that branches on the variant — DATE_TRUNC does — must not treat
            // dialect-neutral SQL as a dialect it has been confirmed against.
            PushdownTarget generic = Dialect.GENERIC.pushdownTarget();

            assertThat(generic.isFamily(PushdownTarget.SQL)).isTrue();
            assertThat(generic.variant()).isEmpty();
            assertThat(generic.isVariant("postgres")).isFalse();
            assertThat(generic.isVariant("mysql")).isFalse();
        }
    }

    /**
     * The two questions a collation answers, and the backend that answers them
     * differently.
     *
     * <p>These are pinned separately because one boolean was enough until a Postgres was
     * run: MySQL's collation is neither exact nor code-point ordered, so on that backend
     * the equality answer and the ordering answer have never disagreed.
     */
    @Nested
    @DisplayName("String comparison and string ordering are two questions")
    class StringCollation {

        @Test
        @DisplayName("MySQL alone compares inexactly; Postgres and GENERIC compare by bytes")
        void equality() {
            assertThat(Dialect.MYSQL.comparesStringsExactly()).isFalse();
            assertThat(Dialect.POSTGRES.comparesStringsExactly()).isTrue();
            assertThat(Dialect.GENERIC.comparesStringsExactly()).isTrue();
        }

        @Test
        @DisplayName("Postgres orders by locale, so only GENERIC orders as the engine does")
        void ordering() {
            assertThat(Dialect.POSTGRES.ordersStringsExactly()).isFalse();
            assertThat(Dialect.MYSQL.ordersStringsExactly()).isFalse();
            assertThat(Dialect.GENERIC.ordersStringsExactly()).isTrue();
        }

        @Test
        @DisplayName("each position has its own wrapping, and Postgres needs only one of them")
        void wrappings() {
            assertThat(Dialect.POSTGRES.exactStringComparison("\"name\"")).isEqualTo("\"name\"");
            assertThat(Dialect.POSTGRES.exactStringOrder("\"name\""))
                    .isEqualTo("(\"name\") COLLATE \"C\"");
            // MySQL's one collation settles both, so the two wrappings coincide there.
            assertThat(Dialect.MYSQL.exactStringOrder("`name`"))
                    .isEqualTo(Dialect.MYSQL.exactStringComparison("`name`"));
            assertThat(Dialect.GENERIC.exactStringOrder("name")).isEqualTo("name");
        }

        @Test
        @DisplayName("a declared collation answers both questions at once")
        void declaredCollationAnswersBoth() {
            ConnectionDeclaration exact = declaring("postgres", "exact");
            assertThat(Dialect.comparesStringsExactly(exact)).isTrue();
            assertThat(Dialect.ordersStringsExactly(exact)).isTrue();

            ConnectionDeclaration database = declaring("postgres", "database");
            assertThat(Dialect.comparesStringsExactly(database)).isFalse();
            assertThat(Dialect.ordersStringsExactly(database)).isFalse();
        }

        @Test
        @DisplayName("an unrecognised collation falls back to the dialect's own answers")
        void unrecognisedCollationFallsBack() {
            ConnectionDeclaration nonsense = declaring("postgres", "sideways");
            assertThat(Dialect.comparesStringsExactly(nonsense)).isTrue();
            assertThat(Dialect.ordersStringsExactly(nonsense)).isFalse();
        }

        private static ConnectionDeclaration declaring(String dialect, String collation) {
            return ScriptBuilders.connection("db", "jdbc",
                    Map.of("dialect", dialect, "collation", collation));
        }
    }

    /**
     * Pinning the session's time zone, which is what keeps a folded temporal computation
     * answering the question the engine would have answered.
     */
    @Nested
    @DisplayName("The session time zone a connection is pinned to")
    class SessionTimeZone {

        @Test
        @DisplayName("each named dialect has its own spelling")
        void namedDialects() {
            assertThat(Dialect.POSTGRES.pinSessionToUtcSql()).contains("SET TIME ZONE 'UTC'");
            assertThat(Dialect.MYSQL.pinSessionToUtcSql()).contains("SET time_zone = '+00:00'");
            assertThat(Dialect.DUCKDB.pinSessionToUtcSql()).contains("SET TimeZone = 'UTC'");
        }

        @Test
        @DisplayName("an unidentified backend is left alone rather than sent a guess")
        void genericIsLeftAlone() {
            assertThat(Dialect.GENERIC.pinSessionToUtcSql()).isEmpty();
        }
    }

    @Nested
    @DisplayName("a DURATION renders as an INTERVAL literal, or not at all")
    class DurationLiterals {

        private final java.time.Duration halfHour = java.time.Duration.parse("PT30M");

        @Test
        @DisplayName("postgres takes the ISO-8601 string as written")
        void postgres() {
            assertThat(Dialect.POSTGRES.durationLiteral(halfHour)).contains("INTERVAL 'PT30M'");
        }

        @Test
        @DisplayName("duckdb rejects the ISO string, so it is handed an exact count of microseconds")
        void duckdb() {
            assertThat(Dialect.DUCKDB.durationLiteral(halfHour)).contains("to_microseconds(1800000000)");
            assertThat(Dialect.DUCKDB.durationLiteral(java.time.Duration.ofSeconds(-1, 500_000)))
                    .contains("to_microseconds(-999500)");
        }

        @Test
        @DisplayName("duckdb declines a duration finer than a microsecond rather than rounding it")
        void duckdbDeclinesNanoseconds() {
            assertThat(Dialect.DUCKDB.durationLiteral(java.time.Duration.ofNanos(1_500))).isEmpty();
        }

        @Test
        @DisplayName("the others decline rather than choosing a unit for the user")
        void othersDecline() {
            assertThat(Dialect.GENERIC.durationLiteral(halfHour)).isEmpty();
            assertThat(Dialect.MYSQL.durationLiteral(halfHour)).isEmpty();
        }
    }

    /**
     * Every constant's answer to every per-backend question, written down.
     *
     * <p>The exhaustive switches in {@code Dialect} already make a new constant fail to
     * compile until it answers each question, which is the real guard and is the
     * compiler's rather than this file's. What a compiler cannot ask for is that the
     * answers be <em>reviewable together</em>: filling an arm in to make the build go
     * green is a two-character edit, and nothing about it is visible afterwards.
     *
     * <p>So the answers are also data here, and the first test fails on a constant this
     * table does not name. Adding a dialect therefore costs a row — one line per backend
     * putting all of its answers next to the answers every other backend gave, which is
     * where an implausible one is visible and is the only place it ever is.
     */
    @Nested
    @DisplayName("every dialect answers every per-backend question")
    class Answers {

        /**
         * One dialect's answers.
         *
         * @param comparesExactly   {@code comparesStringsExactly()}
         * @param ordersExactly     {@code ordersStringsExactly()}
         * @param wrapsEquality     whether {@code exactStringComparison} rewrites its argument
         * @param wrapsOrder        whether {@code exactStringOrder} rewrites its argument
         * @param windowFunctions   {@code supportsWindowFunctions()}
         * @param lateralAsOf       {@code supportsLateralAsOf()}
         * @param pinsSession       whether {@code pinSessionToUtcSql()} has a spelling
         * @param foldsDuration     whether {@code durationLiteral} has a spelling
         */
        record Answer(boolean comparesExactly, boolean ordersExactly,
                      boolean wrapsEquality, boolean wrapsOrder,
                      boolean windowFunctions, boolean lateralAsOf,
                      boolean pinsSession, boolean foldsDuration) {}

        private final Map<Dialect, Answer> expected = Map.of(
                //                      cmp=   ord=   wrapEq wrapOr win    lat    pin    dur
                Dialect.GENERIC,  new Answer(true,  true,  false, false, true,  false, false, false),
                Dialect.POSTGRES, new Answer(true,  false, false, true,  true,  true,  true,  true),
                Dialect.MYSQL,    new Answer(false, false, true,  true,  false, false, true,  false),
                Dialect.DUCKDB,   new Answer(true,  true,  false, false, true,  true,  true,  true));

        @Test
        @DisplayName("a dialect with no row here is a dialect nobody reviewed")
        void everyConstantIsAnswered() {
            assertThat(expected.keySet())
                    .as("add the new dialect's answers rather than only its switch arms")
                    .containsExactlyInAnyOrder(Dialect.values());
        }

        @Test
        @DisplayName("and each row is what the dialect actually answers")
        void answersMatch() {
            for (Dialect d : Dialect.values()) {
                Answer a = expected.get(d);
                assertThat(actual(d)).as("%s", d).isEqualTo(a);
            }
        }

        private Answer actual(Dialect d) {
            String e = "col";
            return new Answer(
                    d.comparesStringsExactly(),
                    d.ordersStringsExactly(),
                    !d.exactStringComparison(e).equals(e),
                    !d.exactStringOrder(e).equals(e),
                    d.supportsWindowFunctions(),
                    d.supportsLateralAsOf(),
                    d.pinSessionToUtcSql().isPresent(),
                    d.durationLiteral(java.time.Duration.ofMinutes(30)).isPresent());
        }
    }
}

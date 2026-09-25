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

import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.function.PushdownTarget;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * A SQL dialect — the database-specific surface syntax used when the planner
 * pushes work down as SQL.  It abstracts the two things that vary across the
 * databases relix targets:
 *
 * <ul>
 *   <li><b>Identifier quoting</b> — {@link #quote(String)} wraps a column or alias
 *       in the dialect's quote characters ({@code "x"} for PostgreSQL,
 *       {@code `x`} for MySQL). The generic dialect leaves a plain identifier bare,
 *       relying on the database folding unquoted identifiers case-insensitively, and
 *       delimits any other name with standard SQL's {@code "x"}, since no backend
 *       reads {@code order-lines} bare. {@link #table(String)} does the same for each
 *       part of a possibly schema-qualified table name.</li>
 *   <li><b>Row limiting</b> — {@link #limit(long, long)} renders the
 *       {@code LIMIT}/{@code OFFSET} clause.</li>
 *   <li><b>String literals</b> — {@link #stringLiteral(String)} writes a value as
 *       literal text.  Doubling the quote is standard SQL and not sufficient: MySQL
 *       reads a backslash as an escape, so the two backends need different rules for
 *       the same value.</li>
 * </ul>
 *
 * <p>The {@link #GENERIC} dialect emits the exact SQL the planner produced before
 * dialects existed for every name that is a plain identifier (unquoted identifiers,
 * {@code LIMIT n [OFFSET m]}), so it is a safe default for H2, PostgreSQL and MySQL.
 * The {@link #POSTGRES}, {@link #MYSQL}, {@link #DUCKDB}, {@link #SQLITE} and
 * {@link #SQLSERVER} dialects add identifier quoting, which is correct when the relix
 * schema's column names match the database's stored case (always true for
 * introspected {@code conn.table} references).
 *
 * <p><b>Every per-backend question here is an exhaustive {@code switch (this)}</b>, so a
 * constant added to this enum does not compile until it has answered each one. That is
 * the point rather than a style: an answer inherited by majority is indistinguishable
 * from an answer nobody considered, and the two differ exactly when the new backend is
 * the odd one out — which is the case a default is least able to get right. The two
 * methods that do not switch are the two whose answer is not the backend's to give:
 * {@link #quote} reads the quoting each constant declares, and {@link #boolAnd}
 * renders SQL-92 that no backend lacks.
 *
 * <p>Every constant but {@link #SQLSERVER} renders {@code LIMIT n [OFFSET m]}. SQL Server's
 * row-limiting is {@code OFFSET … FETCH}, which needs more than an arm of {@link #limit}:
 * that form is legal only after an {@code ORDER BY}, so the pushdown planner also
 * declines a limit fold over an unordered sub-tree — {@link #limitNeedsOrderBy}.
 */
public enum Dialect {

    /**
     * Unquoted identifiers and {@code LIMIT n [OFFSET m]} — the default. A name that is
     * not a plain identifier is double-quoted, as standard SQL delimits one.
     */
    GENERIC("\"", "\"", false),

    /** PostgreSQL: double-quoted identifiers. */
    POSTGRES("\"", "\"", true),

    /** MySQL / MariaDB: back-tick-quoted identifiers. */
    MYSQL("`", "`", true),

    /**
     * DuckDB: double-quoted identifiers, and SQL deliberately shaped like PostgreSQL's —
     * {@code NULLS LAST}, {@code date_trunc}, {@code LATERAL}, window functions. Where it
     * differs from PostgreSQL it differs in the engine's favour: its default collation is
     * binary, so it compares <em>and</em> orders strings exactly as the engine does.
     */
    DUCKDB("\"", "\"", true),

    /**
     * SQLite: double-quoted identifiers and a binary default collation, so it compares and
     * orders strings as the engine does. What sets it apart is what it lacks — date and
     * time types, {@code EXTRACT}, {@code LATERAL}, a case-sensitive {@code LIKE} — and
     * each of those is declined or respelled here rather than inherited from
     * {@link #GENERIC}, which is what a SQLite connection resolved to before it had a
     * constant of its own.
     */
    SQLITE("\"", "\"", true),

    /**
     * Microsoft SQL Server: bracket-quoted identifiers, and the first backend here whose
     * differences are structural rather than spellings. It has no {@code LIMIT} — its
     * {@code OFFSET … FETCH} is legal only after an {@code ORDER BY}, so a limit folds
     * only over a sort ({@link #limitNeedsOrderBy}) — no {@code NULLS LAST}, no boolean
     * values, no {@code LATERAL} (its {@code APPLY} is the same thing spelled otherwise),
     * no session time zone, and a case-insensitive default collation.
     */
    SQLSERVER("[", "]", true);

    /** A name every backend reads bare, up to case folding. */
    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String open;
    private final String close;
    private final boolean quotesEveryName;

    Dialect(String open, String close, boolean quotesEveryName) {
        this.open = open;
        this.close = close;
        this.quotesEveryName = quotesEveryName;
    }

    /**
     * Quotes an identifier for this dialect, doubling any embedded quote character.
     * The generic dialect returns a plain identifier ({@code [A-Za-z_][A-Za-z0-9_]*})
     * unchanged and double-quotes anything else.
     *
     * <p>Generic leaves a plain identifier bare because it does not know how the backend
     * folds case, and a bare name lets the backend decide. A name like
     * {@code order-lines} is different: no backend reads it bare, and a table or column
     * with that name can only have been created delimited, which also preserved its
     * case, so the delimited form written exactly as named is the one that finds it.
     *
     * <p>One of the two methods here that does not switch on the dialect, and it needs
     * no arm for the same reason a switch would give one: the quoting is given by
     * constructor arguments, so a new constant states its own answer where it is
     * declared. Doubling the closing character is the rule every quoting backend
     * follows, bracket-quoting included.
     *
     * @param identifier the column or alias name; must not be null
     * @return the identifier as this dialect writes it
     */
    public String quote(String identifier) {
        if (!quotesEveryName && PLAIN_IDENTIFIER.matcher(identifier).matches()) {
            return identifier;
        }
        return open + identifier.replace(close, close + close) + close;
    }

    /**
     * Writes a table name for this dialect, quoting each dot-separated part with
     * {@link #quote}: {@code public.orders} becomes {@code "public"."orders"} on
     * PostgreSQL. A dot therefore always separates a schema from a table.
     *
     * <p>A {@code FROM} clause, pushed or not, writes its table through this, so that the
     * engine's own scan of a table and a query pushed to the same table name the same
     * thing.
     *
     * @param table the table name as declared, possibly schema-qualified; must not be null
     * @return the table name as this dialect writes it
     */
    public String table(String table) {
        StringJoiner parts = new StringJoiner(".");
        for (String part : table.split("\\.", -1)) {
            parts.add(quote(part));
        }
        return parts.toString();
    }

    /**
     * Writes {@code value} as a SQL string literal for this dialect.
     *
     * <p>Doubling the embedded quote is standard SQL and is the whole of the rule for
     * PostgreSQL and the generic dialect, both of which read a backslash as the character
     * it is ({@code standard_conforming_strings}).
     *
     * <p><b>MySQL and MariaDB also read a backslash as an escape</b>, unless the session
     * sets {@code NO_BACKSLASH_ESCAPES}, which is not the default. Doubling only the quote
     * therefore lets a <em>value</em> end its own literal: {@code x\} followed by
     * {@code ' OR 1=1 -- } renders as <code>'x\'' OR 1=1 -- '</code>, where the value's
     * backslash escapes the quote that was doubled to contain the apostrophe, the literal
     * closes at the second quote instead of the first, and everything after it is parsed
     * as SQL. Escaping the backslash too is what closes that.
     *
     * <p>The two rules are not interchangeable in either direction: doubling a backslash
     * for PostgreSQL would put a second one into the value, and not doubling it for MySQL
     * is the hole above. That is what makes this a per-backend question rather than one
     * answer with an exception, and why it is an exhaustive {@code switch} here rather
     * than a private helper in the renderer — where it was, and where a new backend could
     * inherit an answer nobody had considered for it.
     *
     * <p>This is the escaping rule, not a substitute for one. A literal is inlined into
     * the statement text rather than bound as a parameter, so the rule has to be right for
     * the backend the text is going to.
     *
     * @param value the string the literal denotes; must not be null
     * @return the literal, quotes included; never null
     */
    public String stringLiteral(String value) {
        return switch (this) {
            case GENERIC, POSTGRES, DUCKDB, SQLITE -> "'" + value.replace("'", "''") + "'";
            case MYSQL -> "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
            // T-SQL reads a backslash as itself; N marks the literal Unicode, without which
            // a character outside the database's code page arrives as '?'.
            case SQLSERVER -> "N'" + value.replace("'", "''") + "'";
        };
    }

    /**
     * Renders the row-limit clause: {@code LIMIT count}, plus {@code OFFSET offset}
     * when {@code offset} is positive.
     *
     * <p>Every constant renders that form, which H2, PostgreSQL and MySQL all accept.
     * It is written as a switch rather than as one return because the clause is not
     * universal — see the class documentation on {@code OFFSET … FETCH} — and a
     * constant that cannot spell {@code LIMIT} should not be able to inherit it.
     *
     * <p>SQL Server has no {@code LIMIT}: it renders
     * {@code OFFSET m ROWS FETCH NEXT n ROWS ONLY}, which it accepts only after an
     * {@code ORDER BY} — see {@link #limitNeedsOrderBy}, which is what stops the planner
     * asking for one without.
     *
     * @param count  the maximum number of rows
     * @param offset the number of leading rows to skip (0 = none)
     * @return the SQL clause, without a leading space
     */
    public String limit(long count, long offset) {
        return switch (this) {
            case GENERIC, POSTGRES, MYSQL, DUCKDB, SQLITE ->
                    offset > 0 ? "LIMIT " + count + " OFFSET " + offset : "LIMIT " + count;
            case SQLSERVER -> "OFFSET " + offset + " ROWS FETCH NEXT " + count + " ROWS ONLY";
        };
    }

    /**
     * Whether this dialect's row-limiting clause is legal only after an {@code ORDER BY},
     * so that a limit over an unordered sub-tree must not fold.
     *
     * <p>{@code OFFSET … FETCH} is the standard's row limiting and requires an ordering on
     * SQL Server, as it does on Oracle. The {@code ORDER BY (SELECT NULL)} idiom would make
     * it legal, and would also make it the one fold here whose rows are chosen by the
     * database's plan rather than by anything the query said — so the limit runs in the
     * engine instead, over the rows the rest of the statement returns.
     *
     * @return {@code true} when a limit folds only onto a statement that has an {@code ORDER BY}
     */
    public boolean limitNeedsOrderBy() {
        return switch (this) {
            case GENERIC, POSTGRES, MYSQL, DUCKDB, SQLITE -> false;
            case SQLSERVER -> true;
        };
    }

    /**
     * Renders one {@code ORDER BY} key so the database places NULLs where the engine
     * does — <strong>last, in both directions</strong>.
     *
     * <p>This is not a preference either: an in-engine {@code τ} and a pushed one are
     * the same operator, so a query that sorts differently depending on whether the
     * planner folded it is wrong however the rows come out. SQL's own default is not
     * one answer to copy — H2 and MySQL put NULLs first on {@code ASC}, Postgres puts
     * them last — so the placement is always stated explicitly rather than inherited.
     *
     * <p>Postgres gets the standard {@code NULLS LAST}. Everything else gets the
     * portable form, an extra leading key on the NULL-ness itself: {@code false}
     * sorts before {@code true}, so ascending that expression puts the present values
     * first whichever way the real key runs. MySQL has no {@code NULLS LAST} syntax at
     * all, and GENERIC is an unidentified backend that may be MySQL-shaped, so neither
     * can be given it. SQLite has had {@code NULLS LAST} only since 3.30, and the
     * driver — which is the database, for an embedded backend — is the user's to choose,
     * so it gets the portable form too.
     *
     * <p>SQL Server has neither {@code NULLS LAST} nor a boolean value to sort on, so
     * the portable form is itself a syntax error there. Its leading key is the NULL test
     * spelled as a number: {@code CASE WHEN e IS NULL THEN 1 ELSE 0 END}.
     *
     * @param expr       the rendered key expression (already quoted)
     * @param descending whether the key itself sorts descending
     * @return the {@code ORDER BY} terms for this key, in order; never empty
     */
    public List<String> orderByTerms(String expr, boolean descending) {
        String direction = descending ? " DESC" : " ASC";
        return switch (this) {
            case POSTGRES, DUCKDB -> List.of(expr + direction + " NULLS LAST");
            case GENERIC, MYSQL, SQLITE -> List.of("(" + expr + " IS NULL) ASC", expr + direction);
            case SQLSERVER -> List.of("CASE WHEN " + expr + " IS NULL THEN 1 ELSE 0 END ASC",
                    expr + direction);
        };
    }

    /**
     * Renders {@code subject LIKE pattern} (or {@code NOT LIKE}) so that it matches what
     * the engine's {@code LIKE} matches, or empty where this dialect cannot say it.
     *
     * <p>The engine's {@code LIKE} is exact: {@code %} and {@code _} are its only
     * wildcards, and every other character — case included — matches only itself. That
     * is standard SQL, and most backends need nothing more than the subject rendered in
     * comparison position, which the caller has already done.
     *
     * <p><b>{@link #SQLITE}'s {@code LIKE} is case-insensitive</b> for ASCII letters, and
     * no collation changes that: {@code 'a' LIKE 'A'} is true there whatever the column
     * says. Its {@code GLOB} is the case-sensitive matcher, with {@code *} and {@code ?}
     * for wildcards, so a <em>literal</em> pattern is translated into one — its own
     * {@code *}, {@code ?} and {@code [} bracketed so that they match themselves. A
     * pattern that is not a literal cannot be translated here and declines.
     *
     * <p>{@link #SQLSERVER}'s {@code LIKE} honours the collation, which the caller has
     * already made exact, but also reads {@code [} as opening a character class, so a
     * literal pattern has each one written {@code [[]} and a computed one declines.
     *
     * @param subject the rendered expression being matched; must not be null
     * @param pattern the rendered pattern, used where the dialect reads {@code LIKE}
     *                as the engine does; must not be null
     * @param literal the pattern's text when it is a string literal, else empty
     * @param negated whether this is {@code NOT LIKE}
     * @return the predicate, parenthesised; or empty when this dialect declines it
     */
    public Optional<String> like(String subject, String pattern, Optional<String> literal,
                                 boolean negated) {
        return switch (this) {
            case GENERIC, POSTGRES, MYSQL, DUCKDB -> Optional.of(
                    "(" + subject + (negated ? " NOT LIKE " : " LIKE ") + pattern + ")");
            case SQLITE -> literal.map(text -> "(" + subject
                    + (negated ? " NOT GLOB " : " GLOB ") + stringLiteral(glob(text)) + ")");
            // T-SQL's LIKE also reads [ as the start of a character class, so a literal
            // pattern has each one bracketed, and one that is not a literal cannot be.
            case SQLSERVER -> literal.map(text -> "(" + subject
                    + (negated ? " NOT LIKE " : " LIKE ")
                    + stringLiteral(text.replace("[", "[[]")) + ")");
        };
    }

    /**
     * Writes a boolean value as a SQL literal.
     *
     * <p>{@code TRUE} and {@code FALSE} everywhere but SQL Server, which has no boolean
     * type — its {@code BIT} holds {@code 1} and {@code 0}, and {@code TRUE} is read as a
     * column name.
     *
     * @param value the value
     * @return the literal
     */
    public String booleanLiteral(boolean value) {
        return switch (this) {
            case GENERIC, POSTGRES, MYSQL, DUCKDB, SQLITE -> value ? "TRUE" : "FALSE";
            case SQLSERVER -> value ? "1" : "0";
        };
    }

    /** A {@code LIKE} pattern rewritten as the {@code GLOB} pattern matching the same strings. */
    private static String glob(String like) {
        StringBuilder glob = new StringBuilder(like.length());
        for (int i = 0; i < like.length(); i++) {
            char c = like.charAt(i);
            switch (c) {
                case '%' -> glob.append('*');
                case '_' -> glob.append('?');
                case '*', '?', '[' -> glob.append('[').append(c).append(']');
                default -> glob.append(c);
            }
        }
        return glob.toString();
    }

    /**
     * Returns the SQL boolean-aggregate expression for universal quantification
     * ({@code ∀}) over the given rendered predicate, or empty when this dialect
     * has no supported spelling and the operator must run in-engine instead.
     *
     * <p>The returned expression is placed in a {@code HAVING} clause immediately
     * after {@code GROUP BY}. Every dialect renders the same <em>strict</em> form,
     * {@code COUNT(*) = COUNT(CASE WHEN P THEN 1 END)}: a row whose predicate is
     * UNKNOWN disqualifies its group.
     *
     * <p>That is not a dialect quirk but the reading ∀ has everywhere else — it is a
     * conjunction across the group's rows, and {@code TRUE ∧ UNKNOWN} is UNKNOWN,
     * which keeps nothing. Postgres used to render {@code bool_and(P)} here, which is
     * <em>lenient</em>: like every aggregate it skips NULL inputs, so a group whose
     * only interesting row is UNKNOWN comes back TRUE and is kept — the same query
     * answering differently on Postgres than in-engine or on any other backend. The
     * shorter spelling is not worth a third answer to the same question.
     *
     * <p>No dialect declines, and this is therefore the one answering method here that
     * does not switch on the dialect: the form is the standard's rather than the
     * backend's, so there is nothing for a new constant to decide. The {@link Optional}
     * return survives because declining stays expressible if a backend ever needs to.
     *
     * <p>That the strict form is the one every backend gets is
     * also what makes it available everywhere: {@code COUNT}, {@code CASE} and a
     * comparison in {@code HAVING} are SQL-92, so there is no dialect this has to be
     * withheld from. MySQL was withheld from it while the Postgres arm still said
     * {@code bool_and} — an aggregate MySQL genuinely lacks — and stayed withheld
     * after the arm changed, because no test rendered a MySQL {@code ∀} and no MySQL
     * ran one.
     *
     * @param predicateSql the predicate rendered as a SQL expression; must not be null
     * @return the aggregate expression for {@code HAVING}; never empty
     */
    public Optional<String> boolAnd(String predicateSql) {
        return Optional.of("COUNT(*) = COUNT(CASE WHEN " + predicateSql + " THEN 1 END)");
    }

    /** The same, to {@code DATETIME2}'s hundred-nanosecond precision. */
    private static final DateTimeFormatter TIMESTAMP_UTC_FRACTION =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSS");

    /** UTC wall-clock form ({@code 2026-06-15 13:40:00}) used for zone-less timestamp literals. */
    private static final DateTimeFormatter TIMESTAMP_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Renders a {@code DATE} value as a SQL literal, or empty where this dialect has no
     * literal that means what the engine means: {@code GENERIC} a quoted ISO string
     * (relies on implicit cast), {@code POSTGRES}/{@code MYSQL}/{@code DUCKDB} a typed
     * {@code DATE '…'} literal.
     *
     * <p><b>{@link #SQLITE} declines all four temporal literals.</b> SQLite has no date or
     * time types: a "date" column holds TEXT, a REAL or an INTEGER by the application's
     * convention, and a comparison against it is a comparison of text or of numbers. A
     * quoted ISO string agrees with the engine only while every stored value is written
     * in exactly that form, which nothing the planner can reach reports — so a temporal
     * predicate runs in the engine over the values the connector read, where it means
     * what it says whatever the convention.
     *
     * @param value the date; must not be null
     * @return the literal, or empty when this dialect declines it
     */
    public Optional<String> dateLiteral(LocalDate value) {
        String iso = value.toString();
        return switch (this) {
            case GENERIC                 -> Optional.of("'" + iso + "'");
            case POSTGRES, MYSQL, DUCKDB -> Optional.of("DATE '" + iso + "'");
            // T-SQL has no typed literal syntax; a CAST of the ISO string is its spelling.
            case SQLSERVER               -> Optional.of("CAST('" + iso + "' AS DATE)");
            case SQLITE                  -> Optional.empty();
        };
    }

    /**
     * Renders a {@code TIME} value as a SQL literal, or empty where this dialect declines
     * it (see {@link #dateLiteral}).
     *
     * @param value the time; must not be null
     * @return the literal, or empty when this dialect declines it
     */
    public Optional<String> timeLiteral(LocalTime value) {
        String iso = value.toString();
        return switch (this) {
            case GENERIC                 -> Optional.of("'" + iso + "'");
            case POSTGRES, MYSQL, DUCKDB -> Optional.of("TIME '" + iso + "'");
            case SQLSERVER               -> Optional.of("CAST('" + iso + "' AS TIME)");
            case SQLITE                  -> Optional.empty();
        };
    }

    /**
     * Renders a {@code TIMESTAMP} (an absolute instant) as a SQL literal.  A relix
     * {@code TIMESTAMP} is UTC: {@code GENERIC} emits the UTC wall-clock as a quoted
     * string (matching how a zone-less SQL {@code TIMESTAMP} column is read);
     * {@code POSTGRES} emits an offset-aware {@code TIMESTAMP WITH TIME
     * ZONE '…Z'}; {@code MYSQL} emits a {@code TIMESTAMP '…'} of the UTC wall-clock.
     *
     * <p>{@code DUCKDB} takes PostgreSQL's form. Against a zone-less {@code TIMESTAMP}
     * column DuckDB converts the literal to the session's wall clock, which relix pins to
     * UTC ({@link #pinSessionToUtcSql}), so it compares the instant the engine reads the
     * column as; against a {@code TIMESTAMPTZ} column it compares instants directly. {@code SQLITE} declines (see
     * {@link #dateLiteral}).
     *
     * <p>{@code SQLSERVER} gets a {@code DATETIME2} of the UTC wall clock: a
     * {@code DATETIME2} column holds the wall clock the engine reads as UTC, and against a
     * {@code DATETIMEOFFSET} column the literal is converted at offset {@code +00:00}, so
     * it names the same instant either way — and the column, not the literal, keeps its
     * type, so an index on it still serves the comparison.
     *
     * @param value the instant; must not be null
     * @return the literal, or empty when this dialect declines it
     */
    public Optional<String> timestampLiteral(Instant value) {
        String utc = TIMESTAMP_UTC.format(LocalDateTime.ofInstant(value, ZoneOffset.UTC));
        return switch (this) {
            case GENERIC          -> Optional.of("'" + utc + "'");
            case POSTGRES, DUCKDB -> Optional.of("TIMESTAMP WITH TIME ZONE '" + value + "'");   // ISO instant with Z
            case MYSQL            -> Optional.of("TIMESTAMP '" + utc + "'");
            case SQLSERVER        -> Optional.of("CAST('" + TIMESTAMP_UTC_FRACTION.format(
                    LocalDateTime.ofInstant(value, ZoneOffset.UTC)) + "' AS DATETIME2)");
            case SQLITE           -> Optional.empty();
        };
    }

    /**
     * Renders a {@code DURATION} value as a SQL {@code INTERVAL} literal, or empty where
     * this dialect has no spelling for one.
     *
     * <p>PostgreSQL accepts an ISO-8601 interval string directly, so a duration renders
     * as it prints: {@code INTERVAL 'PT30M'}. {@link #MYSQL} and {@link #GENERIC}
     * decline, because their {@code INTERVAL} syntax names a unit
     * ({@code INTERVAL 30 MINUTE}) and a duration does not carry one — ninety minutes is
     * as truly {@code 90 MINUTE} as {@code 1.5 HOUR}, and picking for the user is how a
     * literal starts meaning something the engine did not say.
     *
     * <p>DuckDB rejects the ISO-8601 form (<code>INTERVAL 'PT30M'</code> is a conversion
     * error there) but builds an interval from an exact count of microseconds, which
     * names no calendar unit and so picks none for the user: {@code to_microseconds(n)}.
     * A duration finer than a microsecond has no such count and declines.
     *
     * <p>Alone among the four temporal literals this returns an {@link Optional},
     * because declining is a real answer for it: no fold is a slower plan, where a wrong
     * literal is a wrong result.
     *
     * @param value the duration; must not be null
     * @return the {@code INTERVAL} literal, or empty when this dialect has no spelling
     */
    public Optional<String> durationLiteral(Duration value) {
        return switch (this) {
            case POSTGRES -> Optional.of("INTERVAL '" + value + "'");
            case DUCKDB -> value.getNano() % 1_000 == 0
                    ? Optional.of("to_microseconds(" + microseconds(value) + ")")
                    : Optional.empty();
            // T-SQL has no interval type at all.
            case GENERIC, MYSQL, SQLITE, SQLSERVER -> Optional.empty();
        };
    }

    /** The whole duration as a count of microseconds; exact, since the caller checked the nanos. */
    private static long microseconds(Duration value) {
        return Math.addExact(Math.multiplyExact(value.getSeconds(), 1_000_000L),
                value.getNano() / 1_000);
    }

    /**
     * Whether this backend compares two strings the way the engine does — exactly,
     * character for character.
     *
     * <p>It is not a question about SQL but about the column's <b>collation</b>, and a
     * collation may say that two values differing in case or in accent are the same
     * value. MySQL's default is {@code utf8mb4_0900_ai_ci}, which says exactly that, so
     * a folded {@code =}, {@code IN}, {@code LIKE}, {@code GROUP BY}, {@code DISTINCT},
     * {@code ORDER BY} or {@code MIN}/{@code MAX} over a string answers a different
     * question there than the same operator answers in the engine — the query returning
     * different rows depending on where the planner put the work.
     *
     * <p>{@link #POSTGRES} and {@link #GENERIC} answer {@code true}, and for Postgres
     * that answer was checked against a server rather than reasoned about: a
     * <em>deterministic</em> collation falls back to comparing bytes when it is asked
     * whether two strings are equal, and Postgres's defaults are deterministic, so
     * {@code 'Gold' = 'gold'} is false there as it is here. {@link #DUCKDB}'s default
     * collation is binary, so it answers {@code true} for the plainer reason.
     * {@link #SQLSERVER}'s default, {@code SQL_Latin1_General_CP1_CI_AS}, is
     * case-insensitive, so it answers {@code false} as MySQL does.
     *
     * <p>This is a claim about <b>equality alone</b>, and the separation is the whole
     * reason there are two methods: the same Postgres that compares equal strings
     * exactly does not <em>order</em> them as the engine does, because ordering is what
     * a locale collation is for. See {@link #ordersStringsExactly()}, which is the
     * question {@code <}, {@code ORDER BY} and {@code MIN}/{@code MAX} ask.
     *
     * <p>The answer is a <em>default</em>, not a fact, because the truth is per column
     * and the planner has no channel to a column's collation. A connection whose string
     * columns really are binary says so with {@code collation: exact}, and a Postgres
     * deployment that wants the conservative reading says {@code collation: database} —
     * see {@link #comparesStringsExactly(ConnectionDeclaration)}.
     *
     * <p>Of every question on this enum this is the one that must never be inherited.
     * A wrong {@code true} does not cost a fold, it folds a comparison the backend
     * answers differently — so the query returns different rows depending on where the
     * planner put the work, which is a wrong result rather than a slow one.
     *
     * @return {@code true} when a string equality test may be handed to this backend
     */
    public boolean comparesStringsExactly() {
        return switch (this) {
            case GENERIC, POSTGRES, DUCKDB, SQLITE -> true;
            case MYSQL, SQLSERVER -> false;
        };
    }

    /**
     * Whether this backend puts two strings in the order the engine puts them —
     * <b>by code point</b>, which is the order a binary collation gives and the order
     * UTF-8 bytes already sort in.
     *
     * <p>It is a different question from {@link #comparesStringsExactly()}, and Postgres
     * is the backend that separates them. A deterministic collation decides
     * <em>equality</em> by bytes, so {@code =} is exact there; it decides <em>order</em>
     * by the locale, and a locale sorts the way a dictionary does — case-insensitively
     * at the primary level, punctuation weighed differently, {@code 'Straße'} filed
     * beside {@code 'Strasse'}. Under {@code en_US.UTF-8}, which is what a default
     * install initialises with, {@code 'Alan' &lt; 'a😀b' &lt; 'grace'}; by code point the
     * emoji sorts after both. So a {@code τ} on a string key, a {@code &lt;} between two
     * strings and a {@code MIN} over one all answer differently there than here.
     *
     * <p>MySQL answers {@code false} for the reason it answers {@code false} to the
     * equality question — its default collation is neither exact nor code-point ordered
     * — so on that backend the two questions have never disagreed, which is why one
     * boolean was enough until a Postgres was run.
     *
     * <p>DuckDB answers {@code true} to both questions: its default collation compares
     * UTF-8 bytes, which is code-point order. That was run rather than read — over the
     * agreement fixture's {@code U+FF5E}/emoji pair, the one that tells code-point order
     * from UTF-16 order. SQLite's {@code BINARY} collation is {@code memcmp} over UTF-8,
     * and answers the same, measured over the same pair.
     *
     * <p>A {@code false} answer does not decline the fold: it renders the ordering under
     * an explicit exact collation, {@link #exactStringOrder}.
     *
     * @return {@code true} when a string ordering may be handed to this backend as written
     */
    public boolean ordersStringsExactly() {
        return switch (this) {
            case GENERIC, DUCKDB, SQLITE -> true;
            case POSTGRES, MYSQL, SQLSERVER -> false;
        };
    }

    /**
     * Wraps a rendered string expression so this backend compares it exactly, or returns
     * it unchanged where the backend already does.
     *
     * <p>MySQL's answer is {@code CONVERT(e USING utf8mb4) COLLATE utf8mb4_0900_bin},
     * and both halves are load-bearing. The {@code COLLATE} is what replaces the
     * column's own case- and accent-insensitive collation with an exact one. The
     * {@code CONVERT} is what makes that legal for <em>any</em> column: a collation
     * belongs to a character set, so {@code COLLATE utf8mb4_0900_bin} applied to a
     * {@code latin1} column is not a wrong answer but a rejected query, and transcoding
     * first removes the question. Both were checked against a server rather than
     * reasoned about, accented values included.
     *
     * <p>It costs the column's index for this comparison, and that is the right trade
     * by a wide margin: the alternative is not an indexed lookup but no fold at all, so
     * the comparison is between a scan inside the database and every row of the table
     * crossing the wire to be scanned here.
     *
     * <p>This is the wrapping for <em>equality</em> — {@code =}, {@code IN},
     * {@code LIKE}, {@code GROUP BY}, {@code DISTINCT}, a join condition. Ordering is
     * wrapped separately by {@link #exactStringOrder}, and separately because the two
     * are not the same set of backends: Postgres needs the second and not the first.
     *
     * <p>SQL Server's is {@code (e) COLLATE Latin1_General_100_BIN2}. A binary collation
     * makes {@code 'Gold' = 'gold'} false, and it is still not all the way to exact: SQL
     * Server compares strings <em>padded</em>, as the standard's {@code PAD SPACE}
     * says, so {@code 'a' = 'a '} is true there under every collation it has. relix
     * compares them as different values. That difference is recorded rather than worked
     * round — no collation removes it, and declining every string comparison to avoid
     * values that differ only in trailing spaces would cost every such query a full
     * read.
     *
     * @param expression an already-rendered expression of string type; must not be null
     * @return the expression, wrapped if this dialect needs it to compare exactly
     */
    public String exactStringComparison(String expression) {
        return switch (this) {
            case GENERIC, POSTGRES, DUCKDB, SQLITE -> expression;
            case MYSQL -> "CONVERT(" + expression + " USING utf8mb4) COLLATE utf8mb4_0900_bin";
            case SQLSERVER -> "(" + expression + ") " + SQLSERVER_EXACT;
        };
    }

    /**
     * Wraps a rendered string expression so this backend <em>orders</em> it by code
     * point, or returns it unchanged where the backend already does.
     *
     * <p>Postgres spells that {@code (e) COLLATE "C"} — the C collation compares the
     * UTF-8 bytes, which for UTF-8 is code-point order — and it is applied in ordering
     * position only: a {@code &lt;}, an {@code ORDER BY} key, a {@code MIN} or
     * {@code MAX}. Its equality is already exact, so wrapping an {@code =} there would
     * cost the column's index and buy nothing.
     *
     * <p>MySQL reuses its comparison wrapping, which is already binary and therefore
     * already code-point ordered. That the two coincide on one backend and not on the
     * other is exactly why they are two methods.
     *
     * <p>SQL Server reuses its comparison wrapping too, and it orders by UTF-16 code
     * unit rather than by code point — the same difference H2 has as the generic
     * dialect, confined to a character outside the basic multilingual plane compared
     * with one in {@code U+E000..U+FFFF}. A code-point order exists there only through a
     * {@code VARCHAR} under a {@code _UTF8} collation, which would change what a
     * {@code MIN} returns and what a {@code <} against a literal compares.
     *
     * <p>Like the comparison wrapping, this costs the column's index for the term it
     * wraps, and the alternative is not an indexed sort but no fold at all — every row
     * crossing the wire to be sorted here.
     *
     * @param expression an already-rendered expression of string type; must not be null
     * @return the expression, wrapped if this dialect needs it to order by code point
     */
    public String exactStringOrder(String expression) {
        return switch (this) {
            case GENERIC, DUCKDB, SQLITE -> expression;
            case POSTGRES                -> "(" + expression + ") COLLATE \"C\"";
            case MYSQL, SQLSERVER        -> exactStringComparison(expression);
        };
    }

    /** SQL Server's binary collation, which compares by code unit and is case- and accent-sensitive. */
    private static final String SQLSERVER_EXACT = "COLLATE Latin1_General_100_BIN2";

    /** The declared value of {@code collation} that overrides the dialect's default. */
    private static final String COLLATION_EXACT = "exact";

    /** The declared value of {@code collation} asking for the conservative reading. */
    private static final String COLLATION_DATABASE = "database";

    /**
     * Whether {@code connection}'s backend compares strings as the engine does: its
     * declared {@code collation} when it has one, else its dialect's default.
     *
     * <p>{@code collation: exact} is how a MySQL whose string columns are declared with
     * a binary collation gets its string predicates folded again. It is the user's claim
     * rather than the engine's, because a collation is a property of each column and
     * nothing the planner can reach reports it — which is also why the safe answer is
     * the default and the fast one is opted into.
     *
     * @param connection the connection declaration; must not be null
     * @return whether a string comparison may be folded into this connection's SQL
     */
    public static boolean comparesStringsExactly(ConnectionDeclaration connection) {
        return declaredCollation(connection)
                .orElseGet(() -> of(connection).comparesStringsExactly());
    }

    /**
     * Whether {@code connection}'s backend orders strings as the engine does: its
     * declared {@code collation} when it has one, else its dialect's default.
     *
     * <p>The declaration answers both questions at once, and that is right rather than
     * a shortcut: {@code collation: exact} is the claim that these columns are declared
     * with a binary collation, and a binary collation is both exact and code-point
     * ordered.
     *
     * @param connection the connection declaration; must not be null
     * @return whether a string ordering may be folded into this connection's SQL as written
     */
    public static boolean ordersStringsExactly(ConnectionDeclaration connection) {
        return declaredCollation(connection)
                .orElseGet(() -> of(connection).ordersStringsExactly());
    }

    /** The {@code collation} property read as a claim, or empty when it says nothing. */
    private static Optional<Boolean> declaredCollation(ConnectionDeclaration connection) {
        String declared = connection.properties().get("collation");
        if (declared == null) {
            return Optional.empty();
        }
        return switch (declared.toLowerCase(Locale.ROOT)) {
            case COLLATION_EXACT -> Optional.of(true);
            case COLLATION_DATABASE -> Optional.of(false);
            // An unrecognised value falls back to the dialect's default rather than
            // raising: getting this wrong costs pushdown, and raising would cost the
            // query — the same trade Dialect.of makes for an unknown dialect name.
            default -> Optional.empty();
        };
    }

    /**
     * The statement that pins a session's time zone to UTC, or empty for a backend this
     * has not been confirmed against.
     *
     * <p><b>Why a session needs pinning at all.</b> A relix {@code TIMESTAMP} is an
     * instant, and a zone-less SQL {@code TIMESTAMP} is read as UTC. A pushed
     * {@code date_trunc} or {@code EXTRACT} runs on the <em>server</em>,
     * over whatever wall clock the session presents — so unless that clock is UTC, the
     * folded query and the in-engine one truncate different numbers and the same query
     * answers differently depending on where the planner put the work.
     *
     * <p><b>Why it is not left to the connection string.</b> On PostgreSQL it cannot be:
     * the driver sends the session's {@code TimeZone} in its startup packet, taken from
     * the <em>client JVM's</em> default zone, and a {@code TimeZone} named in the URL is
     * silently ignored. So a Postgres deployment's answers moved with the time zone of
     * the machine the engine happened to run on, and there was no property a user could
     * set to stop it. That is the shape of bug this exists to remove.
     *
     * <p>{@link #GENERIC} answers empty, and that is deliberate rather than an omission:
     * an unidentified backend is one whose spelling of this is unknown, and a rejected
     * statement would break the connection outright — a much worse failure than the wall
     * clock it would have corrected.
     *
     * @return the SQL to run once on a new connection, or empty to leave the session alone
     */
    public Optional<String> pinSessionToUtcSql() {
        return switch (this) {
            case GENERIC  -> Optional.empty();
            case POSTGRES -> Optional.of("SET TIME ZONE 'UTC'");
            case MYSQL    -> Optional.of("SET time_zone = '+00:00'");
            // Checked against a running DuckDB: the setting is `TimeZone`, and it is what
            // a TIMESTAMPTZ is rendered in and what date_trunc over one truncates.
            case DUCKDB   -> Optional.of("SET TimeZone = 'UTC'");
            // SQLite has no session time zone to pin: its date functions compute at UTC
            // unless a query asks for 'localtime', and relix folds none of them.
            case SQLITE   -> Optional.empty();
            // Nor has SQL Server: DATETIME2 is zone-less and DATETIMEOFFSET carries its
            // own offset, and neither reads a session setting. Its extractions are spelled
            // through SWITCHOFFSET(…, '+00:00') instead, which is UTC whatever the column.
            case SQLSERVER -> Optional.empty();
        };
    }

    /**
     * Names this dialect to a function library, so a function can supply its own
     * spelling here without the planner knowing anything about it.
     *
     * <p>A {@code PushdownTarget} is deliberately weaker than a {@code Dialect}: it is a
     * family and a variant string, and it carries no quoting, no limit syntax and no
     * knowledge of query planning.  That is what lets a spelling live in a library that
     * has never heard of this module — the engine keeps every structural decision and
     * hands over arguments already rendered.
     *
     * @return the target a function's spelling is asked for
     */
    public PushdownTarget pushdownTarget() {
        return switch (this) {
            // Empty variant, not "generic": a spelling declines a dialect it has not
            // been confirmed against, and dialect-neutral SQL is the absence of a
            // dialect rather than a dialect of its own.
            case GENERIC  -> PushdownTarget.sql("");
            case POSTGRES -> PushdownTarget.sql("postgres");
            case MYSQL    -> PushdownTarget.sql("mysql");
            case DUCKDB   -> PushdownTarget.sql("duckdb");
            case SQLITE   -> PushdownTarget.sql("sqlite");
            case SQLSERVER -> PushdownTarget.sql("sqlserver");
        };
    }

    /**
     * Returns whether this dialect's target database reliably supports SQL window
     * functions ({@code OVER (PARTITION BY … ORDER BY … ROWS …)}).
     *
     * <ul>
     *   <li>{@link #GENERIC} (H2 2.x), {@link #POSTGRES} and {@link #DUCKDB}:
     *       {@code true}.</li>
     *   <li>{@link #SQLITE}: {@code true} — window functions arrived in SQLite 3.25
     *       (2018), and the corpus's window cases run against it in the gate.</li>
     *   <li>{@link #SQLSERVER}: {@code true} — {@code ROWS BETWEEN} frames arrived in
     *       SQL Server 2012, which predates every release still in support.</li>
     *   <li>{@link #MYSQL}: {@code false} — MySQL 5.x predates window-function
     *       support; since the declared dialect cannot confirm the server version,
     *       window operators fall back to in-engine execution for safety.</li>
     * </ul>
     *
     * @return {@code true} when the dialect can execute a pushed {@code OVER} clause
     */
    public boolean supportsWindowFunctions() {
        return switch (this) {
            case GENERIC, POSTGRES, DUCKDB, SQLITE, SQLSERVER -> true;
            case MYSQL -> false;
        };
    }

    /**
     * Returns whether this dialect's target database reliably supports a
     * {@code LATERAL} derived table in a join — the construct used to push an AS-OF
     * join down as a correlated "nearest row" lookup
     * ({@code LEFT JOIN LATERAL (SELECT … ORDER BY … LIMIT 1) ON TRUE}).
     *
     * <ul>
     *   <li>{@link #POSTGRES}: {@code true} — {@code LATERAL} has been supported since
     *       PostgreSQL 9.3 and is the canonical AS-OF spelling.</li>
     *   <li>{@link #DUCKDB}: {@code true} — it spells {@code LATERAL} as PostgreSQL does,
     *       and the AS-OF corpus runs against it in the gate.</li>
     *   <li>{@link #GENERIC}: {@code false} — the generic target is H2, whose 2.x
     *       releases have no {@code LATERAL} support, so AS-OF runs in-engine.</li>
     *   <li>{@link #SQLITE}: {@code false} — SQLite has no {@code LATERAL}.</li>
     *   <li>{@link #SQLSERVER}: {@code true} — it has no {@code LATERAL} either, but
     *       {@code OUTER APPLY}/{@code CROSS APPLY} is the same correlated join, and
     *       {@link #nearestRowJoin} spells it.</li>
     *   <li>{@link #MYSQL}: {@code false} — {@code LATERAL} arrived only in MySQL
     *       8.0.14, and the declared dialect cannot confirm the server version, so
     *       AS-OF falls back to in-engine execution for safety (as window functions do).</li>
     * </ul>
     *
     * <p>Answered by whether {@link #nearestRowJoin} has a spelling, which is the
     * exhaustive switch: there is one list of the backends that fold an AS-OF, not two.
     *
     * @return {@code true} when the dialect can execute a pushed nearest-row AS-OF
     */
    public boolean supportsLateralAsOf() {
        return nearestRowJoin("", "", "", "", "", false).isPresent();
    }

    /**
     * Renders the {@code FROM} clause of an AS-OF join folded as a correlated
     * nearest-row lookup — for each left row, the one right row a sub-select filters by
     * the match condition and orders by the match column — or empty where this dialect
     * has no such construct ({@link #supportsLateralAsOf}).
     *
     * <p>PostgreSQL and DuckDB spell it {@code LEFT JOIN LATERAL (… LIMIT 1) r ON TRUE},
     * or {@code JOIN LATERAL} to drop an unmatched left row. SQL Server has the same
     * construct under another name — {@code OUTER APPLY (SELECT TOP 1 …) r}, or
     * {@code CROSS APPLY} — with no {@code ON} and no {@code LIMIT}; both of its halves
     * were checked against a running server.
     *
     * @param left    the left table and its alias, as they appear in {@code FROM}
     * @param columns the sub-select's select list
     * @param source  the sub-select's {@code FROM … WHERE …}
     * @param orderBy the sub-select's ordering, nearest row first
     * @param alias   the quoted alias the sub-select's columns are read through
     * @param inner   whether a left row with no match is dropped rather than NULL-padded
     * @return the {@code FROM} clause, or empty when this dialect cannot fold the join
     */
    public Optional<String> nearestRowJoin(String left, String columns, String source,
                                           String orderBy, String alias, boolean inner) {
        return switch (this) {
            case POSTGRES, DUCKDB -> Optional.of(left
                    + (inner ? " JOIN LATERAL (" : " LEFT JOIN LATERAL (")
                    + "SELECT " + columns + " " + source + " ORDER BY " + orderBy + " LIMIT 1) "
                    + alias + " ON TRUE");
            case SQLSERVER -> Optional.of(left
                    + (inner ? " CROSS APPLY (" : " OUTER APPLY (")
                    + "SELECT TOP 1 " + columns + " " + source + " ORDER BY " + orderBy + ") "
                    + alias);
            case GENERIC, MYSQL, SQLITE -> Optional.empty();
        };
    }

    /**
     * Renders the {@code OVER (…)} clause for a SQL window function, given already-
     * rendered partition and sort expressions.
     *
     * <p>The ANSI SQL:2003 syntax produced here is identical across
     * {@link #GENERIC}, {@link #POSTGRES}, and {@link #MYSQL} (8.0+):
     * {@code OVER (PARTITION BY k1, k2 ORDER BY col ASC ROWS BETWEEN n PRECEDING AND CURRENT ROW)}.
     * Only call this method after verifying {@link #supportsWindowFunctions()}.
     *
     * @param partitionCols already-quoted partition-key column expressions; may be empty
     * @param orderByExprs  already-rendered {@code col [ASC|DESC]} expressions; must not be empty
     * @param frame         the row scope ({@link WindowFrame.BoundedFrame},
     *                      {@link WindowFrame.CumulativeFrame}, or
     *                      {@link WindowFrame.PartitionFrame}); never null
     * @return the complete {@code OVER (…)} string, starting with a space
     */
    public String windowOverClause(List<String> partitionCols, List<String> orderByExprs, WindowFrame frame) {
        StringBuilder over = new StringBuilder(" OVER (");
        if (!partitionCols.isEmpty()) {
            StringJoiner partBy = new StringJoiner(", ");
            partitionCols.forEach(partBy::add);
            over.append("PARTITION BY ").append(partBy).append(' ');
        }
        over.append("ORDER BY ");
        StringJoiner orderBy = new StringJoiner(", ");
        orderByExprs.forEach(orderBy::add);
        over.append(orderBy);
        switch (frame) {
            case WindowFrame.BoundedFrame b ->
                over.append(" ROWS BETWEEN ").append(b.n() - 1).append(" PRECEDING AND CURRENT ROW");
            case WindowFrame.CumulativeFrame f ->
                over.append(" ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW");
            case WindowFrame.PartitionFrame f -> { /* no frame clause — full partition */ }
        }
        over.append(')');
        return over.toString();
    }

    /**
     * Resolves the dialect for a connection: its declared {@code dialect}, if any,
     * otherwise inferred from the JDBC URL, otherwise {@link #GENERIC}.
     *
     * <p>Reads the two properties directly rather than through
     * {@code ConnectionDeclaration.config()}, which requires a {@code url} and
     * raises when there is none.  A connection may legitimately have no URL — one
     * whose coordinates are a live handle supplied by an embedder, or one that is
     * not JDBC at all — and the right answer for it is {@link #GENERIC}, not an
     * exception.  Getting a dialect wrong costs pushdown; raising here would cost
     * the query.
     *
     * @param connection the connection declaration; must not be null
     * @return the resolved dialect; never null
     */
    public static Dialect of(ConnectionDeclaration connection) {
        String declared = connection.properties().get("dialect");
        if (declared != null) {
            return byName(declared);
        }
        String url = connection.properties().get("url");
        return url == null ? GENERIC : fromUrl(url);
    }

    /** Maps a declared dialect name to a dialect; unknown names fall back to {@link #GENERIC}. */
    static Dialect byName(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "postgres", "postgresql" -> POSTGRES;
            case "mysql", "mariadb"       -> MYSQL;
            case "duckdb"                 -> DUCKDB;
            case "sqlite"                 -> SQLITE;
            case "sqlserver", "mssql"     -> SQLSERVER;
            default                       -> GENERIC;   // h2, ansi, unknown
        };
    }

    /** Infers a dialect from a JDBC URL prefix; unrecognised URLs fall back to {@link #GENERIC}. */
    static Dialect fromUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:postgresql:")) {
            return POSTGRES;
        }
        if (lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:")) {
            return MYSQL;
        }
        if (lower.startsWith("jdbc:duckdb:")) {
            return DUCKDB;
        }
        if (lower.startsWith("jdbc:sqlite:")) {
            return SQLITE;
        }
        if (lower.startsWith("jdbc:sqlserver:")) {
            return SQLSERVER;
        }
        return GENERIC;
    }
}

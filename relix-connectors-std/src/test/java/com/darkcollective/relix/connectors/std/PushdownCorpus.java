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
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Every query the SQL pushdown planner is expected to fold, written once and run
 * against every backend the project can reach.
 *
 * <h2>Why the corpus is data</h2>
 * There are two agreement suites — H2 in the gate, MySQL in the {@code integration}
 * tier — and the interesting question is whether they <em>disagree</em>. A corpus
 * written as method bodies in a base class would let each backend's suite drift by
 * overriding one, and the drift would look like a fixture difference rather than an
 * engine one. Written as data, the two suites are the same claim asked of two
 * databases, and the only thing that varies is what each dialect is expected to fold.
 *
 * <h2>What "folds on" means</h2>
 * A case names the dialects whose renderer is expected to translate the <em>whole</em>
 * expression. That set is asserted in both directions: a dialect not in it must
 * decline, so a fold that quietly starts working — or quietly stops — fails rather
 * than passing unnoticed. Where a dialect is excluded, the reason is stated on the
 * case, and it is always a property of the renderer rather than of the database.
 *
 * @see PushdownAgreement
 */
final class PushdownCorpus {

    /**
     * Every dialect: a fold with no dialect-specific part.
     *
     * <p>Listed rather than derived from {@code EnumSet.allOf}. A case names the dialects
     * expected to fold it and is asserted in <em>both</em> directions, so a derived set
     * would enrol a newly added dialect in every case here at once — turning a hundred
     * unexamined claims about a backend nobody has run into assertions, all in the commit
     * that added the constant, and all of them looking like the corpus rather than like
     * the new backend. Widening this is the one line that says a dialect has been asked
     * these questions, which is a thing a reviewer should have to see.
     */
    private static final Set<Dialect> ALL =
            EnumSet.of(Dialect.GENERIC, Dialect.POSTGRES, Dialect.MYSQL, Dialect.DUCKDB,
                    Dialect.SQLITE, Dialect.SQLSERVER);

    /**
     * The dialects confirmed to count and slice a string by code point, as relix does.
     *
     * <p>MySQL and Postgres are; H2 — which resolves to GENERIC — is not: it counts
     * UTF-16 code units, the same unit Java does, so its {@code CHAR_LENGTH} and
     * {@code LEFT} are different functions wearing familiar names. Postgres joined the
     * set when there was a Postgres to ask, which is the whole shape of this file:
     * membership is what the corpus measured over a fixture holding an astral
     * character, not what a manual claims.
     */
    private static final Set<Dialect> COUNTS_CODE_POINTS =
            EnumSet.of(Dialect.MYSQL, Dialect.POSTGRES, Dialect.DUCKDB, Dialect.SQLITE);

    /**
     * The dialects offered {@code DATE_TRUNC}, whose spelling is chosen per dialect.
     * GENERIC is not one of them: an unidentified backend is one no spelling has been
     * confirmed against. Nor is SQLite, which has no date type to truncate, nor SQL
     * Server, whose {@code DATETRUNC} arrived in 2022 — a version a declared dialect
     * cannot confirm.
     */
    private static final Set<Dialect> TRUNCATES_DATES =
            EnumSet.of(Dialect.POSTGRES, Dialect.MYSQL, Dialect.DUCKDB);

    /**
     * The dialects offered {@code Fix}, truncation towards zero, which has a different
     * name on each. Not GENERIC, for the reason above, and not SQLite, whose {@code TRUNC}
     * is a floating-point function behind a compile-time option.
     */
    private static final Set<Dialect> TRUNCATES_NUMBERS =
            EnumSet.of(Dialect.POSTGRES, Dialect.MYSQL, Dialect.DUCKDB, Dialect.SQLSERVER);

    /**
     * The dialects that fold a limit with nothing ordering it — every one but SQL Server,
     * whose {@code OFFSET … FETCH} is legal only after an {@code ORDER BY}
     * ({@code Dialect.limitNeedsOrderBy}).
     */
    private static final Set<Dialect> LIMITS_UNORDERED =
            EnumSet.of(Dialect.GENERIC, Dialect.POSTGRES, Dialect.MYSQL, Dialect.DUCKDB,
                    Dialect.SQLITE);

    /**
     * The dialects with date and time types, and so the ones a temporal literal or a
     * temporal function folds on.
     *
     * <p>SQLite is the one without: its "dates" are TEXT, a REAL or an INTEGER by the
     * application's convention, so {@code Dialect.dateLiteral} and its siblings decline
     * there, and so do {@code EXTRACT} and {@code DATE_TRUNC}. A temporal <em>column</em>
     * compared with another, sorted or grouped still folds — both sides are the same
     * stored text — and those cases stay in {@link #ALL}.
     */
    private static final Set<Dialect> HAS_TEMPORAL_TYPES =
            EnumSet.of(Dialect.GENERIC, Dialect.POSTGRES, Dialect.MYSQL, Dialect.DUCKDB,
                    Dialect.SQLSERVER);

    /**
     * The dialects that round in decimal, as the engine does — every one but SQLite,
     * whose {@code ROUND}, {@code FLOOR} and {@code CEILING} answer in binary floating
     * point.
     */
    private static final Set<Dialect> ROUNDS_IN_DECIMAL =
            EnumSet.of(Dialect.GENERIC, Dialect.POSTGRES, Dialect.MYSQL, Dialect.DUCKDB,
                    Dialect.SQLSERVER);

    /**
     * The dialects that fold an AS-OF join: Postgres, DuckDB, which spells
     * {@code LATERAL} the same way, and SQL Server, which spells it {@code APPLY}.
     *
     * <p>{@code Dialect.supportsLateralAsOf} is the answer, and it is a claim about
     * {@code LATERAL} rather than about the operator: H2 2.x has none, and MySQL grew one
     * in 8.0.14 that a declared dialect cannot confirm the server is new enough for.
     * DuckDB is the second witness to this renderer's SQL, and the first that runs in the
     * gate rather than behind a container.
     */
    private static final Set<Dialect> FOLDS_LATERAL_ASOF =
            EnumSet.of(Dialect.POSTGRES, Dialect.DUCKDB, Dialect.SQLSERVER);

    /**
     * The dialects that execute a pushed {@code OVER} clause — see
     * {@code Dialect.supportsWindowFunctions}. MySQL is excluded there and so here.
     */
    private static final Set<Dialect> FOLDS_WINDOWS =
            EnumSet.of(Dialect.GENERIC, Dialect.POSTGRES, Dialect.DUCKDB, Dialect.SQLITE,
                    Dialect.SQLSERVER);

    /**
     * A backend that folds a case and is accepted to answer it differently.
     *
     * <p>The fold is still asserted and only the answers go uncompared, with the case
     * reported as skipped carrying this reason. It is for a divergence that has been
     * <em>decided about</em> rather than one waiting to be fixed: the alternative to
     * accepting one is usually to stop folding a whole family of queries, and where the
     * difference reaches only text the deployment does not hold, that is a bad trade.
     *
     * <p>What it must never become is a way to make an inconvenient case go quiet. The
     * reason is the whole record, so write it for someone deciding whether it is still
     * the right call.
     *
     * @param reasons each dialect whose answer is not compared, and why that is acceptable
     */
    record Divergence(Map<Dialect, String> reasons) {

        /** One reason, shared by every dialect it names. */
        static Divergence of(Set<Dialect> dialects, String reason) {
            Map<Dialect, String> reasons = new java.util.EnumMap<>(Dialect.class);
            dialects.forEach(d -> reasons.put(d, reason));
            return new Divergence(reasons);
        }
    }

    /**
     * {@link Dialect#GENERIC} is an <em>unidentified</em> backend, so what it does with a
     * string is not knowable — only assumable. relix orders strings by code point, which
     * is what a SQL binary collation does and the order UTF-8 bytes already sort in; H2,
     * which is what GENERIC resolves to here, orders by UTF-16 code unit because it is
     * Java underneath.
     *
     * <p>The two differ only when a character outside the basic multilingual plane is
     * compared with one in {@code U+E000..U+FFFF} — nothing in an 8-bit character set,
     * nothing in ASCII, nothing in most text. Declining to push a string {@code ORDER BY}
     * to every unidentified backend to avoid it would cost every such query a full table
     * read, so the fold stays and the difference is written down: here, and in
     * {@code docs/reference/advanced/pushdown.md} where a user can find it.
     */
    /**
     * SQL Server's binary collation orders by UTF-16 code unit, as H2 does — the same
     * narrow difference, reached by a named backend rather than an unidentified one.
     */
    private static final String SQLSERVER_ORDERS_BY_CODE_UNIT =
            "SQL Server's binary collation (Latin1_General_100_BIN2) orders strings by UTF-16 "
            + "code unit where relix orders by code point. They differ only for a "
            + "supplementary character compared against U+E000..U+FFFF, and a code-point "
            + "order there needs a VARCHAR under a _UTF8 collation, which would change what "
            + "MIN returns. The fold is still asserted; only the answers are not compared.";

    private static final String GENERIC_ORDERS_BY_CODE_UNIT =
            "GENERIC is an unidentified backend and H2, which stands in for one here, orders "
            + "strings by UTF-16 code unit where relix orders by code point. They differ only "
            + "for a supplementary character compared against U+E000..U+FFFF. The fold is "
            + "still asserted; only the answers are not compared.";

    /**
     * No dialect: a case the renderer declines everywhere, kept so the corpus states
     * the boundary of the fold rather than merely staying inside it.
     *
     * <p>Its agreement half is vacuous — both runs are the same in-engine plan — and
     * the claim it carries is the other half, that the expression is <em>not</em>
     * pushed. Each such case names the rule that declines it. A fold that grows to
     * cover one of these should delete the case from here, not edit its expectation
     * in place: the reason written on it will have stopped being true.
     */
    private static final Set<Dialect> NONE = EnumSet.noneOf(Dialect.class);

    private PushdownCorpus() {
    }

    /**
     * One query, and what it takes for a connection to fold it.
     *
     * @param expression  the relix expression, without the surrounding {@code query { … }}
     * @param foldsOn           the dialects whose renderer translates all of it
     * @param needsExactStrings  whether it additionally needs the connection to compare
     *                           strings exactly — see {@link Case#needsExactStrings}
     */
    record Case(String expression, Set<Dialect> foldsOn, boolean needsExactStrings,
                Optional<Divergence> divergence) {

        /** A case every dialect folds. */
        static Case of(String expression) {
            return new Case(expression, ALL, false, Optional.empty());
        }

        /** A case only the dialects with temporal types fold. */
        static Case temporal(String expression) {
            return new Case(expression, HAS_TEMPORAL_TYPES, false, Optional.empty());
        }

        /** A case only some dialects fold. */
        static Case of(String expression, Set<Dialect> foldsOn) {
            return new Case(expression, foldsOn, false, Optional.empty());
        }

        /**
         * A case that folds only where the <em>connection</em> compares strings exactly.
         *
         * <p>Which is not the same question as which dialect it is: MySQL's default
         * collation is not exact, and a MySQL connection declaring
         * {@code collation: exact} is. Ordering a string is the family this covers —
         * nothing can make it exact under a collation that is not, and an exact one
         * needs no help.
         */
        static Case needsExactStrings(String expression) {
            return new Case(expression, ALL, true, Optional.empty());
        }

        /** A case no dialect folds — see {@link PushdownCorpus#NONE}. */
        static Case declined(String expression) {
            return new Case(expression, NONE, false, Optional.empty());
        }

        /**
         * A case that folds everywhere and is <em>accepted</em> to answer differently on
         * some backends — see {@link Divergence}.
         */
        static Case diverging(String expression, Set<Dialect> dialects, String reason) {
            return diverging(expression, ALL, dialects, reason);
        }

        /** The same, for a case only {@code foldsOn} fold. */
        static Case diverging(String expression, Set<Dialect> foldsOn, Set<Dialect> dialects,
                              String reason) {
            return new Case(expression, foldsOn, false,
                    Optional.of(Divergence.of(dialects, reason)));
        }

        /** The same, for two sets of dialects diverging for two different reasons. */
        static Case diverging(String expression, Set<Dialect> dialects, String reason,
                              Set<Dialect> others, String otherReason) {
            Map<Dialect, String> reasons = new java.util.EnumMap<>(Divergence.of(dialects, reason).reasons());
            reasons.putAll(Divergence.of(others, otherReason).reasons());
            return new Case(expression, ALL, false, Optional.of(new Divergence(reasons)));
        }

        /** Why this dialect's answer is not compared, or null when it is. */
        String divergenceOn(Dialect dialect) {
            return divergence.map(d -> d.reasons().get(dialect)).orElse(null);
        }

        /** Whether {@code agreement}'s connection is expected to fold this case. */
        boolean foldsFor(PushdownAgreement agreement) {
            return foldsOn.contains(agreement.dialect())
                    && (!needsExactStrings || agreement.exactStrings());
        }
    }


    /**
     * Turns the corpus into one dynamic test per expression, grouped as the corpus
     * groups it, so a failure names the query that failed rather than the block it
     * was in.
     *
     * @param agreement the harness to run each case through
     * @return the tests, one container per group
     */
    static Stream<DynamicNode> tests(PushdownAgreement agreement) {
        return tests(agreement, groups().keySet());
    }

    /**
     * The same, over the named groups only — for a suite that re-runs part of the
     * corpus under a different connection.
     *
     * @param agreement the harness to run each case through
     * @param groupNames the group keys to include, exactly as {@link #groups} spells them
     * @return the tests, one container per named group
     * @throws IllegalArgumentException if a name matches no group, which is what stops a
     *                                  renamed group silently reducing a suite to nothing
     */
    static Stream<DynamicNode> tests(PushdownAgreement agreement, Collection<String> groupNames) {
        Map<String, List<Case>> groups = groups();
        List<String> unknown = groupNames.stream().filter(n -> !groups.containsKey(n)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("no such corpus group: " + unknown);
        }
        return groupNames.stream().map(name -> DynamicContainer.dynamicContainer(
                name,
                groups.get(name).stream().map(c -> DynamicTest.dynamicTest(
                        c.expression(),
                        () -> agreement.assertAgrees(c.expression(), c.foldsFor(agreement),
                                c.divergenceOn(agreement.dialect()))))));
    }

    /** The corpus, grouped by what each group is about. */
    static Map<String, List<Case>> groups() {
        Map<String, List<Case>> groups = new LinkedHashMap<>();
        groups.put("σ — comparisons against columns that hold NULLs", comparisons());
        groups.put("σ — null tests", nullTests());
        groups.put("σ — connectives, including negation over a nullable column", connectives());
        groups.put("σ — membership and pattern", membershipAndPattern());
        groups.put("σ — arithmetic in the predicate", arithmetic());
        groups.put("σ — temporal columns and literals", temporal());
        groups.put("σ — scalar functions the library spells for a backend", functions());
        groups.put("DATE_TRUNC — one truncation, spelled differently per dialect", dateTrunc());
        groups.put("NOW() — evaluated once here, and pushed as the value", clockCalls());
        groups.put("string functions, over names chosen to break a careless spelling", strings());
        groups.put("math functions, over exact decimals", maths());
        groups.put("the transcendentals, which answer in floating point", transcendentals());
        groups.put("the transcendentals outside their domain", transcendentalDomains());
        groups.put("the NULL functions — COALESCE and IS NULL", nullFunctions());
        groups.put("π and δ", projectionAndDistinct());
        groups.put("γ — every aggregate, over a column that holds a NULL", aggregates());
        groups.put("γ — an extremum over a string, which orders it", stringExtremum());
        groups.put("τ and λ", sortAndLimit());
        groups.put("τ on a string key — ordering is what no collation fixes", sortOnStrings());
        groups.put("∀ — a group survives only when every row satisfies the condition", universal());
        groups.put("⨝ — two tables on one connection, folded into one SELECT", joins());
        groups.put("ASOF — the nearest row in time, as a LATERAL sub-select", asOfJoins());
        groups.put("connection.table — the same joins over tables no source declares", dottedReferences());
        groups.put("names that are not identifiers — order-lines and unit-price", delimitedNames());
        groups.put("⋈ — a natural join, equating the columns two headings share", naturalJoins());
        groups.put("stacks folded into a single statement", combinations());
        groups.put("what composes above a γ — HAVING, ORDER BY, LIMIT", aboveAggregation());
        groups.put("the same, keyed on a string — where a collation cannot help", aboveAggregationOnStrings());
        groups.put("string comparison, over values that differ only in case", stringComparison());
        groups.put("OVER — the window arm, which no database had ever run", windows());
        groups.put("string literals a value could have escaped out of", literalHazards());
        groups.put("the edges of the fold — these decline, and agree anyway", declines());
        return groups;
    }

    private static List<Case> comparisons() {
        return cases(
                "σ amount > 100 (Orders)",
                "σ amount ≥ 100 (Orders)",
                "σ amount < 100 (Orders)",
                "σ amount ≤ 100 (Orders)",
                "σ amount = 100 (Orders)",
                "σ amount ≠ 100 (Orders)",
                "σ region = 'west' (Orders)",
                "σ region ≠ 'west' (Orders)",
                // A column compared to a column rather than to a literal.
                "σ amount > qty (Orders)",
                "σ cid = oid (Orders)");
    }

    private static List<Case> nullTests() {
        return cases(
                "σ amount = NULL (Orders)",
                "σ amount ≠ NULL (Orders)",
                "σ region = NULL (Orders)",
                "σ code ≠ NULL (Orders)",
                "σ placed = NULL (Orders)");
    }

    private static List<Case> connectives() {
        return cases(
                "σ ¬(amount > 100) (Orders)",
                "σ ¬(region = 'west') (Orders)",
                "σ amount > 50 ∧ region = 'west' (Orders)",
                "σ amount > 200 ∨ region = 'east' (Orders)",
                "σ ¬(amount > 50 ∧ region = 'west') (Orders)",
                "σ ¬(amount > 50 ∨ region = 'west') (Orders)",
                "σ ¬(amount = NULL) (Orders)",
                "σ ¬¬(amount > 100) (Orders)",
                "σ (amount > 50 ∧ region = 'west') ∨ qty = 4 (Orders)");
    }

    private static List<Case> membershipAndPattern() {
        return cases(
                "σ amount ∈ {100, 250} (Orders)",
                "σ amount ∉ {100, 250} (Orders)",
                "σ region ∈ {'west', 'north'} (Orders)",
                "σ region ∉ {'west'} (Orders)",
                "σ code LIKE 'AB-%' (Orders)",
                "σ code NOT LIKE 'AB-%' (Orders)",
                "σ ¬(code LIKE 'AB-%') (Orders)",
                // A single-character wildcard, and one anchored at both ends.
                "σ code LIKE 'AB-_' (Orders)",
                "σ code LIKE '%-1' (Orders)",
                "σ code LIKE '%B%' (Orders)");
    }

    private static List<Case> arithmetic() {
        return cases(
                "σ amount * qty > 200 (Orders)",
                "σ amount - qty < 100 (Orders)",
                "σ amount + qty = 102 (Orders)",
                "σ (amount + qty) * 2 > 210 (Orders)",
                "σ -amount < -60 (Orders)");
    }

    private static List<Case> temporal() {
        return List.of(
                Case.temporal("σ placed ≥ TIMESTAMP '2024-01-01T00:00:00Z' (Orders)"),
                Case.temporal("σ placed < TIMESTAMP '2024-02-15T23:45:10Z' (Orders)"),
                Case.temporal("σ placed ≥ TIMESTAMP '2024-01-01T00:00:00Z' "
                        + "∧ placed < TIMESTAMP '2024-03-01T00:00:00Z' (Orders)"),
                // A column against itself or a NULL test needs no literal, so these fold
                // on SQLite too — both sides are the text the column holds.
                Case.of("τ placed ASC, oid ASC (σ placed ≠ NULL (Orders))"),
                Case.temporal("σ joined ≥ DATE '2021-01-01' (Customers)"),
                Case.of("σ joined = NULL (Customers)"));
    }

    private static List<Case> functions() {
        return temporalCases(
                // The extraction family is what the built-in library currently spells
                // for SQL; everything else declines and evaluates in-engine, which is
                // why an unspelled function is not a corpus case but a fold that fails.
                "σ YEAR(placed) = 2024 (Orders)",
                "σ MONTH(placed) = 2 (Orders)",
                "σ DAY(placed) = 15 (Orders)",
                "σ HOUR(placed) ≥ 8 (Orders)",
                "σ MINUTE(placed) = 45 (Orders)",
                "σ YEAR(placed) = 2024 ∧ MONTH(placed) ≠ 1 (Orders)",
                "π oid, YEAR(placed) → yr (Orders)");
    }

    /**
     * Truncation, over both temporal columns.
     *
     * <p>This is the one built-in whose spelling differs in <em>shape</em> per dialect
     * rather than in name: Postgres has {@code date_trunc}, MySQL reaches the same value
     * through a per-unit {@code DATE_FORMAT} pattern, and the generic dialect declines.
     * A dialect asked to truncate a column it hands back in a different wall clock than
     * the engine reads it in would disagree here and nowhere else, which is why every
     * unit is asked of {@code stamped} as well as of {@code placed}.
     */
    private static List<Case> dateTrunc() {
        List<Case> cases = new ArrayList<>();
        for (String column : List.of("placed", "stamped")) {
            for (String unit : List.of("year", "month", "day", "hour", "minute", "second")) {
                cases.add(Case.of("π oid, DATE_TRUNC('" + unit + "', " + column + ") → t (Orders)",
                        TRUNCATES_DATES));
            }
        }
        cases.add(Case.of("σ DATE_TRUNC('month', placed) = TIMESTAMP '2024-02-01T00:00:00Z' (Orders)",
                TRUNCATES_DATES));
        cases.add(Case.of("σ DATE_TRUNC('day', stamped) ≥ TIMESTAMP '2024-01-15T00:00:00Z' (Orders)",
                TRUNCATES_DATES));
        // A unit the spelling cannot read back out of the rendered literal declines,
        // but that claim has no agreement case behind it: the engine rejects an unknown
        // unit too, so both runs raise rather than answering. It is asserted where the
        // rendering is — PushdownSpellingsTest, in relix-function-builtin.
        return cases;
    }

    /**
     * The string library against {@code Customers.name}, whose rows are chosen to break
     * a spelling that is merely plausible: a TAB-padded value, an {@code ß}, an astral
     * character, and mixed case throughout.
     *
     * <p>Every case is a π rather than a σ, so the <em>value</em> is compared rather
     * than a row's presence — a filter agrees whenever both sides happen to keep the
     * same rows, which is a weaker claim than the two functions computing the same
     * string. The exceptions are the two searches, whose result is a number a π shows
     * directly.
     */
    private static List<Case> strings() {
        return List.of(
                // Counted and sliced by code point. MySQL counts the same way and is
                // handed these four; H2 — the generic dialect — counts UTF-16 code units
                // exactly as Java does, so it is offered none of them and evaluates them
                // here. That is measured, not assumed: the same four folded on H2 in an
                // earlier draft and came back with a broken surrogate where an emoji had
                // been cut in half.
                Case.of("π cid, Len(name) → n (Customers)", COUNTS_CODE_POINTS),
                Case.of("σ Len(name) > 3 (Customers)", COUNTS_CODE_POINTS),
                Case.of("π cid, Left(name, 2) → t (Customers)", COUNTS_CODE_POINTS),
                Case.of("π cid, Right(name, 2) → t (Customers)", COUNTS_CODE_POINTS),
                Case.of("π cid, Mid(name, 2) → t (Customers)", COUNTS_CODE_POINTS),
                Case.of("π cid, Mid(name, 2, 3) → t (Customers)", COUNTS_CODE_POINTS),
                Case.of("π cid, Left(name, 20) → t (Customers)", COUNTS_CODE_POINTS),
                Case.of("π cid, Right(name, 0) → t (Customers)", COUNTS_CODE_POINTS),
                Case.of("π cid, Replace(name, 'a', 'X') → t (Customers)"),
                Case.of("π cid, Replace(name, 'A', 'X') → t (Customers)"),
                Case.of("π cid, Replace(tier, 'old', 'ilver') → t (Customers)"),
                Case.of("σ Replace(tier, 'old', 'ilver') = 'silver' (Customers)"),
                Case.of("π cid, Left(Replace(name, 'a', 'X'), 3) → t (Customers)", COUNTS_CODE_POINTS),
                // The three that still evaluate in-engine, each one of the reasons SQL's
                // same-named function is not the same function: a case mapping that
                // belongs to the collation, a whitespace set that is not the same set,
                // and a search that matches under the collation.
                Case.declined("π cid, UCase(name) → u (Customers)"),
                Case.declined("π cid, LCase(name) → l (Customers)"),
                Case.declined("π cid, Trim(name) → t (Customers)"),
                Case.declined("π cid, InStr(name, 'a') → i (Customers)"),
                Case.declined("π cid, UCase(Trim(name)) → t (Customers)"));
    }

    /**
     * The math library over the exact decimal columns. Every case is a π for the reason
     * the string cases are: a filter that agrees is a weaker claim than a value that
     * does.
     */
    private static List<Case> maths() {
        return List.of(
                Case.of("π oid, Abs(amount - 150) → a (Orders)"),
                Case.of("π oid, Sgn(amount - 100) → a (Orders)"),
                Case.of("σ Abs(amount - 150) < 60 (Orders)"),
                Case.of("γ region, SUM(Abs(amount - 150)) → spread (Orders)"),
                // The rounding functions, over an argument that actually has a fraction to
                // round. These read `amount / qty` until division stopped folding, and the
                // quotients there are 50, 250 and 25 — every one of them whole, so Int,
                // Ceil and Round were being asked to round nothing. A decimal literal gives
                // them 0.5, 150.5 and -49.5 without reaching for an operator that declines.
                Case.of("π oid, Int(amount - 99.5) → a (Orders)", ROUNDS_IN_DECIMAL),
                Case.of("π oid, Ceil(amount - 99.5) → a (Orders)", ROUNDS_IN_DECIMAL),
                Case.of("π oid, Round(amount - 99.5) → a (Orders)", ROUNDS_IN_DECIMAL),
                Case.of("π oid, Round(amount - 99.5, 2) → a (Orders)", ROUNDS_IN_DECIMAL),
                Case.of("π oid, Abs(Int(99.5 - amount)) → a (Orders)", ROUNDS_IN_DECIMAL),
                // Truncation towards zero has no single SQL name — MySQL spells it
                // TRUNCATE(x, 0) and Postgres TRUNC(x) — so it is offered only to the
                // dialects that name a backend.
                Case.of("π oid, Fix(99.5 - amount) → a (Orders)", TRUNCATES_NUMBERS));
    }

    /**
     * The transcendentals, none of which is offered to a backend — and the cases that
     * say why, since both reasons are invisible in ordinary data.
     *
     * <p>The first is that a backend computes a <em>different number</em>. MySQL answers
     * {@code TAN(4)} with {@code …495775} where this JVM answers {@code …495777}: one
     * unit in the last place, which a transcendental is entitled to, and enough to make
     * the same query return different text depending on where it ran. H2 agrees with the
     * engine and proves nothing by it, computing as it does on the same JVM — which is
     * why these cases only bite once there is a second backend to ask.
     *
     * <p>The second is {@link #transcendentalDomains()}.
     */
    private static List<Case> transcendentals() {
        return List.of(
                Case.declined("π oid, Sin(qty) → v (Orders)"),
                Case.declined("π oid, Cos(qty) → v (Orders)"),
                Case.declined("π oid, Tan(qty) → v (Orders)"),
                Case.declined("π oid, Atn(amount) → v (Orders)"),
                Case.declined("π oid, Sin(amount * qty) → v (Orders)"),
                Case.declined("π oid, Sqr(amount) → v (Orders)"),
                Case.declined("π oid, Log(amount) → v (Orders)"),
                Case.declined("π oid, Exp(qty) → v (Orders)"),
                Case.declined("π oid, Power(qty, 2) → v (Orders)"),
                Case.declined("σ Cos(qty) > 0 (Orders)"),
                Case.declined("γ region, SUM(Atn(amount)) → v (Orders)"));
    }

    /**
     * Why the partial four keep their calls in the engine: an argument they have no
     * answer for.
     *
     * <p>relix rejects a negative square root and a non-positive logarithm. SQL returns
     * NULL, or NaN, or raises something of its own — so a folded query <em>answers</em>
     * where the unfolded one <em>raises</em>. That is not a rounding difference, and it
     * is invisible in any case that stays inside the domain, which is what makes it worth
     * a group of its own.
     *
     * <p>Kept as cases rather than as a comment because they are now checkable: the
     * harness compares failures as well as rows, so both runs raising the same message is
     * agreement, and a spelling that made the folded run answer NULL instead would break
     * them.
     */
    private static List<Case> transcendentalDomains() {
        return List.of(
                Case.declined("π oid, Sqr(amount - 150) → v (Orders)"),
                Case.declined("π oid, Log(amount - 100) → v (Orders)"));
    }

    /** The two whose SQL form is exact by construction rather than by inspection. */
    private static List<Case> nullFunctions() {
        return cases(
                "π oid, Coalesce(code, region) → c (Orders)",
                "π oid, Coalesce(code, region, 'none') → c (Orders)",
                "π oid, Nz(amount, 0) → c (Orders)",
                "π oid, IsNull(amount) → missing (Orders)",
                "σ Coalesce(code, region) = 'east' (Orders)");
    }

    /**
     * The clock functions, which cannot be handed to a backend — it would answer from its
     * own clock — and whose <em>value</em> can be.
     *
     * <p>What the agreement between the two runs shows is narrower than it looks, and
     * worth saying: each run makes its own execution context and so pins its own instant,
     * microseconds apart, so these cases cannot detect a session's instant being
     * substituted for a backend's. That claim is {@code SessionClockTest}'s, over a
     * pinned clock. What these check is the half that suite cannot: that the literal the
     * planner emits is one a real database accepts, in a type it compares the column
     * against as the engine does.
     */
    private static List<Case> clockCalls() {
        List<Case> cases = new ArrayList<>(temporalCases(
                // A predicate against the instant: every row of the fixture is in the
                // past, so both runs agree on which rows survive however far apart their
                // two instants are — the answer is stable even though the value is not.
                "σ placed < NOW() (Orders)",
                "σ placed ≥ NOW() (Orders)",
                "σ placed < NOW() ∧ amount > 50 (Orders)",
                "σ stamped < NOW() (Orders)",
                "σ joined < CURRENT_DATE() (Customers)",
                "γ region, COUNT(*) → n (σ placed < NOW() (Orders))",
                "τ oid ASC (σ placed < NOW() (Orders))"));
        // Projecting the instant is the case whose value genuinely cannot agree, and it
        // is the harness rather than the engine: each run builds its own execution
        // context and so pins its own moment. The fold is still asserted, which is what
        // says the literal is one the backend accepts in a type the connector reads back
        // as a TIMESTAMP — the half SessionClockTest, which pins a clock, does not check.
        for (String projection : List.of("π oid, NOW() → t (Orders)",
                "π cid, CURRENT_DATE() → d (Customers)")) {
            cases.add(Case.diverging(projection, HAS_TEMPORAL_TYPES, ALL, TWO_RUNS_TWO_INSTANTS));
        }
        return cases;
    }

    /** @see #clockCalls() */
    private static final String TWO_RUNS_TWO_INSTANTS =
            "each of this harness's two runs pins its own instant, so a projected clock "
            + "value cannot agree between them. That the instant is the session's rather "
            + "than the backend's is SessionClockTest's claim, over a pinned clock; the "
            + "fold is still asserted here.";

    private static List<Case> projectionAndDistinct() {
        return List.of(
                Case.of("π region, amount (Orders)"),
                Case.of("π amount → value (Orders)"),
                Case.of("π oid, amount * qty → line (Orders)"),
                Case.of("δ (π region (Orders))"),
                Case.of("δ (π region, amount (σ amount > 50 (Orders)))"));
    }

    private static List<Case> aggregates() {
        return cases(
                "γ region, COUNT(*) → n (Orders)",
                "γ region, COUNT(amount) → n (Orders)",
                "γ region, SUM(amount) → total (Orders)",

                "γ region, MIN(amount) → lo (Orders)",
                "γ region, MAX(amount) → hi (Orders)",
                "γ COUNT(*) → n (Orders)",
                "γ SUM(amount) → total (Orders)",
                "γ region, qty, COUNT(*) → n (Orders)",
                "γ region, SUM(amount * qty) → weighted (Orders)");
    }

    /**
     * A string extremum, which <em>orders</em> its input — so it renders its argument in
     * comparison position, exactly as an {@code ORDER BY} key does.
     */
    private static List<Case> stringExtremum() {
        return cases(
                "γ MIN(code) → first (Orders)",
                "γ region, MAX(code) → last (Orders)");
    }

    private static List<Case> sortAndLimit() {
        List<Case> cases = new ArrayList<>(cases(
                // amount holds a NULL, and the engine sorts NULLs last in *both*
                // directions — a placement no SQL dialect's default matches in both.
                "τ amount ASC, oid ASC (Orders)",
                "τ amount DESC, oid ASC (Orders)",
                "τ placed DESC, oid ASC (Orders)",
                "λ 3 (τ oid ASC (Orders))",
                "λ 2, 2 (τ oid ASC (Orders))",
                "λ 10 (τ oid ASC (Orders))"));
        // A limit with nothing ordering it, over more rows than it keeps would be two
        // correct answers; over fewer it is one, whatever order the rows arrive in. That
        // is what lets these ask SQL Server's side of the question — its limit needs an
        // ORDER BY to be legal SQL, so it declines — without a tie deciding the answer.
        cases.add(Case.of("λ 10 (Orders)", LIMITS_UNORDERED));
        cases.add(Case.of("λ 10 (σ amount > 50 (Orders))", LIMITS_UNORDERED));
        return cases;
    }

    /** The same, keyed on a string — collated, so the database still does the sorting. */
    private static List<Case> sortOnStrings() {
        return cases(
                "τ code ASC, oid ASC (Orders)",
                "τ region ASC, amount DESC, oid ASC (Orders)",
                "λ 3 (τ code DESC, oid ASC (Orders))");
    }

    private static List<Case> universal() {
        return cases(
                "∀ region : amount > 50 (Orders)",
                "∀ region : amount ≠ NULL (Orders)",
                "∀ cid : qty > 0 (Orders)");
    }

    private static List<Case> joins() {
        return cases(
                "Orders ⨝ (Orders.cid = Customers.cid) Customers",
                "Orders ⨝ (Orders.cid = Customers.cid ∧ Customers.tier = 'gold') Customers",
                "Orders ⨝ (Orders.cid > Customers.cid) Customers",
                "Customers ⨝ (Customers.cid = Orders.cid) Orders");
    }

    /**
     * The AS-OF join, whose fold is a whole renderer rather than a spelling: a
     * {@code LEFT JOIN LATERAL (SELECT … ORDER BY … LIMIT 1) ON TRUE} correlated on the
     * probe's alias.
     *
     * <p>It is the largest thing in {@link Dialect} that no database had executed. The
     * planner built the statement, a unit test asserted the string it produced against
     * the string it was expected to produce, and both halves were written by the same
     * hand from the same reading of the manual — the position {@code boolAnd} was in
     * when it turned out to be declining MySQL for a reason that had stopped being true.
     *
     * <p>What each case is for, over a fixture that is nothing but boundaries:
     *
     * <ul>
     *   <li>The <b>outer and inner</b> variants of one query, which differ only in
     *       whether a probe with no match survives. That is the fold turning the
     *       operator's own semantics into a join type, so it is the shape most likely to
     *       be wrong, and the fixture has three probes that match nothing — before the
     *       history, in an empty partition, and holding a NULL.</li>
     *   <li><b>{@code ≥} against {@code >}</b>, over a probe sitting exactly on a history
     *       row. The two differ on that row alone, which is where a rendered {@code <}
     *       standing in for a {@code <=} would hide and nowhere else.</li>
     *   <li>The <b>forward</b> direction, {@code ≤} and {@code <}, which is the same
     *       argument for the {@code ORDER BY … ASC} arm — the direction is decoded from
     *       the inequality, so a renderer could get one right and the other backwards.</li>
     *   <li>A match with <b>no partition key</b> at all, where the sub-select's
     *       {@code WHERE} is the ordering inequality alone.</li>
     * </ul>
     *
     * <p>{@code WITHIN} is here as a decline, which is a claim in its own right: the
     * tolerance has no portable SQL form, so it stays in the engine, and the case fails
     * if a fold ever starts covering it silently.
     */
    private static List<Case> asOfJoins() {
        String backward = "Probes.sym = History.sym ∧ Probes.ts ≥ History.ts";
        String forward = "Probes.sym = History.sym ∧ Probes.ts ≤ History.ts";
        return List.of(
                // Outer and inner over the same match: the probes that match nothing are
                // kept NULL-padded by one and dropped by the other.
                Case.of("Probes ASOF " + backward + " History", FOLDS_LATERAL_ASOF),
                Case.of("Probes ASOF INNER " + backward + " History", FOLDS_LATERAL_ASOF),
                // The exact-boundary pair. Probe 2 sits on a history row: ≥ takes it, >
                // takes the row an hour before it, and no other probe answers differently.
                Case.of("Probes ASOF Probes.sym = History.sym ∧ Probes.ts > History.ts History",
                        FOLDS_LATERAL_ASOF),
                Case.of("Probes ASOF INNER Probes.sym = History.sym ∧ Probes.ts > History.ts "
                        + "History", FOLDS_LATERAL_ASOF),
                // Forward: the same rows read the other way round, and the other ORDER BY
                // direction.
                Case.of("Probes ASOF " + forward + " History", FOLDS_LATERAL_ASOF),
                Case.of("Probes ASOF INNER " + forward + " History", FOLDS_LATERAL_ASOF),
                Case.of("Probes ASOF Probes.sym = History.sym ∧ Probes.ts < History.ts History",
                        FOLDS_LATERAL_ASOF),
                // No partition key: the sub-select filters on the inequality alone, so
                // every probe reaches the whole history.
                Case.of("Probes ASOF Probes.ts ≥ History.ts History", FOLDS_LATERAL_ASOF),
                Case.of("Probes ASOF INNER Probes.ts ≤ History.ts History", FOLDS_LATERAL_ASOF),
                // The tolerance has no portable SQL form and is kept in-engine — stated
                // here so that a fold which grew to cover it would fail rather than pass.
                Case.declined("Probes ASOF " + backward + " WITHIN DURATION 'PT90M' History"),
                Case.declined("Probes ASOF INNER " + backward
                        + " WITHIN DURATION 'PT90M' History"));
    }

    /**
     * The joins above, written over {@code db.orders} rather than a declared source.
     *
     * <p>A dotted name is not a SQL identifier, and every join fold named its sides by
     * their relation names, so none of these folded — a form the programming guide leads
     * with. The statement now names each side by an alias of its own, while a condition
     * still qualifies by the dotted name; the cases hold the two apart. The schemas come
     * from the database, so the columns are whatever names its catalog reports.
     *
     * <p>The mixed case joins a declared source to a dotted reference whose derived alias
     * differs from the source's name only in case, which is the one clash the aliasing
     * has to settle itself.
     */
    private static List<Case> dottedReferences() {
        String backward = "db.probes.sym = db.history.sym ∧ db.probes.ts ≥ db.history.ts";
        return List.of(
                Case.of("db.orders ⨝ (db.orders.cid = db.customers.cid) db.customers"),
                Case.of("db.customers ⨝ (db.customers.cid < db.orders.cid "
                        + "∧ db.orders.region = 'north') db.orders"),
                Case.of("Orders ⨝ (Orders.oid = db.orders.oid) db.orders"),
                Case.of("τ oid (π oid, name (db.orders ⨝ (db.orders.cid = db.customers.cid) "
                        + "db.customers))"),
                Case.of("db.probes ASOF " + backward + " db.history", FOLDS_LATERAL_ASOF),
                Case.of("db.probes ASOF INNER " + backward + " db.history", FOLDS_LATERAL_ASOF),
                Case.of("db.orders IJOIN INTERSECTS (db.orders.placed, db.orders.stamped, "
                        + "db.history.ts, db.history.ts) db.history"),
                Case.declined("db.orders ⨝ (1 = 1) db.orders"));
    }

    /**
     * A table and a column whose names are not plain identifiers.
     *
     * <p>Every dialect has to delimit them, including the generic one, which leaves a
     * plain identifier bare. Before it delimited these it wrote {@code FROM order-lines},
     * which no database reads, and the engine's own scan wrote the same, so neither run
     * could read the table at all. The bare scan is the case that says so first.
     *
     * <p>Each question is asked twice: of {@code Lines}, whose {@code schema:} names the
     * column between backticks, and of {@code db.`order-lines`}, whose heading comes from
     * the database.
     */
    private static List<Case> delimitedNames() {
        String lines = "db.`order-lines`";
        return cases(
                "Lines",
                "σ `unit-price` > 10 (Lines)",
                "π lid, `unit-price` (Lines)",
                "γ oid, SUM(`unit-price`) → total (Lines)",
                "Orders ⨝ (Orders.oid = Lines.oid) Lines",
                lines,
                "σ `unit-price` > 10 (" + lines + ")",
                "π lid, `unit-price` (" + lines + ")",
                "γ oid, SUM(`unit-price`) → total (" + lines + ")",
                "db.orders ⨝ (db.orders.oid = " + lines + ".oid) " + lines);
    }

    /**
     * The natural join, folded into a {@code JOIN … ON} over the shared columns.
     *
     * <p>{@code Orders ⋈ Customers} shares {@code cid}, which is NULL on one order and
     * absent for one customer, so both sides lose rows. {@code Probes ⋈ History} shares a
     * string and a timestamp, and probe 9's {@code 'a'} is where a case-insensitive
     * collation would match a row the engine does not. The σ cases ask the fold's own
     * renderer about the shared column, which is one column above the join.
     */
    private static List<Case> naturalJoins() {
        return cases(
                "Orders ⋈ Customers",
                "Customers ⋈ Orders",
                "Probes ⋈ History",
                "History ⋈ Probes",
                "σ cid = 10 (Orders ⋈ Customers)",
                "σ Orders.cid = 20 ∧ tier = 'gold' (Orders ⋈ Customers)",
                "σ sym = 'A' (Probes ⋈ History)",
                "τ oid (π oid, name (Orders ⋈ Customers))",
                "Lines ⋈ Orders",
                "db.orders ⋈ db.customers");
    }

    private static List<Case> combinations() {
        List<Case> cases = new ArrayList<>(cases(
                // The sort key is made total on purpose: `amount` alone ties rows 1 and 3,
                // and a limit over a tie has more than one correct answer — the engine's
                // sort is stable and a database's need not be, so such a case would agree
                // or disagree by luck and prove nothing either way.
                "λ 2 (τ amount DESC, oid ASC (π oid, amount (σ region ≠ NULL (Orders))))",
                "δ (π region (σ amount > 50 ∧ code LIKE 'AB-%' (Orders)))",
                "λ 3 (τ amount DESC, oid ASC (σ code LIKE 'AB-%' (Orders)))",
                "δ (π region, qty (σ ¬(amount = NULL) ∧ qty ∈ {1, 2, 3} (Orders)))"));
        // An extraction, so only where the dialect has a date type to extract from.
        cases.add(Case.temporal("τ oid ASC (σ YEAR(placed) = 2024 (π oid, placed, amount (Orders)))"));
        return cases;
    }

    /**
     * Every construct that compares strings, over a table whose values differ in case.
     *
     * <p>The engine compares strings exactly. A database compares them under the
     * column's <em>collation</em>, and a collation may say that two values differing in
     * case or accent are the same value — MySQL's default says exactly that. So every
     * one of these would answer differently folded than in-engine, and the fold renders
     * each string in <em>comparison position</em> to stop it: an explicit exact
     * collation, which is what makes them agree again.
     *
     * <p>Ordering is included, which it was not when the collation was first rendered:
     * the engine ordered strings by UTF-16 code unit then, so no collation could agree
     * with it. It orders by code point now — the order a binary collation gives — and the
     * fixture holds the pair that tells the two rules apart, which is what makes the
     * GENERIC divergence below a measured claim rather than a worry.
     */
    /**
     * Literals whose <em>value</em> contains a character the backend's own literal syntax
     * reads as punctuation.
     *
     * <p>These need no fixture row, which is why they are written this way: the question is
     * whether the string that reaches the server is the string the query named, and a case
     * whose answer is a row count over a value nothing holds still separates a correct
     * literal from a misread one.
     *
     * <p>Only a real MySQL can fail them. Standard SQL reads a backslash as the character it
     * is, so the renderer's old output — quote doubled, backslash untouched — parses
     * correctly on H2 and PostgreSQL and the gate could not see the hole. MySQL reads a
     * backslash as an escape, so the value's own backslash escaped the quote that was
     * doubled to contain it: the literal closed early and the rest was parsed as SQL.
     */
    private static List<Case> literalHazards() {
        return List.of(
                // A backslash is one character, and the server has to agree that it is.
                // Where it is read as an escape instead, the literal is "ab" and this is
                // 0 rows against the engine's 5.
                Case.of("σ Len('a\\\\b') = 3 (Orders)", COUNTS_CODE_POINTS),
                Case.of("σ code = 'a\\\\b' (Orders)"),
                // A value ending in a backslash, immediately before the quote that closes
                // its literal — the shape that let a value become SQL.
                Case.of("σ code = 'AB-1\\\\' (Orders)"),
                // The injection itself, as a value. Folded and misread, the literal ends
                // early and `OR 1=1` is a tautology: every row, against the engine's none.
                Case.of("σ code = 'x\\\\\\' OR 1=1 -- ' (Orders)"),
                // A quote alone was always handled; kept so the pair reads together.
                Case.of("σ code = 'it\\'s' (Orders)"),
                // The other characters a literal must carry rather than act on.
                Case.of("σ code = 'a;b' (Orders)"),
                Case.of("σ code = 'a\\tb' (Orders)"),
                Case.of("σ code = 'a%b' (Orders)"),
                Case.of("σ code = 'a_b' (Orders)"));
    }

    /**
     * Window functions, which the renderer has folded into {@code OVER (…)} since the
     * feature shipped and <b>no database had ever executed</b>: there was not one window
     * case in this corpus, so the SQL was asserted against the string it was expected to
     * be and nothing else.
     *
     * <p>MySQL declines them ({@code Dialect.supportsWindowFunctions}), so the folding
     * cases name the two dialects that do — asserted in both directions, which is what
     * makes a capability answer that quietly went stale fail rather than pass.
     */
    private static List<Case> windows() {
        return List.of(
                Case.of("WINDOW ROW_NUMBER() SORT amount DESC, oid ASC AS rk (Orders)",
                        FOLDS_WINDOWS),
                Case.of("WINDOW RANK() SORT amount DESC, oid ASC PER region AS rk (Orders)",
                        FOLDS_WINDOWS),
                Case.of("ROLLING SUM(amount) OVER 2 ROWS SORT oid AS run (Orders)",
                        FOLDS_WINDOWS),
                Case.of("ROLLING MAX(amount) OVER ALL ROWS SORT oid AS peak (Orders)",
                        FOLDS_WINDOWS),
                // The one thing beneath a window that does compose: a WHERE, which SQL
                // evaluates before it, exactly as the engine's σ does.
                Case.of("WINDOW ROW_NUMBER() SORT amount DESC, oid ASC AS rk "
                        + "(σ amount > 60 (Orders))", FOLDS_WINDOWS),
                // The mirror: a window over a sort declines too, because SQL evaluates
                // ORDER BY after the window — a folded τ beneath one orders the output
                // where the engine's τ orders the window's input.
                Case.declined("ROLLING MIN(amount) OVER ALL ROWS SORT oid AS lo "
                        + "(τ oid DESC (Orders))"),
                Case.declined("WINDOW ROW_NUMBER() SORT region ASC AS rk (δ (π region (Orders)))"),
                // A σ above a window declines, and the reason is not a limitation: WHERE
                // is evaluated *before* the window, so folding it would filter the
                // window's input rather than its output and silently answer a different
                // question.
                Case.declined("σ amount > 60 (WINDOW ROW_NUMBER() SORT oid ASC AS rk (Orders))"),
                // The same clock, at the other end: SQL computes a window after GROUP BY
                // and HAVING, so neither can name one.
                Case.declined("γ region, COUNT(*) → n "
                        + "(WINDOW ROW_NUMBER() SORT oid ASC AS rk (Orders))"),
                // τ and λ decline too, and each for its own reason rather than by
                // analogy. A window's alias is not a table column and a backend need not
                // resolve a select-list alias in ORDER BY — H2 refuses one. And a LIMIT
                // over a window takes *some* rows where the engine takes the first ones
                // its scan produced, which is a fold that changes which rows come back.
                Case.declined("τ rk ASC (WINDOW ROW_NUMBER() SORT amount DESC, oid ASC AS rk (Orders))"),
                Case.of("δ (ROLLING MAX(amount) OVER ALL ROWS SORT oid AS peak (Orders))",
                        FOLDS_WINDOWS),
                // Two windows in one select list is ordinary SQL, and folds — so long as
                // the second keys on real columns.
                Case.of("WINDOW RANK() SORT oid ASC AS r2 "
                        + "(WINDOW ROW_NUMBER() SORT amount DESC, oid ASC AS rk (Orders))",
                        FOLDS_WINDOWS),
                // Keying on the first one's output is the case that must not: that alias
                // is a select-list name and not a table column, so PARTITION BY and
                // ORDER BY inside an OVER cannot resolve it — the renderer emitted it
                // regardless and the database refused the statement.
                Case.declined("WINDOW RANK() SORT rk ASC AS r2 "
                        + "(WINDOW ROW_NUMBER() SORT amount DESC, oid ASC AS rk (Orders))"),
                Case.declined("WINDOW RANK() SORT oid ASC PER rk AS r2 "
                        + "(WINDOW ROW_NUMBER() SORT amount DESC, oid ASC AS rk (Orders))"),
                // A τ on a real column composes over a window; on the window's own output
                // it does not, for the same reason.
                Case.of("τ oid DESC (WINDOW ROW_NUMBER() SORT amount DESC, oid ASC AS rk (Orders))",
                        FOLDS_WINDOWS),
                // And a π is not. The OVER expression *is* a select item, so a projection
                // above one would have to replace the list it sits in — which is what it
                // used to do, dropping the window and leaving the alias behind: the
                // database was handed `SELECT oid, rk FROM orders` and refused it, while
                // the engine returned rows.
                Case.declined("π oid, rk (WINDOW ROW_NUMBER() SORT oid ASC AS rk (Orders))"),
                Case.declined("π oid, run (ROLLING SUM(amount) OVER 2 ROWS SORT oid AS run (Orders))"));
    }

    private static List<Case> stringComparison() {
        return List.of(
                // Equality, membership and pattern: collated, and folded.
                Case.of("σ name = 'ada' (Customers)"),
                Case.of("σ name ≠ 'ada' (Customers)"),
                Case.of("σ tier = 'gold' (Customers)"),
                Case.of("σ tier ∈ {'gold', 'silver'} (Customers)"),
                Case.of("σ tier ∉ {'gold'} (Customers)"),
                Case.of("σ name LIKE 'a%' (Customers)"),
                Case.of("σ ¬(tier = 'gold') (Customers)"),
                // Grouping and deduplication compare for equality too.
                Case.of("γ tier, COUNT(*) → n (Customers)"),
                Case.of("δ (π tier (Customers))"),
                Case.of("δ (π name, tier (Customers))"),
                // A join condition over a string column.
                Case.of("Customers ⨝ (Customers.tier = Orders.region) Orders"),
                // Ordering, collated like the rest: the engine orders strings by code
                // point, which is the order a binary collation gives.
                Case.of("σ name > 'B' (Customers)"),
                Case.diverging("τ name ASC, cid ASC (Customers)",
                        EnumSet.of(Dialect.GENERIC), GENERIC_ORDERS_BY_CODE_UNIT,
                        EnumSet.of(Dialect.SQLSERVER), SQLSERVER_ORDERS_BY_CODE_UNIT),
                Case.diverging("τ name DESC, cid ASC (Customers)",
                        EnumSet.of(Dialect.GENERIC), GENERIC_ORDERS_BY_CODE_UNIT,
                        EnumSet.of(Dialect.SQLSERVER), SQLSERVER_ORDERS_BY_CODE_UNIT),
                Case.diverging("γ MIN(name) → lo, MAX(name) → hi (Customers)",
                        EnumSet.of(Dialect.GENERIC), GENERIC_ORDERS_BY_CODE_UNIT,
                        EnumSet.of(Dialect.SQLSERVER), SQLSERVER_ORDERS_BY_CODE_UNIT));
    }

    /**
     * The shapes the SQL renderer declines. Every one of them is a real query a user can
     * write, so each reads the whole table and finishes the work in this process's heap
     * — which is what makes the boundary worth stating rather than leaving to be
     * discovered from {@code --explain}.
     */
    private static List<Case> declines() {
        return List.of(
                // A grouping key that is not a bare column: the GROUP BY fold requires
                // one, so a derived key aggregates in-engine.
                Case.declined("γ YEAR(placed) → yr, COUNT(*) → n (Orders)"),
                // π above τ — ORDER BY fixes the select list, so a projection above it
                // would have to become a sub-select.
                Case.declined("π oid, placed (τ placed ASC, oid ASC (Orders))"),
                // π above γ — the same, for the select list a GROUP BY fixed.
                Case.declined("π region (γ region, COUNT(*) → n (Orders))"),
                // An aggregate with no SQL spelling stops the γ folding, and everything
                // above it with the γ.
                Case.declined("σ region = 'west' (γ region, COLLECT(amount) → items (Orders))"),
                // Division, on every backend. A relix `/` is exact decimal to ten places,
                // half-up, and no backend's `/` answers that question — so the operand
                // declines and takes its whole expression with it.
                //
                // These four are what established it, and each one had to be written to
                // discriminate. `σ amount / qty > 40` folded and agreed for as long as it
                // existed because 100/2, 250/1 and 100/4 are whole numbers that land on
                // the same side of 40 either way; it is kept, now asserted as declining.
                Case.declined("σ amount / qty > 40 (Orders)"),
                // A quotient that is not whole: 40.5 in the engine, 40 on H2 and
                // PostgreSQL, which divide two integers as integers. Different rows.
                Case.declined("σ 81 / 2 > 40 (Orders)"),
                Case.declined("σ amount / 3 > 33 (Orders)"),
                // MySQL does return a decimal and still disagreed, which is why declining
                // is not per-dialect: its scale is the operand's plus
                // div_precision_increment, four by default, so this returned 33.3333
                // against the engine's 33.3333333333. Only a projection could see it — a
                // comparison hides a scale difference behind the same answer.
                Case.declined("π oid, amount / 3 → q (Orders)"),
                // AVG, which is division wearing an aggregate's clothes: the only reducer
                // that divides, and so the only one that cannot carry the engine's exact
                // decimal to ten places. H2 answers 152.33333333333334 where the engine
                // answers 152.3333333333.
                //
                // `γ region, AVG(amount)` sat in the folding group and agreed for as long
                // as it existed, because every group's average in the fixture happens to
                // divide exactly — the same luck the σ over a division passed on. The case
                // below is the one that does not, and needed no new fixture row: three
                // non-NULL values of `amount + qty` summing to 457.
                Case.declined("γ region, AVG(amount) → mean (Orders)"),
                Case.declined("γ AVG(amount + qty) → mean (Orders)"),
                Case.declined("ROLLING AVG(amount) OVER ALL ROWS SORT oid AS run (Orders)"),
                // And everything a declined division is inside. These four sat in the
                // maths group as folding, and passed for the same reason the σ above it
                // did — a whole quotient. `Round(amount / 3, 2)` is 33.33 in the engine
                // and 33 on H2, which divides two integers as integers before rounding.
                Case.declined("π oid, Int(amount / qty) → a (Orders)"),
                Case.declined("π oid, Ceil(amount / qty) → a (Orders)"),
                Case.declined("π oid, Round(amount / qty, 2) → a (Orders)"),
                Case.declined("π oid, Fix(-amount / qty) → a (Orders)"));
    }

    /**
     * What composes above a {@code γ}, which is the ordinary shape of a report: group,
     * filter the groups, rank them, take the top few.
     *
     * <p>These fold into further clauses of the <em>same</em> statement — a
     * {@code HAVING}, an {@code ORDER BY} over an aggregate, the {@code LIMIT} after it
     * — so what crosses the wire is the answer rather than every group. Each is checked
     * against an in-engine run of the same query, which is what says the clause means
     * what the operator means.
     */
    private static List<Case> aboveAggregation() {
        return cases(
                // σ above γ → HAVING, over a key and over an aggregate.
                "σ n > 1 (γ region, COUNT(*) → n (Orders))",
                "σ total ≥ 100 (γ region, SUM(amount) → total (Orders))",
                "σ qty = 2 (γ qty, COUNT(*) → n (Orders))",
                // HAVING is three-valued exactly as WHERE is, which is the shape of the
                // bug that made this suite exist: SUM over a group of all NULLs is NULL.
                "σ total = NULL (γ region, SUM(amount) → total (Orders))",
                "σ total ≠ NULL (γ region, SUM(amount) → total (Orders))",
                "σ ¬(total > 100) (γ region, SUM(amount) → total (Orders))",
                "σ total > 100 ∧ n > 1 (γ region, SUM(amount) → total, COUNT(*) → n (Orders))",
                "σ total ∈ {100, 350} (γ region, SUM(amount) → total (Orders))",
                // τ above γ → ORDER BY over the aggregate expression. `total` holds a
                // NULL group, so this also exercises the NULLs-last placement over an
                // aggregate rather than over a column.
                "τ total DESC, qty ASC (γ qty, SUM(amount) → total (Orders))",
                "τ total ASC, qty ASC (γ qty, SUM(amount) → total (Orders))",
                "τ n DESC, qty ASC (γ qty, COUNT(*) → n (Orders))",
                // λ above that → LIMIT.
                "λ 2 (τ n DESC, qty ASC (γ qty, COUNT(*) → n (Orders)))",
                "λ 1, 1 (τ n DESC, qty ASC (γ qty, COUNT(*) → n (Orders)))",
                // The three of them at once, which is the report.
                "λ 2 (τ total DESC, qty ASC (σ total ≠ NULL "
                        + "(γ qty, SUM(amount) → total (σ code ≠ NULL (Orders)))))");
    }

    /**
     * The same reports, ordered by or filtered on their string grouping key — the shapes
     * that need a connection whose strings need no collating, because a collated grouping
     * key is one MySQL will not match again in an ORDER BY or a HAVING.
     */
    private static List<Case> aboveAggregationOnStrings() {
        return List.of(
                // Ordering by a grouping key that had to be collated: MySQL matches an
                // ORDER BY term against the GROUP BY expression syntactically, and NULL
                // placement wraps the key, so it no longer matches. Folds on a connection
                // that needs no collating.
                Case.needsExactStrings("τ total DESC, region ASC (γ region, SUM(amount) → total (Orders))"),
                Case.needsExactStrings("λ 2 (τ n DESC, region ASC (γ region, COUNT(*) → n (Orders)))"),
                // A HAVING naming a collated grouping key: MySQL will not resolve a base
                // column inside an expression there, and the alias that would work is one
                // Postgres does not admit in HAVING. So this one still needs a connection
                // that needs no collating.
                Case.needsExactStrings("σ region = 'west' (γ region, COUNT(*) → n (Orders))"));
    }

    /** Wraps expressions only the dialects with temporal types are expected to fold. */
    private static List<Case> temporalCases(String... expressions) {
        List<Case> list = new ArrayList<>(expressions.length);
        for (String expression : expressions) {
            list.add(Case.temporal(expression));
        }
        return list;
    }

    /** Wraps expressions every dialect is expected to fold. */
    private static List<Case> cases(String... expressions) {
        List<Case> list = new ArrayList<>(expressions.length);
        for (String expression : expressions) {
            list.add(Case.of(expression));
        }
        return list;
    }

    /** Wraps expressions that fold only where the connection compares strings exactly. */
    private static List<Case> exactStringCases(String... expressions) {
        List<Case> list = new ArrayList<>(expressions.length);
        for (String expression : expressions) {
            list.add(Case.needsExactStrings(expression));
        }
        return list;
    }
}

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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.PushdownSpelling;
import com.darkcollective.relix.function.PushdownTarget;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The shapes a backend spelling takes, so a definition file states which SQL function
 * a call becomes rather than how a string is assembled.
 *
 * <h2>What a spelling is allowed to claim</h2>
 * That the backend computes <em>the same value</em>, not that it computes something
 * similar. A spelling is the only place in the engine where a query's answer can
 * depend on where the data lives, so the bar is equality on every input the column
 * can hold — and where that is not true the function keeps no spelling, because a
 * call evaluated in-engine is slower and a call evaluated wrongly is wrong.
 *
 * <p>That bar excludes more of the string library than it looks like it should, and
 * each exclusion is recorded on the function rather than here. The recurring reasons
 * are worth naming once: SQL case mapping is a property of the column's
 * <em>collation</em> where Java's is a property of the JDK, SQL string comparison is
 * likewise collation-dependent where the engine's is exact, and SQL {@code TRIM}
 * removes spaces where {@code String.strip} removes every Unicode whitespace
 * character.
 *
 * @see PushdownSpelling
 */
final class Spellings {

    /** The dialect names a per-dialect spelling switches on. */
    static final String POSTGRES = "postgres";

    /** @see #POSTGRES */
    static final String MYSQL = "mysql";

    /** @see #POSTGRES */
    static final String DUCKDB = "duckdb";

    /**
     * The unidentified backend, whose variant is the empty string.
     *
     * <p>Named so that a spelling confirmed against H2 — which is what GENERIC resolves
     * to in the agreement suite — can say so, rather than being offered to every dialect
     * because it was offered to no dialect in particular.
     */
    static final String GENERIC = "";

    private Spellings() {
    }

    /**
     * {@code NAME(a, b, …)} in every SQL dialect, for any argument count the engine
     * allows — the shape of a function whose name and semantics are the same
     * everywhere.
     *
     * @param function the SQL function name, spelled as it should appear
     * @return the spelling
     */
    static PushdownSpelling sql(String function) {
        return (target, arguments) -> target.isFamily(PushdownTarget.SQL)
                ? Optional.of(call(function, arguments))
                : Optional.empty();
    }

    /**
     * {@code NAME(a, b, …)} in every SQL dialect, for exactly {@code arity} arguments.
     *
     * <p>The count matters where a function of two forms is only equivalent in one of
     * them: declining the other leaves it in the engine rather than emitting a call the
     * backend would read differently.
     *
     * @param function the SQL function name
     * @param arity    the argument count this spelling is for
     * @return the spelling
     */
    static PushdownSpelling sql(String function, int arity) {
        return (target, arguments) -> target.isFamily(PushdownTarget.SQL)
                && arguments.size() == arity
                ? Optional.of(call(function, arguments))
                : Optional.empty();
    }



    /**
     * {@code NAME(a, b, …)} for exactly {@code arity} arguments, on the named dialects
     * only — the shape of a function whose meaning is the same everywhere it is
     * <em>offered</em>, and unconfirmed elsewhere.
     *
     * <p>The generic dialect is never among them, and this method is why the distinction
     * is worth having rather than assuming SQL is SQL: relix counts and slices a string
     * by code point and MySQL and Postgres agree, while H2 — which resolves to GENERIC —
     * counts UTF-16 code units exactly as Java does, so {@code CHAR_LENGTH} there is a
     * different function with the same name. Each name on the list was put there by
     * running that backend, not by reading about it.
     *
     * @param function the SQL function name
     * @param arity    the argument count this spelling is for
     * @param dialects the dialect variants confirmed to compute the same value
     * @return the spelling
     */
    static PushdownSpelling sqlOn(String function, int arity, String... dialects) {
        Set<String> confirmed = Set.of(dialects);
        return (target, arguments) -> target.isFamily(PushdownTarget.SQL)
                && arguments.size() == arity
                && confirmed.contains(target.variant())
                ? Optional.of(call(function, arguments))
                : Optional.empty();
    }

    /** The same, for a function whose argument count is a range. */
    static PushdownSpelling sqlOn(String function, String... dialects) {
        Set<String> confirmed = Set.of(dialects);
        return (target, arguments) -> target.isFamily(PushdownTarget.SQL)
                && confirmed.contains(target.variant())
                ? Optional.of(call(function, arguments))
                : Optional.empty();
    }

    private static String call(String function, List<String> arguments) {
        return function + "(" + String.join(", ", arguments) + ")";
    }
}

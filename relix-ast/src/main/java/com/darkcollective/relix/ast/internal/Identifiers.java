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
package com.darkcollective.relix.ast.internal;

import java.util.Locale;
import java.util.Set;

/**
 * Delimited-identifier rendering for round-trippable pretty-printing.
 *
 * <p>Relix reserves a fixed set of operator and keyword words (SELECT, SORT,
 * ORDER, GROUP, JOIN, …). A relation, column, or alias whose name collides with
 * one of them — or that is otherwise not identifier-shaped — cannot be written
 * bare: it must be wrapped in backticks so the lexer reads it as a plain name
 * rather than the operator, e.g. {@code `order`} references a relation named
 * {@code order}. {@link #render(String)} applies that wrapping <em>only</em> when
 * needed and leaves ordinary names untouched, so canonical pretty-printer output
 * is byte-for-byte unchanged for the overwhelmingly common case.
 *
 * <p>The reserved-word set is intentionally duplicated here because
 * {@code relix-ast} is the base module and cannot depend on {@code relix-parser}
 * (which owns the lexer). A guard test in {@code relix-parser}
 * ({@code DelimitedIdentifierLexerTest}) asserts this set stays a superset of the
 * lexer's live keyword table ({@code Lexer.reservedWords()}), so a newly added
 * keyword can never silently start printing un-delimited.
 *
 * <p>A qualified name ({@code Users.id}) is delimited per dotted segment
 * ({@code `Users`.id} only if {@code Users} needs it) — the common case. The one
 * ambiguity this cannot recover is a single delimited name that itself contains a
 * literal dot ({@code `weird.name`}): the AST stores it as the bare string
 * {@code weird.name}, indistinguishable from a qualified reference, so it
 * re-renders as two segments. Such names are vanishingly rare.
 */
public final class Identifiers {

    private Identifiers() {
    }

    /** The delimiter the lexer recognises; doubled inside a name to escape itself. */
    private static final char DELIM = '`';

    /**
     * Reserved words (lowercase) — every word the {@code relix-parser} lexer maps
     * to a non-identifier token. Kept in sync with {@code Lexer.reservedWords()}
     * by a guard test in {@code relix-parser}.
     */
    private static final Set<String> RESERVED = Set.of(
            "true", "false",
            "unit", "dee", "empty", "dum",
            "date", "time", "timestamp", "duration",
            "sum", "avg", "count", "min", "max", "collect", "argmax", "argmin",
            "asc", "desc",
            "project", "select", "rename", "group", "sort", "limit", "distinct",
            "unnest", "with", "ordinality", "closure", "rclosure", "cluster",
            "path", "hops", "as", "over", "fix", "forall", "sample", "seed",
            "solve", "optimize", "allocate", "maximize", "minimize", "subject",
            "to", "top", "per", "rows", "cover", "exact", "downsample", "by",
            "using", "for", "lateral", "rolling", "window", "sessionize", "gap",
            "trace", "via", "pivot", "unpivot", "tree", "order", "why",
            "join", "semi", "anti", "ljoin", "rjoin", "fjoin", "usemi", "asof",
            "ijoin", "within", "ties", "cross", "union", "uall", "ounion", "diff",
            "minus", "except", "inter", "intersect", "div", "symdiff", "compose",
            "and", "or", "not", "null", "is", "in", "like");

    /** Whether {@code word} is a reserved operator/keyword (case-insensitive). */
    public static boolean isReserved(String word) {
        return word != null && RESERVED.contains(word.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a single name segment cannot appear bare and must be backtick-
     * delimited: it is empty, not identifier-shaped, or a reserved word.
     */
    public static boolean needsDelimiting(String segment) {
        return segment == null
                || segment.isEmpty()
                || !isIdentifierShaped(segment)
                || isReserved(segment);
    }

    /**
     * Renders a (possibly dotted) name for pretty-printing, delimiting each
     * segment that needs it and leaving ordinary names unchanged.
     *
     * @param name the stored name (bare or dotted); {@code null} renders as
     *             {@code ``}
     * @return the name, ready to parse back to the identical name
     */
    public static String render(String name) {
        if (name == null || name.isEmpty()) {
            return delimit(name == null ? "" : name);
        }
        if (name.indexOf('.') < 0) {
            return needsDelimiting(name) ? delimit(name) : name;
        }
        String[] parts = name.split("\\.", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(needsDelimiting(parts[i]) ? delimit(parts[i]) : parts[i]);
        }
        return sb.toString();
    }

    private static boolean isIdentifierShaped(String s) {
        char first = s.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static String delimit(String segment) {
        StringBuilder sb = new StringBuilder(segment.length() + 2).append(DELIM);
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == DELIM) {
                sb.append(DELIM);   // escape a literal backtick by doubling
            }
            sb.append(c);
        }
        return sb.append(DELIM).toString();
    }
}

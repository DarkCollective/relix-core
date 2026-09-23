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
package com.darkcollective.relix.lang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The grammar published in {@code docs/reference/language/grammar.md}, read from its own
 * text and run as a recogniser — so that the page is a claim the build can check rather
 * than a description of the parser that is free to drift from it.
 *
 * <p>It reads the W3C-style EBNF the page is written in ({@code ::=}, {@code |},
 * {@code ?}, {@code *}, {@code +}, grouping, quoted literals and {@code a - b}), turns it
 * into plain productions, and decides membership with an Earley recogniser. Earley rather
 * than a recursive-descent reading of the rules, because the page is written for a reader
 * and is therefore ambiguous and left-recursive where that reads best; a recogniser that
 * needed the grammar massaged first would be checking the massaged copy.
 *
 * <p>The tokens are the grammar's own: a word literal matches a word in any letter case, a
 * symbol literal matches exactly, and an {@code UPPER_CASE} name that no rule defines is one
 * of the token classes the page's <em>Tokens</em> comment describes. The two raw inline-table
 * forms are cut out as single tokens here for the reason the real lexer cuts them out: a
 * table's cells are text, not tokens.
 */
final class EbnfGrammar {

    /** What a token is, as far as the grammar's terminals can tell. */
    enum Kind { WORD, DELIMITED, NUMBER, STRING_SQ, STRING_DQ, SYMBOL, MARKDOWN_TABLE, CSV_TABLE, ERROR }

    /**
     * One token of a script.
     *
     * @param touching for a {@code (}, whether it follows a name with nothing between
     */
    record Token(Kind kind, String text, boolean touching, int offset) {
        @Override
        public String toString() {
            return kind == Kind.SYMBOL || kind == Kind.WORD ? "'" + text + "'" : kind.name();
        }
    }

    /** The outcome of recognising one input. */
    record Result(boolean accepted, String diagnostic) {}

    /** The token classes the page defines in prose rather than as a rule. */
    private static final Map<String, Predicate<Token>> TOKEN_CLASSES = Map.of(
            "WORD", t -> t.kind() == Kind.WORD,
            "DELIMITED_IDENTIFIER", t -> t.kind() == Kind.DELIMITED,
            "NUMBER", t -> t.kind() == Kind.NUMBER,
            "INTEGER", t -> t.kind() == Kind.NUMBER && t.text().indexOf('.') < 0,
            "STRING", t -> t.kind() == Kind.STRING_SQ || t.kind() == Kind.STRING_DQ,
            "STRING_DQ", t -> t.kind() == Kind.STRING_DQ,
            "CALL_OPEN", t -> t.kind() == Kind.SYMBOL && t.text().equals("(") && t.touching(),
            "MARKDOWN_TABLE", t -> t.kind() == Kind.MARKDOWN_TABLE,
            "CSV_TABLE", t -> t.kind() == Kind.CSV_TABLE);

    private static final Pattern ASCII_WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    // ── the grammar as written ───────────────────────────────────────────────

    private sealed interface Expr permits Lit, Ref, Seq, Alt, Rep, Minus {}
    private record Lit(String text) implements Expr {}
    private record Ref(String name) implements Expr {}
    private record Seq(List<Expr> items) implements Expr {}
    private record Alt(List<Expr> options) implements Expr {}
    /** {@code ?}, {@code *} or {@code +}. */
    private record Rep(Expr body, char op) implements Expr {}
    private record Minus(Expr base, Expr excluded) implements Expr {}

    private final Map<String, Expr> rules;
    private final String start;

    // ── the grammar as productions ───────────────────────────────────────────

    /** A terminal: a predicate over one token, with the text a diagnostic shows. */
    private record Terminal(String label, Predicate<Token> test) {}

    private record Production(int lhs, Object[] rhs) {}

    private final List<Production> productions = new ArrayList<>();
    private final List<List<Integer>> byLhs = new ArrayList<>();
    private final Map<String, Integer> ruleIds = new HashMap<>();
    private final List<String> ntNames = new ArrayList<>();
    private boolean[] nullable;
    private final Set<String> symbols = new TreeSet<>(
            Comparator.comparingInt(String::length).reversed().thenComparing(s -> s));
    private int startId;

    private EbnfGrammar(Map<String, Expr> rules, String start) {
        this.rules = rules;
        this.start = start;
        compile();
    }

    /**
     * Reads a grammar.
     *
     * @param text  the EBNF, comments included
     * @param start the rule a whole input must match
     */
    static EbnfGrammar parse(String text, String start) {
        return new EbnfGrammar(new RuleReader(stripComments(text)).rules(), start);
    }

    /** The EBNF inside every {@code ```ebnf} fence of a Markdown page, in page order. */
    static String fromMarkdown(String markdown) {
        Matcher m = Pattern.compile("```ebnf\\s*\\n(.*?)```", Pattern.DOTALL).matcher(markdown);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            out.append(m.group(1)).append('\n');
        }
        return out.toString();
    }

    /** Every rule name, in the order the page defines them. */
    Set<String> ruleNames() {
        return rules.keySet();
    }

    /** The literals a rule's alternatives name, when it is a plain list of them. */
    Set<String> literalsOf(String rule) {
        Set<String> out = new LinkedHashSet<>();
        collectLiterals(rules.get(rule), out);
        return out;
    }

    /** Rule names used but never defined, and not a token class either. */
    Set<String> undefinedNames() {
        Set<String> used = new TreeSet<>();
        rules.values().forEach(e -> collectRefs(e, used));
        used.removeAll(rules.keySet());
        used.removeAll(TOKEN_CLASSES.keySet());
        return used;
    }

    /** Rules nothing reaches from the start rule. */
    Set<String> unreachableRules() {
        Set<String> seen = new HashSet<>();
        List<String> work = new ArrayList<>(List.of(start));
        while (!work.isEmpty()) {
            String name = work.removeLast();
            if (!seen.add(name) || !rules.containsKey(name)) {
                continue;
            }
            Set<String> refs = new HashSet<>();
            collectRefs(rules.get(name), refs);
            work.addAll(refs);
        }
        Set<String> out = new TreeSet<>(rules.keySet());
        out.removeAll(seen);
        return out;
    }

    private static void collectRefs(Expr e, Set<String> out) {
        switch (e) {
            case Lit ignored -> { }
            case Ref r -> out.add(r.name());
            case Seq s -> s.items().forEach(x -> collectRefs(x, out));
            case Alt a -> a.options().forEach(x -> collectRefs(x, out));
            case Rep r -> collectRefs(r.body(), out);
            case Minus m -> {
                collectRefs(m.base(), out);
                collectRefs(m.excluded(), out);
            }
        }
    }

    private void collectLiterals(Expr e, Set<String> out) {
        switch (e) {
            case Lit l -> out.add(l.text());
            case Ref r -> {
                if (rules.containsKey(r.name())) {
                    collectLiterals(rules.get(r.name()), out);
                }
            }
            case Seq s -> s.items().forEach(x -> collectLiterals(x, out));
            case Alt a -> a.options().forEach(x -> collectLiterals(x, out));
            case Rep r -> collectLiterals(r.body(), out);
            case Minus m -> collectLiterals(m.base(), out);
        }
    }

    // ── reading EBNF ─────────────────────────────────────────────────────────

    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("\"", i)) {
                int end = text.indexOf('"', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated literal at " + i);
                }
                out.append(text, i, end + 1);
                i = end + 1;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated comment at " + i);
                }
                out.append(' ');
                i = end + 2;
            } else {
                out.append(text.charAt(i++));
            }
        }
        return out.toString();
    }

    /** Recursive descent over the notation itself — which, unlike Relix, is tiny. */
    private static final class RuleReader {
        private static final Pattern LEX =
                Pattern.compile("\\s*(::=|\"[^\"]*\"|[A-Za-z_][A-Za-z0-9_]*|[|?*+()\\-])");
        private final List<String> toks = new ArrayList<>();
        private int pos;

        RuleReader(String text) {
            Matcher m = LEX.matcher(text);
            int at = 0;
            while (at < text.length()) {
                if (text.substring(at).isBlank()) {
                    break;
                }
                if (!m.find(at) || m.start() != at) {
                    throw new IllegalArgumentException("unreadable EBNF near: "
                            + text.substring(at, Math.min(text.length(), at + 40)).strip());
                }
                toks.add(m.group(1));
                at = m.end();
            }
        }

        Map<String, Expr> rules() {
            Map<String, Expr> out = new LinkedHashMap<>();
            while (pos < toks.size()) {
                String name = toks.get(pos++);
                expect("::=");
                if (out.put(name, alternatives()) != null) {
                    throw new IllegalArgumentException("rule defined twice: " + name);
                }
            }
            return out;
        }

        private Expr alternatives() {
            List<Expr> options = new ArrayList<>(List.of(sequence()));
            while (peek("|")) {
                pos++;
                options.add(sequence());
            }
            return options.size() == 1 ? options.getFirst() : new Alt(options);
        }

        private Expr sequence() {
            List<Expr> items = new ArrayList<>();
            while (startsPrimary()) {
                items.add(exclusion());
            }
            if (items.isEmpty()) {
                throw new IllegalArgumentException("empty alternative before token " + pos
                        + (pos < toks.size() ? " '" + toks.get(pos) + "'" : ""));
            }
            return items.size() == 1 ? items.getFirst() : new Seq(items);
        }

        private Expr exclusion() {
            Expr base = postfix();
            if (peek("-")) {
                pos++;
                return new Minus(base, postfix());
            }
            return base;
        }

        private Expr postfix() {
            Expr e = primary();
            while (peek("?") || peek("*") || peek("+")) {
                e = new Rep(e, toks.get(pos++).charAt(0));
            }
            return e;
        }

        private Expr primary() {
            String t = toks.get(pos++);
            if (t.equals("(")) {
                Expr inner = alternatives();
                expect(")");
                return inner;
            }
            if (t.startsWith("\"")) {
                return new Lit(t.substring(1, t.length() - 1));
            }
            return new Ref(t);
        }

        private boolean startsPrimary() {
            if (pos >= toks.size()) {
                return false;
            }
            String t = toks.get(pos);
            if (t.equals("(") || t.startsWith("\"")) {
                return true;
            }
            boolean isName = Character.isLetter(t.charAt(0)) || t.charAt(0) == '_';
            // A name followed by ::= starts the next rule rather than continuing this one.
            return isName && !(pos + 1 < toks.size() && toks.get(pos + 1).equals("::="));
        }

        private boolean peek(String t) {
            return pos < toks.size() && toks.get(pos).equals(t);
        }

        private void expect(String t) {
            if (!peek(t)) {
                throw new IllegalArgumentException("expected " + t + " at EBNF token " + pos
                        + (pos < toks.size() ? " but found '" + toks.get(pos) + "'" : ""));
            }
            pos++;
        }
    }

    // ── compiling to productions ─────────────────────────────────────────────

    private void compile() {
        if (!rules.containsKey(start)) {
            throw new IllegalArgumentException("no start rule " + start);
        }
        for (String name : rules.keySet()) {
            ruleId(name);
        }
        for (Map.Entry<String, Expr> rule : rules.entrySet()) {
            define(ruleIds.get(rule.getKey()), rule.getValue());
        }
        startId = newNonTerminal("<start>");
        addProduction(startId, new Object[]{ruleIds.get(start)});
        computeNullable();
    }

    private int ruleId(String name) {
        return ruleIds.computeIfAbsent(name, this::newNonTerminal);
    }

    private int newNonTerminal(String name) {
        ntNames.add(name);
        byLhs.add(new ArrayList<>());
        return ntNames.size() - 1;
    }

    private void addProduction(int lhs, Object[] rhs) {
        byLhs.get(lhs).add(productions.size());
        productions.add(new Production(lhs, rhs));
    }

    private void define(int nt, Expr body) {
        if (body instanceof Alt alt) {
            alt.options().forEach(o -> addProduction(nt, sequenceOf(o)));
        } else {
            addProduction(nt, sequenceOf(body));
        }
    }

    private Object[] sequenceOf(Expr e) {
        if (e instanceof Seq seq) {
            return seq.items().stream().map(this::symbolOf).toArray();
        }
        return new Object[]{symbolOf(e)};
    }

    /** An {@code Integer} is a non-terminal, a {@link Terminal} a terminal. */
    private Object symbolOf(Expr e) {
        return switch (e) {
            case Lit l -> literal(l.text());
            case Ref r -> {
                if (rules.containsKey(r.name())) {
                    yield ruleIds.get(r.name());
                }
                Predicate<Token> cls = TOKEN_CLASSES.get(r.name());
                if (cls == null) {
                    throw new IllegalArgumentException("undefined name: " + r.name());
                }
                yield new Terminal(r.name(), cls);
            }
            case Minus m -> {
                Predicate<Token> base = singleToken(m.base(), new HashSet<>());
                Predicate<Token> excluded = singleToken(m.excluded(), new HashSet<>());
                yield new Terminal(e.toString(), t -> base.test(t) && !excluded.test(t));
            }
            case Seq s -> fresh(s);
            case Alt a -> fresh(a);
            case Rep r -> {
                int nt = newNonTerminal("<" + r.op() + ">");
                Object body = symbolOf(r.body());
                switch (r.op()) {
                    case '?' -> {
                        addProduction(nt, new Object[]{});
                        addProduction(nt, new Object[]{body});
                    }
                    case '*' -> {
                        addProduction(nt, new Object[]{});
                        addProduction(nt, new Object[]{nt, body});
                    }
                    default -> {
                        addProduction(nt, new Object[]{body});
                        addProduction(nt, new Object[]{nt, body});
                    }
                }
                yield nt;
            }
        };
    }

    private int fresh(Expr e) {
        int nt = newNonTerminal("<group>");
        define(nt, e);
        return nt;
    }

    private Terminal literal(String text) {
        if (ASCII_WORD.matcher(text).matches()) {
            return new Terminal(text, t -> t.kind() == Kind.WORD && t.text().equalsIgnoreCase(text));
        }
        symbols.add(text);
        return new Terminal(text, t -> t.kind() == Kind.SYMBOL && t.text().equals(text));
    }

    /**
     * Reads an expression that can only ever match one token, which is what either side
     * of {@code a - b} has to be for the exclusion to mean "this token, but not that one".
     */
    private Predicate<Token> singleToken(Expr e, Set<String> visiting) {
        return switch (e) {
            case Lit l -> literal(l.text()).test();
            case Ref r -> {
                if (!rules.containsKey(r.name())) {
                    Predicate<Token> cls = TOKEN_CLASSES.get(r.name());
                    if (cls == null) {
                        throw new IllegalArgumentException("undefined name: " + r.name());
                    }
                    yield cls;
                }
                if (!visiting.add(r.name())) {
                    throw new IllegalArgumentException("recursive rule under '-': " + r.name());
                }
                yield singleToken(rules.get(r.name()), visiting);
            }
            case Alt a -> {
                List<Predicate<Token>> options = a.options().stream()
                        .map(o -> singleToken(o, new HashSet<>(visiting))).toList();
                yield t -> options.stream().anyMatch(p -> p.test(t));
            }
            case Minus m -> {
                Predicate<Token> base = singleToken(m.base(), new HashSet<>(visiting));
                Predicate<Token> excluded = singleToken(m.excluded(), new HashSet<>(visiting));
                yield t -> base.test(t) && !excluded.test(t);
            }
            default -> throw new IllegalArgumentException(
                    "'-' is only defined here between single-token expressions: " + e);
        };
    }

    private void computeNullable() {
        nullable = new boolean[ntNames.size()];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : productions) {
                if (nullable[p.lhs()]) {
                    continue;
                }
                boolean all = true;
                for (Object s : p.rhs()) {
                    if (!(s instanceof Integer nt && nullable[nt])) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    nullable[p.lhs()] = true;
                    changed = true;
                }
            }
        }
    }

    // ── recognising ──────────────────────────────────────────────────────────

    /** Whether the whole of {@code source} is a sentence of the grammar. */
    Result recognise(String source) {
        List<Token> tokens = tokenize(source);
        int n = tokens.size();
        List<List<long[]>> sets = new ArrayList<>();
        List<Set<Long>> seen = new ArrayList<>();
        List<Map<Integer, List<long[]>>> waiting = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            sets.add(new ArrayList<>());
            seen.add(new HashSet<>());
            waiting.add(new HashMap<>());
        }
        for (int p : byLhs.get(startId)) {
            add(sets, seen, waiting, 0, p, 0, 0);
        }
        int furthest = 0;
        for (int i = 0; i <= n; i++) {
            List<long[]> set = sets.get(i);
            if (!set.isEmpty()) {
                furthest = i;
            }
            for (int k = 0; k < set.size(); k++) {
                long[] item = set.get(k);
                int p = (int) item[0], dot = (int) item[1], origin = (int) item[2];
                Object[] rhs = productions.get(p).rhs();
                if (dot == rhs.length) {
                    int lhs = productions.get(p).lhs();
                    for (long[] parent : waiting.get(origin).getOrDefault(lhs, List.of())) {
                        add(sets, seen, waiting, i, (int) parent[0], (int) parent[1] + 1,
                                (int) parent[2]);
                    }
                } else if (rhs[dot] instanceof Integer nt) {
                    for (int q : byLhs.get(nt)) {
                        add(sets, seen, waiting, i, q, 0, i);
                    }
                    if (nullable[nt]) {
                        add(sets, seen, waiting, i, p, dot + 1, origin);
                    }
                } else if (i < n && ((Terminal) rhs[dot]).test().test(tokens.get(i))) {
                    add(sets, seen, waiting, i + 1, p, dot + 1, origin);
                }
            }
        }
        for (long[] item : sets.get(n)) {
            Production p = productions.get((int) item[0]);
            if (p.lhs() == startId && item[1] == p.rhs().length && item[2] == 0) {
                return new Result(true, "");
            }
        }
        return new Result(false, diagnose(tokens, sets.get(furthest), furthest));
    }

    private void add(List<List<long[]>> sets, List<Set<Long>> seen,
                     List<Map<Integer, List<long[]>>> waiting,
                     int at, int p, int dot, int origin) {
        long key = ((long) p << 40) | ((long) dot << 32) | origin;
        if (!seen.get(at).add(key)) {
            return;
        }
        long[] item = {p, dot, origin};
        sets.get(at).add(item);
        Object[] rhs = productions.get(p).rhs();
        if (dot < rhs.length && rhs[dot] instanceof Integer nt) {
            waiting.get(at).computeIfAbsent(nt, x -> new ArrayList<>()).add(item);
        }
    }

    private String diagnose(List<Token> tokens, List<long[]> set, int at) {
        Set<String> expected = new TreeSet<>();
        for (long[] item : set) {
            Object[] rhs = productions.get((int) item[0]).rhs();
            if (item[1] < rhs.length && rhs[(int) item[1]] instanceof Terminal t) {
                expected.add(t.label());
            }
        }
        String found = at < tokens.size() ? tokens.get(at).toString() : "end of input";
        return "stopped at token " + at + " (" + found + "); expected one of " + expected;
    }

    // ── tokens ───────────────────────────────────────────────────────────────

    /** Splits a script into the tokens the grammar's terminals are written over. */
    List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        boolean gap = true;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                gap = true;
                continue;
            }
            if (src.startsWith("--", i)) {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                gap = true;
                continue;
            }
            if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                if (end < 0) {
                    out.add(new Token(Kind.ERROR, "/*", false, i));
                    return out;
                }
                i = end + 2;
                gap = true;
                continue;
            }
            int startAt = i;
            Token previous = out.isEmpty() ? null : out.getLast();
            if (c == '[' && rawTableFollows(out)) {
                int end = closingBracket(src, i + 1);
                boolean csv = previous.kind() == Kind.WORD;
                String body = end < 0 ? "" : src.substring(i + 1, end);
                boolean ok = end >= 0 && (csv ? hasCsvHeader(body) : hasMarkdownHeader(body));
                out.add(new Token(ok ? (csv ? Kind.CSV_TABLE : Kind.MARKDOWN_TABLE) : Kind.ERROR,
                        body, false, startAt));
                if (!ok) {
                    return out;
                }
                i = end + 1;
            } else if (c == '\'' || c == '"') {
                int end = closingQuote(src, i);
                if (end < 0) {
                    out.add(new Token(Kind.ERROR, src.substring(i), false, startAt));
                    return out;
                }
                out.add(new Token(c == '"' ? Kind.STRING_DQ : Kind.STRING_SQ,
                        src.substring(i, end + 1), false, startAt));
                i = end + 1;
            } else if (c == '`') {
                int j = i + 1;
                StringBuilder name = new StringBuilder();
                boolean closed = false;
                while (j < src.length() && src.charAt(j) != '\n') {
                    if (src.charAt(j) == '`') {
                        if (src.startsWith("``", j)) {
                            name.append('`');
                            j += 2;
                            continue;
                        }
                        closed = true;
                        break;
                    }
                    name.append(src.charAt(j++));
                }
                if (!closed || name.isEmpty()) {
                    out.add(new Token(Kind.ERROR, "`", false, startAt));
                    return out;
                }
                out.add(new Token(Kind.DELIMITED, name.toString(), false, startAt));
                i = j + 1;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < src.length() && Character.isDigit(src.charAt(j))) {
                    j++;
                }
                if (j + 1 < src.length() && src.charAt(j) == '.' && Character.isDigit(src.charAt(j + 1))) {
                    j++;
                    while (j < src.length() && Character.isDigit(src.charAt(j))) {
                        j++;
                    }
                }
                out.add(new Token(Kind.NUMBER, src.substring(i, j), false, startAt));
                i = j;
            } else {
                String symbol = symbolAt(src, i);
                if (symbol != null) {
                    boolean touching = symbol.equals("(") && !gap && previous != null
                            && (previous.kind() == Kind.WORD || previous.kind() == Kind.DELIMITED);
                    out.add(new Token(Kind.SYMBOL, symbol, touching, startAt));
                    i += symbol.length();
                } else if (Character.isLetter(c) || c == '_') {
                    int j = i + 1;
                    while (j < src.length()
                            && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_')) {
                        j++;
                    }
                    out.add(new Token(Kind.WORD, src.substring(i, j), false, startAt));
                    i = j;
                } else {
                    out.add(new Token(Kind.ERROR, String.valueOf(c), false, startAt));
                    return out;
                }
            }
            gap = false;
        }
        return out;
    }

    private String symbolAt(String src, int i) {
        for (String s : symbols) {   // longest first
            if (src.startsWith(s, i)) {
                return s;
            }
        }
        return null;
    }

    /** {@code :=} then {@code [}, or {@code :=} {@code csv} then {@code [}. */
    private static boolean rawTableFollows(List<Token> out) {
        if (out.isEmpty()) {
            return false;
        }
        Token last = out.getLast();
        if (last.kind() == Kind.SYMBOL && last.text().equals(":=")) {
            return true;
        }
        return last.kind() == Kind.WORD && last.text().equalsIgnoreCase("csv") && out.size() >= 2
                && out.get(out.size() - 2).text().equals(":=");
    }

    /** The real lexer's rule: brackets nest, and a double-quoted string is skipped. */
    private static int closingBracket(String src, int from) {
        int depth = 1;
        for (int j = from; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return j;
            } else if (c == '"') {
                j++;
                while (j < src.length() && src.charAt(j) != '"') {
                    if (src.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
            }
        }
        return -1;
    }

    private static int closingQuote(String src, int open) {
        char q = src.charAt(open);
        for (int j = open + 1; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == q) {
                return j;
            }
        }
        return -1;
    }

    /** The real parser's rule: the first "|" line whose cells are not all dashes is the header. */
    private static boolean hasMarkdownHeader(String body) {
        return body.lines().map(String::strip)
                .filter(l -> l.startsWith("|"))
                .anyMatch(l -> {
                    String inner = l.substring(1);
                    if (inner.endsWith("|")) {
                        inner = inner.substring(0, inner.length() - 1);
                    }
                    for (String cell : inner.split("\\|", -1)) {
                        if (!cell.strip().matches("-+")) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    private static boolean hasCsvHeader(String body) {
        return body.lines().anyMatch(l -> !l.isBlank());
    }

    @Override
    public String toString() {
        return "EbnfGrammar[" + rules.size() + " rules, start=" + start + "]";
    }
}

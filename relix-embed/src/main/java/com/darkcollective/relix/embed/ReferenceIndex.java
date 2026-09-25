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
package com.darkcollective.relix.embed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The language reference bundled in this module: every page, and how to find it.
 *
 * <p>The pages are {@code docs/reference}, copied in at build time (the function pages
 * excepted: a function's page belongs to the library that offers the function, and is
 * served by {@code FunctionLibrary.documentation}). The source of truth stays in the
 * repository, where its gates run; this is the copy a program that has only the jar can
 * read. {@code ProjectDocsGuardTest} fails on a reference page missing from {@link #ALL}.
 */
final class ReferenceIndex {

    /** Where the pages are, among this module's resources. */
    static final String ROOT = "/com/darkcollective/relix/embed/reference/";

    /** A backticked span in a spellings-table cell. */
    private static final Pattern SPAN = Pattern.compile("`([^`]+)`");

    /** An operand placeholder, making a span a usage ({@code x IS NULL}), not a spelling. */
    private static final Pattern OPERAND = Pattern.compile("(^|\\s)x(\\s|$)");

    /** A cell boundary: a pipe the table has not escaped with a backslash. */
    private static final Pattern CELL = Pattern.compile("(?<!\\\\)\\|");

    // After the patterns, which building it reads.
    static final List<ReferencePage> ALL =
            withSpellings(buildAll(), read("language/spellings.md").orElseThrow());

    private ReferenceIndex() {
    }

    /** The markdown of the page at {@code path}, if the reference has one. */
    static Optional<String> read(String path) {
        if (path.contains("..") || !path.endsWith(".md")) {
            return Optional.empty();
        }
        try (InputStream in = ReferenceIndex.class.getResourceAsStream(ROOT + path)) {
            return in == null ? Optional.empty()
                    : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Adds to each page the spellings {@code language/spellings.md} gives its construct.
     *
     * <p>A page is keyed by its glyph and its names, but an operator whose ASCII keyword
     * is not its name — {@code ANTI}, {@code ><}, {@code LJOIN} — would otherwise be
     * found by nothing, and that keyword is the spelling recommended for generated code.
     * The spellings page is already the checked list of them, held to the parser, so the
     * keys are read from it rather than kept as a second list here.
     *
     * <p>Each table row names one construct in its first two columns, which a row either
     * resolves to one page — any of its spellings already being that page's key — or,
     * when its columns list several constructs side by side ({@code LJOIN / RJOIN /
     * FJOIN} against {@code |>< / ><| / |><|}), resolves pair by pair. A row naming no
     * page, such as the alias arrow, adds nothing. Rows are read in order, so an alias
     * row can resolve through a spelling an earlier row added.
     */
    static List<ReferencePage> withSpellings(List<ReferencePage> pages, String spellings) {
        Map<String, Integer> owner = new HashMap<>();
        List<Set<String>> keys = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            keys.add(new LinkedHashSet<>(pages.get(i).keys()));
            for (String key : pages.get(i).keys()) {
                owner.put(key, i);
            }
        }
        for (String line : spellings.split("\\R")) {
            if (!line.startsWith("|")) {
                continue;
            }
            String[] cells = CELL.split(line);
            List<String> left = spans(cells[1]);
            List<String> right = spans(cells[2]);
            List<String> all = new ArrayList<>(left);
            all.addAll(right);
            Set<Integer> named = owners(all, owner);
            if (named.size() == 1) {
                addKeys(named.iterator().next(), all, keys, owner);
            } else if (left.size() == right.size()) {
                for (int i = 0; i < left.size(); i++) {
                    List<String> pair = List.of(left.get(i), right.get(i));
                    Set<Integer> one = owners(pair, owner);
                    if (one.size() == 1) {
                        addKeys(one.iterator().next(), pair, keys, owner);
                    }
                }
            }
        }
        List<ReferencePage> result = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            ReferencePage page = pages.get(i);
            result.add(new ReferencePage(page.path(), page.category(), page.title(),
                    page.symbol(), page.summary(), List.copyOf(keys.get(i))));
        }
        return List.copyOf(result);
    }

    /**
     * The spellings a cell writes, lower-cased as keys are, with escaped pipes restored. A
     * span showing an operand is how the construct is used, not a way to look it up, and
     * is left out.
     */
    private static List<String> spans(String cell) {
        List<String> found = new ArrayList<>();
        Matcher m = SPAN.matcher(cell);
        while (m.find()) {
            String span = m.group(1).replace("\\|", "|").toLowerCase(Locale.ROOT);
            if (!OPERAND.matcher(span).find()) {
                found.add(span);
            }
        }
        return found;
    }

    private static Set<Integer> owners(List<String> spellings, Map<String, Integer> owner) {
        Set<Integer> found = new HashSet<>();
        for (String spelling : spellings) {
            Integer page = owner.get(spelling);
            if (page != null) {
                found.add(page);
            }
        }
        return found;
    }

    private static void addKeys(int page, List<String> spellings, List<Set<String>> keys,
                                Map<String, Integer> owner) {
        for (String spelling : spellings) {
            keys.get(page).add(spelling);
            owner.put(spelling, page);
        }
    }

    private static List<ReferencePage> buildAll() {
        return List.of(
            // ── Guides ───────────────────────────────────────────────────────
            e("getting-started.md",        "guide", "Getting started",   "getting-started",
              "Your first script — declare data, name a result, run it",
              "getting-started", "getting started", "start", "tutorial", "intro"),
            e("glossary.md",               "guide", "Glossary",          "glossary",
              "The vocabulary — relation, tuple, bag vs set, materialisation, pushdown",
              "glossary", "terms", "terminology", "vocabulary"),

            // ── Unary operators ──────────────────────────────────────────────
            e("operators/select.md",       "operator", "Selection",         "σ",
              "Filter rows by a condition",
              "σ", "select", "selection"),
            e("operators/project.md",      "operator", "Projection",        "π",
              "Keep or rename a subset of columns",
              "π", "project", "projection"),
            e("operators/rename.md",       "operator", "Rename",            "ρ",
              "Rename a relation or its columns",
              "ρ", "rename"),
            e("operators/group.md",        "operator", "Aggregation",       "γ",
              "Group rows and aggregate",
              "γ", "group", "groupby", "aggregation"),
            e("operators/sort.md",         "operator", "Sort",              "τ",
              "Sort rows by one or more columns",
              "τ", "sort", "order"),
            e("operators/limit.md",        "operator", "Limit",             "λ",
              "Restrict row count (TOP N / OFFSET)",
              "λ", "limit"),
            e("operators/distinct.md",     "operator", "Distinct",          "δ",
              "Eliminate duplicate rows",
              "δ", "distinct"),
            e("operators/unnest.md",       "operator", "Unnest",            "μ",
              "Expand an array column to rows",
              "μ", "unnest"),
            e("operators/forall.md",       "operator", "Universal",         "∀",
              "Universal quantification (∀ / FORALL)",
              "∀", "forall"),
            e("operators/rolling.md",      "operator", "Rolling",           "ROLLING",
              "Sliding / cumulative window aggregation",
              "rolling"),
            e("operators/window-ranking.md", "operator", "Window Ranking",  "WINDOW RANK",
              "ROW_NUMBER, RANK, DENSE_RANK, PERCENT_RANK",
              "window-ranking", "row_number", "rank", "dense_rank", "percent_rank", "ntile"),
            e("operators/window-offset.md",  "operator", "Window Offset",   "WINDOW LAG",
              "LAG, LEAD, FIRST_VALUE, LAST_VALUE",
              "window-offset", "lag", "lead", "first_value", "last_value"),
            e("operators/pivot.md",        "operator", "Pivot",             "PIVOT",
              "Rotate rows to columns",
              "pivot"),
            e("operators/unpivot.md",      "operator", "Unpivot",           "UNPIVOT",
              "Rotate columns to rows",
              "unpivot"),
            e("operators/tree.md",         "operator", "Tree",              "TREE",
              "Fold an adjacency relation into nested documents",
              "tree"),
            e("operators/why.md",          "operator", "Why",               "ω",
              "Reify a tuple's lineage provenance as a queryable column",
              "ω", "why", "provenance", "lineage"),

            // ── Joins ────────────────────────────────────────────────────────
            e("joins/natural-join.md",     "join", "Natural join",    "⋈",
              "Match on all shared columns",
              "⋈", "join", "natural-join", "natural join"),
            e("joins/theta-join.md",       "join", "Theta join",      "⨝",
              "Match on an explicit condition",
              "⨝", "theta-join", "theta join", "join on"),
            e("joins/left-outer-join.md",  "join", "Left outer join", "⟕",
              "Keep all left rows; NULLs for non-matches on right",
              "⟕", "left-outer-join", "left outer join", "left join"),
            e("joins/right-outer-join.md", "join", "Right outer join","⟖",
              "Keep all right rows; NULLs for non-matches on left",
              "⟖", "right-outer-join", "right outer join", "right join"),
            e("joins/full-outer-join.md",  "join", "Full outer join", "⟗",
              "Keep all rows from both sides",
              "⟗", "full-outer-join", "full outer join", "full join"),
            e("joins/semi-join.md",        "join", "Semi join",       "⋉",
              "Keep left rows that have at least one match on right",
              "⋉", "semi-join", "semi join"),
            e("joins/anti-join.md",        "join", "Anti join",       "▷",
              "Keep left rows with no match on right",
              "▷", "anti-join", "anti join"),
            e("joins/asof-join.md",        "join", "AS-OF join",      "AS OF",
              "Match the nearest row in time",
              "asof-join", "as-of join", "as of", "asof"),
            e("joins/interval-join.md",    "join", "Interval join",   "INTERVAL JOIN",
              "Match rows whose time intervals overlap (Allen-set predicates)",
              "interval-join", "interval join"),
            e("joins/lateral-join.md",     "join", "Lateral join",    "LATERAL",
              "Correlated subquery used as a table",
              "lateral-join", "lateral join", "lateral"),

            // ── Set operations ───────────────────────────────────────────────
            e("set-operations/cross.md",              "set", "Cross product",         "×",
              "Cartesian product of two relations",
              "×", "cross", "cross product"),
            e("set-operations/union.md",               "set", "Union",                "∪",
              "Set union — deduplicated rows from both sides",
              "∪", "union"),
            e("set-operations/union-all.md",           "set", "Union all",            "⊎",
              "Bag union — all rows from both sides, duplicates kept",
              "⊎", "union-all", "union all"),
            e("set-operations/outer-union.md",         "set", "Outer union",          "⊔",
              "Union of relations with different schemas",
              "⊔", "outer-union", "outer union"),
            e("set-operations/difference.md",          "set", "Difference",           "−",
              "Rows in the left side that are not in the right",
              "−", "-", "difference"),
            e("set-operations/intersection.md",        "set", "Intersection",         "∩",
              "Rows present in both sides",
              "∩", "intersection"),
            e("set-operations/division.md",            "set", "Division",             "÷",
              "Rows in left related to ALL rows in right",
              "÷", "division"),
            e("set-operations/symmetric-difference.md","set", "Symmetric difference", "∆",
              "Rows in one side but not both",
              "∆", "symmetric-difference", "symmetric difference"),
            e("set-operations/composition.md",         "set", "Composition",          "∘",
              "Single relational hop (pipe one relation through another)",
              "∘", "composition", "compose"),

            // ── Aggregates ───────────────────────────────────────────────────
            e("aggregates/sum.md",    "aggregate", "SUM",    "SUM",    "Sum of a numeric expression",    "sum"),
            e("aggregates/avg.md",    "aggregate", "AVG",    "AVG",    "Average of a numeric expression", "avg", "average"),
            e("aggregates/count.md",  "aggregate", "COUNT",  "COUNT",  "Count rows or non-null values",   "count"),
            e("aggregates/min.md",    "aggregate", "MIN",    "MIN",    "Minimum value",                   "min"),
            e("aggregates/max.md",    "aggregate", "MAX",    "MAX",    "Maximum value",                   "max"),
            e("aggregates/collect.md","aggregate", "COLLECT","COLLECT","Gather group values into an array","collect"),
            e("aggregates/argmax.md", "aggregate", "ARGMAX", "ARGMAX", "Yield value from row with max rank","argmax"),
            e("aggregates/argmin.md", "aggregate", "ARGMIN", "ARGMIN", "Yield value from row with min rank","argmin"),

            // ── Advanced operators ───────────────────────────────────────────
            e("advanced/closure.md",        "advanced", "Closure",      "CLOSURE",
              "Transitive / reflexive-transitive closure",
              "closure", "rclosure", "⁺", "transitive-closure"),
            e("advanced/cluster.md",        "advanced", "Cluster",      "CLUSTER",
              "Partition rows into clusters",
              "cluster"),
            e("advanced/cover.md",          "advanced", "Cover",        "COVER",
              "Combinatorial test-suite generation (t-way covering arrays)",
              "cover"),
            e("advanced/downsample.md",     "advanced", "Downsample",   "DOWNSAMPLE",
              "Reduce row density over a time / numeric axis",
              "downsample"),
            e("advanced/fix.md",            "advanced", "Fixpoint",     "FIX",
              "General monotone recursion (least fixpoint)",
              "fix", "fixpoint"),
            e("advanced/optimize.md",       "advanced", "Optimize",     "OPTIMIZE",
              "LP / ILP declarative optimisation",
              "optimize"),
            e("advanced/path.md",           "advanced", "Path",         "PATH",
              "Enumerate shortest / all paths in a graph",
              "path"),
            e("advanced/sample-bernoulli.md","advanced", "Sample",      "SAMPLE",
              "Bernoulli (row-probability) random sampling",
              "sample", "bernoulli"),
            e("advanced/sample-reservoir.md","advanced", "Reservoir",   "RESERVOIR",
              "Reservoir (fixed-size) random sampling",
              "reservoir", "reservoir-sample"),
            e("advanced/sessionize.md",     "advanced", "Sessionize",   "SESSIONIZE",
              "Gap-and-island / sessionisation",
              "sessionize"),
            e("advanced/solve.md",          "advanced", "Solve",        "SOLVE",
              "Constraint solving over a relation",
              "solve"),
            e("advanced/top.md",            "advanced", "Top-K",        "TOP",
              "Return top-K rows per group",
              "topk", "top-k"),
            e("advanced/trace.md",          "advanced", "Trace",        "TRACE",
              "Diagnostic operator — print rows as they pass through",
              "trace"),
            e("advanced/optimizer.md",      "advanced", "Optimizer",    "optimizer",
              "Logical rewrite rules — what fires, when, and how to see it",
              "optimizer", "optimiser", "rules", "rewrites"),
            e("advanced/pushdown.md",       "advanced", "Pushdown",     "pushdown",
              "What folds into a backend and what the engine computes",
              "pushdown", "push-down", "explain"),

            // ── Predicates ───────────────────────────────────────────────────
            e("predicates/comparison.md", "predicate", "Comparison",  "=",
              "Row comparisons: =, !=, <, >, ≤, ≥",
              "comparison", "=", "!=", "≠", "<", ">", "≤", "≥"),
            e("predicates/and.md",        "predicate", "AND",         "∧",
              "Logical conjunction",
              "and", "∧"),
            e("predicates/or.md",         "predicate", "OR",          "∨",
              "Logical disjunction",
              "or", "∨"),
            e("predicates/not.md",        "predicate", "NOT",         "¬",
              "Logical negation",
              "not", "¬"),
            e("predicates/in.md",         "predicate", "IN",          "∈",
              "Set-membership test",
              "in", "∈", "not in", "∉"),
            e("predicates/is-null.md",    "predicate", "IS NULL",     "IS NULL",
              "NULL / NOT NULL test",
              "is-null", "is null", "isnull", "null"),
            e("predicates/like.md",       "predicate", "LIKE",        "LIKE",
              "Pattern matching with wildcards",
              "like"),

            // ── Language constructs ──────────────────────────────────────────
            e("language/assignment.md",     "language", "Assignment",    ":=",
              "Name a relation expression",
              "assignment", ":="),
            e("language/source.md",         "language", "Source",        "source",
              "Declare an external data source",
              "source"),
            e("language/http-source.md",    "language", "HTTP source",   "http",
              "HTTP / JSON data source",
              "http-source", "http source", "http"),
            e("language/connection.md",     "language", "Connection",    "connection",
              "Declare a named database connection",
              "connection"),
            e("language/gedcom-source.md",  "language", "GEDCOM source", "gedcom",
              "Read a GEDCOM genealogy file as individuals and families",
              "gedcom-source", "gedcom source", "gedcom", "ged"),
            e("language/log-source.md",     "language", "Log source",    "log-source",
              "Read a web server access log as a typed relation",
              "log-source", "log source", "access log", "clf"),
            e("language/namespace.md",      "language", "Namespace",     "namespace",
              "Declare a namespace",
              "namespace"),
            e("language/import.md",         "language", "Import",        "import",
              "Import definitions from another script",
              "import"),
            e("language/inline-table.md",   "language", "Inline table",  "[| … |]",
              "Literal relation value written inline",
              "inline-table", "inline table"),
            e("language/def.md",            "language", "Def",           "def",
              "Define a scalar or relation function",
              "def"),
            e("language/def-relation.md",   "language", "Def relation",  "def relation",
              "Define a relation-valued (table-valued) function",
              "def-relation", "def relation", "tvf"),
            e("language/with-ordinality.md","language", "With ordinality","WITH ORDINALITY",
              "Attach a row number when unnesting",
              "with-ordinality", "with ordinality", "ordinality"),
            e("language/relate.md",         "language", "Relate",        "relate",
              "Declare a named, bounded relationship between two relations",
              "relate", "relationship", "relationships"),
            e("language/delimited-identifier.md", "language", "Delimited identifier", "`name`",
              "Backtick a name that collides with a reserved word",
              "delimited-identifier", "delimited identifier", "backtick", "quoted-identifier"),
            e("language/comments.md",      "language", "Comments",      "--",
              "Ignore text: -- to end of line, /* … */ for a block",
              "--", "comment", "comments", "/*"),
            e("language/spellings.md",     "language", "Operator spellings", "σ / SELECT",
              "Every operator's Unicode glyph and its ASCII equivalent",
              "spellings", "spelling", "ascii", "unicode", "glyph", "glyphs", "syntax"),
            e("language/grammar.md",       "language", "Grammar",      "EBNF",
              "The whole language as one EBNF grammar",
              "grammar", "ebnf", "bnf"),
            e("language/introspection-relations.md", "language", "Relation catalog",
              "relix.relations",
              "relix.relations — every relation the script can name, its kind, size and whether it ends",
              "introspection-relations", "relations", "relix.relations", "boundedness",
              "bounded", "unbounded", "row_count"),
            e("language/introspection-stdlib.md", "language", "Introspection stdlib", "relix.unused",
              "relix.unused / deps / impact / find / schema / cycles / funcs — introspection shipped as Relix",
              "introspection", "introspection-stdlib", "relix.unused", "relix.deps",
              "relix.impact", "relix.find", "relix.schema", "relix.cycles", "relix.funcs"),
            e("language/introspection-events.md", "language", "Observability feed", "relix.events",
              "relix.events / relix.rules — the previous run's engine decisions, as rows",
              "introspection-events", "events", "relix.events", "relix.rules", "feed"),
            e("language/introspection-version.md", "language", "Component inventory",
              "relix.version",
              "relix.version — the engine, the facade and every discovered provider, as rows",
              "introspection-version", "version", "relix.version", "components", "inventory"),
            e("language/introspection-keys.md", "language", "Candidate keys", "relix.keys",
              "relix.keys — the collected candidate keys of every relation, one row per key column",
              "introspection-keys", "keys", "relix.keys", "candidate key", "candidate keys"),
            e("language/introspection-catalog.md", "language", "Reserved namespace",
              "relix.catalog",
              "relix.catalog — the relix.* surface describing itself",
              "introspection-catalog", "catalog", "relix.catalog", "reserved namespace"),

            // ── Literals ─────────────────────────────────────────────────────
            e("literals/date-literal.md",      "literal", "Date",      "DATE '…'",      "Date literal",
              "date-literal", "date literal"),
            e("literals/time-literal.md",      "literal", "Time",      "TIME '…'",      "Time literal",
              "time-literal", "time literal"),
            e("literals/timestamp-literal.md", "literal", "Timestamp", "TIMESTAMP '…'", "Timestamp literal",
              "timestamp-literal", "timestamp literal"),
            e("literals/duration-literal.md",  "literal", "Duration",  "DURATION '…'",  "Duration literal",
              "duration-literal", "duration literal", "interval"),
            e("literals/array-construction.md","literal", "Array",     "[expr, …]",     "Array construction literal",
              "array-construction", "array construction", "array"),
            e("literals/struct-construction.md","literal","Struct",    "{name: expr}",  "Struct construction literal",
              "struct-construction", "struct construction", "struct"),
            e("literals/truth-relations.md",   "literal", "Truth relations", "UNIT",   "Zero-column truth relations",
              "truth-relations", "truth relation", "unit", "empty", "dee", "dum")
        );
    }

    /**
     * One page, keyed by its symbol as well as by {@code keys}.
     *
     * <p>The symbol is what an index prints beside a page's title, so it is the likeliest
     * thing to be looked up next; leaving it to each row to repeat it is how {@code TOP}
     * came to find the Limit page instead of Top-K.
     */
    private static ReferencePage e(String path, String category, String title, String symbol,
                                   String summary, String... keys) {
        Set<String> all = new LinkedHashSet<>();
        all.add(symbol.toLowerCase(Locale.ROOT));
        all.addAll(List.of(keys));
        return new ReferencePage(path, category, title, symbol, summary, List.copyOf(all));
    }
}

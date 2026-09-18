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
package com.darkcollective.relix.parser;

import com.darkcollective.relix.ast.RelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds every Unicode↔ASCII spelling {@code docs/reference/language/spellings.md} declares to the claim
 * the table makes: the alternatives in a row parse to the <em>identical</em> AST.
 *
 * <p>The claim was previously asserted one method at a time in
 * {@link AsciiAlternativesTest}, and 32 of the table's rows had one. Nothing related
 * the two, so the seven that did not — {@code μ}/{@code UNNEST},
 * {@code ⊔}/{@code OUNION}, {@code ≤}, {@code ≥}, {@code UNIT}/{@code DEE},
 * {@code EMPTY}/{@code DUM}, {@code COUNT(*)}/{@code COUNT(1)} — were invisible, and
 * a 39th row would have been too. That some of the seven are exercised elsewhere
 * (OUNION in relix-processor's execution suite) is the point rather than a mitigation:
 * without a correspondence between the table and the tests, finding the gaps means
 * auditing by hand, and the audit goes stale the day after it is done.
 *
 * <p>Two halves, and both are needed. The <b>rows</b> are read from the spellings page, so the
 * table is the source of truth for what must be covered. The <b>snippets</b> are
 * hand-written, because a glyph cannot be turned into a parseable expression by
 * machine — which is what makes the completeness guards below load-bearing: adding a
 * row to the spellings page fails this suite until the row has snippets, and
 * {@link #everyRowExercisesAllOfItsSpellings()} stops a snippet standing for a
 * spelling it does not contain.
 *
 * <p>{@link AsciiAlternativesTest} keeps its {@code *Keyword} tests, which assert the
 * concrete AST each ASCII form builds. Those are not this test: identical-to-the-glyph
 * and equal-to-a-hand-built-tree are different claims, and only the first is uniform
 * enough to be a table.
 */
@DisplayName("Operator spellings — every declared spelling parses to the same AST")
final class SyntaxSpellingTableTest extends ParserTestSupport {

    /**
     * One entry per spelling row, keyed by the row's first cell as written.
     * The value is the row's <em>equivalence groups</em>: within a group every snippet
     * must parse alike. Almost every row is a single group — a row is only split when
     * it declares more than one operator at once, as the outer-join alias row does
     * ({@code LJOIN}/{@code RJOIN}/{@code FJOIN} are three joins, not three spellings
     * of one) and as the SQL null-predicate row does, which pairs a form with its
     * negation.
     */
    private static final Map<String, List<List<String>>> SNIPPETS = new LinkedHashMap<>();

    private static void row(String key, String... snippets) {
        SNIPPETS.put(key, List.of(List.of(snippets)));
    }

    private static void rowOfGroups(String key, List<List<String>> groups) {
        SNIPPETS.put(key, groups);
    }

    static {
        // ── Unary operators ──────────────────────────────────────────────────
        row("`π`", "π name (Users)", "PROJECT name (Users)");
        row("`σ`", "σ age > 18 (Users)", "SELECT age > 18 (Users)");
        row("`ρ`", "ρ U (Users)", "RENAME U (Users)");
        row("`γ`", "γ dept, SUM(salary) -> total (Employees)",
                   "GROUP dept, SUM(salary) -> total (Employees)");
        row("`τ`", "τ name ASC (Users)", "SORT name ASC (Users)");
        row("`λ`", "λ 10 (Orders)", "LIMIT 10 (Orders)");
        row("`δ`", "δ (Users)", "DISTINCT (Users)");
        row("`μ`", "μ items (Orders)", "UNNEST items (Orders)");
        row("`∀`", "∀ cid : amount > 0 (Orders)", "FORALL cid : amount > 0 (Orders)");

        // ── Join operators ───────────────────────────────────────────────────
        row("`⋈`", "Users ⋈ Orders", "Users JOIN Orders");
        row("`⨝`", "Users ⨝ Users.id = Orders.user_id Orders",
                   "Users >< Users.id = Orders.user_id Orders");
        row("`⟕`", "Users ⟕ Users.id = Orders.user_id Orders",
                   "Users |>< Users.id = Orders.user_id Orders");
        row("`⟖`", "Users ⟖ Users.id = Orders.user_id Orders",
                   "Users ><| Users.id = Orders.user_id Orders");
        row("`⟗`", "Users ⟗ Users.id = Orders.user_id Orders",
                   "Users |><| Users.id = Orders.user_id Orders");
        row("`⋉`", "Users ⋉ Users.id = Orders.user_id Orders",
                   "Users SEMI Users.id = Orders.user_id Orders");
        row("`▷`", "Users ▷ Users.id = Orders.user_id Orders",
                   "Users ANTI Users.id = Orders.user_id Orders");

        // ── Set operators ────────────────────────────────────────────────────
        row("`×`", "A × B", "A CROSS B");
        row("`∪`", "A ∪ B", "A UNION B");
        row("`⊎`", "A ⊎ B", "A UALL B");
        row("`⊔`", "A ⊔ B", "A OUNION B");
        row("`−`", "A − B", "A DIFF B");
        row("`∆`", "A ∆ B", "A SYMDIFF B");
        row("`∩`", "A ∩ B", "A INTER B");
        row("`÷`", "A ÷ B", "A DIV B");
        row("`∘`", "A ∘ B", "A COMPOSE B");

        // ── Logical / comparison operators ───────────────────────────────────
        row("`∧`", "σ a = 1 ∧ b = 2 (R)", "SELECT a = 1 AND b = 2 (R)");
        row("`∨`", "σ a = 1 ∨ b = 2 (R)", "SELECT a = 1 OR b = 2 (R)");
        row("`¬`", "σ ¬(a = 1) (R)", "SELECT NOT (a = 1) (R)");
        row("`≠`", "σ status ≠ \"inactive\" (Users)", "SELECT status != \"inactive\" (Users)");
        row("`≤`", "σ age ≤ 18 (Users)", "SELECT age <= 18 (Users)");
        row("`≥`", "σ age ≥ 18 (Users)", "SELECT age >= 18 (Users)");

        // ── Miscellaneous ────────────────────────────────────────────────────
        row("`→`", "π name → full_name (Users)", "PROJECT name -> full_name (Users)");
        row("`↔`", "CLOSURE src ↔ dst (Edges)", "CLOSURE src <-> dst (Edges)");
        row("`⊥`", "σ a = ⊥ (R)", "SELECT a = NULL (R)");
        row("`∈`", "σ dept ∈ {\"hr\", \"eng\"} (Employees)",
                   "SELECT dept IN {\"hr\", \"eng\"} (Employees)");
        row("`∉`", "σ dept ∉ {\"hr\", \"eng\"} (Employees)",
                   "SELECT dept NOT IN {\"hr\", \"eng\"} (Employees)");

        // ── Nullary relation literals ────────────────────────────────────────
        row("`UNIT`", "UNIT", "DEE");
        row("`EMPTY`", "EMPTY", "DUM");

        // ── SQL-prior aliases ────────────────────────────────────────────────
        row("`INTERSECT`", "A ∩ B", "A INTER B", "A INTERSECT B");
        row("`MINUS`, `EXCEPT`", "A − B", "A DIFF B", "A MINUS B", "A EXCEPT B");
        // Two groups: the row pairs a predicate with its negation, so its five
        // spellings are two claims rather than one.
        rowOfGroups("`x IS NULL` / `x IS NOT NULL`", List.of(
                List.of("σ x IS NULL (R)", "σ x = NULL (R)", "σ x = ⊥ (R)"),
                List.of("σ x IS NOT NULL (R)", "σ x ≠ NULL (R)")));
        row("`ORDER`, `ORDER BY`", "τ name ASC (R)", "SORT name ASC (R)",
                "ORDER name ASC (R)", "ORDER BY name ASC (R)");
        row("`GROUP BY`", "γ d, SUM(s) -> t (R)", "GROUP d, SUM(s) -> t (R)",
                "GROUP BY d, SUM(s) -> t (R)");
        // Three joins, not three spellings of one.
        rowOfGroups("`LJOIN` / `RJOIN` / `FJOIN`", List.of(
                List.of("A ⟕ A.i = B.j B", "A |>< A.i = B.j B", "A LJOIN A.i = B.j B"),
                List.of("A ⟖ A.i = B.j B", "A ><| A.i = B.j B", "A RJOIN A.i = B.j B"),
                List.of("A ⟗ A.i = B.j B", "A |><| A.i = B.j B", "A FJOIN A.i = B.j B")));
        row("`COUNT(*)`", "γ d, COUNT(*) -> n (R)", "γ d, COUNT(1) -> n (R)");
    }

    // ── The claim ────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredRowKeys")
    @DisplayName("every spelling in a row parses to the same AST")
    void everySpellingInARowParsesAlike(String rowKey) {
        for (List<String> group : SNIPPETS.get(rowKey)) {
            RelNode reference = parse(group.get(0));
            for (String alternative : group.subList(1, group.size())) {
                assertParsesTo(alternative, reference);
            }
        }
    }

    // ── The completeness guards ──────────────────────────────────────────────

    @Test
    @DisplayName("every spelling row on the spellings page has snippets exercising it")
    void everyDeclaredRowIsCovered() {
        Set<String> uncovered = new LinkedHashSet<>(declaredRows().keySet());
        uncovered.removeAll(SNIPPETS.keySet());
        assertThat(uncovered)
                .as("spelling rows with no entry in this test — the table is the "
                        + "Unicode↔ASCII contract, so a new row needs a snippet pair proving "
                        + "the two forms parse alike")
                .isEmpty();
    }

    @Test
    @DisplayName("no snippet entry outlives the spelling row it stands for")
    void noSnippetEntryIsStale() {
        Set<String> stale = new LinkedHashSet<>(SNIPPETS.keySet());
        stale.removeAll(declaredRows().keySet());
        assertThat(stale)
                .as("entries in this test naming a spelling row that no longer exists — "
                        + "remove the entry, or restore the row")
                .isEmpty();
    }

    @Test
    @DisplayName("every spelling a row declares appears in one of its snippets")
    void everyRowExercisesAllOfItsSpellings() {
        Map<String, List<String>> declared = declaredRows();
        List<String> unexercised = new ArrayList<>();
        for (Map.Entry<String, List<List<String>>> entry : SNIPPETS.entrySet()) {
            List<String> spellings = declared.get(entry.getKey());
            if (spellings == null) {
                continue;   // reported by noSnippetEntryIsStale
            }
            String all = entry.getValue().stream().flatMap(List::stream)
                    .collect(java.util.stream.Collectors.joining("\n"));
            for (String spelling : spellings) {
                if (!all.contains(spelling)) {
                    unexercised.add(entry.getKey() + " → " + spelling);
                }
            }
        }
        assertThat(unexercised)
                .as("spellings declared by a spelling row that no snippet of that row "
                        + "contains — the row's claim is asserted about a form it never "
                        + "writes, which passes without testing anything")
                .isEmpty();
    }

    // ── Reading the table ────────────────────────────────────────────────────

    /**
     * The rows this suite has snippets for. A row the page declares but nothing here
     * covers is deliberately <em>not</em> in this list: it is
     * {@link #everyDeclaredRowIsCovered()}'s finding, and reporting it once — as a
     * missing entry rather than also as a row that would not parse — is what keeps the
     * failure legible.
     */
    static List<String> declaredRowKeys() {
        return declaredRows().keySet().stream().filter(SNIPPETS::containsKey).toList();
    }

    /**
     * The spelling rows of the spellings page, keyed by first cell, valued by every spelling
     * the row's first two columns write in code spans.
     *
     * <p>A table is a spelling table when its header's first two cells are one of the
     * three shapes the document uses; the header is matched rather than the section
     * heading so that a table moved between sections keeps its meaning.
     */
    private static Map<String, List<String>> declaredRows() {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        boolean inSpellingTable = false;
        for (String line : read(repoRoot().resolve("docs/reference/language/spellings.md")).lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("|")) {
                inSpellingTable = false;
                continue;
            }
            List<String> cells = cells(trimmed);
            if (cells.size() < 2) {
                continue;
            }
            if (isSpellingHeader(cells)) {
                inSpellingTable = true;
                continue;
            }
            if (!inSpellingTable || trimmed.startsWith("|-") || cells.get(0).startsWith("-")) {
                continue;
            }
            List<String> spellings = new ArrayList<>();
            spellings.addAll(codeSpans(cells.get(0)));
            spellings.addAll(codeSpans(cells.get(1)));
            if (!spellings.isEmpty()) {
                rows.put(cells.get(0), List.copyOf(spellings));
            }
        }
        assertThat(rows).as("spelling rows read from docs/reference/language/spellings.md").isNotEmpty();
        return rows;
    }

    private static boolean isSpellingHeader(List<String> cells) {
        String first = cells.get(0);
        String second = cells.get(1);
        return (first.equals("Unicode") && second.startsWith("ASCII"))
                || (first.equals("Keyword") && second.equals("Alias"))
                || (first.equals("SQL-prior form") && second.equals("Canonical form"));
    }

    private static List<String> cells(String tableRow) {
        String body = tableRow.substring(1, tableRow.endsWith("|")
                ? tableRow.length() - 1 : tableRow.length());
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                // `\|` inside a cell is a literal pipe — the |>< join spellings.
                cell.append(body.charAt(++i));
            } else if (c == '|') {
                cells.add(cell.toString().strip());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().strip());
        return cells;
    }

    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");

    private static List<String> codeSpans(String cell) {
        List<String> spans = new ArrayList<>();
        Matcher m = CODE_SPAN.matcher(cell);
        while (m.find()) {
            spans.add(m.group(1));
        }
        return spans;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repository root (settings.gradle) not found");
        }
        return dir;
    }
}
